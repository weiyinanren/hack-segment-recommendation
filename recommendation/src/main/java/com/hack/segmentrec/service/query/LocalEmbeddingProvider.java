package com.hack.segmentrec.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.config.SegmentRecProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps one sentence-transformers process alive and talks to it over line-delimited JSON.
 *
 * <p>Loading the model costs seconds while encoding a query costs milliseconds, so the worker
 * is started once and reused. A single pipe pair cannot interleave callers, so requests are
 * serialized; at millisecond-scale encodes that is not the bottleneck.
 *
 * <p>Kept as an alternative to {@link VertexEmbeddingProvider} for environments without Google
 * Cloud access, at the cost of requiring the training virtualenv on the serving host.
 */
@Component
public class LocalEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingProvider.class);
    private static final double[] EMPTY = new double[0];

    private final SegmentRecProperties properties;
    private final ObjectMapper objectMapper;

    private final ReentrantLock lock = new ReentrantLock();
    private final ExecutorService reaper = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "query-embedding-io");
        thread.setDaemon(true);
        return thread;
    });

    private Process process;
    private BufferedWriter toWorker;
    private BufferedReader fromWorker;

    public LocalEmbeddingProvider(SegmentRecProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelId() {
        return properties.getQueryEmbedding().getModel();
    }

    /** Returns the query vector, or an empty array so callers can fall back to lexical retrieval. */
    @Override
    public double[] embed(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        lock.lock();
        try {
            double[] vector = exchange(text);
            if (vector.length > 0) {
                return vector;
            }
            // A dead or wedged worker leaves the pipes unusable; rebuild once and retry.
            log.warn("Query embedding worker did not answer, restarting it and retrying once");
            stopWorker();
            return exchange(text);
        } finally {
            lock.unlock();
        }
    }

    private double[] exchange(String text) {
        try {
            ensureWorker();
        } catch (IOException e) {
            log.warn("Query embedding worker could not be started ({}): {}",
                    properties.getQueryEmbedding().getPythonPath(), e.getMessage());
            return EMPTY;
        }

        SegmentRecProperties.QueryEmbedding cfg = properties.getQueryEmbedding();
        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.set("texts", objectMapper.valueToTree(List.of(text)));
            payload.put("model", cfg.getModel());
            toWorker.write(objectMapper.writeValueAsString(payload));
            toWorker.write("\n");
            toWorker.flush();

            String line = readLine(cfg.getTimeoutSeconds());
            if (line == null) {
                return EMPTY;
            }
            JsonNode root = objectMapper.readTree(line);
            if (root.hasNonNull("error")) {
                log.warn("Query embedding worker reported: {}", root.get("error").asText());
                return EMPTY;
            }
            JsonNode rows = root.path("embeddings");
            if (!rows.isArray() || rows.isEmpty() || !rows.get(0).isArray()) {
                return EMPTY;
            }
            JsonNode row = rows.get(0);
            double[] vector = new double[row.size()];
            for (int i = 0; i < row.size(); i++) {
                vector[i] = row.get(i).asDouble();
            }
            return vector;
        } catch (IOException e) {
            log.warn("Query embedding exchange failed: {}", e.getMessage());
            stopWorker();
            return EMPTY;
        }
    }

    /** Pipes have no read timeout, so bound the wait rather than block a request thread forever. */
    private String readLine(int timeoutSeconds) {
        Future<String> pending = reaper.submit(() -> fromWorker.readLine());
        try {
            return pending.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            log.warn("Query embedding worker timed out after {}s", timeoutSeconds);
            stopWorker();
            return null;
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Query embedding read failed: {}", e.getMessage());
            stopWorker();
            return null;
        }
    }

    private void ensureWorker() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        SegmentRecProperties.QueryEmbedding cfg = properties.getQueryEmbedding();
        ProcessBuilder pb = new ProcessBuilder(cfg.getPythonPath(), cfg.getScriptPath());
        // The model is cached locally; a Hub reachability check only adds latency and
        // failure modes. Progress bars would otherwise land on stderr on every load.
        pb.environment().put("HF_HUB_OFFLINE", "1");
        pb.environment().put("TRANSFORMERS_OFFLINE", "1");
        pb.environment().put("HF_HUB_DISABLE_PROGRESS_BARS", "1");

        Process started = pb.start();
        this.process = started;
        this.toWorker = new BufferedWriter(
                new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
        this.fromWorker = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
        drainStderr(started);
        log.info("Query embedding worker started (pid={}, model={})", started.pid(), cfg.getModel());
    }

    /** stderr must be consumed or the worker blocks once the pipe buffer fills. */
    private void drainStderr(Process started) {
        reaper.submit(() -> {
            try (InputStream es = started.getErrorStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(es, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("query-embedding worker: {}", line);
                }
            } catch (IOException ignored) {
                // The worker is gone; ensureWorker() will rebuild it on the next call.
            }
        });
    }

    @PreDestroy
    public synchronized void stopWorker() {
        if (toWorker != null) {
            try {
                // Closing stdin ends the worker's read loop, letting it exit on its own.
                toWorker.close();
            } catch (IOException ignored) {
                // Falling through to destroy() below.
            }
            toWorker = null;
        }
        if (fromWorker != null) {
            try {
                fromWorker.close();
            } catch (IOException ignored) {
                // Nothing left to read.
            }
            fromWorker = null;
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }
}
