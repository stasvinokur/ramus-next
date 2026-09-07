package com.ramussoft.local;

import java.awt.Desktop;
import java.awt.Frame;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
            Desktop.getDesktop().setOpenFileHandler(event -> desktopRequestedOpen(
                    event.getFiles()));
        } catch (UnsupportedOperationException ex) {
            System.err.println("macOS open-file handler unsupported: " + ex.getMessage());
        } catch (Throwable t) {
            System.err.println("Failed to set macOS open-file handler: " + t.getMessage());
        }
    }

    /**
     * What the handler does, separated from how it is installed so a test can ask for a
     * document without a Desktop, an Apple event or a screen.
     */
    void desktopRequestedOpen(List<File> files) {
        pendingOpen.addAll(files);
        if (started)
            flushPendingOpen();
    }

    /**
     * Whether Finder has already asked for a document.
     *
     * <p>
     * Not gated on {@link #isMac()}: no handler is installed anywhere else, so the list is
     * empty and the answer is no by construction - and leaving the gate off is what makes
     * this reachable from a test on a machine that is not a Mac.
     */
    @Override
    protected boolean isDocumentOpenPending() {
        awaitDesktopEventDelivery();
        return !pendingOpen.isEmpty();
    }

    /**
     * Waits until anything macOS has already handed to this process has reached the handler
     * above.
     *
     * <p>
     * This is a barrier, not a delay, and the difference is the whole point - it waits for
     * work that is already queued, not for work that might arrive. Four facts make that
     * true, and all four are in the JDK rather than in hope:
     *
     * <ol>
     * <li>{@code Desktop.isDesktopSupported()} starts AppKit, and does not return until the
     * application delegate is installed - it blocks for {@code finishLaunching} and again
     * while the delegate is set on the main thread.
     * <li>Nothing is lost before then: the JDK puts a queuing delegate in place BEFORE
     * {@code finishLaunching}, and replays what it collected into the real one.
     * <li>{@code setOpenFileHandler} drains that queue onto the event thread.
     * <li>It drains it at {@code PeerEvent.PRIORITY_EVENT}, which the event queue ranks
     * ABOVE the ordinary priority of an {@code invokeLater}. So an ordinary task posted
     * here is dispatched after the callback even if the callback was posted later.
     * </ol>
     *
     * <p>
     * The timeout is not a grace period and no correct run depends on it. It is there so
     * that a wedged event thread turns into a slow start rather than an application that
     * never draws anything.
     *
     * <p>
     * Not skipped when there is no desktop. An event queue exists with or without a screen,
     * and skipping the drain there would make the one test that pins this behaviour pass
     * for the wrong reason on a machine with no display - which is every machine the build
     * runs on.
     */
    void awaitDesktopEventDelivery() {
        CountDownLatch delivered = new CountDownLatch(1);
        SwingUtilities.invokeLater(delivered::countDown);
        try {
            if (!delivered.await(2, TimeUnit.SECONDS))
                System.err.println("The event thread did not answer within two seconds "
                        + "while working out whether a document was being opened; "
                        + "carrying on without it.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
                reconcileStartupLauncher();
            }
        }.start();
    }

    /**
     * Makes what is on screen agree with what the user asked for, once the document they
     * asked for has either opened or failed.
     *
     * <p>
     * Two directions, and both are needed.
     *
     * <p>
     * A model is up: there should be no launcher. There should not be one anyway - the
     * decision not to show it is made before the launcher is built - so this is a backstop
     * against the one link in that argument that comes from Apple's documentation rather
     * than from source, and against a future macOS that delivers the request later than it
     * does today. Hiding it is the launcher's own Cancel path: its setVisible override
     * disposes the window and only opens something when OK was pressed, which it was not.
     *
     * <p>
     * No model is up: the document was corrupt, or written by a newer version. Runner.open
     * has already said so. Having suppressed the launcher on the strength of a request that
     * then failed, this must not leave a running application with no window and no way to
     * get one - so the launcher is shown after all, and shown even to a user who asked not
     * to be asked, because the alternative there is a second automatic action immediately
     * after one has just failed.
     */
    private void reconcileStartupLauncher() {
        SwingUtilities.invokeLater(() -> {
            if (GUIFramework.getFrameworks().length > 0) {
                for (Frame frame : Frame.getFrames())
                    if ((frame instanceof FirstSwitchFrame) && frame.isDisplayable()) {
                        if (Metadata.DEBUG)
                            System.err.println("The startup launcher was on screen with a "
                                    + "model already open - the request must have arrived "
                                    + "after the decision was made.");
                        frame.setVisible(false);
                    }
                return;
            }
            for (Frame frame : Frame.getFrames())
                if ((frame instanceof FirstSwitchFrame) && frame.isDisplayable())
                    return;
            startupLauncher(true);
        });
    }

    static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
