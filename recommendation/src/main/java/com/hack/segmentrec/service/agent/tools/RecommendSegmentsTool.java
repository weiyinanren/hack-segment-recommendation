package com.hack.segmentrec.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.model.RecommendRequest;
import com.hack.segmentrec.service.RankingService;
import com.hack.segmentrec.service.agent.SegmentTool;
import com.hack.segmentrec.service.agent.ToolArguments;
import com.hack.segmentrec.service.agent.ToolContext;
import com.hack.segmentrec.service.llm.GeminiSchema;
import org.springframework.stereotype.Component;

@Component
public class RecommendSegmentsTool implements SegmentTool {

    private final RankingService rankingService;

    public RecommendSegmentsTool(RankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Override
    public String name() {
        return "recommend_segments";
    }

    @Override
    public String description() {
        return "Rank segments from structured inputs rather than a description. Pick this when the user names "
                + "concrete segment IDs to find lookalikes for (\"和 SEG_123 类似的\"), or asks for the generally "
                + "popular segments of an industry with no audience description to interpret.";
    }

    @Override
    public ObjectNode parameterSchema(ObjectMapper mapper) {
        ObjectNode schema = GeminiSchema.object(mapper);
        GeminiSchema.property(schema, "industry", "STRING",
                "Industry to scope popularity signals to, empty when the user did not mention one.");
        GeminiSchema.stringArrayProperty(schema, "selectedSegmentIds",
                "Segment IDs the user already picked, used as similarity seeds. Empty for a cold start.");
        GeminiSchema.property(schema, "topN", "INTEGER",
                "How many segments to return. Defaults to the caller's value when omitted.");
        GeminiSchema.property(schema, "excludeSelected", "BOOLEAN",
                "Whether the seed segment IDs should be kept out of the results. Defaults to true.");
        GeminiSchema.property(schema, "expandBeyondCatalog", "BOOLEAN",
                "True only when the user explicitly wants segments outside their own catalog.");
        GeminiSchema.stringArrayProperty(schema, "excludeConcepts",
                "Unwanted audience concepts such as female, male, kids, senior.");
        return schema;
    }

    @Override
    public Object invoke(JsonNode arguments, ToolContext context) {
        RecommendRequest request = new RecommendRequest();
        request.setClientName(context.getClientName());
        request.setIndustry(ToolArguments.text(arguments, "industry", context.getIndustry()));
        request.setSelectedSegmentIds(
                ToolArguments.stringList(arguments, "selectedSegmentIds", context.getSelectedSegmentIds())
        );
        request.setTopN(ToolArguments.integer(arguments, "topN", context.getTopN()));
        request.setExcludeSelected(ToolArguments.bool(arguments, "excludeSelected", true));
        request.setExpandBeyondCatalog(
                ToolArguments.bool(arguments, "expandBeyondCatalog", context.isExpandBeyondCatalog())
        );
        request.setExcludeConcepts(ToolArguments.stringList(arguments, "excludeConcepts"));
        return rankingService.recommend(request);
    }
}
