package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Locale;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.GraphicsEnvironment;

import com.dsoft.pb.idef.elements.ProjectOptions;
import com.dsoft.pb.idef.elements.Status;
import com.ramussoft.common.Qualifier;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.idef.visual.MovingPanel;

/**
 * The frame around a diagram - author, project, dates, status.
 *
 * <p>
 * Two things here are easy to get wrong and impossible to see without reopening the file. The
 * project options are handed out as a copy, so anything set on them is lost unless they are
 * handed back. And a diagram's revision date is stamped with the time of any change to it, so
 * a revision written before the drawing is finished is silently replaced by "now".
 */
public class TitleBlockTest {

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

    @Test
    public void theTitleBlockSurvivesBeingSavedAndReopened() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        File file = new File(folder.getRoot(), "titled.rsf");
        SimpleDateFormat day = new SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH);

        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            Function base = plugin.getBaseFunction();

            ProjectOptions options = base.getProjectOptions();
            options.setProjectName("Практика 1.1");
            options.setProjectAutor("Киселев Я Д");
            options.setUsedAt("Учебная практика");
            // The copy has to be handed back, or none of the above happened.
            base.setProjectOptions(options);

            base.setAuthor("Киселев Я Д");
            base.setCreateDate(day.parse("05.09.2026"));
            base.setStatus(new Status(Status.DRAFT, null));
            base.setSystemRevDate(day.parse("06.09.2026"));
            base.setRevDate(day.parse("06.09.2026"));
            session.save();
        }

        try (ModelSession session = new ModelSession(file, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            Function base = session.getDataPlugin(model).getBaseFunction();

            ProjectOptions options = base.getProjectOptions();
            assertEquals("Практика 1.1", options.getProjectName());
            assertEquals("Киселев Я Д", options.getProjectAutor());
            assertEquals("Учебная практика", options.getUsedAt());

            assertEquals("Киселев Я Д", base.getAuthor());
            assertEquals("05.09.2026", day.format(base.getCreateDate()));
            assertEquals("06.09.2026", day.format(base.getRevDate()));
            assertEquals("the marker sits against the row that was chosen",
                    Status.DRAFT, base.getStatus().getType());
        }
    }

    /**
     * And the trap: drawing on a diagram stamps its revision with the time of the change, so
     * a revision set before the drawing is finished does not survive. That is the
     * application's own behaviour and it is right - the revision is when the sheet last
     * changed - but it decides the order the tools have to be called in.
     */
    @Test
    public void drawingOnADiagramStampsItsRevision() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        File file = new File(folder.getRoot(), "stamped.rsf");
        SimpleDateFormat day = new SimpleDateFormat("dd.MM.yyyy", Locale.ENGLISH);

        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            Function base = plugin.getBaseFunction();

            base.setRevDate(day.parse("01.01.2020"));
            base.setSystemRevDate(day.parse("01.01.2020"));

            DiagramBuilder builder = new DiagramBuilder(session, model, base);
            builder.addActivity("Работа", null, null);
            builder.commit();

            assertTrue("the revision set before the drawing is replaced by the change",
                    !"01.01.2020".equals(day.format(base.getRevDate())));

            // Set afterwards, it stands.
            base.setSystemRevDate(day.parse("06.09.2026"));
            base.setRevDate(day.parse("06.09.2026"));
            session.save();
        }

        try (ModelSession session = new ModelSession(file, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            Function base = session.getDataPlugin(model).getBaseFunction();
            assertEquals("06.09.2026", day.format(base.getRevDate()));
        }
    }
}
