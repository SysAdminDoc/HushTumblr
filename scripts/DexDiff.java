import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.ExceptionHandler;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.iface.TryBlock;
import com.android.tools.smali.dexlib2.iface.instruction.Instruction;
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchElement;
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload;
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction;
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload;
import com.android.tools.smali.dexlib2.iface.reference.MethodReference;
import com.android.tools.smali.dexlib2.iface.reference.FieldReference;
import com.android.tools.smali.dexlib2.iface.reference.Reference;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.iface.reference.TypeReference;
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Names every method a patched APK does not share with the clean one it was built from, and prints
 * the register evidence for each: how many registers the method declares, and every instruction the
 * two bodies do not have in common.
 *
 * <p>The point of it is the last line of the report. A patch that writes into a register the method
 * never declared assembles happily and only fails when a device verifies the class, so every
 * instruction that was not in the clean body is checked against the register count of the method it
 * landed in. Methods the patch adds outright, which is where a hand-written bridge lives and where
 * the registers are chosen rather than inherited, are checked the same way: every one of them, the
 * helpers a patch adds to one of the host app's own classes as well as the extension's. Every
 * instruction of every changed and added method is also held to its method's count in the structural
 * pass, the upper half of each wide value it reads or writes included, and one out of range is a
 * finding there rather than a register the later checks step over.
 *
 * <p>Three things this deliberately does not leave to chance:
 *
 * <ul>
 *   <li>A wide instruction names one register and occupies two, so a destination that sets a wide
 *       register counts as reaching one higher than it names. Missing that would let a
 *       {@code const-wide} one short of the ceiling read as safe.
 *   <li>The highest register travels with the rendered line rather than being read back out of the
 *       text, because a host app can hold string constants that look like register names (the
 *       Facebook sibling's held {@code v4190} and {@code v20200906}, and parsing the text flagged
 *       five of them).
 *   <li>Absence of evidence is a failure, not a pass. A run that finds no changed methods, or no
 *       added methods of the extension's own, is comparing the wrong pair of files and says so
 *       instead of reporting nothing wrong.
 * </ul>
 *
 * <p>Branch targets and try-block ranges are part of a body's identity here, so a change that only
 * moves a jump or widens an exception range still shows up as a changed method.
 *
 * <p>Every changed and added method is also held to the structural rules the Facebook sibling's
 * crash reports came from (FroggoMorphePatches issues 3, 16 and 21): a branch or switch case that
 * lands inside an instruction ("target dex pc is not at instruction start"), an invoke whose
 * registers don't match what the callee takes, a parameter register read as the wrong kind (the
 * static and wide off-by-one), a register the body wrote at the wrong width (a narrow const left
 * where a const-wide was, which the Facebook sibling's AMOLED sweep did on its 580), a move-result
 * cut off from its invoke, and a try range or handler off an instruction boundary. A contract file
 * adds rules about the whole APK, in the grammar below: a call with exactly one call site, a stub
 * that has to call out before it returns, a hook that has to come first in a method, right after
 * one call, in place of one call or once in it, and a call nobody outside the extension may make.
 * Each kind came from one of the Facebook sibling's patches: its feed guard had to have one call
 * site, in addNewEdgeToCollection, because two guards stacked on that method is what broke Froggo's
 * builds. A start-call, next-call, sole-call or once-call rule names its method by the strings it
 * loads, and a shape where strings alone don't tell it apart, and exactly one of the host app's
 * methods may answer that: five held "FeedRefreshTriggerController" on the Facebook sibling's 580,
 * so a rule naming that string alone passed a hook in any of them, and its logo rule named one
 * string and counted calls in any method holding it. The device verifier stays the authority;
 * these catch the known shapes without a phone.
 *
 *   java -cp &lt;cli jar&gt; DexDiff.java &lt;cleanApk&gt; &lt;patchedApk&gt; &lt;reportFile&gt;
 *       &lt;removalAllowlist&gt; [&lt;contracts&gt;]
 */
public class DexDiff {

    /** Anything under here is the bundle's own code rather than the host's. */
    private static final String OWN = "Lapp/hushtumblr/extension/";

    private static final class RemovalAllowlist {
        final Set<String> methods = new TreeSet<>();
        final Set<String> dexEntries = new TreeSet<>();
    }

    private static RemovalAllowlist readRemovalAllowlist(File file) throws Exception {
        if (!file.isFile()) throw new IllegalArgumentException("Removal allowlist not found: " + file);
        RemovalAllowlist allowlist = new RemovalAllowlist();
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Set<String> target;
            String value;
            if (line.startsWith("method ")) {
                target = allowlist.methods;
                value = line.substring("method ".length()).trim();
            } else if (line.startsWith("dex ")) {
                target = allowlist.dexEntries;
                value = line.substring("dex ".length()).trim();
            } else {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": expected method or dex");
            }
            if (value.isEmpty() || !target.add(value)) {
                throw new IllegalArgumentException("Invalid removal allowlist line " + lineNumber
                        + ": value is empty or duplicated");
            }
        }
        return allowlist;
    }

    /**
     * One rule about the whole APK.
     *
     * <ul>
     *   <li>"single-call &lt;method reference&gt; in &lt;caller method name&gt;": exactly one call
     *       site, there.
     *   <li>"first-call &lt;method reference&gt; on &lt;class&gt;": the method calls a method of
     *       &lt;class&gt; that takes no arguments before its first return or throw. A stub the
     *       patch filled does; one still answering its marker doesn't.
     *   <li>"first-call &lt;method reference&gt; on-type-named &lt;GraphQL type&gt;": the same, where
     *       the class is the one whose {@code getTypeName()} answers &lt;GraphQL type&gt; as a
     *       literal. For an accessor on a class Redex renames, which no contract can name, and
     *       exactly one class may answer the type.
     *   <li>"first-call &lt;method reference&gt; outside &lt;class prefix&gt;": the method calls a
     *       method of a class whose type doesn't start with &lt;class prefix&gt;, whatever it takes,
     *       before its first return or throw. For a stub the patch fills with a call to a method it
     *       found by what the method does, whose class is a Redex name and no model answering a type
     *       name. A stub still answering its marker makes no call, and one whose only call stays in
     *       the extension doesn't leave it.
     *   <li>"start-call &lt;method reference&gt; [in [static|instance] &lt;shape&gt;] holding
     *       &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the bundle's own code
     *       loads every one of the strings and has the shape, a descriptor such as
     *       {@code (Landroid/content/Context;*)Z} where * stands for any run of
     *       characters. That method calls the method reference, with nothing before the call but
     *       plain instructions (no other call, branch, switch, return or throw), and no other
     *       method loading the strings calls it.
     *   <li>"no-call &lt;method reference&gt; outside &lt;class prefix&gt;": no class but those whose
     *       type starts with &lt;class prefix&gt; calls it. For a call the patch sends to the
     *       extension everywhere, where the extension makes the real one and a call left anywhere
     *       else would undo what the patch is for.
     *   <li>"next-call &lt;method reference&gt; after &lt;method reference&gt; [in [static|instance]
     *       &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the
     *       bundle's own code loads every one of the strings and has the shape, as for start-call.
     *       That method calls the first method once, no other host method calls it, and the
     *       instruction just before the call calls the second method, on the same first register.
     *       For a hook sent in place of one call of a known pair, so it can't drift onto another
     *       view, another call or another method holding the same strings.
     *   <li>"sole-call &lt;method reference&gt; replacing &lt;method reference&gt; [in [static|instance]
     *       &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]": exactly one method outside the
     *       bundle's own code loads every one of the strings and has the shape. It calls the first
     *       method once and the second not at all, no other host method calls the first, and that
     *       call reads the registers, in their order, that the clean build's one call to the
     *       second method there read. For a hook that takes one call's place: left out or left
     *       beside the call it stands in for, the call it replaces still happens, and on other
     *       registers the hook gets the wrong values.
     *   <li>"once-call &lt;method reference&gt; [in [static|instance] &lt;shape&gt;] holding &lt;string&gt;
     *       [&lt;string&gt; ...]": exactly one method outside the bundle's own code loads every one of
     *       the strings and has the shape, as for start-call. That method calls the method reference
     *       once, anywhere in its body, and no other host method calls it. For a hook that has to
     *       read what the method made first, so it can't come first, and follows no one call.
     * </ul>
     */
    /** The kind a first-call rule that names its class by GraphQL type is read into. */
    private static final String TYPED_FIRST_CALL = "first-call-typed";
    /** The kind a first-call rule that asks only for a call leaving a class prefix is read into. */
    private static final String OUTSIDE_FIRST_CALL = "first-call-outside";

    private static final class Contract {
        final String kind;
        final String callee;
        final String target;
        /** For a next-call rule, the call that has to come just before; null for the others. */
        final String after;
        /** For a sole-call rule, the call the callee stands in for; null for the others. */
        final String replaced;
        /** start-call, next-call, sole-call and once-call: the strings its method loads, every one of them. */
        final List<String> strings;
        /** The same rules: whether its method is static, or null when the rule doesn't say. */
        final Boolean isStatic;
        /** The same rules: its method's descriptor, "*" for any run of characters, or null. */
        final String shape;

        Contract(String kind, String callee, String target) {
            this(kind, callee, target, null, null, List.of(), null, null);
        }

        Contract(String kind, String callee, String target, String after, String replaced, List<String> strings,
                Boolean isStatic, String shape) {
            this.kind = kind;
            this.callee = callee;
            this.target = target;
            this.after = after;
            this.replaced = replaced;
            this.strings = strings;
            this.isStatic = isStatic;
            this.shape = shape;
        }

        /** Whether this rule picks its method by strings and a shape: start-call, next-call, sole-call and once-call. */
        boolean picks() {
            return kind.equals("start-call") || kind.equals("next-call") || kind.equals("sole-call")
                    || kind.equals("once-call");
        }

        /** How a rule that picks its method reads in the contract file, from its kind on. */
        String rule() {
            StringBuilder b = new StringBuilder(kind).append(' ').append(callee);
            if (after != null) b.append(" after ").append(after);
            if (replaced != null) b.append(" replacing ").append(replaced);
            if (shape != null) {
                b.append(" in ");
                if (isStatic != null) b.append(isStatic ? "static " : "instance ");
                b.append(shape);
            }
            return b.append(" holding ").append(String.join(" ", strings)).toString();
        }

        /** Whether [m] has this rule's shape: its static flag and its descriptor. */
        boolean hasShape(Method m) {
            if (isStatic != null && AccessFlags.STATIC.isSet(m.getAccessFlags()) != isStatic) return false;
            if (shape == null) return true;
            StringBuilder descriptor = new StringBuilder("(");
            for (CharSequence p : m.getParameterTypes()) descriptor.append(p);
            descriptor.append(')').append(m.getReturnType());
            String[] pieces = shape.split("\\*", -1);
            StringBuilder pattern = new StringBuilder();
            for (int k = 0; k < pieces.length; k++) {
                if (k > 0) pattern.append(".*");
                pattern.append(java.util.regex.Pattern.quote(pieces[k]));
            }
            return descriptor.toString().matches(pattern.toString());
        }
    }

    /** How each rule that picks its method by strings and a shape is written, for the message a bad line gets. */
    private static final Map<String, String> PICKED_FORMS = Map.of(
            "start-call", "start-call <method reference>",
            "next-call", "next-call <method reference> after <method reference>",
            "sole-call", "sole-call <method reference> replacing <method reference>",
            "once-call", "once-call <method reference>");

    private static final Map<String, Integer> MUTATION_COLUMNS = Map.ofEntries(
            Map.entry("answers", 4), Map.entry("settings", 3), Map.entry("firebaseCertificate", 1),
            Map.entry("settingsRow", 3), Map.entry("featureSwitch", 4), Map.entry("timelineList", 4),
            Map.entry("timelineCarousel", 3), Map.entry("postBridge", 3), Map.entry("reviewPrompt", 3),
            Map.entry("followButton", 3), Map.entry("footerButton", 3), Map.entry("postTags", 5),
            Map.entry("noteCounts", 5), Map.entry("unreadDots", 2), Map.entry("bottomBarTabs", 3),
            Map.entry("darkBackground", 3), Map.entry("storeButtons", 5));

    /**
     * A start-call, next-call, sole-call or once-call line: its method reference, the next-call's
     * "after &lt;method reference&gt;" or the sole-call's "replacing &lt;method reference&gt;", then "[in
     * [static|instance] &lt;shape&gt;] holding &lt;string&gt; [&lt;string&gt; ...]". Null when it isn't one.
     */
    private static Contract readPicked(String[] parts) {
        String kind = parts[0];
        if (parts.length < 2 || !parts[1].contains("->")) return null;
        int at = 2;
        String after = null;
        String replaced = null;
        if (kind.equals("next-call") || kind.equals("sole-call")) {
            String word = kind.equals("next-call") ? "after" : "replacing";
            if (parts.length < 4 || !parts[2].equals(word) || !parts[3].contains("->")) return null;
            if (kind.equals("next-call")) after = parts[3];
            else replaced = parts[3];
            at = 4;
        }
        Boolean isStatic = null;
        String shape = null;
        if (at < parts.length && parts[at].equals("in")) {
            at++;
            if (at < parts.length && (parts[at].equals("static") || parts[at].equals("instance"))) {
                isStatic = parts[at].equals("static");
                at++;
            }
            if (at >= parts.length || !parts[at].startsWith("(") || parts[at].indexOf(')') < 1) return null;
            shape = parts[at++];
        }
        if (at >= parts.length || !parts[at].equals("holding")) return null;
        List<String> strings = new ArrayList<>(Arrays.asList(parts).subList(at + 1, parts.length));
        if (strings.isEmpty() || new TreeSet<>(strings).size() != strings.size()) return null;
        return new Contract(kind, parts[1], String.join(" ", strings), after, replaced, strings, isStatic, shape);
    }

    private static List<Contract> readContracts(File file) throws Exception {
        List<Contract> contracts = new ArrayList<>();
        if (file == null) return contracts;
        if (!file.isFile()) throw new IllegalArgumentException("Contract file not found: " + file);
        int lineNumber = 0;
        for (String raw : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            lineNumber++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("family|") || line.startsWith("mutation|")) {
                String[] columns = line.split("\\|", -1);
                boolean family = columns[0].equals("family");
                int mutationValues = columns.length - 3;
                boolean validMutation = family || (!columns[2].equals("guard")
                        ? MUTATION_COLUMNS.getOrDefault(columns[2], -1) == mutationValues
                        : mutationValues >= 2 && mutationValues % 2 == 0);
                // A family's capability column may be empty: a manifest-only patch changes no bytecode.
                if ((family && columns.length != 4) || (!family && columns.length < 4)
                        || Arrays.stream(columns).limit(family ? 3 : columns.length).anyMatch(String::isBlank)
                        || !columns[1].matches("[A-Za-z][A-Za-z0-9]*(,[A-Za-z][A-Za-z0-9]*)*")
                        || (family && !columns[3].matches("([A-Za-z][A-Za-z0-9]*(,[A-Za-z][A-Za-z0-9]*)*)?"))
                        || !validMutation) {
                    throw new IllegalArgumentException("Invalid contract line " + lineNumber + ": invalid feature columns");
                }
                contracts.add(new Contract(columns[0], columns[1], columns[2], null, null,
                        Arrays.asList(columns).subList(3, columns.length), null, null));
                continue;
            }
            String[] parts = line.split("\\s+");
            String form = PICKED_FORMS.get(parts[0]);
            if (form != null) {
                Contract picked = readPicked(parts);
                if (picked == null) {
                    throw new IllegalArgumentException("Invalid contract line " + lineNumber + ": expected " + form
                            + " [in [static|instance] <(parameters)return>] holding <string> [<string> ...]");
                }
                contracts.add(picked);
                continue;
            }
            boolean singleCall = parts.length == 4 && parts[0].equals("single-call") && parts[2].equals("in");
            boolean firstCall = parts.length == 4 && parts[0].equals("first-call") && parts[2].equals("on")
                    && parts[3].startsWith("L") && parts[3].endsWith(";");
            boolean firstCallTyped = parts.length == 4 && parts[0].equals("first-call")
                    && parts[2].equals("on-type-named") && parts[3].matches("[A-Za-z][A-Za-z0-9_]*");
            boolean firstCallOutside = parts.length == 4 && parts[0].equals("first-call") && parts[2].equals("outside")
                    && parts[3].startsWith("L") && parts[3].endsWith("/");
            boolean noCall = parts.length == 4 && parts[0].equals("no-call") && parts[2].equals("outside")
                    && parts[3].startsWith("L") && parts[3].endsWith("/");
            if ((!singleCall && !firstCall && !firstCallTyped && !firstCallOutside && !noCall)
                    || !parts[1].contains("->")) {
                throw new IllegalArgumentException("Invalid contract line " + lineNumber
                        + ": expected single-call <method reference> in <caller method name>,"
                        + " first-call <method reference> on <class>,"
                        + " first-call <method reference> on-type-named <GraphQL type>,"
                        + " first-call <method reference> outside <package prefix ending in />,"
                        + " no-call <method reference> outside <package prefix ending in />,"
                        + " or start-call <method reference>, next-call <method reference> after <method reference>,"
                        + " sole-call <method reference> replacing <method reference>"
                        + " or once-call <method reference>, each then"
                        + " [in [static|instance] <(parameters)return>] holding <string> [<string> ...]");
            }
            String kind = firstCallTyped ? TYPED_FIRST_CALL : firstCallOutside ? OUTSIDE_FIRST_CALL : parts[0];
            contracts.add(new Contract(kind, parts[1], parts[3]));
        }
        return contracts;
    }

    /** Every instruction of a body with its code-unit address, and where the body ends. */
    private static final class Layout {
        final List<Instruction> instructions = new ArrayList<>();
        final List<Integer> addresses = new ArrayList<>();
        final Map<Integer, Instruction> byAddress = new HashMap<>();
        int size;

        Layout(MethodImplementation impl) {
            int address = 0;
            for (Instruction i : impl.getInstructions()) {
                instructions.add(i);
                addresses.add(address);
                byAddress.put(address, i);
                address += i.getCodeUnits();
            }
            size = address;
        }

        boolean isStart(int address) {
            return byAddress.containsKey(address);
        }
    }

    /** Registers a parameter of this type takes: two for long and double, one for anything else. */
    private static int slots(CharSequence type) {
        char c = type.charAt(0);
        return c == 'J' || c == 'D' ? 2 : 1;
    }

    /** The kind a value of this type has in a register: L object, W wide (low half), I narrow. */
    private static char kindOf(CharSequence type) {
        char c = type.charAt(0);
        if (c == 'J' || c == 'D') return 'W';
        if (c == 'L' || c == '[') return 'L';
        return 'I';
    }

    private static boolean isStaticInvoke(Opcode opcode) {
        return opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE;
    }

    /** The invoke kinds whose registers are the callee's receiver and parameters, in order. */
    private static boolean isPlainInvoke(Opcode opcode) {
        switch (opcode) {
            case INVOKE_VIRTUAL: case INVOKE_SUPER: case INVOKE_DIRECT: case INVOKE_STATIC:
            case INVOKE_INTERFACE: case INVOKE_VIRTUAL_RANGE: case INVOKE_SUPER_RANGE:
            case INVOKE_DIRECT_RANGE: case INVOKE_STATIC_RANGE: case INVOKE_INTERFACE_RANGE:
                return true;
            default:
                return false;
        }
    }

    /** The registers an invoke passes, in argument order. */
    private static int[] invokeRegisters(Instruction i) {
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            int[] regs = new int[r.getRegisterCount()];
            for (int k = 0; k < regs.length; k++) regs[k] = r.getStartRegister() + k;
            return regs;
        }
        FiveRegisterInstruction r = (FiveRegisterInstruction) i;
        int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
        int[] regs = new int[r.getRegisterCount()];
        System.arraycopy(all, 0, regs, 0, regs.length);
        return regs;
    }

    /**
     * For each instruction, the parameter registers some path from the entry may have written
     * before it runs, as bits counted from the first parameter register, or null where no path
     * reaches it. A register no path has written still holds its argument, so its kind is known.
     * An exception edge carries the state after the throwing instruction, which only adds writes,
     * so wherever a path is in doubt the check that reads this stays quiet.
     */
    private static BitSet[] parameterWrites(MethodImplementation impl, Layout layout, int firstParameter) {
        int count = layout.instructions.size();
        BitSet[] before = new BitSet[count];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = new BitSet();
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            BitSet after = (BitSet) before[k].clone();
            if (opcode.setsRegister() && i instanceof OneRegisterInstruction) {
                int destination = ((OneRegisterInstruction) i).getRegisterA();
                int last = destination + (opcode.setsWideRegister() ? 1 : 0);
                for (int r = Math.max(destination, firstParameter); r <= last; r++) after.set(r - firstParameter);
            }
            List<Integer> next = new ArrayList<>();
            if (opcode.canContinue()) next.add(at + i.getCodeUnits());
            if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
                int target = at + ((OffsetInstruction) i).getCodeOffset();
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    Instruction payload = layout.byAddress.get(target);
                    if (payload instanceof SwitchPayload) {
                        for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                            next.add(at + element.getOffset());
                        }
                    }
                } else {
                    next.add(target);
                }
            }
            if (opcode.canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                    int start = block.getStartCodeAddress();
                    if (at < start || at >= start + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        next.add(handler.getHandlerCodeAddress());
                    }
                }
            }
            for (int address : next) {
                Integer successor = indexAt.get(address);
                if (successor == null) continue;
                if (before[successor] == null) {
                    before[successor] = (BitSet) after.clone();
                    work.add(successor);
                    continue;
                }
                BitSet merged = (BitSet) before[successor].clone();
                merged.or(after);
                if (!merged.equals(before[successor])) {
                    before[successor] = merged;
                    work.add(successor);
                }
            }
        }
        return before;
    }

    private static boolean isMoveResult(Opcode opcode) {
        return opcode == Opcode.MOVE_RESULT || opcode == Opcode.MOVE_RESULT_WIDE
                || opcode == Opcode.MOVE_RESULT_OBJECT;
    }

    /**
     * The kind each register holds before each instruction, along every path from the entry, or
     * null where no path reaches it: L an object, I a narrow value, Z a zero constant (narrow or
     * null), W and w the halves of a wide value, B and b the halves of one whose other half was
     * overwritten, C a register the paths that meet there disagree about, T unknown. The
     * parameters start with their declared kinds; constants, moves, move-results and the plain
     * object producers set theirs; a wide result sets a pair; anything else leaves T, which no
     * check reads. Where paths meet, the kinds join the way ART merges them (see join), so a pair
     * broken or a width changed on one arm of a branch is still a finding after the arms rejoin.
     * An exception edge carries the kinds from before the throwing instruction, as ART's does: the
     * exception leaves before the instruction writes anything.
     */
    private static char[][] registerKinds(MethodImplementation impl, Layout layout,
            Map<Integer, Character> parameterKind) {
        int count = layout.instructions.size();
        int registers = impl.getRegisterCount();
        char[][] before = new char[count][];
        if (count == 0) return before;
        Map<Integer, Integer> indexAt = new HashMap<>();
        for (int k = 0; k < count; k++) indexAt.put(layout.addresses.get(k), k);
        char[] entry = new char[registers];
        java.util.Arrays.fill(entry, 'T');
        for (Map.Entry<Integer, Character> p : parameterKind.entrySet()) {
            if (p.getKey() < registers) entry[p.getKey()] = p.getValue();
        }
        Deque<Integer> work = new ArrayDeque<>();
        before[0] = entry;
        work.add(0);
        while (!work.isEmpty()) {
            int k = work.poll();
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);
            char[] after = transfer(i, before[k]);
            List<Integer> next = new ArrayList<>();
            if (opcode.canContinue()) next.add(at + i.getCodeUnits());
            if (i instanceof OffsetInstruction && opcode != Opcode.FILL_ARRAY_DATA) {
                int target = at + ((OffsetInstruction) i).getCodeOffset();
                if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                    Instruction payload = layout.byAddress.get(target);
                    if (payload instanceof SwitchPayload) {
                        for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                            next.add(at + element.getOffset());
                        }
                    }
                } else {
                    next.add(target);
                }
            }
            List<Integer> handlers = new ArrayList<>();
            if (opcode.canThrow()) {
                for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
                    int start = block.getStartCodeAddress();
                    if (at < start || at >= start + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) {
                        handlers.add(handler.getHandlerCodeAddress());
                    }
                }
            }
            for (int address : next) flow(before, indexAt, work, address, after);
            for (int address : handlers) flow(before, indexAt, work, address, before[k]);
        }
        return before;
    }

    private static void flow(char[][] before, Map<Integer, Integer> indexAt, Deque<Integer> work,
            int address, char[] state) {
        Integer successor = indexAt.get(address);
        if (successor == null) return;
        if (before[successor] == null) {
            before[successor] = state.clone();
            work.add(successor);
            return;
        }
        boolean changed = false;
        char[] into = before[successor];
        for (int r = 0; r < into.length; r++) {
            char joined = join(into[r], state[r]);
            if (joined != into[r]) {
                into[r] = joined;
                changed = true;
            }
        }
        if (changed) work.add(successor);
    }

    /**
     * One register's kind where two paths meet, as ART merges it. A zero is a null or a zero, so it
     * takes the other side's object or narrow kind, and a pair broken on one path is broken. Any
     * other two known kinds make C, a conflict: ART lets a move copy one, and fails every other
     * read of it. T stays T, since a register this doesn't know is never judged.
     */
    private static char join(char a, char b) {
        if (a == b) return a;
        if (a == 'T' || b == 'T') return 'T';
        if (a == 'Z' && (b == 'L' || b == 'I')) return b;
        if (b == 'Z' && (a == 'L' || a == 'I')) return a;
        if ((a == 'W' && b == 'B') || (a == 'B' && b == 'W')) return 'B';
        if ((a == 'w' && b == 'b') || (a == 'b' && b == 'w')) return 'b';
        return 'C';
    }

    /** A narrow or object move: ART lets one copy a conflict, which fails only where it's used. */
    private static boolean copiesConflict(Opcode opcode) {
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                return true;
            default:
                return false;
        }
    }

    /** The kinds after one instruction. */
    private static char[] transfer(Instruction i, char[] in) {
        Opcode opcode = i.getOpcode();
        if (!opcode.setsRegister() || !(i instanceof OneRegisterInstruction)) return in;
        char[] out = in.clone();
        int a = ((OneRegisterInstruction) i).getRegisterA();
        // A move copies its source's kind, a conflict included, unless the read is already a
        // finding: then what it wrote is unknown, and the one mistake is reported once rather
        // than at every later use.
        if (opcode.setsWideRegister()) {
            char low = 'W', high = 'w';
            if (opcode == Opcode.MOVE_WIDE || opcode == Opcode.MOVE_WIDE_FROM16 || opcode == Opcode.MOVE_WIDE_16) {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                boolean wide = b + 1 < in.length && in[b] == 'W' && in[b + 1] == 'w';
                low = wide ? 'W' : 'T';
                high = wide ? 'w' : 'T';
            }
            write(out, a, low);
            write(out, a + 1, high);
            return out;
        }
        char kind;
        switch (opcode) {
            case CONST_4: case CONST_16: case CONST: case CONST_HIGH16:
                kind = ((com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction) i)
                        .getNarrowLiteral() == 0 ? 'Z' : 'I';
                break;
            case MOVE: case MOVE_FROM16: case MOVE_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'I')) ? source : 'T';
                break;
            }
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16: {
                int b = ((TwoRegisterInstruction) i).getRegisterB();
                char source = b < in.length ? in[b] : 'T';
                kind = source == 'C' || (source != 'T' && readableAs(source, 'L')) ? source : 'T';
                break;
            }
            case MOVE_RESULT: case INSTANCE_OF: case ARRAY_LENGTH:
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case SGET: case SGET_BOOLEAN: case SGET_BYTE: case SGET_CHAR: case SGET_SHORT:
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
                kind = 'I';
                break;
            case MOVE_RESULT_OBJECT: case MOVE_EXCEPTION: case CONST_STRING: case CONST_STRING_JUMBO:
            case CONST_CLASS: case NEW_INSTANCE: case NEW_ARRAY: case CHECK_CAST:
            case IGET_OBJECT: case SGET_OBJECT: case AGET_OBJECT:
                kind = 'L';
                break;
            default:
                // Arithmetic, compares and conversions that don't set a pair set a narrow value.
                kind = arithmeticOperands(opcode) != null ? 'I' : 'T';
        }
        write(out, a, kind);
        return out;
    }

    /**
     * One register's new kind, and the wide pair it breaks if it was half of one. ART keeps the
     * half that wasn't written as it was and checks the pair where it's read, so the half left
     * behind can't be read as anything: writing over the upper half leaves the lower half as B,
     * and writing over the lower half leaves the upper half as b. A patch that borrows either half
     * of a live long as a free local does this, and so does a wide write that lands one register
     * below a live pair.
     */
    private static void write(char[] out, int register, char kind) {
        // Out of range has no kind to hold; the register check in structuralFindings reports it.
        if (register >= out.length) return;
        if (out[register] == 'W' && register + 1 < out.length && out[register + 1] == 'w' && kind != 'W') {
            out[register + 1] = 'b';
        }
        if (out[register] == 'w' && register > 0 && out[register - 1] == 'W' && kind != 'w') {
            out[register - 1] = 'B';
        }
        out[register] = kind;
    }

    /**
     * Whether a register of this kind can be read as that kind. T is never in doubt here; B, b, w
     * and C can't be read as anything, and W only as the pair it starts. E is what a test against
     * zero or an equality test takes: a narrow value or an object.
     */
    private static boolean readableAs(char have, char want) {
        if (have == 'T') return true;
        if (have == 'B') return false;
        switch (want) {
            case 'W': return have == 'W';
            case 'I': return have == 'I' || have == 'Z';
            case 'L': return have == 'L' || have == 'Z';
            case 'E': return have == 'I' || have == 'Z' || have == 'L';
            default: return true;
        }
    }

    private static final java.util.regex.Pattern WIDE_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor)-(long|double)(/2addr)?|cmp-long|cmp[lg]-double");
    private static final java.util.regex.Pattern WIDE_SHIFT = java.util.regex.Pattern.compile(
            "(shl|shr|ushr)-long(/2addr)?");
    private static final java.util.regex.Pattern NARROW_PAIR = java.util.regex.Pattern.compile(
            "(add|sub|mul|div|rem|and|or|xor|shl|shr|ushr)-(int|float)(/2addr)?|cmp[lg]-float");
    private static final java.util.regex.Pattern NARROW_LITERAL = java.util.regex.Pattern.compile(
            "(add|rsub|mul|div|rem|and|or|xor|shl|shr|ushr)-int/lit(8|16)|rsub-int");
    private static final java.util.regex.Pattern WIDE_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(long|double)|(long|double)-to-(int|long|float|double)");
    private static final java.util.regex.Pattern NARROW_SINGLE = java.util.regex.Pattern.compile(
            "(neg|not)-(int|float)|(int|float)-to-(int|long|float|double|byte|char|short)");

    /** The kinds an arithmetic, compare or conversion instruction reads its operands as, or null. */
    private static char[] arithmeticOperands(Opcode opcode) {
        String name = opcode.name;
        if (WIDE_PAIR.matcher(name).matches()) return new char[]{'W', 'W'};
        if (WIDE_SHIFT.matcher(name).matches()) return new char[]{'W', 'I'};
        if (NARROW_PAIR.matcher(name).matches()) return new char[]{'I', 'I'};
        if (NARROW_LITERAL.matcher(name).matches() || NARROW_SINGLE.matcher(name).matches()) return new char[]{'I'};
        if (WIDE_SINGLE.matcher(name).matches()) return new char[]{'W'};
        return null;
    }

    private static void read(List<int[]> reads, int register, char kind) {
        reads.add(new int[]{register, kind});
    }

    /** Every register an instruction reads as a value, with the kind it reads it as. */
    private static List<int[]> valueReads(Instruction i) {
        List<int[]> reads = new ArrayList<>();
        Opcode opcode = i.getOpcode();
        switch (opcode) {
            case MOVE: case MOVE_FROM16: case MOVE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MOVE_WIDE: case MOVE_WIDE_FROM16: case MOVE_WIDE_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'W');
                return reads;
            case MOVE_OBJECT: case MOVE_OBJECT_FROM16: case MOVE_OBJECT_16:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case RETURN:
            case SPUT: case SPUT_BOOLEAN: case SPUT_BYTE: case SPUT_CHAR: case SPUT_SHORT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case RETURN_WIDE: case SPUT_WIDE:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'W');
                return reads;
            case RETURN_OBJECT: case SPUT_OBJECT:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case IPUT: case IPUT_BOOLEAN: case IPUT_BYTE: case IPUT_CHAR: case IPUT_SHORT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_WIDE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'W');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IPUT_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'L');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case IGET: case IGET_BOOLEAN: case IGET_BYTE: case IGET_CHAR: case IGET_SHORT:
            case IGET_WIDE: case IGET_OBJECT:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case APUT: case APUT_BOOLEAN: case APUT_BYTE: case APUT_CHAR: case APUT_SHORT:
            case APUT_WIDE: case APUT_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                char value = opcode == Opcode.APUT_WIDE ? 'W' : opcode == Opcode.APUT_OBJECT ? 'L' : 'I';
                read(reads, t.getRegisterA(), value);
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            case AGET: case AGET_BOOLEAN: case AGET_BYTE: case AGET_CHAR: case AGET_SHORT:
            case AGET_WIDE: case AGET_OBJECT: {
                ThreeRegisterInstruction t = (ThreeRegisterInstruction) i;
                read(reads, t.getRegisterB(), 'L');
                read(reads, t.getRegisterC(), 'I');
                return reads;
            }
            // Each register as ART's verifier checks it. A test against zero or for equality takes
            // a narrow value or an object, an ordering, a switch or a new array's size takes a
            // narrow value, and a lock, a throw, a cast, a type test, or an array to measure or
            // fill takes an object. An equality test's two registers also have to agree, which
            // structuralFindings checks as a pair.
            case IF_EQZ: case IF_NEZ:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'E');
                return reads;
            case IF_EQ: case IF_NE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'E');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'E');
                return reads;
            case IF_LTZ: case IF_GEZ: case IF_GTZ: case IF_LEZ:
            case PACKED_SWITCH: case SPARSE_SWITCH:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'I');
                return reads;
            case IF_LT: case IF_GE: case IF_GT: case IF_LE:
                read(reads, ((TwoRegisterInstruction) i).getRegisterA(), 'I');
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case MONITOR_ENTER: case MONITOR_EXIT: case THROW: case CHECK_CAST: case FILL_ARRAY_DATA:
                read(reads, ((OneRegisterInstruction) i).getRegisterA(), 'L');
                return reads;
            case INSTANCE_OF: case ARRAY_LENGTH:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'L');
                return reads;
            case NEW_ARRAY:
                read(reads, ((TwoRegisterInstruction) i).getRegisterB(), 'I');
                return reads;
            case FILLED_NEW_ARRAY: case FILLED_NEW_ARRAY_RANGE: {
                // Each element as the array holds it: an object, or a narrow value.
                Reference type = ((ReferenceInstruction) i).getReference();
                if (!(type instanceof TypeReference)) return reads;
                char element = kindOf(((TypeReference) type).getType().substring(1));
                for (int register : invokeRegisters(i)) read(reads, register, element);
                return reads;
            }
            default:
                break;
        }
        char[] operands = arithmeticOperands(opcode);
        if (operands == null) return reads;
        int[] registers;
        if (i instanceof ThreeRegisterInstruction) {
            registers = new int[]{((ThreeRegisterInstruction) i).getRegisterB(), ((ThreeRegisterInstruction) i).getRegisterC()};
        } else if (i instanceof TwoRegisterInstruction && opcode.name.endsWith("/2addr")) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterA(), ((TwoRegisterInstruction) i).getRegisterB()};
        } else if (i instanceof TwoRegisterInstruction) {
            registers = new int[]{((TwoRegisterInstruction) i).getRegisterB()};
        } else {
            return reads;
        }
        for (int k = 0; k < Math.min(operands.length, registers.length); k++) read(reads, registers[k], operands[k]);
        return reads;
    }

    /**
     * The structural findings for one method, each as "category: detail". The parameter check
     * reads a register only where no path from the entry has written it, where the value can
     * only be the argument the method was called with.
     */
    private static List<String> structuralFindings(ClassDef cd, Method m) {
        List<String> findings = new ArrayList<>();
        MethodImplementation impl = m.getImplementation();
        if (impl == null) return findings;
        Layout layout = new Layout(impl);

        // Registers: every one an instruction names, and the upper half of each wide value it
        // writes or reads, has to be below the count the method declares. ART checks that for each
        // instruction before it follows any path, so one out of range fails the class wherever it
        // sits, reachable or not. The kind checks further down have no kind to give such a
        // register and step over it, which is why it's a finding here, once for each instruction.
        int registerCount = impl.getRegisterCount();
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            int reach = reach(i);
            if (reach >= registerCount) {
                findings.add("register: " + i.getOpcode().name + " at " + layout.addresses.get(k) + " reaches v" + reach
                        + ", and the method declares " + registerCount + (registerCount == 1 ? " register" : " registers"));
            }
        }

        // A move-result takes the result of the instruction right before it, so an instruction
        // injected between an invoke and its move-result leaves nothing to take.
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (!isMoveResult(layout.instructions.get(k).getOpcode())) continue;
            if (k == 0 || !layout.instructions.get(k - 1).getOpcode().setsResult()) {
                findings.add("result: " + layout.instructions.get(k).getOpcode().name + " at "
                        + layout.addresses.get(k) + " does not follow an invoke");
            }
        }

        // Branches and switch cases.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            if (!(i instanceof OffsetInstruction)) continue;
            int at = layout.addresses.get(k);
            int target = at + ((OffsetInstruction) i).getCodeOffset();
            Opcode opcode = i.getOpcode();
            if (target == at && opcode != Opcode.GOTO_32) {
                findings.add("branch: " + opcode.name + " at " + at + " branches to itself");
                continue;
            }
            if (!layout.isStart(target)) {
                findings.add("branch: " + opcode.name + " at " + at + " targets " + target
                        + ", which is not the start of an instruction");
                continue;
            }
            Instruction payload = layout.byAddress.get(target);
            if (opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH) {
                if (!(payload instanceof SwitchPayload)) {
                    findings.add("branch: " + opcode.name + " at " + at + " points at " + target
                            + ", which is not a switch payload");
                    continue;
                }
                // Each switch reads its own kind of table, and ART checks the table's signature
                // against the switch ("wrong signature for switch table").
                Opcode wanted = opcode == Opcode.PACKED_SWITCH ? Opcode.PACKED_SWITCH_PAYLOAD : Opcode.SPARSE_SWITCH_PAYLOAD;
                if (payload.getOpcode() != wanted) {
                    findings.add("branch: " + opcode.name + " at " + at + " points at " + target + ", a "
                            + payload.getOpcode().name + " rather than the " + wanted.name + " it reads");
                    continue;
                }
                for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements()) {
                    int caseTarget = at + element.getOffset();
                    if (!layout.isStart(caseTarget)) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case "
                                + element.getKey() + " to " + caseTarget
                                + ", which is not the start of an instruction");
                        continue;
                    }
                    String bad = badLanding(layout.byAddress.get(caseTarget));
                    if (bad != null) {
                        findings.add("branch: " + opcode.name + " at " + at + " sends case " + element.getKey()
                                + " to " + caseTarget + ", " + bad + ", which a branch may not land on");
                    }
                }
            } else if (opcode == Opcode.FILL_ARRAY_DATA) {
                if (!(payload instanceof ArrayPayload)) {
                    findings.add("branch: fill-array-data at " + at + " points at " + target
                            + ", which is not an array payload");
                }
            } else {
                String bad = badLanding(payload);
                if (bad != null) {
                    findings.add("branch: " + opcode.name + " at " + at + " targets " + target + ", " + bad
                            + ", which a branch may not land on");
                }
            }
        }

        // Try ranges and handlers.
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            int start = block.getStartCodeAddress();
            int end = start + block.getCodeUnitCount();
            if (block.getCodeUnitCount() <= 0) {
                findings.add("try: a try range at " + start + " covers nothing");
            }
            if (!layout.isStart(start)) {
                findings.add("try: a try range starts at " + start + ", which is not the start of an instruction");
            }
            if (end > layout.size || (end != layout.size && !layout.isStart(end))) {
                findings.add("try: a try range ends at " + end + ", which is not an instruction boundary");
            }
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                int address = handler.getHandlerCodeAddress();
                if (!layout.isStart(address)) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " is at "
                            + address + ", which is not the start of an instruction");
                } else if (isMoveResult(layout.byAddress.get(address).getOpcode())) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts with a move-result at "
                            + address);
                } else if (isPayload(layout.byAddress.get(address))) {
                    findings.add("try: a handler for " + handler.getExceptionType() + " starts at a payload at "
                            + address);
                }
            }
        }

        // Invokes: as many registers as the callee takes, and each wide argument in a pair.
        for (int k = 0; k < layout.instructions.size(); k++) {
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            int at = layout.addresses.get(k);
            if (regs.length != expected.size()) {
                findings.add("invoke: " + opcode.name + " at " + at + " passes " + regs.length
                        + (regs.length == 1 ? " register" : " registers") + " to " + callee
                        + ", which takes " + expected.size());
                continue;
            }
            for (int a = 0; a < regs.length; a++) {
                if (expected.get(a) == 'w' && regs[a] != regs[a - 1] + 1) {
                    findings.add("invoke: " + opcode.name + " at " + at + " splits a wide argument of "
                            + callee + " across v" + regs[a - 1] + " and v" + regs[a]);
                }
            }
        }

        // Parameters: registerCount minus the ins, then this, then each parameter in order.
        boolean isStatic = AccessFlags.STATIC.isSet(m.getAccessFlags());
        int ins = isStatic ? 0 : 1;
        for (CharSequence p : m.getParameterTypes()) ins += slots(p);
        int firstParameter = impl.getRegisterCount() - ins;
        if (firstParameter < 0) {
            findings.add("parameter: declares " + impl.getRegisterCount() + " registers but its parameters need " + ins);
            return findings;
        }
        Map<Integer, Character> parameterKind = new HashMap<>();
        int register = firstParameter;
        if (!isStatic) parameterKind.put(register++, 'L');
        for (CharSequence p : m.getParameterTypes()) {
            char kind = kindOf(p);
            parameterKind.put(register, kind);
            if (kind == 'W') parameterKind.put(register + 1, 'w');
            register += slots(p);
        }

        // Kinds: a read of a register that every path gives the wrong kind. Where no path has
        // written the register it still holds its argument, and the finding is the parameter
        // layout's (the static and wide off-by-one); otherwise the body wrote the wrong width,
        // such as a narrow const left where a const-wide was.
        BitSet[] writes = parameterWrites(impl, layout, firstParameter);
        char[][] kinds = registerKinds(impl, layout, parameterKind);
        for (int k = 0; k < layout.instructions.size(); k++) {
            if (kinds[k] == null || writes[k] == null) continue;
            Instruction i = layout.instructions.get(k);
            Opcode opcode = i.getOpcode();
            int at = layout.addresses.get(k);

            for (int[] read : valueReads(i)) {
                // A register out of range is already a register finding for this instruction.
                if (read[0] >= kinds[k].length) continue;
                char have = kinds[k][read[0]];
                if (readableAs(have, (char) read[1]) || (have == 'C' && copiesConflict(opcode))) continue;
                findings.add(origin(read[0], firstParameter, writes[k], have) + opcode.name + " at " + at
                        + " reads v" + read[0] + ", which holds " + describe(have)
                        + ", as " + describe((char) read[1]));
            }

            // An equality test compares two objects or two narrow values, and a zero may stand
            // for either. Each register passes the read above on its own, so an int tested
            // against an object is only caught as a pair; ART refuses it ("args to if-eq/if-ne
            // must both be references or integral").
            if (opcode == Opcode.IF_EQ || opcode == Opcode.IF_NE) {
                int first = ((TwoRegisterInstruction) i).getRegisterA();
                int second = ((TwoRegisterInstruction) i).getRegisterB();
                if (first < kinds[k].length && second < kinds[k].length) {
                    char a = kinds[k][first];
                    char b = kinds[k][second];
                    if ((a == 'I' && b == 'L') || (a == 'L' && b == 'I')) {
                        findings.add("width: " + opcode.name + " at " + at + " compares v" + first + ", which holds "
                                + describe(a) + ", with v" + second + ", which holds " + describe(b));
                    }
                }
            }

            if (!isPlainInvoke(opcode) || !(i instanceof ReferenceInstruction)
                    || !(((ReferenceInstruction) i).getReference() instanceof MethodReference)) continue;
            MethodReference callee = (MethodReference) ((ReferenceInstruction) i).getReference();
            List<Character> expected = expectedArguments(opcode, callee);
            int[] regs = invokeRegisters(i);
            if (regs.length != expected.size()) continue;
            for (int a = 0; a < regs.length; a++) {
                char want = expected.get(a);
                // The upper half of a wide argument is checked with its pair, and a register out
                // of range is already a register finding for this instruction.
                if (want == 'w' || regs[a] >= kinds[k].length) continue;
                char have = kinds[k][regs[a]];
                if (!readableAs(have, want)) {
                    findings.add(origin(regs[a], firstParameter, writes[k], have) + opcode.name + " at " + at
                            + " passes v" + regs[a] + ", which holds " + describe(have) + ", where "
                            + callee + " takes " + describe(want));
                }
            }
        }

        // A move-exception takes the exception a handler caught, so only a throw may reach it.
        // Falling into one from the instruction above is what ART calls flowing through to it,
        // and the method's entry reaches one at the very start the same way.
        if (!layout.instructions.isEmpty() && layout.instructions.get(0).getOpcode() == Opcode.MOVE_EXCEPTION) {
            findings.add("try: move-exception at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (layout.instructions.get(k).getOpcode() != Opcode.MOVE_EXCEPTION) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("try: move-exception at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }

        // A payload is data, and ART fails the class wherever flow reaches one. A branch, a case
        // or a handler sent to one is reported above; falling into one from the instruction above
        // it, which a return or goto removed from before a switch table leaves, and a payload at
        // the very start are the other ways in.
        if (!layout.instructions.isEmpty() && isPayload(layout.instructions.get(0))) {
            findings.add("branch: the payload at 0 is reached from the method's entry");
        }
        for (int k = 1; k < layout.instructions.size(); k++) {
            if (!isPayload(layout.instructions.get(k))) continue;
            Instruction above = layout.instructions.get(k - 1);
            if (kinds[k - 1] != null && above.getOpcode().canContinue()) {
                findings.add("branch: the payload at " + layout.addresses.get(k) + " is reached by falling through from "
                        + above.getOpcode().name + " at " + layout.addresses.get(k - 1));
            }
        }

        // Flow may not run off the end of the code either. A method whose last return was removed
        // or nopped leaves its last instruction able to continue into nothing, and ART refuses the
        // class ("Execution can walk off end of code area").
        if (!layout.instructions.isEmpty()) {
            int last = layout.instructions.size() - 1;
            Instruction end = layout.instructions.get(last);
            if (kinds[last] != null && !isPayload(end) && end.getOpcode().canContinue()) {
                findings.add("branch: " + end.getOpcode().name + " at " + layout.addresses.get(last)
                        + " runs off the end of the code");
            }
        }
        return findings;
    }

    /**
     * The highest register an instruction touches: every register it names, the upper half of a
     * wide value it writes, and the upper half of each wide value it reads (valueReads knows which
     * operands those are, opcode by opcode). -1 for an instruction with no register.
     */
    private static int reach(Instruction i) {
        int high = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            high = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            for (int register : invokeRegisters(i)) high = Math.max(high, register);
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            high = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            high = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            high = ((OneRegisterInstruction) i).getRegisterA();
        }
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            high = Math.max(high, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        for (int[] read : valueReads(i)) {
            if (read[1] == 'W') high = Math.max(high, read[0] + 1);
        }
        return high;
    }

    /**
     * "parameter" for a register that still holds its argument on every path, else "width". A
     * broken pair or a conflict is always the body's doing, even on an argument's register.
     */
    private static String origin(int register, int firstParameter, BitSet written, char have) {
        if (have == 'B' || have == 'b' || have == 'C') return "width: ";
        boolean argument = register >= firstParameter && !written.get(register - firstParameter);
        return argument ? "parameter: " : "width: ";
    }

    /** A switch or array payload: data inside the code, which ART fails the moment flow reaches it. */
    private static boolean isPayload(Instruction i) {
        return i instanceof SwitchPayload || i instanceof ArrayPayload;
    }

    /**
     * What a branch or switch case may not land on, or null: ART refuses a move-result or
     * move-exception there, and a payload is data ("encountered data table in instruction stream").
     */
    private static String badLanding(Instruction landing) {
        if (landing == null) return null;
        if (isPayload(landing)) return "a payload";
        Opcode opcode = landing.getOpcode();
        if (isMoveResult(opcode)) return "a move-result";
        if (opcode == Opcode.MOVE_EXCEPTION) return "a move-exception";
        return null;
    }

    /** The kind of each register an invoke passes: the receiver, then each parameter's slots. */
    private static List<Character> expectedArguments(Opcode opcode, MethodReference callee) {
        List<Character> expected = new ArrayList<>();
        if (!isStaticInvoke(opcode)) expected.add('L');
        for (CharSequence p : callee.getParameterTypes()) {
            expected.add(kindOf(p));
            if (slots(p) == 2) expected.add('w');
        }
        return expected;
    }

    private static String describe(char kind) {
        switch (kind) {
            case 'L': return "an object";
            case 'W': return "a wide value";
            case 'w': return "the upper half of a wide value";
            case 'B': return "the lower half of a wide value whose upper half was overwritten";
            case 'b': return "the upper half of a wide value whose lower half was overwritten";
            case 'C': return "a different kind depending on the path taken";
            case 'Z': return "a zero constant";
            case 'E': return "a narrow value or an object";
            default: return "a narrow value";
        }
    }

    /**
     * Holds every changed and added method of the patched APK to the structural rules, and counts
     * each contract's call sites across the whole APK. Returns method signature to findings, with
     * contract results under the pseudo-method "contract". [clean] is the build the patched one
     * came from, which a sole-call rule reads the call its hook replaced out of. [fresh] holds the
     * prints of each wanted method's definitions that the clean build doesn't have; a definition
     * the clean build has body for body, a copy in another dex entry the patch left alone, isn't
     * checked again.
     */
    private static Map<String, List<String>> structuralPass(File apk, File clean, Set<String> wanted,
            Map<String, List<String>> fresh, List<Contract> contracts) throws Exception {
        Map<String, List<String>> out = new TreeMap<>();
        Map<String, List<String>> unchecked = new HashMap<>();
        for (Map.Entry<String, List<String>> e : fresh.entrySet()) unchecked.put(e.getKey(), new ArrayList<>(e.getValue()));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, List<String>> callSites = new LinkedHashMap<>();
        // first-call: the method, and the first call it makes on the class before it returns, or
        // an empty string when it makes none there.
        Map<String, String> firstCalls = new HashMap<>();
        Map<String, String> firstCallTargets = new HashMap<>();
        // first-call on-type-named: the method, and the first call it makes on any class, whose
        // class is held afterwards to the classes whose getTypeName() answers the type.
        Map<String, String> typedFirstCallTargets = new HashMap<>();
        Map<String, Set<String>> typeNamedClasses = new HashMap<>();
        // first-call outside: the method, and the class prefix its first call has to leave.
        Map<String, String> outsideFirstCallTargets = new HashMap<>();
        // start-call, next-call, sole-call and once-call: for each rule, every method outside the
        // bundle's own code that loads all of its strings, with whether that method has the rule's shape. The
        // rules' own strings are the only ones collected, so a method is read once.
        List<Contract> pickRules = new ArrayList<>();
        Map<Contract, List<Holder>> holders = new LinkedHashMap<>();
        Set<String> pickStrings = new HashSet<>();
        // And every method outside the bundle's own code that calls such a rule's method, to say
        // where a hook went when it isn't in the method its rule picks, or went there as well.
        Map<String, List<String>> hookCallers = new HashMap<>();
        // no-call: the package prefix whose classes may call the method, and the methods of every
        // other class that do.
        Map<String, String> noCallInside = new HashMap<>();
        Map<String, List<String>> noCallSites = new LinkedHashMap<>();
        for (Contract contract : contracts) {
            if (contract.kind.equals("single-call")) callSites.put(contract.callee, new ArrayList<>());
            else if (contract.kind.equals("first-call")) firstCallTargets.put(contract.callee, contract.target);
            else if (contract.kind.equals(TYPED_FIRST_CALL)) {
                typedFirstCallTargets.put(contract.callee, contract.target);
                typeNamedClasses.put(contract.target, new TreeSet<>());
            } else if (contract.kind.equals(OUTSIDE_FIRST_CALL)) {
                outsideFirstCallTargets.put(contract.callee, contract.target);
            } else if (contract.kind.equals("no-call")) {
                noCallInside.put(contract.callee, contract.target);
                noCallSites.put(contract.callee, new ArrayList<>());
            } else if (contract.picks()) {
                pickRules.add(contract);
                holders.put(contract, new ArrayList<>());
                pickStrings.addAll(contract.strings);
                hookCallers.put(contract.callee, new ArrayList<>());
            }
        }
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, null);
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    List<String> left = wanted.contains(s) ? unchecked.get(s) : null;
                    if (left != null && left.remove(print(m, digest))) {
                        List<String> findings = structuralFindings(cd, m);
                        if (!findings.isEmpty()) out.computeIfAbsent(s, k -> new ArrayList<>()).addAll(findings);
                    }
                    String firstCallOn = firstCallTargets.get(s);
                    if (firstCallOn != null) firstCalls.put(s, firstCallBeforeReturn(m, firstCallOn));
                    if (typedFirstCallTargets.containsKey(s)) firstCalls.put(s, firstCallBeforeReturn(m, null));
                    String leaving = outsideFirstCallTargets.get(s);
                    if (leaving != null) firstCalls.put(s, firstCallOutside(m, leaving));
                    if (!typeNamedClasses.isEmpty()) recordTypeNamed(cd, m, typeNamedClasses);
                    if (!pickRules.isEmpty() && !cd.getType().startsWith(OWN)) {
                        recordHolders(s, m, pickRules, pickStrings, holders);
                    }
                    if ((callSites.isEmpty() && noCallSites.isEmpty() && hookCallers.isEmpty())
                            || m.getImplementation() == null) continue;
                    for (Instruction i : m.getImplementation().getInstructions()) {
                        if (!(i instanceof ReferenceInstruction)) continue;
                        Reference r = ((ReferenceInstruction) i).getReference();
                        if (!(r instanceof MethodReference)) continue;
                        List<String> sites = callSites.get(r.toString());
                        if (sites != null) sites.add(s);
                        String inside = noCallInside.get(r.toString());
                        if (inside != null && !cd.getType().startsWith(inside)) noCallSites.get(r.toString()).add(s);
                        List<String> callers = hookCallers.get(r.toString());
                        if (callers != null && !cd.getType().startsWith(OWN) && !callers.contains(s)) callers.add(s);
                    }
                }
            }
        }
        List<String> contractFindings = new ArrayList<>();
        for (Contract contract : contracts) {
            if (contract.kind.equals("family") || contract.kind.equals("mutation")) continue;
            if (contract.picks()) {
                checkPicked(contract, holders.get(contract), hookCallers.get(contract.callee), clean, contractFindings);
                continue;
            }
            if (contract.kind.equals("no-call")) {
                List<String> left = noCallSites.get(contract.callee);
                System.out.println("[diff] contract no-call " + contract.callee + " outside " + contract.target + ": "
                        + left.size() + " call site" + (left.size() == 1 ? "" : "s")
                        + (left.isEmpty() ? "" : ", in " + String.join(", ", left)));
                if (!left.isEmpty()) {
                    contractFindings.add("contract: " + contract.callee + " is still called outside "
                            + contract.target + ", in " + String.join(", ", left));
                }
                continue;
            }
            if (contract.kind.equals("first-call")) {
                String call = firstCalls.get(contract.callee);
                String rule = "contract first-call " + contract.callee;
                if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing calls "
                            + contract.target + " from it");
                } else if (call.isEmpty()) {
                    System.out.println("[diff] " + rule + ": no call on " + contract.target + " before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method of "
                            + contract.target + " that takes no arguments, so the patch didn't fill it");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            if (contract.kind.equals(OUTSIDE_FIRST_CALL)) {
                String call = firstCalls.get(contract.callee);
                String rule = "contract first-call " + contract.callee + " outside " + contract.target;
                if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing outside "
                            + contract.target + " is called from it");
                } else if (call.isEmpty()) {
                    System.out.println("[diff] " + rule + ": no call outside it before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method outside "
                            + contract.target + ", so the patch didn't fill it");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            if (contract.kind.equals(TYPED_FIRST_CALL)) {
                String call = firstCalls.get(contract.callee);
                Set<String> named = typeNamedClasses.get(contract.target);
                String rule = "contract first-call " + contract.callee + " on-type-named " + contract.target;
                String owner = call == null || call.isEmpty() ? null : call.substring(0, call.indexOf("->"));
                if (named.size() != 1) {
                    System.out.println("[diff] " + rule + ": " + named.size() + " classes answer it");
                    contractFindings.add("contract: " + named.size() + " classes answer getTypeName() with \""
                            + contract.target + "\", and exactly one must" + (named.isEmpty() ? "" : ": " + String.join(", ", named)));
                } else if (call == null) {
                    System.out.println("[diff] " + rule + ": not in the APK");
                    contractFindings.add("contract: " + contract.callee + " is not in the APK, so nothing calls "
                            + named.iterator().next() + " from it");
                } else if (owner == null) {
                    System.out.println("[diff] " + rule + ": no call before its first return");
                    contractFindings.add("contract: " + contract.callee + " returns before it calls a method of "
                            + named.iterator().next() + " that takes no arguments, so the patch didn't fill it");
                } else if (!named.contains(owner)) {
                    System.out.println("[diff] " + rule + ": calls " + call + ", not a method of " + named.iterator().next());
                    contractFindings.add("contract: " + contract.callee + " calls " + call + " first, not a method of "
                            + named.iterator().next() + ", the class answering \"" + contract.target + "\"");
                } else {
                    System.out.println("[diff] " + rule + ": calls " + call + " before its first return");
                }
                continue;
            }
            List<String> sites = callSites.get(contract.callee);
            System.out.println("[diff] contract " + contract.callee + ": " + sites.size() + " call site"
                    + (sites.size() == 1 ? "" : "s") + (sites.isEmpty() ? "" : ", in " + String.join(", ", sites)));
            if (sites.size() != 1) {
                contractFindings.add("contract: " + contract.callee + " has " + sites.size()
                        + " call sites, and must have exactly one, in " + contract.target
                        + (sites.isEmpty() ? "" : ": " + String.join(", ", sites)));
            } else if (!sites.get(0).contains("->" + contract.target + "(")) {
                contractFindings.add("contract: " + contract.callee + " is called from " + sites.get(0)
                        + ", not from " + contract.target);
            }
        }
        if (!contractFindings.isEmpty()) out.put("contract", contractFindings);
        return out;
    }

    /** Where a method calls a start-call rule's method: not at all, first thing, or later. */
    private static final int NOT_CALLED = 0;
    private static final int LATER = 1;
    private static final int FIRST = 2;

    /**
     * A method outside the bundle's own code that loads every string of a start-call, next-call,
     * sole-call or once-call rule: its signature, whether it has the rule's shape, and the method
     * itself, which the rule reads again once it knows which one it picked.
     */
    private static final class Holder {
        final String method;
        final boolean shaped;
        final Method m;

        Holder(String method, boolean shaped, Method m) {
            this.method = method;
            this.shaped = shaped;
            this.m = m;
        }
    }

    /** Adds [m] to each rule whose strings it loads, every one of them. */
    private static void recordHolders(String s, Method m, List<Contract> rules, Set<String> wanted,
            Map<Contract, List<Holder>> holders) {
        if (m.getImplementation() == null) return;
        Set<String> held = null;
        for (Instruction i : m.getImplementation().getInstructions()) {
            if (!(i instanceof ReferenceInstruction)) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (!(r instanceof StringReference) || !wanted.contains(((StringReference) r).getString())) continue;
            if (held == null) held = new HashSet<>();
            held.add(((StringReference) r).getString());
        }
        if (held == null) return;
        for (Contract rule : rules) {
            if (held.containsAll(rule.strings)) holders.get(rule).add(new Holder(s, rule.hasShape(m), m));
        }
    }

    /**
     * Holds a start-call, next-call, sole-call or once-call rule to the patched APK. Exactly one of
     * [holders] has the rule's shape, and it calls the rule's method; [callers] are the host methods
     * that call that method anywhere. Then each kind asks its own questions: start-call that no other
     * holder calls it and that the call comes first; once-call that no other host method calls it and
     * that it's the method's one call there; next-call that as well, right after the call it pairs
     * with and on the same register; sole-call that it's the method's one call, that the call it
     * stands in for is gone, and that it reads what that call read in [clean].
     */
    private static void checkPicked(Contract contract, List<Holder> holders, List<String> callers, File clean,
            List<String> findings) throws Exception {
        String rule = "contract " + contract.rule();
        String held = describePicked(contract);
        List<String> shaped = new ArrayList<>();
        Holder only = null;
        for (Holder holder : holders) {
            if (!holder.shaped) continue;
            shaped.add(holder.method);
            only = holder;
        }
        if (shaped.size() != 1) {
            // None, and the method moved or lost a string; several, and the rule can't tell the
            // right one from the others, so a hook in any of them would pass.
            System.out.println("[diff] " + rule + ": " + shaped.size() + " methods answer it" + named(shaped));
            findings.add("contract: " + shaped.size() + " methods hold " + held + ", and exactly one"
                    + " must, so the rule can't say which one calls " + contract.callee + named(shaped));
            return;
        }
        List<Instruction> body = instructions(only.m);
        List<Integer> sites = callSites(body, contract.callee);
        // Where else the hook went. A start-call rule looks among the methods holding its strings,
        // since the two tray rules send the same call to two adapters. A next-call, sole-call or
        // once-call hook belongs to one method, so a second call anywhere is one too many.
        List<String> elsewhere = new ArrayList<>();
        if (contract.kind.equals("start-call")) {
            for (Holder holder : holders) {
                if (holder != only && !elsewhere.contains(holder.method)
                        && !callSites(instructions(holder.m), contract.callee).isEmpty()) elsewhere.add(holder.method);
            }
        } else {
            for (String caller : callers) if (!caller.equals(only.method)) elsewhere.add(caller);
        }
        if (sites.isEmpty()) {
            System.out.println("[diff] " + rule + ": not called in " + only.method
                    + (callers.isEmpty() ? "" : "; called in " + String.join(", ", callers)));
            findings.add("contract: " + contract.callee + " is not called in " + only.method
                    + ", the one method holding " + held
                    + (callers.isEmpty() ? "" : "; the host methods that call it: " + String.join(", ", callers)));
        } else if (!elsewhere.isEmpty()) {
            System.out.println("[diff] " + rule + ": called in " + only.method + " and in " + String.join(", ", elsewhere));
            findings.add("contract: " + contract.callee + " is called in " + String.join(", ", elsewhere)
                    + " as well as in " + only.method + ", the one method holding " + held);
        } else if (contract.kind.equals("start-call")) {
            if (callOf(only.m, contract.callee) != FIRST) {
                System.out.println("[diff] " + rule + ": not first in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method
                        + ", but after a call, branch, switch, return or throw, not first");
            } else {
                System.out.println("[diff] " + rule + ": first in " + only.method);
            }
        } else if (sites.size() > 1) {
            System.out.println("[diff] " + rule + ": " + sites.size() + " call sites in " + only.method);
            findings.add("contract: " + contract.callee + " has " + sites.size() + " call sites in " + only.method
                    + ", and must have exactly one");
        } else if (contract.kind.equals("once-call")) {
            System.out.println("[diff] " + rule + ": once in " + only.method);
        } else if (contract.kind.equals("next-call")) {
            int at = sites.get(0);
            Instruction before = at == 0 ? null : body.get(at - 1);
            String previous = before != null && before.getOpcode().name.startsWith("invoke")
                    && before instanceof ReferenceInstruction ? ((ReferenceInstruction) before).getReference().toString() : null;
            int register = firstRegister(body.get(at));
            int previousRegister = before == null ? -1 : firstRegister(before);
            if (!contract.after.equals(previous)) {
                System.out.println("[diff] " + rule + ": after " + (previous == null ? "no call" : previous) + " in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method
                        + ", but not right after " + contract.after);
            } else if (previousRegister != register) {
                System.out.println("[diff] " + rule + ": on v" + register + ", after a call on v" + previousRegister
                        + " in " + only.method);
                findings.add("contract: " + contract.callee + " is called in " + only.method + " on v" + register
                        + ", not on v" + previousRegister + ", the register " + contract.after + " is made on");
            } else {
                System.out.println("[diff] " + rule + ": right after it on v" + register + " in " + only.method);
            }
        } else if (!callSites(body, contract.replaced).isEmpty()) {
            System.out.println("[diff] " + rule + ": " + only.method + " still calls " + contract.replaced);
            findings.add("contract: " + only.method + " still calls " + contract.replaced + ", which "
                    + contract.callee + " stands in for");
        } else {
            // What the hook reads, against what the call it took the place of read in the clean
            // build: the one such call there, on the same registers in the same order.
            List<Integer> hook = callRegisters(body.get(sites.get(0)));
            List<List<Integer>> was = cleanCallRegisters(clean, only.method, contract.replaced);
            if (was.size() == 1 && was.get(0).equals(hook)) {
                System.out.println("[diff] " + rule + ": in place of it on " + registerList(hook) + " in " + only.method);
            } else {
                String there = was.size() == 1 ? "on " + registerList(was.get(0)) : was.size() + " times, not once";
                System.out.println("[diff] " + rule + ": on " + registerList(hook) + " in " + only.method
                        + ", where the clean build calls it " + there);
                findings.add("contract: " + contract.callee + " is called in " + only.method + " on "
                        + registerList(hook) + ", but the clean build calls " + contract.replaced + " there " + there);
            }
        }
    }

    /** [m]'s instructions, in order. */
    private static List<Instruction> instructions(Method m) {
        List<Instruction> body = new ArrayList<>();
        if (m.getImplementation() != null) for (Instruction i : m.getImplementation().getInstructions()) body.add(i);
        return body;
    }

    /** The index in [body] of each invoke of [callee]. */
    private static List<Integer> callSites(List<Instruction> body, String callee) {
        List<Integer> sites = new ArrayList<>();
        for (int k = 0; k < body.size(); k++) {
            Instruction i = body.get(k);
            if (i.getOpcode().name.startsWith("invoke") && i instanceof ReferenceInstruction
                    && ((ReferenceInstruction) i).getReference().toString().equals(callee)) sites.add(k);
        }
        return sites;
    }

    /** The registers an invoke passes, in order, as a list. */
    private static List<Integer> callRegisters(Instruction i) {
        List<Integer> registers = new ArrayList<>();
        for (int register : invokeRegisters(i)) registers.add(register);
        return registers;
    }

    /** "v2, v1" for [registers]. */
    private static String registerList(List<Integer> registers) {
        List<String> named = new ArrayList<>();
        for (int register : registers) named.add("v" + register);
        return String.join(", ", named);
    }

    /**
     * The registers of each call to [callee] that [method] makes in [clean], in every dex entry
     * defining it. Only its own class's methods are read.
     */
    private static List<List<Integer>> cleanCallRegisters(File clean, String method, String callee) throws Exception {
        String type = method.substring(0, method.indexOf("->"));
        List<List<Integer>> calls = new ArrayList<>();
        MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(clean, null);
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                if (!cd.getType().equals(type)) continue;
                for (Method m : cd.getMethods()) {
                    if (!sig(cd, m).equals(method)) continue;
                    List<Instruction> body = instructions(m);
                    for (int at : callSites(body, callee)) calls.add(callRegisters(body.get(at)));
                }
            }
        }
        return calls;
    }

    /**
     * FIRST when [m]'s first call to [callee] has only plain instructions before it (no other
     * call, branch, switch, return or throw), LATER when it calls it after one, NOT_CALLED when it
     * doesn't call it at all.
     */
    private static int callOf(Method m, String callee) {
        boolean plainSoFar = true;
        boolean called = false;
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("invoke") && i instanceof ReferenceInstruction
                    && ((ReferenceInstruction) i).getReference().toString().equals(callee)) {
                if (plainSoFar) return FIRST;
                called = true;
            }
            if (name.startsWith("invoke") || name.startsWith("return") || name.startsWith("goto")
                    || name.startsWith("if-") || name.endsWith("-switch") || name.equals("throw")) {
                plainSoFar = false;
            }
        }
        return called ? LATER : NOT_CALLED;
    }

    /** A picking rule's strings as a finding names them, and its shape when it has one. */
    private static String describePicked(Contract rule) {
        List<String> quoted = new ArrayList<>();
        for (String s : rule.strings) quoted.add("\"" + s + "\"");
        String strings = quoted.size() == 1 ? quoted.get(0)
                : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.get(quoted.size() - 1);
        if (rule.shape == null) return strings;
        return strings + " with the shape " + (rule.isStatic == null ? "" : rule.isStatic ? "static " : "instance ") + rule.shape;
    }

    /** ": " and the first eight of [methods], or nothing for none. */
    private static String named(List<String> methods) {
        if (methods.isEmpty()) return "";
        List<String> shown = methods.subList(0, Math.min(8, methods.size()));
        return ": " + String.join(", ", shown) + (methods.size() > shown.size() ? " and " + (methods.size() - shown.size()) + " more" : "");
    }

    /** The first register an invoke names, or -1 for an instruction that isn't one. */
    private static int firstRegister(Instruction i) {
        if (i instanceof RegisterRangeInstruction) return ((RegisterRangeInstruction) i).getStartRegister();
        if (i instanceof FiveRegisterInstruction && ((FiveRegisterInstruction) i).getRegisterCount() > 0) {
            return ((FiveRegisterInstruction) i).getRegisterC();
        }
        return -1;
    }

    /**
     * Adds [cd] to the classes of each type name its {@code getTypeName()} loads as a literal, for
     * the type names a contract asks about.
     */
    private static void recordTypeNamed(ClassDef cd, Method m, Map<String, Set<String>> typeNamedClasses) {
        if (!m.getName().equals("getTypeName") || !m.getReturnType().equals("Ljava/lang/String;")
                || !m.getParameterTypes().isEmpty() || m.getImplementation() == null) {
            return;
        }
        for (Instruction i : m.getImplementation().getInstructions()) {
            if (!(i instanceof ReferenceInstruction)) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (!(r instanceof StringReference)) continue;
            Set<String> classes = typeNamedClasses.get(((StringReference) r).getString());
            if (classes != null) classes.add(cd.getType());
        }
    }

    /**
     * The first call [m] makes, before any return or throw in its instruction order, to a method of
     * [owner], or of any class when [owner] is null, that takes no arguments, or an empty string
     * when there is none. A filled stub makes that call first thing; the stub's own body, left after
     * it, only answers a marker.
     */
    private static String firstCallBeforeReturn(Method m, String owner) {
        if (m.getImplementation() == null) return "";
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("return") || name.equals("throw")) return "";
            if (!(i instanceof ReferenceInstruction) || !name.startsWith("invoke")) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof MethodReference
                    && (owner == null || ((MethodReference) r).getDefiningClass().equals(owner))
                    && ((MethodReference) r).getParameterTypes().isEmpty()) {
                return r.toString();
            }
        }
        return "";
    }

    /**
     * The first call [m] makes, before any return or throw in its instruction order, to a method of
     * a class whose type doesn't start with [prefix], whatever it takes, or an empty string when
     * there is none. A call into the extension on the way there doesn't count, and doesn't stop the
     * search.
     */
    private static String firstCallOutside(Method m, String prefix) {
        if (m.getImplementation() == null) return "";
        for (Instruction i : m.getImplementation().getInstructions()) {
            String name = i.getOpcode().name;
            if (name.startsWith("return") || name.equals("throw")) return "";
            if (!(i instanceof ReferenceInstruction) || !name.startsWith("invoke")) continue;
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r instanceof MethodReference && !((MethodReference) r).getDefiningClass().startsWith(prefix)) {
                return r.toString();
            }
        }
        return "";
    }

    private static Set<String> dexEntries(File apk) throws Exception {
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, null);
        return new TreeSet<>(container.getDexEntryNames());
    }

    /**
     * Signature -> the print of each of its definitions ("registerCount:bodyHash"), sorted and
     * joined with a space, for every method of an APK.
     *
     * <p>A signature can be defined in more than one dex entry. The Facebook sibling's 580 split
     * bundle, merged, carries the in-app browser's standalone dex twice (lib/arm64-v8a/libhelium_standalone.dex.so
     * and assets/heliumcore/helium_standalone.dex.force-store), and 480 of its methods are in
     * classes*.dex too, 212 of them with other bodies. dexlib2 reads the entries in name order, and
     * keyed one body to a signature, the last copy stood for every one: the browser's copy in lib/
     * hid whatever a patch did to the classes*.dex one, and read against base.apk, which has no
     * such copy, those 212 came out changed. So every definition counts, and a signature is changed
     * when the multiset of its definitions' prints is.
     */
    private static Map<String, String> fingerprintAll(File apk) throws Exception {
        Map<String, String> out = new HashMap<>(1 << 20);
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, null);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) out.merge(sig(cd, m), print(m, digest), DexDiff::joinPrints);
            }
        }
        return out;
    }

    /**
     * What keeps [merged] from carrying every classes*.dex at [base]'s root byte for byte, and no
     * other classes*.dex there: "lacks", "changes" and "adds" with the entry names, or nothing.
     *
     * <p>The comparison reads a bundle's merge, which carries no signature, while Meta's signer is
     * checked on base.apk and the device half runs base.apk. The CLI's merger copies base.apk's
     * classes*.dex as they are; other dex a merge carries (the Facebook sibling's 580 in-app
     * browser's, from split_heliumcore.apk, twice) came from the splits under other names.
     */
    private static List<String> rootDexMismatch(File base, File merged) throws Exception {
        Map<String, String> baseDex = rootDexDigests(base);
        Map<String, String> mergedDex = rootDexDigests(merged);
        if (baseDex.isEmpty()) return List.of("was handed a " + base.getName() + " with no classes*.dex to hold it to");
        List<String> lacks = new ArrayList<>(), changes = new ArrayList<>(), adds = new ArrayList<>();
        for (Map.Entry<String, String> e : baseDex.entrySet()) {
            String other = mergedDex.get(e.getKey());
            if (other == null) lacks.add(e.getKey());
            else if (!other.equals(e.getValue())) changes.add(e.getKey());
        }
        for (String name : mergedDex.keySet()) if (!baseDex.containsKey(name)) adds.add(name);
        List<String> said = new ArrayList<>();
        if (!lacks.isEmpty()) said.add("lacks " + String.join(", ", lacks));
        if (!changes.isEmpty()) said.add("changes " + String.join(", ", changes));
        if (!adds.isEmpty()) said.add("adds " + String.join(", ", adds));
        return said;
    }

    /** classes*.dex entry name at an APK's root -> the SHA-256 of its bytes. */
    private static Map<String, String> rootDexDigests(File apk) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    byte[] buffer = new byte[1 << 16];
                    for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
                }
                out.put(entry.getName(), java.util.HexFormat.of().formatHex(digest.digest()));
            }
        }
        return out;
    }

    /** One definition's "registerCount:bodyHash": its register count and its rendered body's hash. */
    private static String print(Method m, MessageDigest digest) throws Exception {
        MethodImplementation impl = m.getImplementation();
        StringBuilder body = new StringBuilder();
        int registers = 0;
        if (impl != null) {
            registers = impl.getRegisterCount();
            for (Instruction i : impl.getInstructions()) body.append(render(i)).append('\n');
            for (String t : tryBlocks(impl)) body.append(t).append('\n');
        }
        digest.reset();
        byte[] hash = digest.digest(body.toString().getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (int k = 0; k < 8; k++) hex.append(String.format("%02x", hash[k]));
        return registers + ":" + hex;
    }

    /** Two joined print lists as one, sorted, so the same definitions compare equal in any order. */
    private static String joinPrints(String a, String b) {
        List<String> all = new ArrayList<>(prints(a));
        all.addAll(prints(b));
        java.util.Collections.sort(all);
        return String.join(" ", all);
    }

    /** The prints fingerprintAll joined for one signature, one per definition; none for null. */
    private static List<String> prints(String joined) {
        return joined == null ? List.of() : Arrays.asList(joined.split(" "));
    }

    /** How many of fingerprintAll's signatures have more than one definition. */
    private static int multiplyDefined(Map<String, String> prints) {
        int count = 0;
        for (String joined : prints.values()) if (joined.indexOf(' ') >= 0) count++;
        return count;
    }

    /** The items of [a] that [b] doesn't hold, counting duplicates. */
    private static <T> List<T> without(List<T> a, List<T> b) {
        List<T> left = new ArrayList<>(b);
        List<T> out = new ArrayList<>();
        for (T item : a) if (!left.remove(item)) out.add(item);
        return out;
    }

    /** Signature -> the rendered body of each of its definitions, in dex entry order, for the named methods only. */
    private static Map<String, List<List<String>>> bodiesOf(File apk, Set<String> wanted) throws Exception {
        Map<String, List<List<String>>> out = new LinkedHashMap<>();
        MultiDexContainer<? extends DexFile> container =
                DexFileFactory.loadDexContainer(apk, null);
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method m : cd.getMethods()) {
                    String s = sig(cd, m);
                    if (!wanted.contains(s)) continue;
                    List<String> body = new ArrayList<>();
                    MethodImplementation impl = m.getImplementation();
                    if (impl != null) {
                        body.add("# registers=" + impl.getRegisterCount());
                        for (Instruction i : impl.getInstructions()) body.add(render(i));
                        body.addAll(tryBlocks(impl));
                    }
                    out.computeIfAbsent(s, k -> new ArrayList<>()).add(body);
                }
            }
        }
        return out;
    }

    /** Each try block as a line, so an exception range that moved is a body that changed. */
    private static List<String> tryBlocks(MethodImplementation impl) {
        List<String> out = new ArrayList<>();
        for (TryBlock<? extends ExceptionHandler> block : impl.getTryBlocks()) {
            StringBuilder b = new StringBuilder("# try start=").append(block.getStartCodeAddress())
                    .append(" units=").append(block.getCodeUnitCount());
            for (ExceptionHandler handler : block.getExceptionHandlers()) {
                b.append(" catch(").append(handler.getExceptionType())
                        .append(")->").append(handler.getHandlerCodeAddress());
            }
            out.add(b.toString());
        }
        return out;
    }

    private static String sig(ClassDef cd, Method m) {
        StringBuilder b = new StringBuilder(cd.getType()).append("->").append(m.getName()).append('(');
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    /** One instruction as text: opcode, registers, branch offset, literal, reference, max register. */
    private static String render(Instruction i) {
        StringBuilder b = new StringBuilder(i.getOpcode().name);
        List<String> regs = new ArrayList<>();
        int maxReg = -1;
        if (i instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction r = (RegisterRangeInstruction) i;
            regs.add("v" + r.getStartRegister() + "..v" + (r.getStartRegister() + r.getRegisterCount() - 1));
            maxReg = r.getStartRegister() + r.getRegisterCount() - 1;
        } else if (i instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction r = (FiveRegisterInstruction) i;
            int n = r.getRegisterCount();
            int[] all = { r.getRegisterC(), r.getRegisterD(), r.getRegisterE(), r.getRegisterF(), r.getRegisterG() };
            for (int k = 0; k < n; k++) { regs.add("v" + all[k]); maxReg = Math.max(maxReg, all[k]); }
        } else if (i instanceof ThreeRegisterInstruction) {
            ThreeRegisterInstruction r = (ThreeRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            regs.add("v" + r.getRegisterC());
            maxReg = Math.max(r.getRegisterA(), Math.max(r.getRegisterB(), r.getRegisterC()));
        } else if (i instanceof TwoRegisterInstruction) {
            TwoRegisterInstruction r = (TwoRegisterInstruction) i;
            regs.add("v" + r.getRegisterA());
            regs.add("v" + r.getRegisterB());
            maxReg = Math.max(r.getRegisterA(), r.getRegisterB());
        } else if (i instanceof OneRegisterInstruction) {
            regs.add("v" + ((OneRegisterInstruction) i).getRegisterA());
            maxReg = ((OneRegisterInstruction) i).getRegisterA();
        }
        // A wide destination names its low half and occupies the pair, so it reaches one higher
        // than it says. Only the destination: dexlib2 does not describe which sources are wide,
        // and guessing there would fail a valid narrow destination sitting above a wide source.
        if (i.getOpcode().setsWideRegister() && i instanceof OneRegisterInstruction) {
            maxReg = Math.max(maxReg, ((OneRegisterInstruction) i).getRegisterA() + 1);
        }
        if (!regs.isEmpty()) b.append(' ').append(String.join(", ", regs));
        if (i instanceof OffsetInstruction) {
            b.append(", ").append(String.format("%+d", ((OffsetInstruction) i).getCodeOffset()));
        }
        if (i instanceof WideLiteralInstruction) {
            b.append(", #").append(((WideLiteralInstruction) i).getWideLiteral());
        }
        if (i instanceof ReferenceInstruction) {
            Reference r = ((ReferenceInstruction) i).getReference();
            if (r != null) b.append(", ").append(r);
        }
        // A payload's cases and values are part of the body: a case moved to another target
        // changed nothing else, and left out of the text, the method read as untouched.
        if (i instanceof SwitchPayload) {
            for (SwitchElement e : ((SwitchPayload) i).getSwitchElements()) {
                b.append(' ').append(e.getKey()).append("->").append(String.format("%+d", e.getOffset()));
            }
        }
        if (i instanceof ArrayPayload) {
            b.append(" width=").append(((ArrayPayload) i).getElementWidth());
            for (Number n : ((ArrayPayload) i).getArrayElements()) b.append(' ').append(n);
        }
        return b.append(" |maxreg=").append(maxReg).toString();
    }

    /** Compiled contracts use the clean target, never a hook's own presence, to locate callers. */
    private static final class FeatureIndex {
        final Map<String, ClassDef> classes = new LinkedHashMap<>();
        final Map<String, Method> methods = new LinkedHashMap<>();
        final Set<String> duplicates = new HashSet<>();
        final Map<String, Set<String>> texts = new HashMap<>();
        final Map<String, Set<String>> classTexts = new HashMap<>();
        final Map<String, List<FeatureSite>> ownCalls = new LinkedHashMap<>();

        FeatureIndex(File apk) throws Exception {
            MultiDexContainer<? extends DexFile> container = DexFileFactory.loadDexContainer(apk, null);
            for (String entry : container.getDexEntryNames()) for (ClassDef cd : container.getEntry(entry).getDexFile().getClasses()) {
                if (classes.putIfAbsent(cd.getType(), cd) != null) duplicates.add(cd.getType());
                for (Method m : cd.getMethods()) {
                    String key = m.toString();
                    if (methods.putIfAbsent(key, m) != null) duplicates.add(key);
                    Set<String> strings = new HashSet<>();
                    if (m.getImplementation() != null) {
                        int at = 0;
                        for (Instruction i : m.getImplementation().getInstructions()) {
                            Reference r = reference(i);
                            if (r instanceof StringReference) strings.add(((StringReference) r).getString());
                            if (!cd.getType().startsWith(OWN) && r instanceof MethodReference && r.toString().startsWith(OWN)) {
                                ownCalls.computeIfAbsent(r.toString(), k -> new ArrayList<>()).add(new FeatureSite(m, at));
                            }
                            at++;
                        }
                    }
                    texts.put(key, strings);
                    classTexts.computeIfAbsent(cd.getType(), k -> new HashSet<>()).addAll(strings);
                }
            }
        }

        List<Method> holding(String text) {
            List<Method> out = new ArrayList<>();
            for (Method m : methods.values()) if (!m.getDefiningClass().startsWith(OWN)
                    && texts.get(m.toString()).contains(text)) out.add(m);
            return out;
        }

        List<ClassDef> classesHolding(String text) {
            List<ClassDef> out = new ArrayList<>();
            for (ClassDef cd : classes.values()) if (!cd.getType().startsWith(OWN)
                    && classTexts.getOrDefault(cd.getType(), Set.of()).contains(text)) out.add(cd);
            return out;
        }
    }

    private static final class FeatureSite {
        final Method method;
        final int at;
        FeatureSite(Method method, int at) { this.method = method; this.at = at; }
        String key() { return method + "@" + at; }
    }

    private static final class FeatureEdits {
        final Set<Integer> removed = new HashSet<>();
        final Map<Integer, Instruction> replacements = new HashMap<>();
    }

    /** Executable successors, including switch cases and only the handlers an instruction can reach. */
    private static final class FeatureFlow {
        final Layout layout;
        final int registers;
        final Map<Integer, Object> parameters = new HashMap<>();
        final List<List<Integer>> normal = new ArrayList<>();
        final List<List<Integer>> handlers = new ArrayList<>();
        final Map<Integer, Integer> indexAt = new HashMap<>();
        final BitSet loopEntries = new BitSet();

        FeatureFlow(Method m) {
            layout = new Layout(m.getImplementation());
            registers = m.getImplementation().getRegisterCount();
            int parameter = registers;
            List<? extends CharSequence> types = m.getParameterTypes();
            for (int n = types.size() - 1; n >= 0; n--) {
                String type = types.get(n).toString();
                boolean wide = type.equals("J") || type.equals("D");
                parameter -= wide ? 2 : 1;
                if (parameter < 0) break; // Structural validation reports malformed parameter layouts.
                if (wide) {
                    parameters.put(parameter, new WideValue(null, parameter));
                    parameters.put(parameter + 1, new HighHalf(parameter));
                } else if (type.startsWith("L") || type.startsWith("[")) parameters.put(parameter, new ObjectValue(parameter, false));
            }
            if (!AccessFlags.STATIC.isSet(m.getAccessFlags()) && parameter > 0) parameters.put(parameter - 1, new ObjectValue(parameter - 1, true));
            for (int at = 0; at < layout.instructions.size(); at++) indexAt.put(layout.addresses.get(at), at);
            for (int at = 0; at < layout.instructions.size(); at++) {
                Instruction i = layout.instructions.get(at);
                int address = layout.addresses.get(at);
                List<Integer> next = new ArrayList<>();
                List<Integer> caught = new ArrayList<>();
                if (i.getOpcode().canContinue()) add(next, address + i.getCodeUnits());
                if (i instanceof OffsetInstruction && i.getOpcode() != Opcode.FILL_ARRAY_DATA) {
                    int target = address + ((OffsetInstruction) i).getCodeOffset();
                    if (i.getOpcode() == Opcode.PACKED_SWITCH || i.getOpcode() == Opcode.SPARSE_SWITCH) {
                        Instruction payload = layout.byAddress.get(target);
                        if (payload instanceof SwitchPayload) for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements())
                            add(next, address + element.getOffset());
                    } else add(next, target);
                }
                if (i.getOpcode().canThrow()) for (TryBlock<? extends ExceptionHandler> block : m.getImplementation().getTryBlocks()) {
                    if (address < block.getStartCodeAddress() || address >= block.getStartCodeAddress() + block.getCodeUnitCount()) continue;
                    for (ExceptionHandler handler : block.getExceptionHandlers()) add(caught, handler.getHandlerCodeAddress());
                }
                normal.add(next);
                handlers.add(caught);
                for (int target : next) if (target <= at) loopEntries.set(target);
            }
        }

        void add(List<Integer> into, int address) {
            Integer index = indexAt.get(address);
            if (index != null && !isPayload(layout.instructions.get(index))) into.add(index);
        }

        BitSet reachable(int start, boolean exceptions) {
            BitSet reached = new BitSet();
            Deque<Integer> work = new ArrayDeque<>();
            if (start >= 0 && start < normal.size()) work.add(start);
            while (!work.isEmpty()) {
                int at = work.poll();
                if (reached.get(at)) continue;
                reached.set(at);
                work.addAll(normal.get(at));
                if (exceptions) work.addAll(handlers.get(at));
            }
            return reached;
        }

        private record ObjectValue(int alias, boolean nonNull) {}
        private record WideValue(Long bits, int alias) {}
        private record HighHalf(int low) {}
        private record ControlState(int at, Map<Integer, Object> values, boolean controlled) {}
        private record ControlPoint(int at, boolean controlled) {}

        static int valueRegister(Instruction i) { return ((OneRegisterInstruction) i).getRegisterA(); }

        /** Normal paths after this control answers false. Unknown inputs explore both arms, not a proof of all paths. */
        BitSet afterControl(int call) {
            List<Instruction> body = layout.instructions;
            Reference ref = reference(body.get(call));
            if (!(ref instanceof MethodReference) || !((MethodReference) ref).getReturnType().equals("Z")
                    || call + 1 >= body.size() || body.get(call + 1).getOpcode() != Opcode.MOVE_RESULT)
                return reachable(call + 1, false);
            BitSet reached = new BitSet();
            Set<ControlState> seen = new HashSet<>();
            Map<ControlPoint, Map<Integer, Object>> loops = new HashMap<>();
            Deque<ControlState> work = new ArrayDeque<>();
            work.add(new ControlState(0, Map.copyOf(parameters), false));
            while (!work.isEmpty()) {
                ControlState state = work.poll();
                if (loopEntries.get(state.at)) {
                    ControlPoint point = new ControlPoint(state.at, state.controlled);
                    Map<Integer, Object> previous = loops.get(point);
                    // A changing loop value becomes unknown once and stays unknown. Stable booleans survive.
                    Map<Integer, Object> joined = new HashMap<>(previous == null ? state.values : previous);
                    Map<Integer, Object> incoming = state.values;
                    joined.entrySet().removeIf(entry -> {
                        Object before = entry.getValue(), after = incoming.get(entry.getKey());
                        if (before.equals(after)) return false;
                        if ((before instanceof ObjectValue || Integer.valueOf(0).equals(before))
                                && (after instanceof ObjectValue || Integer.valueOf(0).equals(after))) {
                            entry.setValue(new ObjectValue(-1, before instanceof ObjectValue a && a.nonNull
                                    && after instanceof ObjectValue b && b.nonNull));
                            return false;
                        }
                        if (before instanceof WideValue && after instanceof WideValue) {
                            entry.setValue(new WideValue(null, -1));
                            return false;
                        }
                        return true;
                    });
                    Map<Integer, Object> values = Map.copyOf(joined);
                    loops.put(point, values);
                    state = new ControlState(state.at, values, state.controlled);
                }
                if (!seen.add(state)) continue;
                int at = state.at;
                Instruction i = body.get(at);
                boolean controlled = state.controlled || at == call + 1;
                if (controlled) reached.set(at);
                Map<Integer, Object> values = state.values;
                if (i.getOpcode().setsRegister()) {
                    // A widened value is initialized but has no stable identity. Give a copied
                    // source a fresh identity before clobbering overlapping destinations.
                    if (i instanceof TwoRegisterInstruction copy) {
                        int source = copy.getRegisterB();
                        Object value = values.get(source), replacement = null;
                        if (i.getOpcode().name.startsWith("move-object") && value instanceof ObjectValue object && object.alias < 0)
                            replacement = new ObjectValue(nextAlias(values), object.nonNull);
                        if (i.getOpcode().name.startsWith("move-wide") && wide(values, source) instanceof WideValue wide
                                && wide.bits == null && wide.alias < 0)
                            replacement = new WideValue(null, nextAlias(values));
                        if (replacement != null) {
                            values = new HashMap<>(values);
                            values.put(source, replacement);
                        }
                    }
                    int destination = valueRegister(i);
                    Map<Integer, Object> written = new HashMap<>(values);
                    forget(written, destination);
                    if (i.getOpcode().setsWideRegister()) {
                        forget(written, destination + 1);
                        WideValue value = wideValue(i, values);
                        if (value == null && !i.getOpcode().name.startsWith("move-wide")) value = new WideValue(null, nextAlias(values));
                        if (value != null && destination >= 0 && destination + 1 < registers) {
                            written.put(destination, value);
                            written.put(destination + 1, new HighHalf(destination));
                        }
                    } else if (at == call + 1) written.put(destination, 0);
                    else if (copiesConflict(i.getOpcode())) {
                        int sourceRegister = ((TwoRegisterInstruction) i).getRegisterB();
                        Object source = values.get(sourceRegister);
                        boolean objectMove = i.getOpcode().name.startsWith("move-object");
                        if (objectMove ? source instanceof ObjectValue || Integer.valueOf(0).equals(source)
                                : source instanceof Integer) written.put(destination, source);
                    } else if (i instanceof NarrowLiteralInstruction && i.getOpcode().name.startsWith("const"))
                        written.put(destination, ((NarrowLiteralInstruction) i).getNarrowLiteral());
                    else if (i.getOpcode() == Opcode.CHECK_CAST) {
                        if (values.containsKey(destination)) written.put(destination, values.get(destination));
                    } else if (i.getOpcode().name.contains("object") || i.getOpcode() == Opcode.NEW_INSTANCE
                            || i.getOpcode() == Opcode.NEW_ARRAY || i.getOpcode() == Opcode.CONST_STRING
                            || i.getOpcode() == Opcode.CONST_STRING_JUMBO || i.getOpcode() == Opcode.CONST_CLASS)
                        written.put(destination, new ObjectValue(nextAlias(values), i.getOpcode().name.startsWith("new-") || i.getOpcode().name.startsWith("const-")));
                    else {
                        Integer value = integerValue(i, values);
                        if (value != null) written.put(destination, value);
                    }
                    values = Map.copyOf(written);
                }
                if ((i.getOpcode().name.startsWith("div-int") || i.getOpcode().name.startsWith("rem-int"))
                        && Integer.valueOf(0).equals(integerRight(i, state.values))) continue;
                Integer chosen = null;
                Boolean taken = branchTaken(i, values);
                if (taken != null) chosen = taken
                        ? indexAt.getOrDefault(layout.addresses.get(at) + ((OffsetInstruction) i).getCodeOffset(), -1) : at + 1;
                else if ((i.getOpcode() == Opcode.PACKED_SWITCH || i.getOpcode() == Opcode.SPARSE_SWITCH) && integer(values.get(valueRegister(i))) != null) {
                    chosen = at + 1;
                    Instruction payload = layout.byAddress.get(layout.addresses.get(at) + ((OffsetInstruction) i).getCodeOffset());
                    if (payload instanceof SwitchPayload) for (SwitchElement element : ((SwitchPayload) payload).getSwitchElements())
                        if (element.getKey() == integer(values.get(valueRegister(i)))) chosen = indexAt.getOrDefault(layout.addresses.get(at) + element.getOffset(), -1);
                }
                for (int next : normal.get(at)) if (chosen == null || chosen == next)
                    work.add(new ControlState(next, values, controlled));
            }
            return reached;
        }

        /** Alias numbers name equal values within one state, never object allocation sites across iterations. */
        static int nextAlias(Map<Integer, Object> values) {
            Set<Integer> used = new HashSet<>();
            for (Object value : values.values()) {
                if (value instanceof ObjectValue object) used.add(object.alias);
                if (value instanceof WideValue wide && wide.bits == null) used.add(wide.alias);
            }
            int next = 0;
            while (used.contains(next)) next++;
            return next;
        }

        static void forget(Map<Integer, Object> values, int register) {
            Object old = values.remove(register);
            if (old instanceof WideValue) values.remove(register + 1);
            if (old instanceof HighHalf high) values.remove(high.low);
        }

        static WideValue wide(Map<Integer, Object> values, int register) {
            return values.get(register) instanceof WideValue value && new HighHalf(register).equals(values.get(register + 1)) ? value : null;
        }

        static WideValue wideValue(Instruction i, Map<Integer, Object> values) {
            if (i.getOpcode().name.startsWith("const-wide")) return new WideValue(((WideLiteralInstruction) i).getWideLiteral(), -1);
            if (i instanceof TwoRegisterInstruction pair) {
                if (i.getOpcode().name.startsWith("move-wide")) return wide(values, pair.getRegisterB());
                Integer source = integer(values.get(pair.getRegisterB()));
                if (source != null && i.getOpcode() == Opcode.INT_TO_LONG) return new WideValue(source.longValue(), -1);
                if (source != null && i.getOpcode() == Opcode.INT_TO_DOUBLE) return new WideValue(Double.doubleToRawLongBits(source.doubleValue()), -1);
            }
            return null;
        }

        static Integer integer(Object value) { return value instanceof Integer scalar ? scalar : null; }

        static Integer integerRight(Instruction i, Map<Integer, Object> values) {
            if (i instanceof NarrowLiteralInstruction) return ((NarrowLiteralInstruction) i).getNarrowLiteral();
            if (i instanceof ThreeRegisterInstruction) return integer(values.get(((ThreeRegisterInstruction) i).getRegisterC()));
            return i instanceof TwoRegisterInstruction ? integer(values.get(((TwoRegisterInstruction) i).getRegisterB())) : null;
        }

        /** Exact 32-bit scalar operations. Unsupported writes and unknown operands discard the destination. */
        static Integer integerValue(Instruction i, Map<Integer, Object> values) {
            if (i.getOpcode() == Opcode.CMP_LONG || i.getOpcode() == Opcode.CMPL_DOUBLE || i.getOpcode() == Opcode.CMPG_DOUBLE) {
                ThreeRegisterInstruction comparison = (ThreeRegisterInstruction) i;
                WideValue first = wide(values, comparison.getRegisterB()), second = wide(values, comparison.getRegisterC());
                if (first == null || second == null) return null;
                if (i.getOpcode() == Opcode.CMP_LONG && (comparison.getRegisterB() == comparison.getRegisterC()
                        || first.equals(second) && (first.bits != null || first.alias >= 0))) return 0;
                if (first.bits == null || second.bits == null) return null;
                if (i.getOpcode() == Opcode.CMP_LONG) return Long.compare(first.bits, second.bits);
                double a = Double.longBitsToDouble(first.bits), b = Double.longBitsToDouble(second.bits);
                if (Double.isNaN(a) || Double.isNaN(b)) return i.getOpcode() == Opcode.CMPL_DOUBLE ? -1 : 1;
                return a == b ? 0 : a < b ? -1 : 1;
            }
            if (i instanceof TwoRegisterInstruction) {
                Integer source = integer(values.get(((TwoRegisterInstruction) i).getRegisterB()));
                if (source != null) switch (i.getOpcode()) {
                    case NEG_INT: return -source;
                    case NOT_INT: return ~source;
                    case INT_TO_BYTE: return (int) (byte) source.intValue();
                    case INT_TO_CHAR: return (int) (char) source.intValue();
                    case INT_TO_SHORT: return (int) (short) source.intValue();
                    default: break;
                }
            }
            int leftRegister;
            if (i instanceof ThreeRegisterInstruction) leftRegister = ((ThreeRegisterInstruction) i).getRegisterB();
            else if (i instanceof TwoRegisterInstruction && i.getOpcode().name.endsWith("/2addr")) leftRegister = valueRegister(i);
            else if (i instanceof TwoRegisterInstruction && i instanceof NarrowLiteralInstruction)
                leftRegister = ((TwoRegisterInstruction) i).getRegisterB();
            else return null;
            Integer first = integer(values.get(leftRegister));
            Integer second = integerRight(i, values);
            String operation = i.getOpcode().name.split("/", 2)[0];
            if (operation.equals("and-int") && (Integer.valueOf(0).equals(first) || Integer.valueOf(0).equals(second))) return 0;
            if (operation.equals("or-int") && (Integer.valueOf(-1).equals(first) || Integer.valueOf(-1).equals(second))) return -1;
            if ((operation.equals("xor-int") || operation.equals("sub-int")) && !(i instanceof NarrowLiteralInstruction)) {
                int rightRegister = i instanceof ThreeRegisterInstruction ? ((ThreeRegisterInstruction) i).getRegisterC()
                        : ((TwoRegisterInstruction) i).getRegisterB();
                if (leftRegister == rightRegister) return 0;
            }
            if (first == null || second == null) return null;
            return switch (operation) {
                case "add-int" -> first + second;
                case "sub-int" -> first - second;
                case "rsub-int" -> second - first;
                case "mul-int" -> first * second;
                case "div-int" -> second == 0 ? null : first / second;
                case "rem-int" -> second == 0 ? null : first % second;
                case "and-int" -> first & second;
                case "or-int" -> first | second;
                case "xor-int" -> first ^ second;
                case "shl-int" -> first << second;
                case "shr-int" -> first >> second;
                case "ushr-int" -> first >>> second;
                default -> null;
            };
        }

        static Boolean branchTaken(Instruction i, Map<Integer, Object> values) {
            if (!i.getOpcode().name.startsWith("if-")) return null;
            Object firstValue = values.get(valueRegister(i));
            Object secondValue = i instanceof TwoRegisterInstruction ? values.get(((TwoRegisterInstruction) i).getRegisterB()) : Integer.valueOf(0);
            if (i.getOpcode() == Opcode.IF_EQ || i.getOpcode() == Opcode.IF_EQZ || i.getOpcode() == Opcode.IF_NE || i.getOpcode() == Opcode.IF_NEZ) {
                Boolean equal = null;
                if (firstValue instanceof ObjectValue first && secondValue instanceof ObjectValue second
                        && (first.alias >= 0 && first.alias == second.alias || i instanceof TwoRegisterInstruction pair
                        && pair.getRegisterA() == pair.getRegisterB())) equal = true;
                else if (firstValue instanceof ObjectValue first && first.nonNull && Integer.valueOf(0).equals(secondValue)
                        || secondValue instanceof ObjectValue second && second.nonNull && Integer.valueOf(0).equals(firstValue)) equal = false;
                if (equal != null) return (i.getOpcode() == Opcode.IF_EQ || i.getOpcode() == Opcode.IF_EQZ) == equal;
            }
            Integer first = integer(firstValue), second = integer(secondValue);
            if (first == null || second == null) return null;
            int compared = Integer.compare(first, second);
            return switch (i.getOpcode()) {
                case IF_EQ, IF_EQZ -> compared == 0;
                case IF_NE, IF_NEZ -> compared != 0;
                case IF_LT, IF_LTZ -> compared < 0;
                case IF_LE, IF_LEZ -> compared <= 0;
                case IF_GT, IF_GTZ -> compared > 0;
                case IF_GE, IF_GEZ -> compared >= 0;
                default -> null;
            };
        }
    }

    private static Reference reference(Instruction i) {
        return i instanceof ReferenceInstruction ? ((ReferenceInstruction) i).getReference() : null;
    }

    private static List<Integer> arguments(Instruction i) {
        List<Integer> out = new ArrayList<>();
        for (int register : invokeRegisters(i)) out.add(register);
        return out;
    }

    private static String descriptor(MethodReference m) {
        StringBuilder b = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) b.append(p);
        return b.append(')').append(m.getReturnType()).toString();
    }

    private static final class FeatureCheck {
        static final String BASE = "Lapp/hushtumblr/extension/tumblr/";
        static final String STATUS = BASE + "settings/SettingsStatus;";
        final FeatureIndex clean;
        final FeatureIndex patched;
        final List<String> findings = new ArrayList<>();
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        final Map<String, String> owners = new LinkedHashMap<>();
        final Map<String, FeatureEdits> edits = new LinkedHashMap<>();
        final Set<String> claimed = new HashSet<>();
        final Set<String> selected;
        final Map<String, FeatureFlow> flows = new HashMap<>();
        final Map<String, Set<String>> controlCallers = new HashMap<>();
        final Map<String, List<Method>> callbacks = new HashMap<>();
        final Set<String> controlMethods = new HashSet<>();
        final Set<String> checkedControls = new HashSet<>();
        boolean featureControls;

        static int firstRegister(Instruction i) {
            return i instanceof OneRegisterInstruction ? ((OneRegisterInstruction) i).getRegisterA() : DexDiff.firstRegister(i);
        }

        FeatureCheck(FeatureIndex clean, FeatureIndex patched, Set<String> selected) {
            this.clean = clean;
            this.patched = patched;
            this.selected = selected;
            indexControls();
        }

        void fail(String reason) { findings.add("contract: feature " + reason); }
        boolean flag(String name) { return flags.getOrDefault(name, false); }

        FeatureFlow flow(Method m) { return flows.computeIfAbsent(m.toString(), key -> new FeatureFlow(m)); }

        static boolean controlReference(Reference r) {
            if (r instanceof FieldReference) {
                String owner = ((FieldReference) r).getDefiningClass();
                return owner.equals(BASE + "settings/Settings;") || owner.equals(BASE + "settings/PatchFamily;")
                        || owner.equals(BASE + "settings/PatchFamily$Capability;");
            }
            if (!(r instanceof MethodReference)) return false;
            MethodReference m = (MethodReference) r;
            String owner = m.getDefiningClass();
            return owner.equals(STATUS) || r.toString().equals("Lapp/hushtumblr/extension/shared/Utils;->settingsReady()Z")
                    || owner.equals(BASE + "settings/PatchFamily;") && m.getName().equals("inBuild")
                    || owner.equals(BASE + "settings/PatchFamily$Capability;") && m.getName().equals("installed")
                    || owner.equals("Lapp/hushtumblr/extension/shared/settings/BooleanSetting;") && m.getName().equals("get");
        }

        void indexControls() {
            for (ClassDef cd : patched.classes.values()) {
                // Compiled lambdas run through their interface after their constructor has returned.
                if (!cd.getType().startsWith(OWN) || !AccessFlags.SYNTHETIC.isSet(cd.getAccessFlags()) || cd.getInterfaces().isEmpty()) continue;
                List<Method> implementations = new ArrayList<>();
                for (Method m : cd.getMethods()) if (m.getImplementation() != null && !AccessFlags.STATIC.isSet(m.getAccessFlags())
                        && !m.getName().startsWith("<")) implementations.add(m);
                for (Method m : cd.getMethods()) if (m.getName().equals("<init>")) {
                    callbacks.put(m.toString(), implementations);
                    for (Method callback : implementations) controlCallers.computeIfAbsent(callback.toString(), key -> new HashSet<>()).add(m.toString());
                }
            }
            for (Method m : patched.methods.values()) {
                if (!m.getDefiningClass().startsWith(OWN) || m.getImplementation() == null) continue;
                for (Instruction i : instructions(m)) {
                    Reference r = reference(i);
                    if (controlReference(r)) controlMethods.add(m.toString());
                    else if (r instanceof MethodReference && patched.methods.containsKey(r.toString()))
                        controlCallers.computeIfAbsent(r.toString(), key -> new HashSet<>()).add(m.toString());
                }
            }
            Deque<String> work = new ArrayDeque<>(controlMethods);
            while (!work.isEmpty()) for (String caller : controlCallers.getOrDefault(work.poll(), Set.of()))
                if (controlMethods.add(caller)) work.add(caller);
        }

        boolean leadsToControl(Reference r) {
            return controlReference(r) || r instanceof MethodReference && controlMethods.contains(r.toString());
        }

        void controls(Method m, boolean required) {
            if (m == null) return;
            if (!controlMethods.contains(m.toString())) {
                if (required) fail(m + " has no compiled family control");
                return;
            }
            if (!checkedControls.add(m.toString())) return;
            BitSet reached = flow(m).reachable(0, true);
            List<Instruction> body = instructions(m);
            for (int at = 0; at < body.size(); at++) {
                Reference r = reference(body.get(at));
                if (!leadsToControl(r)) continue;
                if (!reached.get(at)) fail(m + " has an unreachable required family control " + r);
                if (!controlReference(r) && r instanceof MethodReference) controls(patched.methods.get(r.toString()), false);
            }
            for (Method callback : callbacks.getOrDefault(m.toString(), List.of())) controls(callback, false);
        }

        void fallback(Method m, String original) {
            List<Integer> sites = callSites(instructions(m), original);
            FeatureFlow graph = flow(m);
            BitSet reached = graph.reachable(0, false);
            if (sites.stream().noneMatch(reached::get)) fail(m + " has no reachable original framework call fallback");
            // Settings wrappers restore their shortcut after the framework call; they have no disabled family arm.
            if (!featureControls) return;
            List<Instruction> body = instructions(m);
            for (int at = 0; at < body.size(); at++) {
                if (!reached.get(at) || !leadsToControl(reference(body.get(at)))) continue;
                BitSet disabled = graph.afterControl(at);
                if (sites.stream().noneMatch(disabled::get)) fail(m + " has no original framework call fallback after its disabled family control " + reference(body.get(at)));
            }
        }

        Method unique(List<Method> targets, String what) {
            if (targets.size() != 1) { fail(what + " has " + targets.size() + " clean targets, expected 1"); return null; }
            Method m = targets.get(0);
            if (clean.duplicates.contains(m.toString()) || clean.duplicates.contains(m.getDefiningClass())) {
                fail(what + " has duplicate clean definitions"); return null;
            }
            return m;
        }

        ClassDef uniqueClass(List<ClassDef> targets, String what) {
            if (targets.size() != 1) { fail(what + " has " + targets.size() + " clean classes, expected 1"); return null; }
            ClassDef cd = targets.get(0);
            if (clean.duplicates.contains(cd.getType())) { fail(what + " has duplicate clean classes"); return null; }
            return cd;
        }

        Method actual(Method original) {
            if (original == null) return null;
            Method m = actual(original.toString());
            if (m != null && (AccessFlags.STATIC.isSet(original.getAccessFlags()) != AccessFlags.STATIC.isSet(m.getAccessFlags())
                    || original.getImplementation() != null && original.getImplementation().getRegisterCount() != m.getImplementation().getRegisterCount()))
                fail(original + " changed its original static flag or parameter frame");
            return m;
        }

        Method actual(String key) {
            Method m = patched.methods.get(key);
            if (m == null || m.getImplementation() == null || patched.duplicates.contains(key)
                    || patched.duplicates.contains(m.getDefiningClass())) {
                fail("missing or duplicated compiled method " + key); return null;
            }
            return m;
        }

        void readFlag(String name) {
            Method m = actual(STATUS + "->" + name + "()Z");
            if (m == null) { flags.put(name, false); return; }
            List<Instruction> body = instructions(m);
            if (!AccessFlags.PUBLIC.isSet(m.getAccessFlags()) || !AccessFlags.STATIC.isSet(m.getAccessFlags())
                    || body.size() < 2 || !body.get(0).getOpcode().name.startsWith("const")
                    || !(body.get(0) instanceof WideLiteralInstruction) || body.get(1).getOpcode() != Opcode.RETURN
                    || firstRegister(body.get(0)) != firstRegister(body.get(1))) {
                fail(name + " is not a public static literal flag"); flags.put(name, false); return;
            }
            long value = ((WideLiteralInstruction) body.get(0)).getWideLiteral();
            if (value != 0 && value != 1) fail(name + " has a non-boolean literal");
            flags.put(name, value == 1);
        }

        /** The settings patch is in every build, and a shared hook is wanted by any family that uses it. */
        boolean active(Contract rule) {
            if (rule.callee.equals("settings")) return true;
            for (String cap : rule.callee.split(",")) if (flag(owners.get(cap))) return true;
            for (String user : users(rule)) if (flag(user)) return true;
            return false;
        }

        /**
         * Families that need a shared hook without owning one of its capabilities, the rule's last
         * column: Hide ads asks the feature switch hook too, and the card and post families the
         * timeline list hook, each owning only the capability of its own bridge.
         */
        static List<String> users(Contract rule) {
            return rule.target.equals("featureSwitch") || rule.target.equals("timelineList")
                    ? List.of(rule.strings.get(rule.strings.size() - 1).split(",")) : List.of();
        }

        void capability(String names, boolean available) {
            for (String cap : names.split(",")) {
                boolean expected = flag(owners.get(cap)) && available;
                if (flag(cap) != expected) fail(cap + " flag is " + flag(cap) + ", clean target requires " + expected);
            }
        }

        List<Integer> calls(Method m, String hook, int count) {
            if (m == null) return List.of();
            List<Integer> sites = callSites(instructions(m), hook);
            if (sites.size() != count) fail(hook + " has " + sites.size() + " calls in " + m + ", expected " + count);
            BitSet reached = flow(m).reachable(0, true);
            for (int at : sites) {
                if (claimed.add(m + "@" + at)) hookSites.add(new FeatureSite(m, at));
                if (!reached.get(at)) fail(hook + " is unreachable in " + m);
                Instruction i = instructions(m).get(at);
                if (i.getOpcode() != Opcode.INVOKE_STATIC && i.getOpcode() != Opcode.INVOKE_STATIC_RANGE)
                    fail(hook + " is not invoked statically in " + m);
                // One register passed twice means a scratch write replaced a value the hook still wanted,
                // unless the hook took over a host call that already passed it twice.
                if (new HashSet<>(arguments(i)).size() != arguments(i).size() && !carried(m, i))
                    fail(hook + " passes one register twice in " + m);
            }
            Method callee = actual(hook);
            if (callee != null && (!AccessFlags.PUBLIC.isSet(callee.getAccessFlags())
                    || !AccessFlags.STATIC.isSet(callee.getAccessFlags())
                    || !AccessFlags.PUBLIC.isSet(patched.classes.get(callee.getDefiningClass()).getAccessFlags()))) fail(hook + " is not a public static hook");
            if (featureControls) controls(callee, true);
            return sites;
        }

        boolean carried(Method m, Instruction hook) {
            Method before = clean.methods.get(m.toString());
            if (before == null || before.getImplementation() == null) return false;
            List<? extends CharSequence> shape = ((MethodReference) reference(hook)).getParameterTypes();
            for (Instruction i : instructions(before))
                if (i.getOpcode().name().startsWith("INVOKE_STATIC") && reference(i) instanceof MethodReference host
                        && host.getParameterTypes().equals(shape) && arguments(i).equals(arguments(hook))) return true;
            return false;
        }

        void remove(Method m, int first, int end) {
            if (m == null) return;
            FeatureEdits e = edits.computeIfAbsent(m.toString(), k -> new FeatureEdits());
            for (int at = Math.max(0, first); at < Math.min(end, instructions(m).size()); at++) e.removed.add(at);
        }

        int parameter(Method m, int index) {
            int start = m.getImplementation().getRegisterCount();
            for (CharSequence p : m.getParameterTypes()) start -= slots(p);
            if (!AccessFlags.STATIC.isSet(m.getAccessFlags())) start--;
            if (index < 0) return start;
            if (!AccessFlags.STATIC.isSet(m.getAccessFlags())) start++;
            for (int k = 0; k < index; k++) start += slots(m.getParameterTypes().get(k));
            return start;
        }

        void prefix(Method m, String hook, List<Integer> parameters, boolean result) {
            List<Integer> sites = calls(m, hook, parameters.size());
            if (m == null || sites.size() != parameters.size()) return;
            List<Instruction> body = instructions(m);
            for (int n = 0; n < sites.size(); n++) {
                int at = sites.get(n);
                int expectedAt = n * (result ? 2 : 1);
                if (at != expectedAt || !arguments(body.get(at)).equals(List.of(parameters.get(n))))
                    fail(hook + " is not a parameter-preserving prefix in " + m);
                if (result && (at + 1 >= body.size() || !isMoveResult(body.get(at + 1).getOpcode())
                        || firstRegister(body.get(at + 1)) != parameters.get(n)))
                    fail(hook + " does not replace its input register in " + m);
                remove(m, at, at + (result ? 2 : 1));
            }
        }

        int replacement(String original, String hook, String prefix, boolean fallback) {
            int total = 0;
            for (Method old : clean.methods.values()) {
                if (old.getDefiningClass().startsWith(OWN) || prefix != null && !old.getDefiningClass().startsWith(prefix)) continue;
                List<Integer> originals = callSites(instructions(old), original);
                if (originals.isEmpty()) continue;
                total += originals.size();
                Method m = actual(old);
                List<Integer> sites = calls(m, hook, originals.size());
                if (m == null) continue;
                List<Instruction> body = instructions(m);
                if (!callSites(body, original).isEmpty()) fail(original + " remains beside its replacement in " + m);
                for (int i = 0; i < Math.min(sites.size(), originals.size()); i++) {
                    int at = sites.get(i);
                    Instruction was = instructions(old).get(originals.get(i));
                    if (!arguments(body.get(at)).equals(arguments(was))) fail(hook + " changed receiver or argument registers in " + m);
                    else vouch(m, at, arguments(was));
                    edits.computeIfAbsent(m.toString(), k -> new FeatureEdits()).replacements.put(at, was);
                }
            }
            if (fallback && total > 0) {
                Method method = actual(hook);
                if (method != null) fallback(method, original);
            }
            return total;
        }

        /** True when the instruction at [at] can be reached only by falling through from [from]. */
        boolean onlyFrom(Method m, int at, int from) {
            FeatureFlow graph = flow(m);
            for (int k = 0; k < graph.normal.size(); k++) {
                if (k != from && graph.normal.get(k).contains(at)) return false;
                if (graph.handlers.get(k).contains(at)) return false;
            }
            return from >= 0 && graph.normal.get(from).contains(at);
        }

        /** Getter and hook pairs: each value a getter returns goes through its hook first, in the same register. */
        void answers(Contract c) {
            Set<Opcode> exits = Set.of(Opcode.RETURN, Opcode.RETURN_OBJECT, Opcode.RETURN_WIDE, Opcode.RETURN_VOID);
            boolean all = true;
            for (int n = 0; n + 1 < c.strings.size(); n += 2) {
                String getter = c.strings.get(n), hook = c.strings.get(n + 1);
                Method old = clean.methods.get(getter);
                if (old == null || old.getImplementation() == null) { fail(getter + " is absent from the clean target"); all = false; continue; }
                List<Instruction> was = instructions(old);
                List<Integer> returns = new ArrayList<>();
                for (int at = 0; at < was.size(); at++) if (exits.contains(was.get(at).getOpcode())) returns.add(at);
                Method m = actual(old);
                List<Integer> sites = calls(m, hook, returns.size());
                if (m == null || returns.isEmpty() || sites.size() != returns.size()) { all = false; continue; }
                List<Instruction> body = instructions(m);
                for (int k = 0; k < sites.size(); k++) {
                    int at = sites.get(k);
                    Instruction exit = was.get(returns.get(k));
                    int register = firstRegister(exit);
                    if (exit.getOpcode() == Opcode.RETURN_VOID || exit.getOpcode() == Opcode.RETURN_WIDE || at != returns.get(k) + 2 * k
                            || at + 2 >= body.size() || !arguments(body.get(at)).equals(List.of(register))
                            || !isMoveResult(body.get(at + 1).getOpcode()) || firstRegister(body.get(at + 1)) != register
                            || body.get(at + 2).getOpcode() != exit.getOpcode() || firstRegister(body.get(at + 2)) != register) {
                        fail(hook + " does not filter the answer before every return in " + m); all = false; continue;
                    }
                    remove(m, at, at + 2);
                    vouch(m, at, List.of(register));
                }
            }
            capability(c.callee, all);
        }

        /**
         * Firebase's request header builder, found by its texts. Tumblr's Firebase writes the
         * certificate header as its name, a call to the builder's own fingerprint method (the one
         * method of that class answering a String from nothing that holds the fingerprint error), that
         * call's answer, and the write. The hook goes between the answer and the write and takes the
         * connection and the value, and the method is otherwise untouched.
         */
        void firebaseCertificate(Contract c) {
            String hook = c.strings.get(0);
            List<Method> builders = new ArrayList<>();
            for (Method m : clean.holding("X-Android-Cert")) if (m.getReturnType().equals("Ljava/net/HttpURLConnection;")
                    && clean.texts.get(m.toString()).containsAll(List.of("X-Android-Package", "x-goog-api-key"))) builders.add(m);
            Method builder = unique(builders, "Firebase request header builder");
            Method fingerprint = null;
            if (builder != null) {
                List<Method> fingerprints = new ArrayList<>();
                for (Method m : clean.holding("Could not get fingerprint hash for package: "))
                    if (m.getDefiningClass().equals(builder.getDefiningClass()) && descriptor(m).equals("()Ljava/lang/String;")) fingerprints.add(m);
                fingerprint = unique(fingerprints, "Firebase certificate fingerprint method");
            }
            boolean found = false;
            if (fingerprint != null) {
                List<Instruction> was = instructions(builder);
                List<Integer> names = new ArrayList<>();
                for (int at = 0; at < was.size(); at++)
                    if (reference(was.get(at)) instanceof StringReference s && s.getString().equals("X-Android-Cert")) names.add(at);
                Method m = actual(builder);
                List<Integer> sites = calls(m, hook, 1);
                if (m != null && sites.size() == 1 && names.size() == 1 && names.get(0) + 3 < was.size()) {
                    int name = names.get(0), sink = name + 3, at = sites.get(0);
                    List<Integer> write = arguments(was.get(sink));
                    List<Instruction> body = instructions(m);
                    boolean shaped = fingerprint.toString().equals(String.valueOf(reference(was.get(name + 1))))
                            && was.get(name + 2).getOpcode() == Opcode.MOVE_RESULT_OBJECT
                            && write.size() == 3 && firstRegister(was.get(name + 2)) == write.get(2)
                            && at == sink && body.size() == was.size() + 2
                            && arguments(body.get(at)).equals(List.of(write.get(0), write.get(2)))
                            && body.get(at + 1).getOpcode() == Opcode.MOVE_RESULT_OBJECT && firstRegister(body.get(at + 1)) == write.get(2)
                            && arguments(body.get(at + 2)).equals(write)
                            && onlyFrom(m, at, at - 1) && onlyFrom(m, at + 1, at) && onlyFrom(m, at + 2, at + 1);
                    if (!shaped) fail(hook + " doesn't take the certificate header's fingerprint between its answer and its write in " + m);
                    else { remove(m, at, at + 2); vouch(m, at, List.of(write.get(0), write.get(2))); found = true; }
                }
            }
            capability(c.callee, found);
        }

        Method inherited(String type, String name, String shape) {
            Set<String> seen = new HashSet<>();
            for (ClassDef cd = clean.classes.get(type); cd != null && seen.add(cd.getType()); cd = clean.classes.get(cd.getSuperclass()))
                for (Method m : cd.getMethods()) if (m.getName().equals(name) && descriptor(m).equals(shape) && m.getImplementation() != null) return m;
            fail("settings lifecycle target is absent: " + type + "->" + name + shape); return null;
        }

        void settings(Contract c) {
            String entry = c.strings.get(2);
            Method app = actual(inherited(c.strings.get(0), "onCreate", "()V"));
            if (app != null) {
                prefix(app, "Lapp/hushtumblr/extension/shared/Utils;->setContext(Landroid/content/Context;)V", List.of(parameter(app, -1)), false);
                Method old = clean.methods.get(app.toString());
                List<Integer> returns = new ArrayList<>();
                List<Instruction> was = instructions(old);
                for (int at = 0; at < was.size(); at++) if (was.get(at).getOpcode() == Opcode.RETURN_VOID) returns.add(at);
                if (returns.isEmpty()) fail("settings application lifecycle has no original return");
                // It names no register: by its returns R8 has reused p0 (a String in 46.5.0.109), and
                // ART refused the whole application class when it handed that on as the Context.
                String hook = entry + "->onApplicationCreate()V";
                List<Integer> sites = calls(app, hook, returns.size());
                for (int k = 0; k < sites.size(); k++) {
                    int at = sites.get(k);
                    List<Instruction> body = instructions(app);
                    if (at + 1 >= body.size() || body.get(at + 1).getOpcode() != Opcode.RETURN_VOID
                            || !arguments(body.get(at)).isEmpty()) fail("settings application hook is not before its original return");
                    remove(app, at, at + 1);
                    if (k < returns.size()) anchor(app, at, returns.get(k), hook);
                }
            }
            Method create = actual(inherited(c.strings.get(1), "onCreate", "(Landroid/os/Bundle;)V"));
            if (create != null) prefix(create, entry + "->onActivityCreate(Landroid/app/Activity;)V", List.of(parameter(create, -1)), false);
            Method intent = actual(inherited(c.strings.get(1), "onNewIntent", "(Landroid/content/Intent;)V"));
            if (intent != null) {
                String hook = entry + "->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V";
                List<Integer> sites = calls(intent, hook, 1);
                if (sites.size() == 1) {
                    int at = sites.get(0);
                    if (at != 0 || !arguments(instructions(intent).get(at)).equals(List.of(parameter(intent, -1), parameter(intent, 0)))) fail("settings intent hook is not a parameter-preserving prefix");
                    remove(intent, at, at + 1);
                }
            }
            String manager = "Landroid/content/pm/ShortcutManager;";
            String[] names = {"pushDynamicShortcut", "addDynamicShortcuts", "setDynamicShortcuts", "updateShortcuts", "removeAllDynamicShortcuts"};
            for (String name : names) {
                String parameters = name.equals("pushDynamicShortcut") ? "Landroid/content/pm/ShortcutInfo;" : name.equals("removeAllDynamicShortcuts") ? "" : "Ljava/util/List;";
                String answer = name.equals("pushDynamicShortcut") || name.equals("removeAllDynamicShortcuts") ? "V" : "Z";
                replacement(manager + "->" + name + "(" + parameters + ")" + answer, entry + "->" + name + "(" + manager + parameters + ")" + answer, null, true);
            }
        }

        // Each family selector below finds its clean targets the way its patch does, checks the
        // injected code at the place the patch puts it, and vouches for the registers a hook reads
        // there when the clean code shows what they hold. The run checks the places and registers
        // once every rule has marked its own instructions.

        record Anchor(Method method, int at, int clean, String what) {}
        final List<Anchor> anchors = new ArrayList<>();
        final List<FeatureSite> hookSites = new ArrayList<>();
        final Map<String, Set<Integer>> vouched = new HashMap<>();

        /** What follows a question's `if-eqz`, from [from], before the original instruction it skips to. */
        interface Tail { boolean matches(List<Instruction> body, int from); }
        static final Tail RETURN_NOTHING = (body, from) -> body.get(from).getOpcode() == Opcode.RETURN_VOID;
        static final Set<String> OBJECT_METHODS = Set.of("hashCode", "equals", "toString", "getClass");
        static final String VIEW_IDS = "Lcom/tumblr/R$id;";
        static final String FOOTER_CONTROLS = "com.tumblr.ui.widget.splitnotescount.composable.";
        static final String COMMUNITY_FOOTER = "Lcom/tumblr/ui/widget/CommunityPostCardFooter;";
        static final String COMMENTS_NOTE = "com.tumblr.ui.widget.postcontrol.rememberFormattedComments (";
        static final String REACTION_CONTROL = "Lcom/tumblr/ui/widget/postcontrol/CommunitiesReactionControl;";
        static final Set<String> TUMBLRMART_IDS = Set.of("tumblrmart_button_top_bar", "tumblr_mart_icon");
        static final String ROW_ICONS = "Lcom/tumblr/compose/ds/icons/outline/";
        static final String STRING_TYPE = "Ljava/lang/String;";

        static boolean isStatic(Method m) { return AccessFlags.STATIC.isSet(m.getAccessFlags()); }

        static Instruction get(List<Instruction> body, int at) { return at >= 0 && at < body.size() ? body.get(at) : null; }

        static MethodReference called(Instruction i) {
            return i != null && i.getOpcode().name.startsWith("invoke") && reference(i) instanceof MethodReference r ? r : null;
        }

        static FieldReference field(Instruction i) { return i != null && reference(i) instanceof FieldReference f ? f : null; }

        /** Whether [f] is `Lclass;->name`, as a rule names a field. */
        static boolean names(FieldReference f, String field) { return f != null && field.equals(f.getDefiningClass() + "->" + f.getName()); }

        static boolean isCall(Instruction i, String owner, String name, String shape) {
            MethodReference r = called(i);
            return r != null && r.getDefiningClass().equals(owner) && r.getName().equals(name) && descriptor(r).equals(shape);
        }

        static boolean movesTo(Instruction i, int register, Opcode opcode) {
            return i != null && i.getOpcode() == opcode && firstRegister(i) == register;
        }

        static boolean writes(Instruction i, int register) {
            if (!i.getOpcode().setsRegister() || !(i instanceof OneRegisterInstruction)) return false;
            int destination = ((OneRegisterInstruction) i).getRegisterA();
            return destination == register || i.getOpcode().setsWideRegister() && destination + 1 == register;
        }

        static boolean writtenBetween(List<Instruction> body, int from, int end, int register) {
            for (int k = Math.max(0, from); k < Math.min(end, body.size()); k++) if (writes(body.get(k), register)) return true;
            return false;
        }

        /** Whether [i] names [register] at all, as a source or a destination. */
        static boolean namesRegister(Instruction i, int register) {
            if (i instanceof FiveRegisterInstruction || i instanceof RegisterRangeInstruction) return arguments(i).contains(register);
            if (i instanceof ThreeRegisterInstruction t && (t.getRegisterB() == register || t.getRegisterC() == register)) return true;
            if (i instanceof TwoRegisterInstruction t && t.getRegisterB() == register) return true;
            return i instanceof OneRegisterInstruction o && o.getRegisterA() == register;
        }

        static int firstIndex(List<Instruction> body, int from, java.util.function.Predicate<Instruction> test) {
            if (from < 0) return -1;
            for (int k = from; k < body.size(); k++) if (test.test(body.get(k))) return k;
            return -1;
        }

        static boolean plural(Instruction i, String name) {
            FieldReference f = field(i);
            return i.getOpcode() == Opcode.SGET && f != null && f.getName().equals(name) && f.getDefiningClass().endsWith("R$plurals;");
        }

        /** The index a branch at [at] lands on, or -1. */
        static int target(Method m, int at) {
            Layout layout = new Layout(m.getImplementation());
            Instruction i = get(layout.instructions, at);
            if (!(i instanceof OffsetInstruction)) return -1;
            return layout.addresses.indexOf(layout.addresses.get(at) + ((OffsetInstruction) i).getCodeOffset());
        }

        void anchor(Method m, int at, int cleanAt, String what) { if (m != null) anchors.add(new Anchor(m, at, cleanAt, what)); }

        void vouch(Method m, int at, List<Integer> registers) {
            if (m != null) vouched.computeIfAbsent(m + "@" + at, k -> new HashSet<>()).addAll(registers);
        }

        /**
         * Fails unless the clean instruction [at] is reached only by falling through from the one
         * before. dexlib2 keeps a label on the instruction it was on, so a hook put in front of a
         * jump target is skipped on every path that jumps there.
         */
        void fallthroughOnly(Method old, int at, String what) {
            FeatureFlow graph = new FeatureFlow(old);
            boolean only = at > 0 && at < graph.normal.size() && graph.normal.get(at - 1).contains(at);
            for (int k = 0; only && k < graph.normal.size(); k++)
                if (k != at - 1 && graph.normal.get(k).contains(at) || graph.handlers.get(k).contains(at)) only = false;
            if (!only) fail(what + " sits where Tumblr's own code can jump past it in " + old);
        }

        /** All of Tumblr's methods with a body. */
        List<Method> tumblrMethods() {
            List<Method> out = new ArrayList<>();
            for (Method m : clean.methods.values()) if (!m.getDefiningClass().startsWith(OWN) && m.getImplementation() != null) out.add(m);
            return out;
        }

        /** The classes with a method whose instructions match [test]. */
        List<ClassDef> classesWith(java.util.function.Predicate<Instruction> test) {
            List<ClassDef> out = new ArrayList<>();
            for (ClassDef cd : clean.classes.values()) {
                if (cd.getType().startsWith(OWN)) continue;
                boolean found = false;
                for (Method m : cd.getMethods()) if (!found && m.getImplementation() != null)
                    for (Instruction i : m.getImplementation().getInstructions()) if (test.test(i)) { found = true; break; }
                if (found) out.add(cd);
            }
            return out;
        }

        /**
         * [hook] called once in [m], on [args], in front of what was instruction [cleanAt] of [old],
         * with its answer moved back into [result] unless that's -1. Marks it injected, checks its
         * place after every rule has run, and vouches for [checked], the arguments the clean code
         * shows hold what the hook takes there. True when it's there and shaped so.
         */
        boolean hookAt(Method old, Method m, int cleanAt, String hook, List<Integer> args, int result, List<Integer> checked) {
            List<Integer> sites = calls(m, hook, 1);
            if (m == null || sites.size() != 1) return false;
            int at = sites.get(0);
            List<Instruction> body = instructions(m);
            String answer = ((MethodReference) reference(body.get(at))).getReturnType();
            Opcode move = answer.startsWith("L") || answer.startsWith("[") ? Opcode.MOVE_RESULT_OBJECT : Opcode.MOVE_RESULT;
            if (!arguments(body.get(at)).equals(args) || result >= 0 && !movesTo(get(body, at + 1), result, move)) {
                fail(hook + " doesn't take what its rule hands it in " + m);
                return false;
            }
            remove(m, at, at + (result < 0 ? 1 : 2));
            anchor(m, at, cleanAt, hook);
            if (cleanAt > 0) fallthroughOnly(old, cleanAt, hook);
            vouch(m, at, checked);
            return true;
        }

        /**
         * A question at [at]: `invoke-static {args} hook`, its answer moved into a local, `if-eqz` on
         * it to the original instruction past [size] more that [tail] accepts. That original
         * instruction's index, or -1.
         */
        int guard(Method m, int at, String hook, List<Integer> args, int size, Tail tail) {
            List<Instruction> body = instructions(m);
            Instruction answer = get(body, at + 1), test = get(body, at + 2);
            int keep = at + 3 + size;
            if (!String.valueOf(reference(get(body, at))).equals(hook) || !arguments(body.get(at)).equals(args)
                    || answer == null || answer.getOpcode() != Opcode.MOVE_RESULT || firstRegister(answer) >= parameter(m, -1)
                    || test == null || test.getOpcode() != Opcode.IF_EQZ || firstRegister(test) != firstRegister(answer)
                    || target(m, at + 2) != keep || keep >= body.size() || !tail.matches(body, at + 3)) return -1;
            return keep;
        }

        /** [hook] asked first thing in [old]'s patched copy, the method's own code run when it answers false. */
        boolean guardFirst(Method old, String hook, List<Integer> args, int size, Tail tail) {
            Method m = actual(old);
            List<Integer> sites = calls(m, hook, 1);
            if (m == null || sites.size() != 1) return false;
            int keep = sites.get(0) == 0 ? guard(m, 0, hook, args, size, tail) : -1;
            if (keep < 0) {
                fail(hook + " isn't asked first thing in " + m + ", with Tumblr's own code run when it answers false");
                return false;
            }
            remove(m, 0, keep);
            return true;
        }

        /**
         * Every register a hook reads holds what the hook takes: one its rule vouched for, or a
         * parameter nothing has written over by then, of a type the hook takes. R8 reuses a
         * parameter's register once the method is done with it, so a hook naming pN further down
         * can be handed whatever went there instead: Tumblr's application start hook got a String
         * for its Context, and ART refused the whole class at launch.
         */
        void hookRegisters(FeatureSite site) {
            Method m = site.method;
            Instruction call = instructions(m).get(site.at);
            MethodReference hook = (MethodReference) reference(call);
            Set<Integer> checked = vouched.getOrDefault(site.key(), Set.of());
            List<Integer> args = arguments(call);
            int first = parameter(m, -1);
            Map<Integer, String> declared = new HashMap<>();
            int register = first;
            if (!isStatic(m)) declared.put(register++, m.getDefiningClass());
            for (CharSequence p : m.getParameterTypes()) { declared.put(register, p.toString()); register += slots(p); }
            int slot = 0;
            for (CharSequence type : hook.getParameterTypes()) {
                if (slot >= args.size()) break;
                int read = args.get(slot);
                slot += slots(type);
                if (checked.contains(read)) continue;
                String held = declared.get(read);
                if (held == null) fail(hook + " reads v" + read + " in " + m + ", a local no rule shows holds what it takes");
                else if (overwritten(m, read, site.at)) fail(hook + " reads p" + (read - first) + " in " + m
                        + " where an instruction that writes over that parameter can run first");
                else if (!assignable(held, type.toString())) fail(hook + " takes " + type + " but reads p" + (read - first)
                        + ", a " + held + ", in " + m);
            }
        }

        /** Whether a write to [register] can run before instruction [at], along any branch or handler. */
        boolean overwritten(Method m, int register, int at) {
            FeatureFlow graph = flow(m);
            List<Instruction> body = instructions(m);
            BitSet reached = new BitSet();
            Deque<Integer> work = new ArrayDeque<>();
            for (int k = 0; k < body.size(); k++) if (writes(body.get(k), register)) work.addAll(graph.normal.get(k));
            while (!work.isEmpty()) {
                int k = work.poll();
                if (reached.get(k)) continue;
                reached.set(k);
                work.addAll(graph.normal.get(k));
                work.addAll(graph.handlers.get(k));
            }
            return reached.get(at);
        }

        /**
         * Whether a value of type [held] fits a parameter of type [wanted]. Classes outside the APK
         * can't be followed, so a chain that leaves it doesn't refute; java.lang's final value types
         * are known.
         */
        boolean assignable(String held, String wanted) {
            if (held.equals(wanted)) return true;
            boolean heldObject = held.startsWith("L") || held.startsWith("["), wantedObject = wanted.startsWith("L") || wanted.startsWith("[");
            if (heldObject != wantedObject) return false;
            if (!heldObject) return "ZBSCI".contains(held) && "ZBSCI".contains(wanted);
            if (wanted.equals("Ljava/lang/Object;")) return true;
            Map<String, Set<String>> finals = Map.of(STRING_TYPE, Set.of("Ljava/lang/CharSequence;", "Ljava/io/Serializable;", "Ljava/lang/Comparable;"),
                    "Ljava/lang/Integer;", Set.of("Ljava/lang/Number;", "Ljava/io/Serializable;", "Ljava/lang/Comparable;"),
                    "Ljava/lang/Boolean;", Set.of("Ljava/io/Serializable;", "Ljava/lang/Comparable;"));
            Deque<String> work = new ArrayDeque<>(List.of(held));
            Set<String> seen = new HashSet<>();
            boolean unknown = false;
            while (!work.isEmpty()) {
                String type = work.poll();
                if (!seen.add(type) || type.equals("Ljava/lang/Object;")) continue;
                if (type.equals(wanted)) return true;
                if (finals.containsKey(type)) { if (finals.get(type).contains(wanted)) return true; continue; }
                ClassDef cd = patched.classes.get(type);
                if (cd == null) { unknown = true; continue; }
                if (cd.getSuperclass() != null) work.add(cd.getSuperclass());
                work.addAll(cd.getInterfaces());
            }
            return unknown;
        }

        /**
         * The HushTumblr row at the end of Tumblr's Settings screen: the rows' builder, the key the
         * screen's list holds a row by, the tap on a row, and the title stub. The settings patch
         * leaves the row out with a warning when Tumblr's screen changed shape, so a clean target
         * that doesn't resolve asks for nothing here, and any of the row's calls left in fails as
         * unclaimed.
         */
        void settingsRow(Contract c) {
            String hub = c.strings.get(0), title = c.strings.get(1), owner = c.strings.get(2);
            List<Method> methods = new ArrayList<>();
            for (Method m : clean.methods.values()) if (m.getDefiningClass().startsWith(hub) && m.getImplementation() != null) methods.add(m);
            List<Method> builders = new ArrayList<>();
            for (Method m : methods) if (!isStatic(m) && descriptor(m).equals("()Ljava/util/List;")
                    && instructions(m).stream().anyMatch(i -> i.getOpcode() == Opcode.SGET && names(field(i), title))) builders.add(m);
            if (builders.size() != 1) return;
            Method builder = builders.get(0);
            List<Instruction> was = instructions(builder);
            int size = was.size();
            String rowType = null;
            for (Instruction i : was) if (i.getOpcode() == Opcode.NEW_INSTANCE) { rowType = String.valueOf(reference(i)); break; }
            ClassDef rowClass = rowType == null ? null : clean.classes.get(rowType);
            if (rowClass == null || size < 3) return;
            List<Method> constructors = new ArrayList<>(), titles = new ArrayList<>(), kinds = new ArrayList<>();
            for (Method m : rowClass.getMethods()) if (m.getName().equals("<init>") && m.getParameterTypes().size() == 4
                    && m.getParameterTypes().get(1).toString().equals("I") && m.getParameterTypes().get(3).toString().equals("Ljava/lang/Integer;")) constructors.add(m);
            if (constructors.size() != 1) return;
            Method constructor = constructors.get(0);
            String kindType = constructor.getParameterTypes().get(0).toString(), iconType = constructor.getParameterTypes().get(2).toString();
            for (Method m : rowClass.getMethods()) if (!isStatic(m)) {
                if (!m.getName().equals("hashCode") && descriptor(m).equals("()I")) titles.add(m);
                if (descriptor(m).equals("()" + kindType)) kinds.add(m);
            }
            Instruction handBack = was.get(size - 3);
            if (titles.size() != 1 || kinds.size() != 1 || handBack.getOpcode() != Opcode.INVOKE_STATIC || called(handBack) == null
                    || !descriptor(called(handBack)).equals("(Ljava/util/List;)Ljava/util/List;") || arguments(handBack).size() != 1
                    || was.get(size - 2).getOpcode() != Opcode.MOVE_RESULT_OBJECT || was.get(size - 1).getOpcode() != Opcode.RETURN_OBJECT) return;
            String kindGetter = kinds.get(0).toString();
            List<Method> keys = new ArrayList<>(), clicks = new ArrayList<>();
            for (Method m : methods) if (instructions(m).stream().anyMatch(i -> String.valueOf(reference(i)).equals(kindGetter))) {
                if (isStatic(m) && descriptor(m).equals("(" + rowType + ")Ljava/lang/Object;")) keys.add(m);
                if (!isStatic(m) && descriptor(m).equals("()V")) clicks.add(m);
            }
            if (keys.size() != 1 || clicks.size() != 1) return;
            String keyFunction = keys.get(0).toString();
            List<Method> keyCallers = new ArrayList<>();
            for (Method m : methods) if (instructions(m).stream().anyMatch(i -> String.valueOf(reference(i)).equals(keyFunction))) keyCallers.add(m);
            if (keyCallers.size() != 1) return;
            Method keyCaller = keyCallers.get(0), click = clicks.get(0);
            List<Instruction> keyBody = instructions(keyCaller);
            int keyCall = firstIndex(keyBody, 0, i -> String.valueOf(reference(i)).equals(keyFunction));
            Instruction keyResult = get(keyBody, keyCall + 1), keyReturn = get(keyBody, keyCall + 2);
            if (keyResult == null || keyResult.getOpcode() != Opcode.MOVE_RESULT_OBJECT || keyReturn == null
                    || keyReturn.getOpcode() != Opcode.RETURN_OBJECT || firstRegister(keyReturn) != firstRegister(keyResult)) return;
            List<String> rowFields = new ArrayList<>();
            ClassDef clicker = clean.classes.get(click.getDefiningClass());
            if (clicker != null) for (Field f : clicker.getFields()) if (!AccessFlags.STATIC.isSet(f.getAccessFlags()) && f.getType().equals(rowType))
                rowFields.add(f.getDefiningClass() + "->" + f.getName() + ":" + f.getType());
            if (rowFields.size() != 1) return;

            // The row, added in front of the rows' hand-back when titleRes answers a title.
            String titleRes = owner + "->titleRes()I";
            Method m = actual(builder);
            List<Integer> sites = calls(m, titleRes, 1);
            if (m != null && sites.size() == 1) {
                List<Instruction> body = instructions(m);
                int at = sites.get(0), list = arguments(handBack).get(0), locals = parameter(m, -1);
                Set<String> read = new HashSet<>();
                for (Instruction i : was) if (i.getOpcode() == Opcode.SGET_OBJECT) read.add(String.valueOf(reference(i)));
                boolean shaped = at + 11 < body.size() && arguments(body.get(at)).isEmpty();
                int vt = shaped ? firstRegister(body.get(at + 1)) : -1, vk = -1, vi = -1, vn = -1, vr = -1;
                if (shaped) {
                    Instruction kind = body.get(at + 3), receiver = body.get(at + 4), icon = body.get(at + 5), none = body.get(at + 7), made = body.get(at + 8);
                    vk = firstRegister(kind); vi = firstRegister(receiver); vn = firstRegister(none); vr = firstRegister(made);
                    MethodReference getter = called(icon);
                    shaped = body.get(at + 1).getOpcode() == Opcode.MOVE_RESULT && body.get(at + 2).getOpcode() == Opcode.IF_EQZ
                            && firstRegister(body.get(at + 2)) == vt && target(m, at + 2) == at + 11
                            && kind.getOpcode() == Opcode.SGET_OBJECT && field(kind).getType().equals(kindType) && read.contains(String.valueOf(reference(kind)))
                            && receiver.getOpcode() == Opcode.SGET_OBJECT && read.contains(String.valueOf(reference(receiver)))
                            && icon.getOpcode() == Opcode.INVOKE_STATIC && getter != null && getter.getDefiningClass().startsWith(ROW_ICONS)
                            && descriptor(getter).equals("(" + field(receiver).getType() + ")" + iconType) && arguments(icon).equals(List.of(vi))
                            && movesTo(body.get(at + 6), vi, Opcode.MOVE_RESULT_OBJECT)
                            && none.getOpcode() == Opcode.CONST_4 && ((NarrowLiteralInstruction) none).getNarrowLiteral() == 0
                            && made.getOpcode() == Opcode.NEW_INSTANCE && String.valueOf(reference(made)).equals(rowType)
                            && body.get(at + 9).getOpcode() == Opcode.INVOKE_DIRECT && String.valueOf(reference(body.get(at + 9))).equals(constructor.toString())
                            && arguments(body.get(at + 9)).equals(List.of(vr, vk, vt, vi, vn))
                            && body.get(at + 10).getOpcode() == Opcode.INVOKE_INTERFACE
                            && String.valueOf(reference(body.get(at + 10))).equals("Ljava/util/List;->add(Ljava/lang/Object;)Z")
                            && arguments(body.get(at + 10)).equals(List.of(list, vr))
                            && new HashSet<>(List.of(vt, vk, vi, vn, vr, list)).size() == 6
                            && List.of(vt, vk, vi, vn, vr).stream().allMatch(r -> r < locals);
                }
                if (!shaped) fail("the HushTumblr row isn't added to Tumblr's Settings rows in front of their hand-back in " + m);
                else {
                    remove(m, at, at + 11);
                    anchor(m, at, size - 3, titleRes);
                    fallthroughOnly(builder, size - 3, titleRes);
                }
            }
            // Its key, through rowKey right after Tumblr works one out.
            int keyRow = arguments(keyBody.get(keyCall)).get(0), keyAnswer = firstRegister(keyResult);
            hookAt(keyCaller, actual(keyCaller), keyCall + 2, owner + "->rowKey(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    List.of(keyRow, keyAnswer), keyAnswer, List.of(keyRow, keyAnswer));
            // The tap, asking onClick with the tapped row first thing.
            String onClick = owner + "->onClick(Ljava/lang/Object;)Z";
            Method tap = actual(click);
            List<Integer> taps = calls(tap, onClick, 1);
            if (tap != null && taps.size() == 1) {
                List<Instruction> body = instructions(tap);
                Instruction row = body.get(0);
                int held = row.getOpcode() == Opcode.IGET_OBJECT ? firstRegister(row) : -1;
                int keep = taps.get(0) == 1 && held >= 0 && held < parameter(tap, -1) && ((TwoRegisterInstruction) row).getRegisterB() == parameter(tap, -1)
                        && String.valueOf(reference(row)).equals(rowFields.get(0)) ? guard(tap, 1, onClick, List.of(held), 1, RETURN_NOTHING) : -1;
                if (keep < 0) fail(onClick + " isn't asked first thing in " + tap + " with the tapped row");
                else { remove(tap, 0, keep); vouch(tap, 1, List.of(held)); }
            }
            // The title stub, reading a row's title through its getter.
            Method stub = null;
            ClassDef rowHooks = patched.classes.get(owner);
            if (rowHooks != null) for (Method s : rowHooks.getMethods()) if (s.getName().equals("titleOf") && isStatic(s)) stub = s;
            List<Instruction> body = stub == null || stub.getImplementation() == null ? List.of() : instructions(stub);
            int item = stub == null || stub.getParameterTypes().size() != 1 ? -1 : parameter(stub, 0);
            if (body.size() != 4 || body.get(0).getOpcode() != Opcode.CHECK_CAST || firstRegister(body.get(0)) != item
                    || !String.valueOf(reference(body.get(0))).equals(rowType) || body.get(1).getOpcode() != Opcode.INVOKE_VIRTUAL
                    || !String.valueOf(reference(body.get(1))).equals(titles.get(0).toString()) || !arguments(body.get(1)).equals(List.of(item))
                    || body.get(2).getOpcode() != Opcode.MOVE_RESULT || body.get(3).getOpcode() != Opcode.RETURN
                    || firstRegister(body.get(3)) != firstRegister(body.get(2))) fail(owner + "->titleOf doesn't read a row's title through " + titles.get(0));
        }

        /**
         * Tumblr's feature check: the one instance method of the feature enum's companion that takes
         * a feature and its server value. Its one static (String, String, boolean) comparison's
         * answer goes through the hook, with the feature, right after it's moved into its register.
         */
        void featureSwitch(Contract c) {
            String hook = c.strings.get(0), enumText = c.strings.get(1);
            List<ClassDef> enums = new ArrayList<>();
            for (ClassDef cd : clean.classesHolding(enumText)) if ("Ljava/lang/Enum;".equals(cd.getSuperclass()))
                for (Method m : cd.getMethods()) if (m.getName().equals("<clinit>") && clean.texts.get(m.toString()).contains(enumText)) { enums.add(cd); break; }
            ClassDef feature = uniqueClass(enums, "Tumblr's feature enum");
            ClassDef companion = uniqueClass(clean.classesHolding(c.strings.get(2)), "Tumblr's feature check");
            boolean found = false;
            if (feature != null && companion != null) {
                List<Method> checks = new ArrayList<>();
                for (Method m : companion.getMethods()) if (!isStatic(m) && m.getImplementation() != null
                        && descriptor(m).equals("(" + feature.getType() + STRING_TYPE + ")Z")) checks.add(m);
                Method old = unique(checks, "the feature check comparing a flag's server value");
                if (old != null) {
                    List<Instruction> was = instructions(old);
                    List<Integer> compares = new ArrayList<>();
                    for (int at = 0; at < was.size(); at++) if (was.get(at).getOpcode() == Opcode.INVOKE_STATIC && called(was.get(at)) != null
                            && descriptor(called(was.get(at))).equals("(Ljava/lang/String;Ljava/lang/String;Z)Z")) compares.add(at);
                    Instruction moved = compares.size() == 1 ? get(was, compares.get(0) + 1) : null;
                    if (moved == null || moved.getOpcode() != Opcode.MOVE_RESULT) fail("the feature check has no one comparison taken into a register");
                    else {
                        int answer = firstRegister(moved);
                        found = hookAt(old, actual(old), compares.get(0) + 2, hook, List.of(parameter(old, 0), answer), answer, List.of(answer));
                    }
                }
            }
            capability(c.callee, found);
        }

        /** Each timeline page constructor hands its items, its first parameter, through the hook first thing. */
        void timelineList(Contract c) {
            boolean found = true;
            for (String key : c.strings.subList(1, 3)) {
                Method old = clean.methods.get(key);
                if (old == null || old.getImplementation() == null) { fail(key + " is absent from the clean target"); found = false; continue; }
                int items = parameter(old, 0);
                found &= hookAt(old, actual(old), 0, c.strings.get(0), List.of(items), items, List.of());
            }
            capability(c.callee, found);
        }

        /** The getter of model [type] that its Moshi adapter's toJson calls right after writing [key], or null. */
        MethodReference adapterGetter(String type, String key) {
            ClassDef adapter = clean.classes.get(type.substring(0, type.length() - 1) + "JsonAdapter;");
            List<Method> writers = new ArrayList<>();
            if (adapter != null) for (Method m : adapter.getMethods()) if (m.getName().equals("toJson") && m.getParameterTypes().size() == 2
                    && m.getReturnType().equals("V") && m.getImplementation() != null) writers.add(m);
            if (writers.size() != 1) { fail(type + " has no single Moshi adapter toJson"); return null; }
            List<Instruction> body = instructions(writers.get(0));
            int at = firstIndex(body, 0, i -> reference(i) instanceof StringReference s && s.getString().equals(key));
            int call = firstIndex(body, at < 0 ? -1 : at + 1, i -> i.getOpcode() == Opcode.INVOKE_VIRTUAL && called(i).getDefiningClass().equals(type));
            if (call < 0) { fail(type + "'s adapter calls no getter after \"" + key + "\""); return null; }
            return called(body.get(call));
        }

        /** TimelineFilter.carouselItems, written to answer a carousel's items through the getter its adapter writes under the key. */
        void timelineCarousel(Contract c) {
            String carousel = c.strings.get(1);
            MethodReference getter = adapterGetter(carousel, c.strings.get(2));
            boolean found = false;
            if (getter != null && !descriptor(getter).equals("()Ljava/util/List;")) fail(getter + " isn't a getter of a List");
            else if (getter != null) {
                Method stub = actual(c.strings.get(0));
                if (stub != null) {
                    List<Instruction> body = instructions(stub);
                    int item = stub.getParameterTypes().size() == 1 ? parameter(stub, 0) : -1;
                    found = isStatic(stub) && body.size() == 4 && body.get(0).getOpcode() == Opcode.CHECK_CAST && firstRegister(body.get(0)) == item
                            && String.valueOf(reference(body.get(0))).equals(carousel) && body.get(1).getOpcode() == Opcode.INVOKE_VIRTUAL
                            && String.valueOf(reference(body.get(1))).equals(getter.toString()) && arguments(body.get(1)).equals(List.of(item))
                            && body.get(2).getOpcode() == Opcode.MOVE_RESULT_OBJECT && body.get(3).getOpcode() == Opcode.RETURN_OBJECT
                            && firstRegister(body.get(3)) == firstRegister(body.get(2));
                    if (!found) fail(stub + " doesn't answer a carousel's items through " + getter);
                }
            }
            capability(c.callee, found);
        }

        /** Each model implements its extension interface, every method of it handing back the getter its adapter writes under its key. */
        void postBridge(Contract c) {
            boolean found = true;
            for (String spec : c.strings) found &= bridge(spec);
            capability(c.callee, found);
        }

        /** One `model>interface>method:key,method:key` bridge. */
        boolean bridge(String spec) {
            String[] parts = spec.split(">", -1);
            if (parts.length != 3) { fail("malformed bridge " + spec); return false; }
            String type = parts[0], face = parts[1];
            ClassDef model = patched.classes.get(type), bridge = patched.classes.get(face);
            if (model == null || clean.classes.get(type) == null || bridge == null || !AccessFlags.INTERFACE.isSet(bridge.getAccessFlags())
                    || !model.getInterfaces().contains(face)) { fail(type + " doesn't implement " + face); return false; }
            Map<String, String> keys = new LinkedHashMap<>();
            for (String pair : parts[2].split(",")) { String[] named = pair.split(":", 2); keys.put(named[0], named.length == 2 ? named[1] : ""); }
            boolean ok = true;
            int declared = 0;
            for (Method m : bridge.getMethods()) {
                if (!AccessFlags.ABSTRACT.isSet(m.getAccessFlags())) continue;
                declared++;
                String key = keys.get(m.getName());
                if (key == null || !m.getParameterTypes().isEmpty()) { fail(face + " declares " + m.getName() + ", which its rule gives no key"); ok = false; continue; }
                MethodReference getter = adapterGetter(type, key);
                if (getter == null) { ok = false; continue; }
                Method own = clean.methods.get(getter.toString());
                if (own == null || isStatic(own) || !AccessFlags.PUBLIC.isSet(own.getAccessFlags()) || OBJECT_METHODS.contains(getter.getName())
                        || !descriptor(getter).equals("()" + m.getReturnType())) { fail(getter + " isn't a public getter of " + m.getReturnType()); ok = false; continue; }
                String added = type + "->" + m.getName() + "()" + m.getReturnType();
                Method call = clean.methods.containsKey(added) ? null : actual(added);
                List<Instruction> body = call == null ? List.of() : instructions(call);
                if (call == null || isStatic(call) || body.size() != 3 || body.get(0).getOpcode() != Opcode.INVOKE_VIRTUAL
                        || !String.valueOf(reference(body.get(0))).equals(getter.toString()) || !arguments(body.get(0)).equals(List.of(parameter(call, -1)))
                        || body.get(1).getOpcode() != Opcode.MOVE_RESULT_OBJECT || body.get(2).getOpcode() != Opcode.RETURN_OBJECT
                        || firstRegister(body.get(2)) != firstRegister(body.get(1))) { fail(added + " doesn't hand back " + getter); ok = false; }
            }
            if (declared != keys.size()) { fail(face + " declares " + declared + " methods, its rule names " + keys.size()); ok = false; }
            return ok;
        }

        /** The one instance method taking a Context that names the rating prompt and saves when it was shown, asking the hook first. */
        void reviewPrompt(Contract c) {
            String activity = c.strings.get(2);
            List<Method> launchers = new ArrayList<>();
            for (Method m : clean.holding(c.strings.get(1))) if (!isStatic(m) && descriptor(m).equals("(Landroid/content/Context;)V")
                    && instructions(m).stream().anyMatch(i -> reference(i) instanceof TypeReference t && t.getType().equals(activity))) launchers.add(m);
            Method old = unique(launchers, "the method that opens the rating prompt");
            capability(c.callee, old != null && guardFirst(old, c.strings.get(0), List.of(), 1, RETURN_NOTHING));
        }

        /** The post header's Follow button check, which the header's Follow action test calls, answering yes first when asked. */
        void followButton(Contract c) {
            String header = c.strings.get(1);
            ClassDef view = clean.classes.get(header);
            Method old = null;
            if (view == null) fail("Tumblr's " + header + " is absent");
            else {
                List<Method> tests = new ArrayList<>();
                for (Method m : view.getMethods()) if (!isStatic(m) && m.getReturnType().equals("Z") && m.getImplementation() != null
                        && instructions(m).stream().anyMatch(i -> i.getOpcode() == Opcode.SGET_OBJECT && names(field(i), c.strings.get(2)))) tests.add(m);
                Method test = unique(tests, "the header's Follow action test");
                if (test != null) {
                    Set<String> checks = new TreeSet<>();
                    for (Instruction i : instructions(test)) if (i.getOpcode() == Opcode.INVOKE_DIRECT && called(i).getDefiningClass().equals(header)
                            && called(i).getReturnType().equals("Z") && called(i).getParameterTypes().size() == 2) checks.add(called(i).toString());
                    List<Method> found = new ArrayList<>();
                    for (String key : checks) found.add(clean.methods.get(key));
                    found.removeIf(m -> m == null);
                    old = checks.size() == 1 ? unique(found, "the header's Follow button check") : unique(List.of(), "the header's Follow button check");
                }
            }
            Tail yes = (body, from) -> body.get(from).getOpcode() == Opcode.CONST_4 && ((NarrowLiteralInstruction) body.get(from)).getNarrowLiteral() == 1
                    && body.get(from + 1).getOpcode() == Opcode.RETURN && firstRegister(body.get(from + 1)) == firstRegister(body.get(from));
            capability(c.callee, old != null && guardFirst(old, c.strings.get(0), List.of(), 2, yes));
        }

        /** The footer function Compose names [control], found by its source note. */
        Method footerControl(String control) {
            String note = control + " (";
            List<Method> found = new ArrayList<>();
            for (Method m : tumblrMethods()) if (clean.texts.get(m.toString()).stream().anyMatch(t -> t.startsWith(note))) found.add(m);
            return unique(found, "the footer's " + control);
        }

        /** A footer button asking its hook first, and on a community post the footer's bind handing it the footer before its return. */
        void footerButton(Contract c) {
            Method old = footerControl(c.strings.get(1));
            boolean found = false;
            if (old != null && (!isStatic(old) || !old.getReturnType().equals("V"))) fail("the footer's " + c.strings.get(1) + " isn't a static function returning nothing");
            else if (old != null) found = guardFirst(old, c.strings.get(0), List.of(), 1, RETURN_NOTHING);
            if (!c.strings.get(2).equals("none")) found &= communityFooter(c.strings.get(2));
            capability(c.callee, found);
        }

        /** [hook] handed the community footer, `this`, in front of the one return of its bind. */
        boolean communityFooter(String hook) {
            ClassDef footer = clean.classes.get(COMMUNITY_FOOTER);
            List<Method> binds = new ArrayList<>();
            if (footer != null) for (Method m : footer.getMethods()) if (!isStatic(m) && m.getReturnType().equals("V") && m.getImplementation() != null
                    && clean.texts.get(m.toString()).contains("footer_") && instructions(m).stream().anyMatch(i -> called(i) != null && called(i).getName().equals("setTag"))) binds.add(m);
            Method old = unique(binds, "the method that binds a community post's footer");
            if (old == null) return false;
            List<Instruction> was = instructions(old);
            int end = was.size() - 1;
            if (end <= 0 || was.get(end).getOpcode() != Opcode.RETURN_VOID || was.stream().filter(i -> i.getOpcode() == Opcode.RETURN_VOID).count() != 1) {
                fail("the community footer's bind doesn't end in its one return");
                return false;
            }
            return hookAt(old, actual(old), end, hook, List.of(parameter(old, -1)), -1, List.of());
        }

        /** The tags row's drawer handed no tags when asked, and Tumblr TV's tags through the hook right before they're split. */
        void postTags(Contract c) {
            String view = c.strings.get(2), tagModel = c.strings.get(4);
            ClassDef tags = clean.classes.get(view);
            boolean found = false;
            if (tags == null) fail("Tumblr's " + view + " is absent");
            else {
                List<Method> drawers = new ArrayList<>();
                for (Method m : tags.getMethods()) {
                    List<String> types = m.getParameterTypes().stream().map(Object::toString).toList();
                    if (!isStatic(m) && m.getReturnType().equals("V") && m.getImplementation() != null
                            && types.stream().filter(STRING_TYPE::equals).count() == 1 && types.stream().filter("Ljava/util/List;"::equals).count() == 1
                            && instructions(m).stream().anyMatch(i -> called(i) != null && called(i).getDefiningClass().equals(tagModel))) drawers.add(m);
                }
                Method old = unique(drawers, "the method that draws a post's tags");
                if (old != null) {
                    List<String> types = old.getParameterTypes().stream().map(Object::toString).toList();
                    int text = parameter(old, types.indexOf(STRING_TYPE)), list = parameter(old, types.indexOf("Ljava/util/List;"));
                    Tail none = (body, from) -> (body.get(from).getOpcode() == Opcode.CONST_STRING || body.get(from).getOpcode() == Opcode.CONST_STRING_JUMBO)
                            && firstRegister(body.get(from)) == text && reference(body.get(from)) instanceof StringReference s && s.getString().isEmpty()
                            && body.get(from + 1).getOpcode() == Opcode.SGET_OBJECT && firstRegister(body.get(from + 1)) == list
                            && String.valueOf(reference(body.get(from + 1))).equals("Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;");
                    found = guardFirst(old, c.strings.get(0), List.of(), 2, none);
                }
            }
            found &= tvTags(c.strings.get(3), c.strings.get(1));
            capability(c.callee, found);
        }

        /** The one static (String, String, boolean)List split in Tumblr TV's post text whose separator is ", ". */
        boolean tvTags(String type, String hook) {
            ClassDef view = clean.classes.get(type);
            List<Method> owners = new ArrayList<>();
            List<Integer> splits = new ArrayList<>();
            if (view != null) for (Method m : view.getMethods()) {
                if (isStatic(m) || !m.getReturnType().equals("V") || m.getImplementation() == null) continue;
                List<Instruction> body = instructions(m);
                for (int at = 0; at < body.size(); at++) if (splitsTags(body, at)) { owners.add(m); splits.add(at); }
            }
            if (splits.size() != 1) { fail("Tumblr TV's tag split has " + splits.size() + " clean targets, expected 1"); return false; }
            Method old = owners.get(0);
            int split = splits.get(0), tags = arguments(instructions(old).get(split)).get(0);
            return hookAt(old, actual(old), split, hook, List.of(tags), tags, List.of(tags));
        }

        static boolean splitsTags(List<Instruction> body, int at) {
            Instruction call = body.get(at);
            if (call.getOpcode() != Opcode.INVOKE_STATIC || called(call) == null
                    || !descriptor(called(call)).equals("(Ljava/lang/String;Ljava/lang/String;Z)Ljava/util/List;")) return false;
            List<Integer> registers = arguments(call);
            if (registers.size() < 2 || registers.get(0).equals(registers.get(1))) return false;
            for (int k = at - 1; k >= 0; k--) if (writes(body.get(k), registers.get(1)))
                return reference(body.get(k)) instanceof StringReference s && s.getString().equals(", ");
            return false;
        }

        /** The footer's count formatter, a community post's comment count text, the notes title and a community reaction's text. */
        void noteCounts(Contract c) {
            boolean found = countFormatter(c.strings.get(0));
            found &= communityComments(c.strings.get(1), c.strings.get(2));
            found &= notesTitle(c.strings.get(3));
            found &= reactionText(c.strings.get(4));
            capability(c.callee, found);
        }

        /** The one static (int)String of the class building the comment, like and reblog buttons' states, given the count first. */
        boolean countFormatter(String hook) {
            Set<String> states = new HashSet<>();
            for (String control : List.of("CommentEngagement", "LikeEngagement", "ReblogEngagement")) {
                Method button = footerControl(FOOTER_CONTROLS + control);
                if (button == null) return false;
                if (button.getParameterTypes().isEmpty()) { fail("the footer's " + control + " takes no state"); return false; }
                states.add(button.getParameterTypes().get(0).toString());
            }
            List<ClassDef> builders = new ArrayList<>();
            for (ClassDef cd : clean.classes.values()) {
                Set<String> built = new HashSet<>();
                for (Method m : cd.getMethods()) if (isStatic(m)) built.add(m.getReturnType());
                if (!cd.getType().startsWith(OWN) && built.containsAll(states)) builders.add(cd);
            }
            ClassDef builder = uniqueClass(builders, "the class that builds the footer's button states");
            if (builder == null) return false;
            List<Method> formatters = new ArrayList<>();
            for (Method m : builder.getMethods()) if (isStatic(m) && m.getImplementation() != null && descriptor(m).equals("(I)Ljava/lang/String;")) formatters.add(m);
            Method old = unique(formatters, "the footer's count formatter");
            if (old == null) return false;
            if (!clean.texts.get(old.toString()).contains("") || instructions(old).stream().noneMatch(i -> called(i) != null
                    && called(i).getDefiningClass().equals(STRING_TYPE) && called(i).getName().equals("valueOf"))) {
                fail("the footer's count formatter doesn't leave zero blank and write the rest as digits");
                return false;
            }
            int count = parameter(old, 0);
            return hookAt(old, actual(old), 0, hook, List.of(count), count, List.of());
        }

        /** "3 comments" on a community post, and the count Tumblr bolds inside it, right after the text is written. */
        boolean communityComments(String hook, String countHook) {
            List<Method> writers = new ArrayList<>();
            for (Method m : tumblrMethods()) if (clean.texts.get(m.toString()).stream().anyMatch(t -> t.startsWith(COMMENTS_NOTE))) writers.add(m);
            Method old = unique(writers, "the function that writes a community post's comment count");
            if (old == null) return false;
            List<Instruction> was = instructions(old);
            int plural = firstIndex(was, 0, i -> plural(i, "community_post_comments"));
            int written = firstIndex(was, plural < 0 ? -1 : plural + 1, i -> called(i) != null && called(i).getReturnType().equals(STRING_TYPE));
            Instruction moved = written < 0 ? null : get(was, written + 1);
            if (moved == null || moved.getOpcode() != Opcode.MOVE_RESULT_OBJECT) { fail("the community comment count isn't a String moved into a register"); return false; }
            int text = firstRegister(moved), at = written + 2;
            List<Integer> packs = new ArrayList<>();
            for (int k = plural + 1; k < at; k++) if (was.get(k).getOpcode() == Opcode.FILLED_NEW_ARRAY || was.get(k).getOpcode() == Opcode.FILLED_NEW_ARRAY_RANGE) packs.add(k);
            List<Integer> packed = packs.size() == 1 ? arguments(was.get(packs.get(0))) : List.of();
            int count = packed.size() == 1 ? packed.get(0) : -1;
            int measured = count < 0 ? -1 : firstIndex(was, at, i -> namesRegister(i, count));
            if (measured < 0 || !isCall(was.get(measured), STRING_TYPE, "length", "()I") || writtenBetween(was, packs.get(0) + 1, measured, count)) {
                fail("the community comment count isn't the plural's one argument, measured for its bold right after the text");
                return false;
            }
            Method m = actual(old);
            List<Integer> sites = calls(m, hook, 1);
            // The bolded count's hook reads no switch: it blanks the count when the comment text
            // hook right before it just swapped the text for a word, so that hook's control is its.
            // The shape below holds it to that place.
            boolean controlled = featureControls;
            featureControls = false;
            List<Integer> counts = calls(m, countHook, 1);
            featureControls = controlled;
            if (m == null || sites.size() != 1 || counts.size() != 1) return false;
            List<Instruction> body = instructions(m);
            int site = sites.get(0);
            if (!arguments(body.get(site)).equals(List.of(text)) || !movesTo(get(body, site + 1), text, Opcode.MOVE_RESULT_OBJECT)
                    || counts.get(0) != site + 2 || !arguments(body.get(site + 2)).equals(List.of(count))
                    || !movesTo(get(body, site + 3), count, Opcode.MOVE_RESULT_OBJECT)) {
                fail(hook + " doesn't take the community comment text, then its count, right after the text is written in " + m);
                return false;
            }
            remove(m, site, site + 4);
            anchor(m, site, at, hook);
            fallthroughOnly(old, at, hook);
            vouch(m, site, List.of(text));
            vouch(m, site + 2, List.of(count));
            return true;
        }

        static boolean setsTitle(Instruction i) {
            MethodReference r = called(i);
            return r != null && r.getName().equals("setTitle") && descriptor(r).equals("(Ljava/lang/CharSequence;)V");
        }

        /** The notes screen's "12 notes" title, right after getQuantityString answers it, when it reaches setTitle unchanged. */
        boolean notesTitle(String hook) {
            List<Method> titles = new ArrayList<>();
            for (Method m : tumblrMethods()) {
                List<Instruction> body = instructions(m);
                if (body.stream().anyMatch(i -> plural(i, "note_count_show")) && body.stream().anyMatch(FeatureCheck::setsTitle)) titles.add(m);
            }
            Method old = unique(titles, "the method that titles a post's notes with its count");
            if (old == null) return false;
            List<Instruction> was = instructions(old);
            int plural = firstIndex(was, 0, i -> plural(i, "note_count_show"));
            int written = firstIndex(was, plural + 1, i -> called(i) != null && called(i).getName().equals("getQuantityString"));
            Instruction moved = written < 0 ? null : get(was, written + 1);
            if (moved == null || moved.getOpcode() != Opcode.MOVE_RESULT_OBJECT || !called(was.get(written)).getReturnType().equals(STRING_TYPE)) {
                fail("the notes title isn't a String moved into a register");
                return false;
            }
            int text = firstRegister(moved), at = written + 2;
            int shown = firstIndex(was, at, FeatureCheck::setsTitle);
            if (shown < 0 || arguments(was.get(shown)).size() != 2 || arguments(was.get(shown)).get(1) != text || writtenBetween(was, at, shown, text)) {
                fail("the notes title isn't set from the count text unchanged");
                return false;
            }
            return hookAt(old, actual(old), at, hook, List.of(text), text, List.of(text));
        }

        /** A community reaction button's text and the reactions' total, right before setText shows the text. */
        boolean reactionText(String hook) {
            ClassDef control = clean.classes.get(REACTION_CONTROL);
            List<Method> writers = new ArrayList<>();
            if (control != null) for (Method m : control.getMethods()) if (!isStatic(m) && m.getImplementation() != null
                    && descriptor(m).equals("(Ljava/util/List;)V")) writers.add(m);
            Method old = unique(writers, "the method that writes a community reaction's count");
            if (old == null) return false;
            List<Instruction> was = instructions(old);
            String builder = "Ljava/lang/StringBuilder;";
            int appended = firstIndex(was, 0, i -> isCall(i, builder, "append", "(I)" + builder));
            int built = firstIndex(was, appended < 0 ? -1 : appended + 1, i -> isCall(i, builder, "toString", "()" + STRING_TYPE));
            Instruction moved = built < 0 ? null : get(was, built + 1);
            int at = moved == null ? -1 : firstIndex(was, built + 2, i -> isCall(i, "Landroid/widget/TextView;", "setText", "(Ljava/lang/CharSequence;)V"));
            if (at < 0 || moved.getOpcode() != Opcode.MOVE_RESULT_OBJECT) { fail("the community reaction text isn't a String moved into a register and shown"); return false; }
            int count = arguments(was.get(appended)).get(1), text = firstRegister(moved);
            if (arguments(was.get(at)).get(1) != text || writtenBetween(was, built + 2, at, text) || writtenBetween(was, appended + 1, at, count)) {
                fail("the community reaction text and its count don't reach setText unchanged");
                return false;
            }
            return hookAt(old, actual(old), at, hook, List.of(text, count), text, List.of(text, count));
        }

        /** The one class that looks up [badge], the bottom bar. */
        ClassDef badgeBar(String badge) {
            return uniqueClass(classesWith(i -> i.getOpcode() == Opcode.SGET && names(field(i), badge)), "the bottom bar that looks up " + badge);
        }

        /** The bar's badge pop-in, which starts an animation only on a hidden view, handed its badge through the hook first thing. */
        void unreadDots(Contract c) {
            ClassDef bar = badgeBar(c.strings.get(1));
            Method old = null;
            if (bar != null) {
                List<Method> pops = new ArrayList<>();
                for (Method m : bar.getMethods()) if (!isStatic(m) && m.getImplementation() != null
                        && descriptor(m).equals("(Landroid/widget/TextView;Landroid/view/animation/Animation;)V") && popsInHidden(instructions(m))) pops.add(m);
                old = unique(pops, "the bottom bar's badge pop-in");
            }
            boolean found = false;
            if (old != null) {
                Instruction first = instructions(old).get(0);
                int badge = parameter(old, 0);
                if (first.getOpcode() != Opcode.IF_EQZ || firstRegister(first) != badge) fail("the bottom bar's badge pop-in doesn't start by checking it has a badge");
                else found = hookAt(old, actual(old), 0, c.strings.get(0), List.of(badge), badge, List.of());
            }
            capability(c.callee, found);
        }

        static boolean popsInHidden(List<Instruction> body) {
            int asked = firstIndex(body, 0, i -> called(i) != null && called(i).getName().equals("getVisibility"));
            Instruction answer = asked < 0 ? null : get(body, asked + 1), test = asked < 0 ? null : get(body, asked + 2);
            return answer != null && test != null && answer.getOpcode() == Opcode.MOVE_RESULT && test.getOpcode() == Opcode.IF_EQZ
                    && firstRegister(answer) == firstRegister(test) && body.stream().anyMatch(i -> called(i) != null && called(i).getName().equals("startAnimation"));
        }

        /** Tumblr's tab list handed over before it's frozen, and the bottom bar's view right after its controller is built. */
        void bottomBarTabs(Contract c) {
            List<Method> builders = new ArrayList<>();
            for (Method m : tumblrMethods()) if (isStatic(m) && descriptor(m).equals("()Ljava/util/List;") && buildsTabList(instructions(m))) builders.add(m);
            Method old = unique(builders, "Tumblr's tab list builder");
            boolean found = false;
            if (old != null) {
                List<Instruction> was = instructions(old);
                List<Integer> list = arguments(was.get(was.size() - 3));
                found = hookAt(old, actual(old), was.size() - 3, c.strings.get(0), list, -1, list);
            }
            ClassDef bar = badgeBar(c.strings.get(2));
            List<Method> makers = new ArrayList<>();
            List<Integer> made = new ArrayList<>();
            if (bar != null) for (Method m : tumblrMethods()) {
                if (m.getDefiningClass().equals(bar.getType())) continue;
                List<Instruction> body = instructions(m);
                for (int at = 0; at < body.size(); at++) {
                    MethodReference r = called(body.get(at));
                    if ((body.get(at).getOpcode() == Opcode.INVOKE_DIRECT || body.get(at).getOpcode() == Opcode.INVOKE_DIRECT_RANGE) && r.getDefiningClass().equals(bar.getType())
                            && r.getName().equals("<init>") && !r.getParameterTypes().isEmpty() && r.getParameterTypes().get(0).toString().equals("Landroid/view/ViewGroup;")) {
                        makers.add(m); made.add(at);
                    }
                }
            }
            boolean barred = false;
            if (makers.size() != 1) fail("the bottom bar's controller is built in " + makers.size() + " places, expected 1");
            else {
                Method maker = makers.get(0);
                List<Integer> view = List.of(arguments(instructions(maker).get(made.get(0))).get(1));
                barred = hookAt(maker, actual(maker), made.get(0) + 1, c.strings.get(1), view, -1, view);
            }
            capability(c.callee, found && barred);
        }

        /** A static ()List adding the Dashboard and Account tabs to a new ArrayList and returning a static (Collection) copy. */
        static boolean buildsTabList(List<Instruction> body) {
            if (body.size() < 4) return false;
            int made = firstIndex(body, 0, i -> i.getOpcode() == Opcode.NEW_INSTANCE);
            if (made < 0 || !String.valueOf(reference(body.get(made))).equals("Ljava/util/ArrayList;")) return false;
            FieldReference first = null;
            boolean last = false;
            for (Instruction i : body) if (i.getOpcode() == Opcode.SGET_OBJECT && field(i) != null) {
                FieldReference f = field(i);
                if (first == null && f.getName().equals("Dashboard")) first = f;
                if (f.getName().equals("Account")) last = first == null || f.getType().equals(first.getType()) || last;
            }
            Instruction copy = body.get(body.size() - 3);
            return first != null && first.getType().equals(first.getDefiningClass()) && last
                    && copy.getOpcode() == Opcode.INVOKE_STATIC && called(copy) != null
                    && called(copy).getParameterTypes().stream().map(Object::toString).toList().equals(List.of("Ljava/util/Collection;"))
                    && body.get(body.size() - 2).getOpcode() == Opcode.MOVE_RESULT_OBJECT && body.get(body.size() - 1).getOpcode() == Opcode.RETURN_OBJECT;
        }

        boolean extendsClass(String type, String ancestor) {
            Set<String> seen = new HashSet<>();
            for (String at = type; at != null && seen.add(at); ) {
                if (at.equals(ancestor)) return true;
                ClassDef cd = clean.classes.get(at);
                at = cd == null ? null : cd.getSuperclass();
            }
            return false;
        }

        /** Right after Tumblr sets an activity's theme, and right after its Compose colors take their theme wrapper's theme. */
        void darkBackground(Contract c) {
            List<Method> setters = new ArrayList<>(), composers = new ArrayList<>();
            List<Integer> sets = new ArrayList<>();
            for (Method m : tumblrMethods()) {
                List<Instruction> body = instructions(m);
                List<? extends CharSequence> types = m.getParameterTypes();
                if (!isStatic(m) && m.getReturnType().equals("V") && types.size() == 2 && types.get(1).toString().equals("I") && types.get(0).toString().startsWith("L")) {
                    String activity = types.get(0).toString();
                    int set = firstIndex(body, 0, i -> i.getOpcode() == Opcode.INVOKE_VIRTUAL && isCall(i, activity, "setTheme", "(I)V"));
                    Instruction next = set < 0 ? null : get(body, set + 1);
                    if (next != null && arguments(body.get(set)).get(0) == parameter(m, 0) && next.getOpcode() == Opcode.SGET_OBJECT
                            && field(next).getType().equals(m.getDefiningClass())) { setters.add(m); sets.add(set); }
                }
                if (m.getName().equals("<init>") && descriptor(m).equals("(Landroid/content/Context;I)V")
                        && body.stream().anyMatch(i -> i.getOpcode() == Opcode.NEW_INSTANCE && String.valueOf(reference(i)).equals("Landroid/view/ContextThemeWrapper;"))
                        && body.stream().anyMatch(FeatureCheck::themeOfWrapper)
                        && body.stream().anyMatch(i -> i.getOpcode() == Opcode.SGET && names(field(i), c.strings.get(2)))) composers.add(m);
            }
            boolean found = false;
            Method setter = unique(setters, "the method that sets an activity's theme from its palette");
            if (setter != null && !extendsClass(setter.getParameterTypes().get(0).toString(), "Landroid/app/Activity;"))
                fail(setter.getParameterTypes().get(0) + ", whose theme Tumblr sets, isn't an activity");
            else if (setter != null) {
                int set = sets.get(0);
                List<Integer> activity = List.of(arguments(instructions(setter).get(set)).get(0));
                found = hookAt(setter, actual(setter), set + 1, c.strings.get(0), activity, -1, List.of());
            }
            Method composer = unique(composers, "the constructor that makes the theme Tumblr's Compose colors read");
            boolean themed = false;
            if (composer != null) {
                List<Instruction> was = instructions(composer);
                List<Integer> asked = new ArrayList<>();
                for (int at = 0; at < was.size(); at++) if (themeOfWrapper(was.get(at))) asked.add(at);
                Instruction answer = asked.size() == 1 ? get(was, asked.get(0) + 1) : null;
                if (answer == null || answer.getOpcode() != Opcode.MOVE_RESULT_OBJECT) fail("the Compose colors' theme isn't asked for once and kept in a register");
                else themed = hookAt(composer, actual(composer), asked.get(0) + 2, c.strings.get(1), List.of(firstRegister(answer)), -1, List.of(firstRegister(answer)));
            }
            capability(c.callee, found && themed);
        }

        static boolean themeOfWrapper(Instruction i) {
            return i.getOpcode() == Opcode.INVOKE_VIRTUAL && isCall(i, "Landroid/view/ContextThemeWrapper;", "getTheme", "()Landroid/content/res/Resources$Theme;");
        }

        /**
         * The Premium diamond's drawer asking first with its two views, every TumblrMart button handed
         * over right after its visibility is set, and the two rooms by Explore's search bar right
         * after they're stored.
         */
        void storeButtons(Contract c) {
            List<Method> drawers = new ArrayList<>();
            for (Method m : clean.holding(c.strings.get(4))) {
                List<String> types = m.getParameterTypes().stream().map(Object::toString).toList();
                if (!isStatic(m) && m.getReturnType().equals("V") && types.size() >= 2 && types.get(0).equals("Lcom/facebook/drawee/view/SimpleDraweeView;")
                        && types.get(1).equals("Landroid/widget/ImageView;")) drawers.add(m);
            }
            Method old = unique(drawers, "the method that draws the Premium diamond");
            boolean found = old != null && guardFirst(old, c.strings.get(0), List.of(parameter(old, 0), parameter(old, 1)), 1, RETURN_NOTHING);
            String hook = c.strings.get(1);
            int buttons = 0;
            for (Method was : tumblrMethods()) {
                List<Instruction> body = instructions(was);
                List<Integer> places = new ArrayList<>(), registers = new ArrayList<>();
                for (int id = 0; id < body.size(); id++) {
                    FieldReference f = field(body.get(id));
                    if (body.get(id).getOpcode() != Opcode.SGET || f == null || !f.getDefiningClass().equals(VIEW_IDS) || !TUMBLRMART_IDS.contains(f.getName())) continue;
                    Instruction result = get(body, id + 2);
                    MethodReference find = called(get(body, id + 1));
                    int button = result == null ? -1 : firstRegister(result);
                    int shown = find == null || !find.getName().equals("findViewById") || result == null || result.getOpcode() != Opcode.MOVE_RESULT_OBJECT ? -1
                            : firstIndex(body, id + 3, i -> (i.getOpcode() == Opcode.INVOKE_VIRTUAL || i.getOpcode() == Opcode.INVOKE_VIRTUAL_RANGE)
                                    && called(i).getName().equals("setVisibility") && descriptor(called(i)).equals("(I)V") && arguments(i).get(0) == button);
                    if (shown < 0 || writtenBetween(body, id + 3, shown, button)) { fail(was + " doesn't look a TumblrMart button up and set its visibility"); found = false; continue; }
                    places.add(shown + 1);
                    registers.add(button);
                }
                if (places.isEmpty()) continue;
                buttons += places.size();
                Method m = actual(was);
                List<Integer> sites = calls(m, hook, places.size());
                if (m == null || sites.size() != places.size()) { found = false; continue; }
                List<Instruction> patchedBody = instructions(m);
                for (int k = 0; k < places.size(); k++) {
                    int at = sites.get(k);
                    if (!arguments(patchedBody.get(at)).equals(List.of(registers.get(k)))) { fail(hook + " isn't handed the TumblrMart button in " + m); found = false; continue; }
                    remove(m, at, at + 1);
                    anchor(m, at, places.get(k), hook);
                    fallthroughOnly(was, places.get(k), hook);
                    vouch(m, at, List.of(registers.get(k)));
                }
            }
            if (buttons == 0) { fail("nothing in Tumblr looks up a TumblrMart button"); found = false; }
            List<Method> headers = new ArrayList<>();
            for (Method m : tumblrMethods()) if (instructions(m).stream().anyMatch(i -> i.getOpcode() == Opcode.SGET && names(field(i), VIEW_IDS + "->search_bar"))) headers.add(m);
            Method header = unique(headers, "Explore's header, which looks its search bar up");
            if (header == null) found = false;
            else for (int k = 0; k < 2; k++) found &= searchBarRoom(header, k == 0 ? "APP_TOP_NAVIGATION_UPDATE" : "ENABLE_TUMBLR_PREMIUM", c.strings.get(2 + k));
            capability(c.callee, found);
        }

        /** The room [flag] gives by Explore's search bar, through [hook] right after it's stored, before it's added into the bar's margin. */
        boolean searchBarRoom(Method old, String flag, String hook) {
            List<Instruction> was = instructions(old);
            List<Integer> reads = new ArrayList<>();
            for (int at = 0; at < was.size(); at++) {
                FieldReference f = field(was.get(at));
                if (was.get(at).getOpcode() == Opcode.SGET_OBJECT && f.getName().equals(flag) && f.getType().equals(f.getDefiningClass())) reads.add(at);
            }
            int stored = reads.size() != 1 ? -1 : firstIndex(was, reads.get(0) + 1, i -> i.getOpcode() == Opcode.IPUT && field(i).getDefiningClass().equals(old.getDefiningClass()));
            boolean sized = stored >= 0 && was.subList(reads.get(0) + 1, stored).stream().anyMatch(i -> field(i) != null && field(i).getName().equals("top_navigation_icon_size"));
            int room = stored < 0 ? -1 : firstRegister(was.get(stored)), at = stored + 1;
            int used = room < 0 ? -1 : firstIndex(was, at, i -> namesRegister(i, room));
            if (!sized || used < 0 || was.get(used).getOpcode() != Opcode.ADD_INT_2ADDR && was.get(used).getOpcode() != Opcode.ADD_INT) {
                fail(old + " doesn't size, store and add up the room " + flag + " gives");
                return false;
            }
            return hookAt(old, actual(old), at, hook, List.of(room), room, List.of(room));
        }

        List<String> normalized(Method m, FeatureEdits e) {
            List<String> out = new ArrayList<>();
            if (m == null || m.getImplementation() == null) return out;
            Layout layout = new Layout(m.getImplementation());
            Map<Integer, Integer> positions = new HashMap<>();
            int position = 0;
            for (int at = 0; at < layout.instructions.size(); at++) {
                positions.put(layout.addresses.get(at), position);
                if ((e == null || !e.removed.contains(at)) && layout.instructions.get(at).getOpcode() != Opcode.NOP) position++;
            }
            positions.put(layout.size, position);
            Map<Integer, Integer> switches = new HashMap<>();
            for (int at = 0; at < layout.instructions.size(); at++) {
                Instruction i = layout.instructions.get(at);
                if ((i.getOpcode() == Opcode.PACKED_SWITCH || i.getOpcode() == Opcode.SPARSE_SWITCH) && i instanceof OffsetInstruction)
                    switches.put(layout.addresses.get(at) + ((OffsetInstruction) i).getCodeOffset(), layout.addresses.get(at));
            }
            for (int at = 0; at < layout.instructions.size(); at++) {
                if (e != null && e.removed.contains(at)) continue;
                Instruction i = e == null ? layout.instructions.get(at) : e.replacements.getOrDefault(at, layout.instructions.get(at));
                if (i.getOpcode() == Opcode.NOP) continue;
                String text = render(i).replace("const-string/jumbo", "const-string");
                if (i instanceof OffsetInstruction) {
                    String raw = String.format("%+d", ((OffsetInstruction) i).getCodeOffset());
                    int target = layout.addresses.get(at) + ((OffsetInstruction) i).getCodeOffset();
                    text = text.replace(", " + raw + " |maxreg=", ", target=" + originalTarget(m, layout, e, positions, target) + " |maxreg=");
                    if (i.getOpcode().name.startsWith("goto")) text = text.replaceFirst("goto(?:/16|/32)?", "goto");
                }
                if (i instanceof SwitchPayload) {
                    text = i.getOpcode().name;
                    Integer owner = switches.get(layout.addresses.get(at));
                    for (SwitchElement element : ((SwitchPayload) i).getSwitchElements()) text += " " + element.getKey() + "->" + (owner == null ? "missing" : originalTarget(m, layout, e, positions, owner + element.getOffset()));
                }
                out.add(text);
            }
            for (TryBlock<? extends ExceptionHandler> block : m.getImplementation().getTryBlocks()) {
                String text = "try " + positions.get(block.getStartCodeAddress()) + ":" + positions.get(block.getStartCodeAddress() + block.getCodeUnitCount());
                for (ExceptionHandler handler : block.getExceptionHandlers()) text += " " + handler.getExceptionType() + "->" + originalTarget(m, layout, e, positions, handler.getHandlerCodeAddress());
                out.add(text);
            }
            return out;
        }

        Integer originalTarget(Method m, Layout layout, FeatureEdits e, Map<Integer, Integer> positions, int target) {
            if (e != null) {
                int index = layout.addresses.indexOf(target);
                if (index > 0 && e.removed.contains(index) && e.removed.contains(index - 1))
                    fail(m + " routes an original branch or handler into the middle of an injected block");
            }
            return positions.get(target);
        }

        void run(List<Contract> rules) {
            ClassDef status = patched.classes.get(STATUS);
            if (status == null || !AccessFlags.PUBLIC.isSet(status.getAccessFlags())) fail("compiled SettingsStatus class is absent or inaccessible");
            Set<String> names = new HashSet<>();
            names.add("HushTumblr settings");
            // The patcher reports unnamed dependencies using their two concrete patch kinds.
            names.add("BytecodePatch");
            names.add("ResourcePatch");
            Set<String> families = new HashSet<>();
            for (Contract c : rules) if (c.kind.equals("family")) {
                if (!names.add(c.target) || flags.containsKey(c.callee)) { fail("duplicate family " + c.target); continue; }
                families.add(c.callee);
                readFlag(c.callee);
                for (String cap : c.strings.get(0).split(",")) {
                    if (cap.isEmpty()) continue;
                    if (owners.putIfAbsent(cap, c.callee) != null) fail("duplicate capability " + cap);
                    readFlag(cap);
                    if (!flag(c.callee) && flag(cap)) fail(cap + " is installed without " + c.callee);
                }
                if (selected != null && flag(c.callee) != selected.contains(c.target))
                    fail(c.target + " installed flag disagrees with selected patches");
            }
            if (selected != null) for (String name : selected) if (!names.contains(name)) fail("unknown selected patch " + name);
            if (status != null) for (Method m : status.getMethods()) if (descriptor(m).equals("()Z") && !flags.containsKey(m.getName()))
                fail("compiled status flag has no family contract: " + m.getName());
            Set<String> coveredCapabilities = new HashSet<>();
            for (Contract c : rules) if (c.kind.equals("mutation")) {
                for (String cap : c.callee.split(",")) {
                    coveredCapabilities.add(cap);
                    if (!owners.containsKey(cap) && !cap.equals("settings")) fail("unowned mutation capability " + cap);
                }
                for (String user : users(c)) if (!families.contains(user)) fail("mutation " + c.target + " names an unknown family " + user);
                if (!active(c)) continue;
                featureControls = !c.callee.equals("settings");
                switch (c.target) {
                    case "answers": answers(c); break;
                    case "firebaseCertificate": firebaseCertificate(c); break;
                    case "settings": settings(c); break;
                    case "settingsRow": settingsRow(c); break;
                    case "featureSwitch": featureSwitch(c); break;
                    case "timelineList": timelineList(c); break;
                    case "timelineCarousel": timelineCarousel(c); break;
                    case "postBridge": postBridge(c); break;
                    case "reviewPrompt": reviewPrompt(c); break;
                    case "followButton": followButton(c); break;
                    case "footerButton": footerButton(c); break;
                    case "postTags": postTags(c); break;
                    case "noteCounts": noteCounts(c); break;
                    case "unreadDots": unreadDots(c); break;
                    case "bottomBarTabs": bottomBarTabs(c); break;
                    case "darkBackground": darkBackground(c); break;
                    case "storeButtons": storeButtons(c); break;
                    default: fail("unknown mutation kind " + c.target);
                }
            }
            for (Anchor a : anchors) {
                FeatureEdits e = edits.get(a.method().toString());
                int kept = 0;
                for (int k = 0; k < a.at(); k++) if (e == null || !e.removed.contains(k)) kept++;
                if (kept != a.clean()) fail(a.what() + " is not where its rule puts it in " + a.method()
                        + " (after original instruction " + kept + ", expected " + a.clean() + ")");
            }
            for (FeatureSite site : hookSites) hookRegisters(site);
            for (String cap : owners.keySet()) if (!coveredCapabilities.contains(cap)) fail("capability has no compiled mutation contract: " + cap);
            for (Map.Entry<String, List<FeatureSite>> entry : patched.ownCalls.entrySet()) for (FeatureSite site : entry.getValue())
                if (!claimed.contains(site.key())) fail("unselected, duplicated or misrouted call " + entry.getKey() + " in " + site.method);
            for (Map.Entry<String, FeatureEdits> entry : edits.entrySet()) {
                Method original = clean.methods.get(entry.getKey());
                Method changed = patched.methods.get(entry.getKey());
                if (original != null && !normalized(original, null).equals(normalized(changed, entry.getValue())))
                    fail(entry.getKey() + " changed original instructions, branches or exception ranges beyond its validated mutations");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 7) {
            System.err.println("usage: DexDiff <cleanApk> <patchedApk> <reportFile> <removalAllowlist> [<contracts> [<signedBase> [<selectedPatches>]]]");
            System.exit(2);
        }
        File clean = new File(args[0]);
        File patched = new File(args[1]);
        File allowlistFile = new File(args[3]);
        RemovalAllowlist allowlist = readRemovalAllowlist(allowlistFile);
        File contractFile = args.length > 4 ? new File(args[4]) : null;
        List<Contract> contracts = readContracts(contractFile);
        // The base.apk whose signer was checked, when the clean side is the bundle's merge.
        File signedBase = args.length > 5 && !args[5].equals("-") ? new File(args[5]) : null;
        Set<String> selected = null;
        if (args.length > 6 && !args[6].equals("-")) {
            File file = new File(args[6]);
            if (!file.isFile()) throw new IllegalArgumentException("Selected patch file not found: " + file);
            selected = new TreeSet<>();
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (!selected.add(name)) throw new IllegalArgumentException("Duplicated selected patch: " + name);
            }
        }
        List<String> baseMismatch = signedBase == null ? List.of() : rootDexMismatch(signedBase, clean);

        System.out.println("[diff] fingerprinting clean " + clean.getName());
        Map<String, String> before = fingerprintAll(clean);
        Set<String> beforeDexEntries = dexEntries(clean);
        System.out.println("[diff] " + before.size() + " methods");
        System.out.println("[diff] fingerprinting patched " + patched.getName());
        Map<String, String> after = fingerprintAll(patched);
        Set<String> afterDexEntries = dexEntries(patched);
        System.out.println("[diff] " + after.size() + " methods");
        System.out.println("[diff] signatures defined in more than one dex entry: clean " + multiplyDefined(before)
                + ", patched " + multiplyDefined(after));

        Set<String> changed = new TreeSet<>();
        Set<String> added = new TreeSet<>();
        for (Map.Entry<String, String> e : after.entrySet()) {
            String was = before.get(e.getKey());
            if (was == null) added.add(e.getKey());
            else if (!was.equals(e.getValue())) changed.add(e.getKey());
        }
        // A method gone from the patched APK, or one of its definitions gone while a copy in another
        // dex entry stays: a definition the clean build has more of than the patched one.
        Set<String> removed = new TreeSet<>();
        for (Map.Entry<String, String> e : before.entrySet()) {
            if (prints(after.get(e.getKey())).size() < prints(e.getValue()).size()) removed.add(e.getKey());
        }
        // The clean side is Meta's build, which carries none of the bundle's code. A merged bundle
        // carries no signature to prove that the way base.apk does, and a patched build on the clean
        // side would hide everything the bundle added.
        Set<String> cleanOwn = new TreeSet<>();
        for (String s : before.keySet()) if (s.startsWith(OWN)) cleanOwn.add(s);
        Set<String> removedDexEntries = new TreeSet<>(beforeDexEntries);
        removedDexEntries.removeAll(afterDexEntries);

        Set<String> rejectedRemoved = new TreeSet<>(removed);
        rejectedRemoved.removeAll(allowlist.methods);
        Set<String> rejectedDexEntries = new TreeSet<>(removedDexEntries);
        rejectedDexEntries.removeAll(allowlist.dexEntries);
        Set<String> staleAllowedMethods = new TreeSet<>(allowlist.methods);
        staleAllowedMethods.removeAll(removed);
        Set<String> staleAllowedDexEntries = new TreeSet<>(allowlist.dexEntries);
        staleAllowedDexEntries.removeAll(removedDexEntries);

        Set<String> ownAdded = new TreeSet<>();
        for (String s : added) if (s.startsWith(OWN)) ownAdded.add(s);

        System.out.println("[diff] host methods changed: " + changed.size());
        System.out.println("[diff] methods added: " + added.size()
                + " (" + ownAdded.size() + " under " + OWN + ")");
        System.out.println("[diff] methods removed: " + removed.size()
                + " (rejected " + rejectedRemoved.size() + ")");
        System.out.println("[diff] DEX entries removed: " + removedDexEntries.size()
                + " (rejected " + rejectedDexEntries.size() + ")");

        // A pair of files with nothing between them is not a clean bill of health, it is the wrong
        // pair of files. Both of these were reachable by pointing the run at one APK twice.
        int problems = 0;
        if (!baseMismatch.isEmpty()) {
            System.out.println("[diff] FAIL: the clean APK " + clean.getName() + " doesn't carry " + signedBase.getName()
                    + "'s code as it is: it " + String.join("; ", baseMismatch) + ". Meta's signer was checked on "
                    + signedBase.getName() + ", so this would compare against code nobody checked.");
            problems++;
        } else if (signedBase != null) {
            System.out.println("[diff] the clean APK carries " + signedBase.getName() + "'s classes*.dex byte for byte");
        }
        if (!cleanOwn.isEmpty()) {
            System.out.println("[diff] FAIL: the clean APK carries " + cleanOwn.size() + " method"
                    + (cleanOwn.size() == 1 ? "" : "s") + " under " + OWN + " (" + cleanOwn.iterator().next()
                    + (cleanOwn.size() == 1 ? "" : " first") + "), so it is a patched build, not the one the patch started from.");
            problems++;
        }
        if (changed.isEmpty()) {
            System.out.println("[diff] FAIL: no host method differs, so these two APKs are not a "
                    + "clean build and a patched build of it.");
            problems++;
        }
        if (ownAdded.isEmpty()) {
            System.out.println("[diff] FAIL: the patched APK carries no method under " + OWN
                    + ", so no extension code was added to it.");
            problems++;
        }
        if (!rejectedRemoved.isEmpty()) {
            System.out.println("[diff] FAIL: removed host methods are not allowed:");
            for (String method : rejectedRemoved) System.out.println("[diff]   " + method);
            problems += rejectedRemoved.size();
        }
        if (!rejectedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: removed DEX entries are not allowed:");
            for (String entry : rejectedDexEntries) System.out.println("[diff]   " + entry);
            problems += rejectedDexEntries.size();
        }
        if (!staleAllowedMethods.isEmpty() || !staleAllowedDexEntries.isEmpty()) {
            System.out.println("[diff] FAIL: the removal allowlist contains entries this pair did not remove:");
            for (String method : staleAllowedMethods) System.out.println("[diff]   method " + method);
            for (String entry : staleAllowedDexEntries) System.out.println("[diff]   dex " + entry);
            problems += staleAllowedMethods.size() + staleAllowedDexEntries.size();
        }

        System.out.println("[diff] reading both bodies for the changed and added methods");
        Set<String> wanted = new TreeSet<>(changed);
        wanted.addAll(added);
        Map<String, List<List<String>>> beforeBodies = bodiesOf(clean, changed);
        Map<String, List<List<String>>> afterBodies = bodiesOf(patched, wanted);

        // Every method the patch wrote or touched, the host's and the bundle's alike: each
        // definition of it the clean build doesn't have.
        System.out.println("[diff] checking branches, invokes, parameters and try ranges of "
                + (changed.size() + added.size()) + " methods");
        Set<String> structuralWanted = new TreeSet<>(changed);
        structuralWanted.addAll(added);
        Map<String, List<String>> fresh = new HashMap<>();
        for (String s : structuralWanted) fresh.put(s, without(prints(after.get(s)), prints(before.get(s))));
        Map<String, List<String>> structural = structuralPass(patched, clean, structuralWanted, fresh, contracts);
        if (contracts.stream().anyMatch(c -> c.kind.equals("family"))) {
            FeatureIndex featurePatched = new FeatureIndex(patched);
            System.out.println("[diff] checking compiled family flags, capabilities and original fallbacks"
                    + (selected == null ? " (selection file not supplied)" : " against " + selected.size() + " selected patches"));
            FeatureCheck check = new FeatureCheck(new FeatureIndex(clean), featurePatched, selected);
            check.run(contracts);
            structural.computeIfAbsent("contract", k -> new ArrayList<>()).addAll(check.findings);
        } else if (selected != null) {
            structural.computeIfAbsent("contract", k -> new ArrayList<>()).add("contract: selected patches supplied without family contracts");
        }
        int structuralCount = 0;
        for (Map.Entry<String, List<String>> e : structural.entrySet()) {
            for (String finding : e.getValue()) {
                structuralCount++;
                System.out.println("[diff] FAIL: " + finding
                        + (e.getKey().equals("contract") ? "" : "  in " + e.getKey()));
            }
        }
        System.out.println("[diff] structural findings: " + structuralCount);
        problems += structuralCount;

        PrintWriter report = new PrintWriter(args[2], "UTF-8");
        try {
            report.println("Methods a patched APK does not share with the clean build it came from.");
            report.println("clean:   " + clean.getAbsolutePath());
            report.println("patched: " + patched.getAbsolutePath());
            report.println("removal allowlist: " + allowlistFile.getAbsolutePath());
            report.println("contracts: " + (contractFile == null ? "none" : contractFile.getAbsolutePath()));
            report.println("changed=" + changed.size() + " added=" + added.size()
                    + " (own=" + ownAdded.size() + ") removed=" + removed.size()
                    + " removedDex=" + removedDexEntries.size());
            report.println();

            report.println("Structural findings: " + structuralCount);
            for (Map.Entry<String, List<String>> e : structural.entrySet()) {
                report.println("  " + e.getKey());
                for (String finding : e.getValue()) report.println("    FAIL  " + finding);
            }
            report.println();

            report.println("Removed methods:");
            for (String method : removed) {
                report.println((allowlist.methods.contains(method) ? "  allowed " : "  FAIL    ") + method);
            }
            report.println("Removed DEX entries:");
            for (String entry : removedDexEntries) {
                report.println((allowlist.dexEntries.contains(entry) ? "  allowed " : "  FAIL    ") + entry);
            }
            report.println("Stale removal allowlist entries:");
            for (String method : staleAllowedMethods) report.println("  FAIL    method " + method);
            for (String entry : staleAllowedDexEntries) report.println("  FAIL    dex " + entry);
            report.println();

            int overRegister = 0;
            int unreadable = 0;

            for (String s : changed) {
                // The definitions both builds have, body for body, are copies in other dex entries
                // the patch left alone. What's left on each side is paired in dex entry order.
                List<List<String>> cleanBodies = beforeBodies.getOrDefault(s, List.of());
                List<List<String>> patchedBodies = afterBodies.getOrDefault(s, List.of());
                List<List<String>> befores = without(cleanBodies, patchedBodies);
                List<List<String>> afters = without(patchedBodies, cleanBodies);
                int pairs = Math.max(befores.size(), afters.size());
                for (int k = 0; k < pairs; k++) {
                    List<String> b = k < befores.size() ? befores.get(k) : List.of();
                    List<String> a = k < afters.size() ? afters.get(k) : List.of();
                    int regsBefore = registersOf(b), regsAfter = registersOf(a);
                    report.println("==== " + s + (pairs == 1 ? "" : "  (definition " + (k + 1) + " of " + pairs + " that differ)"));
                    report.println("     registers " + regsBefore + " -> " + regsAfter
                            + ", instructions " + Math.max(0, b.size() - 1) + " -> " + Math.max(0, a.size() - 1));
                    if (regsAfter < 0 && !a.isEmpty()) {
                        // Nothing to hold the injected lines to. Silently skipping this was a hole:
                        // any method the second pass failed to render passed the check by default.
                        report.println("  !  no register count could be read for this method");
                        unreadable++;
                    }
                    List<String> onlyAfter = minus(a, b);
                    for (String line : minus(b, a)) report.println("  -  " + line);
                    for (String line : onlyAfter) {
                        int high = highestRegister(line);
                        boolean bad = high >= 0 && regsAfter >= 0 && high >= regsAfter;
                        if (bad) overRegister++;
                        report.println("  +  " + line + (bad ? "   <<< REGISTER >= registerCount" : ""));
                    }
                    report.println();
                }
            }

            // Added methods are where registers are chosen by hand rather than reused from the
            // host, which is exactly where an out-of-range one would come from: the extension's,
            // and the helpers a patch adds to one of the host app's own classes (the Facebook
            // sibling's story, reel and video downloads each added one). Only the extension's were
            // read here until 2026-09-26.
            // Each definition of one is read, in whichever dex entry it landed.
            for (String s : added) {
                for (List<String> a : afterBodies.getOrDefault(s, List.of())) {
                    int regsAfter = registersOf(a);
                    if (a.isEmpty()) continue;
                    List<String> offending = new ArrayList<>();
                    for (String line : a) {
                        if (line.startsWith("#")) continue;
                        int high = highestRegister(line);
                        if (high >= 0 && regsAfter >= 0 && high >= regsAfter) offending.add(line);
                    }
                    if (regsAfter < 0) {
                        report.println("==== added " + s);
                        report.println("  !  no register count could be read for this method");
                        unreadable++;
                    }
                    if (offending.isEmpty()) continue;
                    overRegister += offending.size();
                    report.println("==== added " + s);
                    report.println("     registers " + regsAfter);
                    for (String line : offending) {
                        report.println("  +  " + line + "   <<< REGISTER >= registerCount");
                    }
                    report.println();
                }
            }

            report.println("Lines naming a register at or above the method's register count: " + overRegister);
            report.println("Methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] methods whose register count could not be read: " + unreadable);
            System.out.println("[diff] injected lines naming an out-of-range register: " + overRegister);
            if (unreadable != 0) problems++;
            if (overRegister != 0) problems++;
        } finally {
            report.close();
        }
        System.out.println("[diff] report written to " + args[2]);
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * The register count the body declares, or -1 when the body was not read at all.
     *
     * <p>Zero is a real answer: a static method with no arguments and no locals declares no
     * registers, and R8's synthetic lambda bridges are full of them. Treating zero as "unknown"
     * flagged twenty-one of the bundle's own methods as unreadable when nothing was wrong.
     */
    private static int registersOf(List<String> body) {
        if (body.isEmpty() || !body.get(0).startsWith("# registers=")) return -1;
        return Integer.parseInt(body.get(0).substring("# registers=".length()));
    }

    /** Lines of a that are not in b, counting duplicates. */
    private static List<String> minus(List<String> a, List<String> b) {
        Map<String, Integer> pool = new HashMap<>();
        for (String s : b) pool.merge(s, 1, Integer::sum);
        List<String> out = new ArrayList<>();
        for (String s : a) {
            Integer left = pool.get(s);
            if (left != null && left > 0) pool.put(s, left - 1);
            else if (!s.startsWith("# registers=")) out.add(s);
        }
        return out;
    }

    /** The highest register the instruction touched, read back from the marker render() wrote. */
    private static int highestRegister(String line) {
        int at = line.lastIndexOf("|maxreg=");
        if (at < 0) return -1;
        return Integer.parseInt(line.substring(at + "|maxreg=".length()).trim());
    }
}
