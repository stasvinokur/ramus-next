package com.ramussoft.mcp;

import java.awt.Dimension;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

import com.ramussoft.common.Qualifier;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Row;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Drawing a diagram, so an agent can look at it rather than only read about it.
 *
 * <p>
 * A diagram carries meaning in its layout that no list of arrows conveys - which boxes sit
 * where, how the arrows actually run between them. This uses the same painter the
 * application's own image export uses, so what an agent sees is what a person would.
 */
final class RenderTools {

    /**
     * Wide enough that the labels inside the boxes are legible when the image is looked at,
     * and small enough that the base64 of it does not swamp a conversation. The application's
     * own export offers sizes in this range.
     */
    private static final int WIDTH = 1600;

    private static final int HEIGHT = 1200;

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        Tools.addToolReturningContent(server, "render_diagram",
                "Draws one diagram and returns it as a PNG image. Give it the id of an "
                        + "activity that HAS a decomposition and you get the diagram below "
                        + "that activity - its child boxes and the arrows between them, laid "
                        + "out as the modeller drew them. Use this when the arrangement "
                        + "matters; use get_diagram when you only need the names.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"activity\":{\"type\":\"integer\",\"description\":\"The id of the "
                        + "activity whose diagram to draw, from get_function_tree.\"},"
                        + "\"model\":{\"type\":\"string\",\"description\":\"The model's name "
                        + "or id. May be omitted when the file holds only one.\"}},"
                        + "\"required\":[\"activity\"]}",
                (request) -> render(workspace.current(), request));
    }

    private static List<McpSchema.Content> render(ModelSession session,
                                                  Map<String, Object> request)
            throws Exception {
        Qualifier model = DiagramTools.resolveModel(session, request);
        DataPlugin plugin = session.getDataPlugin(model);
        long id = Json.integer(request, "activity", -1);

        Function function = DiagramTools.findFunction(plugin, plugin.getBaseFunction(), id);
        if (function == null)
            throw new IllegalArgumentException("No activity with id " + id + " in model \""
                    + model.getName() + "\". Ids come from get_function_tree.");

        boolean decomposed = false;
        for (Row child : plugin.getChilds(function, true))
            if (child instanceof Function) {
                decomposed = true;
                break;
            }
        if (!decomposed)
            throw new IllegalArgumentException("\"" + function.getName() + "\" has no "
                    + "decomposition, so there is no diagram to draw. Pick an activity that "
                    + "get_function_tree reports as decomposed.");

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        new PIDEF0painter(function, new Dimension(WIDTH, HEIGHT), plugin)
                .writeToStream(png, PIDEF0painter.PNG_FORMAT);

        if (png.size() == 0)
            throw new IllegalStateException("The painter produced an empty image for \""
                    + function.getName() + "\".");

        return List.of(new McpSchema.ImageContent(null,
                Base64.getEncoder().encodeToString(png.toByteArray()), "image/png"));
    }

    private RenderTools() {
    }
}
