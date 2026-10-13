/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val WHAT = "Bottom bar tabs"
internal const val BOTTOM_BAR_TABS = "$EXTENSION_PACKAGE/ui/BottomBarTabs;"
internal const val ARRANGE_TABS_HOOK = "$BOTTOM_BAR_TABS->arrangeTabs(Ljava/util/List;)V"
internal const val ARRANGE_BAR_HOOK = "$BOTTOM_BAR_TABS->arrangeBar(Landroid/view/ViewGroup;)V"

/** The first and last tabs Tumblr's tab list always adds, by their kept enum constant names. */
private const val FIRST_TAB = "Dashboard"
private const val LAST_TAB = "Account"

private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val COLLECTION = "Ljava/util/Collection;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"

/**
 * Hands Tumblr's tab list and its bottom bar to `BottomBarTabs`, for Hide the Explore tab, Hide
 * the Notifications tab, Hide the Communities tab and Reorder the bottom bar. Each of them depends
 * on this and turns on only its own switch and target, so the hooks do nothing for a patch that
 * isn't in the build.
 */
internal val bottomBarTabsHookPatch = bytecodePatch {
    dependsOn(tumblrExtensionPatch)

    execute {
        // Both found and checked before either edit, so a refusal leaves the APK as it was.
        val hooks = resolveBottomBarTabs()
        hooks.tabList.addInstructions(hooks.tabListIndex, "invoke-static {v${hooks.tabListRegister}}, $ARRANGE_TABS_HOOK")
        hooks.bar.addInstructions(
            hooks.barIndex,
            "invoke-static/range { v${hooks.barRegister} .. v${hooks.barRegister} }, $ARRANGE_BAR_HOOK",
        )
    }
}

/** Where the two calls go: each method, the index the call goes in at, and the register it hands over. */
internal class BottomBarTabsHooks(
    val tabList: MutableMethod,
    val tabListIndex: Int,
    val tabListRegister: Int,
    val bar: MutableMethod,
    val barIndex: Int,
    val barRegister: Int,
)

/**
 * Finds Tumblr's tab list builder and the place its bottom bar's controller is built, throwing when
 * either is missing, doubled or shaped differently.
 *
 * The builder is the one static `()List` method that makes an `ArrayList`, adds the tab enum's
 * kept `Dashboard` and `Account` constants to it, and hands it to a static `(Collection)` call whose
 * answer it returns (Tumblr 46.5.0.109: `RootActivity`'s tab config `d()`, Guava's
 * `ImmutableList.copyOf` at 24). The call goes in right before that copy, on the `ArrayList`, which
 * nothing branches to.
 *
 * The controller is the class that looks up the Dashboard tab's unread badge (`BottomBar`'s anchor
 * too). Exactly one app method builds it with a `ViewGroup` first, and the call goes in right after
 * that constructor, on the same `ViewGroup` (`RootFragment.Y5`, index 26). Neither shape can be an
 * Object method. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
internal fun BytecodePatchContext.resolveBottomBarTabs(): BottomBarTabsHooks {
    requireExtensionHook(WHAT, ARRANGE_TABS_HOOK)
    requireExtensionHook(WHAT, ARRANGE_BAR_HOOK)
    val builders = mutableListOf<Method>()
    val controllers = mutableListOf<ClassDef>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.forEach { method -> if (method.buildsTabList()) builders += method }
        if (owner.methods.any { method -> method.instructionList().any { it.isDashboardBadge() } }) controllers += owner
    }

    val builder = builders.only(WHAT, "Tumblr's tab list builder")
    val builderBody = builder.instructionList()
    val copy = builderBody.size - 3
    val tabListRegister = builderBody[copy].namedRegisters().single()
    requireShape(WHAT, tabListRegister <= 15, "the tab list is in v$tabListRegister, past what the added call can name")
    requireShape(WHAT, !builder.isJumpTarget(copy), "something in the tab list builder jumps past the hook")

    val controller = controllers.distinctBy { it.type }.only(WHAT, "the bottom bar that looks up the dashboard's unread badge")
    val makers = mutableListOf<Pair<Method, Int>>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT) || owner.type == controller.type) return@classDefForEach
        owner.methods.forEach { method ->
            method.instructionList().forEachIndexed { index, instruction ->
                if (instruction.makesBar(controller.type)) makers += method to index
            }
        }
    }
    val (maker, made) = makers.only(WHAT, "the place the bottom bar's controller is built")
    val makerBody = maker.instructionList()
    val viewGroup = makerBody[made].namedRegisters().getOrNull(1)
        ?: throw PatchException("$WHAT: the bottom bar's controller is built without its view")
    requireShape(WHAT, made + 1 < makerBody.size && !maker.isJumpTarget(made + 1),
        "something jumps past the bottom bar's construction")

    return BottomBarTabsHooks(
        tabList = mutableOf(builder),
        tabListIndex = copy,
        tabListRegister = tabListRegister,
        bar = mutableOf(maker),
        barIndex = made + 1,
        barRegister = viewGroup,
    )
}

/** A static `()List` that adds the Dashboard and Account tabs to a new ArrayList and returns a frozen copy. */
private fun Method.buildsTabList(): Boolean {
    if (!isStatic() || !hasShape(emptyList(), LIST)) return false
    val body = instructionList()
    if (body.size < 4) return false
    val made = body.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
        ?.let { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
    if (made != ARRAY_LIST) return false
    val tabs = body.mapNotNull { instruction -> instruction.fieldRef()?.takeIf { instruction.opcode == Opcode.SGET_OBJECT } }
    val first = tabs.firstOrNull { it.name == FIRST_TAB } ?: return false
    if (first.type != first.definingClass || tabs.none { it.name == LAST_TAB && it.type == first.type }) return false
    val copy = body[body.size - 3]
    val result = body[body.size - 2]
    val back = body[body.size - 1]
    return copy.opcode == Opcode.INVOKE_STATIC && copy.methodRef()?.parameterTypes?.map { it.toString() } == listOf(COLLECTION) &&
        result.opcode == Opcode.MOVE_RESULT_OBJECT && back.opcode == Opcode.RETURN_OBJECT
}

/** A constructor call of [controller] whose first parameter is a ViewGroup. */
private fun Instruction.makesBar(controller: String): Boolean =
    (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) && methodRef()?.let { call ->
        call.definingClass == controller && call.name == "<init>" && call.parameterTypes.firstOrNull()?.toString() == VIEW_GROUP
    } == true

/** Whether a branch or a switch case lands on the instruction at [index]. */
internal fun Method.isJumpTarget(index: Int): Boolean {
    val body = instructionList()
    val addresses = body.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val indexAt = addresses.withIndex().associate { (at, address) -> address to at }
    val wanted = addresses[index]
    return body.withIndex().any { (at, instruction) ->
        if (instruction !is OffsetInstruction) return@any false
        val landing = addresses[at] + instruction.codeOffset
        when (instruction.opcode) {
            Opcode.PACKED_SWITCH, Opcode.SPARSE_SWITCH -> {
                val payload = indexAt[landing]?.let { body[it] } as? SwitchPayload
                payload?.switchElements?.any { addresses[at] + it.offset == wanted } == true
            }
            Opcode.FILL_ARRAY_DATA -> false
            else -> landing == wanted
        }
    }
}
