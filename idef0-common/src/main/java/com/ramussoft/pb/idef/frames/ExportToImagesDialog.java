package com.ramussoft.pb.idef.frames;

import info.clearthought.layout.TableLayout;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Label;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.dsoft.pb.idef.ResourceLoader;
import com.ramussoft.common.Metadata;
import com.ramussoft.gui.common.BaseDialog;
import com.ramussoft.gui.common.BusyDialog;
import com.ramussoft.gui.common.GlobalResourcesManager;
import com.ramussoft.gui.common.TextField;
import com.ramussoft.gui.common.prefrence.Options;
import com.ramussoft.pb.DataPlugin;
import com.ramussoft.pb.Function;
import com.ramussoft.pb.idef.visual.MovingFunction;
import com.ramussoft.pb.print.PIDEF0painter;

public class ExportToImagesDialog extends BaseDialog {

    private static final String LAST_IMG_EXPORT_DIRECTORY = "LAST_IMG_EXPORT_DIRECTORY";

    /**
     * A combo item that carries the value it stands for.
     *
     * <p>The format used to be the item's POSITION in the list: the dialog passed
     * {@code imageTypeComboBox.getSelectedIndex()} straight to
     * {@link PIDEF0painter#writeToStream} as the format constant, and that worked only
     * because .bmp/.png/.jpg/.svg/.emf happened to be listed in the same order as
     * BMP_FORMAT through EMF_FORMAT. The order was written down in three separate places -
     * the item list, the default selection, and the constants - and two lines apart the
     * same combo was read a second time, by item text, to build the file extension.
     * Inserting one item would have silently changed what every export wrote.
     */
    private static final class ImageType {

        private final String extension;

        private final int format;

        ImageType(String extension, int format) {
            this.extension = extension;
            this.format = format;
        }

        String getExtension() {
            return extension;
        }

        int getFormat() {
            return format;
        }

        @Override
        public String toString() {
            return extension;
        }
    }

    /**
     * The offered image sizes. These numbers are the ones the dialog has always actually
     * produced; the labels are now derived from them rather than typed separately, because
     * they used to disagree - the item reading 904x601 wrote an image of 905x700, which is
     * not a rounding difference but a different shape. The pixel sizes are deliberately
     * unchanged, so nobody's exports move.
     */
    private static final class ImageSize {

        private final int width;

        private final int height;

        ImageSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        Dimension toDimension() {
            return new Dimension(width, height);
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }

    private static final ImageType[] IMAGE_TYPES = {
            new ImageType(".bmp", PIDEF0painter.BMP_FORMAT),
            new ImageType(".png", PIDEF0painter.PNG_FORMAT),
            new ImageType(".jpg", PIDEF0painter.JPEG_FORMAT),
            new ImageType(".svg", PIDEF0painter.SVG_FORMAT),
            new ImageType(".emf", PIDEF0painter.EMF_FORMAT),
    };

    private static final ImageSize[] IMAGE_SIZES = {
            new ImageSize(800, 535),
            new ImageSize(905, 700),
            new ImageSize(1024, 768),
            new ImageSize(1152, 864),
            new ImageSize(1300, 1000),
            new ImageSize(1601, 1200),
    };

    private static ImageType imageType(int format) {
        for (ImageType type : IMAGE_TYPES)
            if (type.getFormat() == format)
                return type;
        throw new IllegalArgumentException("No image type for format " + format);
    }

    private DataPlugin dataPlugin;

    private TextField directory = new TextField();

    private JComboBox imageSizeComboBox;

    private JComboBox imageTypeComboBox;

    private IDEF0ChackedPanel chackedPanel;

    public ExportToImagesDialog(JFrame frame, DataPlugin dataPlugin) {
        super(frame, true);
        this.directory.setText(Options.getString(LAST_IMG_EXPORT_DIRECTORY,
                new JFileChooser().getFileSystemView().getDefaultDirectory()
                        .getAbsolutePath()));
        this.dataPlugin = dataPlugin;
        setTitle(ResourceLoader.getString("ExportToImages"));
        setMainPane(createMainPane());
        pack();
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(null);
        Options.loadOptions(this);
    }

    private JComponent createMainPane() {
        JPanel panel = new JPanel(new BorderLayout());
        chackedPanel = new IDEF0ChackedPanel();
        chackedPanel.setFunctionParents(dataPlugin);
        panel.add(chackedPanel, BorderLayout.CENTER);
        panel.add(createBottomPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private Component createBottomPanel() {
        double[][] size = {
                {5, TableLayout.MINIMUM, 5, TableLayout.FILL, 5,
                        TableLayout.MINIMUM, 5},
                {5, TableLayout.MINIMUM, 5, TableLayout.MINIMUM, 5,
                        TableLayout.MINIMUM, 5}};

        JPanel panel = new JPanel(new TableLayout(size));

        imageSizeComboBox = new JComboBox(IMAGE_SIZES);

        imageTypeComboBox = new JComboBox(IMAGE_TYPES);
        // By value, not by position: the default used to be a bare setSelectedIndex(1).
        imageTypeComboBox.setSelectedItem(imageType(PIDEF0painter.PNG_FORMAT));

        panel.add(new JLabel(ResourceLoader.getString("ImageSize")), "1,1");
        panel.add(imageSizeComboBox, "3,1,5,1");

        panel.add(new JLabel(ResourceLoader.getString("ImageType")), "1,3");
        panel.add(imageTypeComboBox, "3,3,5,3");

        panel.add(new Label(ResourceLoader.getString("Folder")), "1, 5");
        panel.add(directory, "3, 5");
        panel.add(new JButton(new AbstractAction(GlobalResourcesManager
                .getString("Action.Browse")) {

            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setSelectedFile(new File(directory.getText()));
                fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int r = fileChooser.showOpenDialog(null);
                if (r == JFileChooser.APPROVE_OPTION)
                    directory.setText(fileChooser.getSelectedFile()
                            .getAbsolutePath());
            }
        }), "5, 5");

        return panel;
    }

    @Override
    protected void onOk() {
        final File dir = new File(directory.getText());
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                JOptionPane.showMessageDialog(this, MessageFormat.format(
                        ResourceLoader.getString("FileIsNotADirectory"),
                        directory.getText()));
            } else {
                for (File file : dir.listFiles()) {
                    if (file.isFile()) {
                        if (JOptionPane.showConfirmDialog(this, ResourceLoader
                                        .getString("DirectoryIsNotEmpty"), UIManager
                                        .getString("OptionPane.titleText"),
                                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
                            return;
                        break;
                    }
                }
            }
        } else {
            if (!dir.mkdirs()) {
                JOptionPane.showMessageDialog(this, ResourceLoader
                        .getString("CanNotCreateADirectory"));
            }
        }

        final BusyDialog dialog = new BusyDialog(this, ResourceLoader.getString("ExportingBusy"));

        // Shown before the worker starts rather than from a runnable queued after it. The
        // busy dialog is modeless, so this returns immediately, and doing it here removes
        // the race that let a fast export queue its hide before this show ever ran.
        dialog.setVisible(true);

        Thread export = new Thread("Export-to-images") {
            @Override
            public void run() {
                Throwable failure = null;
                try {
                    int i = 0;
                    int[] js = chackedPanel.getSelected();
                    for (Function f : chackedPanel.getSelectedFunctions()) {
                        String prefix = Integer.toString(js[i] + 1);
                        while (prefix.length() < 2)
                            prefix = "0" + prefix;
                        exportToFile(dir, f, prefix + "_");
                        i++;
                    }
                } catch (Throwable t) {
                    // Everything, not only IOException. The whole diagram is rendered on
                    // this thread, and an unchecked throw used to kill it silently: the
                    // busy dialog was never hidden and this modal dialog was never
                    // released, which is what "the program just freezes" was.
                    failure = t;
                }

                final Throwable reported = failure;
                // Every one of these touches Swing, so none of them may run here. The old
                // code showed its message dialog straight from this thread.
                SwingUtilities.invokeLater(new Runnable() {

                    @Override
                    public void run() {
                        dialog.setVisible(false);
                        dialog.dispose();
                        if (reported == null) {
                            Options.setString(LAST_IMG_EXPORT_DIRECTORY,
                                    directory.getText());
                            ExportToImagesDialog.super.onOk();
                            return;
                        }
                        if (Metadata.DEBUG)
                            reported.printStackTrace();
                        // The export dialog is deliberately left open, as it already was
                        // for an IOException, so the selection is not lost on a retry.
                        JOptionPane.showMessageDialog(ExportToImagesDialog.this,
                                describe(reported));
                    }
                });
            }
        };
        export.start();
    }

    /**
     * An unchecked failure often carries no message at all - a NullPointerException's is
     * null - and a dialog reading "null" is exactly the kind of thing this change exists
     * to stop. Fall back to the exception's own description so there is always something
     * to report, however technical.
     */
    private static String describe(Throwable t) {
        String message = t.getLocalizedMessage();
        if ((message != null) && (message.trim().length() > 0))
            return message;
        return t.toString();
    }

    protected void exportToFile(File dir, Function f, String prefix)
            throws FileNotFoundException, IOException {
        // One read of each combo, and the extension and the format now come from the same
        // object rather than from the item text and the item position respectively.
        ImageSize size = (ImageSize) imageSizeComboBox.getSelectedItem();
        ImageType type = (ImageType) imageTypeComboBox.getSelectedItem();

        PIDEF0painter painter = new PIDEF0painter(f, size.toDimension(),
                dataPlugin);
        File file = new File(dir, prefix + MovingFunction.getIDEF0Kod((com.ramussoft.database.common.Row) f)
                + type.getExtension());
        writeAtomically(file, stream -> painter.writeToStream(stream, type.getFormat()));
    }

    /**
     * Something that renders into a stream. It may fail in any way at all, which is the
     * whole reason this exists.
     */
    interface StreamWriter {

        void writeTo(OutputStream stream) throws IOException;
    }

    /**
     * Renders into a neighbouring temporary file and only takes the target name once the
     * write has finished.
     *
     * <p>
     * Upstream issue #24 is reported as "the first picture cannot be opened". The stream
     * used to be opened directly on the target before anything was drawn, while the actual
     * encode is the last statement of {@code PIDEF0painter.writeImage} - so any failure
     * while painting left a zero-byte file carrying a valid image extension. A file that
     * does not exist is a far better outcome than a file that looks like an image and is
     * not, because the first is visible and the second is not.
     *
     * <p>
     * Package-private so it can be tested without a dialog, a model or a graphics context.
     */
    static void writeAtomically(File target, StreamWriter writer) throws IOException {
        File directory = target.getParentFile();
        File partial = File.createTempFile(target.getName(), ".part", directory);
        boolean written = false;
        try {
            try (FileOutputStream stream = new FileOutputStream(partial)) {
                writer.writeTo(stream);
            }
            written = true;
        } finally {
            if (!written)
                partial.delete();
        }
        if (target.exists() && !target.delete()) {
            partial.delete();
            throw new IOException("Cannot replace " + target.getAbsolutePath());
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IOException("Cannot write " + target.getAbsolutePath());
        }
    }

    @Override
    public void setVisible(boolean b) {
        super.setVisible(b);
        if (b)
            Options.saveOptions(this);
    }

}
