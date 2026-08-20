package com.hack.segmentrec.service.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Defensive readers for LLM-produced arguments, which may omit fields or use the wrong JSON type.
 */
public final class ToolArguments {

    private ToolArguments() {
    }

    public static String text(JsonNode arguments, String field, String fallback) {
        JsonNode node = node(arguments, field);
        if (node == null || !node.isValueNode()) {
            return fallback;
        }
        String value = node.asText("").trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return fallback;
        }
        return value;
    }

    public static int integer(JsonNode arguments, String field, int fallback) {
        JsonNode node = node(arguments, field);
        if (node == null) {
            return fallback;
        }
        if (node.isNumber()) {
            return node.asInt(fallback);
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean bool(JsonNode arguments, String field, boolean fallback) {
        JsonNode node = node(arguments, field);
        if (node == null) {
            return fallback;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                return Boolean.parseBoolean(value);
            }
        }
        return fallback;
    }

    public static List<String> stringList(JsonNode arguments, String field) {
        List<String> out = new ArrayList<>();
        JsonNode node = node(arguments, field);
        if (node == null) {
            return out;
        }
        if (node.isTextual()) {
            addIfPresent(out, node.asText());
            return out;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item != null && item.isValueNode()) {
                    addIfPresent(out, item.asText());
                }
            }
        }
        return out;
    }

    /** Same as {@link #stringList}, but keeps the caller's values when the model supplied none. */
    public static List<String> stringList(JsonNode arguments, String field, List<String> fallback) {
        List<String> out = stringList(arguments, field);
        if (!out.isEmpty()) {
            return out;
        }
        return fallback == null ? new ArrayList<>() : new ArrayList<>(fallback);
    }

    private static JsonNode node(JsonNode arguments, String field) {
        if (arguments == null || !arguments.isObject()) {
            return null;
        }
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? null : node;
    }

    private static void addIfPresent(List<String> target, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!value.isEmpty() && !target.contains(value)) {
            target.add(value);
        }
    }
}
