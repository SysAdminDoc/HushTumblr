/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ads

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.flags.featureSwitchHookPatch
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

/**
 * Turns on the extension's claim on AD_FREE_CTA_BANNER, the flag Tumblr's tabbed dashboard reads
 * before it shows the banner selling Ad-Free Browsing. The feature switch hook then answers off for
 * it while the switch is on.
 */
@Suppress("unused")
val hideAdFreeBannerPatch = bytecodePatch(
    name = "Hide the ad-free banner",
    description = "Hides the banner on your dashboard that asks you to pay for Ad-Free Browsing. Starts off." +
        " Turn it on in Tumblr settings > HushTumblr > Feed.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideAdFreeBanner")
        requireStatusMethod("adFreeBannerFlags")
        enableCapability("adFreeBannerFlags")
        enableStatus("hideAdFreeBanner")
    }
}
