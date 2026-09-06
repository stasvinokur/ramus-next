package com.ramussoft.mcp;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServer;

import com.dsoft.pb.idef.elements.ProjectOptions;
import com.dsoft.pb.idef.elements.Readed;
import com.dsoft.pb.idef.elements.Status;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;

/**
 * The frame around every diagram: who drew it, for what project, when, and how finished it is.
 *
 * <p>
 * Two homes, and they are not interchangeable. The project, what the model is used for, its
 * purpose and its readers belong to the model and show on every diagram of it. The author, the
 * dates and the status belong to the ACTIVITY whose decomposition the diagram is, so each sheet
 * can carry its own - which is what an IDEF0 author expects, since sheets are reviewed one at a
 * time. The two tools here are the two dialogs the application offers, and they were kept apart
 * for that reason rather than merged into one.
 *
 * <p>
 * What the frame shows and nothing can set: the node code and the sheet number, which are
 * computed from where the activity sits; the context thumbnail; and the row of numbers beside
 * NOTES, which is drawn rather than stored.
 */
final class TitleTools {

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        if (workspace.isReadOnly())
            return;

        Tools.addTool(server, json, "set_model_info",
                "Fills in the part of the title block that belongs to the whole model and "
                        + "shows on every one of its diagrams: the project name, the author, "
                        + "what the model is used for, its purpose, and the readers who have "
                        + "signed it off. Only what you name is changed.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"project\":{\"type\":\"string\",\"description\":\"The project this "
                        + "model belongs to - the PROJECT line.\"},"
                        + "\"author\":{\"type\":\"string\",\"description\":\"Who made the "
                        + "model. Shows as AUTHOR on every diagram that does not name its "
                        + "own.\"},"
                        + "\"used_at\":{\"type\":\"string\",\"description\":\"Where the model "
                        + "is used - the USED AT line.\"},"
                        + "\"purpose\":{\"type\":\"string\",\"description\":\"What the model "
                        + "is for. IDEF0 calls it the purpose, and asks for a viewpoint with "
                        + "it.\"},"
                        + "\"node_letter\":{\"type\":\"string\",\"description\":\"The letter "
                        + "the node codes start with. A by default, giving A0, A1, A11.\"},"
                        + "\"readers\":{\"type\":\"array\",\"description\":\"Who has read the "
                        + "model and when; the three most recent are shown.\",\"items\":"
                        + "{\"type\":\"object\",\"properties\":{"
                        + "\"name\":{\"type\":\"string\"},"
                        + "\"date\":{\"type\":\"string\",\"description\":\"dd.MM.yyyy\"}}}},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[]}",
                (request) -> setModelInfo(workspace.current(), request));

        Tools.addTool(server, json, "set_diagram_info",
                "Fills in the part of the title block that belongs to ONE diagram: its "
                        + "author, the date it was drawn, the date it was last revised, and "
                        + "its status - working, draft, recommended or publication, which is "
                        + "the row the black marker sits against. Call this AFTER the drawing "
                        + "is done: changing a diagram stamps its revision date with the time "
                        + "of the change, exactly as it does in the application.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"The activity "
                        + "whose diagram this is, from get_function_tree. Omit for the "
                        + "context diagram.\"},"
                        + "\"author\":{\"type\":\"string\"},"
                        + "\"date\":{\"type\":\"string\",\"description\":\"When it was drawn, "
                        + "dd.MM.yyyy.\"},"
                        + "\"revision\":{\"type\":\"string\",\"description\":\"When it was "
                        + "last revised, dd.MM.yyyy.\"},"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"working\",\"draft\","
                        + "\"recommended\",\"publication\"]},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[]}",
                (request) -> setDiagramInfo(workspace.current(), request));
    }

    // ------------------------------------------------------------------ tools

    private static Object setModelInfo(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function base = plugin.getBaseFunction();

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            // The getter hands back a copy - a NEW one when the model has never had any - so
            // everything below is lost unless it is handed back at the end. That is the whole
            // reason this reads like a transaction rather than a series of setters.
            ProjectOptions options = base.getProjectOptions();
            Map<String, Object> changed = new LinkedHashMap<>();

            if (request.get("project") != null) {
                options.setProjectName(text(request, "project"));
                changed.put("project", text(request, "project"));
            }
            if (request.get("author") != null) {
                options.setProjectAutor(text(request, "author"));
                changed.put("author", text(request, "author"));
            }
            if (request.get("used_at") != null) {
                options.setUsedAt(text(request, "used_at"));
                changed.put("used_at", text(request, "used_at"));
            }
            if (request.get("purpose") != null) {
                options.setDefinition(text(request, "purpose"));
                changed.put("purpose", text(request, "purpose"));
            }
            if (request.get("node_letter") != null) {
                String letter = text(request, "node_letter");
                if (letter.length() != 1 || !Character.isLetter(letter.charAt(0)))
                    throw new IllegalArgumentException("\"node_letter\" is one letter - the "
                            + "one the node codes start with, A by default. \"" + letter
                            + "\" is not.");
                options.getDeligate().setModelLetter(letter);
                changed.put("node_letter", letter);
            }
            List<Object> readers = readers(request);
            if (readers != null) {
                options.getReadedModel().clearReadeds();
                for (Object entry : readers) {
                    Map<?, ?> reader = asMap(entry);
                    Readed readed = options.getReadedModel().addReaded();
                    readed.setReader(String.valueOf(reader.get("name")));
                    Object when = reader.get("date");
                    if (when != null)
                        readed.getDeligate().setDate(
                                new Timestamp(day(String.valueOf(when)).getTime()));
                }
                changed.put("readers", readers.size());
            }

            base.setProjectOptions(options);
            return Map.of("model", model.getName(), "changed", changed);
        });
    }

    private static Object setDiagramInfo(ModelSession session, Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        Function diagram = request.get("activity") == null
                ? plugin.getBaseFunction()
                : find(plugin, Json.integer(request, "activity", -1), model);

        session.markChanged();
        return inTransaction(session.getEngine(), () -> {
            Map<String, Object> changed = new LinkedHashMap<>();
            if (request.get("author") != null) {
                diagram.setAuthor(text(request, "author"));
                changed.put("author", text(request, "author"));
            }
            if (request.get("date") != null) {
                diagram.setCreateDate(day(text(request, "date")));
                changed.put("date", text(request, "date"));
            }
            if (request.get("status") != null) {
                diagram.setStatus(new Status(statusOf(text(request, "status")), null));
                changed.put("status", text(request, "status"));
            }
            // Last, and the order is not arbitrary: the revision shown is the system one when
            // there is one, and both are stamped with the time of any change to the diagram.
            if (request.get("revision") != null) {
                Date when = day(text(request, "revision"));
                diagram.setSystemRevDate(when);
                diagram.setRevDate(when);
                changed.put("revision", text(request, "revision"));
            }
            return Map.of("diagram", Map.of("id", diagram.getElement().getId(),
                    "name", diagram.getName() == null ? "" : diagram.getName()),
                    "changed", changed);
        });
    }

    // ------------------------------------------------------------- plumbing

    private static final String DAY = "dd.MM.yyyy";

    /**
     * A date as the application writes it. Fixed to one format on purpose: the frame prints
     * whatever it is given, and a model whose sheets are dated in three different ways is
     * worse than one that refuses the third.
     */
    private static Date day(String value) {
        SimpleDateFormat format = new SimpleDateFormat(DAY, Locale.ENGLISH);
        format.setLenient(false);
        try {
            return format.parse(value.trim());
        } catch (ParseException notADate) {
            throw new IllegalArgumentException("\"" + value + "\" is not a date. Write it as "
                    + DAY + ", for example 05.09.2026.");
        }
    }

    private static int statusOf(String status) {
        switch (status.toLowerCase(Locale.ENGLISH)) {
            case "working":
                return Status.WORKING;
            case "draft":
                return Status.DRAFT;
            // The constant is spelled with one m in the model. The tool is not.
            case "recommended":
                return Status.RECOMENDED;
            case "publication":
                return Status.PUBLICATION;
            default:
                throw new IllegalArgumentException("Unknown status \"" + status
                        + "\". The frame has four: working, draft, recommended, publication.");
        }
    }

    private static Function find(DataPlugin plugin, long id, Qualifier model) {
        Function found = DiagramTools.findFunction(plugin, plugin.getBaseFunction(), id);
        if (found == null)
            throw new IllegalArgumentException("No activity with id " + id + " in model \""
                    + model.getName() + "\". Ids come from get_function_tree.");
        return found;
    }

    private static String text(Map<String, Object> request, String key) {
        Object value = request.get(key);
        return value == null ? null : value.toString();
    }

    private static List<Object> readers(Map<String, Object> request) {
        Object value = request.get("readers");
        if (value == null)
            return null;
        if (!(value instanceof List))
            throw new IllegalArgumentException("\"readers\" is a list of {name, date}.");
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) value;
        return list;
    }

    private static Map<?, ?> asMap(Object entry) {
        if (!(entry instanceof Map))
            throw new IllegalArgumentException("Each reader is an object with a name and a "
                    + "date, like {\"name\": \"Ivanov\", \"date\": \"05.09.2026\"}.");
        return (Map<?, ?>) entry;
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

    private TitleTools() {
    }
}
