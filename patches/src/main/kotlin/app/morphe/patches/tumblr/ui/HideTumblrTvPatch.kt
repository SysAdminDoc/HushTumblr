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
 * Turns on the extension's claims on TUMBLR_TV_MOBILE_NAV, which RootActivity reads when it lists
 * the bottom bar's tabs, and TUMBLR_TV_ONBOARDING_REDIRECT, which sends a new account to Tumblr TV
 * after sign-up. The feature switch hook then answers off for both while the switch is on.
 */
@Suppress("unused")
val hideTumblrTvPatch = bytecodePatch(
    name = "Hide Tumblr TV",
    description = "Takes the Tumblr TV tab out of the bottom bar, and stops sign-up from sending you there." +
        " Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideTumblrTv")
        requireStatusMethod("tumblrTvFlags")
        enableCapability("tumblrTvFlags")
        enableStatus("hideTumblrTv")
    }
}
