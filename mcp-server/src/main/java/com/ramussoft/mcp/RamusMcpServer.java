package com.ramussoft.mcp;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import com.ramussoft.common.Metadata;

/**
 * A Model Context Protocol server over one Ramus model.
 *
 * <p>
 * Started as {@code ramus-mcp [model.rsf] [--read-only]} and speaks the protocol on standard
 * input and output, which is the shape every MCP client expects to configure.
 *
 * <p>
 * The model is optional. Given one, it opens it; given none, it starts empty and the agent
 * chooses - which is the point, since otherwise looking at a second model means editing the
 * agent's configuration and restarting.
 *
 * <p>
 * It opens the model the same way {@code project-navigator} always has - no window, no
 * frame, no interface - so it works whether or not the application is installed or running.
 */
public final class RamusMcpServer {

    /**
     * Standard output carries JSON-RPC and nothing else. This application prints to
     * System.out in a good number of places, and any one of those lines would corrupt a
     * message and take the session down with no explanation - the classic way an MCP server
     * fails. So the real stream is handed to the transport and taken away from everyone
     * else: after this, System.out goes to standard error alongside the rest of the noise.
     */
    private static OutputStream claimStandardOutput() {
        OutputStream real = new FileOutputStream(FileDescriptor.out);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
        return real;
    }

    /**
     * Asks macOS to treat this process as an accessory rather than an application.
     *
     * <p>
     * Without it the first tool that reads or draws a diagram raises a Dock icon and takes the
     * focus, because reading a diagram means building the application's own drawing panel and
     * that starts AppKit. A server talking over standard input has no business appearing on
     * anybody's screen, still less pulling them out of a fullscreen window.
     *
     * <p>
     * The property is read once, when AWT initialises, so it has to be set before the first
     * AWT class is touched - which is why this is the first statement of main rather than
     * something done where the panel is built. The packaged launcher passes it as a -D as
     * well, and that is the version that cannot be outrun; this one covers running the jar
     * directly, where there is no launcher to pass anything.
     */
    private static void stayOffTheScreen() {
        if (System.getProperty("os.name", "").startsWith("Mac"))
            System.setProperty("apple.awt.UIElement", "true");
    }

    public static void main(String[] args) {
        stayOffTheScreen();

        File file = null;
        boolean readOnly = false;
        for (String arg : args) {
            if ("--read-only".equals(arg))
                readOnly = true;
            else if (arg.startsWith("--")) {
                usage("unknown option " + arg);
                return;
            } else if (file == null)
                file = new File(arg);
            else {
                usage("only one model can be served at a time");
                return;
            }
        }
        if (file != null && !file.isFile()) {
            usage(file + " is not a file");
            return;
        }

        OutputStream stdout = claimStandardOutput();
        CountDownLatch disconnected = new CountDownLatch(1);
        InputStream stdin = closesOn(System.in, disconnected);

        Workspace workspace = new Workspace(readOnly);
        if (file != null)
            try {
                workspace.open(file);
            } catch (Throwable t) {
                // Before the transport exists there is no protocol to report through, so the
                // only honest channel is stderr and a non-zero exit. A file named on the
                // command line and unreadable is a configuration mistake worth failing on;
                // a file the agent names later is just a failed tool call.
                System.err.println("Cannot open " + file + ": " + t);
                t.printStackTrace();
                System.exit(1);
                return;
            }

        McpJsonMapper mapper = McpJsonDefaults.getMapper();
        McpSyncServer server = McpServer
                .sync(new StdioServerTransportProvider(mapper, stdin, stdout))
                .serverInfo("ramus-next", version())
                .instructions(instructions(workspace))
                // tools(false): the tool list of a running server never changes, so the
                // listChanged notification would never be sent. Declaring it was a promise
                // that was never kept - and worse, it points at the wrong remedy. When the
                // application is updated under a live session, THIS process goes on serving
                // the tools it was built with; re-listing them returns the same list. Only a
                // new process has the new tools. See version() for what does help.
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();

        Tools.registerAll(server, workspace, mapper);

        Runtime.getRuntime().addShutdownHook(new Thread("ramus-mcp-shutdown") {
            @Override
            public void run() {
                closeQuietly(workspace);
            }
        });

        // The transport reads on its own thread, so main has nothing to do but wait - and
        // what it waits for is the client going away. Waiting forever instead was wrong in a
        // way that only shows up later: a client that exits closes this end of the pipe and
        // nothing else happens, so the server lives on holding its session lock, and the
        // NEXT server refuses to write because it sees the model still open. One zombie is
        // enough to make writing impossible until the user finds and kills it.
        try {
            disconnected.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(workspace);
    }

    /**
     * Closing writes out unsaved changes, and at shutdown there is nobody left to tell if
     * that fails - so it is reported to stderr rather than thrown into a dying JVM.
     */
    private static void closeQuietly(Workspace workspace) {
        try {
            if (workspace.hasModel())
                workspace.close();
        } catch (Exception e) {
            System.err.println("Could not close the model cleanly: " + e);
        }
    }

    /**
     * The same stream, which counts down when it reaches end of file.
     *
     * <p>
     * There is no other signal available: the transport owns standard input and reports
     * nothing about it, and closing the pipe is exactly what a client does when it exits.
     */
    private static InputStream closesOn(InputStream in, CountDownLatch latch) {
        return new java.io.FilterInputStream(in) {

            @Override
            public int read() throws java.io.IOException {
                return signal(super.read());
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException {
                return signal(super.read(b, off, len));
            }

            private int signal(int read) {
                if (read < 0)
                    latch.countDown();
                return read;
            }
        };
    }

    /**
     * Which build this is, not merely which release.
     *
     * <p>
     * The release number is a constant in the source, so two builds a day apart both call
     * themselves 3.1.0 - and when someone installs an update while a session is running, the
     * session goes on talking to the old process with no way to tell. That happened: half the
     * tools were missing and nothing said why. The file's own timestamp separates them, costs
     * nothing, and needs no build machinery.
     *
     * <p>
     * Running from classes rather than a jar there is nothing to date, and the release number
     * alone is the honest answer.
     */
    static String version() {
        String release = Metadata.getApplicationVersion();
        try {
            java.net.URL location = RamusMcpServer.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            File jar = new File(location.toURI());
            if (jar.isFile())
                return release + " (build " + new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)
                        .format(new java.util.Date(jar.lastModified())) + ")";
        } catch (Exception noJar) {
            // Started from a directory of classes, or a class loader that does not say where
            // it read them. Either way there is no build to date.
        }
        return release;
    }

    /**
     * What the client is told about this server before it calls anything. Worth more than it
     * looks: an agent that knows a catalog is what Ramus calls a table of things, and that
     * queries are dotted paths, asks for the right tool the first time.
     */
    private static String instructions(Workspace workspace) {
        StringBuilder text = new StringBuilder();
        text.append("This server works with Ramus Next business-process models - .rsf ")
                .append("files.\n\n")
                .append("A model file holds CATALOGS (Ramus calls them classifiers) - named ")
                .append("tables of elements, each element carrying typed attributes - and ")
                .append("MODELS, which are IDEF0 or DFD diagrams built from activities and ")
                .append("the arrows between them.\n\n")
                .append("One model is open at a time and every tool works on it. ");
        if (workspace.hasModel())
            text.append(workspace.peek().getFile().getName())
                    .append(" is open; start with list_catalogs. ");
        else
            text.append("Nothing is open yet: use list_files to see what is in a directory, "
                    + "then open_model. ");
        text.append("open_model switches to another file.\n\n");
        text.append("This is Ramus Next ").append(version()).append(". If the tools listed ")
                .append("here are fewer than the documentation describes, this session is ")
                .append("holding a server started before the application was updated: asking ")
                .append("for the tool list again will not help, because this process only has ")
                .append("the tools it was built with. Start a new session.\n\n");
        if (workspace.isReadOnly())
            text.append("This server is READ-ONLY: nothing can be changed, created, saved "
                    + "or deleted.");
        else
            text.append("Changes are held in memory until you call save - EXCEPT that "
                    + "switching to another model with open_model writes them out first, so "
                    + "check current_model before switching if that matters. A backup of a "
                    + "model is written beside it before the first change.");
        return text.toString();
    }

    private static void usage(String problem) {
        System.err.println("ramus-mcp: " + problem);
        System.err.println();
        System.err.println("usage: ramus-mcp [model.rsf] [--read-only]");
        System.err.println();
        System.err.println("Serves Ramus Next models over the Model Context Protocol,");
        System.err.println("speaking JSON-RPC on standard input and output. With no model");
        System.err.println("named, the agent chooses one with open_model.");
        System.exit(2);
    }

    private RamusMcpServer() {
    }
}
