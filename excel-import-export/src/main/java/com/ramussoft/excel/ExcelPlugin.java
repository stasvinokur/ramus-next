package com.ramussoft.excel;

import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.util.ResourceBundle;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;

import org.apache.poi.UnsupportedFileFormatException;

import com.ramussoft.common.Qualifier;
import com.ramussoft.gui.common.GlobalResourcesManager;
import com.ramussoft.gui.qualifier.table.AbstractElementActionPlugin;
import com.ramussoft.gui.qualifier.table.TableTabView;
import com.ramussoft.gui.qualifier.table.TableView;
import com.ramussoft.gui.common.Icons;

public class ExcelPlugin extends AbstractElementActionPlugin {

    private ResourceBundle bundle = ResourceBundle
            .getBundle("com.ramussoft.excel.labels");

    @Override
    public Action[] getActions(TableTabView tableView) {
        ExportToExcelAction exportToAxcelAction = new ExportToExcelAction(
                tableView);

        ImportFromExcelAction importFromExcelAction = new ImportFromExcelAction(
                tableView);
        return new Action[]{exportToAxcelAction, importFromExcelAction};
    }

    @Override
    public String getName() {
        return "ExcelPlugin";
    }

    @Override
    public String getString(String key) {
        try {
            return bundle.getString(key);
        } catch (Exception e) {
        }
        return super.getString(key);
    }

    public class ExportToExcelAction extends AbstractAction {

        /**
         *
         */
        private static final long serialVersionUID = 5785151469344226760L;

        private TableView tableView;

        public ExportToExcelAction(TableView tableView) {
            this.tableView = tableView;
            putValue(ACTION_COMMAND_KEY, "Action.ExportToExcel");
            putValue(ACTION_STRING_GETTER, ExcelPlugin.this);
            putValue(SMALL_ICON, Icons.get("/com/ramussoft/excel/export-to-excel.png"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JFileChooser chooser = new JFileChooser() {
                /**
                 *
                 */
                private static final long serialVersionUID = 7734252294227238804L;

                @Override
                public void approveSelection() {
                    if (getSelectedFile().exists()) {
                        if (JOptionPane.showConfirmDialog(framework
                                        .getMainFrame(), GlobalResourcesManager
                                        .getString("File.Exists"), UIManager
                                        .getString("OptionPane.messageDialogTitle"),
                                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                            return;
                    }
                    super.approveSelection();
                }
            };
            int r = chooser.showSaveDialog(framework.getMainFrame());
            if (r == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (!file.getName().toLowerCase().endsWith(".xls")) {
                    file = new File(file.getAbsolutePath() + ".xls");
                }
                Exporter exporter = new Exporter(tableView);
                Qualifier qualifier = tableView.getQualifier();
                try {
                    exporter.exportToFile(qualifier.getAttributes(), qualifier,
                            file);
                } catch (IOException e1) {
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(framework.getMainFrame(), e1
                            .getLocalizedMessage());
                }
            }

        }
    }

    ;

    public class ImportFromExcelAction extends AbstractAction {

        /**
         *
         */
        private static final long serialVersionUID = -1063906986487940462L;

        private TableView tableView;

        public ImportFromExcelAction(TableView tableView) {
            this.tableView = tableView;
            putValue(ACTION_COMMAND_KEY, "Action.ImportFromExcel");
            putValue(ACTION_STRING_GETTER, ExcelPlugin.this);
            putValue(SMALL_ICON, Icons.get("/com/ramussoft/excel/import-from-excel.png"));
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileFilter() {

                @Override
                public boolean accept(File f) {
                    if (f.isFile()) {
                        return f.getName().toLowerCase().endsWith(".xls");
                    }
                    return true;
                }

                @Override
                public String getDescription() {
                    return "*.xls";
                }

            });
            int r = chooser.showOpenDialog(framework.getMainFrame());
            if (r == JFileChooser.APPROVE_OPTION) {
                Importer importer = new Importer(framework, tableView
                        .getComponent().getRowSet(), ExcelPlugin.this);
                try {
                    importer.importFromFile(chooser.getSelectedFile());
                } catch (UnsupportedFileFormatException e1) {
                    // The one failure a user can actually cause and act on. POI raises it
                    // for an .xlsx, which the chooser does not hide: the *.xls filter is
                    // there, but "All files" stays selectable. It is an
                    // IllegalArgumentException underneath, so the IOException catch never
                    // saw it - the import died on the event thread, and since this
                    // application installs no uncaught-exception handler, a packaged build
                    // showed the user nothing whatsoever.
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(framework.getMainFrame(),
                            getString("UnsupportedFileFormat"));
                } catch (Exception e1) {
                    // Everything else, by the idiom the rest of the project uses. Not
                    // IOException alone: whatever POI throws about a malformed file, the
                    // user has to hear about it rather than read stderr.
                    e1.printStackTrace();
                    JOptionPane.showMessageDialog(framework.getMainFrame(), e1
                            .getLocalizedMessage());
                }
            }
        }

    }
}
