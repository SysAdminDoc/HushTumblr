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

private const val PATCH = "Hide the like button"
internal const val HIDE_LIKE_HOOK = "$POST_FOOTER->hideLikeButton()Z"
internal const val HIDE_COMMUNITY_REACTIONS_HOOK = "$POST_FOOTER->hideCommunityReactions(Landroid/view/ViewGroup;)V"

/**
 * Puts `PostFooter.hideLikeButton` first in the function that draws the footer's like button, the
 * heart that likes a post, and returns at once while the switch is on, so the footer is drawn
 * without it. Compose expects a footer to draw the same buttons each time, so the extension keeps
 * its first answer until Tumblr restarts.
 *
 * The function is the one whose source note starts with [FOOTER_CONTROLS] and [LIKE_CONTROL]. Every
 * footer drawn with Tumblr's split note counts calls it: the dashboard, blogs, search, Tumblr TV
 * and reblog notes. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 *
 * Community posts have no like button. Their reactions stand in for it, so the community footer's
 * bind ([resolveCommunityFooterBind]) ends with `PostFooter.hideCommunityReactions`, which hides
 * them and the button that adds one, with the same answer.
 */
@Suppress("unused")
val hideLikeButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the like button out of the footer under posts in your dashboard and other feeds, and" +
        " the reactions off community posts. Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideLikeButton")
        requireStatusMethod("likeButton")
        // Found and checked before any edit, so a refusal leaves the APK as it was.
        val button = resolveFooterButton(PATCH, LIKE_CONTROL, HIDE_LIKE_HOOK)
        val community = resolveCommunityFooterBind(PATCH, HIDE_COMMUNITY_REACTIONS_HOOK, COMMUNITY_REACTION_CONTROLS)
        button.returnEarlyWhen(PATCH, HIDE_LIKE_HOOK, "return-void")
        community.addHook(HIDE_COMMUNITY_REACTIONS_HOOK)
        enableCapability("likeButton")
        enableStatus("hideLikeButton")
    }
}
