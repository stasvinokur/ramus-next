package com.ramussoft.gui.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Icon;

import org.junit.Test;

/**
 * Guards the icon set against the failure mode it is most prone to: a path that stops
 * resolving.
 *
 * <p>A missing icon does not throw. {@code new ImageIcon(null)} is legal, a null icon is a
 * legal thing to hand a Swing component, and the result is a gap in a toolbar that nobody
 * notices until a user mentions it. So the check is made here, over the source itself: every
 * classpath literal handed to the loader must resolve to a real resource.
 */
public class IconsTest {

    /**
     * Matches {@code Icons.get("...")} and {@code Icons.image("...")}. Only literals - a path
     * built at runtime cannot be checked this way, and there are two of those in the
     * application (the workspace buttons and one dialog), both noted below.
     */
    private static final Pattern CALL =
            Pattern.compile("Icons\\.(?:get|image)\\(\\s*\"([^\"]+)\"\\s*\\)");

    /** Every module that contains Java source. Found from this module's own location. */
    private static Path repositoryRoot() {
        Path here = new File("").getAbsoluteFile().toPath();
        // The test runs with the module directory as its working directory.
        return here.getFileName().toString().equals("gui-framework-common")
                ? here.getParent() : here;
    }

    private static List<Path> javaSources() throws IOException {
        List<Path> out = new ArrayList<Path>();
        Files.walk(repositoryRoot())
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().contains("/src/main/java/"))
                .forEach(out::add);
        return out;
    }

    private static Set<String> literalIconPaths() throws IOException {
        Set<String> paths = new TreeSet<String>();
        for (Path source : javaSources()) {
            String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            Matcher m = CALL.matcher(text);
            while (m.find())
                paths.add(m.group(1));
        }
        return paths;
    }

    /**
     * Resolved against every module's resource tree rather than against this module's
     * classpath. The icons live in six different modules and only the packaged application
     * sees all of them at once, so a classpath check from here would report most of the set
     * as missing. What this catches is the real mistake: source that names an icon which is
     * not in the repository at all.
     */
    private static boolean existsInAnyModule(String path) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        try (java.util.stream.Stream<Path> roots = Files.list(repositoryRoot())) {
            for (Path module : roots.filter(Files::isDirectory).toArray(Path[]::new)) {
                if (Files.exists(module.resolve("src/main/resources").resolve(relative)))
                    return true;
            }
        }
        return false;
    }

    @Test
    public void everyIconTheSourceAsksForExists() throws Exception {
        Set<String> paths = literalIconPaths();
        // If this drops to nothing the test has stopped testing anything - most likely the
        // working directory moved and no source was scanned.
        assertTrue("no Icons.get/image literals found at all - is the source tree where "
                + "this test expects it?", paths.size() > 50);

        List<String> missing = new ArrayList<String>();
        for (String path : paths)
            if (!existsInAnyModule(path))
                missing.add(path);

        assertEquals("icons named in the source but present in no module's resources: "
                + missing, 0, missing.size());
    }

    @Test
    public void loadsAnIconAndKeepsIt() {
        Icon first = Icons.get("/com/ramussoft/gui/file-save.png");
        assertNotNull(first);
        // The same instance, not merely an equal one: decoding an icon per call was one of
        // the two things this class was written to stop.
        assertSame(first, Icons.get("/com/ramussoft/gui/file-save.png"));
    }

    /**
     * image() used to read the cache without ever writing to it, so it re-decoded on every
     * call while get() did not. Both go through one path now.
     */
    @Test
    public void sharesOneCacheBetweenBothAccessors() {
        Icon viaImage = Icons.image("/com/ramussoft/gui/table/add.png");
        assertNotNull(viaImage);
        assertSame(viaImage, Icons.get("/com/ramussoft/gui/table/add.png"));
    }

    @Test
    public void reportsAMissingIconAsNullRatherThanThrowing() {
        assertEquals(null, Icons.get("/com/ramussoft/gui/there-is-no-such-icon.png"));
    }
}
