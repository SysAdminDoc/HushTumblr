import app.morphe.patcher.apk.ApkMerger;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Merges the APKs of a split bundle (.apkm, .apks or .xapk) into one APK with the desktop CLI's own
 * merger, called the way the CLI's patch command calls it before it patches.
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
 * regular .apk files and nothing else, which the merger reads without unpacking anything.
 *
 * <p>Each APK's SHA-256 comes after the two paths, as {@code name:sha256}, hashed where the scripts
 * checked its signature (Lock-SplitFiles, Assert-SplitsAuthentic). The directory has to hold
 * exactly those files with exactly those bytes, or nothing is merged: the merge is made of what
 * was checked. The merger lists the directory again for itself, so it has to hold the same file
 * names once the merge is done too, or the merge is deleted.
 *
 * <p>The arguments are the CLI's own (PatchCommand, 1.17.0): {@code merge$default} with every
 * default but the last flag, which it passes as false, the same value the default gives it.
 *
 *   java -cp &lt;cli jar&gt; MergeSplits.java &lt;split directory&gt; &lt;merged.apk&gt; &lt;name:sha256&gt;...
 */
public final class MergeSplits {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: MergeSplits <split directory> <merged.apk> <name:sha256>...");
            System.exit(2);
        }
        File splits = new File(args[0]);
        File merged = new File(args[1]);
        if (!splits.isDirectory()) {
            System.err.println("[merge] " + splits.getPath() + " is not a directory of split APKs. A bundle is unpacked "
                + "and its member names checked first (Expand-SplitBundle), and the merger reads that directory.");
            System.exit(2);
        }
        Map<String, String> expected = new TreeMap<>();
        for (int i = 2; i < args.length; i++) {
            int colon = args[i].lastIndexOf(':');
            String name = colon > 0 ? args[i].substring(0, colon) : "";
            String digest = colon > 0 ? args[i].substring(colon + 1) : "";
            if (!name.endsWith(".apk") || name.contains("/") || name.contains("\\") || !digest.matches("[0-9a-f]{64}")) {
                System.err.println("[merge] " + args[i] + " is not <name>.apk:<sha256>");
                System.exit(2);
            }
            if (expected.put(name, digest) != null) {
                System.err.println("[merge] " + name + " is listed twice");
                System.exit(2);
            }
        }
        File[] entries = splits.listFiles();
        if (entries == null) {
            System.err.println("[merge] could not list " + splits.getPath());
            System.exit(2);
        }
        Map<String, File> found = new TreeMap<>();
        for (File entry : entries) {
            if (!Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) || !entry.getName().endsWith(".apk")) {
                System.err.println("[merge] " + splits.getPath() + " holds " + entry.getName()
                    + ", which is not a split APK; the merger would read more than was checked.");
                System.exit(2);
            }
            found.put(entry.getName(), entry);
        }
        if (found.isEmpty()) {
            System.err.println("[merge] " + splits.getPath() + " holds no split APK.");
            System.exit(2);
        }
        if (!found.keySet().equals(expected.keySet())) {
            System.err.println("[merge] " + splits.getPath() + " holds " + found.keySet() + ", and the APKs that were checked are "
                + expected.keySet());
            System.exit(2);
        }
        for (Map.Entry<String, File> entry : found.entrySet()) {
            String digest = sha256(entry.getValue());
            if (!digest.equals(expected.get(entry.getKey()))) {
                System.err.println("[merge] " + entry.getKey() + " hashes to " + digest + ", not the "
                    + expected.get(entry.getKey()) + " that was checked; it changed after its signature was.");
                System.exit(2);
            }
        }
        new ApkMerger().merge(splits, merged, true, null, true, null, false);
        // The merger lists the directory again itself, so an APK that arrived after the check above
        // went into the merge unchecked. The directory has to hold the same files once it's done.
        File[] after = splits.listFiles();
        Set<String> afterNames = new TreeSet<>();
        if (after != null) {
            for (File entry : after) afterNames.add(entry.getName());
        }
        if (after == null || !afterNames.equals(expected.keySet())) {
            Files.deleteIfExists(merged.toPath());
            System.err.println("[merge] " + splits.getPath() + " holds " + afterNames + " after the merge, not the APKs that were checked ("
                + expected.keySet() + "), so the merge was thrown away.");
            System.exit(2);
        }
        // The merger answers nothing, so the file is the only evidence it worked.
        if (!merged.isFile() || merged.length() == 0) {
            System.err.println("[merge] the merge of " + splits.getName() + " wrote no APK at " + merged.getPath());
            System.exit(1);
        }
        System.out.println("[merge] " + found.size() + " APKs from " + splits.getName() + " -> " + merged.getName() + ", "
            + merged.length() + " bytes");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1 << 16];
            for (int read; (read = input.read(buffer)) > 0; ) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
