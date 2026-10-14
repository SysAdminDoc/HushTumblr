import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableField;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.*;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The dex delta's fixtures. A clean host, the two extension dex files of the bundle it was
 * patched with, a build two patches wrote into ("Fixture patch" and "Other patch"), the build of
 * the first alone, a pair whose one reshaped class hashes differently for each selection, builds
 * that each change one more thing nothing accounts for, and copies of Tumblr classes for a second
 * dex entry. The rebuild
 * re-encodes one host string, and each patched build answers its flags and the bundle's Version
 * ahead of the bundle's bodies, as the patcher does.
 *
 * <p>With "extract", the classes under the extension's package of a dex, written as a bundle's
 * extension carries them, so another fixture's patched build can stand as its own bundle.
 */
public final class DexDeltaFixture {
    static final String OWN = "Lapp/hushtumblr/extension/";
    static final String STATUS = OWN + "tumblr/settings/SettingsStatus;", UTILS = OWN + "shared/Utils;";
    static final String BRIDGE = OWN + "tumblr/fixture/FixtureBridge;", FACE = OWN + "tumblr/fixture/FixtureFace;";
    static final String PLAIN = OWN + "tumblr/fixture/Plain;", ROGUE = OWN + "tumblr/fixture/Rogue;";
    static final String HOST = "Lcom/fixture/Host;", MODEL = "Lcom/fixture/Model;", BASE = "Lcom/fixture/Base;", STRAY = "Lcom/fixture/Stray;";
    static final String OBJECT = "Ljava/lang/Object;", STRING = "Ljava/lang/String;", VERSION = "9.9.9";
    static final int PUBLIC = AccessFlags.PUBLIC.getValue(), STATIC = AccessFlags.STATIC.getValue(), PRIVATE = AccessFlags.PRIVATE.getValue();
    static final String SELECT_BOTH = "Fixture patch,Other patch", SELECT_FIXTURE = "Fixture patch";
    static final String UNLISTED = "dex delta: no compiled contract validates this and no selected section of the dex delta allowlist lists it: ";

    static Instruction literal(int register, int value) { return new ImmutableInstruction11n(Opcode.CONST_4, register, value); }
    static Instruction string(int register, String text) { return new ImmutableInstruction21c(Opcode.CONST_STRING, register, new ImmutableStringReference(text)); }
    static Instruction jumbo(int register, String text) { return new ImmutableInstruction31c(Opcode.CONST_STRING_JUMBO, register, new ImmutableStringReference(text)); }
    static Instruction returns(int register) { return new ImmutableInstruction11x(Opcode.RETURN, register); }
    static Instruction returnsObject(int register) { return new ImmutableInstruction11x(Opcode.RETURN_OBJECT, register); }
    static Instruction end() { return new ImmutableInstruction10x(Opcode.RETURN_VOID); }
    static List<Instruction> code(Instruction... instructions) { return List.of(instructions); }
    static List<Instruction> join(List<Instruction> first, List<Instruction> then) {
        List<Instruction> out = new ArrayList<>(first);
        out.addAll(then);
        return out;
    }
    static Method method(String owner, String name, String result, int flags, int registers, List<Instruction> body) {
        return new ImmutableMethod(owner, name, List.of(), result, flags, Set.of(), Set.of(),
                new ImmutableMethodImplementation(registers, body, List.of(), List.of()));
    }
    static ClassDef clazz(String type, int flags, String parent, List<String> interfaces, List<? extends Field> fields, List<Method> methods) {
        return new ImmutableClassDef(type, flags, parent, interfaces, null, Set.of(), fields, methods);
    }
    static ImmutableField field(String owner, String name, String type, int flags) {
        return new ImmutableField(owner, name, type, flags, null, Set.of(), Set.of());
    }

    /**
     * Tumblr's side: clean for no traits. "patched" is the rebuild's re-encoded string, "fixture"
     * and "other" what each patch writes, "helper-leak" Other patch's helper alone, and the rest
     * one fault each.
     */
    static List<ClassDef> host(Set<String> traits) {
        List<Field> hostFields = new ArrayList<>();
        if (traits.contains("other")) hostFields.add(field(HOST, "hushFlag", "Z", PUBLIC | STATIC));
        List<Method> methods = new ArrayList<>();
        boolean runBody = traits.contains("run-body");
        methods.add(method(HOST, "run", "V", PUBLIC | STATIC | (traits.contains("run-flags") ? AccessFlags.FINAL.getValue() : 0),
                runBody ? 1 : 0, runBody ? code(literal(0, 0), end()) : code(end())));
        methods.add(method(HOST, "answer", "Z", PUBLIC | STATIC, 1, code(literal(0, traits.contains("fixture") ? 1 : 0), returns(0))));
        methods.add(method(HOST, "label", STRING, PUBLIC | STATIC, 1,
                code(traits.contains("patched") ? jumbo(0, "fixture") : string(0, "fixture"), returnsObject(0))));
        if (traits.contains("other") || traits.contains("helper-leak")) methods.add(method(HOST, "hushHelper", "V", PUBLIC | STATIC, 0, code(end())));
        List<ClassDef> out = new ArrayList<>();
        if (traits.contains("stray")) out.add(clazz(STRAY, PUBLIC, OBJECT, List.of(), List.of(), List.of(method(STRAY, "go", "V", PUBLIC | STATIC, 0, code(end())))));
        out.add(clazz(HOST, PUBLIC, OBJECT, List.of(), hostFields, methods));
        int countFlags = PRIVATE | (traits.contains("field-changed") ? AccessFlags.VOLATILE.getValue() : 0);
        List<Field> modelFields = new ArrayList<>(List.of(field(MODEL, "count", "I", countFlags)));
        if (traits.contains("model-mark")) modelFields.add(field(MODEL, "hushMark", "Z", PUBLIC));
        out.add(clazz(MODEL, PUBLIC, traits.contains("superclass") ? BASE : OBJECT, traits.contains("fixture") ? List.of(FACE) : List.of(),
                modelFields, List.of(method(MODEL, "get", "I", PUBLIC, 2,
                        code(new ImmutableInstruction22c(Opcode.IGET, 0, 1, new ImmutableFieldReference(MODEL, "count", "I")), returns(0))))));
        out.add(base(0));
        return out;
    }

    /** Base, which no patch touches, with [flags] added to its one method's. */
    static ClassDef base(int flags) {
        return clazz(BASE, PUBLIC, OBJECT, List.of(), List.of(), List.of(method(BASE, "go", "V", PUBLIC | STATIC | flags, 0, code(end()))));
    }

    static List<Instruction> flagBody() { return code(literal(0, 0), returns(0)); }
    static List<Instruction> versionBody() { return code(string(0, ""), returnsObject(0)); }

    /** The bundle's shared extension: Utils, whose Version the patcher answers. */
    static List<ClassDef> shared() {
        return List.of(clazz(UTILS, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(),
                List.of(method(UTILS, "getPatchesReleaseVersion", STRING, PUBLIC | STATIC, 1, versionBody()))));
    }

    /** The bundle's Tumblr extension, with Plain answering [plain]. */
    static List<ClassDef> tumblr(int plain) {
        return List.of(
                clazz(STATUS, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(), List.of(
                        method(STATUS, "fixtureFamily", "Z", PUBLIC | STATIC, 1, flagBody()),
                        method(STATUS, "otherFamily", "Z", PUBLIC | STATIC, 1, flagBody()))),
                clazz(BRIDGE, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(),
                        List.of(method(BRIDGE, "fill", "V", PUBLIC | STATIC, 0, code(end())))),
                clazz(FACE, PUBLIC | AccessFlags.INTERFACE.getValue() | AccessFlags.ABSTRACT.getValue(), OBJECT, List.of(), List.of(), List.of()),
                plain(plain));
    }

    /** The Tumblr extension of a later bundle: Status, the bridge and Plain each have a method more. */
    static List<ClassDef> tumblrMoved() {
        List<ClassDef> out = new ArrayList<>();
        for (ClassDef cd : tumblr(7)) {
            List<Method> methods = new ArrayList<>();
            for (Method m : cd.getMethods()) methods.add(m);
            if (!cd.getType().equals(FACE)) methods.add(method(cd.getType(), "moved", "V", PUBLIC | STATIC, 0, code(end())));
            List<Field> fields = new ArrayList<>();
            for (Field f : cd.getFields()) fields.add(f);
            out.add(clazz(cd.getType(), cd.getAccessFlags(), cd.getSuperclass(), cd.getInterfaces(), fields, methods));
        }
        return out;
    }

    static ClassDef plain(int value) {
        return clazz(PLAIN, PUBLIC, OBJECT, List.of(), List.of(), List.of(method(PLAIN, "value", "I", PUBLIC | STATIC, 1, code(literal(0, value), returns(0)))));
    }

    /** The extension as the patcher leaves it in a build with [traits]. */
    static List<ClassDef> extension(Set<String> traits) {
        List<ClassDef> out = new ArrayList<>();
        List<Instruction> fixtureRest = traits.contains("flag-remainder") ? code(literal(0, 1), returns(0)) : flagBody();
        out.add(clazz(STATUS, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(), List.of(
                method(STATUS, "fixtureFamily", "Z", PUBLIC | STATIC, 1,
                        join(code(literal(0, traits.contains("fixture") ? 1 : 0), returns(0)), fixtureRest)),
                method(STATUS, "otherFamily", "Z", PUBLIC | STATIC, 1,
                        join(code(literal(0, traits.contains("other") ? 1 : 0), returns(0)), flagBody())))));
        out.add(clazz(UTILS, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(), List.of(
                method(UTILS, "getPatchesReleaseVersion", STRING, PUBLIC | STATIC, 1,
                        join(code(string(0, traits.contains("version-wrong") ? "9.9.8" : VERSION), returnsObject(0)), versionBody())))));
        List<Instruction> fill = traits.contains("fixture")
                ? code(new ImmutableInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, new ImmutableMethodReference(HOST, "label", List.of(), STRING)),
                        new ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), end())
                : code(end());
        out.add(clazz(BRIDGE, PUBLIC | AccessFlags.FINAL.getValue(), OBJECT, List.of(), List.of(),
                List.of(method(BRIDGE, "fill", "V", PUBLIC | STATIC, traits.contains("fixture") ? 1 : 0, fill))));
        out.add(clazz(FACE, PUBLIC | AccessFlags.INTERFACE.getValue() | AccessFlags.ABSTRACT.getValue(), OBJECT, List.of(), List.of(), List.of()));
        if (traits.contains("plain-method-missing")) out.add(clazz(PLAIN, PUBLIC, OBJECT, List.of(), List.of(), List.of()));
        else if (!traits.contains("plain-missing")) out.add(plain(traits.contains("plain-edited") ? 6 : 7));
        if (traits.contains("rogue")) out.add(clazz(ROGUE, PUBLIC, OBJECT, List.of(), List.of(), List.of(method(ROGUE, "go", "V", PUBLIC | STATIC, 0, code(end())))));
        return out;
    }

    static void write(Path file, List<? extends ClassDef> classes) throws Exception {
        DexPool.writeTo(file.toString(), new ImmutableDexFile(Opcodes.getDefault(), classes));
    }

    static void build(Path root, String name, Set<String> traits) throws Exception {
        List<ClassDef> classes = new ArrayList<>(host(traits));
        classes.addAll(extension(traits));
        write(root.resolve(name + ".dex"), classes);
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("extract")) {
            List<ClassDef> own = new ArrayList<>();
            try (InputStream in = new BufferedInputStream(Files.newInputStream(Path.of(args[1])))) {
                for (ClassDef cd : DexBackedDexFile.fromInputStream(Opcodes.getDefault(), in).getClasses())
                    if (cd.getType().startsWith(OWN)) own.add(cd);
            }
            if (own.isEmpty()) throw new IllegalArgumentException("No class under " + OWN + " in " + args[1]);
            write(Path.of(args[2]), own);
            return;
        }
        if (args.length != 1) throw new IllegalArgumentException("usage: DexDeltaFixture <outDir> | extract <dex> <mpe>");
        Path root = Path.of(args[0]);
        Files.createDirectories(root);
        write(root.resolve("delta-clean.dex"), host(Set.of()));
        write(root.resolve("delta-shared.mpe"), shared());
        write(root.resolve("delta-tumblr.mpe"), tumblr(7));
        write(root.resolve("delta-tumblr-moved.mpe"), tumblrMoved());
        // A second extension that defines Plain again, another way.
        write(root.resolve("delta-conflict.mpe"), List.of(plain(5)));
        // Plain on its own, for a patched APK that carries it in two dex entries.
        write(root.resolve("delta-plain.dex"), List.of(plain(7)));
        // Copies of Tumblr's classes in a second dex entry: Model with the clean build's shape and no
        // methods, Base, which no patch touches, as it is and with its method made final, and the
        // two together.
        ClassDef modelCopy = clazz(MODEL, PUBLIC, OBJECT, List.of(), List.of(field(MODEL, "count", "I", PRIVATE)), List.of());
        write(root.resolve("delta-model-copy.dex"), List.of(modelCopy));
        write(root.resolve("delta-model-base-copy.dex"), List.of(modelCopy, base(0)));
        write(root.resolve("delta-base-copy.dex"), List.of(base(0)));
        write(root.resolve("delta-base-copy-final.dex"), List.of(base(AccessFlags.FINAL.getValue())));
        build(root, "delta-good", Set.of("patched", "fixture", "other"));
        build(root, "delta-subset", Set.of("patched", "fixture"));
        // A class one patch reshapes and another adds to, so its whole-class line hashes one way for
        // Fixture patch alone and another for both.
        build(root, "delta-variant-subset", Set.of("patched", "fixture", "superclass"));
        build(root, "delta-variant-good", Set.of("patched", "fixture", "other", "superclass", "model-mark"));
        // Fixture patch's build with Other patch's mark in Model and nothing else of Other patch's.
        build(root, "delta-variant-mixed", Set.of("patched", "fixture", "superclass", "model-mark"));
        // Each bad build: its name, the selection it runs with, how many findings it raises, and
        // the text one of them carries.
        List<String> cases = new ArrayList<>();
        Object[][] bad = {
                {"helper-leak", Set.of("patched", "fixture", "helper-leak"), SELECT_FIXTURE, 1, UNLISTED + "method " + HOST + "->hushHelper()V "},
                {"run-body", Set.of("patched", "fixture", "other", "run-body"), SELECT_BOTH, 1, UNLISTED + "method " + HOST + "->run()V "},
                {"run-flags", Set.of("patched", "fixture", "other", "run-flags"), SELECT_BOTH, 1, UNLISTED + "method " + HOST + "->run()V "},
                // A whole-class line takes the place of Model's implements line, which it covers.
                {"superclass", Set.of("patched", "fixture", "other", "superclass"), SELECT_BOTH, 1, UNLISTED + "class " + MODEL + " "},
                {"field-changed", Set.of("patched", "fixture", "other", "field-changed"), SELECT_BOTH, 1, UNLISTED + "class " + MODEL + " "},
                {"plain-edited", Set.of("patched", "fixture", "other", "plain-edited"), SELECT_BOTH, 1, UNLISTED + "method " + PLAIN + "->value()I "},
                {"plain-missing", Set.of("patched", "fixture", "other", "plain-missing"), SELECT_BOTH, 1,
                        "dex delta: the bundle's class " + PLAIN + " is missing from the patched APK"},
                {"plain-method-missing", Set.of("patched", "fixture", "other", "plain-method-missing"), SELECT_BOTH, 1,
                        "dex delta: the bundle's " + PLAIN + "->value()I is missing from the patched APK"},
                {"rogue", Set.of("patched", "fixture", "other", "rogue"), SELECT_BOTH, 1,
                        "dex delta: " + ROGUE + " is under " + OWN + " but none of the bundle's extensions carry it"},
                {"stray", Set.of("patched", "fixture", "other", "stray"), SELECT_BOTH, 2, UNLISTED + "class " + STRAY + " "},
                {"flag-remainder", Set.of("patched", "fixture", "other", "flag-remainder"), SELECT_BOTH, 1, UNLISTED + "method " + STATUS + "->fixtureFamily()Z "},
                {"version-wrong", Set.of("patched", "fixture", "other", "version-wrong"), SELECT_BOTH, 1,
                        UNLISTED + "method " + UTILS + "->getPatchesReleaseVersion()Ljava/lang/String; "},
        };
        for (Object[] c : bad) {
            @SuppressWarnings("unchecked") Set<String> traits = (Set<String>) c[1];
            build(root, "delta-bad-" + c[0], traits);
            cases.add("delta-bad-" + c[0] + "|" + c[2] + "|" + c[3] + "|" + c[4]);
        }
        Files.write(root.resolve("delta-cases.txt"), cases, StandardCharsets.UTF_8);
    }
}
