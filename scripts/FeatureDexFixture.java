import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small compiled outputs with safe registers but incorrect family installation or mutation contracts. */
public final class FeatureDexFixture {
    private static final String BASE = "Lapp/hushtumblr/extension/tumblr/";
    private static final String STATUS = BASE + "settings/SettingsStatus;";
    private static final String ENTRY = BASE + "settings/SettingsEntry;";
    private static final String UTILS = "Lapp/hushtumblr/extension/shared/Utils;";
    // Tumblr's application inherits onCreate from com.tumblr.CoreApp, and its launcher inherits
    // onNewIntent from an AndroidX activity, so the settings hooks land in a superclass of each.
    private static final String APP = "Lcom/tumblr/App;";
    private static final String CORE_APP = "Lcom/tumblr/CoreApp;";
    private static final String ACTIVITY = "Lcom/tumblr/ui/activity/JumpoffActivity;";
    private static final String COMPONENT_ACTIVITY = "Lfixture/ComponentActivity;";
    private static final Map<String, String> SUPERCLASSES = Map.of(APP, CORE_APP, ACTIVITY, COMPONENT_ACTIVITY);
    private static final String VOID = "V";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String LIST = "Ljava/util/List;";
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final String INTENT = "Landroid/content/Intent;";
    private static final String STRING = "Ljava/lang/String;";
    private static final String MANAGER = "Landroid/content/pm/ShortcutManager;";
    private static final String PUBLISHER = "Lfixture/ShortcutPublisher;";
    private static final String INFO = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;";
    private static final String ADVERTISING = BASE + "privacy/AdvertisingId;";
    private static final String HEADERS = "Lfixture/FirebaseHeaders;";
    private static final String PUSH = BASE + "notifications/PushNotifications;";
    private static final String URL = "Ljava/net/URL;";
    private static final String HTTP = "Ljava/net/HttpURLConnection;";
    private static final String CONNECTION = "Ljava/net/URLConnection;";
    private static final List<String> SHORTCUTS = List.of("pushDynamicShortcut", "addDynamicShortcuts", "setDynamicShortcuts", "updateShortcuts", "removeAllDynamicShortcuts");
    private static final Map<String, String[]> FAMILIES = new LinkedHashMap<>();
    private static final Map<String, Boolean> FLAGS = new LinkedHashMap<>();

    private static ImmutableMethodReference ref(String type, String name, String result, String... parameters) {
        return new ImmutableMethodReference(type, name, Arrays.asList(parameters), result);
    }

    private static Instruction invoke(Opcode opcode, ImmutableMethodReference ref, int... registers) {
        int[] words = new int[5];
        System.arraycopy(registers, 0, words, 0, registers.length);
        return new ImmutableInstruction35c(opcode, registers.length, words[0], words[1], words[2], words[3], words[4], ref);
    }

    private static Method method(String owner, String name, String result, boolean isStatic, int registers, List<Instruction> body, String... parameters) {
        List<ImmutableMethodParameter> types = new ArrayList<>();
        for (String parameter : parameters) types.add(new ImmutableMethodParameter(parameter, Set.of(), null));
        return new ImmutableMethod(owner, name, types, result,
                AccessFlags.PUBLIC.getValue() | (isStatic ? AccessFlags.STATIC.getValue() : 0), Set.of(), Set.of(),
                new ImmutableMethodImplementation(registers, body, List.of(), List.of()));
    }

    private static ClassDef type(String owner, List<Method> methods) {
        return new ImmutableClassDef(owner, AccessFlags.PUBLIC.getValue(), SUPERCLASSES.getOrDefault(owner, OBJECT), List.of(), null,
                Set.of(), List.<ImmutableField>of(), methods);
    }

    private static Instruction end() { return new ImmutableInstruction10x(Opcode.RETURN_VOID); }
    private static Instruction literal(String text, int register) {
        return new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(text));
    }

    private static Map<String, List<Method>> hosts(boolean patched) {
        Map<String, List<Method>> classes = new LinkedHashMap<>();
        List<Instruction> app = new ArrayList<>();
        if (patched) {
            app.add(invoke(Opcode.INVOKE_STATIC, ref(UTILS, "setContext", VOID, CONTEXT), 0));
            app.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onApplicationCreate", VOID, CONTEXT), 0));
        }
        app.add(end());
        classes.put(APP, new ArrayList<>());
        classes.put(CORE_APP, new ArrayList<>(List.of(method(CORE_APP, "onCreate", VOID, false, 1, app))));
        List<Instruction> create = new ArrayList<>();
        if (patched) create.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onActivityCreate", VOID, "Landroid/app/Activity;"), 0));
        create.add(end());
        List<Instruction> intent = new ArrayList<>();
        if (patched) intent.add(invoke(Opcode.INVOKE_STATIC, ref(ENTRY, "onNewIntent", VOID, "Landroid/app/Activity;", INTENT), 0, 1));
        intent.add(end());
        classes.put(ACTIVITY, new ArrayList<>(List.of(method(ACTIVITY, "onCreate", VOID, false, 2, create, "Landroid/os/Bundle;"))));
        classes.put(COMPONENT_ACTIVITY, new ArrayList<>(List.of(method(COMPONENT_ACTIVITY, "onNewIntent", VOID, false, 2, intent, INTENT))));
        shortcuts(classes, patched, "good");
        return classes;
    }

    private static void extensions(Map<String, List<Method>> classes) {
        List<Method> flags = new ArrayList<>();
        FLAGS.forEach((name, value) -> flags.add(method(STATUS, name, "Z", true, 1,
                List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, value ? 1 : 0), new ImmutableInstruction11x(Opcode.RETURN, 0)))));
        classes.put(STATUS, flags);
        classes.put(UTILS, List.of(method(UTILS, "setContext", VOID, true, 1, List.of(end()), CONTEXT),
                method(UTILS, "settingsReady", "Z", true, 1,
                        List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 1), new ImmutableInstruction11x(Opcode.RETURN, 0)))));
        List<Method> entry = new ArrayList<>(List.of(
                method(ENTRY, "onApplicationCreate", VOID, true, 1, List.of(end()), CONTEXT),
                method(ENTRY, "onActivityCreate", VOID, true, 1, List.of(end()), "Landroid/app/Activity;"),
                method(ENTRY, "onNewIntent", VOID, true, 2, List.of(end()), "Landroid/app/Activity;", INTENT)));
        for (Method m : classes.getOrDefault(ENTRY, List.of())) if (SHORTCUTS.contains(m.getName())) entry.add(m);
        classes.put(ENTRY, entry);
    }

    private static Instruction readiness() { return invoke(Opcode.INVOKE_STATIC, ref(UTILS, "settingsReady", "Z")); }

    private static void shortcuts(Map<String, List<Method>> classes, boolean patched, String variant) {
        List<Instruction> publisher = new ArrayList<>();
        List<Method> wrappers = new ArrayList<>();
        for (String name : SHORTCUTS) {
            String[] parameters = name.equals("removeAllDynamicShortcuts") ? new String[]{}
                    : new String[]{name.equals("pushDynamicShortcut") ? "Landroid/content/pm/ShortcutInfo;" : LIST};
            String result = name.equals("pushDynamicShortcut") || parameters.length == 0 ? VOID : "Z";
            int[] hostArgs = parameters.length == 0 ? new int[]{1} : new int[]{1, name.equals("pushDynamicShortcut") ? 2 : 3};
            List<String> wrapperParameters = new ArrayList<>(List.of(MANAGER)); wrapperParameters.addAll(Arrays.asList(parameters));
            publisher.add(invoke(patched ? Opcode.INVOKE_STATIC : Opcode.INVOKE_VIRTUAL,
                    patched ? ref(ENTRY, name, result, wrapperParameters.toArray(String[]::new)) : ref(MANAGER, name, result, parameters), hostArgs));
            if (!result.equals(VOID)) publisher.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
            if (!patched) continue;
            List<Instruction> body = new ArrayList<>();
            if (variant.equals(name)) {
                if (result.equals(VOID)) body.add(end());
                else { body.add(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0)); body.add(new ImmutableInstruction11x(Opcode.RETURN, 0)); }
            }
            if (variant.equals("branch-good") && name.equals("pushDynamicShortcut")) {
                body.add(new ImmutableInstruction10t(Opcode.GOTO, 2)); body.add(end());
            }
            body.add(invoke(Opcode.INVOKE_VIRTUAL, ref(MANAGER, name, result, parameters), parameters.length == 0 ? new int[]{1} : new int[]{1, 2}));
            if (!result.equals(VOID)) body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
            body.add(result.equals(VOID) ? end() : new ImmutableInstruction11x(Opcode.RETURN, 0));
            wrappers.add(method(ENTRY, name, result, true, wrapperParameters.size() + 1, body, wrapperParameters.toArray(String[]::new)));
        }
        publisher.add(end());
        classes.put(PUBLISHER, List.of(method(PUBLISHER, "publish", VOID, true, 4, publisher, MANAGER, "Landroid/content/pm/ShortcutInfo;", LIST)));
        if (patched) classes.put(ENTRY, wrappers);
    }

    private static void enable(String name) {
        FLAGS.put(name, true);
        for (String cap : capabilities(name)) FLAGS.put(cap, true);
    }

    /** A family's capability flags; none for a manifest-only family, whose contract column is empty. */
    private static List<String> capabilities(String family) {
        String column = FAMILIES.get(family)[1];
        return column.isEmpty() ? List.of() : List.of(column.split(","));
    }

    private static void reset() { FLAGS.replaceAll((key, value) -> false); }

    private static void write(File root, String name, Map<String, List<Method>> classes, boolean patched, String... selected) throws Exception {
        if (patched) extensions(classes);
        if (name.equals("feature-no-status")) classes.remove(STATUS);
        if (name.equals("feature-nonboolean-status")) {
            List<Method> status = new ArrayList<>(classes.get(STATUS));
            status.set(0, method(STATUS, status.get(0).getName(), "Z", true, 1,
                    List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 2), new ImmutableInstruction11x(Opcode.RETURN, 0))));
            classes.put(STATUS, status);
        }
        List<ClassDef> defs = new ArrayList<>();
        classes.forEach((owner, methods) -> defs.add(type(owner, methods)));
        DexPool.writeTo(new File(root, name + ".dex").getPath(), new ImmutableDexFile(Opcodes.getDefault(), defs));
        if (patched) {
            List<String> names = new ArrayList<>(List.of("HushTumblr settings"));
            for (String family : selected) names.add(FAMILIES.get(family)[0]);
            Files.write(new File(root, name + ".selected").toPath(), names, StandardCharsets.UTF_8);
        }
    }

    /**
     * Google's advertising ID getters, with Hide advertising ID's answer hooks unless clean. Each
     * getter is v0 then this, and its hook takes the answer in v0 and hands back the one returned.
     */
    private static void advertisingId(Map<String, List<Method>> classes, String variant) {
        boolean hooked = !variant.equals("clean");
        ImmutableMethodReference idHook = ref(ADVERTISING, "id", STRING, STRING);
        List<Instruction> id = new ArrayList<>(List.of(literal(variant.equals("changed-original") ? "fixture-other-id" : "fixture-ad-id", 0)));
        List<Instruction> limit = new ArrayList<>(List.of(new ImmutableInstruction11n(Opcode.CONST_4, 0, 0)));
        if (hooked && !variant.equals("missing")) {
            for (int n = 0; n < (variant.equals("duplicate") ? 2 : 1); n++) {
                // The wrong register is this: still an object, so only the contract can see it.
                id.add(invoke(Opcode.INVOKE_STATIC, idHook, variant.equals("wrong-register") ? 1 : 0));
                id.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
            }
        }
        if (hooked) {
            limit.add(invoke(Opcode.INVOKE_STATIC, ref(ADVERTISING, "limitTracking", "Z", "Z"), 0));
            limit.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0));
        }
        id.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        limit.add(new ImmutableInstruction11x(Opcode.RETURN, 0));
        List<Method> info = new ArrayList<>(List.of(method(INFO, "getId", STRING, false, 2, id),
                method(INFO, "isLimitAdTrackingEnabled", "Z", false, 2, limit)));
        if (variant.equals("misrouted")) info.add(method(INFO, "unrelated", VOID, true, 1,
                List.of(literal("fixture-ad-id", 0), invoke(Opcode.INVOKE_STATIC, idHook, 0), new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), end())));
        classes.put(INFO, info);
        if (!hooked || variant.equals("missing-callee")) return;
        // Each hook is v0 then its parameter, and answers the parameter once settings are ready.
        List<Instruction> answer = new ArrayList<>();
        if (variant.equals("unreachable-control")) answer.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1));
        answer.addAll(List.of(readiness(), new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1)));
        List<Instruction> flag = List.of(readiness(), new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), new ImmutableInstruction11x(Opcode.RETURN, 1));
        classes.put(ADVERTISING, List.of(method(ADVERTISING, "id", STRING, true, 2, answer, STRING),
                method(ADVERTISING, "limitTracking", "Z", true, 2, flag, "Z")));
    }

    /**
     * Firebase's request header builder the way Tumblr's build has it: the certificate header's name,
     * a call to the builder's own fingerprint method, that call's answer and the write, with Fix push
     * notifications' hook between the answer and the write unless clean. The builder is v0 the
     * connection, v1 a header name and v2 its value, then this, the URL and the API key.
     */
    private static void firebase(Map<String, List<Method>> classes, String variant) {
        ImmutableMethodReference write = ref(HTTP, "addRequestProperty", VOID, STRING, STRING);
        ImmutableMethodReference hook = ref(PUSH, "certificateHeader", STRING, CONNECTION, STRING);
        boolean hooked = !variant.equals("clean") && !variant.equals("missing");
        List<Instruction> body = new ArrayList<>(List.of(
                invoke(Opcode.INVOKE_VIRTUAL, ref(URL, "openConnection", CONNECTION), 4),
                new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                new ImmutableInstruction21c(Opcode.CHECK_CAST, 0, new ImmutableTypeReference(HTTP)),
                literal("X-Android-Package", 1), literal("com.tumblr", 2), invoke(Opcode.INVOKE_VIRTUAL, write, 0, 1, 2),
                literal("X-Android-Cert", 1)));
        // Early is where the hook went in a build that wrote the fingerprint straight after the name.
        if (hooked && variant.equals("early")) {
            body.add(invoke(Opcode.INVOKE_STATIC, hook, 0, 2));
            body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        }
        body.add(invoke(Opcode.INVOKE_VIRTUAL, ref(HEADERS, "fingerprint", STRING), 3));
        body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        if (hooked && !variant.equals("early")) {
            // The wrong value is the header's name: still a String, so only the contract can see it.
            body.add(invoke(Opcode.INVOKE_STATIC, hook, 0, variant.equals("wrong-value") ? 1 : 2));
            body.add(new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2));
        }
        body.add(invoke(Opcode.INVOKE_VIRTUAL, write, 0, 1, 2));
        body.add(literal("x-goog-api-key", 1));
        body.add(invoke(Opcode.INVOKE_VIRTUAL, write, 0, 1, 5));
        body.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
        classes.put(HEADERS, List.of(
                method(HEADERS, "fingerprint", STRING, false, 2, List.of(literal("Could not get fingerprint hash for package: ", 0),
                        new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))),
                method(HEADERS, "headers", HTTP, false, 6, body, URL, STRING)));
        if (!hooked) return;
        classes.put(PUSH, List.of(method(PUSH, "certificateHeader", STRING, true, 3,
                List.of(readiness(), new ImmutableInstruction11x(Opcode.MOVE_RESULT, 0), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)),
                CONNECTION, STRING)));
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Files.createDirectories(root.toPath());
        for (String line : Files.readAllLines(new File(args[1]).toPath(), StandardCharsets.UTF_8)) if (line.startsWith("family|")) {
            String[] values = line.trim().split("\\|", -1);
            FAMILIES.put(values[1], new String[]{values[2], values[3]});
            FLAGS.put(values[1], false);
            for (String cap : capabilities(values[1])) FLAGS.put(cap, false);
        }
        Map<String, List<Method>> clean = hosts(false);
        advertisingId(clean, "clean");
        firebase(clean, "clean");
        write(root, "feature-clean", clean, false);
        reset();
        Map<String, List<Method>> settings = new LinkedHashMap<>(clean); settings.putAll(hosts(true));
        write(root, "feature-unselected", settings, true);
        write(root, "feature-internal-dependencies", new LinkedHashMap<>(settings), true);
        Files.write(new File(root, "feature-internal-dependencies.selected").toPath(),
                List.of("HushTumblr settings", "BytecodePatch", "ResourcePatch"), StandardCharsets.UTF_8);
        write(root, "feature-unknown-public", new LinkedHashMap<>(settings), true);
        Files.write(new File(root, "feature-unknown-public.selected").toPath(),
                List.of("HushTumblr settings", "Hide imaginary posts"), StandardCharsets.UTF_8);
        write(root, "feature-no-status", new LinkedHashMap<>(settings), true);
        write(root, "feature-nonboolean-status", new LinkedHashMap<>(settings), true);
        reset();
        write(root, "feature-status-copy", new LinkedHashMap<>(Map.of(STATUS, settings.get(STATUS))), false);
        for (String family : FAMILIES.keySet()) {
            reset(); enable(family);
            // With no bytecode of its own, a manifest-only family installed and selected is complete,
            // and the same flag installed without the patch selected is the failure to catch.
            boolean manifestOnly = capabilities(family).isEmpty();
            write(root, (manifestOnly ? "feature-installed-only-" : "feature-installed-missing-") + family,
                    new LinkedHashMap<>(settings), true, family);
            if (manifestOnly) write(root, "feature-installed-unselected-" + family, new LinkedHashMap<>(settings), true);
            reset();
            write(root, "feature-selected-missing-" + family, new LinkedHashMap<>(settings), true, family);
        }
        for (String variant : List.of("good", "missing", "duplicate", "misrouted", "wrong-register", "changed-original",
                "false-capability", "unselected-call", "missing-callee", "unreachable-control")) {
            reset();
            boolean unselected = variant.equals("unselected-call");
            if (!unselected) enable("hideAdvertisingId");
            if (variant.equals("false-capability")) FLAGS.put("advertisingId", false);
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            advertisingId(classes, variant);
            write(root, "feature-answers-" + variant, classes, true, unselected ? new String[]{} : new String[]{"hideAdvertisingId"});
        }
        for (String variant : List.of("good", "missing", "early", "wrong-value", "false-capability")) {
            reset(); enable("fixPushNotifications");
            if (variant.equals("false-capability")) FLAGS.put("firebaseCertificate", false);
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            firebase(classes, variant);
            write(root, "feature-firebase-" + variant, classes, true, "fixPushNotifications");
        }
        for (String variant : SHORTCUTS) {
            reset();
            Map<String, List<Method>> classes = new LinkedHashMap<>(settings);
            shortcuts(classes, true, variant);
            write(root, "feature-shortcuts-unreachable-" + variant, classes, true);
        }
        reset();
        Map<String, List<Method>> branchingShortcut = new LinkedHashMap<>(settings);
        shortcuts(branchingShortcut, true, "branch-good");
        write(root, "feature-shortcuts-branch-good", branchingShortcut, true);
    }
}
