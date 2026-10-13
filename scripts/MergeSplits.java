import app.morphe.patcher.apk.ApkMerger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;

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
 * <p>The arguments are the CLI's own (PatchCommand, 1.17.0): {@code merge$default} with every
 * default but the last flag, which it passes as false, the same value the default gives it.
 *
 *   java -cp &lt;cli jar&gt; MergeSplits.java &lt;split directory&gt; &lt;merged.apk&gt;
 */
public final class MergeSplits {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: MergeSplits <split directory> <merged.apk>");
            System.exit(2);
        }
        File splits = new File(args[0]);
        File merged = new File(args[1]);
        if (!splits.isDirectory()) {
            System.err.println("[merge] " + splits.getPath() + " is not a directory of split APKs. A bundle is unpacked "
                + "and its member names checked first (Expand-SplitBundle), and the merger reads that directory.");
            System.exit(2);
        }
        File[] entries = splits.listFiles();
        if (entries == null) {
            System.err.println("[merge] could not list " + splits.getPath());
            System.exit(2);
        }
        Arrays.sort(entries);
        int apks = 0;
        for (File entry : entries) {
            if (!Files.isRegularFile(entry.toPath(), LinkOption.NOFOLLOW_LINKS) || !entry.getName().endsWith(".apk")) {
                System.err.println("[merge] " + splits.getPath() + " holds " + entry.getName()
                    + ", which is not a split APK; the merger would read more than was checked.");
                System.exit(2);
            }
            apks++;
        }
        if (apks == 0) {
            System.err.println("[merge] " + splits.getPath() + " holds no split APK.");
            System.exit(2);
        }
        new ApkMerger().merge(splits, merged, true, null, true, null, false);
        // The merger answers nothing, so the file is the only evidence it worked.
        if (!merged.isFile() || merged.length() == 0) {
            System.err.println("[merge] the merge of " + splits.getName() + " wrote no APK at " + merged.getPath());
            System.exit(1);
        }
        System.out.println("[merge] " + apks + " APKs from " + splits.getName() + " -> " + merged.getName() + ", "
            + merged.length() + " bytes");
    }
}
