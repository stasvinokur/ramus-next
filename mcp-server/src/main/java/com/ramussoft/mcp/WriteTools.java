package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServer;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Element;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;

/**
 * Changing the model.
 *
 * <p>
 * Deliberately limited to catalogs and their elements. Adding a box or an arrow to a diagram
 * also writes visual data - coordinates, sector geometry, crosspoints - decoded and produced
 * by a Swing panel, and getting that wrong produces a model that opens with an empty
 * diagram. An agent that cannot draw is a smaller loss than an agent that can destroy.
 *
 * <p>
 * Every change here runs inside one user transaction, so it appears in the application as a
 * single step that Undo reverses. Nothing is written to disk until save is called.
 */
final class WriteTools {

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        if (workspace.isReadOnly())
            // Not registered at all rather than registered and refusing: an agent should see
            // the tools it can actually use, and a read-only session has none of these.
            return;

        Tools.addTool(server, json, "create_element",
                "Adds an element to a catalog. Give it the catalog and a name; give it a "
                        + "parent to nest it under an existing element, since elements are "
                        + "hierarchical. Held in memory until you call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"catalog\":{\"type\":\"string\",\"description\":\"The catalog's "
                        + "name or id, from list_catalogs.\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"The name of the new "
                        + "element.\"},"
                        + "\"attributes\":{\"type\":\"object\",\"description\":\"Other "
                        + "attribute values to set, keyed by attribute name.\"}},"
                        + "\"required\":[\"catalog\",\"name\"]}",
                (request) -> create(workspace.current(), request));

        Tools.addTool(server, json, "update_element",
                "Changes an element: its name, any of its attribute values, or both. Only "
                        + "the attributes you name are touched. Held in memory until you "
                        + "call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"id\":{\"type\":\"integer\",\"description\":\"The element id, from "
                        + "list_elements.\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"A new name.\"},"
                        + "\"attributes\":{\"type\":\"object\",\"description\":\"Attribute "
                        + "values to set, keyed by attribute name.\"}},"
                        + "\"required\":[\"id\"]}",
                (request) -> update(workspace.current(), request));

        Tools.addTool(server, json, "delete_element",
                "Removes an element from its catalog. Anything that referred to it - an "
                        + "arrow it was attached to, another element pointing at it - loses "
                        + "that reference, so check with get_element first. Held in memory "
                        + "until you call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"id\":{\"type\":\"integer\",\"description\":\"The element id.\"}},"
                        + "\"required\":[\"id\"]}",
                (request) -> delete(workspace.current(), request));

        Tools.addTool(server, json, "save",
                "Writes every change made so far back to the .rsf file. Until this is "
                        + "called nothing has touched the file on disk. A backup of the "
                        + "model as it was found is written beside it before the first "
                        + "change of the session.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                (request) -> save(workspace.current()));
    }

    // ------------------------------------------------------------------ tools

    private static Object create(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier catalog = Tools.resolveCatalog(session, Json.string(request, "catalog"));
        String name = Json.string(request, "name");

        session.markChanged();
        Engine engine = session.getEngine();
        return inTransaction(engine, () -> {
            Element element = engine.createElement(catalog.getId());
            setName(engine, catalog, element, name);
            applyAttributes(engine, catalog, element, attributesOf(request));
            return Map.of("created", Tools.describe(session, element, catalog));
        });
    }

    private static Object update(ModelSession session, Map<String, Object> request)
            throws Exception {
        Engine engine = session.getEngine();
        long id = Json.integer(request, "id", -1);
        Element element = engine.getElement(id);
        if (element == null)
            throw new IllegalArgumentException("No element with id " + id
                    + ". Ids come from list_elements.");
        Qualifier catalog = engine.getQualifier(element.getQualifierId());

        Object newName = request.get("name");
        Map<String, Object> attributes = attributesOf(request);
        if (newName == null && attributes.isEmpty())
            throw new IllegalArgumentException("Nothing to change: give \"name\", "
                    + "\"attributes\", or both.");

        session.markChanged();
        return inTransaction(engine, () -> {
            if (newName != null)
                setName(engine, catalog, element, newName.toString());
            applyAttributes(engine, catalog, element, attributes);
            return Map.of("updated", Tools.describe(session, element, catalog));
        });
    }

    private static Object delete(ModelSession session, Map<String, Object> request)
            throws Exception {
        Engine engine = session.getEngine();
        long id = Json.integer(request, "id", -1);
        Element element = engine.getElement(id);
        if (element == null)
            throw new IllegalArgumentException("No element with id " + id + ".");
        String name = element.getName();

        session.markChanged();
        return inTransaction(engine, () -> {
            engine.deleteElement(id);
            return Map.of("deleted", Map.of("id", id, "name", name == null ? "" : name));
        });
    }

    private static Object save(ModelSession session) throws Exception {
        if (!session.isDirty())
            return Map.of("saved", false, "note", "Nothing has changed since the model was "
                    + "opened, so there was nothing to write.");
        session.save();
        return Map.of("saved", true, "file", session.getFile().getAbsolutePath());
    }

    // ------------------------------------------------------------- plumbing

    /**
     * An element's name is not a field: it is whichever attribute the catalog nominates as
     * its name. A catalog that nominates none is one whose elements have no name to set, and
     * saying so beats writing the name into a random attribute.
     */
    private static void setName(Engine engine, Qualifier catalog, Element element,
                                String name) {
        long forName = catalog.getAttributeForName();
        for (Attribute a : catalog.getAttributes())
            if (a.getId() == forName) {
                engine.setAttribute(element, a, name);
                return;
            }
        throw new IllegalArgumentException("Catalog \"" + catalog.getName() + "\" has no "
                + "attribute nominated to hold the name, so a name cannot be set on its "
                + "elements. Use \"attributes\" to set values directly.");
    }

    private static void applyAttributes(Engine engine, Qualifier catalog, Element element,
                                        Map<String, Object> values) {
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Attribute attribute = null;
            List<String> known = new ArrayList<>();
            for (Attribute a : catalog.getAttributes()) {
                known.add(a.getName());
                if (a.getName().equals(entry.getKey()))
                    attribute = a;
            }
            if (attribute == null)
                throw new IllegalArgumentException("Catalog \"" + catalog.getName()
                        + "\" has no attribute called \"" + entry.getKey() + "\". It has: "
                        + String.join(", ", known));
            // Values arrive as JSON, and the attribute plugins expect their own types. Text
            // is what an agent can meaningfully set and what every plugin can parse from;
            // anything richer belongs in the application.
            engine.setAttribute(element, attribute,
                    entry.getValue() == null ? null : entry.getValue().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> request) {
        Object value = request == null ? null : request.get("attributes");
        if (value == null)
            return new LinkedHashMap<>();
        if (!(value instanceof Map))
            throw new IllegalArgumentException("\"attributes\" must be an object keyed by "
                    + "attribute name.");
        return (Map<String, Object>) value;
    }

    private interface Work {
        Object run() throws Exception;
    }

    /**
     * One tool call, one undo step.
     *
     * <p>
     * Without this an agent that sets three attributes leaves three separate entries in the
     * journal, and a person undoing what the agent did has to press Undo three times without
     * knowing that is the number. A failure rolls the whole thing back rather than leaving
     * the model half-changed.
     */
    private static Object inTransaction(Engine engine, Work work) throws Exception {
        if (!(engine instanceof Journaled))
            return work.run();
        Journaled journal = (Journaled) engine;
        journal.startUserTransaction();
        boolean committed = false;
        try {
            Object result = work.run();
            journal.commitUserTransaction();
            committed = true;
            return result;
        } finally {
            if (!committed)
                try {
                    journal.rollbackUserTransaction();
                } catch (Exception e) {
                    System.err.println("Could not roll back after a failed change: " + e);
                }
        }
    }

    private WriteTools() {
    }
}
