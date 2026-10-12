/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.rootmute

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.freeLocalsAt
import app.morphe.patches.tumblr.misc.extension.requireParameterIntact
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.requireStub
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.extension.writeStub
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.patches.tumblr.timeline.POST
import app.morphe.patches.tumblr.timeline.postJsonPresencePatch
import app.morphe.patches.tumblr.timeline.timelineListHookPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val PATCH = "Mute posts locally"
internal const val ROOT_POST_WRAPPER = "Lv81;"
internal const val ROOT_MENU_OWNER = "Lu9g;"
internal const val ROOT_MENU = "$EXTENSION_PACKAGE/rootmute/RootPostMenu;"
internal const val ROOT_ACTION = "$EXTENSION_PACKAGE/rootmute/RootPostMuteAction;"
internal const val HUSH_ROOT_POST = "$EXTENSION_PACKAGE/timeline/HushRootPost;"
internal const val HUSH_LOCAL_TIMELINE = "$EXTENSION_PACKAGE/rootmute/HushLocalTimeline;"
internal const val LOCAL_TIMELINE = "Lcom/tumblr/ui/fragment/TimelineFragment;"
internal const val ROOT_TIMELINE_OBJECT = "Lcom/tumblr/rumblr/model/TimelineObject;"
internal const val ROOT_MENU_HOOK = "$ROOT_MENU->add(Ljava/lang/Object;Ljava/lang/Object;Landroid/content/Context;Ljava/lang/Object;)V"
internal const val CANONICAL_ROOT = "$ROOT_MUTES->canonicalRoot(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Z)Ljava/lang/String;"
internal const val ROOT_TRAIL_PRESENT = "$POST->hushRootTrailPresent:Z"
internal const val ROOT_JSON_SET = "$POST->hushSetJsonPresence(II)V"
internal const val WRAPPED_CANONICAL_ROOT = "$ROOT_POST_WRAPPER->hushCanonicalRoot:Ljava/lang/String;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val MODE = "Lrbk;"
private const val CALLBACK = "Ltj8;"
private const val UNIT = "Lb6l;"
private const val MENU_BUILDER = "Lcom/tumblr/components/bottomsheet/b\$b;"
private const val MENU_CONCRETE = "Lcom/tumblr/components/bottomsheet/b\$a;"
private const val TIMELINEABLE = "Lcom/tumblr/rumblr/model/Timelineable;"

/** A private account list, with a local action and an immediate local list replacement. */
@Suppress("unused")
val muteRootPostsPatch = bytecodePatch(
    name = PATCH,
    description = "Adds a local mute action to post menus. Originals and their reblogs stay hidden for this account. " +
        "Starts off. Turn it on in Tumblr settings > HushTumblr > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, tumblrExtensionPatch, timelineListHookPatch, accountIdentityPatch, postJsonPresencePatch)
    compatibleWith(*AppCompatibilities.tumblr())
    execute {
        requireStatusMethod("muteRootPosts")
        requireStatusMethod("rootPostMutes")
        applyRootPostMutes(resolveRootPostMutes())
        enableCapability("rootPostMutes")
        enableStatus("muteRootPosts")
    }
}

internal data class RootBridgePlan(val owner: String, val getters: List<String>)
internal data class RootPostMutesPlan(val menu: MutableMethod, val at: Int, val scratch: List<Int>, val roots: List<RootBridgePlan>,
    val wrapperConstructor: MutableMethod, val captureScratch: Int)

internal fun BytecodePatchContext.resolveRootPostMutes(): RootPostMutesPlan {
    requireExtensionHook(PATCH, ROOT_MENU_HOOK)
    requireExtensionHook(PATCH, CANONICAL_ROOT)
    for (stub in listOf("cardRoot", "addNative", "unit")) requireStub(ROOT_MENU, stub)
    val rootInterface = classDefByOrNull(HUSH_ROOT_POST)
    requireShape(PATCH, rootInterface != null && AccessFlags.INTERFACE.isSet(rootInterface.accessFlags)
        && rootInterface.methods.count() == 1 && rootInterface.methods.single().let {
            it.name == "hushRootId" && it.hasShape(emptyList(), STRING) && AccessFlags.ABSTRACT.isSet(it.accessFlags)
        }, "the canonical root interface changed")
    val native = listOf("reblogged_root_id", "reblogged_from_id", "id").map { rootGetter(it, STRING) } + rootGetter("trail", LIST)
    val wrapperConstructor = rootMethod(ROOT_POST_WRAPPER, "<init>", listOf(POST), "V")
    val constructorBody = wrapperConstructor.instructionList()
    requireShape(PATCH, wrapperConstructor.implementation!!.registerCount == 9 && !wrapperConstructor.isStatic()
        && constructorBody.first().opcode == Opcode.INVOKE_DIRECT
        && constructorBody.first().methodRef().toString() == "Ljava/lang/Object;-><init>()V"
        && (constructorBody.first() as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == 7 },
        "the wrapped Post constructor no longer has its original receiver and Post parameter")
    wrapperConstructor.requireParameterIntact(PATCH, 0, listOf(1))
    val captureScratch = wrapperConstructor.freeLocalsAt(PATCH, 1, 1, reads = listOf(7, 8)).single()
    val postModel = classDefByOrNull(POST)
    requireShape(PATCH, postModel?.fields?.any { it.name == "hushRootTrailPresent" && it.type == "Z"
        && AccessFlags.PRIVATE.isSet(it.accessFlags) } == true && postModel.methods.any {
        it.name == "hushSetJsonPresence" && it.hasShape(listOf("I", "I"), "V") && !it.isStatic()
            && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.SYNTHETIC.isSet(it.accessFlags)
    }, "the shared explicit Post JSON presence bridge is missing")
    for (type in listOf(POST, ROOT_POST_WRAPPER)) {
        val owner = classDefByOrNull(type)
        requireShape(PATCH, owner != null && HUSH_ROOT_POST !in owner.interfaces
            && owner.methods.none { it.name == "hushRootId" }
            && owner.fields.none { it.name == "hushCanonicalRoot" },
            "the canonical root bridge already exists or its owner is missing")
    }
    requireLocalTimeline()
    val data = rootMethod(ROOT_TIMELINE_OBJECT, "getData", emptyList(), TIMELINEABLE)
    requireShape(PATCH, !data.isStatic() && AccessFlags.PUBLIC.isSet(data.accessFlags), "the timeline payload getter changed")
    rootMethod("Lvgi;", "l", emptyList(), TIMELINEABLE).also {
        requireShape(PATCH, !it.isStatic() && AccessFlags.PUBLIC.isSet(it.accessFlags), "the post card payload getter changed")
    }
    val callback = classDefByOrNull(CALLBACK)
    requireShape(PATCH, callback != null && AccessFlags.PUBLIC.isSet(callback.accessFlags) && AccessFlags.INTERFACE.isSet(callback.accessFlags)
        && callback.interfaces.toList() == listOf("Lvj8;") && callback.methods.count() == 1
        && callback.methods.single().let { it.name == "invoke" && it.hasShape(emptyList(), "Ljava/lang/Object;")
            && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.ABSTRACT.isSet(it.accessFlags) },
        "the native menu callback is not Function0")
    val action = classDefByOrNull(ROOT_ACTION)
    requireShape(PATCH, action != null && CALLBACK !in action.interfaces && action.methods.any {
        it.name == "invoke" && it.hasShape(emptyList(), "Ljava/lang/Object;") && AccessFlags.PUBLIC.isSet(it.accessFlags)
            && !it.isStatic() && it.implementation != null
    }, "the local menu callback bridge changed")
    requireShape(PATCH, classDefByOrNull(UNIT)?.fields?.count {
        it.name == "a" && it.type == UNIT && AccessFlags.PUBLIC.isSet(it.accessFlags)
            && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.FINAL.isSet(it.accessFlags)
    } == 1, "the native Unit singleton changed")
    for ((name, parameters, result) in listOf(Triple("c", listOf(STRING, CALLBACK), MENU_BUILDER),
        Triple("h", emptyList(), "Lcom/tumblr/components/bottomsheet/b;"))) {
        val method = rootMethod(MENU_BUILDER, name, parameters, result)
        requireShape(PATCH, !method.isStatic() && AccessFlags.PUBLIC.isSet(method.accessFlags), "the native menu builder changed")
    }
    val parameters = listOf("Lcom/tumblr/ui/fragment/c;", "Lcom/tumblr/rumblr/TumblrPostNotesService;", "Lu9g\$a;", "Ly9g;",
        STRING, STRING, STRING, "Lzve;", "Lpxa;", "Lcom/tumblr/analytics/ScreenType;", "Lju1;")
    val menu = rootMethod(ROOT_MENU_OWNER, "y", parameters, "V")
    val body = menu.instructionList()
    val at = 225
    requireShape(PATCH, menu.isStatic() && AccessFlags.PUBLIC.isSet(menu.accessFlags) && menu.implementation!!.registerCount == 28
        && body.size > at && body[at].opcode == Opcode.INVOKE_VIRTUAL
        && body[at].methodRef().toString() == "$MENU_BUILDER->h()Lcom/tumblr/components/bottomsheet/b;"
        && (body[at] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == 11 }
        && body[12].methodRef().toString() == "Landroidx/fragment/app/Fragment;->requireContext()Landroid/content/Context;"
        && body[13].opcode == Opcode.MOVE_RESULT_OBJECT && (body[13] as OneRegisterInstruction).registerA == 10
        && body[14].opcode == Opcode.NEW_INSTANCE && (body[14] as ReferenceInstruction).reference.toString() == MENU_CONCRETE
        && (body[14] as OneRegisterInstruction).registerA == 11
        && body[15].methodRef().toString() == "$MENU_CONCRETE-><init>(Landroid/content/Context;)V"
        && (body[15] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerC == 11 && it.registerD == 10 },
        "the native post menu no longer finalizes its known builder and context")
    for ((register, expected) in listOf(10 to 13, 11 to 14)) requireShape(PATCH,
        body.take(at).withIndex().filter { it.value.opcode.setsRegister() && (it.value as? OneRegisterInstruction)?.registerA == register }
            .map { it.index } == listOf(expected), "the post menu replaces its builder or context before finalization")
    menu.requireParameterIntact(PATCH, 0, listOf(at))
    menu.requireParameterIntact(PATCH, 7, listOf(at))
    val scratch = menu.freeLocalsAt(PATCH, at, 2, reads = listOf(10, 11, 17, 24))
    return RootPostMutesPlan(menu, at, scratch, listOf(RootBridgePlan(POST, native), RootBridgePlan(ROOT_POST_WRAPPER, emptyList())),
        wrapperConstructor, captureScratch)
}

internal fun BytecodePatchContext.applyRootPostMutes(plan: RootPostMutesPlan) {
    mutableClassDefBy(ROOT_POST_WRAPPER).instanceFields.add(ImmutableField(ROOT_POST_WRAPPER, "hushCanonicalRoot", STRING,
        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null, null).toMutable())
    for (root in plan.roots) {
        val owner = mutableClassDefBy(root.owner)
        owner.interfaces.add(HUSH_ROOT_POST)
        owner.methods.add(ImmutableMethod(root.owner, "hushRootId", emptyList(), STRING, AccessFlags.PUBLIC.value,
            null, null, MutableMethodImplementation(if (root.owner == POST) 6 else 2)).toMutable().apply {
            if (root.owner == POST) addInstructions(0, """
                invoke-virtual { p0 }, ${root.getters[0]}
                move-result-object v0
                invoke-virtual { p0 }, ${root.getters[1]}
                move-result-object v1
                invoke-virtual { p0 }, ${root.getters[2]}
                move-result-object v2
                invoke-virtual { p0 }, ${root.getters[3]}
                move-result-object v3
                iget-boolean v4, p0, $ROOT_TRAIL_PRESENT
                invoke-static { v0, v1, v2, v3, v4 }, $CANONICAL_ROOT
                move-result-object v0
                return-object v0
            """) else addInstructions(0, "iget-object v0, p0, $WRAPPED_CANONICAL_ROOT\nreturn-object v0")
        })
    }
    plan.wrapperConstructor.addInstructions(1, """
        invoke-interface/range { p1 .. p1 }, $HUSH_ROOT_POST->hushRootId()$STRING
        move-result-object v${plan.captureScratch}
        iput-object v${plan.captureScratch}, p0, $WRAPPED_CANONICAL_ROOT
    """)
    val timeline = mutableClassDefBy(LOCAL_TIMELINE)
    timeline.interfaces.add(HUSH_LOCAL_TIMELINE)
    timeline.methods.add(ImmutableMethod(LOCAL_TIMELINE, "hushTimelineItems", emptyList(), LIST, AccessFlags.PUBLIC.value,
        null, null, MutableMethodImplementation(3)).toMutable().apply {
        addInstructions(0, "new-instance v0, Ljava/util/ArrayList;\niget-object v1, p0, $LOCAL_TIMELINE->z:$LIST\n" +
            "invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V\nreturn-object v0")
    })
    timeline.methods.add(ImmutableMethod(LOCAL_TIMELINE, "hushApplyTimelineItems", listOf(
        com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter(LIST, null, null)), "V", AccessFlags.PUBLIC.value,
        null, null, MutableMethodImplementation(3)).toMutable().apply {
        addInstructions(0, "sget-object v0, $MODE->FROM_CACHE:$MODE\n" +
            "invoke-virtual { p0, v0, p1 }, $LOCAL_TIMELINE->i6($MODE$LIST)V\nreturn-void")
    })
    mutableClassDefBy(ROOT_ACTION).interfaces.add(CALLBACK)
    writeStub(ROOT_MENU, "addNative", 3, "check-cast p0, $MENU_BUILDER\n" +
        "invoke-virtual { p0, p1, p2 }, $MENU_BUILDER->c($STRING$CALLBACK)$MENU_BUILDER\nreturn-void")
    writeStub(ROOT_MENU, "unit", 1, "sget-object v0, $UNIT->a:$UNIT\nreturn-object v0")
    writeStub(ROOT_MENU, "cardRoot", 3, """
        instance-of v0, p0, Lzve;
        if-eqz v0, :timeline
        check-cast p0, Lzve;
        invoke-virtual { p0 }, Lvgi;->l()$TIMELINEABLE
        move-result-object v0
        goto :payload
        :timeline
        instance-of v0, p0, $ROOT_TIMELINE_OBJECT
        if-eqz v0, :absent
        check-cast p0, $ROOT_TIMELINE_OBJECT
        invoke-virtual { p0 }, $ROOT_TIMELINE_OBJECT->getData()$TIMELINEABLE
        move-result-object v0
        :payload
        instance-of v1, v0, $HUSH_ROOT_POST
        if-eqz v1, :absent
        check-cast v0, $HUSH_ROOT_POST
        invoke-interface { v0 }, $HUSH_ROOT_POST->hushRootId()$STRING
        move-result-object v0
        return-object v0
        :absent
        const/4 v0, 0x0
        return-object v0
    """)
    plan.menu.addInstructionsAtControlFlowLabel(plan.at, """
        move-object/from16 v${plan.scratch[0]}, p7
        move-object/from16 v${plan.scratch[1]}, p0
        invoke-static { v11, v${plan.scratch[0]}, v10, v${plan.scratch[1]} }, $ROOT_MENU_HOOK
    """)
}

private fun BytecodePatchContext.rootGetter(key: String, result: String): String {
    val adapterType = POST.removeSuffix(";") + "JsonAdapter;"
    val adapter = classDefByOrNull(adapterType)?.methods?.filter {
        it.name == "toJson" && it.hasShape(listOf("Ldca;", "Ljava/lang/Object;"), "V")
    }.orEmpty().only(PATCH, "Moshi Post writer").instructionList()
    val at = adapter.indices.filter { adapter[it].stringRef() == key }.only(PATCH, "Moshi $key binding")
    val call = adapter.drop(at + 1).takeWhile { it.stringRef() == null }.firstNotNullOfOrNull {
        it.methodRef()?.takeIf { method -> it.opcode == Opcode.INVOKE_VIRTUAL && method.definingClass == POST }
    }
    requireShape(PATCH, call != null && call.hasShape(emptyList(), result), "the canonical $key getter changed")
    val getter = rootMethod(POST, call!!.name, emptyList(), result)
    requireDirectRootGetter(getter, result)
    val field = getter.instructionList().first().fieldRef()!!
    requireShape(PATCH, classDefByOrNull(POST)?.fields?.any {
        it.name == field.name && it.type == field.type && AccessFlags.FINAL.isSet(it.accessFlags)
    } == true, "a canonical root field is no longer immutable")
    return getter.toString()
}

private fun requireDirectRootGetter(getter: MutableMethod, result: String) {
    val body = getter.instructionList()
    val registers = getter.implementation!!.registerCount
    requireShape(PATCH, !getter.isStatic() && AccessFlags.PUBLIC.isSet(getter.accessFlags)
        && registers in 1..2 && body.map { it.opcode } == listOf(Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT)
        && body[0].fieldRef()?.type == result && body[0].fieldRef()?.definingClass == getter.definingClass
        && (body[0] as TwoRegisterInstruction).let { it.registerA == 0 && it.registerB == registers - 1 }
        && (body[1] as OneRegisterInstruction).registerA == 0, "a canonical ID getter is no longer a direct immutable model read")
}

private fun BytecodePatchContext.requireLocalTimeline() {
    val bridge = classDefByOrNull(HUSH_LOCAL_TIMELINE)
    requireShape(PATCH, bridge != null && AccessFlags.INTERFACE.isSet(bridge.accessFlags) && bridge.methods.count() == 2
        && bridge.methods.any { it.name == "hushTimelineItems" && it.hasShape(emptyList(), LIST) }
        && bridge.methods.any { it.name == "hushApplyTimelineItems" && it.hasShape(listOf(LIST), "V") },
        "the local timeline bridge changed")
    val owner = classDefByOrNull(LOCAL_TIMELINE)
    requireShape(PATCH, owner != null && HUSH_LOCAL_TIMELINE !in owner.interfaces
        && owner.methods.none { it.name in setOf("hushTimelineItems", "hushApplyTimelineItems") }
        && owner.fields.any { it.name == "z" && it.type == LIST && AccessFlags.PROTECTED.isSet(it.accessFlags) },
        "the displayed timeline backing list changed")
    val apply = rootMethod(LOCAL_TIMELINE, "i6", listOf(MODE, LIST), "V")
    val body = apply.instructionList()
    requireShape(PATCH, !apply.isStatic() && AccessFlags.PROTECTED.isSet(apply.accessFlags)
        && apply.implementation!!.registerCount == 6 && body.size == 60
        && body.drop(51).map { it.opcode } == listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_NEZ,
            Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID)
        && body[51].methodRef().toString() == "$MODE->f()Z"
        && (body[51] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == 4 }
        && (body[52] as OneRegisterInstruction).registerA == 0 && (body[53] as OneRegisterInstruction).registerA == 0
        && (body[53] as OffsetInstruction).codeOffset == 7
        && listOf(54, 56).all { index -> body[index].fieldRef().toString() == "$LOCAL_TIMELINE->z:$LIST"
            && (body[index] as TwoRegisterInstruction).let { it.registerA == 0 && it.registerB == 3 } }
        && body[55].methodRef().toString() == "$LIST->clear()V"
        && (body[55] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == 0 }
        && body[57].methodRef().toString() == "$LIST->addAll(Ljava/util/Collection;)Z"
        && (body[57] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerC == 0 && it.registerD == 5 }
        && body[58].methodRef().toString() == "$LOCAL_TIMELINE->X6($MODE$LIST)V"
        && (body[58] as FiveRegisterInstruction).let { it.registerCount == 3 && it.registerC == 3 && it.registerD == 4 && it.registerE == 5 },
        "the local cache rebind no longer replaces its own displayed list")
    val mode = rootMethod(MODE, "f", emptyList(), "Z")
    val predicate = mode.instructionList()
    requireShape(PATCH, mode.implementation!!.registerCount == 2 && predicate.map { it.opcode } ==
        listOf(Opcode.SGET_OBJECT, Opcode.IF_NE, Opcode.CONST_4, Opcode.RETURN, Opcode.CONST_4, Opcode.RETURN)
        && predicate[0].fieldRef().toString() == "$MODE->PAGINATION:$MODE"
        && (predicate[0] as OneRegisterInstruction).registerA == 0
        && (predicate[1] as TwoRegisterInstruction).let { it.registerA == 1 && it.registerB == 0 }
        && (predicate[1] as OffsetInstruction).codeOffset == 4
        && (predicate[2] as NarrowLiteralInstruction).narrowLiteral == 1 && (predicate[4] as NarrowLiteralInstruction).narrowLiteral == 0
        && listOf(2, 3, 4, 5).all { (predicate[it] as OneRegisterInstruction).registerA == 1 },
        "the cache mode no longer means replacement rather than pagination")
    val init = rootMethod(MODE, "<clinit>", emptyList(), "V").instructionList()
    for (name in listOf("FROM_CACHE", "PAGINATION")) requireShape(PATCH, init.windowed(5).count { group ->
        group[0].opcode == Opcode.NEW_INSTANCE && group[1].stringRef() == name && group[3].opcode == Opcode.INVOKE_DIRECT
            && group[3].methodRef().toString() == "$MODE-><init>($STRING" + "I)V"
            && group[4].opcode == Opcode.SPUT_OBJECT && group[4].fieldRef().toString() == "$MODE->$name:$MODE"
    } == 1, "the cache and pagination enum values are no longer independently constructed")
    val render = rootMethod(LOCAL_TIMELINE, "X6", listOf(MODE, LIST), "V").instructionList()
    requireShape(PATCH, render.size == 46 && render.any { it.methodRef().toString() == "$LOCAL_TIMELINE->Y6($LIST$MODE$LIST${LIST}II)V" }
        && render.any { it.methodRef().toString() == "$LOCAL_TIMELINE->e6()V" }
        && render.mapNotNull { it.methodRef()?.definingClass }.all { it in setOf(LOCAL_TIMELINE, "Ljava/util/Collection;",
            "Ljava/util/Queue;", "Ljava/lang/Integer;", "Lcom/google/common/collect/ImmutableList;") },
        "the local rebind enters an unknown loader or renderer")
}

private fun BytecodePatchContext.rootMethod(type: String, name: String, parameters: List<String>, result: String): MutableMethod =
    classDefByOrNull(type)?.methods?.filter { it.name == name && it.hasShape(parameters, result) && it.implementation != null }
        .orEmpty().only(PATCH, "$type->$name(${parameters.joinToString("")})$result").let { mutableOf(it) }
