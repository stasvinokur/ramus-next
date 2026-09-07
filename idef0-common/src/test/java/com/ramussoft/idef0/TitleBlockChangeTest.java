package com.ramussoft.idef0;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.Test;

import com.ramussoft.common.Element;
import com.ramussoft.pb.Row;

/**
 * Which sheets a change to the title block actually alters.
 *
 * <p>
 * Changing the author or the project of a model did not show until the application was
 * restarted, and it turned out to be two faults sharing one branch. The frame is drawn by
 * panels BESIDE the diagram rather than inside it, so the repaint never reached them - and,
 * separately, a model-level change was compared against the open sheet, which matches only
 * on the context diagram, so on any decomposition the change was dropped before a repaint
 * was even considered. PROJECT, USED AT, the reader table and an inherited author are
 * printed on every sheet.
 *
 * <p>
 * The repaint itself needs a screen to observe and the build machine has none. The decision
 * does not, which is why it lives in its own class and why these tests are the ones that
 * hold the fix in place.
 */
public class TitleBlockChangeTest {

    private static final long MODEL = 3;

    private static final long SHEET = 42;

    private static final long ANOTHER_MODEL = 99;

    /** A decomposition sheet: itself, then the model's base function above it. */
    private static final long[] DECOMPOSITION = {SHEET, MODEL};

    /** The context diagram, which IS the base function. */
    private static final long[] CONTEXT = {MODEL};

    /**
     * The defect itself. The project is printed on every sheet of the model, so a change to
     * it reaches every sheet - not just the one that happens to be the base function.
     */
    @Test
    public void theProjectIsPrintedOnEverySheetSoEverySheetIsAffected() {
        assertTrue("a decomposition prints the project too",
                TitleBlockChange.MODEL.affects(MODEL, DECOMPOSITION));
        assertTrue(TitleBlockChange.MODEL.affects(MODEL, CONTEXT));
    }

    /**
     * And the job the old code was doing by accident must still be done. One catalog holds
     * the base function of every model in the file and this listener is registered on it, so
     * a tab is told about other models as well as its own.
     */
    @Test
    public void anotherModelsProjectIsNotThisModelsBusiness() {
        assertFalse(TitleBlockChange.MODEL.affects(ANOTHER_MODEL, DECOMPOSITION));
        assertFalse(TitleBlockChange.MODEL.affects(ANOTHER_MODEL, CONTEXT));
    }

    /** An author set on the model is printed by a sheet that has none of its own. */
    @Test
    public void aSheetWithNoAuthorOfItsOwnPrintsTheOneAbove() {
        assertTrue(TitleBlockChange.SHEET_OR_ANY_PARENT.affects(MODEL, DECOMPOSITION));
        assertTrue(TitleBlockChange.SHEET_OR_ANY_PARENT.affects(SHEET, DECOMPOSITION));
        assertFalse("a sibling's author is not printed here",
                TitleBlockChange.SHEET_OR_ANY_PARENT.affects(7, DECOMPOSITION));
    }

    /** The status marker is not inherited, so only the sheet's own value moves it. */
    @Test
    public void statusIsNotInherited() {
        assertTrue(TitleBlockChange.SHEET_ONLY.affects(SHEET, DECOMPOSITION));
        assertFalse(TitleBlockChange.SHEET_ONLY.affects(MODEL, DECOMPOSITION));
    }

    /** Status was in no branch of the listener at all, so it never refreshed anything. */
    @Test
    public void statusAndTheRevisionStampAreTitleBlockAttributes() {
        assertEquals(TitleBlockChange.SHEET_ONLY,
                TitleBlockChange.of(IDEF0Plugin.F_STATUS));
        assertEquals(TitleBlockChange.SHEET_OR_ANY_PARENT,
                TitleBlockChange.of(IDEF0Plugin.F_SYSTEM_REV_DATE));
    }

    /** And the attributes the other branches handle must keep falling through to them. */
    @Test
    public void attributesThatBelongToAnotherBranchAreLeftAlone() {
        assertEquals(TitleBlockChange.NONE, TitleBlockChange.of(IDEF0Plugin.F_BOUNDS));
        assertEquals(TitleBlockChange.NONE, TitleBlockChange.of(IDEF0Plugin.F_FONT));
        assertEquals(TitleBlockChange.NONE, TitleBlockChange.of(IDEF0Plugin.F_BACKGROUND));
        assertEquals(TitleBlockChange.NONE, TitleBlockChange.of(null));
    }

    /**
     * Rebuilding the diagram is more expensive than repainting the frame, and a diagram edit
     * stamps the revision date - so an ancestor's date must not reload anything, while a
     * model-level change must, because the node letter it carries is drawn inside the
     * diagram.
     */
    @Test
    public void onlyAModelChangeOrTheSheetsOwnRowReloadsTheDiagram() {
        assertTrue(TitleBlockChange.MODEL.alsoChangesTheDiagram(MODEL, DECOMPOSITION));
        assertTrue(TitleBlockChange.SHEET_ONLY.alsoChangesTheDiagram(SHEET, DECOMPOSITION));
        assertFalse("an ancestor's author changes one printed line and nothing else",
                TitleBlockChange.SHEET_OR_ANY_PARENT.alsoChangesTheDiagram(
                        MODEL, DECOMPOSITION));
    }

    /** The walk itself: from the sheet up to the model, in that order, and it terminates. */
    @Test
    public void theSheetPathRunsFromTheSheetUpToTheModel() {
        Row model = row(MODEL, null);
        Row sheet = row(SHEET, model);

        assertArrayEquals(new long[]{SHEET, MODEL}, TitleBlockChange.sheetPath(sheet));
        assertArrayEquals(new long[]{MODEL}, TitleBlockChange.sheetPath(model));
    }

    /** Nothing to compare against is not a reason to repaint everything. */
    @Test
    public void anEmptyPathAffectsNothing() {
        assertFalse(TitleBlockChange.MODEL.affects(MODEL, new long[0]));
        assertFalse(TitleBlockChange.SHEET_ONLY.affects(SHEET, null));
        assertFalse(TitleBlockChange.NONE.affects(MODEL, DECOMPOSITION));
    }

    /**
     * A row that answers only the two questions the walk asks. Building a real one wants a
     * database, and the walk is pure enough not to need it.
     */
    private static Row row(long elementId, Row parent) {
        Element element = new Element(elementId, 0, "");
        return (Row) Proxy.newProxyInstance(Row.class.getClassLoader(),
                new Class[]{Row.class}, new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getElement".equals(method.getName()))
                            return element;
                        if ("getParentRow".equals(method.getName()))
                            return parent;
                        throw new UnsupportedOperationException(
                                "the walk asked for " + method.getName());
                    }
                });
    }
}
