package com.ramussoft.eval;

import com.ramussoft.common.Metadata;
import com.ramussoft.eval.js.JsException;
import com.ramussoft.eval.js.JsScope;

public class ScriptFunctionsHolder {

    /**
     * Formulas are recalculated on the UI thread whenever an attribute changes, so a
     * runaway loop here freezes the application. Keep the limit tight.
     */
    private static final long TIMEOUT_MILLIS = 5000L;

    private static final String TIMEOUT_MESSAGE =
            "Script function ran longer than " + (TIMEOUT_MILLIS / 1000L) + " s";

    private final JsScope scope = new JsScope();

    public ScriptFunctionsHolder(String script) throws JsException {
        scope.eval(script, "/script", TIMEOUT_MILLIS, TIMEOUT_MESSAGE);
    }

    public EObject tryToInvoke(String functionName, Object[] objects) {
        if (!scope.hasFunction(functionName))
            return null;
        try {
            return new EObject(scope.invoke(functionName, objects,
                    TIMEOUT_MILLIS, TIMEOUT_MESSAGE));
        } catch (JsException e) {
            if (Metadata.DEBUG)
                e.printStackTrace();
            return null;
        }
    }

    /**
     * Note a deliberate change of meaning: this used to return true for any non-null
     * binding, so Math, print or a plain variable all counted as functions. It now
     * requires an actual function, which is what the formula engine means by the question.
     */
    public boolean isFunctionExists(String function) {
        return scope.hasFunction(function);
    }

    public String[] getFunctions() {
        return scope.names();
    }
}
