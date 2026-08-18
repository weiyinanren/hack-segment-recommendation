package com.hack.segmentrec.model;

import java.util.ArrayList;
import java.util.List;

public class ChatRecommendResponse {

    private ParsedQuery parsedQuery = new ParsedQuery();
    private List<SeedSegment> seedSegments = new ArrayList<>();
    private RecommendResponse recommendations;

    public ParsedQuery getParsedQuery() {
        return parsedQuery;
    }

    public void setParsedQuery(ParsedQuery parsedQuery) {
        this.parsedQuery = parsedQuery;
    }

    public List<SeedSegment> getSeedSegments() {
        return seedSegments;
    }

    public void setSeedSegments(List<SeedSegment> seedSegments) {
        this.seedSegments = seedSegments;
    }

    public RecommendResponse getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(RecommendResponse recommendations) {
        this.recommendations = recommendations;
    }

    public static class ParsedQuery {
        private String originalQuery;
        private String industry;
        private String concept;
        private List<String> excludeConcepts = new ArrayList<>();
        private String understandingStrategy;

        public String getOriginalQuery() {
            return originalQuery;
        }

        public void setOriginalQuery(String originalQuery) {
            this.originalQuery = originalQuery;
        }

        public String getIndustry() {
            return industry;
        }

        public void setIndustry(String industry) {
            this.industry = industry;
        }

        public String getConcept() {
            return concept;
        }

        public void setConcept(String concept) {
            this.concept = concept;
        }

        public List<String> getExcludeConcepts() {
            return excludeConcepts;
        }

        public void setExcludeConcepts(List<String> excludeConcepts) {
            this.excludeConcepts = excludeConcepts != null ? excludeConcepts : new ArrayList<>();
        }

        public String getUnderstandingStrategy() {
            return understandingStrategy;
        }

        public void setUnderstandingStrategy(String understandingStrategy) {
            this.understandingStrategy = understandingStrategy;
        }
    }

    public static class SeedSegment {
        private String segmentId;
        private String segmentName;
        private boolean inCatalog;
        private double score;
        private String retrievalReason;

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

        public boolean isInCatalog() {
            return inCatalog;
        }

        public void setInCatalog(boolean inCatalog) {
            this.inCatalog = inCatalog;
        }

        public double getScore() {
            return score;
        }

        public void setScore(double score) {
            this.score = score;
        }

        public String getRetrievalReason() {
            return retrievalReason;
        }

        public void setRetrievalReason(String retrievalReason) {
            this.retrievalReason = retrievalReason;
        }
    }
}
