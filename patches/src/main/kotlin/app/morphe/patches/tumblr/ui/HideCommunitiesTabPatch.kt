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
 * Turns on the extension's claims on COMMUNITIES_TAB_BAR_ITEM, which RootActivity reads when it
 * lists the bottom bar's tabs, and COMMUNITIES_TOOLTIPS, which the post editor reads before it
 * shows its Communities tips. The feature switch hook then answers off for both while the switch is
 * on.
 */
@Suppress("unused")
val hideCommunitiesTabPatch = bytecodePatch(
    name = "Hide the Communities tab",
    description = "Takes the Communities tab out of the bottom bar and stops the Communities tips in the post" +
        " editor. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideCommunitiesTab")
        requireStatusMethod("communitiesTabFlags")
        enableCapability("communitiesTabFlags")
        enableStatus("hideCommunitiesTab")
    }
}
