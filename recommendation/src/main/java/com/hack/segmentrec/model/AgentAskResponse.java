package com.hack.segmentrec.model;

import com.fasterxml.jackson.databind.JsonNode;

public class AgentAskResponse {

    private String originalQuery;

    /** Name of the tool the router picked, e.g. {@code chat_recommend}. */
    private String tool;

    /** Arguments the model supplied for that tool. */
    private JsonNode toolArguments;

    /** How the tool was chosen, e.g. {@code gemini:gemini-2.5-flash} or {@code heuristic:gemini_error}. */
    private String routingStrategy;

    /** Natural-language summary of the result, null when summarization is off or failed. */
    private String reply;

    /** Raw payload returned by the invoked tool. */
    private Object result;

    public String getOriginalQuery() {
        return originalQuery;
    }

    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    public String getTool() {
        return tool;
    }

    public void setTool(String tool) {
        this.tool = tool;
    }

    public JsonNode getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(JsonNode toolArguments) {
        this.toolArguments = toolArguments;
    }

    public String getRoutingStrategy() {
        return routingStrategy;
    }

    public void setRoutingStrategy(String routingStrategy) {
        this.routingStrategy = routingStrategy;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }
}
