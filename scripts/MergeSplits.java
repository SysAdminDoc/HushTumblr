import app.morphe.patcher.apk.ApkMerger;

import com.reandroid.apk.APKLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.apk.ApkUtil;
import com.reandroid.archive.ArchiveFile;
import com.reandroid.archive.InputSource;
import com.reandroid.archive.io.FileChannelOutputStream;
import com.reandroid.archive.io.ZipFileInput;
import com.reandroid.archive.io.ZipFileOutput;
import com.reandroid.archive.writer.ApkFileWriter;
import com.reandroid.archive.writer.BufferFileInput;
import com.reandroid.archive.writer.BufferFileOutput;
import com.reandroid.archive.writer.DataDescriptorFactory;
import com.reandroid.archive.writer.ZipAligner;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Merges the APKs of a split bundle (.apkm, .apks or .xapk) into one APK with the desktop CLI's own
 * merger, doing what the CLI's patch command does before it patches.
 *
 * <p>morphe-desktop 1.17.0 merges a bundle into {@code <name>-merged.apk} beside its output,
 * patches that and deletes it on the way out, so nothing the scripts could compare a patched APK
 * with was left behind: the resource check fell back to base.apk, which lacks every resource the
 * splits carry, and the receipt read its manifest baseline off base.apk too. The scripts merge
 * first now and hand the CLI the merged APK, which it patches as it is, so the table and manifest
 * the patched APK was rebuilt from are a file they hold.
 *
 * <p>The merger is handed a directory, never the archive. Given an archive it unpacks every .apk
 * member into tmp_&lt;hash&gt; beside it, where the member's name points, with nothing to stop a
 * name that climbs out of that folder. The scripts check every member name and unpack the APKs
 * themselves (Expand-SplitBundle in common.ps1), and this refuses anything but a directory holding
 * regular .apk files and nothing else.
 *
 * <p>Each APK's SHA-256 comes after the two paths, as {@code name:sha256}, hashed where the scripts
 * checked its signature (Lock-SplitFiles, Assert-SplitsAuthentic). The directory has to hold
 * exactly those files with exactly those bytes, or nothing is merged: the merge is made of what
 * was checked.
 *
 * <p>Another process of the same user can write to anything this one can, so nothing here is read
 * or written by a path a second time. {@code ApkMerger.merge} lists the directory for itself after
 * the check, which lets in an APK that turns up in between, and its writer opens the merged APK
 * and the buffer it compresses changed entries into by path, shared with everyone, and leaves the
 * scripts to hash the result from its path once it has closed. This does the merger's steps itself
 * instead:
 * <ul>
 * <li>Each APK that was checked is opened once, denying other processes write and delete, hashed
 * through that channel, and loaded into the bundle through it (what {@code ApkBundle.loadApkDirectory}
 * does per file, without the listing). The channels stay open until the merge is written.
 * <li>The merged APK and the compression buffer are created new and held the same way, the buffer
 * denying read as well, before any of the library's own output classes is constructed: each of
 * those deletes its path and creates it again, and it is the hold that turns that away
 * ({@link #assertHeld}). The library's writer reaches its output through members only a class in
 * its own package can override, so that class is compiled here and defined into the library's
 * package and loader ({@link #defineWriter}). A copy loaded with this file would be in another
 * runtime package and the writer would go on opening the path.
 * <li>The merged APK is hashed through the channel it was written with, before that closes, and
 * the digest is printed. The scripts open the file denying writers, hash it through that handle
 * and compare (Open-HeldFile in common.ps1), since no handle that can write may be open when theirs
 * opens and anything can happen to the path in between.
 * </ul>
 *
 * <p>The sharing options are com.sun.nio.file.ExtendedOpenOption's, which only a Windows JDK
 * honours and which are not part of the Java API. They are looked up by name, and without them, or
 * without the compiler, nothing is merged.
 *
 * <p>The steps and their arguments are the CLI's own (PatchCommand and ApkMerger.merge, 1.18.1):
 * merge with the manifests' split attributes taken out, the resources directory validated, the
 * manifest's own extractNativeLibs kept, and ApkUtils.writeAlignedApk's alignment of 4 with 16384
 * for native libraries. test-resource-table-check.ps1 holds this to the same bytes as
 * {@code ApkMerger.merge}.
 *
 * <p>{@code -Dhushtumblr.merge.hold=<directory>} is for that suite: at each of three points
 * (checked, buffered, written) this writes {@code <point>.reached} there and waits for
 * {@code <point>.go}, so a second process can try the files while they are held. It only waits.
 *
 *   java -cp &lt;cli jar&gt; MergeSplits.java &lt;split directory&gt; &lt;merged.apk&gt; &lt;name:sha256&gt;...
 */
public final class MergeSplits {
    private static final String WRITER_PACKAGE = "com.reandroid.archive.writer";
    private static final String WRITER_NAME = WRITER_PACKAGE + ".HeldApkFileWriter";

    /**
     * ApkFileWriter with its output and its buffer swapped for ones this process holds.
     * ApkWriter.position, getOutputStream and close go to the ZipFileOutput its constructor made
     * from the path, and ApkFileWriter.prepareOutputs makes a BufferFileOutput and BufferFileInput
     * from a path of its own; prepareOutputs here is that method and the private writeBuffer it
     * calls, with the held pair in their place.
     */
    private static final String WRITER_SOURCE = """
        package com.reandroid.archive.writer;

        import com.reandroid.archive.InputSource;
        import com.reandroid.archive.io.ZipFileOutput;
        import com.reandroid.arsc.chunk.TableBlock;

        import java.io.File;
        import java.io.IOException;
        import java.io.OutputStream;

        public final class HeldApkFileWriter extends ApkFileWriter {
            private final ZipFileOutput output;
            private final BufferFileOutput bufferOutput;
            private final BufferFileInput bufferInput;
            private final Runnable buffered;

            public HeldApkFileWriter(File file, InputSource[] sources, ZipFileOutput output,
                                     BufferFileOutput bufferOutput, BufferFileInput bufferInput,
                                     Runnable buffered) throws IOException {
                super(file, sources);
                this.output = output;
                this.bufferOutput = bufferOutput;
                this.bufferInput = bufferInput;
                this.buffered = buffered;
            }

            @Override
            public ZipFileOutput getZipOutput() {
                return output;
            }

            @Override
            long position() throws IOException {
                return output.position();
            }

            @Override
            OutputStream getOutputStream() throws IOException {
                return output.getOutputStream();
            }

            @Override
            public void close() throws IOException {
                output.close();
            }

            @Override
            void closeBuffer() throws IOException {
                bufferInput.close();
            }

            @Override
            void prepareOutputs(FileOutputSource[] outputs) throws IOException {
                logMessage("Buffering compress changed files ...");
                FileOutputSource table = null;
                for (FileOutputSource source : outputs) {
                    InputSource input = source.getInputSource();
                    if (table == null && TableBlock.FILE_NAME.equals(input.getAlias())) {
                        table = source;
                        continue;
                    }
                    onCompressFileProgress(input.getAlias(), input.getMethod(), bufferOutput.position());
                    source.makeBuffer(bufferInput, bufferOutput);
                }
                if (table != null) {
                    table.makeBuffer(bufferInput, bufferOutput);
                }
                bufferOutput.close();
                bufferInput.unlock();
                buffered.run();
            }
        }
        """;

    public static void main(String[] args) throws Exception {
        try {
            merge(args);
        } catch (Refusal refusal) {
            System.err.println(refusal.getMessage());
            System.exit(refusal.code);
        }
    }

    private static void merge(String[] args) throws Exception {
        if (args.length < 3) {
            throw new Refusal(2, "usage: MergeSplits <split directory> <merged.apk> <name:sha256>...");
        }
        File splits = new File(args[0]);
        File merged = new File(args[1]);
        if (!splits.isDirectory()) {
            throw new Refusal(2, "[merge] " + splits.getPath() + " is not a directory of split APKs. A bundle is unpacked "
                + "and its member names checked first (Expand-SplitBundle), and the merger reads that directory.");
        }
        Map<String, String> expected = new TreeMap<>();
        for (int i = 2; i < args.length; i++) {
            int colon = args[i].lastIndexOf(':');
            String name = colon > 0 ? args[i].substring(0, colon) : "";
            String digest = colon > 0 ? args[i].substring(colon + 1) : "";
            if (!name.endsWith(".apk") || name.contains("/") || name.contains("\\") || !digest.matches("[0-9a-f]{64}")) {
                throw new Refusal(2, "[merge] " + args[i] + " is not <name>.apk:<sha256>");
            }
            if (expected.put(name, digest) != null) {
                throw new Refusal(2, "[merge] " + name + " is listed twice");
            }
        }
        File[] entries = splits.listFiles();
        if (entries == null) {
            throw new Refusal(2, "[merge] could not list " + splits.getPath());
        }
        Map<String, File> found = new TreeMap<>();
        for (File entry : entries) {
            if (!Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) || !entry.getName().endsWith(".apk")) {
                throw new Refusal(2, "[merge] " + splits.getPath() + " holds " + entry.getName()
                    + ", which is not a split APK; the merger would read more than was checked.");
            }
            found.put(entry.getName(), entry);
        }
        if (found.isEmpty()) {
            throw new Refusal(2, "[merge] " + splits.getPath() + " holds no split APK.");
        }
        if (!found.keySet().equals(expected.keySet())) {
            throw new Refusal(2, "[merge] " + splits.getPath() + " holds " + found.keySet() + ", and the APKs that were checked are "
                + expected.keySet());
        }

        OpenOption noRead = sharing("NOSHARE_READ");
        OpenOption noWrite = sharing("NOSHARE_WRITE");
        OpenOption noDelete = sharing("NOSHARE_DELETE");
        Path mergedPath = merged.toPath();
        Path bufferPath = mergedPath.resolveSibling(merged.getName() + ".buffer");
        List<FileChannel> held = new ArrayList<>();
        boolean written = false;
        boolean made = false;
        try {
            // Opened once each. The hash and everything the merge reads come through these channels,
            // and while they are open no other process can write to the files or put others in their
            // place, whatever turns up beside them.
            Map<String, FileChannel> inputs = new TreeMap<>();
            for (Map.Entry<String, File> entry : found.entrySet()) {
                FileChannel channel = FileChannel.open(entry.getValue().toPath(),
                    Set.of(StandardOpenOption.READ, noWrite, noDelete, LinkOption.NOFOLLOW_LINKS));
                held.add(channel);
                String digest = sha256(channel);
                if (!digest.equals(expected.get(entry.getKey()))) {
                    throw new Refusal(2, "[merge] " + entry.getKey() + " hashes to " + digest + ", not the "
                        + expected.get(entry.getKey()) + " that was checked; it changed after its signature was.");
                }
                inputs.put(entry.getKey(), channel);
            }
            hold("checked");

            ApkMerger merger = new ApkMerger();
            Field loggerField = ApkMerger.class.getDeclaredField("arsclibLogger");
            loggerField.setAccessible(true);
            APKLogger logger = (APKLogger) loggerField.get(merger);
            Method sanitizeManifest = ApkMerger.class.getDeclaredMethod("sanitizeManifest", ApkModule.class);
            sanitizeManifest.setAccessible(true);

            ApkBundle bundle = new ApkBundle();
            bundle.setAPKLogger(logger);
            for (Map.Entry<String, FileChannel> input : inputs.entrySet()) {
                File file = found.get(input.getKey());
                // The path the library is told is one nothing can be opened at, since the APK it
                // would have to be a folder of can't be replaced: a read that didn't come through
                // the channel fails instead of reading whatever the name resolves to by then.
                ArchiveFile archive = new ArchiveFile(new HeldInput(new File(file, "held"), input.getValue()));
                ApkModule module = new ApkModule(ApkUtil.toModuleName(file), archive.createZipEntryMap());
                module.setApkSignatureBlock(archive.getApkSignatureBlock());
                module.setCloseable(archive);
                module.setAPKLogger(logger);
                bundle.addModule(module);
            }
            ApkModule module = bundle.mergeModules(true);
            module.validateResourcesDir();
            try {
                sanitizeManifest.invoke(merger, module);
            } catch (InvocationTargetException failed) {
                throw failed.getCause() instanceof Exception cause ? cause : failed;
            }
            module.refreshTable();
            module.refreshManifest();
            AndroidManifestBlock manifest = module.getAndroidManifest();
            module.setExtractNativeLibs(manifest != null ? manifest.isExtractNativeLibs() : null);

            module.updateUncompressedFiles();
            InputSource[] sources = module.getZipEntryMap().toArray(true);
            Class<?> writerClass = defineWriter();

            // Made new, so a file another process already has open or mapped is never written into,
            // and held from here until the hash below has been taken through the same channel.
            Files.deleteIfExists(mergedPath);
            Files.deleteIfExists(bufferPath);
            FileChannel output = FileChannel.open(mergedPath, Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, noWrite, noDelete));
            held.add(output);
            made = true;
            FileChannel buffer = FileChannel.open(bufferPath, Set.of(StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE, noRead, noWrite, noDelete));
            held.add(buffer);
            assertHeld(mergedPath);
            assertHeld(bufferPath);

            HeldOutput heldOutput = new HeldOutput(merged, output);
            ApkFileWriter writer = (ApkFileWriter) writerClass
                .getConstructor(File.class, InputSource[].class, ZipFileOutput.class, BufferFileOutput.class,
                    BufferFileInput.class, Runnable.class)
                .newInstance(merged, sources, heldOutput, new HeldBufferOutput(bufferPath.toFile(), buffer),
                    new HeldBufferInput(bufferPath.toFile(), buffer), (Runnable) () -> hold("buffered"));
            writer.setAPKLogger(module.getApkLogger());
            writer.setApkSignatureBlock(module.getApkSignatureBlock());
            writer.setArchiveInfo(module.getZipEntryMap().getArchiveInfo());
            writer.setDataDescriptorFactory(DataDescriptorFactory.NO_ACTION);
            ZipAligner aligner = new ZipAligner();
            aligner.setDefaultAlignment(4);
            aligner.setFileAlignment(name -> name.endsWith(".so"), 16384);
            writer.setZipAligner(aligner);
            writer.write();
            module.close();
            bundle.close();

            long length = output.size();
            if (length == 0) {
                throw new Refusal(1, "[merge] the merge of " + splits.getName() + " wrote no APK at " + merged.getPath());
            }
            String digest = sha256(output);
            hold("written");
            System.out.println("[merge] " + found.size() + " APKs from " + splits.getName() + " -> " + merged.getName() + ", "
                + length + " bytes");
            System.out.println("[merge] sha256 " + digest);
            written = true;
        } finally {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).close();
            }
            if (made) {
                Files.deleteIfExists(bufferPath);
                if (!written) {
                    Files.deleteIfExists(mergedPath);
                }
            }
        }
    }

    /** One of ExtendedOpenOption's share modes, by name: it is the JDK's own API, not Java's. */
    private static OpenOption sharing(String name) {
        try {
            for (Object option : Class.forName("com.sun.nio.file.ExtendedOpenOption").getEnumConstants()) {
                if (((Enum<?>) option).name().equals(name)) {
                    return (OpenOption) option;
                }
            }
        } catch (ReflectiveOperationException | LinkageError missing) {
            // Reported below, with the option that was wanted.
        }
        throw new Refusal(2, "[merge] this Java (" + System.getProperty("java.version") + ") has no com.sun.nio.file.ExtendedOpenOption."
            + name + ", so a file can't be kept from other processes while it is merged. The scripts need JDK 21 on Windows.");
    }

    /**
     * A second writer has to be turned away and a delete refused, or the options above did nothing
     * on this system. The delete matters inside this process too: the library's ZipFileOutput
     * deletes its path and creates it again when it is constructed, as the output, the writer and
     * the buffer each are below, and BufferFileInput.close deletes the buffer's. They go by path,
     * and the hold is what makes them fail and leave the held file where it is. Tried here on a
     * file that is still empty, so a delete that goes through loses nothing.
     */
    private static void assertHeld(Path path) throws IOException {
        boolean opened = true;
        try (FileChannel second = FileChannel.open(path, StandardOpenOption.WRITE)) {
            // Falls through to the refusal: nothing should have opened.
        } catch (IOException turnedAway) {
            opened = false;
        }
        if (opened) {
            throw new Refusal(2, "[merge] " + path.getFileName() + " could be opened for writing a second time while the merge "
                + "holds it, so another process could change it; nothing was merged.");
        }
        if (path.toFile().delete() || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new Refusal(2, "[merge] " + path.getFileName() + " could be deleted while the merge holds it, so another file "
                + "could be put in its place; nothing was merged.");
        }
    }

    /**
     * Compiles {@link #WRITER_SOURCE} in memory and defines it beside ApkFileWriter: same loader and
     * same package, so one runtime package, which is what lets it override members that have no
     * access modifier. Nothing is written to disk for another process to change before it loads.
     */
    private static Class<?> defineWriter() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new Refusal(2, "[merge] this Java (" + System.getProperty("java.version") + ") has no compiler, and the class "
                + "that keeps the merged APK held is compiled as it runs. The scripts need a JDK, not a JRE.");
        }
        Map<String, ByteArrayOutputStream> compiled = new LinkedHashMap<>();
        StringWriter said = new StringWriter();
        boolean passed;
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavaFileManager manager = new ForwardingJavaFileManager<>(files) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                                                           FileObject sibling) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    compiled.put(className, bytes);
                    return new SimpleJavaFileObject(URI.create("memory:///" + className.replace('.', '/') + kind.extension), kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return bytes;
                        }
                    };
                }
            };
            JavaFileObject source = new SimpleJavaFileObject(
                URI.create("memory:///" + WRITER_NAME.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return WRITER_SOURCE;
                }
            };
            passed = compiler.getTask(said, manager, null,
                List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"), null, List.of(source)).call();
        }
        if (!passed || !compiled.keySet().equals(Set.of(WRITER_NAME))) {
            throw new Refusal(2, "[merge] the class that keeps the merged APK held did not compile against this CLI jar "
                + "(wrote " + compiled.keySet() + "); the library's writer isn't the one it was written for.\n" + said);
        }
        Class<?> writer = MethodHandles.privateLookupIn(ApkFileWriter.class, MethodHandles.lookup())
            .defineClass(compiled.get(WRITER_NAME).toByteArray());
        if (writer.getClassLoader() != ApkFileWriter.class.getClassLoader() || !writer.getPackageName().equals(WRITER_PACKAGE)) {
            throw new Refusal(2, "[merge] " + writer.getName() + " was not defined beside the library's writer, so it could "
                + "not stand in for the members that open the merged APK by its path.");
        }
        return writer;
    }

    private static void hold(String point) {
        String directory = System.getProperty("hushtumblr.merge.hold");
        if (directory == null) {
            return;
        }
        try {
            Files.createFile(Path.of(directory, point + ".reached"));
            Path go = Path.of(directory, point + ".go");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (!Files.exists(go)) {
                if (System.nanoTime() > until) {
                    throw new Refusal(1, "[merge] nothing wrote " + go + " in two minutes, so the merge stopped where it was held.");
                }
                Thread.sleep(20);
            }
        } catch (IOException | InterruptedException failed) {
            throw new Refusal(1, "[merge] could not hold at " + point + " in " + directory + ": " + failed);
        }
    }

    private static String sha256(FileChannel channel) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        long at = 0;
        // Read at an offset, which leaves the channel's own position for the library.
        for (int read; (read = channel.read(buffer, at)) > 0; at += read) {
            buffer.flip();
            digest.update(buffer);
            buffer.clear();
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class Refusal extends RuntimeException {
        private final int code;

        Refusal(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** A split APK read through the channel that was hashed. ZipFileInput would open its path. */
    private static final class HeldInput extends ZipFileInput {
        private final FileChannel channel;

        HeldInput(File unreachable, FileChannel channel) {
            super(unreachable);
            this.channel = channel;
        }

        @Override
        public FileChannel getFileChannel() {
            return channel;
        }

        @Override
        public long getLength() {
            try {
                return channel.size();
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }

        @Override
        public long position() throws IOException {
            return channel.position();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }
    }

    /**
     * The merged APK, written through the channel that is hashed afterwards. ZipFileOutput would
     * delete the path and open it again, shared with every other process. write is its own loop.
     */
    private static class HeldOutput extends ZipFileOutput {
        private final FileChannel channel;
        private FileChannelOutputStream stream;

        HeldOutput(File file, FileChannel channel) throws IOException {
            super(file);
            this.channel = channel;
        }

        @Override
        public void write(FileChannel input, long length) throws IOException {
            flush();
            long start = channel.position();
            long done = 0;
            while (done < length) {
                long moved = channel.transferFrom(input, start + done, length - done);
                if (moved <= 0) {
                    throw new IOException(getFile().getName() + " ran out " + (length - done) + " bytes short of an entry");
                }
                done += moved;
            }
            channel.position(start + done);
        }

        @Override
        public long position() throws IOException {
            flush();
            return channel.position();
        }

        @Override
        public FileChannelOutputStream getOutputStream() {
            if (stream == null) {
                stream = new FileChannelOutputStream(channel);
            }
            return stream;
        }

        /** The channel outlives the library's close: it is hashed first, and closed by merge. */
        @Override
        public void close() throws IOException {
            flush();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        private void flush() throws IOException {
            if (stream != null) {
                stream.flush();
            }
        }
    }

    /** The entries the merge compresses again, written through a channel no other process can open. */
    private static final class HeldBufferOutput extends BufferFileOutput {
        private final FileChannel channel;
        private FileChannelOutputStream stream;

        HeldBufferOutput(File file, FileChannel channel) throws IOException {
            super(file);
            this.channel = channel;
        }

        @Override
        public void write(FileChannel input, long length) throws IOException {
            throw new IOException("nothing copies whole entries into the compression buffer");
        }

        @Override
        public long position() throws IOException {
            flush();
            return channel.position();
        }

        @Override
        public FileChannelOutputStream getOutputStream() {
            if (stream == null) {
                stream = new FileChannelOutputStream(channel);
            }
            return stream;
        }

        @Override
        public void close() throws IOException {
            flush();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        private void flush() throws IOException {
            if (stream != null) {
                stream.flush();
            }
        }
    }

    /**
     * The same buffer read back, through the same channel. BufferFileInput's unlock is a flag in
     * this process and its close deletes the path; the file is held by merge, which deletes it.
     */
    private static final class HeldBufferInput extends BufferFileInput {
        private final FileChannel channel;
        private boolean unlocked;

        HeldBufferInput(File file, FileChannel channel) {
            super(file);
            this.channel = channel;
        }

        @Override
        public void unlock() {
            unlocked = true;
        }

        @Override
        public FileChannel getFileChannel() throws IOException {
            if (!unlocked) {
                throw new IOException("File locked!");
            }
            return channel;
        }

        @Override
        public long getLength() {
            try {
                return channel.size();
            } catch (IOException failed) {
                throw new java.io.UncheckedIOException(failed);
            }
        }

        @Override
        public long position() throws IOException {
            return channel.position();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }
    }
}
