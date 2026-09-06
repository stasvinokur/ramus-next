package com.ramussoft.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.SQLException;

import org.junit.Test;

/**
 * What an agent is told when a tool refuses.
 *
 * <p>
 * This is the only channel a failure has. An agent cannot read a log or a stack trace; it reads
 * one line and either corrects itself or gives up. The line that prompted these tests was
 * {@code java.sql.SQLException: Unparseable date: "9/5/26, 9:21 AM"} - the useful sentence was
 * in there, wrapped in a class name, and the agent had nowhere to go with it.
 */
public class ToolFailureTest {

    /** The ordinary case: a tool refused on purpose, and its own words are the whole answer. */
    @Test
    public void aDeliberateRefusalIsPassedThroughWithTheToolThatMadeIt() {
        String said = Tools.explain("list_elements", new IllegalArgumentException(
                "No catalog called \"Docs\". This model has: Документы, Роли"));

        assertEquals("list_elements failed: No catalog called \"Docs\". "
                + "This model has: Документы, Роли", said);
    }

    /**
     * The case that started this: the sentence is at the bottom of the chain, and everything
     * above it is machinery.
     */
    @Test
    public void aWrappedFailureIsToldByItsDeepestCause() {
        SQLException cause = new SQLException("The date \"9/5/26, 9:21 AM\" in this model file "
                + "is written in a format this version cannot read.");
        // How XMLToTable actually wraps it - and RuntimeException(cause) takes the cause's
        // toString as its own message, which is where the class name comes from.
        RuntimeException wrapped = new RuntimeException(new RuntimeException(cause));

        String said = Tools.explain("open_model", wrapped);

        assertTrue(said, said.startsWith("open_model failed: The date "));
        assertFalse("no class name reaches the agent", said.contains("SQLException"));
        assertFalse(said, said.contains("java."));
    }

    /** A throwable with nothing to say anywhere in the chain still has to name itself. */
    @Test
    public void aFailureWithNoMessageAtAllIsNamedRatherThanBlank() {
        String said = Tools.explain("render_diagram",
                new IllegalStateException(new NullPointerException()));

        assertEquals("render_diagram failed: NullPointerException", said);
    }

    /**
     * The deepest cause is not always the one that speaks. When it is mute, the nearest thing
     * above it that said something is the answer - not an empty line.
     */
    @Test
    public void aMuteCauseFallsBackToWhateverAboveItSpoke() {
        String said = Tools.explain("add_arrow", new IllegalStateException(
                "The arrow has no straight run long enough to branch from",
                new NullPointerException()));

        assertEquals("add_arrow failed: The arrow has no straight run long enough to branch "
                + "from", said);
    }

    /**
     * And the stripping must not eat prose. A message may perfectly well contain a colon, or
     * open with a capitalised word - a name, a label, a heading - and taking that for a class
     * name would throw away the half that matters.
     */
    @Test
    public void proseWithAColonInItSurvivesIntact() {
        assertEquals("save failed: Cannot write the model: the disk is full",
                Tools.explain("save", new java.io.IOException(
                        "Cannot write the model: the disk is full")));
        assertEquals("get_diagram failed: Функция: не найдена",
                Tools.explain("get_diagram", new IllegalArgumentException(
                        "Функция: не найдена")));
    }
}
