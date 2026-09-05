package com.ramussoft.eval.js;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;

/**
 * The only class in Ramus Next that configures Rhino. Everything else reaches
 * JavaScript through {@link JsScope}.
 *
 * <p>Deliberately not installed with {@code ContextFactory.initGlobal()}. Entering
 * contexts through our own factory instance keeps the instruction observer ours,
 * leaves no process-wide state for anything else to trip over, and avoids the
 * one-shot race that {@code initGlobal} imposes.
 */
final class JsRuntime extends ContextFactory {

    /** Instructions between two deadline checks. A few hundred microseconds' worth. */
    private static final int OBSERVER_THRESHOLD = 100000;

    private static final JsRuntime INSTANCE = new JsRuntime();

    /** Absolute System.nanoTime() deadline for whatever script this thread is running. */
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<Long>();

    private JsRuntime() {
    }

    static JsRuntime get() {
        return INSTANCE;
    }

    @Override
    protected Context makeContext() {
        Context cx = super.makeContext();
        // Interpreted mode, on purpose, for three reasons:
        //  * observeInstructionCount fires reliably, which is what bounds a runaway script;
        //  * a JSSP page expands into ONE flat top-level script of thousands of doc.print
        //    calls, which in compiled mode becomes one generated method and hits the JVM's
        //    64 KB method size limit on any large report;
        //  * no bytecode is generated at runtime, so no class-loader games.
        // Reports are I/O bound anyway, so the interpreter costs nothing that matters.
        cx.setInterpretedMode(true);
        cx.setInstructionObserverThreshold(OBSERVER_THRESHOLD);
        // Language version is left at the default deliberately. The existing user scripts
        // were written against the ES3/ES5 Rhino that shipped inside JDK 6 and 7 - the error
        // handling used to strip a "sun.org.mozilla.javascript.internal" prefix, which is
        // that engine's package name - so tightening the version could break them.
        return cx;
    }

    @Override
    protected void observeInstructionCount(Context cx, int instructionCount) {
        Long deadline = DEADLINE.get();
        if (deadline != null && System.nanoTime() - deadline.longValue() >= 0L)
            throw new JsTimeoutError();
    }

    static void setDeadline(Long nanoTime) {
        if (nanoTime == null)
            DEADLINE.remove();
        else
            DEADLINE.set(nanoTime);
    }

    /**
     * Thrown out of the instruction observer. An Error rather than an Exception so that a
     * <code>try { ... } catch (e) { }</code> inside a report script cannot swallow its own
     * cancellation.
     */
    static final class JsTimeoutError extends Error {

        private static final long serialVersionUID = 1L;

        JsTimeoutError() {
            super("script timed out", null, false, false);
        }
    }
}
