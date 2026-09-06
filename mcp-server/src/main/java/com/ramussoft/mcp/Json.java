package com.ramussoft.mcp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;

/**
 * Reading tool arguments and writing tool results.
 *
 * <p>
 * Arguments arrive as a plain map of whatever the JSON contained. The SDK validates them
 * against each tool's declared schema before a handler ever runs - an agent that writes
 * {@code "limit": "50"} gets back {@code /limit: string found, integer expected}, which is
 * a better answer than quietly guessing what it meant. So the readers below are defensive
 * rather than lenient by design: they exist so that a handler never has to think about JSON
 * types, and so that a field the schema does not constrain still fails with a sentence
 * rather than a ClassCastException.
 */
final class Json {

    private final McpJsonMapper mapper;

    Json(McpJsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A tool's input schema, written as JSON because that is what it is. Building the same
     * thing out of nested maps in Java obscures the one thing a reader wants to check -
     * whether the schema an agent sees matches what the handler reads.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(String json) {
        try {
            return McpJsonDefaults.getMapper().readValue(json, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("bad tool schema: " + json, e);
        }
    }

    static String string(Map<String, Object> request, String name) {
        Object value = request == null ? null : request.get(name);
        if (value == null)
            throw new IllegalArgumentException("\"" + name + "\" is required.");
        return value.toString();
    }

    static int integer(Map<String, Object> request, String name, int fallback) {
        Object value = request == null ? null : request.get(name);
        if (value == null)
            return fallback;
        if (value instanceof Number)
            return ((Number) value).intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + name + "\" must be a number, got "
                    + value);
        }
    }

    static boolean bool(Map<String, Object> request, String name, boolean fallback) {
        Object value = request == null ? null : request.get(name);
        if (value == null)
            return fallback;
        if (value instanceof Boolean)
            return (Boolean) value;
        return Boolean.parseBoolean(value.toString().trim());
    }
}
