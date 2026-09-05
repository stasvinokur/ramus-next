package com.ramussoft.gui.core;

import java.awt.event.ActionEvent;

import javax.swing.AbstractAction;

import com.ramussoft.gui.common.AbstractViewPlugin;
import com.ramussoft.gui.common.ActionDescriptor;
import com.ramussoft.gui.common.ActionLevel;

/**
 * Contributes the single item left on the Help menu.
 *
 * <p>The menu used to hold "Help Contents" above it, bound to F1, which opened a JavaHelp window
 * over a help set nobody had maintained. That went, and with it this class's reason to hold an
 * Engine: the only thing it was ever used for was fetching the two plugin lists that filled the
 * About dialog's tables, and the dialog no longer has tables.
 */
public class AboutPlugin extends AbstractViewPlugin {

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

            @Override
            public void actionPerformed(ActionEvent e) {
                AboutDialog dialog = new AboutDialog(framework.getMainFrame());
                dialog.setVisible(true);
                dialog.dispose();
            }
        });

        return new ActionDescriptor[]{about};
    }

}
