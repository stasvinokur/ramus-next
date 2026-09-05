package com.ramussoft.gui.core;

import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.JDialog;

import com.ramussoft.common.Engine;
import com.ramussoft.common.Plugin;
import com.ramussoft.gui.common.AbstractViewPlugin;
import com.ramussoft.gui.common.ActionDescriptor;
import com.ramussoft.gui.common.ActionLevel;
import com.ramussoft.gui.common.GUIPlugin;

/**
 * Contributes the single item left on the Help menu.
 *
 * <p>The menu used to hold "Help Contents" above it, bound to F1, which opened a JavaHelp window
 * over a help set nobody had maintained. Both the action and the {@code openHelp} method that
 * built the {@code HelpSet} are gone, and with them this module's only use of {@code javax.help}.
 */
public class AboutPlugin extends AbstractViewPlugin {

    private Engine engine;

    public AboutPlugin(Engine engine) {
        this.engine = engine;
    }

    @Override
    public String getName() {
        return "About";
    }

    @Override
    public ActionDescriptor[] getActionDescriptors() {
        ActionDescriptor about = new ActionDescriptor();

        about.setActionLevel(ActionLevel.GLOBAL);
        about.setMenu("Help");
        about.setAction(new AbstractAction() {
            /**
             *
             */
            private static final long serialVersionUID = -7354349852168425066L;

            {
                putValue(ACTION_COMMAND_KEY, "About");
            }

            @SuppressWarnings("unchecked")
            @Override
            public void actionPerformed(ActionEvent e) {
                AboutDialog dialog = new AboutDialog(framework.getMainFrame(),
                        (List<Plugin>) engine.getPluginProperty("Core",
                                "PluginList"), (List<GUIPlugin>) engine
                        .getPluginProperty("GUI", "PluginList"));
                dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
                dialog.setVisible(true);
                dialog.dispose();
            }
        });

        return new ActionDescriptor[]{about};
    }

}
