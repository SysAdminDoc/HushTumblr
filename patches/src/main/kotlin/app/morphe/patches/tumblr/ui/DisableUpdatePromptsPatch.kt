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
 * Turns on the extension's claim on Tumblr's in-app update flag. Tumblr's update manager reads it
 * before asking Google Play whether a newer Tumblr is out, so with it answering off Tumblr never
 * asks and never opens Play's update prompt. The feature switch hook answers off while the switch
 * is on.
 */
@Suppress("unused")
val disableUpdatePromptsPatch = bytecodePatch(
    name = "Disable update prompts",
    description = "Stops Tumblr asking Google Play for a newer version and showing Play's update prompt. Starts" +
        " off. Turn it on in Tumblr settings > HushTumblr > More settings > Updates.",
    default = true,
) {
    category("Updates")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("disableUpdatePrompts")
        requireStatusMethod("updatePromptFlags")
        enableCapability("updatePromptFlags")
        enableStatus("disableUpdatePrompts")
    }
}
