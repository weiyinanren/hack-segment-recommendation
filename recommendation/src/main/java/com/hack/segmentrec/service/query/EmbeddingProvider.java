package com.hack.segmentrec.service.query;

/**
 * Encodes a query into the same vector space as the segment name embeddings in the artifacts.
 *
 * <p>Implementations return an empty array rather than throwing when they cannot answer, so
 * retrieval can fall back to name matching instead of failing the request.
 */
public interface EmbeddingProvider {

    /** Returns the query vector, or an empty array when encoding is unavailable. */
    double[] embed(String text);

    /**
     * Model identifier as recorded in the artifacts, used to detect a serving/training mismatch
     * before comparing vectors that are not comparable.
     */
    String modelId();
}
