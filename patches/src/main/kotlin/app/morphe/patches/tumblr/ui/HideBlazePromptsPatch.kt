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
 * Turns on the extension's claims on Tumblr's Blaze prompt flags: the Blaze dashboard entry points
 * in your blog's header and beside Premium, the nudge after you post, and the settings and landing
 * page banners. The feature switch hook then answers off for each while the switch is on.
 */
@Suppress("unused")
val hideBlazePromptsPatch = bytecodePatch(
    name = "Hide Blaze prompts",
    description = "Hides the prompts to pay for Blaze, Tumblr's way to promote a post. That covers the Blaze" +
        " buttons on your blog and dashboard, the nudge after you post, and the Blaze banners. Starts" +
        " off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideBlazePrompts")
        requireStatusMethod("blazePromptFlags")
        enableCapability("blazePromptFlags")
        enableStatus("hideBlazePrompts")
    }
}
