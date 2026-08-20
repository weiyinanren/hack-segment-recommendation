package com.hack.segmentrec.service.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.model.ChatRecommendRequest;
import com.hack.segmentrec.service.ConversationalRecommendationService;
import com.hack.segmentrec.service.agent.SegmentTool;
import com.hack.segmentrec.service.agent.ToolArguments;
import com.hack.segmentrec.service.agent.ToolContext;
import com.hack.segmentrec.service.llm.GeminiSchema;
import org.springframework.stereotype.Component;

@Component
public class ChatRecommendTool implements SegmentTool {

    private final ConversationalRecommendationService conversationalRecommendationService;

    public ChatRecommendTool(ConversationalRecommendationService conversationalRecommendationService) {
        this.conversationalRecommendationService = conversationalRecommendationService;
    }

    @Override
    public String name() {
        return "chat_recommend";
    }

    @Override
    public String description() {
        return "Recommend audience segments from a free-form natural-language description of the wanted people, "
                + "for example \"推荐一些CPG行业的高价值人群，不要女性\" or \"retail beauty VIP shoppers\". "
                + "Parses the request into industry/concept/exclusions, retrieves seed segments by embedding "
                + "similarity, then ranks the final list. This is the default choice for any descriptive request.";
    }

    @Override
    public ObjectNode parameterSchema(ObjectMapper mapper) {
        ObjectNode schema = GeminiSchema.object(mapper);
        GeminiSchema.property(schema, "query", "STRING",
                "The audience description to search for. Keep the user's own wording unless it needs cleanup.");
        GeminiSchema.property(schema, "concept", "STRING",
                "The wanted audience as a short search phrase, stripped of pleasantries and of any "
                        + "exclusions, for example \"high income anti-aging skincare\". Always fill this in: "
                        + "it saves a second parse of the same sentence.");
        GeminiSchema.stringArrayProperty(schema, "excludeConcepts",
                "Audience concepts the user does NOT want, such as female, male, kids, senior.");
        GeminiSchema.property(schema, "industry", "STRING",
                "Industry to scope popularity signals to, empty when the user did not mention one.");
        GeminiSchema.property(schema, "topN", "INTEGER",
                "How many segments to return. Defaults to the caller's value when omitted.");
        GeminiSchema.property(schema, "expandBeyondCatalog", "BOOLEAN",
                "True only when the user explicitly wants segments outside their own catalog.");
        return GeminiSchema.required(schema, "query");
    }

    @Override
    public Object invoke(JsonNode arguments, ToolContext context) {
        ChatRecommendRequest request = new ChatRecommendRequest();
        request.setClientName(context.getClientName());
        request.setQuery(ToolArguments.text(arguments, "query", context.getOriginalQuery()));
        request.setConcept(ToolArguments.text(arguments, "concept", null));
        request.setExcludeConcepts(ToolArguments.stringList(arguments, "excludeConcepts"));
        request.setIndustry(ToolArguments.text(arguments, "industry", context.getIndustry()));
        request.setTopN(ToolArguments.integer(arguments, "topN", context.getTopN()));
        request.setExpandBeyondCatalog(
                ToolArguments.bool(arguments, "expandBeyondCatalog", context.isExpandBeyondCatalog())
        );
        return conversationalRecommendationService.recommend(request);
    }
}
