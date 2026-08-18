package com.hack.segmentrec.service;

import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.service.query.LlmQueryUnderstandingProvider;
import com.hack.segmentrec.service.query.QueryParseResult;
import com.hack.segmentrec.service.query.RuleQueryUnderstandingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

@Service
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    private final SegmentRecProperties properties;
    private final RuleQueryUnderstandingProvider ruleProvider;
    private final LlmQueryUnderstandingProvider llmProvider;

    public QueryUnderstandingService(
            SegmentRecProperties properties,
            RuleQueryUnderstandingProvider ruleProvider,
            LlmQueryUnderstandingProvider llmProvider
    ) {
        this.properties = properties;
        this.ruleProvider = ruleProvider;
        this.llmProvider = llmProvider;
    }

    public QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries) {
        String provider = properties.getQueryUnderstanding().getProvider().trim().toLowerCase(Locale.ROOT);
        if ("openai".equals(provider) || "llm".equals(provider)) {
            try {
                return llmProvider.parse(query, explicitIndustry, knownIndustries);
            } catch (Exception e) {
                if (!properties.getQueryUnderstanding().isFallbackToRule()) {
                    throw new IllegalStateException("LLM query understanding failed: " + e.getMessage(), e);
                }
                log.warn("LLM query understanding failed, falling back to rules: {}", e.getMessage());
                QueryParseResult fallback = ruleProvider.parse(query, explicitIndustry, knownIndustries);
                fallback.setStrategy("rule_fallback_after_llm_error");
                return fallback;
            }
        }
        return ruleProvider.parse(query, explicitIndustry, knownIndustries);
    }
}
