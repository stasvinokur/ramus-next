package com.ramussoft.mcp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import com.ramussoft.core.impl.FileIEngineImpl;

/**
 * Whether the model is already open somewhere else.
 *
 * <p>
 * This matters because of a failure the application has had all along: a model file is never
 * locked, only the session directory beside it is. Two things holding the same model each
 * keep their own session, and whichever saves last silently discards the other's work. The
 * desktop application hit that with two windows on one file; a server writing underneath a
 * running application is the same accident with an extra participant.
 *
 * <p>
 * There is no register of which file a session belongs to - but every session keeps
 * {@code source.rms}, a byte copy of the model as it was opened, and it stays identical to
 * the file on disk until somebody saves. Comparing against that is exact, and it is the only
 * exact answer available.
 */
final class ModelLock {

    /**
     * Must be asked BEFORE this process opens its own session, or it finds itself.
     */
    static boolean isOpenElsewhere(File model) {
        File sessions = new File(FileIEngineImpl.getSessionsPath());
        File[] directories = sessions.listFiles();
        if (directories == null)
            return false;
        long size = model.length();
        for (File directory : directories) {
            File source = new File(directory, "source.rms");
            if (!source.isFile() || source.length() != size)
                continue;
            // A session left behind by a process that died is not somebody working: the
            // application offers to recover those, and there may be several. Only a session
            // whose lock is still held belongs to something running right now, and checking
            // that first also keeps the cost of the byte comparison off the common case.
            if (!isHeld(new File(directory, ".lock")))
                continue;
            try {
                if (sameBytes(source, model))
                    return true;
            } catch (IOException e) {
                // A session being written while we read it is not an answer either way, and
                // refusing to start over an unreadable temporary file would be worse than
                // the risk it represents.
                System.err.println("Could not compare " + source + ": " + e);
            }
        }
        return false;
    }

    /**
     * Whether something still holds this session's lock. Asked by trying to take it: the
     * attempt fails, or returns null, precisely while another process has it.
     */
    private static boolean isHeld(File lock) {
        if (!lock.isFile())
            return false;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(lock, "rw");
             java.nio.channels.FileChannel channel = file.getChannel()) {
            java.nio.channels.FileLock taken = channel.tryLock();
            if (taken == null)
                return true;
            taken.release();
            return false;
        } catch (java.nio.channels.OverlappingFileLockException heldByUs) {
            // Held inside THIS JVM - which happens when a test opens the same model twice,
            // and means exactly what a lock held by another process means.
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean sameBytes(File a, File b) throws IOException {
        try (InputStream left = Files.newInputStream(a.toPath());
             InputStream right = Files.newInputStream(b.toPath())) {
            byte[] one = new byte[8192];
            byte[] two = new byte[8192];
            while (true) {
                int read = left.readNBytes(one, 0, one.length);
                if (read != right.readNBytes(two, 0, two.length))
                    return false;
                if (read == 0)
                    return true;
                for (int i = 0; i < read; i++)
                    if (one[i] != two[i])
                        return false;
            }
        }
    }

    private ModelLock() {
    }
}
