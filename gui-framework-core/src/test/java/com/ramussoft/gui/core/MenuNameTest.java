package com.ramussoft.gui.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.Test;

import com.ramussoft.gui.common.ViewPlugin;

/**
 * Upstream issue #17, "Some menus are empty, but doing something when selected".
 *
 * <p>
 * A plugin declares where its action belongs as a menu path, and the frame translates each
 * segment through the plugin's resource bundle. The lookup returns null for a key no
 * bundle defines, and the null used to travel straight into {@code new JMenu(null)}: a
 * menu with no visible name whose items still work, which is precisely the reported
 * symptom. At top level it does not even blank out, it throws - createJMenu locates an
 * existing menu by comparing {@code getText()}.
 *
 * <p>
 * Every key in the tree resolves today, so this is a guard against the next menu added
 * without a matching resource rather than a fix for something visible now.
 */
public class MenuNameTest {

    @Test
    public void amissingResourceFallsBackToTheKeyInsteadOfNull() {
        String name = PlugableFrame.menuName(pluginReturning(null), "Reports");

        assertNotNull("A menu whose resource key is missing must still have a name; "
                + "null here becomes new JMenu(null) and then a NullPointerException "
                + "in createJMenu.", name);
        assertEquals("Menu.Reports", name);
    }

    @Test
    public void aResolvedResourceIsUsedUnchanged() {
        assertEquals("Reports", PlugableFrame.menuName(pluginReturning("Reports"), "Reports"));
    }

    /**
     * The empty string is a resolved value, not a miss, so it must be passed through
     * rather than replaced by the key - a bundle is entitled to define a blank name.
     */
    @Test
    public void anEmptyResourceIsNotTreatedAsMissing() {
        assertEquals("", PlugableFrame.menuName(pluginReturning(""), "Reports"));
    }

    private static ViewPlugin pluginReturning(final String value) {
        return (ViewPlugin) Proxy.newProxyInstance(ViewPlugin.class.getClassLoader(),
                new Class<?>[]{ViewPlugin.class}, new InvocationHandler() {

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("getString".equals(method.getName()))
                            return value;
                        return method.getReturnType().isPrimitive()
                                ? Integer.valueOf(0) : null;
                    }
                });
    }
}
