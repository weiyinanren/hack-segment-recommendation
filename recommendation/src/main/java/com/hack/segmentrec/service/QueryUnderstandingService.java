package com.hack.segmentrec.service;

import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.service.query.GeminiQueryUnderstandingProvider;
import com.hack.segmentrec.service.query.LlmQueryUnderstandingProvider;
import com.hack.segmentrec.service.query.QueryParseResult;
import com.hack.segmentrec.service.query.QueryUnderstandingProvider;
import com.hack.segmentrec.service.query.RuleQueryUnderstandingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    private final SegmentRecProperties properties;
    private final RuleQueryUnderstandingProvider ruleProvider;
    private final Map<String, QueryUnderstandingProvider> llmProviders = new HashMap<>();

    public QueryUnderstandingService(
            SegmentRecProperties properties,
            RuleQueryUnderstandingProvider ruleProvider,
            LlmQueryUnderstandingProvider llmProvider,
            GeminiQueryUnderstandingProvider geminiProvider
    ) {
        this.properties = properties;
        this.ruleProvider = ruleProvider;
        this.llmProviders.put("openai", llmProvider);
        this.llmProviders.put("llm", llmProvider);
        this.llmProviders.put("gemini", geminiProvider);
        this.llmProviders.put("google", geminiProvider);
    }

    public QueryParseResult parse(String query, String explicitIndustry, Set<String> knownIndustries) {
        String providerName = properties.getQueryUnderstanding().getProvider().trim().toLowerCase(Locale.ROOT);
        QueryUnderstandingProvider provider = llmProviders.get(providerName);
        if (provider == null) {
            return ruleProvider.parse(query, explicitIndustry, knownIndustries);
        }

        try {
            return provider.parse(query, explicitIndustry, knownIndustries);
        } catch (Exception e) {
            if (!properties.getQueryUnderstanding().isFallbackToRule()) {
                throw new IllegalStateException("LLM query understanding failed: " + e.getMessage(), e);
            }
            log.warn("LLM query understanding via {} failed, falling back to rules: {}", providerName, e.getMessage());
            QueryParseResult fallback = ruleProvider.parse(query, explicitIndustry, knownIndustries);
            fallback.setStrategy("rule_fallback_after_llm_error");
            return fallback;
        }
    }
}
