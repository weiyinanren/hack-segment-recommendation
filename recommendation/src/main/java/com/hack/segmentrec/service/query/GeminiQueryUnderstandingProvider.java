package com.hack.segmentrec.service.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.service.llm.GeminiClient;
import com.hack.segmentrec.service.llm.GeminiSchema;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * Semantic parsing of a natural-language audience request via Gemini structured output.
 */
@Component
public class GeminiQueryUnderstandingProvider implements QueryUnderstandingProvider {

    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    public GeminiQueryUnderstandingProvider(GeminiClient geminiClient, ObjectMapper objectMapper) {
        this.geminiClient = geminiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }
        if (!geminiClient.isConfigured()) {
            throw new IllegalStateException("Vertex AI credentials are unavailable; "
                    + "configure Application Default Credentials and a project id");
        }

        try {
            JsonNode parsed = geminiClient.generateJson(
                    QueryUnderstandingSupport.buildSystemPrompt(knownIndustries, explicitIndustry),
                    original,
                    responseSchema()
            );
            return QueryUnderstandingSupport.toParseResult(
                    parsed,
                    original,
                    explicitIndustry,
                    knownIndustries,
                    "gemini:" + geminiClient.getModel()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Gemini query understanding failed: " + e.getMessage(), e);
        }
    }

    private JsonNode responseSchema() {
        ObjectNode schema = GeminiSchema.object(objectMapper);
        GeminiSchema.property(schema, "industry", "STRING",
                "Canonical industry from the known list, or an empty string when unknown.");
        GeminiSchema.property(schema, "concept", "STRING",
                "Short phrase describing the desired audience, without negated attributes.");
        GeminiSchema.stringArrayProperty(schema, "excludeConcepts",
                "Unwanted audience concepts, canonical English terms such as female, male, kids, senior.");
        return GeminiSchema.required(schema, "industry", "concept", "excludeConcepts");
    }
}
