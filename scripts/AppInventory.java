import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.Annotation;
import com.android.tools.smali.dexlib2.iface.AnnotationElement;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.iface.value.ArrayEncodedValue;
import com.android.tools.smali.dexlib2.iface.value.EncodedValue;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A sorted inventory of what an Android build talks to and what it reads about the device, written
 * so that two builds' inventories can be compared line by line.
 *
 * <pre>
 * java -Xmx6g -cp desktop-cli.jar scripts/AppInventory.java inventory app.apk out.txt [--detail]
 * java -cp desktop-cli.jar scripts/AppInventory.java diff old.txt new.txt out.txt
 * </pre>
 *
 * <p>Six sections, one "key = value" line per entry, sorted by key:
 *
 * <ul>
 *   <li>endpoints: every Retrofit service method's verb and relative path, with how many methods
 *       share it. An obfuscated build can rename Retrofit's annotation classes, so each verb's annotation is found
 *       where Retrofit itself reads it: the method that tests an annotation with instance-of and
 *       then names the verb in a string. The one tested that way with a verb as a string element is
 *       HTTP(method, path).
 *   <li>startup-tasks: the TAG_* constants of the startup task enum, the enum whose static
 *       initializer names the most of them, with how many places read each. The startup task
 *       runner takes one with each task it starts, so a constant read nowhere is a task this build
 *       doesn't start.
 *   <li>hosts: every host a string names in a URL (or a whole string that is a host name), with
 *       the packages whose code holds it.
 *   <li>transport: who opens connections: URL.openConnection, OkHttp, Cronet, HttpEngine and Volley.
 *   <li>sdk: the third-party packages that keep their names, with their class counts.
 *   <li>identifiers: who reads the advertising ID (and the app set ID), the Android ID, the install
 *       referrer and installer, and the device identifiers (IMEI and the rest, serial, MAC
 *       addresses, the DRM device ID, the Firebase installation ID, accounts, the installed apps).
 * </ul>
 *
 * <p>Code owners are named by package. A package the shrinker renamed (its first part a short
 * generated name such as t52) is written as ~, since that name changes with every build and the
 * comparison would show every line as moved. --detail adds the real class and method names under
 * each line, indented, for working on a patch. The comparison leaves those out.
 *
 * <p>It only reads. Nothing is loaded or run: the dex files are parsed with dexlib2, the opcode
 * table taken from each file's header.
 */
public class AppInventory {
    static final String FORMAT = "# AppInventory 1";
    static final List<String> SECTIONS = List.of("endpoints", "startup-tasks", "hosts", "transport", "sdk", "identifiers");
    static final Set<String> VERBS = Set.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");
    static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|wss?)://([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)");
    static final Pattern BARE_HOST = Pattern.compile(
            "^(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:com|net|org|io|it|co|cn|jp|tv|me|ly|gl)$");
    static final Pattern TAG = Pattern.compile("^TAG_[A-Z0-9_]+$");
    static final Pattern GENERATED = Pattern.compile("^[a-z]{1,3}[0-9]{0,2}$");
    // First package parts that are real names even though they're short.
    static final Set<String> ROOTS = Set.of("com", "org", "net", "io", "me", "de", "fr", "nl", "uk", "ru", "jp", "cn",
            "ch", "se", "us", "tv", "it", "co", "app", "dev", "bo", "j$");
    // Packages that are the platform, the language or the app itself, never an SDK.
    static final List<String> NOT_SDK = List.of("android/", "androidx/", "java/", "javax/", "kotlin/", "kotlinx/",
            "dalvik/", "j$/", "org/jetbrains/", "org/intellij/", "com/tumblr/", "org/json/", "org/xml/",
            "org/w3c/", "sun/", "libcore/");
    static final Set<String> HOST_NOT = Set.of("com", "org", "net", "io", "android", "androidx", "java", "javax",
            "kotlin", "kotlinx", "dalvik");

    /** A method call or field read that counts as reading an identifier, or as opening a connection. */
    record Rule(String section, String category, String owner, Set<String> names) {}

    static final List<Rule> RULES = List.of(
            new Rule("identifiers", "advertising-id", "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;",
                    Set.of("getAdvertisingIdInfo")),
            new Rule("identifiers", "advertising-id", "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;",
                    Set.of("getId", "isLimitAdTrackingEnabled")),
            new Rule("identifiers", "advertising-id", "Landroid/adservices/adid/AdIdManager;", Set.of("getAdId")),
            new Rule("identifiers", "advertising-id", "Landroidx/privacysandbox/ads/adservices/adid/AdIdManager;",
                    Set.of("getAdId")),
            new Rule("identifiers", "app-set-id", "Lcom/google/android/gms/appset/AppSetIdClient;", Set.of("getAppSetIdInfo")),
            new Rule("identifiers", "app-set-id", "Lcom/google/android/gms/appset/AppSet;", Set.of("getClient")),
            new Rule("identifiers", "install-referrer", "Lcom/android/installreferrer/api/InstallReferrerClient;",
                    Set.of("newBuilder", "startConnection", "getInstallReferrer")),
            new Rule("identifiers", "install-referrer", "Lcom/android/installreferrer/api/ReferrerDetails;",
                    Set.of("getInstallReferrer", "getReferrerClickTimestampSeconds", "getInstallBeginTimestampSeconds")),
            new Rule("identifiers", "installer", "Landroid/content/pm/PackageManager;",
                    Set.of("getInstallerPackageName", "getInstallSourceInfo")),
            new Rule("identifiers", "device-id", "Landroid/telephony/TelephonyManager;",
                    Set.of("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber", "getLine1Number")),
            new Rule("identifiers", "device-id", "Landroid/os/Build;", Set.of("getSerial", "SERIAL")),
            new Rule("identifiers", "device-id", "Landroid/net/wifi/WifiInfo;", Set.of("getMacAddress", "getBSSID")),
            new Rule("identifiers", "device-id", "Ljava/net/NetworkInterface;", Set.of("getHardwareAddress")),
            new Rule("identifiers", "device-id", "Landroid/bluetooth/BluetoothAdapter;", Set.of("getAddress")),
            new Rule("identifiers", "device-id", "Landroid/media/MediaDrm;", Set.of("getPropertyByteArray")),
            new Rule("identifiers", "device-id", "Lcom/google/firebase/installations/FirebaseInstallations;", Set.of("getId")),
            new Rule("identifiers", "accounts", "Landroid/accounts/AccountManager;", Set.of("getAccounts", "getAccountsByType")),
            new Rule("identifiers", "installed-apps", "Landroid/content/pm/PackageManager;",
                    Set.of("getInstalledPackages", "getInstalledApplications")),
            new Rule("transport", "", "Ljava/net/URL;", Set.of("openConnection")),
            new Rule("transport", "", "Lokhttp3/OkHttpClient;", Set.of("newCall", "newWebSocket")),
            new Rule("transport", "", "Lorg/chromium/net/CronetEngine;", Set.of("newUrlRequestBuilder", "openConnection")),
            new Rule("transport", "", "Lorg/chromium/net/ExperimentalCronetEngine;", Set.of("newUrlRequestBuilder")),
            new Rule("transport", "", "Landroid/net/http/HttpEngine;", Set.of("newUrlRequestBuilder")),
            new Rule("transport", "", "Lcom/android/volley/RequestQueue;", Set.of("add")));

    /** Strings that name an identifier source on their own: reflection, a service action, a provider. */
    static final Map<String, String> STRINGS = Map.of(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient", "advertising-id",
            "com.google.android.gms.ads.identifier.service.START", "advertising-id",
            "com.google.android.gms.ads.identifier.internal.IAdvertisingIdService", "advertising-id",
            "com.android.vending.INSTALL_REFERRER", "install-referrer",
            "com.google.android.finsky.externalreferrer.GetInstallReferrerService", "install-referrer",
            "com.android.installreferrer.api.InstallReferrerClient", "install-referrer");

    /** One line of a section: a count, the packages that hold it and, for --detail, the real names. */
    static final class Entry {
        int count;
        final TreeMap<String, Integer> owners = new TreeMap<>();
        final TreeSet<String> detail = new TreeSet<>();
    }

    final Map<String, TreeMap<String, Entry>> sections = new HashMap<>();
    // Retrofit: annotation type -> verb, the types Retrofit tests that name no verb, and the
    // annotations on interface methods, read once the verbs are known.
    final Map<String, String> verbTypes = new HashMap<>();
    final Set<String> testedTypes = new HashSet<>();
    final List<Object[]> serviceAnnotations = new ArrayList<>();
    // TAG_ name -> its enum field, and how often each enum constant field is read.
    final Map<String, String> tagFields = new TreeMap<>();
    final Map<String, Integer> constantReads = new HashMap<>();
    final Map<String, TreeSet<String>> constantReaders = new HashMap<>();
    int classCount, dexCount;

    Entry entry(String section, String key) {
        return sections.computeIfAbsent(section, s -> new TreeMap<>()).computeIfAbsent(key, k -> new Entry());
    }

    void hold(String section, String key, String ownerType, String where) {
        Entry e = entry(section, key);
        e.count++;
        e.owners.merge(ownerPackage(ownerType), 1, Integer::sum);
        e.detail.add(where);
    }

    static boolean generated(String part) { return GENERATED.matcher(part).matches() && !ROOTS.contains(part); }

    /**
     * "Lcom/foo/Bar;" is com/foo, and a class in a renamed package or in none is ~. A package of one
     * short part is renamed even when the part is a real top-level name: the shrinker hands out uk,
     * co and tv too, and no library keeps classes directly under com or uk.
     */
    static String ownerPackage(String type) {
        String internal = type.startsWith("L") && type.endsWith(";") ? type.substring(1, type.length() - 1) : type;
        int slash = internal.lastIndexOf('/');
        if (slash < 0) return "~";
        String pkg = internal.substring(0, slash);
        int first = pkg.indexOf('/');
        if (first < 0) return GENERATED.matcher(pkg).matches() ? "~" : pkg;
        return generated(pkg.substring(0, first)) ? "~" : pkg;
    }

    static String sdkPrefix(String pkg) {
        String[] parts = pkg.split("/");
        int depth = 2;
        if (pkg.startsWith("com/google/android/gms/")) depth = 5;
        else if (pkg.startsWith("com/google/android/") || pkg.startsWith("com/google/firebase/")) depth = 4;
        else if (pkg.startsWith("com/google/") || pkg.startsWith("com/facebook/") || pkg.startsWith("com/android/")
                || pkg.startsWith("org/chromium/") || pkg.startsWith("com/amazon/") || pkg.startsWith("com/microsoft/")) depth = 3;
        return String.join("/", java.util.Arrays.copyOf(parts, Math.min(depth, parts.length)));
    }

    static String simple(String type) {
        String internal = type.substring(1, type.length() - 1);
        return internal.substring(internal.lastIndexOf('/') + 1).replace('$', '.');
    }

    static String where(ClassDef c, Method m) { return c.getType() + "->" + m.getName(); }

    void strings(String value, ClassDef c, String where) {
        Matcher url = URL.matcher(value);
        while (url.find()) hold("hosts", url.group(1).toLowerCase(java.util.Locale.ROOT), c.getType(), where);
        if (BARE_HOST.matcher(value).matches() && !HOST_NOT.contains(value.substring(0, value.indexOf('.')))) {
            hold("hosts", value, c.getType(), where);
        }
        String category = STRINGS.get(value);
        if (category != null) hold("identifiers", category + " \"" + value + "\"", c.getType(), where);
        if (value.contains("InstallReferrerProvider")) hold("identifiers", "install-referrer \"" + value + "\"", c.getType(), where);
    }

    void encoded(EncodedValue value, ClassDef c, String where) {
        if (value instanceof StringEncodedValue s) strings(s.getValue(), c, where);
        else if (value instanceof ArrayEncodedValue a) for (EncodedValue v : a.getValue()) encoded(v, c, where);
    }

    void scan(ClassDef c) {
        classCount++;
        String pkg = ownerPackage(c.getType());
        if (!pkg.equals("~") && NOT_SDK.stream().noneMatch(p -> (pkg + "/").startsWith(p))) {
            entry("sdk", sdkPrefix(pkg)).count++;
        }
        for (Field f : c.getFields()) {
            if (f.getInitialValue() != null) encoded(f.getInitialValue(), c, c.getType() + "->" + f.getName());
        }
        boolean service = AccessFlags.INTERFACE.isSet(c.getAccessFlags());
        for (Method m : c.getMethods()) {
            String at = where(c, m);
            for (Annotation a : m.getAnnotations()) {
                for (AnnotationElement e : a.getElements()) encoded(e.getValue(), c, at);
                if (service) serviceAnnotations.add(new Object[] {a, c.getType(), at});
            }
            MethodImplementation impl = m.getImplementation();
            if (impl == null) continue;
            boolean clinit = m.getName().equals("<clinit>");
            String pendingType = null, pendingTag = null;
            Map<String, String> verbsHere = new HashMap<>();
            Set<String> testedHere = new HashSet<>();
            boolean androidId = false;
            int secureReads = 0;
            for (Instruction i : impl.getInstructions()) {
                Opcode op = i.getOpcode();
                if (!(i instanceof ReferenceInstruction ri)) continue;
                Reference ref;
                try {
                    ref = ri.getReference();
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (ref instanceof StringReference s) {
                    String value = s.getString();
                    strings(value, c, at);
                    if (pendingType != null && VERBS.contains(value)) {
                        verbsHere.putIfAbsent(pendingType, value);
                        pendingType = null;
                    }
                    if (clinit && TAG.matcher(value).matches()) pendingTag = value;
                    if (value.equals("android_id")) androidId = true;
                } else if (ref instanceof TypeReference t && op == Opcode.INSTANCE_OF) {
                    pendingType = t.getType();
                    testedHere.add(pendingType);
                } else if (ref instanceof FieldReference f) {
                    // An enum constant: the class's own field, of the class's own type.
                    if (op == Opcode.SPUT_OBJECT && clinit && pendingTag != null && f.getDefiningClass().equals(c.getType())
                            && f.getType().equals(c.getType())) {
                        tagFields.putIfAbsent(pendingTag, f.getDefiningClass() + "->" + f.getName());
                        pendingTag = null;
                    } else if (op == Opcode.SGET_OBJECT && f.getType().equals(f.getDefiningClass())
                            && !f.getDefiningClass().equals(c.getType())) {
                        String key = f.getDefiningClass() + "->" + f.getName();
                        constantReads.merge(key, 1, Integer::sum);
                        constantReaders.computeIfAbsent(key, k -> new TreeSet<>()).add(ownerPackage(c.getType()) + " " + at);
                    }
                    if (op.name().startsWith("SGET")) rule(f.getDefiningClass(), f.getName(), c, at);
                } else if (ref instanceof MethodReference r) {
                    rule(r.getDefiningClass(), r.getName(), c, at);
                    if (r.getName().equals("getString") && (r.getDefiningClass().equals("Landroid/provider/Settings$Secure;")
                            || r.getDefiningClass().equals("Landroid/provider/Settings$System;"))) secureReads++;
                }
            }
            if (androidId && secureReads > 0) hold("identifiers", "android-id Settings.Secure.getString(\"android_id\")", c.getType(), at);
            // Retrofit's own annotation reader tests each verb's annotation and names the verb next,
            // and a method that does that for most of the verbs is taken to be it.
            if (new HashSet<>(verbsHere.values()).size() >= 4) {
                verbsHere.forEach(verbTypes::putIfAbsent);
                testedTypes.addAll(testedHere);
            }
        }
    }

    void rule(String owner, String name, ClassDef c, String at) {
        for (Rule r : RULES) {
            if (r.owner().equals(owner) && r.names().contains(name)) {
                String label = simple(owner) + "." + name;
                hold(r.section(), r.category().isEmpty() ? label : r.category() + " " + label, c.getType(), at);
            }
        }
    }

    /** The verbs are known once every class has been read, so the service annotations wait until then. */
    void endpoints() {
        for (Object[] held : serviceAnnotations) {
            Annotation a = (Annotation) held[0];
            String type = a.getType();
            String verb = verbTypes.get(type);
            String path = null;
            List<String> values = new ArrayList<>();
            for (AnnotationElement e : a.getElements()) {
                if (e.getValue() instanceof StringEncodedValue s) values.add(s.getValue());
            }
            if (verb != null) {
                if (values.size() > 1) continue;
                path = values.isEmpty() ? "" : values.get(0);
            } else if (testedTypes.contains(type) && !verbTypes.isEmpty()) {
                // HTTP(method, path, hasBody): the one tested annotation with a verb as a value.
                for (String v : values) if (v.matches("^[A-Z]+$")) verb = v;
                if (verb == null) continue;
                path = "";
                for (String v : values) if (!v.equals(verb)) path = v;
            } else {
                continue;
            }
            String key = verb + " " + (path.isEmpty() ? "(the call's own @Url)" : path);
            Entry e = entry("endpoints", key);
            e.count++;
            e.owners.merge(ownerPackage((String) held[1]), 1, Integer::sum);
            e.detail.add((String) held[2]);
        }
        // Other enums name TAG_ constants too (the ads SDK's child-directed treatment, for one). The
        // startup task enum is the one with the most of them.
        Map<String, Integer> perEnum = new HashMap<>();
        for (String field : tagFields.values()) perEnum.merge(field.substring(0, field.indexOf("->")), 1, Integer::sum);
        String tasks = perEnum.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (Map.Entry<String, String> tag : tagFields.entrySet()) {
            if (!tag.getValue().startsWith(tasks + "->")) continue;
            Entry e = entry("startup-tasks", tag.getKey());
            e.count = constantReads.getOrDefault(tag.getValue(), 0);
            e.detail.add("constant " + tag.getValue());
            for (String reader : constantReaders.getOrDefault(tag.getValue(), new TreeSet<>())) {
                String[] parts = reader.split(" ", 2);
                e.owners.merge(parts[0], 1, Integer::sum);
                e.detail.add(parts[1]);
            }
        }
    }

    static String owners(Entry e) {
        List<String> parts = new ArrayList<>();
        e.owners.forEach((pkg, n) -> parts.add(n == 1 ? pkg : pkg + " x" + n));
        return String.join(", ", parts);
    }

    static String value(String section, Entry e) {
        switch (section) {
            case "endpoints": return e.count == 1 ? "1 method" : e.count + " methods";
            case "startup-tasks": return e.count == 1 ? "read in 1 place" : "read in " + e.count + " places";
            case "sdk": return e.count == 1 ? "1 class" : e.count + " classes";
            default: return owners(e);
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : digest.digest()) b.append(String.format("%02x", x));
        return b.toString();
    }

    static void inventory(File apk, File out, boolean detail) throws Exception {
        AppInventory inv = new AppInventory();
        // The opcode table comes from each file's own header, as in HostReferences.
        MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(apk, null);
        List<String> names = new ArrayList<>(container.getDexEntryNames());
        Collections.sort(names);
        for (String name : names) {
            inv.dexCount++;
            for (ClassDef c : container.getEntry(name).getDexFile().getClasses()) inv.scan(c);
        }
        inv.endpoints();
        if (inv.verbTypes.isEmpty()) System.err.println("[inventory] no Retrofit annotation reader was found, so endpoints is empty");
        List<String> lines = new ArrayList<>();
        lines.add(FORMAT);
        lines.add("apk = " + apk.getName());
        lines.add("sha256 = " + sha256(apk));
        lines.add("dex = " + inv.dexCount + " files, " + inv.classCount + " classes");
        for (String section : SECTIONS) {
            TreeMap<String, Entry> entries = inv.sections.getOrDefault(section, new TreeMap<>());
            lines.add("");
            lines.add("[" + section + "] " + entries.size());
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                lines.add(e.getKey() + " = " + value(section, e.getValue()));
                if (detail) for (String d : e.getValue().detail) lines.add("    " + d);
            }
        }
        Files.write(out.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        StringBuilder summary = new StringBuilder("[inventory] " + apk.getName() + ":");
        for (String section : SECTIONS) summary.append(' ').append(section).append(' ')
                .append(inv.sections.getOrDefault(section, new TreeMap<>()).size());
        System.out.println(summary);
    }

    /** header lines, then section -> key -> value, detail lines left out. */
    static Map<String, TreeMap<String, String>> read(File file, List<String> header) throws Exception {
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.get(0).equals(FORMAT)) throw new IllegalArgumentException(file + " isn't an inventory this reads");
        Map<String, TreeMap<String, String>> sections = new TreeMap<>();
        String section = null;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty() || line.startsWith(" ")) continue;
            if (line.startsWith("[")) {
                section = line.substring(1, line.indexOf(']'));
                sections.put(section, new TreeMap<>());
                continue;
            }
            int split = line.indexOf(" = ");
            if (split < 0) throw new IllegalArgumentException(file + " has a line with no \" = \": " + line);
            if (section == null) header.add(line);
            else sections.get(section).put(line.substring(0, split), line.substring(split + 3));
        }
        return sections;
    }

    static void diff(File before, File after, File out) throws Exception {
        List<String> oldHeader = new ArrayList<>(), newHeader = new ArrayList<>();
        Map<String, TreeMap<String, String>> old = read(before, oldHeader), now = read(after, newHeader);
        List<String> lines = new ArrayList<>();
        lines.add("# AppInventory diff 1");
        for (String h : oldHeader) lines.add("- " + h);
        for (String h : newHeader) lines.add("+ " + h);
        int changes = 0;
        for (String section : SECTIONS) {
            TreeMap<String, String> a = old.getOrDefault(section, new TreeMap<>()), b = now.getOrDefault(section, new TreeMap<>());
            List<String> body = new ArrayList<>();
            int added = 0, removed = 0, changed = 0;
            TreeSet<String> keys = new TreeSet<>(a.keySet());
            keys.addAll(b.keySet());
            for (String key : keys) {
                String x = a.get(key), y = b.get(key);
                if (x == null) { body.add("+ " + key + " = " + y); added++; }
                else if (y == null) { body.add("- " + key + " = " + x); removed++; }
                else if (!x.equals(y)) { body.add("~ " + key + " = " + x + " -> " + y); changed++; }
            }
            lines.add("");
            lines.add("[" + section + "] " + a.size() + " -> " + b.size() + ": " + added + " added, " + removed
                    + " removed, " + changed + " changed");
            lines.addAll(body);
            changes += added + removed + changed;
        }
        Files.write(out.toPath(), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("[inventory] " + changes + " lines differ between " + before.getName() + " and " + after.getName());
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("inventory")) {
            inventory(new File(args[1]), new File(args[2]), args.length > 3 && args[3].equals("--detail"));
        } else if (args.length == 4 && args[0].equals("diff")) {
            diff(new File(args[1]), new File(args[2]), new File(args[3]));
        } else {
            System.err.println("usage: AppInventory inventory <apk> <out.txt> [--detail] | diff <old.txt> <new.txt> <out.txt>");
            System.exit(2);
        }
    }
}
