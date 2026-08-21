package com.hack.segmentrec.service.query;

import com.hack.segmentrec.config.SegmentRecProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Locale;

/**
 * Selects how query text is turned into a vector. Vertex keeps the service a plain JVM process;
 * the local worker avoids leaving the machine but needs the training virtualenv on the host.
 *
 * <p>Whichever is chosen must match the model the artifacts were built with, since vectors from
 * different models are not comparable.
 */
@Component
public class QueryEmbeddingClient implements EmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(QueryEmbeddingClient.class);
    private static final String LOCAL = "local";

    private final SegmentRecProperties properties;
    private final VertexEmbeddingProvider vertexProvider;
    private final LocalEmbeddingProvider localProvider;

    public QueryEmbeddingClient(
            SegmentRecProperties properties,
            VertexEmbeddingProvider vertexProvider,
            LocalEmbeddingProvider localProvider
    ) {
        this.properties = properties;
        this.vertexProvider = vertexProvider;
        this.localProvider = localProvider;
    }

    @PostConstruct
    public void logSelection() {
        log.info("Query embedding provider '{}' using model '{}'", providerName(), modelId());
    }

    @Override
    public double[] embed(String text) {
        return active().embed(text);
    }

    @Override
    public String modelId() {
        return active().modelId();
    }

    private EmbeddingProvider active() {
        return LOCAL.equals(providerName()) ? localProvider : vertexProvider;
    }

    private String providerName() {
        String configured = properties.getQueryEmbedding().getProvider();
        if (configured == null || configured.isBlank()) {
            return "vertex";
        }
        return configured.trim().toLowerCase(Locale.ROOT);
    }
}
