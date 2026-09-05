package com.ramussoft.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import com.ramussoft.common.Engine;
import com.ramussoft.report.data.Out;

/**
 * DocBook output differs from HTML output in exactly one respect: the final write does not
 * apply the HTML fixups.
 *
 * <p>This used to be a 175-line copy of {@link JSSPReportEngine#execute} carrying its own
 * duplicate of the worker thread, the wait, the {@code Thread.stop()} call and the error
 * page renderer, all so that one method call at the end could differ.
 */
public class JSSPDocBookReportEngine extends JSSPReportEngine {

    public JSSPDocBookReportEngine(Engine engine, ReportQueryImpl reportQuery) {
        super(engine, reportQuery);
    }

    @Override
    protected void finish(Out out, ByteArrayOutputStream outputStream,
                          OutputStream stream) throws IOException {
        out.flush();
        out.realWrite(false);
        stream.write(outputStream.toByteArray());
    }
}
