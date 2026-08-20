package com.hack.segmentrec.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "segment-rec")
public class SegmentRecProperties {

    private String artifactsPath = "../artifacts";
    private QueryUnderstanding queryUnderstanding = new QueryUnderstanding();
    private QueryEmbedding queryEmbedding = new QueryEmbedding();
    private Gemini gemini = new Gemini();
    private Agent agent = new Agent();
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

    public Gemini getGemini() {
        return gemini;
    }

    public void setGemini(Gemini gemini) {
        this.gemini = gemini;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
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

    /**
     * Google Gemini access, shared by query understanding and the tool router.
     *
     * <p>Calls Vertex AI {@code generateContent} and authenticates with Application Default
     * Credentials, so access is granted through IAM (the caller needs {@code roles/aiplatform.user}
     * on the project) rather than a shared API key.
     */
    public static class Gemini {
        /** Falls back to the ADC service account's own project when left blank. */
        private String projectId = "";

        /** {@code global} uses aiplatform.googleapis.com; any region uses REGION-aiplatform.googleapis.com. */
        private String location = "global";

        /** Overrides the derived host, e.g. for Private Service Connect endpoints. */
        private String endpoint = "";

        private String model = "gemini-3.5-flash";

        /**
         * Reasoning depth for Gemini 3 models: MINIMAL, LOW, MEDIUM or HIGH. The model default is
         * MEDIUM, whose reasoning pass dominates request latency; routing and extraction do not
         * benefit from it. Blank omits the field, which restores the model default. Note that
         * MINIMAL is unavailable on some Gemini 3 models, where LOW is the floor.
         */
        private String thinkingLevel = "MINIMAL";

        public String getThinkingLevel() {
            return thinkingLevel;
        }

        public void setThinkingLevel(String thinkingLevel) {
            this.thinkingLevel = thinkingLevel;
        }

        /**
         * Left unset on purpose: Gemini 3 models are tuned for their default temperature of 1.0
         * and can loop or reason worse when it is forced lower. Only set this for older models.
         */
        private Double temperature;
        private int timeoutSeconds = 30;

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Double getTemperature() {
            return temperature;
        }

        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /**
     * Gemini-driven routing over the tools exposed by this service.
     */
    public static class Agent {
        private boolean enabled = true;
        private boolean summarizeResult = true;
        private boolean allowAdminTools = false;
        private String fallbackTool = "chat_recommend";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isSummarizeResult() {
            return summarizeResult;
        }

        public void setSummarizeResult(boolean summarizeResult) {
            this.summarizeResult = summarizeResult;
        }

        /** Admin tools mutate server state, so an LLM may only reach them when explicitly opted in. */
        public boolean isAllowAdminTools() {
            return allowAdminTools;
        }

        public void setAllowAdminTools(boolean allowAdminTools) {
            this.allowAdminTools = allowAdminTools;
        }

        public String getFallbackTool() {
            return fallbackTool;
        }

        public void setFallbackTool(String fallbackTool) {
            this.fallbackTool = fallbackTool;
        }
    }

    public static class QueryEmbedding {
        private String pythonPath = "../training/.venv/bin/python";
        private String scriptPath = "../training/scripts/embed_texts.py";
        private String model = "sentence-transformers/all-MiniLM-L6-v2";
        private int topK = 8;
        private double minScore = 0.25;
        /** Covers the worker's one-off model load on the first request, not just an encode. */
        private int timeoutSeconds = 60;

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

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
