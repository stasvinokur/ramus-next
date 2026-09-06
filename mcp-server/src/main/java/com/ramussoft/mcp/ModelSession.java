package com.ramussoft.mcp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipException;

import com.dsoft.pb.idef.ResourceLoader;
import com.ramussoft.common.AccessRules;
import com.ramussoft.common.Engine;
import com.ramussoft.common.Attribute;
import com.ramussoft.common.AttributeType;
import com.ramussoft.common.Element;
import com.ramussoft.common.Qualifier;
import com.ramussoft.core.attribute.standard.AutochangePlugin;
import com.ramussoft.core.attribute.standard.StandardAttributesPlugin;
import com.ramussoft.database.common.RowSet;
import com.ramussoft.gui.common.event.ActionEvent;
import com.ramussoft.idef0.IDEF0Plugin;
import com.ramussoft.idef0.IDEF0ViewPlugin;
import com.ramussoft.idef0.OpenDiagram;
import com.ramussoft.pb.idef.visual.MovingArea;
import com.ramussoft.common.PluginFactory;
import com.ramussoft.core.impl.FileIEngineImpl;
import com.ramussoft.database.MemoryDatabase;
import com.ramussoft.idef0.NDataPluginFactory;
import com.ramussoft.pb.DataPlugin;

/**
 * One open model. The only class here that knows there is a file.
 *
 * <p>
 * Opening is four lines, and they are not invented: {@code project-navigator} already opens
 * a model this way to serve it as HTML, which is why an application built entirely around a
 * Swing frame can be driven with no frame at all. What this adds is the writing side, and
 * writing is where a model gets destroyed, so the rules are here rather than spread across
 * the tools.
 */
public final class ModelSession implements AutoCloseable {

    private File file;

    private final boolean readOnly;

    private final MemoryDatabase database;

    private final Engine engine;

    private final AccessRules rules;

    private FileIEngineImpl fileEngine;

    private final java.util.Map<Long, DataPlugin> dataPlugins =
            new java.util.HashMap<Long, DataPlugin>();

    /**
     * Whether the model was already open somewhere when this session started. Asked before
     * the session exists, because afterwards it would find itself.
     */
    private final boolean openElsewhere;

    private boolean backedUp = false;

    private boolean dirty = false;

    /**
     * A session over an existing model file.
     */
    public ModelSession(File file, boolean readOnly) {
        this(file, readOnly, false);
    }

    /**
     * A session over a model that does not exist yet.
     *
     * <p>
     * The whole difference is that the database is told there is no file to read: the schema
     * is built either way, so what comes back is an empty but complete model. The path is
     * remembered as where it will be written, not as where it came from.
     */
    public static ModelSession createNew(File target) {
        return new ModelSession(target, false, true);
    }

    private ModelSession(File file, boolean readOnly, boolean isNew) {
        this.file = file;
        this.readOnly = readOnly;
        // A file that does not exist yet cannot be open in the application, and asking would
        // compare against a zero length - which every empty session copy would match.
        this.openElsewhere = !isNew && ModelLock.isOpenElsewhere(file);
        this.database = new MemoryDatabase(true) {

            @Override
            protected File getFile() {
                return isNew ? null : ModelSession.this.file;
            }

            @Override
            protected FileIEngineImpl createFileIEngine(PluginFactory factory)
                    throws ClassNotFoundException, ZipException, IOException {
                // The database keeps this privately and offers no accessor, but the hook
                // that builds it is protected - and the file engine is the only thing that
                // can write the model back out.
                FileIEngineImpl created = super.createFileIEngine(factory);
                fileEngine = created;
                return created;
            }
        };
        this.engine = database.getEngine(null);
        this.rules = database.getAccessRules(null);
    }

    public Engine getEngine() {
        return engine;
    }

    public File getFile() {
        return file;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * The IDEF0 view of the same model - functions, arrows, diagrams.
     *
     * <p>
     * One per model qualifier, because a file can hold several models and each has its own
     * function tree. Built lazily and kept: building one walks the whole model, and a
     * conversation about a diagram asks for the same one over and over.
     */
    public synchronized DataPlugin getDataPlugin(Qualifier model) {
        Long key = model == null ? Long.valueOf(-1L) : Long.valueOf(model.getId());
        DataPlugin cached = dataPlugins.get(key);
        if (cached == null) {
            cached = NDataPluginFactory.getDataPlugin(model, engine, rules);
            dataPlugins.put(key, cached);
        }
        return cached;
    }

    /**
     * Adds a model to this file - a named IDEF0, DFD or DFDS diagram tree with a top
     * activity, which is what makes a new file worth opening. Exactly what the application's
     * own "create model" dialog does, minus the dialog.
     */
    public synchronized Qualifier addModel(String name, int decompositionType) {
        Attribute forName = nameAttribute();
        Qualifier qualifier = engine.createQualifier();
        qualifier.setName(name);
        // A model whose qualifier has no attribute to hold a name cannot name an activity:
        // NFunction.setName looks that attribute up and dereferences it. In the application
        // this is never missing because the new-project wizard creates it and registers it
        // for auto-adding, so every later qualifier inherits it - a file created here has to
        // do the same thing rather than rely on a wizard nobody ran.
        if (engine.getAttribute(qualifier.getAttributeForName()) == null) {
            if (!qualifier.getAttributes().contains(forName))
                qualifier.getAttributes().add(forName);
            qualifier.setAttributeForName(forName.getId());
            engine.updateQualifier(qualifier);
        }
        IDEF0Plugin.installFunctionAttributes(qualifier, engine);

        // And register it in the model tree, which is the catalog the application's Models
        // panel reads. Without this the model exists but is invisible in the interface -
        // a file half created, which is worse than one not created at all.
        // The two-argument constructor never runs init(), so it registers no listeners while
        // close() still tries to remove them - "Listener not found and can not be removed".
        // This one initialises, and then the pair matches.
        RowSet modelTree = new RowSet(engine, IDEF0Plugin.getModelTree(engine),
                new Attribute[]{});
        try {
            Element element = engine.createElement(modelTree.getQualifier().getId());
            engine.setAttribute(element,
                    StandardAttributesPlugin.getAttributeQualifierId(engine),
                    qualifier.getId());
            modelTree.createRow(null, element).setName(name);
        } finally {
            modelTree.close();
        }
        // There is no DIAGRAM_TYPE_IDEF0 constant: IDEF0 is the absence of the other two,
        // which is why the application only sets the type when it is DFD or DFDS.
        if (decompositionType == MovingArea.DIAGRAM_TYPE_DFD
                || decompositionType == MovingArea.DIAGRAM_TYPE_DFDS)
            NDataPluginFactory.getDataPlugin(qualifier, engine, rules).getBaseFunction()
                    .setDecompositionType(decompositionType);
        dirty = true;
        return qualifier;
    }

    /**
     * The text attribute that holds a name, creating it the first time.
     *
     * <p>
     * Registered for auto-adding as well as returned, so that catalogs created afterwards -
     * by an agent or by the application once it opens the file - get it without being told.
     * That registration is the difference between a file the application treats as its own
     * and one where every new catalog comes out nameless.
     */
    private Attribute nameAttribute() {
        java.util.Properties properties =
                engine.getProperties(AutochangePlugin.AUTO_ADD_ATTRIBUTES);
        String registered = properties.getProperty(AutochangePlugin.ATTRIBUTE_FOR_NAME);
        if (registered != null) {
            Attribute existing = engine.getAttribute(Long.parseLong(registered.trim()));
            if (existing != null)
                return existing;
        }
        Attribute attribute = engine.createAttribute(new AttributeType("Core", "Text", true));
        attribute.setName(ResourceLoader.getString("name"));
        engine.updateAttribute(attribute);

        String ids = properties.getProperty(AutochangePlugin.AUTO_ADD_ATTRIBUTE_IDS);
        properties.setProperty(AutochangePlugin.AUTO_ADD_ATTRIBUTE_IDS,
                ids == null || ids.trim().isEmpty()
                        ? Long.toString(attribute.getId())
                        : ids + " " + attribute.getId());
        properties.setProperty(AutochangePlugin.ATTRIBUTE_FOR_NAME,
                Long.toString(attribute.getId()));
        engine.setProperties(AutochangePlugin.AUTO_ADD_ATTRIBUTES, properties);
        return attribute;
    }

    /**
     * Writes this model to another path and continues there. The file it came from is left
     * exactly as it was, which is the only behaviour anyone means by "save as".
     */
    public synchronized void saveAs(File target) throws IOException {
        if (readOnly)
            throw new IllegalStateException(
                    "This server was started read-only, so the model cannot be saved.");
        File previous = file;
        file = target;
        try {
            save();
        } catch (IOException failed) {
            file = previous;
            throw failed;
        }
        // The backup belongs to the file it was taken from; the new path has its own history
        // and gets its own backup the next time something changes.
        backedUp = false;
    }

    /**
     * Records that something has been changed and must be saved. Also the moment the backup
     * is taken - once per session, before the first change rather than before the first
     * save, so the copy is of the model as it was found.
     */
    public synchronized void markChanged() throws IOException {
        if (readOnly)
            throw new IllegalStateException(
                    "This server was started read-only, so the model cannot be changed.");
        if (openElsewhere)
            throw new IllegalStateException("This model is already open in Ramus Next. "
                    + "Writing now would create a second session over one file, and "
                    + "whichever is saved last would silently discard the other's work. "
                    + "Close the model in the application and start this server again, or "
                    + "run it with --read-only if you only need to look.");
        if (!backedUp) {
            // Nothing to copy for a model that has never been written; its backup is taken
            // the first time it changes after it exists.
            if (file.isFile())
                backup();
            backedUp = true;
        }
        dirty = true;
    }

    public boolean isOpenElsewhere() {
        return openElsewhere;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    private void backup() throws IOException {
        File copy = new File(file.getParentFile(), file.getName() + ".backup");
        // Never overwrite an existing backup: the first one is of the model as it was
        // before any agent touched it, and that is the one worth keeping.
        int n = 1;
        while (copy.exists())
            copy = new File(file.getParentFile(), file.getName() + ".backup." + (n++));
        Files.copy(file.toPath(), copy.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * Writes the model back.
     *
     * <p>
     * Into a neighbouring temporary file first, and only then over the target. The same
     * discipline the image export needed: a failure halfway through must leave the model
     * that was there, not half of a new one. A model is the user's work of months.
     */
    public synchronized void save() throws IOException {
        if (readOnly)
            throw new IllegalStateException(
                    "This server was started read-only, so the model cannot be saved.");
        if (fileEngine == null)
            throw new IllegalStateException("The model was never opened for writing.");

        rememberWhichDiagramToOpen();

        File partial = File.createTempFile(file.getName(), ".part", file.getParentFile());
        boolean written = false;
        try {
            fileEngine.saveToFile(partial);
            written = true;
        } finally {
            if (!written)
                partial.delete();
        }
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        dirty = false;
    }

    /**
     * The application's record of which diagrams were open, written into the file itself.
     */
    private static final String GUI_SESSION = "/user/gui/session.binary";

    /**
     * Leaves the file saying which diagram to show when somebody opens it.
     *
     * <p>
     * Ramus Next does not decide that from the model. On opening a file it deserialises a
     * list of "open this view" events from {@code /user/gui/session.binary} and replays them
     * - {@code Runner} does it, {@code FilePlugin.saveToFile} writes it on every save - and a
     * file without that stream opens on an empty canvas however complete its model is. That
     * is what a model built here looked like: correct in every field, and blank on screen.
     *
     * <p>
     * Only written when there is none. A file the application has saved carries the tabs the
     * person had open, and that is theirs to keep.
     */
    private void rememberWhichDiagramToOpen() {
        java.io.InputStream existing = engine.getInputStream(GUI_SESSION);
        if (existing != null) {
            try {
                existing.close();
            } catch (IOException ignored) {
                // Nothing was read from it; it was opened to ask whether it is there.
            }
            return;
        }
        java.util.List<Qualifier> models = IDEF0Plugin.getBaseQualifiers(engine);
        if (models.isEmpty())
            return;

        // The context diagram of the first model, which is the same event the new-project
        // wizard fires when it has finished building one: OpenDiagram(qualifier, -1).
        java.util.List<ActionEvent> session = new java.util.ArrayList<ActionEvent>();
        session.add(new ActionEvent(IDEF0ViewPlugin.OPEN_DIAGRAM,
                new OpenDiagram(models.get(0), -1L)));
        try (java.io.ObjectOutputStream out =
                     new java.io.ObjectOutputStream(engine.getOutputStream(GUI_SESSION))) {
            out.writeObject(session);
        } catch (IOException failed) {
            // A model that opens on a blank canvas is a poor result, not a lost one, and the
            // model itself is about to be written either way.
            System.err.println("Could not record which diagram to open: " + failed);
        }
    }

    /**
     * Closes the model AND its session.
     *
     * <p>
     * Both, because MemoryDatabase.close only closes the JDBC connection - it never touches
     * the file engine, so the session keeps its lock and its directory survives. Left that
     * way, every run of this server would leave a session behind under the user's settings,
     * the application would offer to recover models nobody lost, and the second run would
     * refuse to write because it would see the first one still holding the file.
     */
    @Override
    public void close() {
        try {
            database.close();
        } catch (Exception e) {
            // Nothing useful to do at shutdown, and stdout belongs to the protocol.
            e.printStackTrace();
        }
        if (fileEngine != null)
            try {
                fileEngine.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
    }
}
