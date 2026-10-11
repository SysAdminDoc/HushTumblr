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

private const val PATCH = "Hide the reblog button"
internal const val HIDE_REBLOG_HOOK = "$POST_FOOTER->hideReblogButton()Z"

/**
 * Puts `PostFooter.hideReblogButton` first in the function that draws the footer's reblog button,
 * the button that opens Tumblr's reblog options, and returns at once while the switch is on, so the
 * footer is drawn without it. Compose expects a footer to draw the same buttons each time, so the
 * extension keeps its first answer until Tumblr restarts.
 *
 * The function is the one whose source note starts with [FOOTER_CONTROLS] and [REBLOG_CONTROL].
 * Every footer drawn with Tumblr's split note counts calls it: the dashboard, blogs, search, Tumblr
 * TV and reblog notes. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideReblogButtonPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the reblog button out of the footer under posts in your dashboard and other feeds." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideReblogButton")
        requireStatusMethod("reblogButton")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val button = resolveFooterButton(PATCH, REBLOG_CONTROL, HIDE_REBLOG_HOOK)
        button.returnEarlyWhen(PATCH, HIDE_REBLOG_HOOK, "return-void")
        enableCapability("reblogButton")
        enableStatus("hideReblogButton")
    }
}
