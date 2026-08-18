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
import java.util.ArrayList;
import java.util.List;
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
            String systemPrompt = buildSystemPrompt(knownIndustries, explicitIndustry);
            String content = callChatCompletion(cfg, apiKey, systemPrompt, original);
            JsonNode parsed = parseJsonContent(content);

            String industry = resolveIndustry(
                    parsed.path("industry").asText(null),
                    explicitIndustry,
                    knownIndustries
            );
            String concept = parsed.path("concept").asText("").trim();
            if (concept.isEmpty()) {
                concept = original;
            }

            QueryParseResult result = new QueryParseResult();
            result.setOriginalQuery(original);
            result.setIndustry(industry);
            result.setConcept(concept);
            result.setExcludeConcepts(readStringList(parsed.path("excludeConcepts")));
            result.setStrategy("llm:" + cfg.getModel());
            return result;
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

    private static String buildSystemPrompt(Set<String> knownIndustries, String explicitIndustry) {
        List<String> industries = new ArrayList<>(knownIndustries);
        industries.sort(String::compareToIgnoreCase);
        String industryList = String.join(", ", industries);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You extract structured intent for audience segment recommendation.\n");
        prompt.append("Return JSON only with keys: industry, concept, excludeConcepts.\n");
        prompt.append("- industry: one canonical industry from the known list, or null if unknown.\n");
        prompt.append("- concept: short phrase describing the desired audience for semantic segment-name search.\n");
        prompt.append("  Do NOT put negated/unwanted attributes into concept.\n");
        prompt.append("- excludeConcepts: array of unwanted audience concepts. Empty array if none.\n");
        prompt.append("  Examples: \"不想要女性\" -> [\"female\"]; \"不要小孩\" -> [\"kids\"].\n");
        prompt.append("  Use canonical English terms when possible (female, male, kids, senior).\n");
        prompt.append("Known industries: ").append(industryList).append("\n");
        if (explicitIndustry != null && !explicitIndustry.isBlank()) {
            prompt.append("Caller already specified industry: ").append(explicitIndustry.trim()).append("\n");
            prompt.append("Prefer this industry unless the user clearly contradicts it.\n");
        }
        prompt.append("Examples:\n");
        prompt.append("Q: 推荐一些CPG行业的高价值人群 -> {\"industry\":\"CPG\",\"concept\":\"high value premium shoppers\",\"excludeConcepts\":[]}\n");
        prompt.append("Q: 快消里愿意多花钱的妈妈 -> {\"industry\":\"CPG\",\"concept\":\"moms willing to spend more\",\"excludeConcepts\":[]}\n");
        prompt.append("Q: 推荐CPG高价值人群，不想要女性 -> {\"industry\":\"CPG\",\"concept\":\"high value premium shoppers\",\"excludeConcepts\":[\"female\"]}\n");
        prompt.append("Q: retail beauty VIP, excluding kids -> {\"industry\":\"Retail\",\"concept\":\"beauty VIP loyalty\",\"excludeConcepts\":[\"kids\"]}\n");
        return prompt.toString();
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

    private String resolveIndustry(String llmIndustry, String explicitIndustry, Set<String> knownIndustries) {
        String fromExplicit = RuleQueryUnderstandingProvider.normalizeIndustry(explicitIndustry, knownIndustries);
        if (fromExplicit != null && !fromExplicit.isBlank()) {
            return fromExplicit;
        }
        if (llmIndustry == null || llmIndustry.isBlank() || "null".equalsIgnoreCase(llmIndustry)) {
            return null;
        }
        String normalized = RuleQueryUnderstandingProvider.normalizeIndustry(llmIndustry, knownIndustries);
        for (String known : knownIndustries) {
            if (known.equalsIgnoreCase(normalized)) {
                return known;
            }
        }
        return normalized;
    }

    private static List<String> readStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return out;
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            if (!value.isEmpty()) {
                out.add(value);
            }
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isTextual()) {
                    String value = item.asText().trim();
                    if (!value.isEmpty()) {
                        out.add(value);
                    }
                }
            }
        }
        return out;
    }
}
