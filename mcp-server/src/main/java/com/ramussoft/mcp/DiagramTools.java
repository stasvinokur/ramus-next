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
import com.ramussoft.pb.print.PIDEF0painter;
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
     * The panel is sized before the diagram is loaded, and the size decides the coordinate
     * scale. Nothing here draws, so this only has to be large enough that the geometry is
     * the geometry of a real diagram rather than of a squashed one.
     */
    private static final java.awt.Dimension DIAGRAM_SIZE = new java.awt.Dimension(1600, 1200);

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
                        + "activity you care about.",
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
                        + "this process consume and produce\".",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"The id of the "
                        + "activity whose decomposition you want, from get_function_tree.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"activity\"]}",
                (request) -> diagram(workspace.current(), request));
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
        long id = Json.integer(request, "activity", -1);

        Function parent = findFunction(plugin, plugin.getBaseFunction(), id);
        if (parent == null)
            throw new IllegalArgumentException("No activity with id " + id
                    + " in model \"" + model.getName()
                    + "\". Ids come from get_function_tree.");

        // The arrows of a diagram are not a property of an activity that can be read off
        // it: they live in a blob of visual data, and the only thing that decodes it is
        // SectorRefactor, which belongs to a Swing panel. Driving that panel without a
        // window is what the web export has always done. createMovingArea only sizes it -
        // setActiveFunction is what loads the diagram.
        MovingArea area = PIDEF0painter.createMovingArea(DIAGRAM_SIZE, plugin, parent);
        area.setActiveFunction(parent);

        Map<Long, Map<String, List<String>>> byActivity = new LinkedHashMap<>();
        Vector<PaintSector> painted = area.getRefactor().getSectors();
        if (painted != null)
            for (PaintSector paint : painted) {
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
            out.put("arrows", arrowsOf(byActivity.get(childId)));
            children.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model.getName());
        result.put("activity", Map.of(
                "id", parent.getElement().getId(),
                "code", code(parent),
                "name", parent.getName()));
        result.put("children", children);
        if (children.isEmpty())
            result.put("note", "This activity has no decomposition, so it has no diagram "
                    + "of its own.");
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
