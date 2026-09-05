package com.ramussoft.report;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Map;
import java.util.Map.Entry;

import com.ramussoft.common.Engine;
import com.ramussoft.common.Metadata;
import com.ramussoft.eval.js.JsException;
import com.ramussoft.eval.js.JsScope;
import com.ramussoft.report.data.Data;
import com.ramussoft.report.data.Out;

public class JSSPReportEngine extends ReportEngine {

    private static final long DEMO_TIMEOUT_MILLIS = 50000L;

    private static final long CORPORATE_TIMEOUT_MILLIS = 300000L;

    /** Lines of source shown either side of the failing line on the error page. */
    private static final int CONTEXT_LINES = 5;

    protected ReportQueryImpl reportQuery;

    public JSSPReportEngine(Engine engine, ReportQueryImpl reportQuery) {
        super(engine);
        this.reportQuery = reportQuery;
    }

    protected static long timeoutMillis() {
        return Metadata.CORPORATE ? CORPORATE_TIMEOUT_MILLIS : DEMO_TIMEOUT_MILLIS;
    }

    /**
     * Derived rather than hard-coded. The old constant told the user "50 seconds" while
     * Metadata.CORPORATE made the real limit 300, so the message was simply wrong in the
     * shipped build.
     */
    protected static String scriptWorkedTooLong() {
        return MessageFormat.format(
                "Script worked too long ({0} s) and was interrupted.",
                Long.valueOf(timeoutMillis() / 1000L));
    }

    public void execute(String scriptPath, OutputStream stream,
                        Map<String, Object> parameters) throws IOException {
        byte[] bytes = engine.getStream(scriptPath);
        if (bytes == null)
            bytes = new byte[]{};
        String script = new String(bytes, StandardCharsets.UTF_8);
        JSSPToJsConverter converter = new JSSPToJsConverter(script);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        Out out = createOut(outputStream);
        try {
            JsScope scope = new JsScope();
            SimpleOut simpleOut = new SimpleOut(out);
            scope.put("doc", simpleOut);
            scope.put("document", simpleOut);
            scope.put("out", simpleOut);
            Query query = (Query) parameters.get("query");
            scope.put("data", new Data(this.engine, query, reportQuery));
            for (Entry<String, Object> entry : parameters.entrySet())
                scope.put(entry.getKey(), entry.getValue());

            // Runs on the calling thread. The deadline is enforced from inside the
            // interpreter, so there is no second thread to kill and Thread.stop - which
            // JDK 20 made throw unconditionally - is not needed. This also removes the
            // non-volatile `finished` flag and the wait() that was not in a loop, where a
            // spurious wakeup would abort a perfectly healthy report with a bogus timeout.
            scope.eval(converter.convert(), scriptPath, timeoutMillis(),
                    scriptWorkedTooLong());

            finish(out, outputStream, stream);
        } catch (JsException e) {
            // Preserves the old two-branch behaviour: a RuntimeException raised by Ramus
            // code the script called keeps propagating to the caller, which renders it as a
            // DataException; anything else becomes an error page.
            if (e.getJavaCause() instanceof RuntimeException)
                throw (RuntimeException) e.getJavaCause();
            writeError(stream, script, e);
        }
    }

    /** Overridden by {@link JSSPDocBookReportEngine}. */
    protected void finish(Out out, ByteArrayOutputStream outputStream,
                          OutputStream stream) throws IOException {
        out.flush();
        out.realWriteWithHTMLUpdate();
        stream.write(outputStream.toByteArray());
    }

    protected void writeError(OutputStream stream, String script, JsException e)
            throws IOException {
        Out out = new Out(stream);
        // Rhino reports the line directly. The old code scraped the last integer out of the
        // message text with a regular expression, after stripping a
        // "sun.org.mozilla.javascript.internal.EcmaError:" prefix that no JDK has produced
        // since Java 8.
        int number = e.getLineNumber();
        String message = escape(e.getMessage());
        out.println("<html>");
        out.println("<body>");
        if (number > 0) {
            out.println(message);
            out.println("<br><br><table width=\"100%\">");
            int from = Math.max(0, number - 1 - CONTEXT_LINES);
            BufferedReader br = new BufferedReader(new StringReader(script));
            for (int i = 0; i < from; i++)
                if (br.readLine() == null)
                    break;
            for (int i = from; i < number + CONTEXT_LINES; i++) {
                String line = br.readLine();
                if (line == null)
                    break;
                String text = escape(line);
                if (i + 1 == number)
                    text = "<font color=\"#FF0000\">" + text + "</font>";
                out.println("<tr><td width=\"1%\"><font color=green>" + (i + 1)
                        + "</font></td><td width=\"99%\"><pre>" + text
                        + "</pre></td></tr>");
            }
            out.println("</table>");
        } else {
            out.println("<pre>" + message + "</pre>");
        }
        out.println("</body>");
        out.println("</html>");
        out.flush();
        out.realWrite();
    }

    /** The old code printed the exception message into the page unescaped. */
    private static String escape(String s) {
        return (s == null) ? "" : s.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }

    protected Out createOut(ByteArrayOutputStream outputStream)
            throws UnsupportedEncodingException {
        return new Out(outputStream);
    }

    public class SimpleOut {
        private Out out;

        public SimpleOut(Out out) {
            this.out = out;
        }

        public Out getOut() {
            return out;
        }

        public void print(Object object) {
            out.print(object);
        }

        public void println(Object object) {
            out.println(object);
        }

        public void write(Object object) {
            out.print(object);
        }
    }
}
