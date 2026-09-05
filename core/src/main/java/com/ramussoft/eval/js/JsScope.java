package com.ramussoft.eval.js;

import java.util.concurrent.TimeUnit;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Wrapper;

/**
 * One isolated JavaScript global per script, with a time limit.
 *
 * <p>No Rhino type appears in any public signature here, so callers do not take a
 * compile-time dependency on the engine.
 *
 * <p>The time limit is enforced from inside the interpreter by
 * {@link JsRuntime#observeInstructionCount}, on the calling thread. That replaces the old
 * arrangement of a worker thread plus {@code Thread.stop()}, which stopped being an option
 * when JDK 20 made {@code Thread.stop()} throw unconditionally - and which could in any
 * case abort the worker in the middle of a database statement.
 *
 * <p>Known limit, and it is a deliberate trade: the observer only fires while JavaScript
 * bytecode is executing. A script blocked inside a Java call it made - a query over a huge
 * model, say - will not be interrupted. Bounding scripts while not bounding Java calls is
 * the correct side to err on; the alternative was killing a thread mid-statement.
 */
public final class JsScope {

    /** Sealed standard objects, shared by every scope, created once. */
    private static Scriptable standardObjects;

    private final ScriptableObject scope;

    private static synchronized Scriptable standardObjects() {
        if (standardObjects == null) {
            Context cx = JsRuntime.get().enterContext();
            try {
                standardObjects = cx.initStandardObjects(null, true);
            } finally {
                Context.exit();
            }
        }
        return standardObjects;
    }

    public JsScope() {
        Context cx = JsRuntime.get().enterContext();
        try {
            Scriptable parent = standardObjects();
            ScriptableObject s = (ScriptableObject) cx.newObject(parent);
            s.setPrototype(parent);
            s.setParentScope(null);
            // A child of the sealed standard objects, so names() below returns exactly what
            // the host put in plus what the script declared, and nothing else.
            this.scope = s;
        } finally {
            Context.exit();
        }
    }

    public void put(String name, Object value) {
        Context cx = JsRuntime.get().enterContext();
        try {
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope));
        } finally {
            Context.exit();
        }
    }

    public boolean hasFunction(String name) {
        Context cx = JsRuntime.get().enterContext();
        try {
            return ScriptableObject.getProperty(scope, name) instanceof Function;
        } finally {
            Context.exit();
        }
    }

    public String[] names() {
        Object[] ids = scope.getIds();
        String[] result = new String[ids.length];
        for (int i = 0; i < ids.length; i++)
            result[i] = String.valueOf(ids[i]);
        return result;
    }

    /**
     * @param timeoutMillis zero or less means no limit
     */
    public Object eval(String source, String sourceName, long timeoutMillis,
                       String timeoutMessage) throws JsException {
        Context cx = JsRuntime.get().enterContext();
        try {
            JsRuntime.setDeadline(deadline(timeoutMillis));
            return unwrap(cx.evaluateString(scope, source, sourceName, 1, null));
        } catch (JsRuntime.JsTimeoutError e) {
            throw JsException.timeout(timeoutMessage);
        } catch (RhinoException e) {
            throw JsException.of(e);
        } finally {
            JsRuntime.setDeadline(null);
            Context.exit();
        }
    }

    /**
     * @return null when no such function exists
     */
    public Object invoke(String function, Object[] args, long timeoutMillis,
                         String timeoutMessage) throws JsException {
        Context cx = JsRuntime.get().enterContext();
        try {
            Object f = ScriptableObject.getProperty(scope, function);
            if (!(f instanceof Function))
                return null;
            JsRuntime.setDeadline(deadline(timeoutMillis));
            Object[] wrapped = new Object[args.length];
            for (int i = 0; i < args.length; i++)
                wrapped[i] = Context.javaToJS(args[i], scope);
            return unwrap(((Function) f).call(cx, scope, scope, wrapped));
        } catch (JsRuntime.JsTimeoutError e) {
            throw JsException.timeout(timeoutMessage);
        } catch (RhinoException e) {
            throw JsException.of(e);
        } finally {
            JsRuntime.setDeadline(null);
            Context.exit();
        }
    }

    private static Long deadline(long timeoutMillis) {
        if (timeoutMillis <= 0L)
            return null;
        return Long.valueOf(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
    }

    private static Object unwrap(Object o) {
        if (o instanceof Wrapper)
            return ((Wrapper) o).unwrap();
        if (o == Scriptable.NOT_FOUND || o == Context.getUndefinedValue())
            return null;
        return o;
    }
}
