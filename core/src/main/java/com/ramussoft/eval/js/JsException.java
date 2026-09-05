package com.ramussoft.eval.js;

import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.WrappedException;

/**
 * A JavaScript failure, carrying the line number as a number.
 *
 * <p>The previous implementation scraped the last integer out of the engine's message text
 * with a regular expression, after stripping a package-name prefix that no JDK has produced
 * since Java 8. Rhino reports the line directly, so none of that is needed.
 */
public class JsException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int lineNumber;

    private final boolean timeout;

    private final Throwable javaCause;

    private JsException(String message, Throwable cause, int lineNumber,
                        boolean timeout, Throwable javaCause) {
        super(message, cause);
        this.lineNumber = lineNumber;
        this.timeout = timeout;
        this.javaCause = javaCause;
    }

    static JsException of(RhinoException e) {
        // A WrappedException means Ramus code that the script called threw. Keep the
        // original so callers can rethrow it, which is what the old ExceptionHolder did.
        Throwable java = (e instanceof WrappedException)
                ? ((WrappedException) e).getWrappedException() : null;
        String message = (java != null) ? String.valueOf(java) : e.details();
        return new JsException(message, e, e.lineNumber(), false, java);
    }

    static JsException timeout(String message) {
        return new JsException(message, null, 0, true, null);
    }

    /** 1-based line in the original script, or 0 when Rhino could not determine one. */
    public int getLineNumber() {
        return lineNumber;
    }

    public boolean isTimeout() {
        return timeout;
    }

    /** Non-null only when the script called into Java and that call threw. */
    public Throwable getJavaCause() {
        return javaCause;
    }
}
