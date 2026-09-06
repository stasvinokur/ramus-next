package com.ramussoft.mcp;

import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Vector;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Qualifier;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Row;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Whether the arrows of a diagram are reachable without a screen.
 *
 * <p>
 * They are not a property of an activity that can simply be read: the arrows of a diagram
 * live in a blob of visual data, and the thing that decodes it is
 * {@code SectorRefactor}, which belongs to a Swing panel. The web export drives that panel
 * headlessly through {@code PIDEF0painter.createMovingArea}, so the path exists - this test
 * is what says whether it works outside the application, which is the question that decides
 * whether an agent can be told what a process consumes and produces.
 */
public class DiagramProbeTest {

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
    public void theArrowsOfADiagramCanBeReadWithNoScreen() throws Exception {
        Assume.assumeFalse("needs a graphics environment for font metrics",
                GraphicsEnvironment.isHeadless());

        Path source = repositoryRoot().resolve("dest/doc/ru/Model example.rsf");
        File copy = new File(folder.getRoot(), "probe.rsf");
        Files.copy(source, copy.toPath(), StandardCopyOption.REPLACE_EXISTING);

        try (ModelSession session = new ModelSession(copy, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            DataPlugin plugin = session.getDataPlugin(model);

            // The base function is a container; the diagram worth looking at is the one
            // under the real top activity.
            Function top = (Function) plugin.getChilds(plugin.getBaseFunction(), true).get(0);

            MovingArea area = PIDEF0painter.createMovingArea(
                    new Dimension(1024, 768), plugin, top);
            // createMovingArea only sizes the panel; what decodes the visual blob into
            // arrows is setActiveFunction, which calls SectorRefactor.loadFromFunction.
            area.setActiveFunction(top);
            Vector<PaintSector> sectors = area.getRefactor().getSectors();

            System.err.println("activity: " + top.getName());
            System.err.println("children: " + plugin.getChilds(top, true).size());
            System.err.println("sectors:  " + (sectors == null ? "null" : sectors.size()));
            if (sectors != null)
                for (int i = 0; i < Math.min(6, sectors.size()); i++) {
                    PaintSector s = sectors.get(i);
                    System.err.println("   " + s.getSector().getName());
                }

            assertTrue("the diagram under " + top.getName() + " has arrows",
                    sectors != null && !sectors.isEmpty());
        }
    }

    private static Path repositoryRoot() {
        Path path = new File(".").getAbsoluteFile().toPath().normalize();
        while (path != null && !Files.exists(path.resolve("settings.gradle")))
            path = path.getParent();
        return path;
    }
}
