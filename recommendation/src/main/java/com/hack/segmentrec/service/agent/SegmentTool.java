package com.hack.segmentrec.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One capability of this service, described well enough for an LLM to choose it
 * and fill in its arguments.
 */
public interface SegmentTool {

    /** Stable identifier used as the function name in the LLM tool declaration. */
    String name();

    /** Tells the LLM when to pick this tool over the others. */
    String description();

    /** Argument schema, or null when the tool takes no arguments. */
    ObjectNode parameterSchema(ObjectMapper mapper);

    /** Admin tools mutate server state and stay hidden unless explicitly enabled. */
    default boolean isAdmin() {
        return false;
    }

    /**
     * @param arguments LLM-supplied arguments, possibly missing or partially filled
     * @param context   caller-owned values such as the tenant, which the LLM never chooses
     */
    Object invoke(JsonNode arguments, ToolContext context);
}
