package com.ramussoft.core.impl;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The sequence statements the application builds at runtime, checked against the driver it
 * actually ships with.
 *
 * <p>This exists because of a hole in an earlier check rather than out of general caution.
 * When the H2 upgrade replaced {@code CREATE SEQUENCE ... START n} with {@code START WITH n},
 * seven occurrences were found by searching for the literal {@code START 1}. The eighth,
 * in {@link FileIEngineImpl#loadSequences}, is assembled by concatenation with a value read
 * out of the model file, so no search for a literal could have found it.
 *
 * <p>It survived the round-trip test too, and the reason is worth remembering: that test
 * built its model with only the simple attribute plugins loaded, and the sequences in
 * question belong to the IDEF0 plugin. So the file it produced had a sequences.xml with no
 * entries in it, the loop over those entries ran zero times, and a statement that cannot
 * parse was never executed. The code path was covered; the data was empty. Opening any real
 * model threw immediately.
 */
public class SequenceSyntaxTest {

    private Connection connection;

    @Before
    public void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:sequencesyntax", "sa", "");
        connection.setAutoCommit(true);
    }

    @After
    public void tearDown() throws Exception {
        connection.close();
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    /**
     * The shape FileIEngineImpl.loadSequences builds, with a start value out of a model
     * file rather than the constant 1.
     */
    @Test
    public void acceptsTheStatementUsedToRestoreASequence() throws Exception {
        execute("DROP SEQUENCE IF EXISTS ramus_ordinates__sequence;");
        execute("CREATE SEQUENCE ramus_ordinates__sequence START WITH 95;");
    }

    /**
     * And the form that used to be there, so that anyone tempted to shorten it back finds
     * out from a test rather than from a model that will not open.
     */
    @Test
    public void rejectsTheFormThatUsedToBeThere() throws Exception {
        try {
            execute("CREATE SEQUENCE ramus_legacy_form START 95;");
            fail("START without WITH is expected to be a syntax error on this driver");
        } catch (SQLException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Syntax error"));
        }
    }

    /**
     * The drop is unconditional now. It used to be wrapped in a catch that discarded the
     * exception, which is how a sequence that is simply not there yet and a statement that
     * cannot parse looked identical.
     */
    @Test
    public void toleratesDroppingASequenceThatIsNotThere() throws Exception {
        execute("DROP SEQUENCE IF EXISTS ramus_never_created;");
    }
}
