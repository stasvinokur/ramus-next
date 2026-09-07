package com.ramussoft.local;

import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Label;
import java.awt.Window;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipException;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;


import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Metadata;
import com.ramussoft.common.PluginFactory;
import com.ramussoft.common.PluginProvider;
import com.ramussoft.common.journal.DirectoryJournalFactory;
import com.ramussoft.common.journal.Journal;
import com.ramussoft.common.journal.Journaled;
import com.ramussoft.common.journal.StopUndoPointCommand;
import com.ramussoft.common.journal.command.Command;
import com.ramussoft.common.journal.command.EndUserTransactionCommand;
import com.ramussoft.common.journal.command.StartUserTransactionCommand;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.core.impl.FileMinimumVersionException;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.gui.common.AbstractGUIPluginFactory;
import com.ramussoft.gui.common.GUIFramework;
import com.ramussoft.gui.common.GUIPlugin;
import com.ramussoft.gui.common.GlobalResourcesManager;
import com.ramussoft.gui.common.SplashScreen;
import com.ramussoft.gui.common.UndoRedoPlugin;
import com.ramussoft.gui.common.event.ActionEvent;
import com.ramussoft.gui.common.event.CloseMainFrameAdapter;
import com.ramussoft.gui.common.prefrence.Options;
import com.ramussoft.gui.common.theme.AppTheme;
import com.ramussoft.gui.core.GUIPluginFactory;
import com.ramussoft.gui.core.simple.SimleGUIPluginFactory;
import com.ramussoft.gui.qualifier.QualifierPluginSuit;

public class Runner implements Commands {

    private static final String CORE = "Core";

    public static void main(String[] args) {
        new Runner().load(args);
    }

    /**
     * @param args
     */
    public void load(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--hide-splash")) {
                Metadata.HIDE_SPLASH = true;
                String[] strings = new String[args.length - 1];
                int k = 0;
                for (int j = 0; j < args.length; j++) {
                    if (j != i) {
                        strings[k] = args[j];
                        k++;
                    }
                }
                args = strings;
                break;
            }
        }

        try {

            try {

                final DesktopComunication comunication = createDesktopComunication();

                if (comunication.isClient()) {
                    comunication.send(args);
                    System.exit(0);
                    return;
                } else {
                    Thread hook = new Thread() {
                        @Override
                        public void run() {
                            try {
                                comunication.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    };
                    Runtime.getRuntime().addShutdownHook(hook);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (Metadata.DEBUG) {

                final PrintStream old = System.err;
                System.setErr(new PrintStream(new OutputStream() {

                    FileOutputStream fos = null;

                    private boolean err = false;

                    @Override
                    public void write(final int b) throws IOException {
                        getFos();
                        if (!err)
                            fos.write(b);
                        old.write(b);
                    }

                    private FileOutputStream getFos() throws IOException {
                        if (fos == null) {
                            try {
                                final Calendar c = Calendar.getInstance();
                                String name = Options.getPreferencesPath()
                                        + "log";
                                new File(name).mkdir();
                                name += File.separator + c.get(Calendar.YEAR)
                                        + "_" + c.get(Calendar.MONTH) + "_"
                                        + c.get(Calendar.DAY_OF_MONTH) + "_"
                                        + c.get(Calendar.HOUR_OF_DAY) + "_"
                                        + c.get(Calendar.MINUTE) + "_"
                                        + c.get(Calendar.SECOND) + "_"
                                        + c.get(Calendar.MILLISECOND) + ".log";
                                fos = new FileOutputStream(name);
                            } catch (final IOException e) {
                                err = true;
                                e.printStackTrace();
                            }
                        }
                        return fos;
                    }

                }));

            }

            try {
                // Russian is the product's own language, so it is the default even where the
                // operating system says otherwise. The two-argument form writes its default
                // back into options.conf on first read, which is the point: it stops "never
                // chose a language" and "chose Russian" being the same state on disk, so
                // changing this default later cannot move the language under an existing user.
                String lang = Options.getString("LANG", "ru");

                try {
                    Locale.setDefault(new Locale(lang));
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // After the locale: the look and feel replaces the whole UIDefaults table,
                // so anything locale-dependent that writes into it has to come later, not
                // earlier. See the note on ResourceLoader below.
                AppTheme.install();
            } catch (Exception e1) {
                e1.printStackTrace();
            }

            start(args);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    protected DesktopComunication createDesktopComunication()
            throws IOException {
        final DesktopComunication comunication = new DesktopComunication() {
            @Override
            public void applyArgs(String[] args) {
                recoveredCount = 0;
                run(args);
            }
        };
        return comunication;
    }

    protected int recoveredCount = 0;

    private void recoveryFiles() {
        String path = FileIEngineImpl.getSessionsPath();
        File file = new File(path);
        if (file.exists())
            for (File session : file.listFiles()) {
                try {
                    String lockName = session + File.separator + ".lock";
                    if (new File(lockName).exists()) {
                        RandomAccessFile rf = new RandomAccessFile(lockName,
                                "rw");
                        FileChannel channel = rf.getChannel();
                        FileLock lock = channel.tryLock();
                        if (lock != null) {
                            lock.release();
                            rf.close();
                            // Read whole. available() is not a length and the old code threw
                            // away what read() returned, so a short read left the tail of the
                            // array as zero bytes INSIDE the recovered file name.
                            byte[] bs = Files.readAllBytes(new File(lockName).toPath());
                            String fileName = bs.length > 0
                                    ? new String(bs, StandardCharsets.UTF_8) : null;
                            File sourceFile = (fileName == null) ? null
                                    : new File(fileName);
                            try {
                                if (!recoverySession(session.getAbsolutePath(),
                                        sourceFile))
                                    clear(session);
                            } catch (Exception e) {
                                clear(session);
                            }
                        }
                        rf.close();
                    } else {
                        clear(session);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
    }

    private void clear(File session) {
        FileIEngineImpl.deleteRec(session);
    }

    private void start(String[] args) {
        if (args.length > 0) {
            for (String arg : args)
                if (arg.equals("--close")) {
                    System.exit(0);
                    return;
                }
        }
        recoveryFiles();
        run(args);
    }

    protected void run(String[] args) {
        if (Metadata.DEMO) {
            String regVer = Options.getString("REGISTERED_VERSION");
            if (regVer == null)
                Metadata.DEMO_REGISTERED = false;
            else {
                Metadata.DEMO_REGISTERED = Boolean.valueOf(regVer);
            }
            Metadata.REGISTERED_FOR = Options.getString("REG_NAME");
        }

        if (args.length > 0) {
            for (String arg : args)
                if (arg.equals("--close")) {
                    System.exit(0);
                    return;
                }
            File file = null;
            try {
                URI uri = new URI(args[0]);
                File f = new File(uri);
                if (f.exists())
                    file = f;
            } catch (Exception e) {
            }

            if (file == null)
                file = new File(args[0]);
            open(file);
        } else {
            if (recoveredCount == 0 && !isDocumentOpenPending()) {
                startupLauncher(false);
            }
        }
    }

    /**
     * Whether a document is already on its way to a window, so the startup launcher must
     * not be shown.
     *
     * <p>
     * Windows and Linux hand a double-clicked model to {@code main} as {@code args[0]}, and
     * the decision above is then complete on its own. macOS does not: Finder sends an Apple
     * event, so this process is started with no arguments at all and learns which document
     * was wanted through a callback. Deciding from the arguments alone put the launcher on
     * screen every single time somebody opened a model from Finder.
     *
     * <p>
     * Answering this question is platform work and belongs to whoever installed the handler,
     * which is why it is a seam rather than a check. The default is the honest answer
     * everywhere the question does not arise.
     */
    protected boolean isDocumentOpenPending() {
        return false;
    }

    /**
     * Offers the choice between a new model and an existing one - or, when the user has said
     * not to ask, makes that choice for them.
     *
     * <p>
     * On the event thread, because it builds and shows a window. It used to be called
     * straight from the startup thread, which is a Swing violation that had simply not bitten
     * yet.
     *
     * @param showAnyway show the window even when the user asked not to be asked. Used when
     *                   there is nothing else on screen and no other way to get a window.
     */
    public void startupLauncher(final boolean showAnyway) {
        Runnable show = new Runnable() {

            @Override
            public void run() {
                FirstSwitchFrame frame = new FirstSwitchFrame() {
                    /**
                     *
                     */
                    private static final long serialVersionUID = -7348079857187669414L;

                    @Override
                    public void setVisible(boolean b) {
                        super.setVisible(b);
                        if (!b) {
                            if (isOk()) {
                                Thread thread = new Thread() {
                                    public void run() {
                                        open(getFile());
                                    }

                                    ;
                                };
                                thread.start();
                            }
                            // Hidden is not gone: a realized window keeps its peer and goes
                            // on taking part in the modal-blocking calculation of every
                            // later dialog.
                            dispose();
                        }
                    }
                };
                if (!showAnyway && frame.isDoNotShow()) {
                    // The user told us not to ask, so act on what they chose last time
                    // rather than put the question up again. This window must still end up
                    // either shown or disposed - pack() in the constructor has already
                    // realized it, and a realized window goes on taking part in the
                    // modal-blocking calculation of every later dialog. Both ways out of
                    // ok() do that: it ends in setVisible(false), which the override above
                    // turns into open-and-dispose, or - when the remembered file has since
                    // been deleted - it makes the window visible so the user can answer
                    // after all.
                    frame.ok();
                    return;
                }
                frame.setVisible(true);
            }
        };
        if (SwingUtilities.isEventDispatchThread())
            show.run();
        else
            SwingUtilities.invokeLater(show);
    }

    private void openFile(File file) {
        saveFileToHistory(file);

        MemoryDatabase database = createDatabase(file);
        Engine engine = database.getEngine(null);

        AccessRules accessor = database.getAccessRules(null);

        openInNewWindows(engine, accessor, file, false);
    }

    public static void saveFileToHistory(File file) {
        if (file != null) {
            ArrayList<String> files = new ArrayList<String>();
            files.add(file.getAbsolutePath());
            String[] lasts = getLastOpenedFiles();
            int count = 0;
            for (String last : lasts) {
                if (count > 10)
                    break;
                if (files.indexOf(last) < 0)
                    files.add(last);
                count++;
            }

            setLastOpenedFiles(files.toArray(new String[files.size()]));
            Options.setString("LAST_FILE", file.getAbsolutePath());
            Options.setString("LAST_FILE_FIRST", file.getAbsolutePath());
        }
    }

    static String[] getLastOpenedFiles() {
        try {
            int count = Options.getInteger("LastFileCount", 0);
            String[] res = new String[count];
            for (int i = 0; i < count; i++) {
                res[i] = Options.getString("LastFile_" + i);
            }
            return res;
        } catch (Exception e) {
            e.printStackTrace();
            return new String[]{};
        }
    }

    static void setLastOpenedFiles(String[] files) {
        Options.setInteger("LastFileCount", files.length);
        for (int i = 0; i < files.length; i++) {
            Options.setString("LastFile_" + i, files[i]);
        }
    }

    public MemoryDatabase createDatabase(final File file) {
        MemoryDatabase database = new MemoryDatabase(true) {
            @Override
            protected Collection<? extends PluginProvider> getAdditionalSuits() {
                ArrayList<PluginProvider> ps = new ArrayList<PluginProvider>(1);
                initAdditionalPluginSuits(ps);
                return ps;
            }

            @Override
            protected File getFile() {
                return file;
            }

        };
        return database;
    }

    /**
     * Serialises {@link #open(File)}.
     *
     * <p>
     * That method is a check-then-act: it scans FilePlugin.plugins for a window already
     * showing this file, and only if there is none does it load it. The registration that
     * would make the scan succeed happens inside the load, behind reading the entire model
     * from disk - seconds, for a real one. Two threads entering in that gap both find
     * nothing and both load, and the result is two main frames with two independent
     * sessions over one .rsf, where whichever is saved last silently discards the other's
     * edits. Nothing downstream catches it: a session locks its own directory, never the
     * model file.
     *
     * <p>
     * There is more than one way in. The command line opens on the main thread, the startup
     * launcher opens on a thread of its own, and macOS can deliver an open-file event at any
     * moment. Holding the lock across the whole method is what makes the check and the
     * registration atomic between all of them. Nothing on this path calls invokeAndWait, so
     * the lock cannot invert against the event thread.
     */
    private static final Object OPEN_LOCK = new Object();

    public boolean open(File afile) {

        Exception failure = null;
        boolean opened = false;

        synchronized (OPEN_LOCK) {

            JFrame frame = null;

            if (afile != null)

                for (FilePlugin plugin : FilePlugin.plugins) {
                    if ((plugin.getFile() != null)
                            && (plugin.getFile().equals(afile))) {
                        frame = plugin.getFramework().getMainFrame();
                        break;
                    }
                }

            if (frame != null) {
                frame.setVisible(true);
                return false;
            }

            SplashScreen screen = null;
            if (FilePlugin.plugins.size() < 1 && !Metadata.HIDE_SPLASH) {
                screen = new SplashScreen() {
                    /**
                     *
                     */
                    private static final long serialVersionUID = -8194442573188103621L;

                    @Override
                    protected String getImageName() {
                        return Runner.this.getSplashImageName();
                    }
                };
                screen.setLocationRelativeTo(null);
                screen.setVisible(true);
            }
            try {
                openFile(afile);
                opened = true;
            } catch (Exception e) {
                e.printStackTrace();
                failure = e;
            } finally {
                if (screen != null)
                    screen.setVisible(false);
            }
        }

        // Reported outside the lock, deliberately. showMessageDialog does not return until
        // the user dismisses it, so telling them about one unreadable file while still
        // holding the lock would stop every other window from opening anything until they
        // noticed the dialog.
        if (failure != null) {
            if (failure instanceof FileMinimumVersionException) {
                JOptionPane
                        .showMessageDialog(
                                null,
                                MessageFormat.format(
                                        GlobalResourcesManager
                                                .getString("MinimumApplicationVersionToOpenFile"),
                                        ((FileMinimumVersionException) failure)
                                                .getMinimumVersion()));
            } else
                JOptionPane.showMessageDialog(null, failure.getLocalizedMessage());
        }

        return opened;
    }

    protected String getSplashImageName() {
        return "/com/ramussoft/gui/about.png";
    }

    @SuppressWarnings("unchecked")
    public JFrame openInNewWindows(final Engine engine,
                                   final AccessRules rules, final File file, final boolean recovered) {
        List<GUIPlugin> list = new ArrayList<GUIPlugin>();
        FilePlugin filePlugin = new FilePlugin(
                (FileIEngineImpl) engine.getDeligate(), engine, rules, file,
                this);
        list.add(filePlugin);
        list.add(new UndoRedoPlugin(engine));
        initAdditionalGUIPlugins(list, engine, rules);
        final AbstractGUIPluginFactory factory = createGUIPluginFactory(engine,
                rules, list);
        final JFrame frame = factory.getMainFrame();
        engine.setPluginProperty(CORE, "MainFrame", frame);
        String title = getApplicationTitle();
        if (file != null)
            title += " - " + file.getName();
        frame.setTitle(title);
        factory.getFramework().addCloseMainFrameListener(
                new CloseMainFrameAdapter() {
                    @Override
                    public void afterClosed() {

                        List<JFrame> list = (List<JFrame>) engine
                                .getPluginProperty(CORE, "AdditionalWindows");
                        if (list != null) {
                            JFrame[] frames = list.toArray(new JFrame[list
                                    .size()]);
                            for (JFrame frame : frames) {
                                frame.setVisible(false);
                                frame.dispose();
                            }
                        }

                        FileIEngineImpl impl = (FileIEngineImpl) engine
                                .getDeligate();
                        try {
                            impl.getTemplate().getConnection().close();
                            if (engine instanceof Journaled)
                                ((Journaled) engine).close();
                            impl.close();
                            impl.clear();
                        } catch (Exception e1) {
                            e1.printStackTrace();
                        }
                    }
                });

        Object changed = engine.getPluginProperty(CORE, "Changed");
        if (changed != null)
            filePlugin.changed();

        InputStream is = engine.getInputStream("/user/gui/session.binary");

        if (is != null) {
            try {
                ObjectInputStream ois = new ObjectInputStream(is);
                try {
                    final List<ActionEvent> session = (List<ActionEvent>) ois
                            .readObject();
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            for (ActionEvent e : session)
                                if (e != null)
                                    factory.getFramework().propertyChanged(e);
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    is.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        beforeMainFrameShow(frame);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                frame.setVisible(true);
                if (recovered) {
                    factory.getFramework().propertyChanged("FileRecovered");
                } else
                    factory.getFramework().propertyChanged("FileOpened", file);

                postShowVisibaleMainFrame(engine, factory.getFramework());

            }
        });

        return frame;
    }

    private AbstractGUIPluginFactory createGUIPluginFactory(
            final Engine engine, final AccessRules rules, List<GUIPlugin> list) {
        AbstractGUIPluginFactory factory;
        String ws = Options.getString("WindowsControl", "classic");
        if (ws.equals("simple"))
            factory = new SimleGUIPluginFactory(list, engine, rules, null, null);
        else
            factory = new GUIPluginFactory(list, engine, rules, null, null);
        return factory;
    }

    protected void beforeMainFrameShow(JFrame frame) {
    }

    public static String getApplicationTitle() {
        String title = Metadata.getApplicationName();

        return title;
    }

    @SuppressWarnings("unchecked")
    public void openInNewWindows(final Engine engine, AccessRules rules) {
        List<GUIPlugin> list = new ArrayList<GUIPlugin>();
        initAdditionalGUIPlugins(list, engine, rules);
        list.add(new UndoRedoPlugin(engine));
        final AbstractGUIPluginFactory factory = createGUIPluginFactory(engine,
                rules, list);
        final JFrame frame = factory.getMainFrame();

        List<JFrame> frames = (List<JFrame>) engine.getPluginProperty(CORE,
                "AdditionalWindows");
        if (frames == null) {
            frames = new ArrayList<JFrame>();
            engine.setPluginProperty(CORE, "AdditionalWindows", frames);
        }
        frames.add(frame);

        final JFrame mainFrame = (JFrame) engine.getPluginProperty(CORE,
                "MainFrame");
        frame.setTitle("[" + mainFrame.getTitle() + "]");
        final PropertyChangeListener titleListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                frame.setTitle("[" + (String) evt.getNewValue() + "]");
            }
        };

        factory.getFramework().addCloseMainFrameListener(
                new CloseMainFrameAdapter() {
                    @Override
                    public void afterClosed() {
                        mainFrame.removePropertyChangeListener("title",
                                titleListener);
                        List<JFrame> list = (List<JFrame>) engine
                                .getPluginProperty(CORE, "AdditionalWindows");
                        list.remove(frame);
                    }
                });

        mainFrame.addPropertyChangeListener("title", titleListener);

        beforeMainFrameShow(frame);

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                frame.setVisible(true);
                postShowVisibaleMainFrame(engine, factory.getFramework());
            }
        });
    }

    protected void postShowVisibaleMainFrame(Engine engine,
                                             GUIFramework framework) {
        JFrame frame = framework.getMainFrame();
        framework.propertyChanged("MainFrameShown");
        for (String name : engine.getStreamNames()) {
            if (name.startsWith("/script/")) {
                if (JOptionPane.showConfirmDialog(frame,
                        GlobalResourcesManager.getString("Scripts.Warning"),
                        UIManager.getString("OptionPane.titleText"),
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                    engine.setPluginProperty("Scripting", "Disable",
                            Boolean.TRUE);
                }
                break;
            }
        }
    }

    /**
     * Recovers a session left behind, and says whether there was anything in it.
     *
     * <p>
     * The indicator is raised only once the answer to that is yes. Most abandoned sessions
     * hold nothing at all - a model opened and closed leaves the directory behind, and so does
     * every process killed before it could tidy up - and those are deleted without a word by
     * the caller. Showing the window first meant that a person double-clicking one model was
     * told a different model was being restored, twice over, for sessions that were about to
     * be thrown away.
     */
    public boolean recoverySession(final String sessionPath,
                                   final File sourceFile) {
        final Window[] indicator = new Window[1];
        try {
            return recoverSession(sessionPath, sourceFile, new Runnable() {
                @Override
                public void run() {
                    indicator[0] = showRecoveryProgress(sourceFile);
                }
            });
        } finally {
            closeRecoveryProgress(indicator[0]);
        }
    }

    /**
     * The "restoring session" indicator: a bare AWT window, centred on the screen.
     *
     * <p>It used to be opened at the top of the recovery and closed - sometimes - far below.
     * Three of the four ways out left it on screen: no journals, nothing to restore, and any
     * exception. The last is not hypothetical. Opening the database of a half-written session
     * throws, and a half-written session is the normal outcome of the crash this feature
     * exists to recover from.
     *
     * <p>What that cost was not obvious from here. The window is about 292x16 at the centre
     * of the screen, and the project wizard that opens straight afterwards is centred too, so
     * the leftover sat precisely over the wizard's three text fields and stopped short of its
     * buttons. A click on those fields went to the stranded window and never arrived, while
     * Tab and typing - which go to the focus owner, not to a screen position - kept working.
     * It let go when the user switched applications, because that re-orders the windows.
     */
    /** Overridden by a test to see whether it is raised at all. */
    protected Window showRecoveryProgress(final File sourceFile) {
        if (GraphicsEnvironment.isHeadless())
            return null;
        Window rFrame = new Window((Frame) null);

        String recovering = GlobalResourcesManager.getString("File.Recovering");

        rFrame.add(new Label(MessageFormat.format(
                recovering,
                ((sourceFile == null) ? GlobalResourcesManager
                        .getString("Session.NoName") : sourceFile.getName()))));
        rFrame.pack();
        rFrame.setLocationRelativeTo(null);
        rFrame.setVisible(true);
        return rFrame;
    }

    /**
     * Disposed, not hidden. {@code Window.addNotify} adds a realized window to a static list
     * that only {@code dispose} takes it out of, so a merely hidden one keeps its native peer
     * and goes on taking part in the modal-blocking calculation of every dialog opened for the
     * rest of the session.
     */
    private void closeRecoveryProgress(final Window rFrame) {
        if (rFrame != null)
            rFrame.dispose();
    }

    /**
     * @param found run once it is known the session holds something to restore, and not at
     *              all otherwise. Everything before that point is silent by design.
     */
    private boolean recoverSession(final String sessionPath,
                                   final File sourceFile, final Runnable found) {
        final String s = sessionPath + File.separator + "source.rms";

        MemoryDatabase database = new MemoryDatabase() {
            @Override
            protected Collection<? extends PluginProvider> getAdditionalSuits() {
                ArrayList<PluginProvider> ps = new ArrayList<PluginProvider>(1);
                initAdditionalPluginSuits(ps);
                return ps;
            }

            @Override
            protected File getFile() {
                File file = new File(s);
                if (file.exists())
                    return file;
                return null;
            }

            protected FileIEngineImpl createFileIEngine(PluginFactory factory)
                    throws ClassNotFoundException, ZipException, IOException {
                return new FileIEngineImpl(0, template, factory, sessionPath);
            }

        };

        final Engine engine = database.getEngine(null);

        DirectoryJournalFactory factory = database.getJournalFactory();
        Journal[] journals = factory
                .loadJournals(database.getJournaledEngine());
        if (journals.length == 0)
            return false;
        boolean exist = false;
        Journal.RedoCallback redoCallback = new Journal.RedoCallback() {

            boolean hadStartUserTransaction;

            @Override
            public boolean execute(Command command) {
                if (command instanceof StartUserTransactionCommand) {
                    hadStartUserTransaction = true;
                }
                return hadStartUserTransaction;
            }
        };
        for (Journal journal : journals) {
            try {
                Command command = null;
                while (journal.canRedo()) {
                    command = journal.redo(redoCallback);
                }
                if ((journal.getPointer() == 0l)
                        || (command instanceof StopUndoPointCommand)) {
                    continue;
                } else
                    exist = true;
                if (!(command instanceof EndUserTransactionCommand))
                    throw new Exception();
            } catch (Exception e) {
                e.printStackTrace();
                while (journal.canUndo()) {
                    if (journal.undo() instanceof StartUserTransactionCommand)
                        break;
                }
            }
        }
        if (!exist) {
            try {
                ((FileIEngineImpl) engine.getDeligate()).close();
                for (Journal journal : journals)
                    journal.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return false;
        }
        // Past both ways out, so there really is work: from here on the person is told.
        found.run();
        ((FileIEngineImpl) engine.getDeligate()).recoveryStreams();

        engine.setPluginProperty(CORE, "Changed", Boolean.TRUE);
        engine.setActiveBranch(-1l);

        final AccessRules accessor = database.getAccessRules(null);

        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                SplashScreen screen = new SplashScreen() {
                    /**
                     *
                     */
                    private static final long serialVersionUID = -2727237354089088151L;

                    @Override
                    protected String getImageName() {
                        return Runner.this.getSplashImageName();
                    }
                };
                screen.setLocationRelativeTo(null);
                screen.setVisible(true);

                try {
                    final JFrame frame = openInNewWindows(engine, accessor,
                            sourceFile, true);
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            String recovered = GlobalResourcesManager
                                    .getString("File.Recovered");
                            frame.setTitle(frame.getTitle() + " " + recovered);
                        }
                    });
                } finally {
                    screen.setVisible(false);
                }
            }
        };
        recoveredCount++;
        Thread thread = new Thread(runnable);
        thread.start();
        return true;
    }

    protected void initAdditionalGUIPlugins(List<GUIPlugin> list,
                                            Engine engine, AccessRules rules) {
        QualifierPluginSuit.addPlugins(list, engine, rules);
    }

    protected void initAdditionalPluginSuits(ArrayList<PluginProvider> ps) {
    }
}
