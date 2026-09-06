package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServer;

import com.ramussoft.common.Qualifier;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.report.Query;
import com.ramussoft.report.data.Data;
import com.ramussoft.report.data.Row;
import com.ramussoft.report.data.Rows;

/**
 * The report query language, exposed as one tool.
 *
 * <p>
 * This is the highest-leverage thing here. The language already answers the questions people
 * actually ask of a process model - which activities consume this document, what does this
 * role own - and it is written down in docs/report-queries.md, so an agent can compose a
 * query without knowing anything about qualifiers, sectors or the shape of the database.
 */
final class QueryTools {

    private static final int MAX_ROWS = 500;

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        Tools.addTool(server, json, "run_query",
                "Runs a report query against the model and returns the rows it matches. A "
                        + "query is a chain of words separated by dots, and the FIRST word is "
                        + "always the name of a catalog: Documents.Inputs returns the "
                        + "activities each document is an input to. After an activity you may "
                        + "write Inputs, Outputs, Controls, Mechanisms, their unions "
                        + "(InputsControls and so on), or Owners; the plain forms return only "
                        + "activities that have no decomposition, so use AllInputs and its "
                        + "siblings to include the ones above them. After a stream, Catalogs "
                        + "returns the elements linked to it, or name a catalog directly. Your "
                        + "own catalog and attribute names may be used as words and take "
                        + "priority over the keywords. This is the same language the report "
                        + "editor uses.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"query\":{\"type\":\"string\",\"description\":\"The query, for "
                        + "example \\\"Документы.Inputs\\\" or \\\"Documents.AllOutputs\\\".\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"Which model the "
                        + "IDEF0 keywords refer to. May be omitted when the file holds only "
                        + "one; required otherwise.\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"How many rows to "
                        + "return, at most " + MAX_ROWS + ". Default 100.\"}},"
                        + "\"required\":[\"query\"]}",
                (request) -> run(workspace.current(), request));
    }

    private static Object run(ModelSession session, Map<String, Object> request) {
        String query = Json.string(request, "query");
        int limit = Math.min(MAX_ROWS, Math.max(1, Json.integer(request, "limit", 100)));

        // Every IDEF0 keyword refuses to run until it knows which model to walk, and it
        // looks that up as the report attribute "ReportFunction". A report form would carry
        // it; here it is supplied directly, which is the whole difference between running a
        // query and running a report.
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("ReportFunction", modelName(session, request));
        Data data = new Data(session.getEngine(), new Query(attributes));

        Rows rows;
        try {
            rows = data.getRowsByQuery(query);
        } catch (Exception e) {
            throw new IllegalArgumentException(explain(session, query, e));
        }

        List<Object> out = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), limit); i++)
            out.add(describe(rows.get(i)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("total", rows.size());
        result.put("rows", out);
        if (rows.size() > out.size())
            result.put("note", "Showing the first " + out.size() + " of " + rows.size()
                    + "; raise \"limit\" to see more.");
        return result;
    }

    private static Map<String, Object> describe(Row row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", row.getName());
        Qualifier qualifier = row.getQualifier();
        if (qualifier != null)
            out.put("catalog", qualifier.getName());
        return out;
    }

    /**
     * A failed query is the normal way to learn this language, so the message has to teach
     * rather than just refuse. The first word is the one that is wrong most often - it must
     * be a catalog name, and an agent that is told which catalogs exist will fix it itself.
     */
    private static String explain(ModelSession session, String query, Exception failure) {
        String first = query.contains(".") ? query.substring(0, query.indexOf('.')) : query;
        boolean known = false;
        List<String> names = new ArrayList<>();
        for (Qualifier q : session.getEngine().getQualifiers())
            if (!q.isSystem()) {
                names.add(q.getName());
                if (q.getName().equals(first))
                    known = true;
            }
        StringBuilder text = new StringBuilder();
        text.append("The query \"").append(query).append("\" failed: ")
                .append(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        if (!known)
            text.append(". The first word of a query must be a catalog name, and \"")
                    .append(first).append("\" is not one. This model has: ")
                    .append(String.join(", ", names));
        return text.toString();
    }

    /**
     * Optional while there is one model, required as soon as there is a choice - the same
     * rule the diagram tools use, so an agent learns it once.
     */
    private static String modelName(ModelSession session, Map<String, Object> request) {
        Object named = request == null ? null : request.get("model");
        List<Qualifier> models = IDEF0Plugin.getBaseQualifiers(session.getEngine());
        if (named != null)
            return named.toString();
        if (models.size() == 1)
            return models.get(0).getName();
        if (models.isEmpty())
            return "";
        List<String> names = new ArrayList<>();
        for (Qualifier q : models)
            names.add(q.getName());
        throw new IllegalArgumentException("This file holds several models, so \"model\" is "
                + "required for a query that uses IDEF0 keywords. They are: "
                + String.join(", ", names));
    }

    private QueryTools() {
    }
}
