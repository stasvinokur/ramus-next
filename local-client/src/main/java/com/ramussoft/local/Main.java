package com.ramussoft.local;

import java.awt.Desktop;
import java.awt.Frame;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.SwingUtilities;

import com.ramussoft.common.Engine;
import com.ramussoft.common.Metadata;
import com.ramussoft.gui.common.GUIFramework;
import com.ramussoft.gui.core.AboutDialog;

public class Main extends Runner {

    /**
     * Models macOS asked for before the application was ready to open them.
     *
     * <p>
     * Finder does not pass a double-clicked document as a command-line argument the way
     * Windows does - it sends an Apple event, which arrives on its own schedule and can
     * turn up while the first window is still being built. Parking the request and
     * flushing it once startup finishes is the only way not to drop it.
     */
    private final List<File> pendingOpen = Collections.synchronizedList(new ArrayList<File>());

    private volatile boolean started = false;

    public static void main(String[] args) {
        Main main = new Main();

        // Set macOS-specific system properties
        if (isMac()) {
            System.setProperty("apple.laf.useScreenMenuBar", "true");
            System.setProperty("apple.awt.application.name", Metadata.getApplicationName());
            System.setProperty("com.apple.mrj.application.apple.menu.about.name", Metadata.getApplicationName());

            // Before load, not after: both events can reach a running application at any
            // moment, and the open-file one is delivered during startup when the user
            // launched us by double-clicking a model in the first place.
            main.installOpenFileHandler();
            main.installQuitHandler();
        }

        main.load(args);

        // load() returns once it has decided what to show - a model, or the startup
        // launcher when there was nothing to open. Only now is it safe to act on a
        // document Finder asked for, and this must not be left to
        // postShowVisibaleMainFrame: that runs only when a model is opened, so a
        // double-click on a cold start would park the file behind a window that only
        // opening the file could ever produce.
        main.started = true;
        main.flushPendingOpen();
    }

    @Override
    protected void postShowVisibaleMainFrame(Engine engine, GUIFramework framework) {
        super.postShowVisibaleMainFrame(engine, framework);

        // Properly wire the macOS About menu to the app's About dialog
        if (isMac() && Desktop.isDesktopSupported()) {
            try {
                Desktop.getDesktop().setAboutHandler(e -> {
                    AboutDialog dialog = new AboutDialog(framework.getMainFrame());
                    dialog.setVisible(true);
                    dialog.dispose();
                });
            } catch (UnsupportedOperationException ex) {
                System.err.println("macOS About handler unsupported: " + ex.getMessage());
            } catch (Throwable t) {
                System.err.println("Failed to set macOS About handler: " + t.getMessage());
            }
        }

        started = true;
        flushPendingOpen();
    }

    private void installOpenFileHandler() {
        if (!Desktop.isDesktopSupported())
            return;
        try {
            Desktop.getDesktop().setOpenFileHandler(event -> {
                pendingOpen.addAll(event.getFiles());
                if (started)
                    flushPendingOpen();
            });
        } catch (UnsupportedOperationException ex) {
            System.err.println("macOS open-file handler unsupported: " + ex.getMessage());
        } catch (Throwable t) {
            System.err.println("Failed to set macOS open-file handler: " + t.getMessage());
        }
    }

    /**
     * Without a quit handler, Quit from the application menu tears the JVM down where it
     * stands - no chance to ask about unsaved work, no clean database shutdown. This sends
     * every open window down the identical path its own close button uses, so there is one
     * shutdown story rather than two.
     */
    private void installQuitHandler() {
        if (!Desktop.isDesktopSupported())
            return;
        try {
            Desktop.getDesktop().setQuitHandler((event, response) -> {
                for (GUIFramework framework : GUIFramework.getFrameworks()) {
                    try {
                        framework.exit();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
                // That path calls System.exit itself once the last window is gone, so
                // reaching this line at all means either something declined to close - a
                // dialog about unsaved changes the user cancelled - or the preference that
                // keeps the application alive with no window open is set.
                if (GUIFramework.getFrameworks().length == 0)
                    response.performQuit();
                else
                    response.cancelQuit();
            });
        } catch (UnsupportedOperationException ex) {
            System.err.println("macOS quit handler unsupported: " + ex.getMessage());
        } catch (Throwable t) {
            System.err.println("Failed to set macOS quit handler: " + t.getMessage());
        }
    }

    private void flushPendingOpen() {
        final List<File> files;
        synchronized (pendingOpen) {
            if (pendingOpen.isEmpty())
                return;
            files = new ArrayList<File>(pendingOpen);
            pendingOpen.clear();
        }
        // Off the event thread, matching how Runner.startupLauncher opens the file the user
        // picked there. Opening a model is database work and takes as long as the model is
        // big; on the event thread that is a frozen interface for the duration.
        new Thread("Open-requested-documents") {

            @Override
            public void run() {
                for (File file : files) {
                    try {
                        open(file);
                    } catch (Throwable t) {
                        System.err.println("Failed to open " + file + ": " + t.getMessage());
                        t.printStackTrace();
                    }
                }
                dismissStartupLauncher();
            }
        }.start();
    }

    /**
     * On a cold start the application has already decided there is nothing to open by the
     * time the Apple event arrives, so it puts the startup launcher on screen and the user
     * ends up looking at both it and their model. Take it away once the model is up.
     *
     * <p>
     * Hiding it is the launcher's own Cancel path: its setVisible override disposes the
     * window and only opens something when its OK button was pressed, which it was not.
     *
     * <p>
     * Conditional on a model window existing, and that condition is the whole point. If the
     * document could not be opened - corrupt, or written by a newer version - Runner.open
     * reports it and returns, and taking the launcher away then would leave a running
     * application with no window at all and no way to get one back, right after telling the
     * user their file failed. Asking GUIFramework rather than trusting open's return value
     * also covers the case where it returns false because the model was ALREADY open: a
     * window exists, so the launcher should still go.
     */
    private static void dismissStartupLauncher() {
        SwingUtilities.invokeLater(() -> {
            if (GUIFramework.getFrameworks().length == 0)
                return;
            for (Frame frame : Frame.getFrames())
                if ((frame instanceof FirstSwitchFrame) && frame.isDisplayable())
                    frame.setVisible(false);
        });
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
