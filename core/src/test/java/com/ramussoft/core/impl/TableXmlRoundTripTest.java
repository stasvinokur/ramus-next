package com.ramussoft.core.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Locale;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.ramussoft.jdbc.JDBCTemplate;

/**
 * A .rsf model file is a dump of the in-memory database, and the type of every column is
 * recorded in it as a bare string that {@link XMLToTable} then dispatches on when the file is
 * read back. That is a quiet trap: the string used to be whatever the JDBC driver of the day
 * called the type, so upgrading H2 - which renames four of the eight types this schema uses -
 * would have started writing files that neither a new build nor an old one could open, with
 * no error at save time to warn anybody.
 *
 * <p>Hence this round trip, which the project would otherwise have no test for at all. It is
 * deliberately literal about the type tokens: they are a file format, not an implementation
 * detail, and an older Ramus release has to keep recognising them.
 */
public class TableXmlRoundTripTest {

    /**
     * Mirrors the production schema: BIGINT, VARCHAR(255), BOOLEAN, INTEGER and TIMESTAMP
     * come from database.sql, TEXT, DOUBLE PRECISION and BYTEA are what
     * {@code PersistentField.DATABASE_TYPES} creates for user-defined attributes.
     */
    private static final String DDL = "CREATE TABLE ROUNDTRIP("
            + "ID BIGINT NOT NULL, "
            + "NAME VARCHAR(255), "
            + "BODY TEXT, "
            + "AMOUNT INTEGER, "
            + "FLAG BOOLEAN, "
            + "WEIGHT DOUBLE PRECISION, "
            + "MOMENT TIMESTAMP, "
            + "PAYLOAD BYTEA)";

    private static final List<String> TOKENS = Arrays.asList("BIGINT", "CLOB", "CLOB",
            "INTEGER", "BOOLEAN", "DOUBLE", "TIMESTAMP", "VARBINARY");

    /**
     * The format has minute resolution, so the fixture is minute-aligned; anything finer
     * would be testing that the assertion is wrong rather than that the code is.
     */
    private static final Timestamp MOMENT = moment();

    private static final byte[] PAYLOAD = {-128, -1, 0, 1, 127};

    private static final String TEXT =
            "Body <text> with an ampersand & a newline\nand a tab\t.";

    private Connection source;

    private int databases;

    private final List<Connection> open = new ArrayList<Connection>();

    private static Timestamp moment() {
        Calendar calendar = Calendar.getInstance();
        calendar.clear();
        calendar.set(2024, Calendar.SEPTEMBER, 7, 9, 21, 0);
        return new Timestamp(calendar.getTimeInMillis());
    }

    private Connection database() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:roundtrip" + (databases++), "sa", "");
        connection.setAutoCommit(false);
        Statement statement = connection.createStatement();
        statement.execute(DDL);
        statement.close();
        connection.commit();
        open.add(connection);
        return connection;
    }

    @Before
    public void setUp() throws Exception {
        source = database();

        PreparedStatement ps = source
                .prepareStatement("INSERT INTO ROUNDTRIP VALUES(?, ?, ?, ?, ?, ?, ?, ?)");
        ps.setLong(1, 42L);
        ps.setString(2, "Процесс A0");
        ps.setString(3, TEXT);
        ps.setInt(4, -17);
        ps.setBoolean(5, true);
        ps.setDouble(6, 3.5d);
        ps.setTimestamp(7, MOMENT);
        ps.setBytes(8, PAYLOAD);
        ps.execute();

        // Every value null but the key: the columns of a real model are mostly empty, and a
        // converter that is never asked to do anything must not be what decides whether the
        // file opens.
        ps.setLong(1, 43L);
        for (int i = 2; i <= 8; i++)
            ps.setObject(i, null);
        ps.execute();
        ps.close();
        source.commit();
    }

    @After
    public void tearDown() throws Exception {
        for (Connection connection : open)
            connection.close();
    }

    private byte[] store(Connection connection) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new TableToXML(new JDBCTemplate(connection), out, "ROUNDTRIP", "").store();
        return out.toByteArray();
    }

    private Connection load(byte[] xml) throws Exception {
        Connection connection = database();
        new XMLToTable(new JDBCTemplate(connection), new ByteArrayInputStream(xml),
                "ROUNDTRIP", "").load();
        return connection;
    }

    private static List<String> types(byte[] xml) {
        List<String> types = new ArrayList<String>();
        Matcher matcher = Pattern.compile("<field [^>]*type=\"([^\"]*)\"")
                .matcher(new String(xml, StandardCharsets.UTF_8));
        while (matcher.find())
            types.add(matcher.group(1));
        return types;
    }

    private void assertContentsSurvived(Connection connection) throws Exception {
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT * FROM ROUNDTRIP ORDER BY ID");

        assertTrue(rs.next());
        assertEquals(42L, rs.getLong("ID"));
        assertEquals("Процесс A0", rs.getString("NAME"));
        assertEquals(TEXT, rs.getString("BODY"));
        assertEquals(-17, rs.getInt("AMOUNT"));
        assertEquals(true, rs.getBoolean("FLAG"));
        assertEquals(3.5d, rs.getDouble("WEIGHT"), 0d);
        assertEquals(MOMENT, rs.getTimestamp("MOMENT"));
        assertArrayEquals(PAYLOAD, rs.getBytes("PAYLOAD"));

        assertTrue(rs.next());
        assertEquals(43L, rs.getLong("ID"));
        assertNull(rs.getString("NAME"));
        assertNull(rs.getTimestamp("MOMENT"));
        assertNull(rs.getBytes("PAYLOAD"));

        assertTrue(!rs.next());
        rs.close();
        statement.close();
    }

    /**
     * The tokens are asserted by value, not merely round-tripped, because the whole point of
     * them is to be understood by a build that is not this one.
     */
    @Test
    public void writesTheTypeTokensAnOlderReleaseUnderstands() throws Exception {
        assertEquals(TOKENS, types(store(source)));
    }

    @Test
    public void survivesTheRoundTrip() throws Exception {
        assertContentsSurvived(load(store(source)));
    }

    /**
     * The other half of the same promise: the names in files that already exist keep working.
     * CHAR is what every model saved before this change carries for a name column, and the
     * four long spellings are what H2 2.x reports for the same types.
     */
    @Test
    public void readsTheNamesOlderFilesAndNewerDriversUse() throws Exception {
        String[][] synonyms = {
                {"CLOB", "CHAR"},
                {"CLOB", "CHARACTER"},
                {"CLOB", "CHARACTER VARYING"},
                {"CLOB", "CHARACTER LARGE OBJECT"},
                {"VARBINARY", "BINARY VARYING"},
                {"VARBINARY", "BINARY LARGE OBJECT"},
                {"DOUBLE", "DOUBLE PRECISION"},
                {"INTEGER", "INT"},
                {"BOOLEAN", "BOOL"},
                {"BIGINT", "LONG"},
        };
        for (String[] synonym : synonyms) {
            byte[] xml = new String(store(source), StandardCharsets.UTF_8)
                    .replace("type=\"" + synonym[0] + "\"", "type=\"" + synonym[1] + "\"")
                    .getBytes(StandardCharsets.UTF_8);
            assertTrue(synonym[1], types(xml).contains(synonym[1]));
            assertContentsSurvived(load(xml));
        }
    }

    /**
     * The regression this whole change exists for. Java 9 moved the JRE to CLDR locale data,
     * which puts a comma into the English SHORT date-time pattern, so a modern build wrote
     * "9/7/24, 9:21 AM" and could no longer parse the "9/7/24 9:21 AM" in every file saved
     * before it. Nothing was reported: the parse failure was caught, printed and the column
     * left null, so dates simply went missing.
     */
    @Test
    public void writesTheDateFormatEveryReleaseWrote() throws Exception {
        String xml = new String(store(source), StandardCharsets.UTF_8);
        assertTrue(xml, xml.contains(">9/7/24 9:21 AM<"));
    }

    @Test
    public void readsBothSpellingsOfADate() throws Exception {
        assertEquals(MOMENT.getTime(), XMLToTable.parseDate("9/7/24 9:21 AM").getTime());
        assertEquals(MOMENT.getTime(), XMLToTable.parseDate("9/7/24, 9:21 AM").getTime());
    }

    /**
     * The spelling the runtime itself produces - which is not the one anybody types.
     *
     * <p>
     * Since CLDR 42, that is on every Java from 20 onwards, the short time format puts U+202F
     * before AM. Ramus 2.x takes its format from the runtime, so a model it saved on a current
     * Java has that character in every date. A test that types "9:21 AM" by hand asserts a
     * character no JRE writes, passes, and lets a file that will not open reach a user - which
     * is what happened. So the input is built the way the runtime builds it.
     *
     * <p>
     * Whether the mismatch throws depends on the JRE: Java 26 accepts the narrow space against
     * a plain one, the Temurin 21 this product ships does not. On a modern development machine
     * this test therefore passes either way; it earns its keep on the runtime that is
     * shipped, and that is where it was checked.
     */
    @Test
    public void readsTheDateTheRuntimeItselfWrites() throws Exception {
        String written = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT,
                Locale.ENGLISH).format(MOMENT);
        assertEquals("the round trip of what the runtime writes",
                MOMENT.getTime() / 60000, XMLToTable.parseDate(written).getTime() / 60000);
    }

    /** And the two characters spelled out, so the case survives whatever CLDR does next. */
    @Test
    public void readsADateWrittenWithANarrowNoBreakSpace() throws Exception {
        assertEquals(MOMENT.getTime(),
                XMLToTable.parseDate("9/7/24, 9:21\u202fAM").getTime());
        assertEquals(MOMENT.getTime(),
                XMLToTable.parseDate("9/7/24 9:21\u00a0AM").getTime());
    }

    @Test
    public void refusesADateItCannotRead() throws Exception {
        try {
            XMLToTable.parseDate("2024-09-07T09:21");
            fail("expected the unparseable date to be reported");
        } catch (SQLException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("2024-09-07T09:21"));
        }
    }

    /**
     * An unknown type used to print one line to System.err and store a null converter, so the
     * only symptom was a NullPointerException raised somewhere else entirely. It now names
     * the type and the table.
     */
    @Test
    public void namesATypeItDoesNotKnow() throws Exception {
        byte[] xml = new String(store(source), StandardCharsets.UTF_8)
                .replace("type=\"CLOB\"", "type=\"GEOMETRY\"")
                .getBytes(StandardCharsets.UTF_8);
        try {
            load(xml);
            fail("expected the unknown type to be reported");
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getCause() == null ? e : e.getCause());
            assertTrue(message, message.contains("GEOMETRY"));
        }
    }
}
