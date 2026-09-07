package com.ramussoft.idef0;

import java.util.ArrayList;
import java.util.List;

import com.ramussoft.pb.Row;

/**
 * Whether a changed attribute alters the frame drawn around one diagram, and if so, around
 * which ones.
 *
 * <p>
 * The frame prints things that belong to the whole model - PROJECT, USED AT, the reader
 * table - and things that belong to one sheet - its author, its dates, the status marker.
 * They are stored in different places and inherit differently, so "does this change affect
 * the sheet I am showing" has three different answers depending on the attribute. Getting
 * that wrong is not cosmetic in either direction: too narrow and the frame silently keeps
 * printing something that is no longer true, too wide and every tab of every model repaints
 * on every edit.
 *
 * <p>
 * It lives here, apart from the panel, because it is the whole of the decision and because
 * the decision is the part worth testing. Repainting a component cannot be observed without
 * a screen, and the machine the build runs on has none; this can be checked on any machine,
 * which is why the rules below have tests and the two-line repaint that follows them does
 * not.
 */
public enum TitleBlockChange {

    /** Nothing in the frame depends on this attribute. */
    NONE,

    /**
     * Belongs to the model and is printed on every one of its sheets, so any sheet of that
     * model is affected - and no sheet of any other.
     */
    MODEL,

    /**
     * Belongs to one activity but is inherited downwards when unset, so a sheet is affected
     * by its own value and by that of anything above it.
     */
    SHEET_OR_ANY_PARENT,

    /** Belongs to one activity and is not inherited, so only that sheet is affected. */
    SHEET_ONLY;

    /**
     * Which kind of change an attribute is, by name.
     *
     * <p>
     * By name rather than by identity because that is what the event carries, and because
     * these names are the model's own vocabulary - the same strings the report engine and
     * the file format use.
     */
    public static TitleBlockChange of(String attributeName) {
        if (attributeName == null)
            return NONE;
        if (IDEF0Plugin.F_PROJECT_PREFERENCES.equals(attributeName))
            return MODEL;
        if (IDEF0Plugin.F_AUTHOR.equals(attributeName)
                || IDEF0Plugin.F_CREATE_DATE.equals(attributeName)
                || IDEF0Plugin.F_REV_DATE.equals(attributeName)
                || IDEF0Plugin.F_SYSTEM_REV_DATE.equals(attributeName))
            return SHEET_OR_ANY_PARENT;
        if (IDEF0Plugin.F_STATUS.equals(attributeName))
            return SHEET_ONLY;
        return NONE;
    }

    /**
     * The elements from a sheet up to the model it belongs to, the sheet first and the
     * model's base function last.
     *
     * <p>
     * The last entry is what identifies the model here. There is no cheaper way to ask:
     * the listener is registered on a catalog that every model shares, so a tab is told
     * about changes to other models' base functions as well as its own, and the only thing
     * that distinguishes them is whether the changed element is the one this sheet's chain
     * ends at.
     */
    public static long[] sheetPath(Row sheet) {
        List<Long> path = new ArrayList<Long>();
        for (Row row = sheet; row != null; row = row.getParentRow())
            if (row.getElement() != null)
                path.add(row.getElement().getId());
        long[] ids = new long[path.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = path.get(i);
        return ids;
    }

    /** Whether a change to this element alters the frame around the sheet with this path. */
    public boolean affects(long changedElementId, long[] sheetPath) {
        if (sheetPath == null || sheetPath.length == 0)
            return false;
        switch (this) {
            case MODEL:
                return changedElementId == sheetPath[sheetPath.length - 1];
            case SHEET_OR_ANY_PARENT:
                for (long id : sheetPath)
                    if (id == changedElementId)
                        return true;
                return false;
            case SHEET_ONLY:
                return changedElementId == sheetPath[0];
            default:
                return false;
        }
    }

    /**
     * Whether the diagram itself has to be rebuilt as well, or only the frame around it.
     *
     * <p>
     * Two cases need more than a repaint. A model-level change carries the node letter,
     * which is drawn inside the diagram and not only in the frame. And a change to the
     * sheet's own row is what the panel has always reloaded on, so that stays as it was
     * rather than being quietly narrowed. A change to an ancestor's author is neither: it
     * alters one printed line and nothing else.
     */
    public boolean alsoChangesTheDiagram(long changedElementId, long[] sheetPath) {
        if (this == NONE || sheetPath == null || sheetPath.length == 0)
            return false;
        return this == MODEL || changedElementId == sheetPath[0];
    }
}
