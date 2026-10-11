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

private const val PATCH = "Hide the share button"
internal const val HIDE_SHARE_HOOK = "$POST_FOOTER->hideShareButton()Z"

/**
 * Puts `PostFooter.hideShareButton` first in the function that draws the footer's share button, the
 * button that opens Tumblr's share sheet, and returns at once while the switch is on, so the footer
 * is drawn without it. Compose expects a footer to draw the same buttons each time, so the
 * extension keeps its first answer until Tumblr restarts.
 *
 * The function is the one whose source note starts with [FOOTER_CONTROLS] and [SHARE_CONTROL].
 * Every footer drawn with Tumblr's split note counts calls it: the dashboard, blogs, search, Tumblr
 * TV and reblog notes. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideShareButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the share button out of the footer under posts in your dashboard and other feeds." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideShareButton")
        requireStatusMethod("shareButton")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val button = resolveFooterButton(PATCH, SHARE_CONTROL, HIDE_SHARE_HOOK)
        button.returnEarlyWhen(PATCH, HIDE_SHARE_HOOK, "return-void")
        enableCapability("shareButton")
        enableStatus("hideShareButton")
    }
}
