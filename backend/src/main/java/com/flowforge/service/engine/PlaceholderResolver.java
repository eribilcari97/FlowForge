package com.flowforge.service.engine;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

public class PlaceholderResolver {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(.*?)}}");
    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\{\\{([^{}]*)}}$");

    public record Context(JsonNode input, long executionId, int runNumber, Map<String, JsonNode> stepOutputs) {
    }

    public static class MissingValueException extends RuntimeException {

        public MissingValueException(String placeholder) {
            super("No value for {{" + placeholder + "}}");
        }
    }

    public JsonNode resolve(JsonNode config, Context context) {
        if (config.isString()) {
            return resolveString(config.stringValue(), context);
        }
        if (config.isObject()) {
            ObjectNode resolved = JsonNodeFactory.instance.objectNode();
            config.properties().forEach(entry -> resolved.set(entry.getKey(), resolve(entry.getValue(), context)));
            return resolved;
        }
        if (config.isArray()) {
            ArrayNode resolved = JsonNodeFactory.instance.arrayNode();
            config.forEach(element -> resolved.add(resolve(element, context)));
            return resolved;
        }
        return config;
    }

    private JsonNode resolveString(String text, Context context) {
        Matcher whole = WHOLE_PLACEHOLDER.matcher(text);
        if (whole.matches()) {
            return lookup(whole.group(1).trim(), context);
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            JsonNode value = lookup(matcher.group(1).trim(), context);
            String replacement = value.isValueNode() ? value.asString() : value.toString();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return JsonNodeFactory.instance.stringNode(result.toString());
    }

    private JsonNode lookup(String placeholder, Context context) {
        String[] parts = placeholder.split("\\.");
        JsonNode value = switch (parts[0]) {
            case "input" -> walk(context.input(), parts, 1);
            case "execution" -> executionValue(parts, context);
            case "steps" -> stepOutput(parts, context);
            default -> null;
        };
        if (value == null || value.isMissingNode()) {
            throw new MissingValueException(placeholder);
        }
        return value;
    }

    private static JsonNode executionValue(String[] parts, Context context) {
        if (parts.length != 2) {
            return null;
        }
        return switch (parts[1]) {
            case "id" -> JsonNodeFactory.instance.numberNode(context.executionId());
            case "runNumber" -> JsonNodeFactory.instance.numberNode(context.runNumber());
            default -> null;
        };
    }

    private static JsonNode stepOutput(String[] parts, Context context) {
        if (parts.length < 3 || !parts[2].equals("output")) {
            return null;
        }
        return walk(context.stepOutputs().get(parts[1]), parts, 3);
    }

    private static JsonNode walk(JsonNode node, String[] parts, int from) {
        JsonNode current = node;
        for (int i = from; i < parts.length && current != null; i++) {
            if (current.isArray() && parts[i].matches("\\d+")) {
                current = current.get(Integer.parseInt(parts[i]));
            } else {
                current = current.get(parts[i]);
            }
        }
        return current;
    }
}
