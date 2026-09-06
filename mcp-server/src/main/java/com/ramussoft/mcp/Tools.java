package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Element;
import com.ramussoft.common.Qualifier;

/**
 * The tools this server offers, and the translation from Ramus objects to JSON.
 *
 * <p>
 * Two things shape everything here. First, a tool's description is the only documentation an
 * agent ever reads, so the descriptions say what the thing IS in the model's own vocabulary
 * rather than restating the method name. Second, a model can be large - tens of thousands of
 * elements - so anything that lists is paged, and no tool ever returns the whole file.
 */
final class Tools {

    /** Nothing returns more than this at once, however large the model. */
    private static final int MAX_PAGE = 200;

    static void registerAll(McpSyncServer server, Workspace workspace, McpJsonMapper mapper) {
        Json json = new Json(mapper);

        addTool(server, json, "list_catalogs",
                "Lists the catalogs in this model. A catalog (Ramus calls it a classifier) "
                        + "is a named table of elements - Documents, Roles, Equipment and so "
                        + "on - and each has its own set of typed attributes. Returns the id "
                        + "and name of each, how many elements it holds, and its attributes "
                        + "with their types. Call this first: every other tool takes a "
                        + "catalog id or name that comes from here.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"include_system\":{\"type\":\"boolean\",\"description\":"
                        + "\"Include catalogs the application maintains for itself. "
                        + "Normally false.\"}},\"required\":[]}",
                (request) -> listCatalogs(workspace.current(), request));

        addTool(server, json, "list_elements",
                "Lists the elements of one catalog with their attribute values. Elements are "
                        + "hierarchical, so each carries the id of its parent. Paged: ask for "
                        + "an offset to continue. Use this to see what is actually in a "
                        + "catalog; use get_element when you already know which one you want.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"catalog\":{\"type\":\"string\",\"description\":\"The catalog's "
                        + "name, or its id as a number.\"},"
                        + "\"offset\":{\"type\":\"integer\",\"description\":\"How many to "
                        + "skip. Default 0.\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"How many to "
                        + "return, at most " + MAX_PAGE + ". Default 50.\"}},"
                        + "\"required\":[\"catalog\"]}",
                (request) -> listElements(workspace.current(), request));

        addTool(server, json, "get_element",
                "Reads one element by its id: its name, its catalog, its parent, and every "
                        + "attribute value it carries.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"id\":{\"type\":\"integer\",\"description\":\"The element id, as "
                        + "returned by list_elements.\"}},\"required\":[\"id\"]}",
                (request) -> getElement(workspace.current(), request));

        FileTools.register(server, json, workspace);
        DiagramTools.register(server, json, workspace);
        QueryTools.register(server, json, workspace);
        RenderTools.register(server, json, workspace);
        WriteTools.register(server, json, workspace);
        DrawTools.register(server, json, workspace);
    }

    // ------------------------------------------------------------------ tools

    private static Object listCatalogs(ModelSession session, Map<String, Object> request) {
        boolean includeSystem = Json.bool(request, "include_system", false);
        List<Object> out = new ArrayList<>();
        for (Qualifier q : session.getEngine().getQualifiers()) {
            if (q.isSystem() && !includeSystem)
                continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("name", q.getName());
            row.put("system", q.isSystem());
            row.put("elements", session.getEngine().getElementCountForQualifier(q.getId()));
            List<Object> attributes = new ArrayList<>();
            for (Attribute a : q.getAttributes())
                attributes.add(describe(a));
            row.put("attributes", attributes);
            out.add(row);
        }
        return Map.of("catalogs", out);
    }

    private static Object listElements(ModelSession session, Map<String, Object> request) {
        Qualifier qualifier = resolveCatalog(session, Json.string(request, "catalog"));
        int offset = Math.max(0, Json.integer(request, "offset", 0));
        int limit = Math.min(MAX_PAGE, Math.max(1, Json.integer(request, "limit", 50)));

        List<Element> all = session.getEngine().getElements(qualifier.getId());
        List<Object> out = new ArrayList<>();
        for (int i = offset; i < Math.min(all.size(), offset + limit); i++)
            out.add(describe(session, all.get(i), qualifier));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("catalog", Map.of("id", qualifier.getId(), "name", qualifier.getName()));
        result.put("total", all.size());
        result.put("offset", offset);
        result.put("elements", out);
        if (offset + out.size() < all.size())
            result.put("next_offset", offset + out.size());
        return result;
    }

    private static Object getElement(ModelSession session, Map<String, Object> request) {
        long id = Json.integer(request, "id", -1);
        Element element = session.getEngine().getElement(id);
        if (element == null)
            throw new IllegalArgumentException("No element with id " + id
                    + ". Ids come from list_elements.");
        Qualifier qualifier = session.getEngine().getQualifier(element.getQualifierId());
        return describe(session, element, qualifier);
    }

    // ------------------------------------------------------------- translation

    private static Map<String, Object> describe(Attribute a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("name", a.getName());
        // "Core.Text", "IDEF0.Type" - the plugin and the type together, which is what
        // decides how a value should be read.
        out.put("type", a.getAttributeType().getPluginName() + "."
                + a.getAttributeType().getTypeName());
        return out;
    }

    static Map<String, Object> describe(ModelSession session, Element element,
                                                Qualifier qualifier) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", element.getId());
        out.put("name", element.getName());
        out.put("catalog", qualifier.getName());
        Map<String, Object> values = new LinkedHashMap<>();
        for (Attribute a : qualifier.getAttributes()) {
            Object value = session.getEngine().getAttribute(element, a);
            if (value != null)
                // toString rather than the object: an attribute value can be a whole
                // persistent graph, and an agent wants the text a person would see.
                values.put(a.getName(), value.toString());
        }
        out.put("attributes", values);
        return out;
    }

    /**
     * Catalogs are addressed by name in conversation and by id in the model, so both are
     * accepted. A name that matches nothing produces a message naming what does exist,
     * because an agent given only "not found" will guess again rather than look.
     */
    static Qualifier resolveCatalog(ModelSession session, String catalog) {
        try {
            Qualifier byId = session.getEngine().getQualifier(Long.parseLong(catalog));
            if (byId != null)
                return byId;
        } catch (NumberFormatException notAnId) {
            // fall through to the name
        }
        List<String> names = new ArrayList<>();
        for (Qualifier q : session.getEngine().getQualifiers()) {
            if (q.getName().equals(catalog))
                return q;
            if (!q.isSystem())
                names.add(q.getName());
        }
        throw new IllegalArgumentException("No catalog called \"" + catalog
                + "\". This model has: " + String.join(", ", names));
    }

    // ------------------------------------------------------------- plumbing

    /** What a tool does: take the arguments, return something serialisable. */
    interface Handler {
        Object handle(Map<String, Object> request) throws Exception;
    }

    /**
     * Registers one tool.
     *
     * <p>
     * The schema is written as JSON rather than built out of nested maps, because the one
     * thing a reader needs to check is whether what an agent is shown matches what the
     * handler reads, and that is only legible side by side.
     *
     * <p>
     * A failure comes back as a tool result marked as an error rather than as a protocol
     * fault. The difference matters: the agent should read "no catalog called X, this model
     * has Y and Z" and correct itself, not lose the session.
     */
    static void addTool(McpSyncServer server, Json json, String name, String description,
                        String schema, Handler handler) {
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(Json.schema(schema))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json.write(handler.handle(request.arguments())))
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage() == null
                                        ? e.toString() : e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build());
    }

    /** What a tool does when its answer is not text - an image, say. */
    interface ContentHandler {
        java.util.List<McpSchema.Content> handle(Map<String, Object> request) throws Exception;
    }

    /**
     * The same registration, for a tool whose result is content rather than JSON. Kept
     * separate rather than folded in with an instanceof: a tool either describes something
     * or shows it, and which one it is should be visible where it is registered.
     */
    static void addToolReturningContent(McpSyncServer server, String name, String description,
                                        String schema, ContentHandler handler) {
        server.addTool(McpServerFeatures.SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder()
                        .name(name)
                        .description(description)
                        .inputSchema(Json.schema(schema))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        return McpSchema.CallToolResult.builder()
                                .content(handler.handle(request.arguments()))
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(e.getMessage() == null
                                        ? e.toString() : e.getMessage())
                                .isError(true)
                                .build();
                    }
                })
                .build());
    }

    private Tools() {
    }
}
