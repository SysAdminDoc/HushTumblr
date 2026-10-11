/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.returnEarlyWhen
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

private const val PATCH = "Hide the comment button"
internal const val HIDE_COMMENT_HOOK = "$POST_FOOTER->hideCommentButton()Z"
internal const val HIDE_COMMUNITY_COMMENTS_HOOK = "$POST_FOOTER->hideCommunityComments(Landroid/view/ViewGroup;)V"

/**
 * Puts `PostFooter.hideCommentButton` first in the function that draws the footer's comment button,
 * the speech bubble that opens a post's comments, and returns at once while the switch is on, so
 * the footer is drawn without it. Compose expects a footer to draw the same buttons each time, so
 * the extension keeps its first answer until Tumblr restarts.
 *
 * The function is the one whose source note starts with [FOOTER_CONTROLS] and [COMMENT_CONTROL].
 * Every footer drawn with Tumblr's split note counts calls it: the dashboard, blogs, search, Tumblr
 * TV and reblog notes. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 *
 * Community posts draw their own footer with Views, so its bind ([resolveCommunityFooterBind]) ends
 * with `PostFooter.hideCommunityComments`, which hides its comment button with the same answer.
 */
@Suppress("unused")
val hideCommentButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the comment button out of the footer under posts in your dashboard and other feeds." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideCommentButton")
        requireStatusMethod("commentButton")
        // Found and checked before any edit, so a refusal leaves the APK as it was.
        val button = resolveFooterButton(PATCH, COMMENT_CONTROL, HIDE_COMMENT_HOOK)
        val community = resolveCommunityFooterBind(PATCH, HIDE_COMMUNITY_COMMENTS_HOOK, COMMUNITY_COMMENT_CONTROLS)
        button.returnEarlyWhen(PATCH, HIDE_COMMENT_HOOK, "return-void")
        community.addHook(HIDE_COMMUNITY_COMMENTS_HOOK)
        enableCapability("commentButton")
        enableStatus("hideCommentButton")
    }
}
