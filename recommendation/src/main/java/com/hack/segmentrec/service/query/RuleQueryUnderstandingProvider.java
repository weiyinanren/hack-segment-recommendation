package com.hack.segmentrec.service.query;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleQueryUnderstandingProvider implements QueryUnderstandingProvider {

    private static final Map<String, String> INDUSTRY_ALIASES = buildIndustryAliases();
    private static final Pattern[] EXCLUDE_PATTERNS = {
            Pattern.compile("不想要([^，,。.；;]+)"),
            Pattern.compile("不要([^，,。.；;]+)"),
            Pattern.compile("排除([^，,。.；;]+)"),
            Pattern.compile("别要([^，,。.；;]+)"),
            Pattern.compile("(?i)excluding\\s+([^,.]+)"),
            Pattern.compile("(?i)exclude\\s+([^,.]+)"),
            Pattern.compile("(?i)without\\s+([^,.]+)"),
            Pattern.compile("(?i)don't want\\s+([^,.]+)"),
            Pattern.compile("(?i)do not want\\s+([^,.]+)"),
            Pattern.compile("(?i)no\\s+(female|females|women|woman|male|males|men|kids|children|child)\\b")
    };

    @Override
    public QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries) {
        String original = query == null ? "" : query.trim();
        if (original.isEmpty()) {
            throw new IllegalArgumentException("query is required");
        }

        String industry = normalizeIndustry(explicitIndustry, knownIndustries);
        String strategy = "rule_explicit_industry";
        if (industry == null) {
            industry = inferIndustryFromQuery(original, knownIndustries);
            strategy = industry != null ? "rule_inferred_industry" : "rule_concept_only";
        }

        QueryParseResult result = new QueryParseResult();
        result.setOriginalQuery(original);
        result.setIndustry(industry);
        result.setExcludeConcepts(extractExclusions(original));
        result.setConcept(extractConcept(original, industry));
        result.setStrategy(strategy);
        return result;
    }

    static String normalizeIndustry(String explicit, Set<String> knownIndustries) {
        if (explicit == null || explicit.isBlank()) {
            return null;
        }
        String normalized = explicit.trim();
        for (String candidate : knownIndustries) {
            if (candidate.equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        String alias = INDUSTRY_ALIASES.get(normalized.toLowerCase(Locale.ROOT));
        if (alias == null) {
            return normalized;
        }
        for (String candidate : knownIndustries) {
            if (candidate.equalsIgnoreCase(alias)) {
                return candidate;
            }
        }
        return alias;
    }

    private static String inferIndustryFromQuery(String query, Set<String> knownIndustries) {
        String lower = query.toLowerCase(Locale.ROOT);
        for (String known : knownIndustries) {
            if (lower.contains(known.toLowerCase(Locale.ROOT))) {
                return known;
            }
        }
        for (Map.Entry<String, String> e : INDUSTRY_ALIASES.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                return normalizeIndustry(e.getValue(), knownIndustries);
            }
        }
        return null;
    }

    private static String extractConcept(String query, String industry) {
        String concept = query;
        if (industry != null && !industry.isBlank()) {
            concept = concept.replace(industry, " ");
            concept = concept.replace(industry.toLowerCase(Locale.ROOT), " ");
        }
        for (Pattern pattern : EXCLUDE_PATTERNS) {
            concept = pattern.matcher(concept).replaceAll(" ");
        }

        String[] fillerPhrases = {
                "你帮我推荐一些", "帮我推荐一些", "推荐一些", "推荐", "一些", "行业", "人群", "segments", "segment",
                "audience", "audiences"
        };
        for (String filler : fillerPhrases) {
            concept = concept.replace(filler, " ");
        }

        concept = concept.replaceAll("\\s+", " ").trim();
        if (concept.isEmpty()) {
            return query.trim();
        }
        return concept;
    }

    private static List<String> extractExclusions(String query) {
        List<String> out = new ArrayList<>();
        for (Pattern pattern : EXCLUDE_PATTERNS) {
            Matcher matcher = pattern.matcher(query);
            while (matcher.find()) {
                String raw = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                String value = canonicalizeExclusion(raw);
                if (!value.isEmpty() && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    private static String canonicalizeExclusion(String raw) {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.contains("女性") || text.contains("女人") || text.contains("女士")
                || text.contains("female") || text.contains("women") || text.contains("woman")) {
            return "female";
        }
        if (text.contains("男性") || text.contains("男人") || text.contains("male") || text.equals("men")) {
            return "male";
        }
        if (text.contains("小孩") || text.contains("儿童") || text.contains("孩子")
                || text.contains("kid") || text.contains("child")) {
            return "kids";
        }
        if (text.contains("老人") || text.contains("老年") || text.contains("senior") || text.contains("elder")) {
            return "senior";
        }
        return text.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fff]+", " ").trim();
    }

    private static Map<String, String> buildIndustryAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("cpg", "CPG");
        aliases.put("consumer packaged goods", "CPG");
        aliases.put("快消", "CPG");
        aliases.put("oem", "OEM");
        aliases.put("retail", "Retail");
        aliases.put("零售", "Retail");
        aliases.put("healthcheck", "HealthCheck");
        aliases.put("health check", "HealthCheck");
        aliases.put("medical checkup", "HealthCheck");
        aliases.put("checkup", "HealthCheck");
        aliases.put("体检", "HealthCheck");
        aliases.put("dining", "Dining");
        aliases.put("food", "Dining");
        aliases.put("restaurant", "Dining");
        aliases.put("餐饮", "Dining");
        return aliases;
    }
}
