package com.ramussoft.pb.idef.frames;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Upstream issue #24, "Can not export as pictures", where the reporter's complaint is that
 * the first picture cannot be opened.
 *
 * <p>
 * The export used to open its stream on the target file before anything had been drawn,
 * while the actual encode is the last statement of the painter - so a failure anywhere in
 * between left a zero-byte file carrying a valid image extension, and the export reported
 * success. A file that is not there is a far better outcome than a file that looks like an
 * image and is not: the first is visible to the user, the second is not.
 */
public class ExportToImagesAtomicWriteTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aSuccessfulWriteLandsOnTheTargetName() throws Exception {
        File target = new File(folder.getRoot(), "01_A0.png");

        ExportToImagesDialog.writeAtomically(target, stream -> stream.write(new byte[]{1, 2, 3}));

        assertTrue(target.exists());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.toPath()));
    }

    /**
     * The regression. An unchecked failure is the interesting one, because that is what
     * rendering throws and what the old code did not catch.
     */
    @Test
    public void aFailedRenderLeavesNoFileAtAll() {
        File target = new File(folder.getRoot(), "01_A0.png");

        try {
            ExportToImagesDialog.writeAtomically(target, stream -> {
                stream.write(new byte[]{1, 2, 3});
                throw new IllegalStateException("rendering blew up");
            });
            fail("the failure must propagate so the caller can report it");
        } catch (IllegalStateException expected) {
            // the export thread turns this into a message to the user
        } catch (IOException e) {
            fail("wrong exception: " + e);
        }

        assertFalse("A zero-byte file with a valid image extension is exactly the "
                + "reported defect - the user opens it and it is not an image.",
                target.exists());
        assertEquals("No partial file may be left behind either.",
                0, folder.getRoot().list().length);
    }

    /**
     * An IOException must behave the same way. It is the one case the old code did handle,
     * and it handled it by leaving the truncated file on disk.
     */
    @Test
    public void aFailedWriteLeavesNoFileAtAll() {
        File target = new File(folder.getRoot(), "02_A1.png");

        try {
            ExportToImagesDialog.writeAtomically(target, stream -> {
                throw new IOException("disk full");
            });
            fail("the failure must propagate");
        } catch (IOException expected) {
            assertEquals("disk full", expected.getMessage());
        }

        assertFalse(target.exists());
        assertEquals(0, folder.getRoot().list().length);
    }

    /**
     * Re-exporting into a directory that already holds the previous run must still replace
     * the old picture, and must not leave the old one behind if the new render fails.
     */
    @Test
    public void anExistingFileIsReplacedOnSuccessAndKeptIntactUntilThen() throws Exception {
        File target = folder.newFile("01_A0.png");
        Files.write(target.toPath(), new byte[]{9, 9, 9});

        try {
            ExportToImagesDialog.writeAtomically(target, stream -> {
                throw new IllegalStateException("rendering blew up");
            });
            fail("the failure must propagate");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertArrayEquals("The previous export must survive a failed re-export.",
                new byte[]{9, 9, 9}, Files.readAllBytes(target.toPath()));

        ExportToImagesDialog.writeAtomically(target, stream -> stream.write(new byte[]{7}));
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(target.toPath()));
        assertEquals(1, folder.getRoot().list().length);
    }
}
