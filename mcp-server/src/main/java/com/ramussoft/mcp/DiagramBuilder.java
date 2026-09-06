package com.ramussoft.mcp;

import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.util.HashSet;
import java.util.List;
import java.util.Vector;

import com.dsoft.pb.types.FRectangle;
import com.dsoft.pb.types.FloatPoint;
import com.dsoft.utils.DataLoader;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Qualifier;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.Row;
import com.ramussoft.pb.Sector;
import com.ramussoft.pb.Stream;
import com.ramussoft.pb.data.negine.NSectorBorder;
import com.ramussoft.pb.idef.elements.Ordinate;
import com.ramussoft.pb.idef.elements.PaintSector;
import com.ramussoft.pb.idef.elements.ReplaceStreamType;
import com.ramussoft.pb.idef.elements.Point;
import com.ramussoft.pb.idef.elements.SectorRefactor;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.pb.idef.visual.MovingLabel;
import com.ramussoft.pb.idef.visual.MovingPanel;

/**
 * Builds one diagram: the activities on it and the arrows between them.
 *
 * <p>
 * A diagram is not a table. An activity carries its rectangle and an arrow carries a route,
 * and both live in a blob that only a Swing panel writes. So this does not write that blob -
 * it drives the panel, without a window, exactly as {@code render_diagram} already drives it
 * to read one.
 *
 * <p>
 * The recipe is not invented either. {@code com.ramussoft.pb.dmaster.AbstractClassicTemplate}
 * is the original authors' code for building a starter diagram programmatically, and every
 * step below - two PerspectivePoints per arrow, each followed by doSector, the Ordinate pair
 * for a point on a box - is taken from it. Imitating the data instead would mean guessing at
 * crosspoints and border types; driving the machine means the machine gets them right.
 */
final class DiagramBuilder {

    /**
     * An arrow's role IS the side of the box it touches, and the numbers agree with the
     * report keywords - see IDEF0ConnectionPlugin, which maps Inputs, Controls, Mechanisms
     * and Outputs onto these same four.
     */
    static int roleOf(String role) {
        switch (role.toLowerCase()) {
            case "input":
                return MovingPanel.LEFT;
            case "control":
                return MovingPanel.TOP;
            case "mechanism":
                return MovingPanel.BOTTOM;
            case "output":
                return MovingPanel.RIGHT;
            default:
                throw new IllegalArgumentException("Unknown role \"" + role
                        + "\". Use input, control, mechanism or output.");
        }
    }

    /** Where the diagram's own frame sits, in the panel's coordinates. */
    private static final double FRAME_MARGIN = 10;

    private final ModelSession session;

    private final DataPlugin plugin;

    private final Function parent;

    private final MovingArea area;

    DiagramBuilder(ModelSession session, Qualifier model, Function parent) {
        this.session = session;
        this.plugin = session.getDataPlugin(model);
        this.parent = parent;
        this.area = new MovingArea(plugin);
        area.setDataPlugin(plugin);
        area.setActiveFunction(parent);
        // Without this the panel is not in the state where two points make an arrow, and
        // doSector quietly does nothing.
        area.setArrowAddingState();
    }

    Function getParent() {
        return parent;
    }

    MovingArea getArea() {
        return area;
    }

    /**
     * Adds an activity. Position optional: left out, boxes step down the diagonal from the
     * top left, which is how an IDEF0 diagram is laid out by convention and what
     * SimpleTemplate does for the starter diagrams.
     */
    Function addActivity(String name, Double x, Double y) {
        return addActivity(name, x, y, null);
    }

    Function addActivity(String name, Double x, Double y, Integer fontSize) {
        refuseASecondBoxOnAContextDiagram();
        // The panel's own way of adding a box, so the font, the colours and the dates are
        // the ones the application would have written; createRow alone leaves all three
        // unset and the box comes out looking like nothing a user could have drawn.
        Function function = area.createFunctionalObject(0, 0, Function.TYPE_PROCESS, parent);
        function.setName(name);
        // Before measuring, not after: the size of the box is decided by how big the name is
        // written, and a box fitted to one font and drawn in another is fitted to nothing.
        if (fontSize != null)
            function.setFont(new Font(BOX_FONT_FAMILY, Font.PLAIN, fontSize));

        FRectangle rect = fitToName(function, name);
        double[] place = (x == null || y == null) ? nextOnTheDiagonal(rect) : new double[]{x, y};
        rect.setX(place[0]);
        rect.setY(place[1]);
        function.setBounds(rect);
        return function;
    }

    /**
     * A context diagram holds one activity - that is what makes it a context diagram, and the
     * application will not let you draw a second one either. Said here rather than left to
     * fail later, because the agent's next question is where the box should have gone.
     */
    private void refuseASecondBoxOnAContextDiagram() {
        if (!isAContextDiagram())
            return;
        for (Row row : plugin.getChilds(parent, true))
            if (row instanceof Function
                    && ((Function) row).getType() < Function.TYPE_EXTERNAL_REFERENCE)
                throw new IllegalStateException("An IDEF0 context diagram holds exactly one "
                        + "activity, and this one already has \"" + row.getName()
                        + "\". Add the next level inside it instead: name that activity as "
                        + "the parent.");
    }

    /**
     * The box, grown until the name fits inside it.
     *
     * <p>
     * The default is the size the application gives a box the moment it is drawn, and a user
     * then drags it to fit. Nobody is going to drag this one, and a box whose name is cut off
     * halfway is the first thing anyone would notice about a generated diagram.
     */
    private FRectangle fitToName(Function function, String name) {
        FRectangle rect = new FRectangle(function.getBounds());
        double step = rect.getWidth() / 2;
        double width = rect.getWidth();
        double height = rect.getHeight();

        area.stringBounder.setFont(function.getFont());
        for (int attempt = 0; ; attempt++) {
            Rectangle2D needed = area.stringBounder.getLinesBounds(name,
                    new Rectangle2D.Double(0, 0, width, height));
            if (needed.getHeight() <= height - ID_ROOM)
                break;
            // Wider first, twice: a box that has grown only downwards over a long name looks
            // like a column. After that take whatever height the text asks for.
            if (attempt < 2)
                width += step;
            else {
                height = needed.getHeight() + ID_ROOM;
                break;
            }
        }
        // A floor as well as a fit. The application's default box is the size of a box you
        // have just drawn and are about to drag; four arrows on one side of it land a
        // finger's width apart, and their labels land on top of each other. The example
        // model that ships with Ramus draws its context box half again as big, and so does
        // this.
        rect.setWidth(Math.max(width, MINIMUM_WIDTH));
        rect.setHeight(Math.min(Math.max(height, MINIMUM_HEIGHT), maxBoxHeight()));
        return rect;
    }

    private static final String BOX_FONT_FAMILY = "Dialog";

    private static final double MINIMUM_WIDTH = 108;

    /**
     * Tall enough that four arrows down one side leave room for their names between them: the
     * side is divided into five, and a name is about thirteen units high.
     */
    private static final double MINIMUM_HEIGHT = 90;

    /**
     * The strip along the bottom of a box that the text does not get: IDEF0Object keeps it
     * for the activity's number, and a box measured without it comes out one line short.
     */
    private static final double ID_ROOM = 7;

    /**
     * Where the next box goes: down the diagonal, top left to bottom right, which is how an
     * IDEF0 diagram is read and how the activities get their numbers. The one box of a
     * context diagram goes in the middle instead.
     *
     * <p>
     * The step down is the height of a box and not a share of the page, because a share of
     * the page is how boxes end up overlapping each other: the page is not tall enough for
     * four of them at even spacing. Past the bottom the column starts again at the top,
     * further right.
     */
    private double[] nextOnTheDiagonal(FRectangle box) {
        int existing = 0;
        for (Row row : plugin.getChilds(parent, true))
            if (row instanceof Function)
                existing++;
        // Existing counts the box being placed, so the first one is at index zero.
        int index = Math.max(0, existing - 1);

        // Room on the left for the names of the arrows coming in from the edge of the page.
        // Without it the leftmost box sits in the corner, its inputs are a few units long,
        // and their names are printed across the box itself.
        double spanX = area.MOVING_AREA_WIDTH - LABEL_ROOM - MARGIN - box.getWidth();
        if (isAContextDiagram())
            return new double[]{LABEL_ROOM + spanX / 2,
                    MARGIN + (area.CLIENT_HEIGHT - MARGIN * 2 - box.getHeight()) / 2};

        // Four down and four across, which is the diagram an IDEF0 author is told to aim for.
        double across = spanX / (ROWS - 1);
        return new double[]{
                Math.min(LABEL_ROOM + across * index, LABEL_ROOM + spanX),
                TOP_ROOM + rowStep() * (index % ROWS)};
    }

    private static final double BOX_GAP = 16;

    private static final double MARGIN = 24;

    /** How many boxes a diagram is laid out for, across and down. */
    private static final int ROWS = 4;

    /** Room kept clear on the left for the names of arrows arriving from the page edge. */
    private static final double LABEL_ROOM = 150;

    /**
     * Room kept clear above the first box.
     *
     * <p>
     * Not margin for its own sake: a control comes down from the top of the page, and with the
     * first box against the top edge that arrow is a dozen units long - too short to be read,
     * and too short to branch, which is how one control reaches four activities.
     */
    private static final double TOP_ROOM = 60;

    /** How far apart the rows of a decomposition sit, so that four of them fit the page. */
    private double rowStep() {
        return (area.CLIENT_HEIGHT - TOP_ROOM - MARGIN) / ROWS;
    }

    /**
     * The tallest a box may be on this diagram. A decomposition holds four of them and they
     * must not overlap; a context diagram holds one and can have whatever it needs.
     */
    private double maxBoxHeight() {
        if (isAContextDiagram())
            return Double.MAX_VALUE;
        return rowStep() - BOX_GAP;
    }

    /** The diagram of the model's top activity, where IDEF0 allows exactly one box. */
    private boolean isAContextDiagram() {
        if (!parent.equals(plugin.getBaseFunction()))
            return false;
        int type = parent.getDecompositionType();
        return type != MovingArea.DIAGRAM_TYPE_DFD && type != MovingArea.DIAGRAM_TYPE_DFDS;
    }

    /**
     * One end of an arrow: either a side of an activity, or the frame of the diagram.
     */
    static final class End {

        final Function activity;

        final int side;

        private End(Function activity, int side) {
            this.activity = activity;
            this.side = side;
        }

        static End on(Function activity, int side) {
            return new End(activity, side);
        }

        static End frame(int side) {
            return new End(null, side);
        }
    }

    /**
     * Draws an arrow from one end to the other.
     *
     * <p>
     * Direction is not decoration: which end is the start decides whether the stream leaves
     * an activity or arrives at it, and the report keywords read exactly that.
     */
    void addArrow(String name, End from, End to) {
        addArrow(name, from, to, null, null, null);
    }

    /**
     * @param labelX   where to put the arrow's name, or null to place it automatically.
     * @param fontSize the size to write the name in, or null for the usual one.
     */
    void addArrow(String name, End from, End to, Double labelX, Double labelY,
                  Integer fontSize) {
        // An arrow that crosses the edge of the page may already be here, waiting: when a
        // parent diagram's arrow arrives at this activity, the model puts the other half of
        // it on this diagram as a stub with one end loose. Drawing a second one is what
        // leaves a diagram with each name on it twice, one of them connected to nothing.
        if (from.activity == null && to.activity != null
                && connectStub(name, to, false, labelX, labelY, fontSize))
            return;
        if (to.activity == null && from.activity != null
                && connectStub(name, from, true, labelX, labelY, fontSize))
            return;

        // Both anchors before either point: an arrow that ends at the frame leaves it level
        // with the box it came from, which is what stops it from being drawn as a staircase.
        double[] start = anchorOf(from);
        double[] end = anchorOf(to);

        point(from, SectorRefactor.TYPE_START, start, end);
        area.doSector();
        point(to, SectorRefactor.TYPE_END, end, start);
        area.doSector();

        PaintSector created = area.getRefactor().getSector();
        if (created == null || created.getSector() == null)
            throw new IllegalStateException("The arrow \"" + name + "\" was not created. "
                    + "The two ends may be the same, or the activity may not be on this "
                    + "diagram.");
        created.getSector().setStream(stream(name),
                ReplaceStreamType.CHILDREN);

        End box = from.activity == null ? to : from;
        double[] anchor = from.activity == null ? end : start;
        nameIt(created, box.activity, box.side, anchor == null ? 0 : (int) anchor[2],
                from.activity != null, labelX, labelY, fontSize);
    }

    /**
     * Connects a stub that is already on this diagram to a box, and says whether it found
     * one.
     *
     * <p>
     * This is the operation a modeller does by dragging the loose end onto the box, and it is
     * the only one that keeps the two levels joined: the stub and the parent's arrow share a
     * crosspoint, so the model knows they are the same flow. Drawing a fresh arrow with the
     * same name would only look the same.
     *
     * @param leaving whether the box is where the arrow starts rather than where it ends.
     */
    private boolean connectStub(String name, End box, boolean leaving,
                                Double labelX, Double labelY, Integer fontSize) {
        PaintSector stub = null;
        for (PaintSector paint : sectors()) {
            Sector sector = paint.getSector();
            if (sector == null || !paint.isPart() || sector.getName() == null)
                continue;
            if (!name.trim().equals(sector.getName().trim()))
                continue;
            // The loose end has to be the end the arrow needs: a stub waiting to be an input
            // is loose at its head, and one waiting to carry an output is loose at its tail.
            boolean looseAtTheStart = paint.getStart() == null;
            if (looseAtTheStart == leaving) {
                stub = paint;
                break;
            }
        }
        if (stub == null)
            return false;

        boolean looseAtTheStart = stub.getStart() == null;
        Sector stubbed = stub.getSector();
        SectorRefactor refactor = area.getRefactor();
        refactor.setSector(stub);
        // The states the application puts itself in when the loose end is picked up. Its own
        // doSector would branch on which panel has the mouse and create a new sector instead,
        // so the change is asked for directly.
        area.setState(looseAtTheStart
                ? MovingArea.START_POINT_CHANGING : MovingArea.END_POINT_CHANGING);

        double[] anchor = anchorOf(box);
        Ordinate x = new Ordinate(Ordinate.TYPE_X);
        Ordinate y = new Ordinate(Ordinate.TYPE_Y);
        SectorRefactor.PerspectivePoint pp = new SectorRefactor.PerspectivePoint();
        pp.point = new Point(x, y);
        pp.setFunction(box.activity, box.side);
        pp.type = looseAtTheStart ? SectorRefactor.TYPE_START : SectorRefactor.TYPE_END;
        x.setPosition(anchor[0]);
        y.setPosition(anchor[1]);
        refactor.setPoint(pp);

        boolean changed = refactor.changeSector();
        refactor.fixOwners();
        area.setArrowAddingState();
        if (!changed)
            throw new IllegalStateException("\"" + name + "\" is already on this diagram as "
                    + "an arrow from the parent, but its loose end could not be joined to \""
                    + box.activity.getName() + "\".");

        // The stub was a stub: it had no name of its own to place. Now that it reaches a box
        // it is an arrow like any other and gets the same treatment. changeSector may have
        // replaced the PaintSector, so the current one is asked for rather than assumed.
        // Found again rather than kept: changeSector leaves the refactor with no current
        // sector, and it may have replaced the painted object for this one. The row underneath
        // is the same either way, so that is what identifies it.
        PaintSector connected = paintedAs(stubbed);
        if (connected != null)
            nameIt(connected, box.activity, box.side, (int) anchor[2], leaving,
                    labelX, labelY, fontSize);
        return true;
    }

    /**
     * The size an arrow's name is written in. The shipped models use ten, and so does the
     * application's own default - this only becomes a decision because it has to be WRITTEN,
     * see {@link #nameIt}.
     */
    private static final int ARROW_FONT_SIZE = 10;

    /** How far the name is set back from its own line. */
    private static final double LABEL_GAP = 3;

    /**
     * The widest a name is allowed to be laid out. A quarter of the page is what the
     * application allows a label to grow to, and it is wide enough that most arrow names come
     * out on one line.
     */
    private static final double MAX_LABEL_WIDTH = 200;

    /**
     * Gives an arrow its name: a label of its own, placed clear of the line, joined back to it
     * by the zig-zag the notation asks for.
     *
     * <p>
     * All three parts are needed and none of them is decoration. An arrow with no label at all
     * is what this used to write, and the consequence was not a missing name but a misplaced
     * one: the panel manufactures a label on every load and puts it at the middle of the line,
     * so three arrows into one side of a box printed three names in one place. Writing the
     * font matters for the same reason - the size is what decides how big the label is, the
     * reader's own setting is used when the file does not say, and the label is re-measured
     * and re-centred on open. A layout computed here only survives if the file carries the
     * font it was computed for.
     */
    private void nameIt(PaintSector arrow, Function activity, int side, int slot,
                        boolean leavesABox, Double labelX, Double labelY, Integer fontSize) {
        arrow.setFont(new Font(ARROW_FONT_FAMILY, Font.PLAIN,
                fontSize == null ? ARROW_FONT_SIZE : fontSize));
        // Not saved by PaintSector.save and not by the path that creates a sector - that call
        // is commented out there - so it has to be asked for.
        arrow.saveVisual();

        arrow.createTexts();

        // Where the name goes to sit. An arrow that leaves a box is named just outside the
        // box that produces it - that is where a drawn diagram puts it, and the middle of the
        // line is no good for these because the line bends around other boxes and its middle
        // lands on one of them. Everything else is named at its middle, which for an arrow
        // running in from the edge of the page is the clear space beside it.
        FRectangle activityBounds = activity == null ? null : activity.getBounds();
        Point exit = leavesABox ? arrow.getStartPoint() : null;
        FloatPoint anchor = exit == null ? arrow.getTildaPoint()
                : new FloatPoint(exit.getX(), exit.getY());
        boolean vertical = side == MovingPanel.TOP || side == MovingPanel.BOTTOM;
        MovingLabel label = labelOf(arrow);
        if (label == null)
            // The group already has its name somewhere else - a branch of an arrow drawn
            // earlier. Leaving that one where the modeller or an earlier call put it.
            return;
        // Measured rather than fitted. resetBoundsX only ever GROWS a label to its minimum,
        // and it measures at the width the label already has - which for a new one is narrow,
        // so a name comes out three lines tall and stacks onto its neighbours. Measuring at a
        // width a name can actually use puts most names on one line, and one line is what
        // makes three arrows into one side of a box readable at all.
        area.stringBounder.setFont(arrow.getFont());
        Rectangle2D needed = area.stringBounder.getLinesBounds(label.getText(),
                new Rectangle2D.Double(0, 0, MAX_LABEL_WIDTH, 0));
        FRectangle box = new FRectangle(0, 0, needed.getWidth(), needed.getHeight());

        double x;
        double y;
        if (labelX != null && labelY != null) {
            x = labelX;
            y = labelY;
        } else if (leavesABox) {
            x = anchor.getX() + LABEL_GAP;
            y = anchor.getY() - box.getHeight() - LABEL_GAP;
        } else if (vertical) {
            // Names beside a vertical arrow are written across it, so three arrows into the
            // bottom of one box would print three names side by side in the width of one.
            // They step along the arrow instead, which is what the drawn diagrams do.
            x = anchor.getX() + LABEL_GAP;
            y = anchor.getY() - box.getHeight() / 2
                    + (slot - 1) * box.getHeight() * 1.6;
        } else {
            x = anchor.getX() - box.getWidth() / 2;
            y = anchor.getY() - box.getHeight() - LABEL_GAP;
        }
        // Last resort, and the one that catches every case the rules above do not: a name
        // printed across its own box is unreadable, and it happens whenever the box is at the
        // edge of the page and the name has nowhere else to go. Above the box it always has.
        if (labelX == null && overlaps(x, y, box, activityBounds)) {
            x = Math.max(FRAME_MARGIN,
                    Math.min(x, area.MOVING_AREA_WIDTH - MARGIN - box.getWidth()));
            y = activityBounds.getTop() - box.getHeight() - LABEL_GAP;
        }
        label.setBounds(new FRectangle(x, y, box.getWidth(), box.getHeight()));

        // The zig-zag runs from the line to the name, so it belongs beside the name rather
        // than at the middle of the arrow. setTildaPos hit-tests the point and dereferences
        // the result without checking, so it is only ever called on a point already known to
        // be on the line; otherwise the middle stands, which is where the name is anyway.
        List<PaintSector> touched = null;
        if (labelX == null) {
            double[] near = vertical
                    ? new double[]{anchor.getX(), y + box.getHeight() / 2}
                    : nearTheExit(arrow, leavesABox);
            if (near != null && arrow.isOnMe(near[0], near[1]) != null)
                touched = arrow.setTildaPos(near[0], near[1]);
        }
        if (touched == null)
            arrow.setShowTilda(true);

        Engine engine = plugin.getEngine();
        PaintSector.save(arrow, new DataLoader.MemoryData(), engine);
        // setTildaPos moves the label off its neighbours, so they are stale until written.
        if (touched != null)
            for (PaintSector also : touched)
                if (also != arrow)
                    PaintSector.save(also, new DataLoader.MemoryData(), engine);
    }

    private static final String ARROW_FONT_FAMILY = "Dialog";

    /** Whether a label put here would be printed across the box it belongs to. */
    private boolean overlaps(double x, double y, FRectangle label, FRectangle activity) {
        if (activity == null)
            return false;
        return x < activity.getRight() && x + label.getWidth() > activity.getLeft()
                && y < activity.getBottom() && y + label.getHeight() > activity.getTop();
    }

    /**
     * A point on the arrow just after it leaves the box, for the zig-zag to start from. Null
     * when the arrow does not leave a box, or when its first run is too short to hold a point
     * that is neither of its ends.
     */
    private double[] nearTheExit(PaintSector arrow, boolean leavesABox) {
        if (!leavesABox || arrow.getPinCount() == 0)
            return null;
        PaintSector.Pin first = arrow.getPin(0);
        Point start = first.getStart();
        Point end = first.getEnd();
        if (start == null || end == null)
            return null;
        double length = Math.abs(first.getLength());
        if (length < BRANCH_ROOM)
            return null;
        double at = Math.min(BRANCH_ROOM, length / 2) / length;
        return new double[]{start.getX() + (end.getX() - start.getX()) * at,
                start.getY() + (end.getY() - start.getY()) * at};
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
    private MovingLabel labelOf(PaintSector arrow) {
        if (arrow.getText() != null)
            return arrow.getText();
        HashSet<PaintSector> group = new HashSet<PaintSector>();
        arrow.getConnectedSector(group);
        for (PaintSector sector : group)
            if (sector.getText() != null)
                return sector.getText();
        return null;
    }

    /**
     * Branches an arrow that is already on this diagram, or merges into it.
     *
     * <p>
     * One flow reaching four activities is one arrow that forks, not four arrows that share a
     * name. The difference is in the model, not only on the page: a fork is a crosspoint, the
     * segments carry the same stream element, and a report reading the model sees one thing
     * arriving at four places. Four separate arrows would be four things with equal names.
     *
     * <p>
     * The application makes one by drawing a new arrow whose first point lands on an existing
     * one, and that is exactly what this does - a PerspectivePoint carrying a pin instead of
     * coordinates. What follows is the panel's own work: it splits the trunk where the point
     * landed, mints the crosspoint, and copies the stream onto the branch. Naming the branch
     * afterwards would be wrong, and is not done.
     *
     * @param leaving whether the new segment leaves the activity and merges into the arrow,
     *                rather than branching off the arrow and arriving at the activity.
     */
    void branch(String name, End box, boolean leaving) {
        if (arrowNamed(name) == null)
            throw new IllegalArgumentException("No arrow called \"" + name + "\" on this "
                    + "diagram, so there is nothing to branch. get_diagram lists the arrows "
                    + "it has.");
        // The longest run of the WHOLE flow, not of the first segment found. A flow that has
        // been branched already is several segments, each shorter than the one it was cut
        // from, and cutting the shortest again is what runs out of room first.
        PaintSector trunk = null;
        PaintSector.Pin pin = null;
        double longest = 0;
        for (PaintSector paint : sectors()) {
            Sector sector = paint.getSector();
            if (sector == null || sector.getName() == null
                    || !name.trim().equals(sector.getName().trim()))
                continue;
            // The panel refuses to split an arrow that is still a stub, and it refuses in
            // silence, so those are passed over here rather than offered to it.
            if (paint.getStart() == null || paint.getEnd() == null)
                continue;
            for (int i = 0; i < paint.getPinCount(); i++) {
                double length = Math.abs(paint.getPin(i).getLength());
                if (length > longest) {
                    longest = length;
                    trunk = paint;
                    pin = paint.getPin(i);
                }
            }
        }
        double[] where = pin == null ? null : middleOf(pin);
        if (where == null)
            throw new IllegalStateException("\"" + name + "\" has no straight run long enough "
                    + "to branch from - every part of it is either too short to cut or not "
                    + "joined at both ends yet. Move what it connects further apart, or draw "
                    + "the branch as its own arrow.");

        int before = area.getRefactor().getSectorsCount();
        area.setArrowAddingState();

        SectorRefactor.PerspectivePoint onTheArrow = new SectorRefactor.PerspectivePoint();
        onTheArrow.type = leaving ? SectorRefactor.TYPE_END : SectorRefactor.TYPE_START;
        onTheArrow.pin = pin;
        onTheArrow.x = where[0];
        onTheArrow.y = where[1];

        double[] anchor = anchorOf(box);
        Ordinate x = new Ordinate(Ordinate.TYPE_X);
        Ordinate y = new Ordinate(Ordinate.TYPE_Y);
        SectorRefactor.PerspectivePoint onTheBox = new SectorRefactor.PerspectivePoint();
        onTheBox.type = leaving ? SectorRefactor.TYPE_START : SectorRefactor.TYPE_END;
        onTheBox.point = new Point(x, y);
        onTheBox.setFunction(box.activity, box.side);
        x.setPosition(anchor[0]);
        y.setPosition(anchor[1]);

        // Start first whichever end it is: the panel adds the start point, then the end.
        SectorRefactor.PerspectivePoint first = leaving ? onTheBox : onTheArrow;
        SectorRefactor.PerspectivePoint second = leaving ? onTheArrow : onTheBox;
        area.getRefactor().setPoint(first);
        area.doSector();
        area.getRefactor().setPoint(second);
        area.doSector();

        // The panel's refusals are silent - it simply returns - so the count is what says
        // whether anything happened.
        if (area.getRefactor().getSectorsCount() <= before)
            throw new IllegalStateException("The branch of \"" + name + "\" to \""
                    + box.activity.getName() + "\" was not drawn. The arrow may already reach "
                    + "that activity.");
    }

    /** The arrow of this name on this diagram, or null. */
    private PaintSector arrowNamed(String name) {
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
     * The middle of a run, or null when it is too short to cut.
     *
     * <p>
     * Too short is not a matter of taste: a point within about eleven units of either end is
     * taken as "join the corner that is already there" rather than "make a new one", which is
     * a legal result and not the one asked for.
     */
    private double[] middleOf(PaintSector.Pin pin) {
        Point start = pin.getStart();
        Point end = pin.getEnd();
        if (start == null || end == null)
            return null;
        if (Math.abs(pin.getLength()) < BRANCH_ROOM)
            return null;
        return new double[]{(start.getX() + end.getX()) / 2, (start.getY() + end.getY()) / 2};
    }

    private static final double BRANCH_ROOM = 30;

    /** The painted sector standing for this row right now, or null if it has gone. */
    private PaintSector paintedAs(Sector row) {
        if (row == null)
            return null;
        for (PaintSector paint : sectors()) {
            Sector sector = paint.getSector();
            if (sector != null && row.getGlobalId().equals(sector.getGlobalId()))
                return paint;
        }
        return null;
    }

    private void point(End end, int type, double[] own, double[] other) {
        SectorRefactor.PerspectivePoint pp = new SectorRefactor.PerspectivePoint();
        pp.type = type;
        if (end.activity == null) {
            // On the frame the coordinate across the border is just a margin - the template
            // passes 10 whichever side it is - and the one along it is where the arrow
            // actually meets the edge. Levelling that with the box gives a straight line.
            // The coordinate across the border is a margin - the template passes 10 whichever
            // side it is - and the one along it is where the arrow meets the edge. The
            // refactor lays a border arrow out level with the box it joins whatever is
            // passed here, so this is agreement rather than instruction.
            pp.borderType = end.side;
            boolean vertical = end.side == MovingPanel.LEFT || end.side == MovingPanel.RIGHT;
            pp.x = vertical ? FRAME_MARGIN
                    : (other == null ? area.MOVING_AREA_WIDTH / 2 : other[0]);
            pp.y = vertical ? (other == null ? area.CLIENT_HEIGHT / 2 : other[1])
                    : FRAME_MARGIN;
        } else {
            // A point on a box is a pair of ordinates rather than two numbers: the panel
            // keeps them so the arrow follows when the box is moved.
            Ordinate x = new Ordinate(Ordinate.TYPE_X);
            Ordinate y = new Ordinate(Ordinate.TYPE_Y);
            pp.point = new Point(x, y);
            pp.setFunction(end.activity, end.side);
            x.setPosition(own[0]);
            y.setPosition(own[1]);
        }
        area.getRefactor().setPoint(pp);
    }

    /**
     * How many arrows one side of a box is divided into. Four is more than an IDEF0 diagram
     * usually wants on one side, and a fixed number matters more than the right one: divide
     * by the arrows present instead, and every arrow already drawn would have to move each
     * time another is added.
     */
    private static final int SLOTS = 4;

    /**
     * Where on the side of a box this arrow should attach, in diagram coordinates. Null for
     * an end on the frame, which has no side to divide.
     */
    private double[] anchorOf(End end) {
        if (end.activity == null)
            return null;
        FRectangle bounds = end.activity.getBounds();
        int slot = occupied(end.activity, end.side) % SLOTS;
        double along = (slot + 1.0) / (SLOTS + 1);
        switch (end.side) {
            case MovingPanel.LEFT:
                return new double[]{bounds.getLeft(),
                        bounds.getTop() + bounds.getHeight() * along, slot};
            case MovingPanel.RIGHT:
                return new double[]{bounds.getRight(),
                        bounds.getTop() + bounds.getHeight() * along, slot};
            case MovingPanel.TOP:
                return new double[]{bounds.getLeft() + bounds.getWidth() * along,
                        bounds.getTop(), slot};
            default:
                return new double[]{bounds.getLeft() + bounds.getWidth() * along,
                        bounds.getBottom(), slot};
        }
    }


    /**
     * How many arrows already touch this side of this box.
     *
     * <p>
     * Counted from the panel rather than remembered, because a builder lives for one tool
     * call: the second arrow an agent draws is drawn by a different instance, and it still
     * has to land beside the first rather than on top of it.
     */
    private int occupied(Function activity, int side) {
        int used = 0;
        for (PaintSector paint : sectors()) {
            Sector sector = paint.getSector();
            if (sector == null)
                continue;
            if (touches(sector.getStart(), activity, side))
                used++;
            if (touches(sector.getEnd(), activity, side))
                used++;
        }
        return used;
    }

    private boolean touches(NSectorBorder border, Function activity, int side) {
        return border != null && border.getFunction() != null
                && border.getFunctionType() == side
                && border.getFunction().getElement().getId()
                        == activity.getElement().getId();
    }

    /**
     * Moves, resizes or renames a box that is already on the diagram. Everything is optional
     * and nothing else is touched: an agent correcting a name should not have to restate
     * where the box was.
     */
    void setActivity(Function activity, String name, Double x, Double y,
                     Double width, Double height, Integer fontSize) {
        if (name != null)
            activity.setName(name);
        if (fontSize != null) {
            activity.setFont(new Font(BOX_FONT_FAMILY, Font.PLAIN, fontSize));
            if (width == null && height == null) {
                // A name that fitted at ten points does not fit at fourteen. Refitted unless
                // the caller is setting the size itself in the same breath.
                FRectangle fitted = fitToName(activity, activity.getName());
                fitted.setX(activity.getBounds().getX());
                fitted.setY(activity.getBounds().getY());
                activity.setBounds(fitted);
            }
        }
        if (x == null && y == null && width == null && height == null)
            return;
        FRectangle rect = new FRectangle(activity.getBounds());
        if (x != null)
            rect.setX(x);
        if (y != null)
            rect.setY(y);
        if (width != null)
            rect.setWidth(width);
        if (height != null)
            rect.setHeight(height);
        activity.setBounds(rect);
    }

    /**
     * Changes an arrow already drawn: its name, where its name sits, whether it has a tilde,
     * and the size it is written in.
     */
    void setArrow(String name, String newName, Double labelX, Double labelY,
                  Boolean tilde, Integer fontSize) {
        PaintSector arrow = arrowNamed(name);
        if (arrow == null)
            throw new IllegalArgumentException("No arrow called \"" + name + "\" on this "
                    + "diagram. get_diagram lists the arrows it has.");

        if (newName != null)
            arrow.getSector().setStream(stream(newName), ReplaceStreamType.CHILDREN);
        if (fontSize != null) {
            arrow.setFont(new Font(ARROW_FONT_FAMILY, Font.PLAIN, fontSize));
            arrow.saveVisual();
        }
        if (tilde != null)
            arrow.setShowTilda(tilde);

        // The label may belong to another segment of the same flow, and then that is the one
        // to move and the one to write.
        MovingLabel label = labelOf(arrow);
        PaintSector owner = arrow.getText() != null ? arrow : ownerOfLabel(arrow);
        if (label != null && (labelX != null || labelY != null || fontSize != null)) {
            area.stringBounder.setFont(owner == null ? arrow.getFont() : owner.getFont());
            Rectangle2D needed = area.stringBounder.getLinesBounds(label.getText(),
                    new Rectangle2D.Double(0, 0, MAX_LABEL_WIDTH, 0));
            FRectangle was = label.getBounds();
            label.setBounds(new FRectangle(labelX == null ? was.getX() : labelX,
                    labelY == null ? was.getY() : labelY,
                    needed.getWidth(), needed.getHeight()));
        }

        Engine engine = plugin.getEngine();
        PaintSector.save(arrow, new DataLoader.MemoryData(), engine);
        if (owner != null && owner != arrow)
            PaintSector.save(owner, new DataLoader.MemoryData(), engine);
    }

    @SuppressWarnings("unchecked")
    private PaintSector ownerOfLabel(PaintSector arrow) {
        HashSet<PaintSector> group = new HashSet<PaintSector>();
        arrow.getConnectedSector(group);
        for (PaintSector sector : group)
            if (sector.getText() != null)
                return sector;
        return null;
    }

    /**
     * Removes an arrow by name, and says how many it removed.
     *
     * <p>
     * By name because that is what an agent has: the arrows it drew and the ones get_diagram
     * reported are both named, and nothing else about a sector is nameable from outside.
     */
    int removeArrow(String name) {
        int removed = 0;
        for (PaintSector paint : new Vector<PaintSector>(sectors())) {
            Sector sector = paint.getSector();
            if (sector == null || sector.getName() == null)
                continue;
            if (!name.trim().equals(sector.getName().trim()))
                continue;
            paint.remove();
            removed++;
        }
        if (removed == 0)
            throw new IllegalArgumentException("No arrow called \"" + name
                    + "\" on this diagram. get_diagram lists the arrows it has.");
        return removed;
    }

    /**
     * Removes a box and the arrows attached to it.
     *
     * <p>
     * The arrows first, and that order is the application's: an arrow left pointing at a box
     * that no longer exists is exactly the corruption that shows up as a diagram which will
     * not open.
     */
    void removeActivity(Function activity) {
        if (!activity.isRemoveable())
            throw new IllegalStateException("\"" + activity.getName() + "\" cannot be "
                    + "removed. The top activity of a model is part of the model itself.");
        for (PaintSector paint : new Vector<PaintSector>(sectors())) {
            Sector sector = paint.getSector();
            if (sector == null)
                continue;
            if (touchesAnySide(sector.getStart(), activity)
                    || touchesAnySide(sector.getEnd(), activity))
                paint.remove();
        }
        if (!plugin.removeRow(activity))
            throw new IllegalStateException("\"" + activity.getName() + "\" could not be "
                    + "removed.");
    }

    private boolean touchesAnySide(NSectorBorder border, Function activity) {
        return border != null && border.getFunction() != null
                && border.getFunction().getElement().getId()
                        == activity.getElement().getId();
    }

    private Vector<PaintSector> sectors() {
        Vector<PaintSector> sectors = area.getRefactor().getSectors();
        return sectors == null ? new Vector<PaintSector>() : sectors;
    }

    /**
     * The stream an arrow carries, by name.
     *
     * <p>
     * An existing name is reused rather than duplicated, and that is the whole point: one
     * stream appearing on several diagrams is how a model says the same thing flows through
     * them, and it is what the report queries follow.
     */
    private Stream stream(String name) {
        for (Row row : plugin.getRecChilds(plugin.getBaseStream(), true))
            if (row instanceof Stream && name.equals(row.getName()))
                return (Stream) row;
        Stream created = (Stream) plugin.createRow(plugin.getBaseStream(), true);
        created.setName(name);
        return created;
    }

    /**
     * Writes the diagram's visual data back onto the parent activity. Until this runs the
     * arrows exist only in the panel.
     */
    void commit() throws java.io.IOException {
        area.getRefactor().saveToFunction();
        session.markChanged();
    }
}
