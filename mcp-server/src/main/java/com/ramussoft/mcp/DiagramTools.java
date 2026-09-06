package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import io.modelcontextprotocol.server.McpSyncServer;

import com.ramussoft.common.Qualifier;
import com.ramussoft.pb.Row;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.IDEF0Object;

/**
 * The diagrams: activities, their decompositions, and the arrows between them.
 *
 * <p>
 * This is what the application is for, and it is the part an agent cannot reconstruct from
 * the catalogs alone. Arrow roles come from the sector borders rather than from geometry -
 * the same field the report engine reads - so what an agent is told matches what a report
 * would say about the same model.
 */
final class DiagramTools {

    /**
     * The four IDEF0 arrow roles, in the encoding the model stores. Not invented here: it is
     * what {@code IDEF0ConnectionPlugin} maps its Outputs/Mechanisms/Inputs/Controls
     * keywords onto, so these names and the report keywords mean the same thing.
     */
    private static final String[] ROLES = {"output", "mechanism", "input", "control"};

    /**
     * What a sheet is named by, said the same way everywhere it is asked for.
     *
     * <p>
     * A diagram has no id of its own in this model: it is identified by the activity it
     * decomposes, and three tools spell that parameter three different ways for historical
     * reasons - {@code activity} here, {@code diagram} in the arrow tools, {@code parent} in
     * add_activity. The spellings are not being changed, because scripts already use them; the
     * least that can be done is that all three mean exactly this, and all three default the
     * same way.
     */
    static final String SHEET = "The activity whose decomposition this diagram is, from "
            + "get_function_tree. Omit for the context diagram - the single-box sheet the "
            + "model's top activity is drawn on.";

    /**
     * And what {@code activity} means in the OTHER half of the tools, where it is not a sheet
     * at all but one of the boxes on one.
     */
    static final String BOX = "The activity itself - a box drawn on a diagram - from "
            + "get_function_tree or get_diagram.";

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        Tools.addTool(server, json, "list_models",
                "Lists the IDEF0 and DFD models in this file. A model is a tree of "
                        + "activities: one top activity, decomposed into child diagrams, "
                        + "each of those decomposed again. Most files hold exactly one. "
                        + "Every diagram tool takes a model name or id from here.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                (request) -> listModels(workspace.current()));

        Tools.addTool(server, json, "get_function_tree",
                "Returns the tree of activities of one model, each with its IDEF0 code "
                        + "(A0, A1, A11 and so on), its name, its type, and whether it is "
                        + "decomposed into a diagram of its own. This is the map of the "
                        + "model: read it first, then ask get_diagram about whichever "
                        + "activity you care about. Also returns context_diagram, which "
                        + "names the A-0 sheet - the one holding the single top box, which "
                        + "is not a node of this tree.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id, from list_models. May be omitted when the file holds only "
                        + "one.\"},"
                        + "\"depth\":{\"type\":\"integer\",\"description\":\"How many levels "
                        + "to descend. Default 3; use a larger number for the whole tree.\"}},"
                        + "\"required\":[]}",
                (request) -> functionTree(workspace.current(), request));

        Tools.addTool(server, json, "get_diagram",
                "Returns one diagram: the child activities of the given activity, and for "
                        + "each of them the arrows attached to it, grouped by role - input, "
                        + "control, mechanism, output. This is the answer to \"what does "
                        + "this process consume and produce\". Ask for include_geometry to "
                        + "get the page and every rectangle on it as well; use list_arrows "
                        + "when what you need is how the arrows are connected.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"" + SHEET
                        + "\"},"
                        + "\"include_geometry\":{\"type\":\"boolean\",\"description\":\"Also "
                        + "return the size of the page, the rectangle each box occupies, and "
                        + "the route, label and tilde of each arrow - in the same units "
                        + "add_activity takes as x and y. Default false.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[]}",
                (request) -> diagram(workspace.current(), request));

        Tools.addTool(server, json, "list_arrows",
                "Lists the arrows of one diagram, one row per segment, with both ends said "
                        + "in full: the activity and role at each end, or the side of the "
                        + "page it runs off, or the junction where it meets the rest of its "
                        + "flow. Segments carrying one flow share a group number, so an arrow "
                        + "that forks to four activities is one group rather than four "
                        + "arrows of the same name - which is what get_diagram, grouping by "
                        + "name, cannot tell you.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"" + SHEET
                        + "\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[]}",
                (request) -> arrows(workspace.current(), request));
    }

    // ------------------------------------------------------------------ tools

    private static Object listModels(ModelSession session) {
        List<Object> out = new ArrayList<>();
        for (Qualifier q : IDEF0Plugin.getBaseQualifiers(session.getEngine())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("name", q.getName());
            Function base = session.getDataPlugin(q).getBaseFunction();
            if (base != null) {
                row.put("top_activity_id", base.getElement().getId());
                row.put("top_activity", base.getName());
                row.put("notation", notation(base.getDecompositionType()));
            }
            out.add(row);
        }
        return Map.of("models", out);
    }

    private static Object functionTree(ModelSession session, Map<String, Object> request) {
        Qualifier model = resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        int depth = Math.max(1, Json.integer(request, "depth", 3));

        Function base = plugin.getBaseFunction();
        if (base == null)
            throw new IllegalStateException("Model \"" + model.getName()
                    + "\" has no top activity.");

        // getBaseFunction can be the top activity itself - in a model nobody has drawn on
        // yet, it is - or a container above it. In an existing model the container carries
        // the SAME code as its only child, which is what shows A0 twice if it is not
        // skipped, and is also the only reliable way to tell the two apart.
        List<Function> children = new ArrayList<>();
        for (Row child : plugin.getChilds(base, true))
            if (child instanceof Function)
                children.add((Function) child);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model.getName());
        // A field and not a node. The A-0 sheet belongs to the base function, which carries
        // the same IDEF0 code as its only child - so putting it in the tree would show A0
        // twice and make an agent walk the model twice. But its id is what get_diagram,
        // render_diagram and set_diagram_info want for the context diagram, and until now
        // nothing said what that id was.
        result.put("context_diagram", sheet(base, "A-0"));
        if (children.size() == 1 && code(children.get(0)).equals(code(base)))
            result.put("tree", describe(plugin, children.get(0), depth));
        else if (children.isEmpty())
            result.put("tree", describe(plugin, base, depth));
        else {
            List<Object> roots = new ArrayList<>();
            for (Function child : children)
                roots.add(describe(plugin, child, depth));
            result.put("trees", roots);
        }
        return result;
    }

    private static Object diagram(ModelSession session, Map<String, Object> request) {
        Qualifier model = resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function parent = sheetOf(plugin, model, request, "activity");
        boolean includeGeometry = Json.bool(request, "include_geometry", false);

        DiagramGeometry geometry = DiagramGeometry.read(plugin, parent);

        Map<Long, Map<String, List<String>>> byActivity = new LinkedHashMap<>();
        for (PaintSector paint : geometry.sectors()) {
            Sector sector = paint.getSector();
            if (sector == null)
                continue;
            String name = sector.getName();
            if (name == null || name.trim().isEmpty())
                continue;
            record(byActivity, sector.getStart(), name);
            record(byActivity, sector.getEnd(), name);
        }

        List<Object> children = new ArrayList<>();
        for (Row child : plugin.getChilds(parent, true)) {
            if (!(child instanceof Function))
                continue;
            Function function = (Function) child;
            long childId = function.getElement().getId();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", childId);
            out.put("code", code(function));
            out.put("name", function.getName());
            out.put("type", typeName(function.getType()));
            if (includeGeometry)
                out.put("bounds", DiagramGeometry.rectangle(function.getBounds()));
            out.put("arrows", arrowsOf(byActivity.get(childId)));
            children.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model.getName());
        result.put("activity", sheet(parent, code(parent)));
        if (includeGeometry)
            result.put("page", geometry.page());
        result.put("children", children);
        if (includeGeometry)
            result.put("arrows_drawn", geometry.arrows());
        if (children.isEmpty())
            result.put("note", notDecomposed(plugin, parent));
        return result;
    }

    /**
     * What to say about an activity that has no diagram of its own.
     *
     * <p>
     * "No decomposition, so no diagram" was true and useless: every activity is DRAWN
     * somewhere - as a box on its parent's sheet - and an agent that asked the wrong one of
     * the two questions was left with nowhere to go and asked again. Naming the sheet it is
     * drawn on answers the question that was meant.
     */
    private static String notDecomposed(DataPlugin plugin, Function function) {
        Function parent = parentOf(function);
        if (parent == null)
            return "\"" + function.getName() + "\" is not decomposed, so it has no diagram "
                    + "of its own.";
        return "\"" + function.getName() + "\" is not decomposed, so it has no diagram of "
                + "its own - it is drawn as a box on the diagram of \"" + parent.getName()
                + "\" (activity " + parent.getElement().getId() + ", " + code(parent)
                + "). Ask for that one.";
    }

    /** Which sheet an answer is about, always said the same way and in the same order. */
    private static Map<String, Object> sheet(Function activity, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", activity.getElement().getId());
        out.put("code", code);
        out.put("name", activity.getName());
        return out;
    }

    /** The activity one level up, or null at the top of the model. */
    private static Function parentOf(Function function) {
        com.ramussoft.database.common.Row parent =
                ((com.ramussoft.database.common.Row) function).getParent();
        return parent instanceof Function ? (Function) parent : null;
    }

    private static Object arrows(ModelSession session, Map<String, Object> request) {
        Qualifier model = resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function parent = sheetOf(plugin, model, request, "activity");

        DiagramGeometry geometry = DiagramGeometry.read(plugin, parent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model.getName());
        result.put("activity", sheet(parent, code(parent)));
        result.put("page", geometry.page());
        result.put("arrows", geometry.arrows());
        return result;
    }

    // ------------------------------------------------------------- translation

    /**
     * Files one arrow under the activity it touches and the role it plays THERE.
     *
     * <p>
     * A sector has two ends, and only the end attached to a given activity says what the
     * arrow is for it - the same stream is an output of one activity and an input of the
     * next, which is the whole point of an IDEF0 diagram.
     */
    private static void record(Map<Long, Map<String, List<String>>> byActivity,
                               NSectorBorder border, String name) {
        if (border == null || border.getFunction() == null)
            return;
        int role = border.getFunctionType();
        if (role < 0 || role >= ROLES.length)
            return;
        long id = border.getFunction().getElement().getId();
        Map<String, List<String>> roles = byActivity.get(id);
        if (roles == null) {
            roles = new LinkedHashMap<>();
            byActivity.put(id, roles);
        }
        List<String> names = roles.get(ROLES[role]);
        if (names == null) {
            names = new ArrayList<>();
            roles.put(ROLES[role], names);
        }
        String trimmed = name.trim();
        if (!names.contains(trimmed))
            names.add(trimmed);
    }

    /** Roles in the order a reader of an IDEF0 diagram expects, and never an empty one. */
    private static Map<String, Object> arrowsOf(Map<String, List<String>> roles) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (roles == null)
            return out;
        for (String role : new String[]{"input", "control", "mechanism", "output"})
            if (roles.containsKey(role))
                out.put(role, roles.get(role));
        return out;
    }

    private static Map<String, Object> describe(DataPlugin plugin, Function function,
                                                int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", function.getElement().getId());
        out.put("code", code(function));
        out.put("name", function.getName());
        out.put("type", typeName(function.getType()));

        Vector<Row> children = plugin.getChilds(function, true);
        int count = 0;
        List<Object> below = new ArrayList<>();
        for (Row child : children)
            if (child instanceof Function) {
                count++;
                if (depth > 1)
                    below.add(describe(plugin, (Function) child, depth - 1));
            }
        out.put("decomposed", count > 0);
        if (count > 0 && depth > 1)
            out.put("children", below);
        else if (count > 0)
            out.put("children_not_shown", count);
        return out;
    }

    /**
     * The activity kinds an IDEF0 diagram distinguishes. The numbers are
     * {@code Function.TYPE_*}; naming them here keeps an agent from having to know them.
     */
    /**
     * The activity's IDEF0 code, through the authoritative implementation rather than one of
     * the stripped copies in the report engine - that one honours the model letter and the
     * DFD case. It takes the database Row, and a Function is one at runtime; the same cast
     * is made in ExportToImagesDialog.
     */
    private static String code(Function function) {
        return IDEF0Object.getIDEF0Kod((com.ramussoft.database.common.Row) function);
    }

    /**
     * Which notation a model is drawn in. IDEF0 is the absence of the other two rather than
     * a value of its own, which is why this reads as a default and not as a case.
     */
    private static String notation(int decompositionType) {
        if (decompositionType == MovingArea.DIAGRAM_TYPE_DFD)
            return "dfd";
        if (decompositionType == MovingArea.DIAGRAM_TYPE_DFDS)
            return "dfds";
        return "idef0";
    }

    private static String typeName(int type) {
        switch (type) {
            case Function.TYPE_ACTION:
                return "action";
            case Function.TYPE_OPERATION:
                return "operation";
            case Function.TYPE_PROCESS_PART:
                return "process part";
            case Function.TYPE_PROCESS:
                return "process";
            case Function.TYPE_PROCESS_KOMPLEX:
                return "process complex";
            case Function.TYPE_EXTERNAL_REFERENCE:
                return "external reference";
            case Function.TYPE_DATA_STORE:
                return "data store";
            case Function.TYPE_DFDS_ROLE:
                return "role";
            default:
                return "type " + type;
        }
    }

    /**
     * The activity a sheet belongs to: the one named, or the context diagram by default.
     *
     * <p>
     * Defaulting matters more than it looks. The context diagram's owner is the base function,
     * whose id is not a node of the function tree - so an agent that wanted the A-0 sheet had
     * to know a number nothing had told it, and the commonest request of all was the one that
     * could not be made.
     */
    static Function sheetOf(DataPlugin plugin, Qualifier model, Map<String, Object> request,
                            String key) {
        Function base = plugin.getBaseFunction();
        if (base == null)
            throw new IllegalStateException("Model \"" + model.getName()
                    + "\" has no top activity.");
        if (request == null || request.get(key) == null)
            return base;
        long id = Json.integer(request, key, -1);
        Function found = findFunction(plugin, base, id);
        if (found == null)
            throw new IllegalArgumentException("No activity with id " + id + " in model \""
                    + model.getName() + "\". Ids come from get_function_tree, and its "
                    + "context_diagram field has the id of the A-0 sheet.");
        return found;
    }

    static Function findFunction(DataPlugin plugin, Function from, long id) {
        if (from == null)
            return null;
        if (from.getElement().getId() == id)
            return from;
        for (Row child : plugin.getChilds(from, true))
            if (child instanceof Function) {
                Function found = findFunction(plugin, (Function) child, id);
                if (found != null)
                    return found;
            }
        return null;
    }

    /**
     * Most files hold one model, and making an agent name it every time would be noise. So
     * the argument is optional while there is only one, and required - with the names listed
     * - as soon as there is a choice to make.
     */
    static Qualifier resolveModel(ModelSession session, Map<String, Object> request) {
        List<Qualifier> models = IDEF0Plugin.getBaseQualifiers(session.getEngine());
        if (models.isEmpty())
            throw new IllegalStateException("This file holds no IDEF0 or DFD model.");

        Object named = request == null ? null : request.get("model");
        if (named == null) {
            if (models.size() == 1)
                return models.get(0);
            List<String> names = new ArrayList<>();
            for (Qualifier q : models)
                names.add(q.getName());
            throw new IllegalArgumentException("This file holds several models, so \"model\""
                    + " is required. They are: " + String.join(", ", names));
        }
        String wanted = named.toString();
        for (Qualifier q : models)
            if (q.getName().equals(wanted) || Long.toString(q.getId()).equals(wanted))
                return q;
        List<String> names = new ArrayList<>();
        for (Qualifier q : models)
            names.add(q.getName());
        throw new IllegalArgumentException("No model called \"" + wanted
                + "\". This file has: " + String.join(", ", names));
    }

    private DiagramTools() {
    }
}
