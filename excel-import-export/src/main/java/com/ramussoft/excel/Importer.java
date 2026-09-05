package com.ramussoft.excel;

import info.clearthought.layout.TableLayout;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import com.ramussoft.common.Attribute;
import com.ramussoft.common.Qualifier;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.core.attribute.standard.StandardAttributesPlugin;
import com.ramussoft.database.common.RowSet;
import com.ramussoft.gui.common.BaseDialog;
import com.ramussoft.gui.common.GUIFramework;

public class Importer {

    private GUIFramework framework;

    private RowSet rowSet;

    private ArrayList<String> header = new ArrayList<String>();

    private ArrayList<JComboBox>[] boxes;

    private int sheetNumber;

    private ExcelPlugin plugin;

    private int startFrom = 1;

    private ArrayList<ImportRule> importRules = new ArrayList<ImportRule>();

    private JCheckBox applyToAll;

    private JCheckBox uniqueElement;

    public Importer(GUIFramework framework, RowSet rowSet, ExcelPlugin plugin) {
        this.framework = framework;
        this.rowSet = rowSet;
        this.plugin = plugin;
    }

    /**
     * RowSet exposes start and commit but no rollback, so this goes through Journaled the
     * way the other eight rollback sites in the project do - see the IDL import in
     * IDEF0ViewPlugin, which is the same shape as this one: a file the user chose, a format
     * that may not be the expected one, and a transaction that must not be left half applied.
     */
    private void rollback() {
        Object engine = rowSet.getEngine();
        if (engine instanceof Journaled)
            ((Journaled) engine).rollbackUserTransaction();
        else
            rowSet.commitUserTransaction();
    }

    @SuppressWarnings("unchecked")
    public void importFromFile(File file) throws IOException {
        final Workbook workbook;
        // try-with-resources: the close() used to sit after the constructor, so it was
        // skipped on exactly the input that makes the constructor throw - a file that is
        // not an .xls.
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            workbook = new HSSFWorkbook(fileInputStream);
        }
        int sheetCount = workbook.getNumberOfSheets();
        if (sheetCount == 0) {
            JOptionPane.showMessageDialog(framework.getMainFrame(), plugin
                    .getString("NoSheetsAreFound"));
            // The message used to be advice rather than a decision: the method carried on
            // and put up an import dialog with no tabs in it.
            return;
        }

        boxes = new ArrayList[sheetCount];
        for (int i = 0; i < sheetCount; i++)
            boxes[i] = new ArrayList<JComboBox>();

        final JTabbedPane pane = new JTabbedPane();

        for (int i = 0; i < sheetCount; i++) {
            sheetNumber = i;
            Sheet sheet = workbook.getSheetAt(i);
            pane.addTab(workbook.getSheetName(i), createSheetSelect(sheet));
        }

        BaseDialog dialog = new BaseDialog(framework.getMainFrame(), true) {

            /**
             *
             */
            private static final long serialVersionUID = -5962006392465638821L;

            @Override
            protected void onOk() {
                rowSet.startUserTransaction();
                try {
                    int index = pane.getSelectedIndex();
                    ArrayList<JComboBox> boxes = Importer.this.boxes[index];
                    Sheet sheet = workbook.getSheetAt(index);

                    ArrayList<ImportRule> rules = new ArrayList<ImportRule>();
                    int attr = 0;
                    for (JComboBox box : boxes) {
                        ImportRule source = importRules.get(attr);

                        int column = box.getSelectedIndex() - 2;
                        if (column >= -1) {
                            ImportRule rule = new ImportRule(source
                                    .getAttribute(),
                                    source.getTableAttribute(), column);
                            rules.add(rule);
                        }
                        attr++;
                    }
                    if (applyToAll.isSelected()) {
                        for (index = 0; index < workbook.getNumberOfSheets(); index++) {
                            try {
                                sheet = workbook.getSheetAt(index);
                                ComplexImport import1 = new ComplexImport(
                                        rowSet, uniqueElement.isSelected());
                                import1.importDromSheet(sheet, workbook
                                        .getSheetName(index), startFrom, rules
                                        .toArray(new ImportRule[rules.size()]));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    } else {
                        ComplexImport import1 = new ComplexImport(rowSet,
                                uniqueElement.isSelected());
                        import1.importDromSheet(sheet, workbook
                                .getSheetName(index), startFrom, rules
                                .toArray(new ImportRule[rules.size()]));
                    }
                    rowSet.commitUserTransaction();
                } catch (Exception e) {
                    // The commit used to be in a finally with no catch anywhere near it, so
                    // a failure part way through committed whatever rows had already been
                    // created - a half-imported catalogue, silently, in the user's model -
                    // and then threw on the event thread, where nothing reports it.
                    rollback();
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(framework.getMainFrame(), e
                            .getLocalizedMessage());
                    // Deliberately not closing: the column mapping the user just set up is
                    // in this dialog, and throwing it away on a failed attempt is unkind.
                    return;
                }
                super.onOk();
            }
        };

        dialog.setTitle(plugin.getString("Action.ImportFromExcel"));

        JPanel panel = new JPanel(new BorderLayout());

        panel.add(pane, BorderLayout.CENTER);

        applyToAll = new JCheckBox(plugin.getString("ApplyToAllSheets"));

        uniqueElement = new JCheckBox(plugin.getString("UniqueElements"));

        uniqueElement.setSelected(true);

        JPanel panel2 = new JPanel(new FlowLayout(FlowLayout.LEFT));

        panel2.add(applyToAll);
        panel2.add(uniqueElement);

        panel.add(panel2, BorderLayout.SOUTH);

        dialog.setMainPane(panel);
        dialog.pack();
        dialog.setMaximumSize(dialog.getSize());
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    private JPanel createSheetSelect(Sheet sheet) {
        double[] x = {5, TableLayout.MINIMUM, 5, TableLayout.FILL, 5};
        Qualifier qualifier = rowSet.getQualifier();
        int boxCount = 0;

        for (Attribute attribute : qualifier.getAttributes()) {
            if (attribute.getAttributeType().toString().equals("Core.Table")) {
                boxCount += StandardAttributesPlugin
                        .getTableQualifierForAttribute(rowSet.getEngine(),
                                attribute).getAttributes().size();
            } else {
                boxCount++;
            }
        }

        double[] y = new double[boxCount * 2 + 1];

        y[0] = 5;
        for (int i = 0; i < boxCount; i++) {
            y[i * 2 + 1] = TableLayout.MINIMUM;
            y[i * 2 + 2] = 5;
        }

        double[][] size = {x, y};
        TableLayout layout = new TableLayout(size);

        JPanel panel = new JPanel(layout);

        Row row = sheet.getRow(0);
        int count = 0;
        header.clear();
        if (row != null)
            while (row.getCell(count) != null) {
                String value = row.getCell(count).getStringCellValue();
                if ((value == null) || (value.equals(""))) {
                    startFrom = 2;
                }
                header.add(value);
                count++;
            }

        if (startFrom > 1) {
            Row hr = sheet.getRow(1);
            if (hr != null)
                for (int c = 0; c < count; c++) {
                    Cell cell = hr.getCell(c);
                    if (cell != null) {
                        String tmp = cell.getStringCellValue();
                        if ((tmp != null) && (!"".equals(tmp))) {
                            header.set(c, header.get(c) + " " + tmp);
                        }
                    }
                }
        }

        int i = 1;

        for (Attribute attribute : qualifier.getAttributes()) {
            if (attribute.getAttributeType().toString().equals("Core.Table")) {
                for (Attribute tableAttribute : StandardAttributesPlugin
                        .getTableQualifierForAttribute(rowSet.getEngine(),
                                attribute).getAttributes()) {
                    JLabel label = new JLabel(attribute.getName() + "."
                            + tableAttribute.getName());
                    panel.add(label, "1," + i);
                    panel.add(createCombo(), "3," + i);
                    i += 2;
                    importRules.add(new ImportRule(attribute, tableAttribute,
                            -1));
                }
            } else {
                JLabel label = new JLabel(attribute.getName());

                panel.add(label, "1," + i);
                panel.add(createCombo(), "3," + i);
                i += 2;
                importRules.add(new ImportRule(attribute, null, -1));
            }
        }

        return panel;
    }

    private JComboBox createCombo() {
        JComboBox box = new JComboBox();
        box.addItem(plugin.getString("DoNotImport"));
        box.addItem(plugin.getString("SheetName"));
        for (String s : header) {
            box.addItem(s);
        }
        boxes[sheetNumber].add(box);
        return box;
    }

}
