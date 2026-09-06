package com.ramussoft.gui.common;

import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Icon;
import javax.swing.ImageIcon;

import com.formdev.flatlaf.extras.FlatSVGIcon;

/**
 * The one place the application loads an interface icon.
 *
 * <p>There was no such place. The same expression - {@code new ImageIcon(getClass()
 * .getResource("..."))} - was copied to 195 call sites across 56 files, which meant three
 * things: a path that no longer resolved produced a silent null icon rather than an error,
 * every icon was decoded again on every call, and there was nowhere to put a change that had
 * to apply to all of them. The last one is why this exists: the icons are 16-pixel bitmaps
 * with no high-resolution variants, and on a Retina display every one of them is doubled by
 * the compositor. Fixing that means loading them differently, and loading them differently
 * means loading them in one place.
 *
 * <p>The paths are the same classpath literals as before. An icon is served as a vector when
 * a file with the same path and an {@code .svg} extension exists, and as the original bitmap
 * otherwise - see {@link #load}.
 */
public final class Icons {

    /**
     * One icon instance per path, shared by every call site. Fine today, because nothing
     * mutates an icon after loading it. A dark theme should recolour through a single global
     * {@code FlatSVGIcon.ColorFilter} for that reason - a per-icon {@code setColorFilter} here
     * would change the icon everywhere it is already on screen.
     */
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<String, Icon>();

    /**
     * Every path that has actually been asked for. Rather more icons exist on disk than are
     * reachable from the code, and this is how that difference gets measured rather than
     * guessed: run the application with {@code -Dramus.icons.trace=<file>}, use it, and the
     * file lists what was really loaded.
     *
     * <p>Appended as each new path is first seen, not written from a shutdown hook. A hook
     * is the tidier design and it does not survive the way this application actually ends -
     * it registers three shutdown hooks of its own, and in practice a fourth one added here
     * never got to run. A few bytes of I/O on the first request for each of a hundred-odd
     * icons is not worth being clever about.
     */
    private static final Set<String> REQUESTED =
            ConcurrentHashMap.newKeySet();

    private static final String TRACE_FILE = System.getProperty("ramus.icons.trace");

    private Icons() {
    }

    /**
     * Loads an icon by its classpath path, e.g. {@code "/com/ramussoft/gui/file-save.png"}.
     *
     * @return the icon, or null if the resource is missing - the same thing the copied
     * expression did, because a missing icon must not stop a toolbar from being built.
     * A missing path is reported once on stderr rather than silently becoming a blank.
     */
    public static Icon get(final String path) {
        return lookup(path);
    }

    /**
     * The same icon, typed as {@link ImageIcon}.
     *
     * <p>Only for the few places whose API demands that concrete type - the tree tables take
     * {@code setLeafIcon(ImageIcon)}, and the user's own element icons are built from raw
     * bytes. Everywhere else should use {@link #get}, which is deliberately free to return
     * something that is not a bitmap.
     *
     * <p>It returns the same instance {@link #get} does. That is not luck and it is worth
     * knowing: {@code FlatSVGIcon} extends {@link ImageIcon}, so a vector already satisfies
     * the cast and the second branch below is unreachable for anything that loaded at all.
     * It stays as the guard it looks like, rather than as a promise this method keeps.
     */
    public static ImageIcon image(final String path) {
        Icon icon = lookup(path);
        if (icon instanceof ImageIcon)
            return (ImageIcon) icon;
        return raster(path);
    }

    /**
     * The classpath path of the application icon, at 256 pixels.
     */
    private static final String APPLICATION_ICON = "/com/ramussoft/gui/app-icon.png";

    /**
     * The sizes a window manager picks between. 16 to 48 are the small slots - a title bar, a
     * task switcher - and 256 is what a Retina display or a large taskbar asks for.
     */
    private static final int[] WINDOW_ICON_SIZES = {16, 20, 24, 32, 48, 64, 128, 256};

    private static java.util.List<java.awt.Image> windowIcons;

    /**
     * The application icon at every size a window will ask for, for
     * {@code Window.setIconImages}.
     *
     * <p>
     * Both call sites used to do {@code Toolkit.getDefaultToolkit().getImage(getResource(
     * "/com/ramussoft/gui/application.png"))} and hand the single 32-pixel result to
     * {@code setIconImage}. That is one bitmap for every slot there is, so it was doubled by
     * the compositor on a high-resolution display and downsampled without care in the small
     * ones. Handing over a list instead lets the window manager choose, and every entry is
     * rendered from the 256-pixel source rather than from the 32-pixel one.
     *
     * <p>
     * Rendered into a {@link java.awt.image.BufferedImage} rather than through
     * {@code getScaledInstance}, whose lazily-sized result can report a width of -1 to
     * whoever asks first. Computed once: this is called per window, and the answer never
     * changes.
     *
     * @return the icons, or an empty list if the source is missing - a window without an
     * icon is a great deal better than a window that fails to open.
     */
    public static synchronized java.util.List<java.awt.Image> windowIcons() {
        if (windowIcons != null)
            return windowIcons;
        ImageIcon source = image(APPLICATION_ICON);
        java.util.List<java.awt.Image> icons = new java.util.ArrayList<java.awt.Image>();
        if (source != null)
            for (int size : WINDOW_ICON_SIZES)
                icons.add(render(source.getImage(), size));
        windowIcons = java.util.Collections.unmodifiableList(icons);
        return windowIcons;
    }

    private static java.awt.image.BufferedImage render(java.awt.Image source, int size) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /**
     * Shared by both accessors, which is the point: the previous version had {@code image}
     * read the cache but never write to it, so a path only ever asked for through it was
     * decoded again on every call - and both {@code RowTreeTable} and {@code QualifierTable}
     * ask from a field initialiser, i.e. once per table. It also skipped the trace, so those
     * paths were missing from the very measurement this class exists to make.
     */
    private static Icon lookup(final String path) {
        if (REQUESTED.add(path) && TRACE_FILE != null)
            trace(path);
        Icon cached = CACHE.get(path);
        if (cached != null)
            return cached;
        Icon loaded = load(path);
        if (loaded != null)
            CACHE.put(path, loaded);
        return loaded;
    }

    /**
     * Prefers a vector icon at the same path with an {@code .svg} extension, and falls back
     * to the bitmap the path actually names.
     *
     * <p>This is where the icon replacement lives, and it is deliberately not a mapping table
     * in code. Call sites keep passing the paths they always passed; whether an icon has been
     * redrawn is decided by whether a .svg sits beside it.
     *
     * <p>Every icon the application asks for now has one, so the bitmap branch is not reached
     * in a running application. It stays because the rule it expresses is what let the set be
     * replaced in pieces, and it is still pinned by a test fixture kept for the purpose.
     */
    private static Icon load(final String path) {
        URL svg = Icons.class.getResource(vectorPath(path));
        if (svg != null)
            return new FlatSVGIcon(svg);
        return raster(path);
    }

    static String vectorPath(final String path) {
        int dot = path.lastIndexOf('.');
        return (dot > path.lastIndexOf('/'))
                ? path.substring(0, dot) + ".svg"
                : path + ".svg";
    }

    private static ImageIcon raster(final String path) {
        URL url = Icons.class.getResource(path);
        if (url == null) {
            System.err.println("Icon not found on the classpath: " + path);
            return null;
        }
        return new ImageIcon(url);
    }

    private static synchronized void trace(final String path) {
        try (java.io.Writer out = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(TRACE_FILE, true), "UTF-8")) {
            out.write(path);
            out.write('\n');
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
