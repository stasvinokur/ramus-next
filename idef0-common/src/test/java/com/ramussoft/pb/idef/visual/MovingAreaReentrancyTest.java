package com.ramussoft.pb.idef.visual;

import static org.junit.Assert.fail;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.Test;

import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;

/**
 * Upstream issue #26, "By the command Go to child diagrams": arrows from the parent stop
 * being carried into child diagrams, and another user's workaround is to decompose once
 * more and come back - i.e. to get a fresh panel.
 *
 * <p>
 * {@link MovingArea#setActiveFunction(Function)} guards re-entrancy with a boolean flag.
 * It is set on the way in and cleared on the way out, and the code between the two reaches
 * {@code SectorRefactor.loadFromFunction}, whose own try catches only IOException while
 * the border-creation calls inside it are known to throw - {@code createPainted} catches
 * NullPointerException around the very same calls. Without a finally, a single escape
 * leaves the flag stuck and every later navigation returns at the guard without reloading.
 *
 * <p>
 * The test drives that directly. Given a stub function, loadFromFunction throws out of the
 * first call - at SectorRefactor:676, where it casts the function to its own
 * implementation type - which is the very line the flag has to survive. What the second
 * call does is the whole question: it must try again and throw again. With the flag stuck
 * it returns silently instead, which is the defect.
 */
public class MovingAreaReentrancyTest {

    /**
     * The flag is private and there is no accessor, so the assertion is behavioural: a
     * second call has to do the same work as the first and fail the same way. Silence is
     * the failure - it means the guard swallowed the call.
     */
    @Test
    public void anExceptionDoesNotPermanentlyDisableTheDiagram() {
        MovingArea area = new MovingArea(stub(DataPlugin.class), stub(Function.class));
        Function function = stub(Function.class);

        assertReloadWasAttempted(area, function, "first call");
        assertReloadWasAttempted(area, function, "second call");
    }

    /**
     * Which exception escapes is not the point and is not asserted - only that one does,
     * because reaching the throw means the method actually went to reload the diagram
     * rather than returning at the re-entrancy guard.
     */
    private static void assertReloadWasAttempted(MovingArea area, Function function,
                                                 String which) {
        try {
            area.setActiveFunction(function);
        } catch (RuntimeException expected) {
            return;
        }
        fail(which + " to setActiveFunction returned without touching the diagram. "
                + "The re-entrancy flag is stuck, so navigation no longer reloads anything.");
    }

    /**
     * Both collaborators are interfaces, so a proxy returning zero-values is enough to
     * walk the method as far as the flag. Two of those defaults are load-bearing:
     * getType() must stay below {@link Function#TYPE_EXTERNAL_REFERENCE} or the method
     * returns early, and getDecompositionType() must differ from
     * {@link MovingArea#DIAGRAM_TYPE_DFDS} so the drop-target branch is skipped. Zero
     * satisfies both.
     */
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        Class<?> returnType = method.getReturnType();
                        if (!returnType.isPrimitive())
                            return null;
                        if (returnType == boolean.class)
                            return Boolean.FALSE;
                        if (returnType == void.class)
                            return null;
                        if (returnType == char.class)
                            return Character.valueOf('\0');
                        if (returnType == double.class)
                            return Double.valueOf(0);
                        if (returnType == float.class)
                            return Float.valueOf(0);
                        if (returnType == long.class)
                            return Long.valueOf(0);
                        return Integer.valueOf(0);
                    }
                });
    }
}
