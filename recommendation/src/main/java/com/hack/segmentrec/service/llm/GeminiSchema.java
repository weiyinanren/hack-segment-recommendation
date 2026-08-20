package com.hack.segmentrec.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builders for the OpenAPI-subset schema that Gemini uses for structured output
 * and function-call parameters.
 */
public final class GeminiSchema {

    private GeminiSchema() {
    }

    public static ObjectNode object(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "OBJECT");
        schema.putObject("properties");
        return schema;
    }

    public static ObjectNode property(ObjectNode object, String name, String type, String description) {
        ObjectNode property = ((ObjectNode) object.get("properties")).putObject(name);
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    public static ObjectNode stringArrayProperty(ObjectNode object, String name, String description) {
        ObjectNode property = property(object, name, "ARRAY", description);
        property.putObject("items").put("type", "STRING");
        return property;
    }

    public static ObjectNode required(ObjectNode object, String... names) {
        ArrayNode required = object.putArray("required");
        for (String name : names) {
            required.add(name);
        }
        return object;
    }
}
