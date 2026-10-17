/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 */
package app.hushtumblr.extension.shared;

import android.util.AtomicFile;
import android.os.Build;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.shadow.api.Shadow;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * {@link AtomicFile#finishWrite} with its rename done the way the phone does it.
 *
 * <p>Android 11 writes {@code <file>.new} and renames it over the file with
 * {@code File.renameTo}. On the phone that's Linux {@code rename(2)}, which replaces the file. On
 * a Windows JVM it fails whenever the file exists, and the framework only logs that, so under
 * Robolectric on Windows every write after the first was lost without a word: a newer crash
 * report read back as the older one. This moves the new file over the old one, as Linux does, and
 * fails the test if it can't. Applied to every test through {@code robolectric.properties}.
 */
@Implements(AtomicFile.class)
public class ShadowReplacingAtomicFile {
    @RealObject private AtomicFile realObject;
    /** Per-file faults, reset by the test that owns them. */
    public static File refuseCommitFor, closeOnFlushFor, refuseReadFor;
    public static int refusedReads;

    @Implementation
    protected FileInputStream openRead() throws FileNotFoundException {
        File base = ReflectionHelpers.getField(realObject, "mBaseName");
        if (base.equals(refuseReadFor) && refusedReads > 0) {
            refusedReads--;
            throw new FileNotFoundException("Recorded verification read failure");
        }
        try {
            return Shadow.directlyOn(realObject, AtomicFile.class, "openRead");
        } catch (RuntimeException wrapped) {
            // directlyOn wraps the framework's checked exception, which callers read as "no file yet".
            if (wrapped.getCause() instanceof FileNotFoundException) throw (FileNotFoundException) wrapped.getCause();
            throw wrapped;
        }
    }

    @Implementation
    protected FileOutputStream startWrite() throws IOException {
        FileOutputStream output;
        try {
            output = Shadow.directlyOn(realObject, AtomicFile.class, "startWrite");
        } catch (RuntimeException wrapped) {
            if (wrapped.getCause() instanceof IOException) throw (IOException) wrapped.getCause();
            throw wrapped;
        }
        File base = ReflectionHelpers.getField(realObject, "mBaseName");
        if (!base.equals(closeOnFlushFor)) return output;
        return new FileOutputStream(output.getFD()) {
            @Override public void flush() throws IOException { output.close(); }
        };
    }

    @Implementation
    protected void finishWrite(FileOutputStream output) {
        if (output == null) return;
        File base = ReflectionHelpers.getField(realObject, "mBaseName");
        if (base.equals(refuseCommitFor)) {
            try { output.close(); } catch (IOException ignored) { }
            if (Build.VERSION.SDK_INT < 30) {
                // The old implementation writes the base directly. Model a failed finalization
                // restoring its backup (or losing an uncommitted first file), not a rename.
                File backup = new File(base.getPath() + ".bak");
                try {
                    Files.deleteIfExists(base.toPath());
                    if (backup.exists()) Files.move(backup.toPath(), base.toPath());
                } catch (IOException error) { throw new UncheckedIOException(error); }
            }
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            Shadow.directlyOn(realObject, AtomicFile.class, "finishWrite",
                    ReflectionHelpers.ClassParameter.from(FileOutputStream.class, output));
            return;
        }
        try {
            output.getFD().sync();
            output.close();
            File written = ReflectionHelpers.getField(realObject, "mNewName");
            Files.move(written.toPath(), base.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("AtomicFile could not replace its file", e);
        }
    }
}
