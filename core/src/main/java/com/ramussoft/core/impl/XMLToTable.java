package com.ramussoft.core.impl;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.ramussoft.jdbc.JDBCCallback;
import com.ramussoft.jdbc.JDBCTemplate;

public class XMLToTable {

    private JDBCTemplate template;

    private String tableName;

    private String prefix;

    private InputStream stream;

    /**
     * The date format of a model file. Up to Java 8 this was exactly what
     * {@code DateFormat.getDateTimeInstance(SHORT, SHORT, Locale.ENGLISH)} produced, and
     * that is what every Ramus release wrote into every .rsf ever saved.
     *
     * <p>Java 9 replaced the JRE locale data with CLDR, and CLDR puts a comma between the
     * date and the time: the very same call now yields <code>9/7/24, 9:21 AM</code> where it
     * used to yield <code>9/7/24 9:21 AM</code>. A build running on a modern JRE therefore
     * could not parse a single date out of any file written before it - and the dates did
     * not fail loudly, they silently disappeared, because {@link DateConverter} caught the
     * {@link ParseException}, printed it and left the column at null.
     *
     * <p>So the pattern is pinned rather than looked up, and reading accepts both spellings:
     * the first entry is what is written, every entry is understood.
     */
    private static final String[] DATE_PATTERNS = {"M/d/yy h:mm a", "M/d/yy, h:mm a"};

    public static DateFormat DATE_FORMAT = new SimpleDateFormat(DATE_PATTERNS[0],
            Locale.ENGLISH);

    /**
     * {@link DateFormat} is not thread safe, and {@link #DATE_FORMAT} is shared with
     * {@link TableToXML}, so both directions go through these two methods.
     */
    public static synchronized String formatDate(Object date) {
        return DATE_FORMAT.format(date);
    }

    public static synchronized Date parseDate(String value) throws SQLException {
        String normal = normalizeSpaces(value);
        try {
            return DATE_FORMAT.parse(normal);
        } catch (ParseException e) {
            // DATE_FORMAT is public and writable, so fall through to the known patterns
            // rather than trusting it to be one of them.
        }
        for (String pattern : DATE_PATTERNS) {
            try {
                return new SimpleDateFormat(pattern, Locale.ENGLISH).parse(normal);
            } catch (ParseException e) {
                // try the next spelling
            }
        }
        throw new SQLException("The date \"" + value + "\" in this model file is written in "
                + "a format this version cannot read. The file was probably written by "
                + "another version of Ramus; open it there and save it again, or report the "
                + "date above.");
    }

    /**
     * Turns every kind of space into the ordinary one before a date is parsed.
     *
     * <p>
     * Not a nicety. Since CLDR 42 - that is, on every Java from 20 onwards - the short time
     * format puts U+202F, a narrow no-break space, before AM and PM. Ramus 2.x takes its
     * format from the runtime, so a model saved by it on a current Java has that character in
     * every date, and a pattern written with an ordinary space does not match it. The result
     * was that such a model would not open at all.
     *
     * <p>
     * Worth knowing before touching this: whether the mismatch throws depends on the JRE.
     * Java 26 quietly accepts the narrow space against a plain one in the pattern; the Temurin
     * 21 this product ships does not. So the failure is invisible on a modern development
     * machine and certain on a user's, which is exactly how it reached one.
     *
     * <p>
     * Normalising here rather than adding two more entries to {@link #DATE_PATTERNS} covers
     * U+00A0 as well, survives whatever CLDR does to whitespace next, and changes nothing
     * about what is written - the writer stays pinned to ASCII, so files remain byte for byte
     * what they were.
     */
    private static String normalizeSpaces(String value) {
        return value == null ? null
                : value.replace('\u202f', ' ').replace('\u00a0', ' ').replace('\u2009', ' ');
    }

    private interface Converter {
        void fill(PreparedStatement ps, int column, String value)
                throws SQLException;
    }

    private class StringConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setString(column, value);
        }

    }

    private class IntegerConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setInt(column, Integer.parseInt(value));
        }

    }

    private class BooleanConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setBoolean(column, Boolean.parseBoolean(value));
        }

    }

    private class DoubleConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setDouble(column, Double.parseDouble(value));
        }

    }

    private class LongConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setLong(column, Long.parseLong(value));
        }

    }

    private class ByteAConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else {
                try {
                    if (value.length() == 0)
                        ps.setBytes(column, new byte[]{});
                    else {
                        byte[] bs = new byte[value.length() / 2];
                        int len = value.length();
                        for (int i = 0; i < len; i += 2) {
                            int val;
                            char c = value.charAt(i);
                            if (c >= 'A')
                                val = 16 * (c - 'A' + 10);
                            else
                                val = 16 * (c - '0');
                            c = value.charAt(i + 1);
                            if (c >= 'A')
                                val += (c - 'A' + 10);
                            else
                                val += (c - '0');
                            bs[i / 2] = (byte) (val - 128);
                        }
                        ps.setBytes(column, bs);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

    }

    private class DateConverter implements Converter {

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            if (value == null)
                ps.setObject(column, null);
            else
                ps.setTimestamp(column,
                        new Timestamp(parseDate(value).getTime()));
        }

    }

    /**
     * Stands in for a column whose type this build does not recognise. Nothing is thrown
     * while the field list is read, because a column whose every value is null never reaches
     * a converter and refusing to open such a file would be a regression; the failure
     * happens on the first real value instead, and says which type and which table.
     */
    private class UnknownTypeConverter implements Converter {

        private final String type;

        public UnknownTypeConverter(String type) {
            this.type = type;
        }

        @Override
        public void fill(PreparedStatement ps, int column, String value)
                throws SQLException {
            throw new SQLException("Unknown column type \"" + type + "\" in table "
                    + prefix + tableName);
        }

    }

    /**
     * Chooses the converter for one column of a model file.
     *
     * <p>The token comes from {@link TableToXML#canonicalTypeName}, which since this change
     * writes a fixed name instead of whatever the JDBC driver of the day called the type.
     * The driver names stay accepted, because every file written before this change carries
     * one: H2 1.x wrote CLOB, CHAR, VARBINARY and DOUBLE, H2 2.x renames all four, and
     * PostgreSQL - which the removed server module spoke - wrote bpchar, int4, int8, float8
     * and bytea.
     */
    private Converter createConverter(String type) {
        switch (type.toUpperCase(Locale.ROOT)) {
            case "CLOB":
            case "CHAR":
            case "TEXT":
            case "BPCHAR":
            case "VARCHAR":
            case "VARCHAR_IGNORECASE":
            case "CHARACTER":
            case "CHARACTER VARYING":
            case "CHARACTER LARGE OBJECT":
            case "NCHAR":
            case "NVARCHAR":
            case "NCLOB":
                return new StringConverter();
            case "INTEGER":
            case "INT":
            case "INT4":
            case "SMALLINT":
            case "TINYINT":
                return new IntegerConverter();
            case "BLOB":
            case "VARBINARY":
            case "BYTEA":
            case "BINARY":
            case "RAW":
            case "BINARY VARYING":
            case "BINARY LARGE OBJECT":
                return new ByteAConverter();
            case "TIMESTAMP":
            case "DATETIME":
            case "TIMESTAMP WITHOUT TIME ZONE":
                return new DateConverter();
            case "LONG":
            case "BIGINT":
            case "INT8":
                return new LongConverter();
            case "BOOL":
            case "BOOLEAN":
                return new BooleanConverter();
            case "DOUBLE":
            case "FLOAT8":
            case "FLOAT":
            case "REAL":
            case "DOUBLE PRECISION":
                return new DoubleConverter();
            default:
                return new UnknownTypeConverter(type);
        }
    }

    public XMLToTable(JDBCTemplate template, InputStream stream,
                      String tableName, String prefix) {
        this.template = template;
        this.tableName = tableName;
        this.prefix = prefix;
        this.stream = stream;
    }

    public void load() throws IOException, SQLException {
        final Hashtable<String, Integer> positions = new Hashtable<String, Integer>();
        final List<String> columns = new ArrayList<String>();
        final Hashtable<String, String> ids = new Hashtable<String, String>();

        final PreparedStatement ps = (PreparedStatement) template
                .execute(new JDBCCallback() {

                    @Override
                    public Object execute(Connection connection)
                            throws SQLException {
                        Statement st = connection.createStatement();
                        ResultSet rs = st.executeQuery("SELECT * FROM "
                                + prefix + tableName);
                        ResultSetMetaData meta = rs.getMetaData();

                        String colums = "";
                        String values = "";
                        int columnCount = meta.getColumnCount();
                        for (int i = 0; i < columnCount; i++) {
                            if (values.equals(""))
                                values += "?";
                            else
                                values += ", ?";

                            String cn = meta.getColumnName(i + 1);
                            if (colums.equals(""))
                                colums += cn;
                            else
                                colums += ", " + cn;
                            String cnLowerCase = cn.toLowerCase();
                            columns.add(cnLowerCase);
                            positions.put(cn.toLowerCase(), i + 1);
                        }
                        rs.close();
                        st.close();
                        PreparedStatement ps = connection
                                .prepareStatement("INSERT INTO " + prefix
                                        + tableName + "(" + colums
                                        + ") VALUES(" + values + ");");
                        for (int i = 1; i <= columnCount; i++)
                            if (columns.get(i - 1).equals("removed_branch_id"))
                                ps.setLong(i, Integer.MAX_VALUE);
                            else
                                ps.setObject(i, null);
                        return ps;
                    }
                });
        template.execute(new JDBCCallback() {
            @Override
            public Object execute(final Connection connection)
                    throws SQLException {
                SAXParserFactory factory = SAXParserFactory.newInstance();
                SAXParser parser;

                final Hashtable<String, Converter> converters = new Hashtable<String, Converter>();

                try {
                    parser = factory.newSAXParser();
                    parser.parse(stream, new DefaultHandler() {
                        StringBuilder sb = new StringBuilder();

                        private boolean initFields = true;

                        private boolean inRow = false;

                        private String id;

                        @Override
                        public void startElement(String uri, String localName,
                                                 String name, Attributes attributes)
                                throws SAXException {
                            if ("data".equals(name))
                                initFields = false;
                            if ("row".equals(name)) {
                                inRow = true;
                                for (int i = 1; i <= positions.size(); i++)
                                    try {
                                        String column = columns.get(i - 1);
                                        if (column.endsWith("_branch_id")
                                                && !column
                                                .equals("removed_branch_id"))
                                            ps.setLong(i, 0l);
                                        else if (column
                                                .equals("removed_branch_id"))
                                            ps.setObject(i, Integer.MAX_VALUE);
                                        else
                                            ps.setObject(i, null);
                                    } catch (SQLException e) {
                                        throw new RuntimeException(e);
                                    }
                            }
                            if ((inRow) && ("f".equals(name))) {
                                id = attributes.getValue("id");
                            }
                            if ((initFields) && ("field".equals(name))) {
                                String type = attributes.getValue("type");

                                ids.put(attributes.getValue("id"),
                                        attributes.getValue("name"));

                                converters.put(attributes.getValue("id"),
                                        createConverter(type));
                            }
                        }

                        @Override
                        public void endElement(String uri, String localName,
                                               String name) throws SAXException {
                            if ("row".equals(name)) {
                                try {
                                    inRow = false;
                                    ps.execute();
                                } catch (SQLException e) {
                                    e.printStackTrace();
                                }
                            } else if ((inRow) && ("f".equals(name))) {
                                String key = ids.get(id);
                                int i = -1;
                                try {
                                    i = positions.get(key.toLowerCase());
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                if (i > 0) {
                                    Converter c = converters.get(id);
                                    try {
                                        c.fill(ps, i, sb.toString());
                                    } catch (SQLException e) {
                                        throw new RuntimeException(e);
                                    }
                                }
                            }
                            sb = new StringBuilder();
                        }

                        @Override
                        public void characters(char[] ch, int start, int length)
                                throws SAXException {
                            sb.append(new String(ch, start, length));
                        }

                        public void endDocument() throws SAXException {
                            try {
                                ps.close();
                            } catch (SQLException e) {
                                e.printStackTrace();
                            }
                        }

                        ;
                    });
                } catch (ParserConfigurationException e) {
                    e.printStackTrace();
                } catch (SAXException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
        });

        stream.close();
    }
}
