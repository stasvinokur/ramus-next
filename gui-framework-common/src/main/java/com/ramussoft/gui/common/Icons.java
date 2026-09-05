package com.ramussoft.gui.common;

import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.Icon;
import javax.swing.ImageIcon;

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
 * <p>The paths are the same classpath literals as before, so this is a change of plumbing
 * and not of behaviour.
 */
public final class Icons {

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
     * The same icon, typed as {@link ImageIcon}.
     *
     * <p>Only for the few places whose API demands that concrete type - the tree tables take
     * {@code setLeafIcon(ImageIcon)}, and the user's own element icons are built from raw
     * bytes. Everywhere else should use {@link #get}, which is deliberately free to return
     * something that is not a bitmap.
     *
     * <p>Note that this loads the bitmap itself rather than asking {@link #get} and casting.
     * That distinction is the whole reason the method exists: the moment {@code get} starts
     * returning a vector icon, a cast would fail and these call sites would blank out - and
     * blank out silently, because a null icon is a legal thing to hand a Swing component.
     * Whatever the rest of the application renders, these paths always get a raster.
     */
    public static ImageIcon image(final String path) {
        REQUESTED.add(path);
        Icon cached = CACHE.get(path);
        if (cached instanceof ImageIcon)
            return (ImageIcon) cached;
        URL url = Icons.class.getResource(path);
        if (url == null) {
            System.err.println("Icon not found on the classpath: " + path);
            return null;
        }
        return new ImageIcon(url);
    }

    private static Icon load(final String path) {
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
