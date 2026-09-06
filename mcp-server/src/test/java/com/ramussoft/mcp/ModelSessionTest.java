package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Element;
import com.ramussoft.common.Qualifier;

/**
 * The premise of this whole module, checked first because everything else rests on it: a
 * real model opens with no interface at all.
 *
 * <p>
 * The application is built entirely around a Swing frame, so this is not obvious - but
 * {@code project-navigator} has always opened models this way to serve them as HTML, and
 * {@code ModelSession} does the same. The test uses the sample model that ships in the
 * repository, so it is reproducible on any machine and needs nothing installed.
 */
public class ModelSessionTest {

    /**
     * Opening a model creates a session directory with a lock file, and its location is
     * derived from user.home. Left alone, a test run would litter the developer's real
     * settings folder with sessions for a file they never opened - and worse, leave the
     * application offering to recover them.
     */
    private static String realUserHome;

    private static Path sandbox;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @BeforeClass
    public static void redirectTheSettingsDirectory() throws Exception {
        realUserHome = System.getProperty("user.home");
        sandbox = Files.createTempDirectory("ramus-mcp-home");
        System.setProperty("user.home", sandbox.toString());
    }

    @AfterClass
    public static void restoreTheSettingsDirectory() {
        if (realUserHome != null)
            System.setProperty("user.home", realUserHome);
    }

    @Test
    public void aShippedModelOpensWithNoInterface() throws Exception {
        try (ModelSession session = open("dest/doc/ru/Model example.rsf")) {
            List<Qualifier> qualifiers = session.getEngine().getQualifiers();

            assertFalse("the model has catalogs", qualifiers.isEmpty());

            // Not a fixed number: the point is that the plugins registered and the model
            // decoded, not that this particular file has N catalogs. A count assertion here
            // would fail every time the sample is edited, for no benefit.
            long withElements = 0;
            for (Qualifier q : qualifiers)
                if (session.getEngine().getElementCountForQualifier(q.getId()) > 0)
                    withElements++;
            assertTrue("at least one catalog holds elements", withElements > 0);
        }
    }

    /**
     * Attribute plugins register through META-INF/services, and if that discovery fails the
     * model still opens - it just has no readable values. That failure is silent, which is
     * exactly why it is asserted.
     */
    @Test
    public void attributesAreTypedAndReadable() throws Exception {
        try (ModelSession session = open("dest/doc/ru/Model example.rsf")) {
            int read = 0;
            for (Qualifier q : session.getEngine().getQualifiers()) {
                List<Attribute> attributes = q.getAttributes();
                if (attributes.isEmpty())
                    continue;
                List<Element> elements = session.getEngine().getElements(q.getId());
                for (Element e : elements.subList(0, Math.min(3, elements.size())))
                    for (Attribute a : attributes) {
                        session.getEngine().getAttribute(e, a);
                        read++;
                    }
            }
            assertTrue("attribute values are reachable", read > 0);
        }
    }

    /**
     * The round trip that matters: change nothing, save, reopen, and find the same model.
     * If the schema, the type names or the date format ever break, this is what says so.
     */
    @Test
    public void aModelSurvivesBeingSavedAndReopened() throws Exception {
        File copy = copyOfSample("dest/doc/ru/Model example.rsf");
        int before;
        try (ModelSession session = new ModelSession(copy, false)) {
            before = session.getEngine().getQualifiers().size();
            session.save();
        }
        try (ModelSession session = new ModelSession(copy, false)) {
            assertEquals("the reopened model has the same catalogs", before,
                    session.getEngine().getQualifiers().size());
        }
    }

    /**
     * A saved model must say which diagram to open, or it opens on an empty canvas.
     *
     * <p>
     * This is not a detail of presentation. Ramus Next does not work out what to show from
     * the model: on opening a file it deserialises a list of "open this view" events from
     * {@code /user/gui/session.binary} inside the file and replays them. A model built
     * through this server was complete in every field and still came up blank, because
     * nothing had written that stream. The check is done the way the application reads it -
     * deserialise, and look at what is in the list.
     */
    @Test
    public void aSavedModelSaysWhichDiagramToOpen() throws Exception {
        File file = new File(folder.getRoot(), "new.rsf");
        long modelId;
        try (ModelSession session = ModelSession.createNew(file)) {
            modelId = session.addModel("Модель", -1).getId();
            session.save();
        }

        List<?> events;
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(file)) {
            java.util.zip.ZipEntry entry = archive.getEntry("user/gui/session.binary");
            assertTrue("the file records which diagram to open", entry != null);
            try (java.io.ObjectInputStream in =
                         new java.io.ObjectInputStream(archive.getInputStream(entry))) {
                events = (List<?>) in.readObject();
            }
        }

        assertEquals("one view to open", 1, events.size());
        com.ramussoft.gui.common.event.ActionEvent event =
                (com.ramussoft.gui.common.event.ActionEvent) events.get(0);
        assertEquals(com.ramussoft.idef0.IDEF0ViewPlugin.OPEN_DIAGRAM, event.getKey());
        com.ramussoft.idef0.OpenDiagram open =
                (com.ramussoft.idef0.OpenDiagram) event.getValue();
        assertEquals("the model that was created", modelId, open.getQualifier().getId());
        assertEquals("its context diagram", -1L, open.getFunctionId());
    }

    /**
     * And the record the application wrote is left alone: those are the tabs a person had
     * open, and an agent saving the file must not decide otherwise for them.
     */
    @Test
    public void anExistingRecordOfOpenDiagramsIsNotOverwritten() throws Exception {
        File copy = copyOfSample("dest/doc/ru/Model example.rsf");
        byte[] before = sessionRecordOf(copy);
        assertTrue("the sample was saved by the application, so it has one",
                before != null && before.length > 0);

        try (ModelSession session = new ModelSession(copy, false)) {
            session.save();
        }

        assertArrayEquals("the person's own open tabs survive an agent's save",
                before, sessionRecordOf(copy));
    }

    private byte[] sessionRecordOf(File model) throws IOException {
        try (java.util.zip.ZipFile archive = new java.util.zip.ZipFile(model)) {
            java.util.zip.ZipEntry entry = archive.getEntry("user/gui/session.binary");
            if (entry == null)
                return null;
            try (java.io.InputStream in = archive.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /**
     * The backup is taken before the first CHANGE, not before the first save - so a session
     * that changes something and then crashes still leaves the model as it was found.
     */
    @Test
    public void aBackupIsWrittenBeforeTheFirstChangeAndOnlyOnce() throws Exception {
        File copy = copyOfSample("dest/doc/ru/Model example.rsf");
        long original = copy.length();

        try (ModelSession session = new ModelSession(copy, false)) {
            assertEquals("nothing is copied until something changes", 1,
                    folder.getRoot().list().length);
            session.markChanged();
            session.markChanged();
        }

        File backup = new File(folder.getRoot(), copy.getName() + ".backup");
        assertTrue("a backup is written", backup.isFile());
        assertEquals("and it is the model as it was found", original, backup.length());
        assertEquals("one change, one backup - not one per change", 2,
                folder.getRoot().list().length);
    }

    /** A read-only session must not be able to change or save anything. */
    @Test
    public void aReadOnlySessionRefusesToChangeOrSave() throws Exception {
        File copy = copyOfSample("dest/doc/ru/Model example.rsf");
        try (ModelSession session = new ModelSession(copy, true)) {
            try {
                session.markChanged();
                fail("a read-only session must refuse to change the model");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("read-only"));
            }
            try {
                session.save();
                fail("a read-only session must refuse to save");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("read-only"));
            }
        }
    }

    /**
     * The whole point of writing through a temporary file: the model that is there must
     * survive a save that does not finish. Simulated by making the target unwritable, which
     * is the closest a test can get to a disk filling up mid-write.
     */
    @Test
    public void aFailedSaveLeavesTheModelIntact() throws Exception {
        File copy = copyOfSample("dest/doc/ru/Model example.rsf");
        byte[] before = Files.readAllBytes(copy.toPath());

        File readOnlyDirectory = folder.newFolder("locked");
        File inside = new File(readOnlyDirectory, copy.getName());
        Files.copy(copy.toPath(), inside.toPath());

        try (ModelSession session = new ModelSession(inside, false)) {
            session.markChanged();
            assertTrue("the test needs the directory to become unwritable",
                    readOnlyDirectory.setWritable(false));
            try {
                session.save();
                // Some filesystems and a root user ignore the permission; then this test
                // proves nothing and says so rather than passing quietly.
                Assume.assumeTrue("could not make the directory unwritable", false);
            } catch (IOException expected) {
                // what a full disk would look like
            }
        } finally {
            readOnlyDirectory.setWritable(true);
        }

        assertArrayEquals("the model on disk is untouched by a failed save",
                before, Files.readAllBytes(inside.toPath()));
    }

    private ModelSession open(String relative) throws Exception {
        return new ModelSession(copyOfSample(relative), true);
    }

    /**
     * Always a copy. Opening writes a session beside the model and saving would rewrite it;
     * neither belongs anywhere near a file tracked in the repository.
     */
    private File copyOfSample(String relative) throws Exception {
        Path source = repositoryRoot().resolve(relative);
        assertTrue(source + " must exist", Files.exists(source));
        File target = new File(folder.getRoot(), source.getFileName().toString());
        Files.copy(source, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static Path repositoryRoot() {
        Path path = new File(".").getAbsoluteFile().toPath().normalize();
        while (path != null && !Files.exists(path.resolve("settings.gradle")))
            path = path.getParent();
        if (path == null)
            throw new IllegalStateException("no settings.gradle above "
                    + new File(".").getAbsolutePath());
        return path;
    }
}
