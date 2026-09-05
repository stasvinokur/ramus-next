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
     * Resolved the way the loader resolves: either the bitmap the path names, or the vector
     * beside it. Most icons are now the second case - the source still asks for
     * {@code file-save.png} and gets {@code file-save.svg}, because that indirection is the
     * whole replacement mechanism.
     */
    private static boolean resolvable(String path) throws IOException {
        return existsInAnyModule(path) || existsInAnyModule(Icons.vectorPath(path));
    }

    /**
     * Checked against every module's resource tree rather than against this module's
     * classpath. The icons live in six different modules and only the packaged application
     * sees all of them at once, so a classpath check from here would report most of the set
     * as missing. What this catches is the real mistake: source that names an icon which is
     * in the repository under neither name.
     */
    private static boolean existsInAnyModule(String path) throws IOException {
        return findInAnyModule(path) != null;
    }

    private static Path findInAnyModule(String path) throws IOException {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        try (java.util.stream.Stream<Path> roots = Files.list(repositoryRoot())) {
            for (Path module : roots.filter(Files::isDirectory).toArray(Path[]::new)) {
                Path candidate = module.resolve("src/main/resources").resolve(relative);
                if (Files.exists(candidate))
                    return candidate;
            }
        }
        return null;
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
            if (!resolvable(path))
                missing.add(path);

        assertEquals("icons named in the source but present in no module's resources, "
                + "as neither a bitmap nor a vector: " + missing, 0, missing.size());
    }

    @Test
    public void loadsAnIconAndKeepsIt() {
        Icon first = Icons.get("/com/ramussoft/gui/common/probe-raster-only.png");
        assertNotNull(first);
        // The same instance, not merely an equal one: decoding an icon per call was one of
        // the two things this class was written to stop.
        assertSame(first, Icons.get("/com/ramussoft/gui/common/probe-raster-only.png"));
    }

    /**
     * image() used to read the cache without ever writing to it, so it re-decoded on every
     * call while get() did not. Both go through one path now.
     */
    @Test
    public void sharesOneCacheBetweenBothAccessors() {
        Icon viaImage = Icons.image("/com/ramussoft/gui/common/probe-raster-only.png");
        assertNotNull(viaImage);
        assertSame(viaImage, Icons.get("/com/ramussoft/gui/common/probe-raster-only.png"));
    }

    /**
     * The replacement mechanism: an icon is served as a vector when a file with the same path
     * and an .svg extension exists. Both files are present in the test resources, so this
     * also pins the precedence rather than only the happy path.
     */
    @Test
    public void prefersTheVectorBesideTheBitmap() {
        Icon icon = Icons.get("/com/ramussoft/gui/common/probe-icon.png");
        assertNotNull(icon);
        assertEquals("com.formdev.flatlaf.extras.FlatSVGIcon",
                icon.getClass().getName());
        assertEquals(16, icon.getIconWidth());
        assertEquals(16, icon.getIconHeight());
    }

    /**
     * And the other half of the rule: no .svg beside it means the bitmap is served. Nothing the
     * application asks for takes this path any more - the fixture below is kept precisely so
     * that the fallback stays pinned rather than quietly rotting once it stopped being used.
     */
    @Test
    public void fallsBackToTheBitmapWhenThereIsNoVector() {
        // A path with no .svg beside it, on purpose. This used to name a real application
        // icon, which then acquired a vector and turned the test red - correctly, but it made
        // the test a hostage of the asset set rather than a check of the mechanism.
        Icon icon = Icons.get("/com/ramussoft/gui/common/probe-raster-only.png");
        assertNotNull(icon);
        assertEquals("javax.swing.ImageIcon", icon.getClass().getName());
    }

    @Test
    public void namesTheVectorBesideAnyPath() {
        assertEquals("/a/b/c.svg", Icons.vectorPath("/a/b/c.png"));
        assertEquals("/a/b/c.svg", Icons.vectorPath("/a/b/c.gif"));
        // A path with a dot in a directory but not in the file name must not be truncated.
        assertEquals("/a.b/c.svg", Icons.vectorPath("/a.b/c"));
    }

    @Test
    public void reportsAMissingIconAsNullRatherThanThrowing() {
        assertEquals(null, Icons.get("/com/ramussoft/gui/there-is-no-such-icon.png"));
    }

    /**
     * Resolving is not the same as rendering, and this is where the difference bites. Feed
     * jsvg a path with corrupt data and it does not complain: the icon reports itself as
     * found, painting throws nothing, and the only trace is an INFO line in java.util.logging.
     * What you get on screen is a blank, or a scattering of stray pixels.
     *
     * <p>Which matters here more than it would elsewhere, because four of these files are
     * generated geometry rather than exported artwork - the case where a silent malformation
     * is likeliest. So every icon is actually painted, and the pixels are counted.
     */
    @Test
    public void everyIconPaintsSomething() throws Exception {
        List<String> blank = new ArrayList<String>();
        for (String path : literalIconPaths()) {
            Path file = findInAnyModule(Icons.vectorPath(path));
            if (file == null)
                file = findInAnyModule(path);
            if (file == null)
                continue; // already reported by everyIconTheSourceAsksForExists
            if (ink(file) == 0)
                blank.add(path + " -> " + repositoryRoot().relativize(file));
        }
        assertEquals("icons that load but paint nothing: " + blank, 0, blank.size());
    }

    /** Paints the file at 16x16 the way a toolbar would, and counts non-transparent pixels. */
    private static int ink(Path file) throws Exception {
        java.net.URL url = file.toUri().toURL();
        javax.swing.Icon icon = file.toString().endsWith(".svg")
                ? new com.formdev.flatlaf.extras.FlatSVGIcon(url)
                : new javax.swing.ImageIcon(url);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        icon.paintIcon(null, g, 0, 0);
        g.dispose();
        int ink = 0;
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                if ((image.getRGB(x, y) >>> 24) > 20)
                    ink++;
        return ink;
    }

    /** getResource / getResourceAsStream / Toolkit.getImage on a bitmap path. */
    private static final Pattern BYPASS = Pattern.compile(
            "get(?:Resource|ResourceAsStream)\\(\\s*\"([^\"]+\\.(?:png|gif|jpg|jpeg))\"");

    /**
     * The one mistake this class cannot otherwise see. Three window icons read their bitmap
     * straight off the classpath instead of through the loader, so they kept showing the 2006
     * artwork after the icon beside them had been redrawn - and when the bitmap was finally
     * deleted, {@code Toolkit.getImage(null)} did not degrade to a window without an icon, it
     * threw and the dialog stopped opening. Every one of the three was found by hand, late.
     *
     * <p>The rule is not an allowlist, which would need maintaining. Reading a bitmap directly
     * is fine while that is genuinely all there is - a mouse cursor, an application icon with
     * no vector. It becomes a bug at the moment someone puts a .svg beside it, and that is
     * exactly what is asserted.
     */
    @Test
    public void nothingBypassesTheLoaderForAPathThatHasAVector() throws Exception {
        List<String> stale = new ArrayList<String>();
        for (Path source : javaSources()) {
            if (source.getFileName().toString().equals("Icons.java"))
                continue;
            for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//"))
                    continue; // commented-out code, of which there is plenty
                Matcher m = BYPASS.matcher(line);
                while (m.find()) {
                    String path = m.group(1);
                    if (existsInAnyModule(Icons.vectorPath(path)))
                        stale.add(repositoryRoot().relativize(source) + ": " + path);
                }
            }
        }
        assertEquals("these read a bitmap off the classpath for a path that now has a vector, "
                + "so they show stale artwork or fail outright - route them through Icons: "
                + stale, 0, stale.size());
    }
}
