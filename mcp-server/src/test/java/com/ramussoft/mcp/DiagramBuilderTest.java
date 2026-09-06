package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

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
import com.ramussoft.pb.idef.visual.MovingPanel;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Building the context diagram from the task sheet, and finding it again afterwards.
 *
 * <p>
 * The assertion that matters is the last one: everything is written, the file is closed, and
 * a FRESH session reads the arrows back with the roles they were given. A diagram that only
 * exists in the panel that made it is not a diagram.
 */
public class DiagramBuilderTest {

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
    public void buildsTheContextDiagramAndFindsItAgain() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        // Not the temporary folder: a diagram is judged by looking at it, and a picture
        // deleted when the test ends cannot be looked at. This one stays under build/.
        File output = new File("build/diagram-builder");
        output.mkdirs();
        File file = new File(output, "context.rsf");

        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Формирование Технического проекта (ТП)", -1);
            DataPlugin plugin = session.getDataPlugin(model);

            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function a0 = builder.addActivity("Формирование Технического проекта (ТП)",
                    null, null);

            arrive(builder, a0, "Утверждённое ТЗ", "input");
            arrive(builder, a0, "Уточняющие данные", "input");
            arrive(builder, a0, "Утверждённый эскиз проекта", "input");
            arrive(builder, a0, "Стандарт", "control");
            arrive(builder, a0, "Программист", "mechanism");
            arrive(builder, a0, "Бизнес-аналитик", "mechanism");
            arrive(builder, a0, "Проектировщик", "mechanism");
            builder.addArrow("Технический проект",
                    DiagramBuilder.End.on(a0, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));

            builder.commit();
            session.save();
        }

        // A fresh session: nothing of the panel that built it survives here.
        try (ModelSession session = new ModelSession(file, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            DataPlugin plugin = session.getDataPlugin(model);
            Function base = plugin.getBaseFunction();

            List<Function> boxes = new ArrayList<>();
            for (Row row : plugin.getChilds(base, true))
                if (row instanceof Function)
                    boxes.add((Function) row);
            assertEquals("one activity on the context diagram", 1, boxes.size());
            assertEquals("Формирование Технического проекта (ТП)", boxes.get(0).getName());

            List<String> inputs = new ArrayList<>();
            List<String> controls = new ArrayList<>();
            List<String> mechanisms = new ArrayList<>();
            List<String> outputs = new ArrayList<>();
            for (Sector sector : base.getSectors()) {
                collect(sector.getStart(), sector, inputs, controls, mechanisms, outputs);
                collect(sector.getEnd(), sector, inputs, controls, mechanisms, outputs);
            }

            System.err.println("inputs:     " + inputs);
            System.err.println("controls:   " + controls);
            System.err.println("mechanisms: " + mechanisms);
            System.err.println("outputs:    " + outputs);

            assertEquals("three inputs", 3, inputs.size());
            assertEquals("one control", 1, controls.size());
            assertEquals("three mechanisms", 3, mechanisms.size());
            assertEquals("one output", 1, outputs.size());
            assertTrue(inputs.contains("Утверждённое ТЗ"));
            assertTrue(controls.contains("Стандарт"));
            assertTrue(mechanisms.contains("Бизнес-аналитик"));
            assertTrue(outputs.contains("Технический проект"));

            // And it draws. An empty picture is what a broken visual blob looks like.
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            new PIDEF0painter(base, new Dimension(1600, 1200), plugin)
                    .writeToStream(png, PIDEF0painter.PNG_FORMAT);
            assertTrue("the diagram renders", png.size() > 10000);
            File picture = new File(output, "context.png");
            Files.write(picture.toPath(), png.toByteArray());
            System.err.println("png bytes: " + png.size());
            System.err.println("png at:    " + picture.getAbsolutePath());
            System.err.println("rsf at:    " + file.getAbsolutePath());
        }
    }

    /**
     * Decomposing: the arrows of the diagram above arrive here as stubs with one end loose,
     * and drawing them again has to connect those rather than add a second copy of each.
     */
    @Test
    public void aDecompositionConnectsTheArrowsItInherits() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        File file = new File(folder.getRoot(), "decomposed.rsf");

        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);

            DiagramBuilder context = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function a0 = context.addActivity("Работа", null, null);
            context.addArrow("Заявка", DiagramBuilder.End.frame(MovingPanel.LEFT),
                    DiagramBuilder.End.on(a0, MovingPanel.LEFT));
            context.addArrow("Результат", DiagramBuilder.End.on(a0, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));
            context.commit();

            // A new builder, as every tool call gets: the stub is found in the model rather
            // than remembered from the call that made it.
            DiagramBuilder below = new DiagramBuilder(session, model, a0);
            Function a1 = below.addActivity("Приём", null, null);
            Function a2 = below.addActivity("Исполнение", null, null);
            below.addArrow("Заявка", DiagramBuilder.End.frame(MovingPanel.LEFT),
                    DiagramBuilder.End.on(a1, MovingPanel.LEFT));
            below.addArrow("Принятая заявка", DiagramBuilder.End.on(a1, MovingPanel.RIGHT),
                    DiagramBuilder.End.on(a2, MovingPanel.LEFT));
            below.addArrow("Результат", DiagramBuilder.End.on(a2, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));
            below.commit();
            session.save();
        }

        try (ModelSession session = new ModelSession(file, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            DataPlugin plugin = session.getDataPlugin(model);
            Function a0 = (Function) plugin.getChilds(plugin.getBaseFunction(), true).get(0);

            int loose = 0;
            List<String> names = new ArrayList<>();
            for (Sector sector : a0.getSectors()) {
                names.add(sector.getName());
                if (sector.getStart().getFunction() == null
                        && sector.getStart().getBorderType() < 0)
                    loose++;
                if (sector.getEnd().getFunction() == null
                        && sector.getEnd().getBorderType() < 0)
                    loose++;
            }
            System.err.println("the child diagram holds: " + names);

            assertEquals("one arrow per name, no second copy of the inherited ones",
                    1, java.util.Collections.frequency(names, "Заявка"));
            assertEquals(1, java.util.Collections.frequency(names, "Результат"));
            assertEquals("nothing left dangling", 0, loose);

            // And one stream, not one per drawing of it: the same name on two diagrams is
            // how a model says the same thing flows through both, and it is what the report
            // queries follow.
            int streams = 0;
            for (Row row : plugin.getRecChilds(plugin.getBaseStream(), true))
                if ("Заявка".equals(row.getName()))
                    streams++;
            assertEquals("the arrow drawn on both levels is one stream", 1, streams);
        }
    }

    /**
     * Taking things off a diagram again. The arrows of a removed box have to go with it: one
     * left pointing at a box that is not there is the corruption that opens as an empty page.
     */
    @Test
    public void removingAnActivityTakesItsArrowsWithIt() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        File file = new File(folder.getRoot(), "removed.rsf");

        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);

            DiagramBuilder context = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function a0 = context.addActivity("Работа", null, null);
            context.commit();

            DiagramBuilder below = new DiagramBuilder(session, model, a0);
            Function a1 = below.addActivity("Приём", null, null);
            Function a2 = below.addActivity("Исполнение", null, null);
            below.addArrow("Заявка", DiagramBuilder.End.frame(MovingPanel.LEFT),
                    DiagramBuilder.End.on(a1, MovingPanel.LEFT));
            below.addArrow("Принятая заявка", DiagramBuilder.End.on(a1, MovingPanel.RIGHT),
                    DiagramBuilder.End.on(a2, MovingPanel.LEFT));
            below.addArrow("Результат", DiagramBuilder.End.on(a2, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));
            // Touches only the box that stays, so it is what proves the removal took the
            // arrows of one box and not simply all of them.
            below.addArrow("Отчёт", DiagramBuilder.End.on(a1, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));
            below.commit();

            DiagramBuilder editing = new DiagramBuilder(session, model, a0);
            assertEquals(1, editing.removeArrow("Заявка"));
            editing.removeActivity(a2);
            editing.commit();
            session.save();
        }

        try (ModelSession session = new ModelSession(file, true)) {
            Qualifier model = IDEF0Plugin.getBaseQualifiers(session.getEngine()).get(0);
            DataPlugin plugin = session.getDataPlugin(model);
            Function a0 = (Function) plugin.getChilds(plugin.getBaseFunction(), true).get(0);

            List<String> left = new ArrayList<>();
            for (Row row : plugin.getChilds(a0, true))
                if (row instanceof Function)
                    left.add(row.getName());
            assertEquals("only the box that was not removed", List.of("Приём"), left);

            List<String> arrows = new ArrayList<>();
            for (Sector sector : a0.getSectors()) {
                arrows.add(sector.getName());
                for (NSectorBorder border : new NSectorBorder[]{sector.getStart(),
                        sector.getEnd()})
                    if (border.getFunction() != null)
                        assertTrue("an arrow still points at a box that was removed",
                                plugin.getChilds(a0, true).contains(border.getFunction()));
            }
            System.err.println("after removing: " + arrows);
            assertTrue("the removed arrow is gone", !arrows.contains("Заявка"));
            assertTrue("the arrows of the removed box went with it",
                    !arrows.contains("Принятая заявка") && !arrows.contains("Результат"));
            assertTrue("the arrow of the box that stayed is still there",
                    arrows.contains("Отчёт"));

            // And it still draws: the check that catches a diagram left in pieces.
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            new PIDEF0painter(a0, new Dimension(1600, 1200), plugin)
                    .writeToStream(png, PIDEF0painter.PNG_FORMAT);
            assertTrue("the diagram still renders", png.size() > 10000);
        }
    }

    /** An arrow that arrives at the activity: the frame is the start, the box is the end. */
    private void arrive(DiagramBuilder builder, Function activity, String name, String role) {
        int side = DiagramBuilder.roleOf(role);
        builder.addArrow(name, DiagramBuilder.End.frame(side),
                DiagramBuilder.End.on(activity, side));
    }

    private void collect(NSectorBorder border, Sector sector, List<String> inputs,
                         List<String> controls, List<String> mechanisms,
                         List<String> outputs) {
        if (border == null || border.getFunction() == null)
            return;
        String name = sector.getName();
        if (name == null || name.isEmpty())
            return;
        switch (border.getFunctionType()) {
            case MovingPanel.LEFT:
                inputs.add(name);
                break;
            case MovingPanel.TOP:
                controls.add(name);
                break;
            case MovingPanel.BOTTOM:
                mechanisms.add(name);
                break;
            case MovingPanel.RIGHT:
                outputs.add(name);
                break;
            default:
                break;
        }
    }
}
