package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Writing over a file that is already there.
 *
 * <p>
 * A script that builds a model cannot be run twice: the second run stops at "already exists",
 * and the only way on is to delete the file by hand, outside the server, which is the one
 * thing this is meant to save anyone from. So overwriting is allowed - but it is the only
 * operation here that destroys work, and the three conditions on it are what these tests are
 * about. It must be a Ramus model. It must not be open. And a copy is kept.
 */
public class OverwriteTest {

    private static String realUserHome;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void redirectTheSettingsDirectory() throws Exception {
        realUserHome = System.getProperty("user.home");
        System.setProperty("user.home",
                Files.createTempDirectory("ramus-mcp-home").toString());
    }

    @AfterClass
    public static void restoreTheSettingsDirectory() {
        if (realUserHome != null)
            System.setProperty("user.home", realUserHome);
    }

    /** The refusal has to name the way past it, or an agent deletes the file instead. */
    @Test
    public void withoutOverwriteAnExistingModelIsRefusedAndTheFlagIsNamed() throws Exception {
        Workspace workspace = new Workspace(false);
        File first = new File(folder.getRoot(), "first.rsf");
        FileTools.create(workspace, Map.of("path", first.getPath(), "name", "Первая"));

        try {
            FileTools.create(workspace, Map.of("path", first.getPath(), "name", "Вторая"));
            fail("an existing file must not be silently replaced");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("overwrite"));
        } finally {
            workspace.close();
        }
    }

    /** And with it, the model that was there is copied before it goes. */
    @Test
    public void overwritingKeepsACopyOfWhatWasThere() throws Exception {
        Workspace workspace = new Workspace(false);
        File path = new File(folder.getRoot(), "twice.rsf");
        FileTools.create(workspace, Map.of("path", path.getPath(), "name", "Первая"));
        workspace.close();
        byte[] before = Files.readAllBytes(path.toPath());

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) FileTools.create(workspace,
                Map.of("path", path.getPath(), "name", "Вторая", "overwrite", true));
        workspace.close();

        File copy = new File(folder.getRoot(), "twice.rsf.backup");
        assertEquals(copy.getAbsolutePath(), out.get("replaced_model_copied_to"));
        assertTrue("the copy is on disk", copy.isFile());
        org.junit.Assert.assertArrayEquals("and it is what was there",
                before, Files.readAllBytes(copy.toPath()));

        try (ModelSession reopened = new ModelSession(path, true)) {
            assertEquals("Вторая", com.ramussoft.idef0.IDEF0Plugin
                    .getBaseQualifiers(reopened.getEngine()).get(0).getName());
        }
    }

    /**
     * The condition that keeps the flag from being a way to delete arbitrary files. A tool
     * that makes models may replace a model; it may not replace a photograph because someone
     * gave it the wrong path.
     */
    @Test
    public void aFileThatIsNotAModelIsNeverOverwritten() throws Exception {
        Workspace workspace = new Workspace(false);
        File notAModel = new File(folder.getRoot(), "notes.rsf");
        Files.write(notAModel.toPath(), "these are somebody's notes".getBytes("UTF-8"));

        try {
            FileTools.create(workspace, Map.of("path", notAModel.getPath(),
                    "name", "Модель", "overwrite", true));
            fail("only a Ramus model may be overwritten");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().contains("not a Ramus model"));
        }

        assertEquals("these are somebody's notes",
                new String(Files.readAllBytes(notAModel.toPath()), "UTF-8"));
    }

    /**
     * Saving to the path the model is already open at.
     *
     * <p>
     * This used to run the whole save-as and then report the file it had just written over as
     * "original_untouched" - a statement an agent has no way to check and every reason to
     * believe.
     */
    @Test
    public void savingToItsOwnPathIsASaveAndDoesNotClaimAnOriginalSurvives() throws Exception {
        Workspace workspace = new Workspace(false);
        File path = new File(folder.getRoot(), "itself.rsf");
        FileTools.create(workspace, Map.of("path", path.getPath(), "name", "Модель"));

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>)
                FileTools.saveAs(workspace, Map.of("path", path.getPath()));
        workspace.close();

        assertEquals(path.getAbsolutePath(), out.get("saved"));
        assertNull("nothing survived elsewhere, because nothing went elsewhere",
                out.get("original_untouched"));
        assertFalse("and no copy was taken of a file that was not replaced",
                new File(folder.getRoot(), "itself.rsf.backup").isFile());
    }

    /** save_model_as over another model: same three conditions, same copy. */
    @Test
    public void savingOverAnotherModelCopiesItFirst() throws Exception {
        Workspace workspace = new Workspace(false);
        File older = new File(folder.getRoot(), "older.rsf");
        FileTools.create(workspace, Map.of("path", older.getPath(), "name", "Старая"));
        workspace.close();

        File working = new File(folder.getRoot(), "working.rsf");
        FileTools.create(workspace, Map.of("path", working.getPath(), "name", "Новая"));

        try {
            FileTools.saveAs(workspace, Map.of("path", older.getPath()));
            fail("an existing model must not be replaced without being asked");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("overwrite"));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) FileTools.saveAs(workspace,
                Map.of("path", older.getPath(), "overwrite", true));
        workspace.close();

        assertEquals(new File(folder.getRoot(), "older.rsf.backup").getAbsolutePath(),
                out.get("replaced_model_copied_to"));
        try (ModelSession reopened = new ModelSession(older, true)) {
            assertEquals("Новая", com.ramussoft.idef0.IDEF0Plugin
                    .getBaseQualifiers(reopened.getEngine()).get(0).getName());
        }
    }
}
