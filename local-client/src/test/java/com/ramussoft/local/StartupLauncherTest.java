package com.ramussoft.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.util.List;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.gui.common.prefrence.Options;

/**
 * Which window comes up on a cold start, and which does not.
 *
 * <p>
 * Double-clicking a model in Finder opened it correctly and ALSO raised the create-new /
 * open-existing chooser, which then went away again by itself. Not a race: the decision was
 * made from {@code args.length} alone, and macOS never hands a double-clicked document over
 * as an argument - it sends an Apple event - so on a cold start the answer was always "there
 * is nothing to open" and the chooser always appeared.
 *
 * <p>
 * The tests that matter here are headless, which is what makes them worth having: the
 * property the fix rests on is "the request has reached this process before the decision is
 * made", and that can be set up with an event queue and no macOS plumbing at all.
 */
public class StartupLauncherTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /**
     * The defect itself.
     *
     * <p>
     * A document request is put on the event queue - which is where the JDK really delivers
     * it, and at a HIGHER priority than anything posted afterwards - and then startup runs
     * with no arguments, exactly as it does when Finder launches the application. Nothing
     * may be offered to the user: they already said what they wanted by double-clicking it.
     */
    @Test
    public void doesNotAskWhenAnAppleEventHasAlreadyAskedForADocument() throws Exception {
        File model = folder.newFile("model.rsf");
        CountingMain main = new CountingMain();

        EventQueue.invokeLater(() -> main.desktopRequestedOpen(List.of(model)));
        main.run(new String[0]);

        assertEquals("the chooser was shown even though a document was already on its way",
                0, main.launchersShown);
    }

    /** And with nothing on its way, it is still the right thing to show. */
    @Test
    public void asksWhenThereIsNothingToOpen() {
        CountingMain main = new CountingMain();

        main.run(new String[0]);

        assertEquals(1, main.launchersShown);
    }

    /**
     * Windows and Linux hand the path over as an argument, and that path must keep working
     * exactly as it did - it is the one platform where the old decision was correct.
     */
    @Test
    public void opensAnArgumentAndDoesNotAsk() throws Exception {
        File model = folder.newFile("named.rsf");
        CountingMain main = new CountingMain();

        main.run(new String[]{model.getAbsolutePath()});

        assertEquals(0, main.launchersShown);
        assertEquals(model.getAbsolutePath(), main.opened.getAbsolutePath());
    }

    /** A recovered session already produces a window; asking on top of it was never right. */
    @Test
    public void doesNotAskAfterASessionWasRecovered() {
        CountingMain main = new CountingMain();
        main.recoveredCount = 1;

        main.run(new String[0]);

        assertEquals(0, main.launchersShown);
    }

    /**
     * The barrier waits for work that is already queued, which is the whole of its claim.
     * If this ever stops holding, the fix above rests on nothing.
     */
    @Test
    public void theBarrierWaitsForWhatIsAlreadyOnTheEventQueue() {
        // No Assume: an event queue exists with or without a screen, and this is the claim
        // the whole fix rests on, so it should be pinned on the machine the build runs on.
        CountingMain main = new CountingMain();
        final boolean[] ran = {false};

        EventQueue.invokeLater(() -> ran[0] = true);
        main.awaitDesktopEventDelivery();

        assertTrue("the barrier returned before work queued ahead of it had run", ran[0]);
    }

    /**
     * Building the chooser must not BE the choice.
     *
     * <p>
     * Its constructor used to call {@code ok()} when "do not ask again" was set, and
     * {@code ok()} writes preferences and asks for a file to be opened. So a window nobody
     * had decided to show opened a model - and on a double-click that meant the previous
     * model opening alongside the one the user actually asked for.
     */
    @Test
    public void theConstructorDecidesNothing() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        // A file that exists, deliberately. Point the preference at a deleted one and the
        // old code does not fail this test - it hangs, because the choice it makes in the
        // constructor runs into a modal "file not found" dialog with nobody to dismiss it.
        // That is worth knowing on its own: merely building this window could block startup.
        File model = folder.newFile("remembered.rsf");
        Options.setBoolean("DO_NOT_ASK_AGAIN_FIRST_LOAD", true);
        Options.setInteger("FIRST_LOAD_TYPE", FirstSwitchFrame.OPEN_FILE);
        Options.setString("LAST_FILE_FIRST", model.getAbsolutePath());
        try {
            FirstSwitchFrame frame = new FirstSwitchFrame();
            try {
                assertFalse("constructing the chooser answered its own question",
                        frame.isOk());
            } finally {
                frame.dispose();
            }
        } finally {
            Options.setBoolean("DO_NOT_ASK_AGAIN_FIRST_LOAD", false);
        }
    }

    /**
     * A chooser that is not shown must not be left realized.
     *
     * <p>
     * Today the constructor's own {@code ok()} disposes it as a side effect. Taking that
     * away - which is the fix above - would otherwise leave a realized window behind, and a
     * realized window goes on taking part in the modal-blocking calculation of every later
     * dialog. Asserted on {@code isDisplayable} for the reason written down in
     * {@link RecoveryProgressTest}: it is the predicate that tells hidden from disposed.
     */
    @Test
    public void doesNotLeaveTheChooserRealizedWhenItIsNotShown() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        File model = folder.newFile("remembered.rsf");
        Options.setBoolean("DO_NOT_ASK_AGAIN_FIRST_LOAD", true);
        Options.setInteger("FIRST_LOAD_TYPE", FirstSwitchFrame.OPEN_FILE);
        Options.setString("LAST_FILE_FIRST", model.getAbsolutePath());
        try {
            int before = displayableWindows();

            // Not CountingMain: this is the one test that needs the real startupLauncher,
            // so only opening is stubbed out.
            Main main = new Main() {

                @Override
                public boolean open(File file) {
                    return true;
                }
            };
            main.startupLauncher(false);
            EventQueue.invokeAndWait(() -> {
            });

            assertEquals("a chooser that was never shown was left realized",
                    before, displayableWindows());
        } finally {
            Options.setBoolean("DO_NOT_ASK_AGAIN_FIRST_LOAD", false);
        }
    }

    private static int displayableWindows() {
        int count = 0;
        for (Window window : Window.getWindows())
            if (window.isDisplayable())
                count++;
        return count;
    }

    /**
     * Counts what was offered instead of offering it. Opening is stubbed out too: these
     * tests are about the decision, and a real open would want a database and a screen.
     */
    private static final class CountingMain extends Main {

        private int launchersShown = 0;

        private File opened;

        @Override
        public void startupLauncher(boolean showAnyway) {
            launchersShown++;
        }

        @Override
        public boolean open(File file) {
            opened = file;
            return true;
        }
    }
}
