package com.hack.segmentrec.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.config.SegmentRecProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LlmQueryUnderstandingProvider implements QueryUnderstandingProvider {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final SegmentRecProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmQueryUnderstandingProvider(SegmentRecProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }

        SegmentRecProperties.QueryUnderstanding cfg = properties.getQueryUnderstanding();
        String apiKey = resolveApiKey(cfg);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("query-understanding API key is not configured");
        }

        try {
            String systemPrompt = QueryUnderstandingSupport.buildSystemPrompt(knownIndustries, explicitIndustry);
            String content = callChatCompletion(cfg, apiKey, systemPrompt, original);
            JsonNode parsed = parseJsonContent(content);
            return QueryUnderstandingSupport.toParseResult(
                    parsed,
                    original,
                    explicitIndustry,
                    knownIndustries,
                    "llm:" + cfg.getModel()
            );
        } catch (IOException e) {
            throw new IllegalStateException("LLM query understanding failed: " + e.getMessage(), e);
        }
    }

    private static String resolveApiKey(SegmentRecProperties.QueryUnderstanding cfg) {
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            return cfg.getApiKey();
        }
        String env = System.getenv("OPENAI_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getenv("SEGMENT_QUERY_LLM_API_KEY");
    }

    private String callChatCompletion(
            SegmentRecProperties.QueryUnderstanding cfg,
            String apiKey,
            String systemPrompt,
            String userQuery
    ) throws IOException {
        String baseUrl = cfg.getBaseUrl().replaceAll("/+$", "");
        String url = baseUrl + "/chat/completions";

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", cfg.getModel());
        root.put("temperature", cfg.getTemperature());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userQuery);
        if (cfg.isJsonResponse()) {
            ObjectNode responseFormat = root.putObject("response_format");
            responseFormat.put("type", "json_object");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("LLM HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode body = objectMapper.readTree(response.body());
            JsonNode contentNode = body.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode() || contentNode.asText().isBlank()) {
                throw new IOException("LLM response missing message content");
            }
            return contentNode.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM request interrupted", e);
        }
    }

    private JsonNode parseJsonContent(String content) throws IOException {
        String trimmed = content.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (IOException ignored) {
            Matcher matcher = JSON_BLOCK.matcher(trimmed);
            if (matcher.find()) {
                return objectMapper.readTree(matcher.group());
            }
            throw new IOException("Failed to parse LLM JSON: " + trimmed);
        }
    }
}
