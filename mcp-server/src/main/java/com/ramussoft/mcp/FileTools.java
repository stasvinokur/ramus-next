package com.ramussoft.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import io.modelcontextprotocol.server.McpSyncServer;

import com.ramussoft.common.Qualifier;
import com.ramussoft.pb.idef.visual.MovingArea;

/**
 * Choosing which model to work on, and making and removing model files.
 *
 * <p>
 * This server is not confined to a directory: a path is a path. That was chosen knowingly,
 * and the one thing standing between {@code delete_model} and any file on the machine is
 * that it is a tool for deleting MODELS and checks that the target is one - a zip with a
 * Ramus model inside it - before removing anything.
 */
final class FileTools {

    /** What a Ramus model always contains, and nothing else does. */
    private static final String MODEL_MARKER = "data/application_metadata.xml";

    static void register(McpSyncServer server, Json json, Workspace workspace) {
        Tools.addTool(server, json, "list_files",
                "Lists the Ramus model files (.rsf) in a directory, with their size and when "
                        + "they were last changed. Use it to find out what there is before "
                        + "opening anything.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"directory\":{\"type\":\"string\",\"description\":\"The directory "
                        + "to look in.\"}},\"required\":[\"directory\"]}",
                (request) -> listFiles(request));

        Tools.addTool(server, json, "current_model",
                "Says which model is open, if any, and whether it has changes that have not "
                        + "been written to disk. Worth asking before switching models, since "
                        + "switching writes them out.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                (request) -> current(workspace));

        Tools.addTool(server, json, "open_model",
                "Opens a .rsf file and makes it the model every other tool works on. "
                        + "IMPORTANT: if the model that is currently open has unsaved "
                        + "changes, they are WRITTEN TO DISK before the switch - tell the "
                        + "user when that happens; the reply says which file was written.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\",\"description\":\"Path to the .rsf "
                        + "file.\"}},\"required\":[\"path\"]}",
                (request) -> open(workspace, request));

        Tools.addTool(server, json, "close_model",
                "Closes the open model, writing out any unsaved changes first. After this no "
                        + "tool works until something is opened again.",
                "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
                (request) -> close(workspace));

        if (workspace.isReadOnly())
            // A read-only server offers no way to make or destroy a file, rather than
            // offering one and refusing.
            return;

        Tools.addTool(server, json, "create_model",
                "Creates a new .rsf file containing one empty model, and opens it. The model "
                        + "gets a name and a notation: idef0 for the usual activity boxes "
                        + "with input, control, mechanism and output; dfd or dfds for a data "
                        + "flow diagram. Unsaved changes to the model currently open are "
                        + "written out first.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\",\"description\":\"Where to create the "
                        + "file. It must not already exist.\"},"
                        + "\"name\":{\"type\":\"string\",\"description\":\"The name of the "
                        + "model inside the file.\"},"
                        + "\"notation\":{\"type\":\"string\",\"enum\":[\"idef0\",\"dfd\","
                        + "\"dfds\"],\"description\":\"Default idef0.\"}},"
                        + "\"required\":[\"path\",\"name\"]}",
                (request) -> create(workspace, request));

        Tools.addTool(server, json, "save_model_as",
                "Writes the open model to another path and continues working there. The file "
                        + "it came from is left exactly as it was, which makes this the way "
                        + "to experiment without touching the original.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\",\"description\":\"Where to write it. "
                        + "It must not already exist.\"}},\"required\":[\"path\"]}",
                (request) -> saveAs(workspace, request));

        Tools.addTool(server, json, "delete_model",
                "Deletes a Ramus model file from disk. This cannot be undone: there is no "
                        + "trash and no copy is kept. It refuses a file that is not a Ramus "
                        + "model, and refuses the model that is currently open - close it "
                        + "first.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"path\":{\"type\":\"string\",\"description\":\"Path to the .rsf "
                        + "file to delete.\"}},\"required\":[\"path\"]}",
                (request) -> delete(workspace, request));
    }

    // ------------------------------------------------------------------ tools

    private static Object listFiles(Map<String, Object> request) {
        File directory = new File(Json.string(request, "directory"));
        if (!directory.isDirectory())
            throw new IllegalArgumentException(directory + " is not a directory.");
        File[] found = directory.listFiles((dir, name) -> name.toLowerCase().endsWith(".rsf"));
        if (found == null)
            throw new IllegalArgumentException("Could not read " + directory + ".");
        Arrays.sort(found);

        List<Object> out = new ArrayList<>();
        for (File f : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", f.getName());
            row.put("path", f.getAbsolutePath());
            row.put("bytes", f.length());
            row.put("modified", Instant.ofEpochMilli(f.lastModified()).toString());
            out.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", directory.getAbsolutePath());
        result.put("models", out);
        if (out.isEmpty())
            result.put("note", "No .rsf files here.");
        return result;
    }

    private static Object current(Workspace workspace) {
        if (!workspace.hasModel())
            return Map.of("open", false, "note", "No model is open. Use open_model, or "
                    + "create_model to make one.");
        ModelSession session = workspace.peek();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", true);
        out.put("path", session.getFile().getAbsolutePath());
        out.put("unsaved_changes", session.isDirty());
        out.put("read_only", session.isReadOnly());
        if (session.isDirty())
            out.put("note", "There are changes that have not been written. save writes them; "
                    + "so does opening another model.");
        return out;
    }

    private static Object open(Workspace workspace, Map<String, Object> request)
            throws IOException {
        File file = new File(Json.string(request, "path"));
        requireModel(file);
        String wrote = workspace.open(file);
        return opened(workspace, wrote);
    }

    private static Object close(Workspace workspace) throws IOException {
        String wrote = workspace.close();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("closed", true);
        if (wrote != null)
            out.put("saved_on_close", wrote);
        return out;
    }

    private static Object create(Workspace workspace, Map<String, Object> request)
            throws IOException {
        File file = new File(Json.string(request, "path"));
        String name = Json.string(request, "name");
        String notation = request.containsKey("notation")
                ? request.get("notation").toString() : "idef0";
        int type;
        switch (notation.toLowerCase()) {
            case "idef0":
                type = -1; // anything that is neither DFD nor DFDS leaves the default
                break;
            case "dfd":
                type = MovingArea.DIAGRAM_TYPE_DFD;
                break;
            case "dfds":
                type = MovingArea.DIAGRAM_TYPE_DFDS;
                break;
            default:
                throw new IllegalArgumentException("Unknown notation \"" + notation
                        + "\". Use idef0, dfd or dfds.");
        }
        String wrote = workspace.create(file, name, type);
        Map<String, Object> out = new LinkedHashMap<>(opened(workspace, wrote));
        out.put("created", file.getAbsolutePath());
        return out;
    }

    private static Object saveAs(Workspace workspace, Map<String, Object> request)
            throws IOException {
        File file = new File(Json.string(request, "path"));
        if (file.exists())
            throw new IllegalArgumentException(file + " already exists. Choose another path.");
        File from = workspace.current().getFile();
        workspace.saveAs(file);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved_as", file.getAbsolutePath());
        out.put("original_untouched", from.getAbsolutePath());
        out.put("now_working_on", file.getAbsolutePath());
        return out;
    }

    private static Object delete(Workspace workspace, Map<String, Object> request) {
        File file = new File(Json.string(request, "path"));
        requireModel(file);

        if (workspace.hasModel()
                && sameFile(workspace.peek().getFile(), file))
            throw new IllegalArgumentException("That model is open. Call close_model first - "
                    + "the open session holds it.");

        if (!file.delete())
            throw new IllegalStateException("Could not delete " + file + ".");
        return Map.of("deleted", file.getAbsolutePath());
    }

    // ------------------------------------------------------------- plumbing

    private static Map<String, Object> opened(Workspace workspace, String wrote) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open", workspace.peek().getFile().getAbsolutePath());
        if (wrote != null)
            // Said plainly, because the agent has to be able to tell the user that a file it
            // was not asked to write has been written.
            out.put("saved_before_switching", wrote);
        return out;
    }

    /**
     * Refuses anything that is not a Ramus model.
     *
     * <p>
     * By reading it rather than by trusting the extension: this is the only check between a
     * tool called "delete a model" and whatever else happens to be on the disk, and a file
     * named {@code .rsf} is not evidence of anything.
     */
    private static void requireModel(File file) {
        if (!file.isFile())
            throw new IllegalArgumentException(file + " is not a file.");
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.getEntry(MODEL_MARKER) == null)
                throw new IllegalArgumentException(file + " is not a Ramus model: it is a zip "
                        + "archive but has no " + MODEL_MARKER + " inside.");
        } catch (IOException notAZip) {
            throw new IllegalArgumentException(file + " is not a Ramus model - a model is a "
                    + "zip archive and this could not be read as one.");
        }
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.isFile() && b.isFile() && Files.isSameFile(a.toPath(), b.toPath());
        } catch (IOException e) {
            return a.getAbsoluteFile().equals(b.getAbsoluteFile());
        }
    }

    private FileTools() {
    }
}
