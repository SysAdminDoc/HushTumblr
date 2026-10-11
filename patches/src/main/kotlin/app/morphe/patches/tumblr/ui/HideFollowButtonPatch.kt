/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
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
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.returnEarlyWhen
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode

private const val PATCH = "Hide the Follow button"
internal const val HIDE_FOLLOW_HOOK = "$POST_PARTS->hideFollowButton()Z"

/** A post card's header. A view named in Tumblr's layouts, so R8 keeps its name. */
internal const val POST_CARD_HEADER = "Lcom/tumblr/ui/widget/PostCardHeader;"

/** The Follow action a server-sent header button can carry. An enum constant, read by name from JSON. */
internal const val FOLLOW_ACTION = "Lcom/tumblr/rumblr/model/post/PostHeaderAction\$ActionType;"

/**
 * Puts `PostParts.hideFollowButton` first in the header's check for whether its Follow button
 * stays hidden, and answers yes at once while the switch is on. Tumblr asks it when it binds the
 * header, where yes hides the Follow button (and its twin on blazed posts with a follow goal), and
 * for a server-sent Follow action in the header, where yes leaves the action out. Tumblr answers yes
 * itself for your own posts and blogs you already follow, so this is the path those take. The
 * switch is read each time a header is bound, so it works without a restart.
 *
 * The check is the one method of [POST_CARD_HEADER] that the header's Follow action test calls:
 * that test is the one instance method of the header returning a boolean that reads the FOLLOW
 * constant of [FOLLOW_ACTION], and the check is its one direct call to a header method taking two
 * parameters and returning a boolean. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideFollowButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the Follow button out of the header of posts from blogs you don't follow yet." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideFollowButton")
        requireStatusMethod("followButton")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val check = resolveFollowButtonCheck()
        check.returnEarlyWhen(PATCH, HIDE_FOLLOW_HOOK, "const/4 v0, 0x1\nreturn v0")
        enableCapability("followButton")
        enableStatus("hideFollowButton")
    }
}

/** The header's Follow button check, throwing when this build has none, several, or one with no local. */
internal fun BytecodePatchContext.resolveFollowButtonCheck(): MutableMethod {
    requireExtensionHook(PATCH, HIDE_FOLLOW_HOOK)
    val header = classDefByOrNull(POST_CARD_HEADER)
        ?: throw PatchException("$PATCH: Tumblr's $POST_CARD_HEADER wasn't found")
    val actionTest = header.methods
        .filter { method ->
            !method.isStatic() && method.returnType == "Z" &&
                method.instructionList().any {
                    it.opcode == Opcode.SGET_OBJECT && it.fieldRef()?.let { field ->
                        field.definingClass == FOLLOW_ACTION && field.name == "FOLLOW"
                    } == true
                }
        }
        .only(PATCH, "the header's Follow action test")
    val check = actionTest.instructionList()
        .filter { it.opcode == Opcode.INVOKE_DIRECT }
        .mapNotNull { it.methodRef() }
        .filter { it.definingClass == POST_CARD_HEADER && it.returnType == "Z" && it.parameterTypes.size == 2 }
        .distinctBy { "${it.name}(${it.parameterTypes.joinToString("")})" }
        .only(PATCH, "the header's Follow button check")
    return mutableOf(check).also { it.requireLocals(PATCH, 1) }
}
