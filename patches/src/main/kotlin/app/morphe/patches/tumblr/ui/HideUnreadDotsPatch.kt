/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Hide unread dots"
internal const val BOTTOM_BAR = "$EXTENSION_PACKAGE/ui/BottomBar;"
internal const val UNREAD_BADGE_HOOK = "$BOTTOM_BAR->unreadBadge(Landroid/widget/TextView;)Landroid/widget/TextView;"

/** The dashboard tab's unread badge, a view id in [VIEW_IDS]. Resource names are kept. */
internal const val DASHBOARD_BADGE = "dashboard_badge"

/**
 * The three tabs' unread badges, view ids in [VIEW_IDS]. `BottomBar` tells them apart by these
 * names, one switch each, so the bar must look up every one of them.
 */
internal val UNREAD_BADGES = listOf(DASHBOARD_BADGE, "communities_badge", "notification_badge")

private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val ANIMATION = "Landroid/view/animation/Animation;"

/**
 * Hands the bottom bar's badge pop-in the badge `BottomBar.unreadBadge` answers, null while that
 * badge's switch is on (after it hides the badge). The pop-in's own first instruction returns when
 * it has no badge, so the dot never starts its animation, and that animation's start is the only
 * place Tumblr makes a badge visible. The Dashboard, Communities and Notifications badges all go
 * through it each time Tumblr's unread counts come in, so a switch shows with the next count. The
 * extension tells the badges apart by their view ids, [UNREAD_BADGES] by name.
 *
 * The bar is the one class that looks up [DASHBOARD_BADGE], and it must look up each of
 * [UNREAD_BADGES]. The pop-in is its one instance method taking a TextView and an Animation that
 * starts the animation only on a view that isn't already showing (its `getVisibility` answer is
 * tested with `if-eqz`); the pop-out has the same shape with `if-nez`. Its first instruction must
 * be the null check on the badge. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideUnreadDotsPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the unread dots and counts on the bottom bar's Dashboard, Communities and Notifications" +
        " tabs, with a switch for each tab. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideUnreadDots")
        requireStatusMethod("unreadDots")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val popIn = resolveBadgePopIn()
        val badge = popIn.parameterRegister(0)
        popIn.addInstructions(
            0,
            """
                invoke-static {$badge}, $UNREAD_BADGE_HOOK
                move-result-object $badge
            """,
        )
        enableCapability("unreadDots")
        enableStatus("hideUnreadDots")
    }
}

/**
 * The bottom bar's badge pop-in, throwing when this build has no bar or several, a bar that doesn't
 * look up each of [UNREAD_BADGES], no pop-in or several, or one that doesn't start with its own
 * null check on the badge.
 */
internal fun BytecodePatchContext.resolveBadgePopIn(): MutableMethod {
    requireExtensionHook(PATCH, UNREAD_BADGE_HOOK)
    val bars = mutableListOf<ClassDef>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (owner.methods.any { method -> method.instructionList().any { it.isDashboardBadge() } }) bars += owner
    }
    val bar = bars.distinctBy { it.type }.only(PATCH, "the bottom bar that looks up the dashboard's unread badge")
    val badges = bar.methods.flatMap { method -> method.instructionList().mapNotNull { it.viewIdRead() } }.toSet()
    val unknown = UNREAD_BADGES.filterNot { it in badges }
    requireShape(
        PATCH,
        unknown.isEmpty(),
        "the bottom bar doesn't look up the unread badges ${unknown.joinToString()} by those names",
    )
    val popIn = bar.methods
        .filter { method ->
            !method.isStatic() && method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(TEXT_VIEW, ANIMATION) && method.popsInHidden()
        }
        .only(PATCH, "the bottom bar's badge pop-in")
    val method = mutableOf(popIn)
    val first = method.instructionList().firstOrNull()
    val badge = method.parameterRegisterNumber(0)
    requireShape(
        PATCH,
        first?.opcode == Opcode.IF_EQZ && (first as OneRegisterInstruction).registerA == badge,
        "the bottom bar's badge pop-in doesn't start by checking it has a badge",
    )
    return method
}

internal fun Instruction.isDashboardBadge(): Boolean = viewIdRead() == DASHBOARD_BADGE

/** The name of the view id this reads, or null when it reads none. */
internal fun Instruction.viewIdRead(): String? =
    if (opcode == Opcode.SGET) fieldRef()?.takeIf { it.definingClass == VIEW_IDS }?.name else null

/** Whether the method starts an animation only when its view's visibility isn't VISIBLE (zero). */
private fun Method.popsInHidden(): Boolean {
    val body = instructionList()
    val asked = body.indexOfFirst { it.methodRef()?.name == "getVisibility" }
    val answer = body.getOrNull(asked + 1)
    val test = body.getOrNull(asked + 2)
    return asked >= 0 &&
        answer?.opcode == Opcode.MOVE_RESULT && test?.opcode == Opcode.IF_EQZ &&
        (answer as OneRegisterInstruction).registerA == (test as OneRegisterInstruction).registerA &&
        body.any { it.methodRef()?.name == "startAnimation" }
}
