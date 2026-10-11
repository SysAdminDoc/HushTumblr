/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.flags.featureSwitchHookPatch
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

/**
 * Turns on the extension's claim on Tumblr's screenshot flag. With it answering off, Tumblr doesn't
 * sign up for screenshot callbacks or watch your screenshots, so it never offers to save the post
 * as an image. The feature switch hook answers off while the switch is on.
 */
@Suppress("unused")
val turnOffScreenshotSharingPatch = bytecodePatch(
    name = "Turn off screenshot sharing",
    description = "Stops Tumblr watching for screenshots, so taking one doesn't bring up its offer to save the" +
        " post as an image. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("turnOffScreenshotSharing")
        requireStatusMethod("screenshotSharingFlags")
        enableCapability("screenshotSharingFlags")
        enableStatus("turnOffScreenshotSharing")
    }
}
