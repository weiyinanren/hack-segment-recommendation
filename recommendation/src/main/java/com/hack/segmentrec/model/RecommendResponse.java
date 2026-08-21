package com.hack.segmentrec.model;

import java.util.ArrayList;
import java.util.List;

public class RecommendResponse {

    private String clientName;
    private String version;
    private String strategy;
    private boolean expandBeyondCatalog;
    private List<RecommendedItem> items = new ArrayList<>();

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public boolean isExpandBeyondCatalog() {
        return expandBeyondCatalog;
    }

    public void setExpandBeyondCatalog(boolean expandBeyondCatalog) {
        this.expandBeyondCatalog = expandBeyondCatalog;
    }

    public List<RecommendedItem> getItems() {
        return items;
    }

    public void setItems(List<RecommendedItem> items) {
        this.items = items;
    }

    public static class RecommendedItem {
        private String segmentId;
        private String segmentName;
        private double score;
        /** Whether this segment is in the requesting client's catalog. */
        private boolean inCatalog;
        private double globalPopularityScore;
        private double industryPopularityScore;
        private double clientPopularityScore;
        private double similarityScore;
        private double embeddingScore;
        private double nameSimilarityScore;

        public String getSegmentId() {
            return segmentId;
        }

        public void setSegmentId(String segmentId) {
            this.segmentId = segmentId;
        }

        public String getSegmentName() {
            return segmentName;
        }

        public void setSegmentName(String segmentName) {
            this.segmentName = segmentName;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public boolean isInCatalog() {
            return inCatalog;
        }

        public void setInCatalog(boolean inCatalog) {
            this.inCatalog = inCatalog;
        }

        public double getGlobalPopularityScore() {
            return globalPopularityScore;
        }

        public void setGlobalPopularityScore(double globalPopularityScore) {
            this.globalPopularityScore = globalPopularityScore;
        }

        public double getIndustryPopularityScore() {
            return industryPopularityScore;
        }

        public void setIndustryPopularityScore(double industryPopularityScore) {
            this.industryPopularityScore = industryPopularityScore;
        }

        public double getClientPopularityScore() {
            return clientPopularityScore;
        }

        public void setClientPopularityScore(double clientPopularityScore) {
            this.clientPopularityScore = clientPopularityScore;
        }

        public double getSimilarityScore() {
            return similarityScore;
        }

        public void setSimilarityScore(double similarityScore) {
            this.similarityScore = similarityScore;
        }

        public double getEmbeddingScore() {
            return embeddingScore;
        }

        public void setEmbeddingScore(double embeddingScore) {
            this.embeddingScore = embeddingScore;
        }

        public double getNameSimilarityScore() {
            return nameSimilarityScore;
        }

        public void setNameSimilarityScore(double nameSimilarityScore) {
            this.nameSimilarityScore = nameSimilarityScore;
        }
    }
}
