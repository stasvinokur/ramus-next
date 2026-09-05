package com.ramussoft.gui.common.theme;

import javax.swing.UIManager;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.ramussoft.gui.common.prefrence.Options;

/**
 * The application's look and feel, in one place.
 *
 * <p>This used to be the system look and feel, chosen inline in the launcher: Aqua on macOS,
 * and deliberately Metal rather than GTK on Linux. It is FlatLaf now for one reason - the
 * application ships for macOS and for Windows, and looking the same on both is the point.
 */
public final class AppTheme {

    /**
     * Options keys that were written by code that no longer exists. They are removed on
     * startup rather than merely ignored, because {@code Options.getString(name, default)}
     * writes its default back into options.conf the first time it is asked - so these are
     * not hypothetical leftovers, they are on disk for everyone who has ever run the
     * application, and they would outlive the code that put them there.
     */
    private static final String[] OBSOLETE_KEYS = {
            // A raw look-and-feel class name, written by a menu this change deletes.
            // Honouring it would leave anyone who ever opened that menu on the old
            // appearance with no way back, and a name that no longer resolves used to drop
            // them silently onto Metal.
            "LookAndFeel",
            // The font of every text area, overridden to family "Tymes New Roman" - a
            // misspelling of Times New Roman, so it never resolved and Swing quietly fell
            // back. Text areas now use the theme font like everything else.
            "TEXT_AREA_DEF_FONT",
            "TEXT_AREA_DEF_FONT_SIZE",
    };

    private AppTheme() {
    }

    /**
     * Installs the theme. Call once, from the launcher, after {@code Locale.setDefault} -
     * several classes bind a {@code ResourceBundle} at class-init and are touched later.
     */
    public static void install() {
        for (String key : OBSOLETE_KEYS)
            Options.remove(key);

        // Before setup, which is what FlatLaf requires. This is the seam for a dark theme
        // later: FlatLaf.properties in that package applies to every theme,
        // FlatLightLaf.properties only to this one, and adding FlatDarkLaf.properties plus a
        // branch here is then the whole job rather than a rewrite.
        FlatLaf.registerCustomDefaultsSource("com.ramussoft.gui.common.theme");

        if (FlatLightLaf.setup())
            return;

        // Not expected. But the code this replaces had exactly one failure path - a stack
        // trace, and a silent fall through to Metal - and under Metadata.DEBUG stderr is
        // redirected to a log file, so nobody would ever see it. Land on the platform's own
        // look instead, and say so somewhere visible.
        System.out.println("Could not install the application theme, "
                + "falling back to the system look and feel.");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
