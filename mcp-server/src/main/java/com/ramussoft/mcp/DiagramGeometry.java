package com.ramussoft.mcp;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import com.dsoft.pb.types.FRectangle;
import com.dsoft.pb.types.FloatPoint;

import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Row;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.data.SectorBorder;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.elements.Point;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.MovingLabel;
import com.ramussoft.pb.idef.visual.MovingPanel;
import com.ramussoft.pb.print.PIDEF0painter;

/**
 * Reading a diagram: where everything on it actually is.
 *
 * <p>
 * All of this was already being computed and then thrown away. {@code get_diagram} built the
 * panel, walked every sector, and kept two strings from each - so an agent that had drawn a
 * diagram could not find out what it looked like, and had to keep its own idea of the page in
 * its head and hope the two agreed. They did not: the page is 800 by 444 with a seven-unit
 * margin, and nothing said so anywhere.
 *
 * <p>
 * Separate from {@link DiagramBuilder} on purpose. That class puts the panel into the state
 * where two points make an arrow, and it is not registered at all when the server is read-only
 * - neither of which should have anything to do with being able to read a diagram.
 *
 * <p>
 * The units are the model's own, the ones {@code add_activity} takes as x and y. The size of
 * the panel does not enter into them.
 */
final class DiagramGeometry {

    /**
     * The panel is sized before the diagram is loaded, and that size decides nothing about
     * these coordinates - but it must be large enough that the geometry is a real diagram's
     * rather than a squashed one's.
     */
    private static final java.awt.Dimension DIAGRAM_SIZE = new java.awt.Dimension(1600, 1200);

    /**
     * The four IDEF0 arrow roles, in the encoding the model stores - {@code MovingPanel.RIGHT}
     * is 0, {@code BOTTOM} 1, {@code LEFT} 2, {@code TOP} 3. Not invented here: it is what
     * {@code IDEF0ConnectionPlugin} maps its Outputs/Mechanisms/Inputs/Controls keywords onto,
     * so these names and the report keywords mean the same thing.
     */
    private static final String[] ROLES = {"output", "mechanism", "input", "control"};

    /** The same four as sides of the page, where "input" would be a claim and "left" is not. */
    private static final String[] SIDES = {"right", "bottom", "left", "top"};

    private final MovingArea area;

    private DiagramGeometry(MovingArea area) {
        this.area = area;
    }

    /**
     * Loads a diagram for reading.
     *
     * <p>
     * The arrows of a diagram are not a property of an activity that can be read off it: they
     * live in a blob of visual data, and the only thing that decodes it is SectorRefactor,
     * which belongs to a Swing panel. Driving that panel without a window is what the web
     * export has always done. createMovingArea only sizes it - setActiveFunction is what loads
     * the diagram.
     */
    static DiagramGeometry read(DataPlugin plugin, Function parent) {
        MovingArea area = PIDEF0painter.createMovingArea(DIAGRAM_SIZE, plugin, parent);
        area.setActiveFunction(parent);
        return new DiagramGeometry(area);
    }

    /** Around a panel that is already loaded - the one a builder is drawing on. */
    static DiagramGeometry over(MovingArea area) {
        return new DiagramGeometry(area);
    }

    // --------------------------------------------------------------- the parts

    Vector<PaintSector> sectors() {
        Vector<PaintSector> sectors = area.getRefactor().getSectors();
        return sectors == null ? new Vector<PaintSector>() : sectors;
    }

    /** The arrow of this name on this diagram, or null. */
    PaintSector arrowNamed(String name) {
        for (PaintSector paint : sectors()) {
            Sector sector = paint.getSector();
            if (sector == null || sector.getName() == null)
                continue;
            if (name.trim().equals(sector.getName().trim()))
                return paint;
        }
        return null;
    }

    /**
     * The label of an arrow, wherever it ended up.
     *
     * <p>
     * One name belongs to a whole group of sectors carrying the same stream - the arrow and
     * every branch of it - and exactly one of them holds the label. Asking the sector that was
     * just drawn is right most of the time and wrong after a branch, which is why this asks
     * the group.
     */
    @SuppressWarnings("unchecked")
    MovingLabel labelOf(PaintSector arrow) {
        if (arrow.getText() != null)
            return arrow.getText();
        HashSet<PaintSector> group = new HashSet<PaintSector>();
        arrow.getConnectedSector(group);
        for (PaintSector sector : group)
            if (sector.getText() != null)
                return sector.getText();
        return null;
    }

    static boolean touches(NSectorBorder border, Function activity, int side) {
        return border != null && border.getFunction() != null
                && border.getFunctionType() == side
                && border.getFunction().getElement().getId()
                        == activity.getElement().getId();
    }

    static boolean touchesAnySide(NSectorBorder border, Function activity) {
        return border != null && border.getFunction() != null
                && border.getFunction().getElement().getId()
                        == activity.getElement().getId();
    }

    // ------------------------------------------------------------- as reported

    /**
     * The sheet itself: how much room there is to draw in.
     *
     * <p>
     * The margin is not decoration - it is where an arrow from outside actually starts.
     * {@code SectorRefactor.createBorderPoints} replaces whatever coordinate was asked for on
     * the frame with this one, so a border arrow lands at 7 or at width minus 7 whatever it
     * was told, and a caller comparing what it asked for against what came back needs to know
     * that before it decides something went wrong.
     */
    Map<String, Object> page() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("width", round(area.getDoubleWidth()));
        out.put("height", round(area.getDoubleHeight()));
        out.put("margin", round(MovingArea.PART_SPACE));
        return out;
    }

    static Map<String, Object> rectangle(FRectangle bounds) {
        if (bounds == null)
            return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", round(bounds.getX()));
        out.put("y", round(bounds.getY()));
        out.put("width", round(bounds.getWidth()));
        out.put("height", round(bounds.getHeight()));
        return out;
    }

    /** The route an arrow takes, corner by corner, in the order it is drawn. */
    static List<Object> route(PaintSector arrow) {
        List<Object> points = new ArrayList<>();
        for (int i = 0; i < arrow.getPointCount(); i++) {
            Point point = arrow.getPoint(i);
            if (point != null)
                points.add(List.of(round(point.getX()), round(point.getY())));
        }
        return points;
    }

    /**
     * Where an arrow's name is written, and where the zig-zag joins it back to the line.
     *
     * <p>
     * {@code getTildaPoint} and not {@code getTildaOPoint}: the second converts to pixels for
     * painting and would report a number in units nothing else here uses.
     */
    Map<String, Object> labelling(PaintSector arrow) {
        MovingLabel label = labelOf(arrow);
        Map<String, Object> out = new LinkedHashMap<>();
        if (label != null) {
            out.put("text", label.getText());
            out.put("bounds", rectangle(label.getBounds()));
        }
        if (arrow.isShowTilda()) {
            FloatPoint tilda = arrow.getTildaPoint();
            if (tilda != null)
                out.put("tilde", List.of(round(tilda.getX()), round(tilda.getY())));
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Every segment on the diagram, one row each, said the way an agent asked about it.
     *
     * <p>
     * One row per SEGMENT rather than per name, and that is the point. Grouping by name is
     * what {@code get_diagram} does, and it cannot tell one flow that forks to four activities
     * from four separate arrows that happen to share a name - a difference that is in the
     * model and that a report can see. The group number here says which segments are the same
     * flow.
     *
     * <p>
     * And an end that is not on an activity is reported rather than dropped. That was the
     * other half of the loss: on a context diagram every arrow has one end on the frame, so
     * the whole sheet came back as four lists of names with no directions in them at all.
     */
    List<Object> arrows() {
        Vector<PaintSector> all = sectors();
        Map<PaintSector, Integer> groups = groupsOf(all);
        List<Object> out = new ArrayList<>();
        for (PaintSector paint : all) {
            Sector sector = paint.getSector();
            if (sector == null)
                continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", sector.getName() == null ? null : sector.getName().trim());
            row.put("from", end(sector.getStart()));
            row.put("to", end(sector.getEnd()));
            if (paint.isPart())
                // A segment with an end that is attached to nothing: drawn, but not yet a
                // connection. Worth saying, because it looks finished on the page.
                row.put("unconnected", true);
            row.put("group", groups.get(paint));
            row.put("route", route(paint));
            Map<String, Object> labelling = labelling(paint);
            if (labelling != null)
                row.putAll(labelling);
            out.add(row);
        }
        return out;
    }

    /**
     * Which segments are one flow.
     *
     * <p>
     * A fork is not two arrows: it is one arrow cut at a crosspoint, both halves carrying the
     * same stream. {@code getConnectedSector} walks that, so numbering the groups it returns
     * gives an agent the one fact it cannot get from the names.
     */
    @SuppressWarnings("unchecked")
    private Map<PaintSector, Integer> groupsOf(Vector<PaintSector> all) {
        Map<PaintSector, Integer> groups = new IdentityHashMap<>();
        int next = 1;
        for (PaintSector paint : all) {
            if (groups.containsKey(paint))
                continue;
            HashSet<PaintSector> group = new HashSet<PaintSector>();
            paint.getConnectedSector(group);
            group.add(paint);
            for (PaintSector member : group)
                groups.put(member, next);
            next++;
        }
        return groups;
    }

    /**
     * One end of a segment: the activity it touches and the role it plays there, or the side
     * of the page it runs off, or the junction it meets.
     */
    private static Map<String, Object> end(SectorBorder border) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (border == null) {
            out.put("on", "nothing");
            return out;
        }
        Function function = border.getFunction();
        if (function != null) {
            out.put("on", "activity");
            out.put("activity", function.getElement().getId());
            out.put("name", function.getName());
            int role = border.getFunctionType();
            if (role >= 0 && role < ROLES.length)
                out.put("role", ROLES[role]);
            return out;
        }
        int side = border.getBorderType();
        if (side >= 0) {
            out.put("on", "page");
            if (side < SIDES.length)
                out.put("side", SIDES[side]);
            return out;
        }
        // No activity and not on the frame: a crosspoint, where this segment meets the rest
        // of its flow.
        out.put("on", "junction");
        return out;
    }

    /** Every activity drawn on this diagram, with the rectangle it occupies. */
    static List<Object> boxes(DataPlugin plugin, Function parent) {
        List<Object> out = new ArrayList<>();
        for (Row row : plugin.getChilds(parent, true))
            if (row instanceof Function) {
                Function function = (Function) row;
                Map<String, Object> box = new LinkedHashMap<>();
                box.put("id", function.getElement().getId());
                box.put("name", function.getName());
                box.put("bounds", rectangle(function.getBounds()));
                out.add(box);
            }
        return out;
    }

    /**
     * Two decimals. The coordinates are doubles arrived at by arithmetic, so they carry a tail
     * of noise that means nothing at this scale and reads as precision that is not there.
     */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    static {
        // MovingPanel's constants are the encoding both arrays above are indexed by. If they
        // are ever renumbered, this says so at class load rather than by mislabelling arrows.
        if (MovingPanel.RIGHT != 0 || MovingPanel.BOTTOM != 1 || MovingPanel.LEFT != 2
                || MovingPanel.TOP != 3)
            throw new IllegalStateException("MovingPanel's side constants have been "
                    + "renumbered; the role and side names here are indexed by them.");
    }
}
