package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServer;

import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.common.Engine;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;

/**
 * Drawing: the boxes on a diagram and the arrows between them.
 *
 * <p>
 * Separate from {@link WriteTools} because the danger is different. Editing a catalog changes
 * a value; changing a diagram rewrites the visual data of a whole diagram at once, and getting
 * that wrong is how a model comes back with an empty page. Nothing here writes that data
 * itself - {@link DiagramBuilder} drives the same panel the application draws with, which is
 * the only thing that has ever produced a diagram this program can read.
 */
final class DrawTools {

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        if (workspace.isReadOnly())
            return;

        Tools.addTool(server, json, "add_activity",
                "Adds an activity box to a diagram. The diagram is the decomposition of the "
                        + "activity you name as the parent - leave it out for the top of the "
                        + "model. Position is optional: without one the boxes step down the "
                        + "diagonal, the way an IDEF0 diagram is laid out. An IDEF0 context "
                        + "diagram holds exactly one activity; the second one goes inside it. "
                        + "Held in memory until you call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"What the activity "
                        + "does - a verb phrase, as IDEF0 asks.\"},"
                        + "\"parent\":{\"type\":\"integer\",\"description\":\"The id of the "
                        + "activity whose diagram this goes on, from get_function_tree. "
                        + "Omit for the top of the model.\"},"
                        + "\"x\":{\"type\":\"number\",\"description\":\"Left edge, in diagram "
                        + "units; the page is 800 wide. Optional.\"},"
                        + "\"y\":{\"type\":\"number\",\"description\":\"Top edge. Optional.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"name\"]}",
                (request) -> addActivity(workspace.current(), request));

        Tools.addTool(server, json, "set_activity",
                "Changes an activity that is already on a diagram: its name, where it sits, "
                        + "how big it is. Only what you name is touched. Held in memory until "
                        + "you call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"The activity "
                        + "id, from get_function_tree.\"},"
                        + "\"name\":{\"type\":\"string\"},"
                        + "\"x\":{\"type\":\"number\"},"
                        + "\"y\":{\"type\":\"number\"},"
                        + "\"width\":{\"type\":\"number\"},"
                        + "\"height\":{\"type\":\"number\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"activity\"]}",
                (request) -> setActivity(workspace.current(), request));

        Tools.addTool(server, json, "add_arrow",
                "Draws an arrow and names it. Each end is either an activity with the role "
                        + "the arrow plays there - {\"activity\": 12, \"role\": \"input\"} - "
                        + "or the edge of the page, written {\"border\": true}, which is how "
                        + "anything from outside the diagram arrives. An arrow leaves an "
                        + "output and arrives at an input, a control or a mechanism, so "
                        + "\"from\" takes the output end. A border end needs no side: it "
                        + "takes the one that matches the other end. Two arrows given the "
                        + "same name carry the same thing, which is how one flow crosses "
                        + "several diagrams. Held in memory until you call save.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"What flows along "
                        + "the arrow - a noun phrase.\"},"
                        + "\"from\":{\"type\":\"object\",\"description\":\"Where the arrow "
                        + "starts.\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\"},"
                        + "\"role\":{\"type\":\"string\",\"enum\":[\"output\",\"input\","
                        + "\"control\",\"mechanism\"]},"
                        + "\"border\":{\"type\":\"boolean\"}}},"
                        + "\"to\":{\"type\":\"object\",\"description\":\"Where it ends.\","
                        + "\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\"},"
                        + "\"role\":{\"type\":\"string\",\"enum\":[\"output\",\"input\","
                        + "\"control\",\"mechanism\"]},"
                        + "\"border\":{\"type\":\"boolean\"}}},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"name\",\"from\",\"to\"]}",
                (request) -> addArrow(workspace.current(), request));

        Tools.addTool(server, json, "remove_arrow",
                "Removes an arrow from a diagram by name. Say which diagram it is on - the "
                        + "activity whose decomposition it belongs to - or name one of the "
                        + "activities it touches. The stream itself stays in the catalog, so "
                        + "the same name can be drawn again.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\",\"description\":\"The arrow's name.\"},"
                        + "\"diagram\":{\"type\":\"integer\",\"description\":\"The id of the "
                        + "activity whose diagram the arrow is on. Omit for the top of the "
                        + "model.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"name\"]}",
                (request) -> removeArrow(workspace.current(), request));

        Tools.addTool(server, json, "remove_activity",
                "Removes an activity from its diagram, together with everything below it - "
                        + "its decomposition, and the arrows attached to it. This is not a "
                        + "small change: check get_diagram first.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"The activity "
                        + "id, from get_function_tree.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"activity\"]}",
                (request) -> removeActivity(workspace.current(), request));
    }

    // ------------------------------------------------------------------ tools

    private static Object addActivity(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function parent = request.get("parent") == null
                ? plugin.getBaseFunction()
                : activity(plugin, Json.integer(request, "parent", -1), model);
        String name = Json.string(request, "name");

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            DiagramBuilder builder = new DiagramBuilder(session, model, parent);
            Function added = builder.addActivity(name, number(request, "x"),
                    number(request, "y"));
            builder.commit();
            return Map.of("added", describe(added));
        });
    }

    private static Object setActivity(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function function = activity(plugin, Json.integer(request, "activity", -1), model);

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            DiagramBuilder builder = new DiagramBuilder(session, model, parentOf(function));
            builder.setActivity(function, request.get("name") == null
                            ? null : request.get("name").toString(),
                    number(request, "x"), number(request, "y"),
                    number(request, "width"), number(request, "height"));
            builder.commit();
            return Map.of("changed", describe(function));
        });
    }

    private static Object addArrow(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        String name = Json.string(request, "name");

        Map<String, Object> from = end(request, "from");
        Map<String, Object> to = end(request, "to");
        Function fromActivity = endActivity(plugin, model, from);
        Function toActivity = endActivity(plugin, model, to);
        if (fromActivity == null && toActivity == null)
            throw new IllegalArgumentException("An arrow needs at least one end on an "
                    + "activity: both ends cannot be the border of the page.");

        // The diagram an arrow belongs to is the one both its ends are on, which is the
        // decomposition of their common parent. Saying it this way means an agent never has
        // to name the diagram - naming the activities already did.
        Function parent = parentOf(fromActivity == null ? toActivity : fromActivity);
        if (fromActivity != null && toActivity != null
                && !parent.equals(parentOf(toActivity)))
            throw new IllegalArgumentException("\"" + fromActivity.getName() + "\" and \""
                    + toActivity.getName() + "\" are on different diagrams, so an arrow "
                    + "cannot join them. An arrow that leaves one diagram ends at the "
                    + "border of the page.");

        int fromSide = side(from, "from", fromActivity, true);
        int toSide = side(to, "to", toActivity, false);
        // A border end takes the side that matches the other end, so an input arriving from
        // outside comes in on the left, a control from the top, and nobody has to say so.
        DiagramBuilder.End start = fromActivity == null
                ? DiagramBuilder.End.frame(toSide)
                : DiagramBuilder.End.on(fromActivity, fromSide);
        DiagramBuilder.End finish = toActivity == null
                ? DiagramBuilder.End.frame(fromSide)
                : DiagramBuilder.End.on(toActivity, toSide);

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            DiagramBuilder builder = new DiagramBuilder(session, model, parent);
            builder.addArrow(name, start, finish);
            builder.commit();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("added", name);
            result.put("diagram", describe(parent));
            return result;
        });
    }

    private static Object removeArrow(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function parent = request.get("diagram") == null
                ? plugin.getBaseFunction()
                : activity(plugin, Json.integer(request, "diagram", -1), model);
        String name = Json.string(request, "name");

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            DiagramBuilder builder = new DiagramBuilder(session, model, parent);
            int removed = builder.removeArrow(name);
            builder.commit();
            return Map.of("removed", removed, "arrow", name,
                    "diagram", describe(parent));
        });
    }

    private static Object removeActivity(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function function = activity(plugin, Json.integer(request, "activity", -1), model);
        Map<String, Object> removed = describe(function);

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            DiagramBuilder builder = new DiagramBuilder(session, model, parentOf(function));
            builder.removeActivity(function);
            builder.commit();
            return Map.of("removed", removed);
        });
    }

    // ------------------------------------------------------------- plumbing

    private static Map<String, Object> describe(Function function) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", function.getElement().getId());
        out.put("name", function.getName());
        return out;
    }

    private static Function activity(DataPlugin plugin, long id, Qualifier model) {
        Function found = DiagramTools.findFunction(plugin, plugin.getBaseFunction(), id);
        if (found == null)
            throw new IllegalArgumentException("No activity with id " + id + " in model \""
                    + model.getName() + "\". Ids come from get_function_tree.");
        return found;
    }

    private static Function parentOf(Function function) {
        Object parent = function.getParent();
        if (!(parent instanceof Function))
            throw new IllegalArgumentException("\"" + function.getName() + "\" is the top of "
                    + "the model, so it is not on a diagram that can be changed.");
        return (Function) parent;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> end(Map<String, Object> request, String which) {
        Object value = request.get(which);
        if (!(value instanceof Map))
            throw new IllegalArgumentException("\"" + which + "\" must be an object: either "
                    + "{\"activity\": <id>, \"role\": \"output\"} or {\"border\": true}.");
        return (Map<String, Object>) value;
    }

    private static Function endActivity(DataPlugin plugin, Qualifier model,
                                        Map<String, Object> end) {
        Object id = end.get("activity");
        if (id == null)
            return null;
        return activity(plugin, Json.integer(end, "activity", -1), model);
    }

    /**
     * The side of the box an end attaches to, which is also the role the arrow plays there.
     *
     * <p>
     * An arrow leaves an output and arrives at an input, a control or a mechanism. That is
     * not a rule this adds - it is what the notation means - so a request the other way round
     * is a mistake worth naming rather than drawing.
     */
    private static int side(Map<String, Object> end, String which, Function activity,
                            boolean starting) {
        if (activity == null)
            return -1;
        Object role = end.get("role");
        if (role == null)
            throw new IllegalArgumentException("\"" + which + "\" names an activity but no "
                    + "role. Say input, control, mechanism or output.");
        int wanted = DiagramBuilder.roleOf(role.toString());
        boolean output = "output".equalsIgnoreCase(role.toString());
        if (starting && !output)
            throw new IllegalArgumentException("An arrow starts where something is produced, "
                    + "so \"from\" takes the role \"output\" - \"" + role + "\" is what the "
                    + "arrow is at the other end. Swap \"from\" and \"to\".");
        if (!starting && output)
            throw new IllegalArgumentException("An arrow ends where something is used, so "
                    + "\"to\" takes input, control or mechanism rather than \"output\". "
                    + "Swap \"from\" and \"to\".");
        return wanted;
    }

    private static Double number(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null)
            return null;
        if (value instanceof Number)
            return ((Number) value).doubleValue();
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("\"" + key + "\" must be a number, not \""
                    + value + "\".");
        }
    }

    private interface Work {
        Object run() throws Exception;
    }

    /** One tool call, one Undo - the same discipline as every other change. */
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

    private DrawTools() {
    }
}
