package com.ramussoft.mcp;

import java.io.File;
import java.io.IOException;

/**
 * The model the agent is working on at the moment, and the only thing that changes it.
 *
 * <p>
 * The tools are registered once, when the server starts, but the model can be swapped
 * underneath them - so nothing may capture a session. Everything asks here instead, at the
 * moment it is called.
 *
 * <p>
 * One model at a time. Comparing two means opening one, reading it, then opening the other:
 * a deliberate trade, because it keeps every tool free of a "which file" argument and keeps
 * one model's worth of database in memory rather than several.
 */
final class Workspace {

    private final boolean readOnly;

    private ModelSession session;

    Workspace(boolean readOnly) {
        this.readOnly = readOnly;
    }

    boolean isReadOnly() {
        return readOnly;
    }

    synchronized boolean hasModel() {
        return session != null;
    }

    synchronized ModelSession peek() {
        return session;
    }

    /**
     * The open model, or a refusal an agent can act on.
     *
     * <p>
     * The message names the way out rather than only the problem: a server started with no
     * argument is a normal state, not an error, and the agent needs to be told what to do
     * about it the first time it asks for anything.
     */
    synchronized ModelSession current() {
        if (session == null)
            throw new IllegalStateException("No model is open. Call open_model with the path "
                    + "to a .rsf file, or create_model to make a new one. list_files shows "
                    + "what is in a directory.");
        return session;
    }

    /**
     * Opens a model, replacing whatever was open.
     *
     * <p>
     * Unsaved changes to the previous model are written out. That is the agreed behaviour
     * and it is the reason this returns what it did: the caller has to be able to tell the
     * agent that a file was written, because the agent has to be able to tell the user.
     */
    synchronized String open(File file) throws IOException {
        if (!file.isFile())
            throw new IllegalArgumentException(file + " is not a file.");
        String saved = closeCurrent();
        session = new ModelSession(file, readOnly);
        return saved;
    }

    /**
     * Creates a model file that is worth having: an empty database plus one named model
     * inside it, so the very next call can look at its top activity.
     */
    synchronized String create(File file, String modelName, int decompositionType)
            throws IOException {
        requireWritable("create a model");
        if (file.exists())
            throw new IllegalArgumentException(file + " already exists. Choose another path "
                    + "or delete that one first.");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory())
            throw new IllegalArgumentException(parent + " is not a directory.");

        String saved = closeCurrent();
        ModelSession created = ModelSession.createNew(file);
        try {
            created.addModel(modelName, decompositionType);
            created.save();
        } catch (Exception e) {
            created.close();
            throw e;
        }
        session = created;
        return saved;
    }

    /**
     * Writes the current model somewhere else and continues there, which is what "save as"
     * means everywhere and what an agent will expect.
     */
    synchronized void saveAs(File file) throws IOException {
        requireWritable("save a model somewhere else");
        ModelSession open = current();
        open.saveAs(file);
    }

    synchronized String close() throws IOException {
        if (session == null)
            throw new IllegalStateException("No model is open.");
        return closeCurrent();
    }

    /** Closes what is open, writing it out first if it changed. Returns what was written. */
    private String closeCurrent() throws IOException {
        if (session == null)
            return null;
        String written = null;
        if (session.isDirty() && !readOnly) {
            session.save();
            written = session.getFile().getAbsolutePath();
        }
        session.close();
        session = null;
        return written;
    }

    void requireWritable(String what) {
        if (readOnly)
            throw new IllegalStateException("This server was started read-only, so it cannot "
                    + what + ".");
    }
}
