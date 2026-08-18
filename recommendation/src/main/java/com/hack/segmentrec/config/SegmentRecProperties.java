package com.hack.segmentrec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "segment-rec")
public class SegmentRecProperties {

    private String artifactsPath = "../artifacts";
    private QueryUnderstanding queryUnderstanding = new QueryUnderstanding();
    private QueryEmbedding queryEmbedding = new QueryEmbedding();
    private Weights weights = new Weights();

    public String getArtifactsPath() {
        return artifactsPath;
    }

    public void setArtifactsPath(String artifactsPath) {
        this.artifactsPath = artifactsPath;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public QueryUnderstanding getQueryUnderstanding() {
        return queryUnderstanding;
    }

    public void setQueryUnderstanding(QueryUnderstanding queryUnderstanding) {
        this.queryUnderstanding = queryUnderstanding;
    }

    public QueryEmbedding getQueryEmbedding() {
        return queryEmbedding;
    }

    public void setQueryEmbedding(QueryEmbedding queryEmbedding) {
        this.queryEmbedding = queryEmbedding;
    }

    /**
     * score = wGlobal * global + wIndustry * industry + wClientPop * clientPop
     *       + wSim * sim + wEmb * emb
     */
    public static class Weights {
        private double globalPopularity = 0.15;
        private double industryPopularity = 0.25;
        private double clientPopularity = 0.15;
        private double similarity = 0.30;
        private double embedding = 0.12;
        private double nameSimilarity = 0.18;

        public double getGlobalPopularity() {
            return globalPopularity;
        }

        public void setGlobalPopularity(double globalPopularity) {
            this.globalPopularity = globalPopularity;
        }

        public double getIndustryPopularity() {
            return industryPopularity;
        }

        public void setIndustryPopularity(double industryPopularity) {
            this.industryPopularity = industryPopularity;
        }

        public double getClientPopularity() {
            return clientPopularity;
        }

        public void setClientPopularity(double clientPopularity) {
            this.clientPopularity = clientPopularity;
        }

        public double getSimilarity() {
            return similarity;
        }

        public void setSimilarity(double similarity) {
            this.similarity = similarity;
        }

        public double getEmbedding() {
            return embedding;
        }

        public void setEmbedding(double embedding) {
            this.embedding = embedding;
        }

        public double getNameSimilarity() {
            return nameSimilarity;
        }

        public void setNameSimilarity(double nameSimilarity) {
            this.nameSimilarity = nameSimilarity;
        }
    }

    /**
     * Natural-language query → industry + concept.
     * Default vendor: OpenAI Chat Completions ({@code gpt-4o-mini}).
     */
    public static class QueryUnderstanding {
        private String provider = "openai";
        private String apiKey = "";
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4o-mini";
        private double temperature = 0.0;
        private int timeoutSeconds = 30;
        private boolean jsonResponse = true;
        private boolean fallbackToRule = true;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public boolean isJsonResponse() {
            return jsonResponse;
        }

        public void setJsonResponse(boolean jsonResponse) {
            this.jsonResponse = jsonResponse;
        }

        public boolean isFallbackToRule() {
            return fallbackToRule;
        }

        public void setFallbackToRule(boolean fallbackToRule) {
            this.fallbackToRule = fallbackToRule;
        }
    }

    public static class QueryEmbedding {
        private String pythonPath = "../training/.venv/bin/python";
        private String scriptPath = "../training/scripts/embed_texts.py";
        private String model = "sentence-transformers/all-MiniLM-L6-v2";
        private int topK = 8;
        private double minScore = 0.25;

        public String getPythonPath() {
            return pythonPath;
        }

        public void setPythonPath(String pythonPath) {
            this.pythonPath = pythonPath;
        }

        public String getScriptPath() {
            return scriptPath;
        }

        public void setScriptPath(String scriptPath) {
            this.scriptPath = scriptPath;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getMinScore() {
            return minScore;
        }

        public void setMinScore(double minScore) {
            this.minScore = minScore;
        }
    }
}
