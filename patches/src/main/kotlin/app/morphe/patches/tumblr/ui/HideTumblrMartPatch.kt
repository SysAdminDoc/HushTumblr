/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.appClassesWithString
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.namedRegisters
import app.morphe.util.writesRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Hide the diamond and TumblrMart"
internal const val STORE_BUTTONS = "$EXTENSION_PACKAGE/ui/StoreButtons;"
internal const val HIDE_DIAMOND_HOOK = "$STORE_BUTTONS->hideDiamond(Landroid/view/View;Landroid/view/View;)Z"
internal const val HIDE_TUMBLRMART_HOOK = "$STORE_BUTTONS->hideTumblrMart(Landroid/view/View;)V"

/** The preference the diamond's drawer reads to pick its icon. Only the diamond's class names it. */
internal const val DIAMOND_CLICKED = "arg_has_clicked_on_premium_diamond"

internal const val DRAWEE = "Lcom/facebook/drawee/view/SimpleDraweeView;"
internal const val IMAGE_VIEW = "Landroid/widget/ImageView;"

/** Tumblr's view IDs. R8 keeps the field names, and the patch reads them. */
internal const val VIEW_IDS = "Lcom/tumblr/R\$id;"

/** The TumblrMart button's view ID on the dashboard's top bar, and on Explore and notifications. */
internal val TUMBLRMART_IDS = setOf("tumblrmart_button_top_bar", "tumblr_mart_icon")

/** One place Tumblr shows a TumblrMart button: the hook goes in at [at], with the button in [register]. */
internal class TumblrMartSite(val method: MutableMethod, val at: Int, val register: Int)

/**
 * Keeps the Premium diamond and the TumblrMart button out of Tumblr's top bars while the switch is
 * on. Two hooks, each its own capability.
 *
 * The diamond: one method draws it and its credit dot for the dashboard, Explore and
 * notifications, the only instance method taking (SimpleDraweeView, ImageView, ...) and returning
 * void in the one class whose methods load [DIAMOND_CLICKED]. `StoreButtons.hideDiamond` goes
 * first, gets the two views, hides them and answers true, and the method returns before it sets
 * up the icon or its tap. When Tumblr's own premium flag is off it hides the diamond itself.
 *
 * The TumblrMart button: every method that looks a [TUMBLRMART_IDS] view up with `findViewById`
 * and then sets that view's visibility gets `StoreButtons.hideTumblrMart` right after the
 * visibility call. In 46.5.0.109 that's the dashboard host fragment's top bar setup, the
 * notifications fragment's `onViewCreated` and the Explore header's constructor. Each insertion
 * point is reached only from the visibility call, and the button's register isn't reused between
 * the lookup and the call; a reader that doesn't fit refuses the patch rather than missing a
 * screen. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideTumblrMartPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the Premium diamond and the TumblrMart button at the top of your dashboard, Explore" +
        " and notifications. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideTumblrMart")
        requireStatusMethod("premiumDiamond")
        requireStatusMethod("tumblrMartButtons")
        // Found and checked before any edit, so a refusal leaves the APK as it was.
        val diamond = resolveDiamondDrawer()
        val sites = resolveTumblrMartSites()
        diamond.hideDiamondWhenAsked()
        // Last first in each method, so an earlier site's index still holds.
        for (site in sites.sortedByDescending { it.at }) {
            val button = "v${site.register}"
            site.method.addInstruction(site.at, "invoke-static/range {$button .. $button}, $HIDE_TUMBLRMART_HOOK")
        }
        enableCapability("premiumDiamond")
        enableCapability("tumblrMartButtons")
        enableStatus("hideTumblrMart")
    }
}

/** The diamond's drawer, throwing when this build has none, several, or one with no local. */
internal fun BytecodePatchContext.resolveDiamondDrawer(): MutableMethod {
    requireExtensionHook(PATCH, HIDE_DIAMOND_HOOK)
    val drawer = appClassesWithString(DIAMOND_CLICKED)
        .flatMap { it.methods }
        .filter { method ->
            !method.isStatic() && method.returnType == "V" && method.parameterTypes.size >= 2 &&
                method.parameterTypes[0].toString() == DRAWEE && method.parameterTypes[1].toString() == IMAGE_VIEW &&
                DIAMOND_CLICKED in method.strings()
        }
        .only(PATCH, "the method that draws the Premium diamond")
    return mutableOf(drawer).also { it.requireLocals(PATCH, 1) }
}

/**
 * Every place Tumblr looks up a TumblrMart button and sets its visibility, throwing when there's
 * none or when one of them doesn't have the shape the hook needs.
 */
internal fun BytecodePatchContext.resolveTumblrMartSites(): List<TumblrMartSite> {
    requireExtensionHook(PATCH, HIDE_TUMBLRMART_HOOK)
    val readers = mutableListOf<Method>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.filterTo(readers) { method -> method.instructionList().any { it.isTumblrMartId() } }
    }
    if (readers.isEmpty()) throw PatchException("$PATCH: nothing in Tumblr looks up a TumblrMart button")
    return readers.flatMap { reader ->
        val method = mutableOf(reader)
        method.tumblrMartPlaces().map { (at, register) -> TumblrMartSite(method, at, register) }
    }
}

/** Where each TumblrMart lookup in this method gets its hook, and the button's register there. */
private fun Method.tumblrMartPlaces(): List<Pair<Int, Int>> {
    val body = instructionList()
    val flow = ControlFlow.of(this)
    val where = "$definingClass->$name"
    return body.indices.filter { body[it].isTumblrMartId() }.map { id ->
        val result = body.getOrNull(id + 2)
        requireShape(PATCH, body.getOrNull(id + 1)?.methodRef()?.name == "findViewById" &&
            result?.opcode == Opcode.MOVE_RESULT_OBJECT, "$where doesn't look the TumblrMart button up right after naming it")
        val button = (result as OneRegisterInstruction).registerA
        val shown = (id + 3 until body.size).firstOrNull { body[it].setsVisibilityOf(button) }
        requireShape(PATCH, shown != null, "$where never sets the TumblrMart button's visibility")
        requireShape(PATCH, (id + 3 until shown!!).none { writesRegister(body[it], button) },
            "$where reuses the TumblrMart button's register before setting its visibility")
        val at = shown + 1
        requireShape(PATCH, at < body.size && at in flow.normal[shown] &&
            flow.normal.indices.none { from -> from != shown && at in flow.normal[from] } &&
            flow.exceptional.none { at in it },
            "$where reaches the instruction after the TumblrMart button's visibility another way")
        at to button
    }
}

/** An `sget` of one of the TumblrMart button's view IDs. */
private fun Instruction.isTumblrMartId(): Boolean =
    opcode == Opcode.SGET && fieldRef()?.let { it.definingClass == VIEW_IDS && it.name in TUMBLRMART_IDS } == true

/** A `View.setVisibility(int)` call on the view in [register]. */
private fun Instruction.setsVisibilityOf(register: Int): Boolean =
    opcode in setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE) &&
        methodRef()?.let { it.name == "setVisibility" && it.parameterTypes.map(CharSequence::toString) == listOf("I") } == true &&
        namedRegisters().firstOrNull() == register

/**
 * Puts the question in front of the drawer's first instruction with its two views: on true the
 * drawer returns at once, the views already hidden, and on false it runs as it always did.
 * Borrows v0, which holds nothing before the method's own first instruction.
 */
private fun MutableMethod.hideDiamondWhenAsked() {
    val diamond = parameterRegister(0)
    val dot = parameterRegister(1)
    val first = getInstruction(0)
    addInstructionsWithLabels(
        0,
        """
            invoke-static/range {$diamond .. $dot}, $HIDE_DIAMOND_HOOK
            move-result v0
            if-eqz v0, :hush_keep
            return-void
        """,
        ExternalLabel("hush_keep", first),
    )
}
