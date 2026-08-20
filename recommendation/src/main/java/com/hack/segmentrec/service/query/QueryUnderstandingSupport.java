package com.hack.segmentrec.service.query;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Prompt text and response mapping shared by every LLM-backed
 * {@link QueryUnderstandingProvider}, so vendors only differ in transport.
 */
public final class QueryUnderstandingSupport {

    private QueryUnderstandingSupport() {
    }

    public static String buildSystemPrompt(Set<String> knownIndustries, String explicitIndustry) {
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

    public static QueryParseResult toParseResult(
            JsonNode parsed,
            String originalQuery,
            String explicitIndustry,
            Set<String> knownIndustries,
            String strategy
    ) {
        String concept = parsed.path("concept").asText("").trim();
        if (concept.isEmpty()) {
            concept = originalQuery;
        }

        QueryParseResult result = new QueryParseResult();
        result.setOriginalQuery(originalQuery);
        result.setIndustry(resolveIndustry(
                parsed.path("industry").asText(null),
                explicitIndustry,
                knownIndustries
        ));
        result.setConcept(concept);
        result.setExcludeConcepts(readStringList(parsed.path("excludeConcepts")));
        result.setStrategy(strategy);
        return result;
    }

    public static String resolveIndustry(String llmIndustry, String explicitIndustry, Set<String> knownIndustries) {
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

    public static List<String> readStringList(JsonNode node) {
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
