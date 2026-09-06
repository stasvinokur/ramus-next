package com.ramussoft.mcp;

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
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Not an assertion - a reading.
 *
 * <p>
 * Before anything can WRITE a diagram, the exact shape of one has to be known, and reading
 * the code does not give it: what a border arrow puts in borderType and crosspoint is a fact
 * about the data, not about the source. So this prints a real context diagram field by
 * field, from the model that ships with the application, and the builder is then made to
 * produce the same shape.
 *
 * <p>
 * Kept in the tree rather than thrown away: when a generated diagram opens empty, this is
 * the thing to run first, and the comparison it enables is the only way to see why.
 */
public class DiagramShapeProbeTest {

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
    public void printTheShapeOfARealDiagram() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        Path source = repositoryRoot().resolve("dest/doc/ru/Model example.rsf");
        File copy = new File(folder.getRoot(), "probe.rsf");
        Files.copy(source, copy.toPath(), StandardCopyOption.REPLACE_EXISTING);

        try (ModelSession session = new ModelSession(copy, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            DataPlugin plugin = session.getDataPlugin(model);

            Function base = plugin.getBaseFunction();
            report(plugin, base, "THE CONTEXT DIAGRAM - the decomposition of the base row");

            for (Row child : plugin.getChilds(base, true))
                if (child instanceof Function) {
                    report(plugin, (Function) child, "ONE LEVEL DOWN");
                    break;
                }
        }
    }

    private void report(DataPlugin plugin, Function parent, String title) {
        System.err.println();
        System.err.println("=== " + title + ": " + parent.getName());
        System.err.println("    parent id=" + parent.getElement().getId()
                + " bounds=" + parent.getBounds()
                + " decompositionType=" + parent.getDecompositionType());

        for (Row child : plugin.getChilds(parent, true))
            if (child instanceof Function) {
                Function f = (Function) child;
                System.err.println("    box  id=" + f.getElement().getId()
                        + " type=" + f.getType()
                        + " bounds=" + f.getBounds()
                        + "  " + f.getName());
            }

        MovingArea area = PIDEF0painter.createMovingArea(new Dimension(1600, 1200),
                plugin, parent);
        area.setActiveFunction(parent);
        Vector<PaintSector> painted = area.getRefactor().getSectors();
        System.err.println("    sectors in the refactor: "
                + (painted == null ? "null" : painted.size()));

        int n = 0;
        for (Sector sector : parent.getSectors()) {
            System.err.println("    arrow  createState=" + sector.getCreateState()
                    + " createPos=" + sector.getCreatePos()
                    + "  name=" + sector.getName());
            describe("start", sector.getStart());
            describe("end  ", sector.getEnd());
            if (++n >= 8) {
                System.err.println("    ... (only the first 8 shown)");
                break;
            }
        }
    }

    private void describe(String which, NSectorBorder border) {
        if (border == null) {
            System.err.println("        " + which + ": null");
            return;
        }
        System.err.println("        " + which
                + ": function=" + (border.getFunction() == null
                        ? "null" : border.getFunction().getElement().getId())
                + " functionType=" + border.getFunctionType()
                + " borderType=" + border.getBorderType()
                + " crosspoint=" + (border.getCrosspoint() == null
                        ? "null" : border.getCrosspoint().getGlobalId())
                + " tunnel=" + border.getTunnelType());
    }

    private static Path repositoryRoot() {
        Path path = new File(".").getAbsoluteFile().toPath().normalize();
        while (path != null && !Files.exists(path.resolve("settings.gradle")))
            path = path.getParent();
        return path;
    }
}
