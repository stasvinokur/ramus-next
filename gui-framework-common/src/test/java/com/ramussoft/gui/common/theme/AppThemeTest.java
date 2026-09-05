package com.ramussoft.gui.common.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import javax.swing.UIManager;

import org.junit.BeforeClass;
import org.junit.Test;

import com.ramussoft.gui.common.prefrence.Options;

/**
 * The theme is the one thing in the interface that can be checked without looking at it.
 *
 * <p>Two of these assertions are here because the behaviour they pin is otherwise invisible
 * until the application is running: that the docking library will follow the theme, and that
 * the obsolete options keys really are gone. Neither shows up in a screenshot, and both would
 * fail silently.
 */
public class AppThemeTest {

    /**
     * The nine UIDefaults keys that DockingFrames' DefaultLookAndFeelColors reads. It maps
     * them onto its own dock.* colours, which FlatTheme then derives every title bar and
     * border from. If any of them is missing, the docking panels quietly fall back to
     * hardcoded colours and stop matching the rest of the window - and nothing says so.
     */
    private static final String[] DOCKING_COLOUR_KEYS = {
            "MenuItem.background",
            "MenuItem.foreground",
            "MenuItem.selectionBackground",
            "MenuItem.selectionForeground",
            "TextField.selectionBackground",
            "Panel.background",
            "Panel.foreground",
            "controlDkShadow",
            "windowBorder",
    };

    @BeforeClass
    public static void installTheme() {
        // Written before the theme is installed, and read back after it. setLookAndFeel
        // replaces the look and feel's own defaults table but not the values put on top of
        // it, which is why the 48 localised FileChooser strings in ResourceLoader's static
        // block are safe whichever side of the install they land on. Worth pinning: if that
        // ever changed, the Russian file dialog would quietly revert to English.
        UIManager.put("ramus.test.survives", "да");
        AppTheme.install();
    }

    @Test
    public void installsTheApplicationTheme() {
        assertEquals("com.formdev.flatlaf.FlatLightLaf",
                UIManager.getLookAndFeel().getClass().getName());
    }

    @Test
    public void keepsWhatWasPutBeforeIt() {
        assertEquals("да", UIManager.get("ramus.test.survives"));
    }

    @Test
    public void definesEveryColourTheDockingThemeReadsFromIt() {
        for (String key : DOCKING_COLOUR_KEYS)
            assertNotNull(key, UIManager.get(key));
    }

    /**
     * Text areas used to be forced to family "Tymes New Roman" at a fixed 14pt - a
     * misspelling of Times New Roman, so it never resolved. With the override gone they
     * follow the theme like every other component.
     */
    @Test
    public void letsTextComponentsUseTheThemeFont() {
        assertNotNull(UIManager.getFont("TextArea.font"));
        assertNotNull(UIManager.getFont("TextPane.font"));
        assertEquals(UIManager.getFont("Label.font").getFamily(),
                UIManager.getFont("TextArea.font").getFamily());
    }

    /**
     * Options.getString(name, default) writes its default back to disk, so a key outlives
     * the code that read it. These three are on disk for everyone who has ever started the
     * application and have to be removed rather than merely ignored.
     */
    @Test
    public void forgetsTheKeysWhoseCodeIsGone() {
        assertNull(Options.getString("LookAndFeel"));
        assertNull(Options.getString("TEXT_AREA_DEF_FONT"));
        assertNull(Options.getString("TEXT_AREA_DEF_FONT_SIZE"));
    }
}
