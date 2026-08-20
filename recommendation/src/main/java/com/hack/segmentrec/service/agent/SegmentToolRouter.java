package com.hack.segmentrec.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hack.segmentrec.config.SegmentRecProperties;
import com.hack.segmentrec.model.AgentAskRequest;
import com.hack.segmentrec.model.AgentAskResponse;
import com.hack.segmentrec.service.ArtifactStore;
import com.hack.segmentrec.service.llm.GeminiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lets Gemini decide which service capability answers a natural-language request,
 * then invokes it. Falls back to a fixed tool whenever Gemini is unavailable, so the
 * endpoint keeps working without an API key.
 */
@Service
public class SegmentToolRouter {

    private static final Logger log = LoggerFactory.getLogger(SegmentToolRouter.class);
    private static final int SUMMARY_INPUT_LIMIT = 6000;

    private final SegmentRecProperties properties;
    private final GeminiClient geminiClient;
    private final ArtifactStore artifactStore;
    private final ObjectMapper objectMapper;
    private final List<SegmentTool> tools;

    public SegmentToolRouter(
            SegmentRecProperties properties,
            GeminiClient geminiClient,
            ArtifactStore artifactStore,
            ObjectMapper objectMapper,
            List<SegmentTool> tools
    ) {
        this.properties = properties;
        this.geminiClient = geminiClient;
        this.artifactStore = artifactStore;
        this.objectMapper = objectMapper;
        this.tools = tools;
    }

    public AgentAskResponse ask(AgentAskRequest request) {
        if (request.getClientName() == null || request.getClientName().isBlank()) {
            throw new IllegalArgumentException("clientName is required");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        // Fail fast on an unknown tenant before spending an LLM call.
        artifactStore.requireClient(request.getClientName());

        String query = request.getQuery().trim();
        List<SegmentTool> available = availableTools();
        List<String> seeds = request.getSelectedSegmentIds();
        Selection selection = select(query, request.getIndustry(), seeds, available);

        ToolContext context = new ToolContext(
                request.getClientName(),
                query,
                request.getIndustry(),
                seeds,
                request.getTopN() <= 0 ? 10 : request.getTopN(),
                request.isExpandBeyondCatalog()
        );
        Object result = selection.tool.invoke(selection.arguments, context);

        AgentAskResponse response = new AgentAskResponse();
        response.setOriginalQuery(query);
        response.setTool(selection.tool.name());
        response.setToolArguments(selection.arguments);
        response.setRoutingStrategy(selection.strategy);
        response.setResult(result);
        response.setReply(summarize(query, selection.tool, result));
        return response;
    }

    /** Tool names the router may currently pick, useful for diagnostics. */
    public List<String> availableToolNames() {
        return availableTools().stream().map(SegmentTool::name).collect(Collectors.toList());
    }

    private List<SegmentTool> availableTools() {
        boolean allowAdmin = properties.getAgent().isAllowAdminTools();
        return tools.stream()
                .filter(tool -> allowAdmin || !tool.isAdmin())
                .collect(Collectors.toList());
    }

    private Selection select(
            String query,
            String explicitIndustry,
            List<String> seeds,
            List<SegmentTool> available
    ) {
        if (available.isEmpty()) {
            throw new IllegalStateException("no tools are available to answer the request");
        }
        if (!properties.getAgent().isEnabled()) {
            return fallback(available, seeds, "heuristic:agent_disabled");
        }
        if (!geminiClient.isConfigured()) {
            return fallback(available, seeds, "heuristic:gemini_unavailable");
        }

        try {
            GeminiClient.FunctionCall call = geminiClient.generateFunctionCall(
                    buildRoutingPrompt(explicitIndustry, seeds, available),
                    query,
                    declarations(available)
            );
            SegmentTool chosen = available.stream()
                    .filter(tool -> tool.name().equals(call.getName()))
                    .findFirst()
                    .orElse(null);
            if (chosen == null) {
                log.warn("Gemini picked unknown tool '{}', falling back", call.getName());
                return fallback(available, seeds, "heuristic:unknown_tool");
            }
            return new Selection(chosen, call.getArguments(), "gemini:" + geminiClient.getModel());
        } catch (Exception e) {
            log.warn("Gemini tool routing failed, falling back: {}", e.getMessage());
            return fallback(available, seeds, "heuristic:gemini_error");
        }
    }

    /**
     * Seeds already pin the request to a lookalike search, so route on them rather than the
     * configured default whenever the model is unavailable.
     */
    private Selection fallback(List<SegmentTool> available, List<String> seeds, String strategy) {
        String preferred = seeds != null && !seeds.isEmpty()
                ? "recommend_segments"
                : properties.getAgent().getFallbackTool();
        SegmentTool tool = available.stream()
                .filter(candidate -> candidate.name().equals(preferred))
                .findFirst()
                .orElse(available.get(0));
        String resolved = seeds != null && !seeds.isEmpty() ? strategy + "+seeds" : strategy;
        return new Selection(tool, objectMapper.createObjectNode(), resolved);
    }

    private List<ObjectNode> declarations(List<SegmentTool> available) {
        List<ObjectNode> declarations = new ArrayList<>();
        for (SegmentTool tool : available) {
            ObjectNode declaration = objectMapper.createObjectNode();
            declaration.put("name", tool.name());
            declaration.put("description", tool.description());
            ObjectNode parameters = tool.parameterSchema(objectMapper);
            if (parameters != null) {
                declaration.set("parameters", parameters);
            }
            declarations.add(declaration);
        }
        return declarations;
    }

    private String buildRoutingPrompt(String explicitIndustry, List<String> seeds, List<SegmentTool> available) {
        List<String> industries = new ArrayList<>(artifactStore.listIndustries());
        industries.sort(String::compareToIgnoreCase);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You route user requests for an audience segment recommendation service.\n");
        prompt.append("Call exactly one of the provided functions, choosing the one whose description best "
                + "matches the request, and fill in only the arguments the user actually implied.\n");
        prompt.append("The tenant is fixed by the caller, so never ask for or invent a client name.\n");
        prompt.append("Known industries: ").append(String.join(", ", industries)).append("\n");
        if (explicitIndustry != null && !explicitIndustry.isBlank()) {
            prompt.append("Caller already specified industry: ").append(explicitIndustry.trim()).append("\n");
        }
        if (seeds != null && !seeds.isEmpty()) {
            prompt.append("The user has already selected these segment ids in the UI: ")
                    .append(String.join(", ", seeds))
                    .append("\nTreat the request as a lookalike search seeded by them: prefer recommend_segments "
                            + "and pass these ids through as selectedSegmentIds unchanged.\n");
        }
        prompt.append("Available functions: ")
                .append(available.stream().map(SegmentTool::name).collect(Collectors.joining(", ")))
                .append("\n");
        prompt.append("When in doubt, prefer chat_recommend.\n");
        return prompt.toString();
    }

    private String summarize(String query, SegmentTool tool, Object result) {
        if (!properties.getAgent().isSummarizeResult() || !geminiClient.isConfigured()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(result);
            if (json.length() > SUMMARY_INPUT_LIMIT) {
                json = json.substring(0, SUMMARY_INPUT_LIMIT);
            }
            StringBuilder prompt = new StringBuilder();
            prompt.append("Summarize the result of an audience segment tool call for the user.\n");
            prompt.append("Answer in the same language as the user's request, in at most three sentences.\n");
            prompt.append("Mention the top segments by name or id when the result contains them. ");
            prompt.append("State plainly when the result is empty. Do not invent data.\n");
            prompt.append("Tool: ").append(tool.name()).append("\n");
            prompt.append("User request: ").append(query).append("\n");
            prompt.append("Result JSON: ").append(json);
            return geminiClient.generateText(null, prompt.toString());
        } catch (Exception e) {
            log.warn("Gemini result summarization failed: {}", e.getMessage());
            return null;
        }
    }

    private static final class Selection {
        private final SegmentTool tool;
        private final JsonNode arguments;
        private final String strategy;

        private Selection(SegmentTool tool, JsonNode arguments, String strategy) {
            this.tool = tool;
            this.arguments = arguments;
            this.strategy = strategy;
        }
    }
}
