/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.appClassesWithString
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.sameAs
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.Method

private const val PATCH = "Hide gift purchase popups"
internal const val GIFT_POPUP_SEEN = "key_has_seen_ad_free_browsing_gift_popup"
internal const val CRAB_POPUP_SEEN = "key_has_seen_summon_crabs_popup"
internal const val GIFT_POPUP_HOOK = "$OPTIONAL_NUDGES->hideGiftMessage(Ljava/lang/String;)Z"
internal val GIFT_POPUP_PARAMETERS = listOf("Landroid/app/Activity;", "Landroid/view/View;", "F", "Landroid/view/View\$OnClickListener;")

internal class GiftPopupPlan(val renderer: MutableMethod, val keyGetter: Method)

/**
 * yu8.h in 46.5.0.109 renders both the unsolicited gift pitch and a crab tip. Its instance key
 * distinguishes them. Ask at rendering time, after any queued delay, so a switch changed while
 * a callback waits still applies. The two blog-header schedulers and deliberate purchase
 * launchers keep their own code. No entitlement or purchase operation is intercepted.
 */
@Suppress("unused")
val hideGiftMessagePopupsPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the pop-up suggesting you buy a gift for a blog. You can still open gifting yourself." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())
    execute {
        requireStatusMethod("hideGiftMessagePopups")
        requireStatusMethod("giftMessagePopup")
        val (renderer, getter) = resolveGiftPopup().let { it.renderer to it.keyGetter }
        val first = renderer.getInstruction(0)
        renderer.addInstructionsWithLabels(0, """
            invoke-virtual/range {p0 .. p0}, ${getter.definingClass}->${getter.name}()Ljava/lang/String;
            move-result-object v0
            invoke-static {v0}, $GIFT_POPUP_HOOK
            move-result v0
            if-eqz v0, :hush_keep
            return-void
        """, ExternalLabel("hush_keep", first))
        enableCapability("giftMessagePopup")
        enableStatus("hideGiftMessagePopups")
    }
}

internal fun BytecodePatchContext.resolveGiftPopup(): GiftPopupPlan {
    requireExtensionHook(PATCH, GIFT_POPUP_HOOK)
    val owner = appClassesWithString(GIFT_POPUP_SEEN).filter { candidate -> candidate.methods.any { method ->
        method.isStatic() && method.hasShape(listOf(candidate.type), "Ljava/lang/String;") &&
            setOf(GIFT_POPUP_SEEN, CRAB_POPUP_SEEN).all { it in method.strings() }
    } }.only(PATCH, "the popup with separate gift and crab keys")
    val renderer = owner.methods.filter { method ->
        !method.isStatic() && method.hasShape(GIFT_POPUP_PARAMETERS, "V") && "textMessage" in method.strings() &&
            setOf("popup_gift_message", "popup_gift_message_right").all { layout -> method.instructionList().any {
                it.fieldRef()?.let { field -> field.definingClass == "Lcom/tumblr/R\$layout;" && field.name == layout } == true
            } } && method.instructionList().any {
                it.fieldRef()?.let { field -> field.definingClass == "Lcom/tumblr/R\$id;" && field.name == "popup_text_message" } == true
            }
    }.only(PATCH, "the gift and crab popup renderer")
    val getter = owner.methods.filter { method -> !method.isStatic() && method.hasShape(emptyList(), "Ljava/lang/String;") &&
        renderer.instructionList().any { it.methodRef()?.sameAs(method) == true }
    }.only(PATCH, "the renderer's instance preference key")
    requireShape(PATCH, renderer.instructionList().any { it.methodRef()?.let { call ->
        call.definingClass == "Lcom/tumblr/Remember;" && call.name == "l" &&
            call.hasShape(listOf("Ljava/lang/String;", "Z"), "Lcom/tumblr/Remember;")
    } == true }, "the renderer doesn't record its own seen preference")
    return GiftPopupPlan(mutableOf(renderer).also { it.requireLocals(PATCH, 1) }, getter)
}
