package com.ramussoft.gui.core;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.text.MessageFormat;

import javax.swing.AbstractAction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import com.ramussoft.common.Metadata;
import com.ramussoft.gui.common.GlobalResourcesManager;
import com.ramussoft.gui.common.Icons;

/**
 * The About panel: an icon, a name, a version and a few lines of attribution.
 *
 * <p>It used to be a 600x380 window with four tabs - an HTML pane wired to the system browser,
 * two tables listing plugin names, and a dump of libraries.txt - behind an OK button. None of
 * that answers the question an About box is asked, which is what this is and what version.
 *
 * <p>The third-party notices are no longer shown. {@code libraries.txt} still ships inside the
 * jar, because the MIT licence asks that the notice travel with the software, not that it be
 * displayed; there is a comment at the top of that file saying so.
 */
public class AboutDialog extends JDialog {

    /**
     *
     */
    private static final long serialVersionUID = 2259997170092758726L;

    /** Logical size of the icon. The bitmap is 256, so a 2x display gets a real pixel each. */
    private static final int ICON = 128;

    public AboutDialog(JFrame owner) {
        // Modal, and it has to stay that way: both call sites dispose() straight after
        // setVisible(true). Non-modal, setVisible would return at once and the panel would be
        // torn down in the same frame - a window that flashes and vanishes, with no exception
        // anywhere to say why.
        super(owner, true);
        setTitle(MessageFormat.format(
                GlobalResourcesManager.getString("About.Title"),
                Metadata.getApplicationName()));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(UIScale.scale(28), UIScale.scale(40),
                UIScale.scale(24), UIScale.scale(40)));

        addIcon(content, appIcon());
        strut(content, 14);
        add(content, Metadata.getApplicationName(), "h2", false);
        strut(content, 4);
        add(content, MessageFormat.format(
                GlobalResourcesManager.getString("About.Version"),
                Metadata.getApplicationVersion()), "small", true);
        strut(content, 18);
        add(content, "Copyright \u00A9 2026 Stanislav Vinokur", "small", false);
        add(content, "Original Ramus \u00A9 2005\u20132025", "small", false);
        add(content, "Vitaliy Yakovchuk, Oleksiy Chizhevskiy", "small", false);
        add(content, "macOS version modifications by Vladislav Pavlik", "small", false);
        strut(content, 14);
        add(content, "GNU General Public License, version 3", "small", true);
        add(content, "github.com/stasvinokur/ramus-next", "small", true);

        setContentPane(content);
        closeOn(content);

        // pack() and nothing else. The old class set a flat 600x380 and then made the window
        // non-resizable, so a font any larger than the 2009 default had nowhere to go. Sized
        // by its contents, the panel simply grows with the theme font instead.
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /**
     * Escape, Enter, the platform's close shortcut, and a click anywhere on the panel. The
     * bindings are WHEN_IN_FOCUSED_WINDOW because nothing in here is focusable - it is all
     * labels - so a WHEN_FOCUSED binding would never fire.
     */
    private void closeOn(JPanel content) {
        AbstractAction close = new AbstractAction() {
            /**
             *
             */
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        };
        JRootPane root = getRootPane();
        root.getActionMap().put("close", close);
        InputMap keys = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "close");
        keys.put(KeyStroke.getKeyStroke(KeyEvent.VK_W,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "close");
        content.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dispose();
            }
        });
    }

    /**
     * @param dimmed draws the line in the theme's disabled colour, the way a macOS About panel
     *               separates the headline from everything under it.
     */
    private void add(JPanel content, String text, String styleClass, boolean dimmed) {
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        // Named style classes rather than new Font(...): the font then follows the theme, and
        // a family name that does not resolve cannot silently fall back to Dialog.
        label.putClientProperty(FlatClientProperties.STYLE_CLASS, styleClass);
        if (dimmed)
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
        content.add(label);
    }

    private void addIcon(JPanel content, Icon icon) {
        if (icon == null)
            return;
        JLabel label = new JLabel(icon);
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        content.add(label);
    }

    private void strut(JPanel content, int height) {
        content.add(Box.createVerticalStrut(UIScale.scale(height)));
    }

    /**
     * The application icon at {@link #ICON} points, with a 2x variant so it stays sharp on a
     * high-resolution display - an ImageIcon scaled by the compositor would be visibly soft.
     * Both variants are rendered into a BufferedImage rather than through getScaledInstance,
     * whose lazily-sized result can report a width of -1 while the multi-resolution image is
     * choosing between them.
     */
    private Icon appIcon() {
        ImageIcon source = Icons.image("/com/ramussoft/gui/app-icon.png");
        if (source == null)
            return null;
        Image image = source.getImage();
        return new ImageIcon(new BaseMultiResolutionImage(
                render(image, ICON), render(image, ICON * 2)));
    }

    private static BufferedImage render(Image source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
