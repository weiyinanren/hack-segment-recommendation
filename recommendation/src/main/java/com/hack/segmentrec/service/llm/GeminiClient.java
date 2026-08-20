package com.hack.segmentrec.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.config.SegmentRecProperties;
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
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin client over the Vertex AI Gemini {@code generateContent} API, authenticated with
 * Application Default Credentials so access is governed by IAM.
 *
 * <p>Supports the two modes this service needs: structured JSON output (semantic parsing)
 * and function calling (deciding which service capability to invoke).
 */
@Component
public class GeminiClient {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final SegmentRecProperties properties;
    private final VertexAiCredentials credentials;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiClient(
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

    public boolean isConfigured() {
        return credentials.isAvailable();
    }

    public String getModel() {
        return properties.getGemini().getModel();
    }

    /**
     * Ask Gemini for a JSON object constrained by {@code responseSchema} (may be null).
     */
    public JsonNode generateJson(String systemInstruction, String userText, JsonNode responseSchema)
            throws IOException {
        ObjectNode body = baseBody(systemInstruction, userText);
        ObjectNode generationConfig = (ObjectNode) body.get("generationConfig");
        generationConfig.put("responseMimeType", "application/json");
        if (responseSchema != null) {
            generationConfig.set("responseSchema", responseSchema);
        }

        String text = extractText(callGenerateContent(body));
        if (text.isBlank()) {
            throw new IOException("Gemini response contained no text");
        }
        return parseLenientJson(text);
    }

    /**
     * Ask Gemini to pick exactly one of {@code functionDeclarations} and fill in its arguments.
     */
    public FunctionCall generateFunctionCall(
            String systemInstruction,
            String userText,
            List<ObjectNode> functionDeclarations
    ) throws IOException {
        if (functionDeclarations == null || functionDeclarations.isEmpty()) {
            throw new IOException("no function declarations supplied");
        }

        ObjectNode body = baseBody(systemInstruction, userText);
        ArrayNode declarations = body.putArray("tools").addObject().putArray("functionDeclarations");
        functionDeclarations.forEach(declarations::add);
        body.putObject("toolConfig")
                .putObject("functionCallingConfig")
                .put("mode", "ANY");

        JsonNode root = callGenerateContent(body);
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            JsonNode call = part.path("functionCall");
            if (!call.isMissingNode() && call.hasNonNull("name")) {
                return new FunctionCall(call.path("name").asText(), call.path("args"));
            }
        }
        throw new IOException("Gemini did not return a function call");
    }

    public String generateText(String systemInstruction, String userText) throws IOException {
        return extractText(callGenerateContent(baseBody(systemInstruction, userText))).trim();
    }

    private ObjectNode baseBody(String systemInstruction, String userText) {
        ObjectNode body = objectMapper.createObjectNode();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.putObject("systemInstruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", systemInstruction);
        }
        body.putArray("contents")
                .addObject()
                .put("role", "user")
                .putArray("parts")
                .addObject()
                .put("text", userText);
        ObjectNode generationConfig = body.putObject("generationConfig");
        Double temperature = properties.getGemini().getTemperature();
        if (temperature != null) {
            generationConfig.put("temperature", temperature);
        }
        // Gemini 3 defaults to MEDIUM thinking. Routing, field extraction and summarizing need
        // none of it, and the reasoning pass dominates the request latency.
        String thinkingLevel = properties.getGemini().getThinkingLevel();
        if (thinkingLevel != null && !thinkingLevel.isBlank()) {
            generationConfig.putObject("thinkingConfig")
                    .put("thinkingLevel", thinkingLevel.trim().toUpperCase(Locale.ROOT));
        }
        return body;
    }

    private JsonNode callGenerateContent(ObjectNode body) throws IOException {
        SegmentRecProperties.Gemini cfg = properties.getGemini();
        URI uri = generateContentUri(cfg);

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

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new IOException("Vertex AI denied the request (HTTP " + response.statusCode()
                        + "). Check that the ADC principal has roles/aiplatform.user on project "
                        + credentials.getProjectId() + ": " + response.body());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Gemini HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Gemini request interrupted", e);
        }
    }

    /**
     * {@code POST {host}/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent}
     */
    private URI generateContentUri(SegmentRecProperties.Gemini cfg) throws IOException {
        String location = cfg.getLocation().trim();
        String host = cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()
                ? cfg.getEndpoint().trim()
                : ("global".equalsIgnoreCase(location)
                        ? "https://aiplatform.googleapis.com"
                        : "https://" + location + "-aiplatform.googleapis.com");

        String model = cfg.getModel();
        if (model.startsWith("models/")) {
            model = model.substring("models/".length());
        }

        return URI.create(host.replaceAll("/+$", "")
                + "/v1/projects/" + encode(credentials.getProjectId())
                + "/locations/" + encode(location)
                + "/publishers/google/models/" + encode(model)
                + ":generateContent");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String extractText(JsonNode root) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            JsonNode value = part.path("text");
            if (value.isTextual()) {
                text.append(value.asText());
            }
        }
        return text.toString();
    }

    private JsonNode parseLenientJson(String content) throws IOException {
        String trimmed = content.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (IOException ignored) {
            Matcher matcher = JSON_BLOCK.matcher(trimmed);
            if (matcher.find()) {
                return objectMapper.readTree(matcher.group());
            }
            throw new IOException("Failed to parse Gemini JSON: " + trimmed);
        }
    }

    public static final class FunctionCall {
        private final String name;
        private final JsonNode arguments;

        public FunctionCall(String name, JsonNode arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public String getName() {
            return name;
        }

        public JsonNode getArguments() {
            return arguments;
        }
    }
}
