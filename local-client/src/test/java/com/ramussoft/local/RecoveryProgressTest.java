package com.ramussoft.local;

import static org.junit.Assert.assertEquals;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.File;
import java.io.FileOutputStream;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The "restoring session" indicator must not outlive the recovery that shows it.
 *
 * <p>It used to, on three of the four ways out, and the consequence was not where anyone
 * would look for it: a 292x16 window stranded at the centre of the screen sat over the text
 * fields of the project wizard that opens next, and swallowed every click aimed at them.
 * Tab and typing went on working, because they go to the focus owner rather than to a point
 * on screen, which is what made it look like a focus bug rather than a leaked window.
 *
 * <p>The assertion is on {@code isDisplayable}, deliberately. It stays true after
 * {@code setVisible(false)} and only turns false after {@code dispose}, so it is the one
 * predicate that tells the two apart - and the difference matters, because a hidden but
 * realized window still takes part in the modal-blocking calculation of every later dialog.
 * Asserting on membership of {@code Window.getWindows()} would not work: that list holds weak
 * references and a disposed window stays in it until a garbage collection nobody can force.
 */
public class RecoveryProgressTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static int displayableWindows() {
        int count = 0;
        for (Window window : Window.getWindows())
            if (window.isDisplayable())
                count++;
        return count;
    }

    /** Nothing to recover: the session holds no journals, so recovery gives up at once. */
    @Test
    public void closesTheIndicatorWhenThereIsNothingToRecover() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        File session = folder.newFolder("empty-session");

        int before = displayableWindows();
        new Runner().recoverySession(session.getAbsolutePath(), null);

        assertEquals("the recovery indicator was left realized",
                before, displayableWindows());
    }

    /**
     * And the path the crash logs actually show: opening the database of a half-written
     * session throws, which is the normal outcome of the crash this feature recovers from.
     */
    @Test
    public void closesTheIndicatorWhenRecoveryThrows() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        File session = folder.newFolder("corrupt-session");
        try (FileOutputStream out = new FileOutputStream(new File(session, "source.rms"))) {
            out.write("this is not a model".getBytes("UTF-8"));
        }

        int before = displayableWindows();
        try {
            new Runner().recoverySession(session.getAbsolutePath(), new File("Model.rsf"));
        } catch (Exception expected) {
            // the throw is the point of this case, not the assertion
        }

        assertEquals("the recovery indicator was left realized after a failed recovery",
                before, displayableWindows());
    }
}
