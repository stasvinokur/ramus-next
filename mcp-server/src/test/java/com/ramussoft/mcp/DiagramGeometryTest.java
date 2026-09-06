package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.ramussoft.common.Qualifier;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.idef.visual.MovingPanel;

/**
 * Reading back what was drawn.
 *
 * <p>
 * An agent building a diagram through this server had no way to see the result except as a
 * picture: the page size, the margin, where a box actually ended up and how an arrow actually
 * ran were all computed inside and thrown away. So it guessed, and its guesses disagreed with
 * the page - which is only visible once the numbers can be asked for, which is what these
 * tests are about.
 */
public class DiagramGeometryTest {

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

    /**
     * The page, and the margin that is not advice.
     *
     * <p>
     * These three numbers decide every coordinate an agent chooses, and until now the only
     * one written down anywhere was the width. The margin in particular is not a suggestion:
     * an arrow told to start at x=0 comes back starting at 7, because
     * {@code createBorderPoints} substitutes it - so an agent checking its own work against
     * what it asked for concludes something went wrong.
     */
    @Test
    public void thePageIsEightHundredByFourFourFourWithASevenUnitMargin() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "page.rsf"))) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            builder.addActivity("Работа", null, null);
            builder.commit();

            Map<String, Object> page =
                    DiagramGeometry.read(plugin, plugin.getBaseFunction()).page();
            assertEquals(800.0, (Double) page.get("width"), 0.001);
            assertEquals(444.0, (Double) page.get("height"), 0.001);
            assertEquals(7.0, (Double) page.get("margin"), 0.001);
        }
    }

    /** A box comes back where it was put, in the units it was put there in. */
    @Test
    public void aBoxIsReportedWhereItWasDrawn() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "box.rsf"))) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            builder.addActivity("Работа", 120.0, 90.0);
            builder.commit();

            List<Object> boxes = DiagramGeometry.boxes(plugin, plugin.getBaseFunction());
            assertEquals("one box on the context diagram", 1, boxes.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> bounds = (Map<String, Object>)
                    ((Map<String, Object>) boxes.get(0)).get("bounds");
            assertEquals(120.0, (Double) bounds.get("x"), 0.001);
            assertEquals(90.0, (Double) bounds.get("y"), 0.001);
            assertTrue("and a size fitted to the name",
                    (Double) bounds.get("width") > 0 && (Double) bounds.get("height") > 0);
        }
    }

    /**
     * The half that used to be dropped: on a context diagram every arrow has one end on the
     * frame, and an end with no activity was skipped outright - so the whole sheet came back
     * as four lists of names with no directions in them.
     */
    @Test
    public void anArrowRunningOffThePageSaysWhichSideItRunsOff() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "sides.rsf"))) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function a0 = builder.addActivity("Работа", null, null);
            builder.addArrow("Заявка", DiagramBuilder.End.frame(MovingPanel.LEFT),
                    DiagramBuilder.End.on(a0, MovingPanel.LEFT));
            builder.addArrow("Стандарт", DiagramBuilder.End.frame(MovingPanel.TOP),
                    DiagramBuilder.End.on(a0, MovingPanel.TOP));
            builder.addArrow("Результат", DiagramBuilder.End.on(a0, MovingPanel.RIGHT),
                    DiagramBuilder.End.frame(MovingPanel.RIGHT));
            builder.commit();

            List<Object> arrows =
                    DiagramGeometry.read(plugin, plugin.getBaseFunction()).arrows();
            assertEquals("three arrows", 3, arrows.size());

            Map<String, Object> incoming = named(arrows, "Заявка");
            assertEquals("page", on(incoming, "from"));
            assertEquals("left", side(incoming, "from"));
            assertEquals("activity", on(incoming, "to"));
            assertEquals("input", role(incoming, "to"));

            Map<String, Object> control = named(arrows, "Стандарт");
            assertEquals("top", side(control, "from"));
            assertEquals("control", role(control, "to"));

            Map<String, Object> outgoing = named(arrows, "Результат");
            assertEquals("output", role(outgoing, "from"));
            assertEquals("page", on(outgoing, "to"));
            assertEquals("right", side(outgoing, "to"));

            // And the drawing itself, which nothing could ask for before.
            assertTrue("an arrow has a route", ((List<?>) incoming.get("route")).size() >= 2);
            assertNotNull("and a label", incoming.get("bounds"));
        }
    }

    /**
     * One flow that forks is one flow. Grouping arrows by name cannot say that - three
     * segments called "Стандарт" look exactly like three separate arrows that happen to
     * agree - and the difference is in the model, not only on the page.
     */
    @Test
    public void aForkIsOneGroupRatherThanSeveralArrowsOfTheSameName() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "fork.rsf"))) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);

            DiagramBuilder context = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function top = context.addActivity("Работа", null, null);
            context.addArrow("Стандарт", DiagramBuilder.End.frame(MovingPanel.TOP),
                    DiagramBuilder.End.on(top, MovingPanel.TOP));
            context.commit();

            DiagramBuilder below = new DiagramBuilder(session, model, top);
            Function a1 = below.addActivity("Приём", null, null);
            Function a2 = below.addActivity("Исполнение", null, null);
            below.addArrow("Стандарт", DiagramBuilder.End.frame(MovingPanel.TOP),
                    DiagramBuilder.End.on(a1, MovingPanel.TOP));
            below.branch("Стандарт", DiagramBuilder.End.on(a2, MovingPanel.TOP), false);
            below.commit();

            List<Object> arrows = DiagramGeometry.read(plugin, top).arrows();
            List<Map<String, Object>> standard = new ArrayList<>();
            for (Object row : arrows) {
                @SuppressWarnings("unchecked")
                Map<String, Object> arrow = (Map<String, Object>) row;
                if ("Стандарт".equals(arrow.get("name")))
                    standard.add(arrow);
            }

            assertTrue("a fork is drawn as several segments", standard.size() > 1);
            Set<Object> groups = new HashSet<>();
            for (Map<String, Object> arrow : standard)
                groups.add(arrow.get("group"));
            assertEquals("but they are one flow, and say so", 1, groups.size());
        }
    }

    /**
     * The id an agent could not find. The context diagram belongs to the base function, whose
     * id is not a node of the function tree - so asking for the A-0 sheet meant knowing a
     * number nothing had ever reported. Omitting the argument now means it.
     */
    @Test
    public void aSheetWithNoActivityNamedIsTheContextDiagram() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        try (ModelSession session = ModelSession.createNew(
                new File(folder.getRoot(), "context.rsf"))) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            builder.addActivity("Работа", null, null);
            builder.commit();

            assertEquals(plugin.getBaseFunction().getElement().getId(),
                    DiagramTools.sheetOf(plugin, model, Map.of(), "activity")
                            .getElement().getId());
            assertEquals("and naming it explicitly means the same sheet",
                    plugin.getBaseFunction().getElement().getId(),
                    DiagramTools.sheetOf(plugin, model,
                            Map.of("activity", plugin.getBaseFunction().getElement().getId()),
                            "activity").getElement().getId());
        }
    }

    /**
     * How wide a name may run before it wraps.
     *
     * <p>
     * The default suits most names, and nothing here can work out when it does not: a long
     * name in a narrow lane of arrows wants to be narrow and tall, and only whoever is looking
     * at the diagram knows that. So it is asked for - and what proves it arrived is that the
     * same name in half the room comes back taller.
     */
    @Test
    public void aNarrowerLabelWidthWrapsTheNameOntoMoreLines() throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());

        assertTrue("the same name laid out in less room is taller",
                labelHeight(60.0) > labelHeight(null));
    }

    private double labelHeight(Double labelWidth) throws Exception {
        String name = "Утверждённое техническое задание на разработку";
        File file = new File(folder.getRoot(),
                "width-" + (labelWidth == null ? "default" : labelWidth.intValue()) + ".rsf");
        try (ModelSession session = ModelSession.createNew(file)) {
            Qualifier model = session.addModel("Модель", -1);
            DataPlugin plugin = session.getDataPlugin(model);
            DiagramBuilder builder = new DiagramBuilder(session, model,
                    plugin.getBaseFunction());
            Function a0 = builder.addActivity("Работа", null, null);
            builder.addArrow(name, DiagramBuilder.End.frame(MovingPanel.LEFT),
                    DiagramBuilder.End.on(a0, MovingPanel.LEFT), null, null, null,
                    labelWidth);
            builder.commit();

            List<Object> arrows =
                    DiagramGeometry.read(plugin, plugin.getBaseFunction()).arrows();
            @SuppressWarnings("unchecked")
            Map<String, Object> bounds =
                    (Map<String, Object>) named(arrows, name).get("bounds");
            return (Double) bounds.get("height");
        }
    }

    // ------------------------------------------------------------------ reading

    @SuppressWarnings("unchecked")
    private Map<String, Object> named(List<Object> arrows, String name) {
        for (Object row : arrows) {
            Map<String, Object> arrow = (Map<String, Object>) row;
            if (name.equals(arrow.get("name")))
                return arrow;
        }
        throw new AssertionError("no arrow called \"" + name + "\" among " + arrows);
    }

    @SuppressWarnings("unchecked")
    private Object on(Map<String, Object> arrow, String end) {
        return ((Map<String, Object>) arrow.get(end)).get("on");
    }

    @SuppressWarnings("unchecked")
    private Object side(Map<String, Object> arrow, String end) {
        return ((Map<String, Object>) arrow.get(end)).get("side");
    }

    @SuppressWarnings("unchecked")
    private Object role(Map<String, Object> arrow, String end) {
        return ((Map<String, Object>) arrow.get(end)).get("role");
    }
}
