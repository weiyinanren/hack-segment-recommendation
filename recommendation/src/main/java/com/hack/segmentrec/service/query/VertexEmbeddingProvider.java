package com.hack.segmentrec.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.service.llm.VertexAiCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Encodes queries with a Vertex AI embedding model over ADC, which keeps the service a plain
 * JVM process: no Python, no local model weights, and the same credentials Gemini already uses.
 *
 * <p>The artifacts must be built with this same model — see the mismatch check in
 * {@code ConceptRetrievalService}.
 */
@Component
public class VertexEmbeddingProvider implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(VertexEmbeddingProvider.class);
    private static final double[] EMPTY = new double[0];

    private final SegmentRecProperties properties;
    private final VertexAiCredentials credentials;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VertexEmbeddingProvider(
            SegmentRecProperties properties,
            VertexAiCredentials credentials,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String modelId() {
        return properties.getQueryEmbedding().getVertexModel();
    }

    @Override
    public double[] embed(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        SegmentRecProperties.QueryEmbedding cfg = properties.getQueryEmbedding();
        try {
            URI uri = predictUri(cfg);
            ObjectNode body = objectMapper.createObjectNode();
            body.putArray("instances").addObject().put("content", text);
            body.putObject("parameters")
                    .put("outputDimensionality", cfg.getVertexOutputDimensionality());

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
            for (Map.Entry<String, List<String>> header : credentials.requestMetadata(uri).entrySet()) {
                for (String value : header.getValue()) {
                    builder.header(header.getKey(), value);
                }
            }

            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Vertex embedding HTTP {}: {}", response.statusCode(), response.body());
                return EMPTY;
            }
            return parseVector(objectMapper.readTree(response.body()));
        } catch (IOException e) {
            log.warn("Vertex embedding failed ({}): {}", modelId(), e.getMessage());
            return EMPTY;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return EMPTY;
        }
    }

    private double[] parseVector(JsonNode root) {
        JsonNode values = root.path("predictions").path(0).path("embeddings").path("values");
        if (!values.isArray() || values.isEmpty()) {
            log.warn("Vertex embedding response held no values: {}", root);
            return EMPTY;
        }
        double[] vector = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i).asDouble();
        }
        return vector;
    }

    /**
     * {@code POST {host}/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:predict}
     */
    private URI predictUri(SegmentRecProperties.QueryEmbedding cfg) throws IOException {
        String location = cfg.getVertexLocation().trim();
        String host = "global".equalsIgnoreCase(location)
                ? "https://aiplatform.googleapis.com"
                : "https://" + location + "-aiplatform.googleapis.com";
        return URI.create(host
                + "/v1/projects/" + encode(credentials.getProjectId())
                + "/locations/" + encode(location)
                + "/publishers/google/models/" + encode(cfg.getVertexModel())
                + ":predict");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
