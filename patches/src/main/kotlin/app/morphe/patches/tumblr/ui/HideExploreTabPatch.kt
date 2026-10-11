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
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

/**
 * Turns on the bottom bar hook's Explore switch: once the bar is built, `BottomBarTabs` sets the
 * Explore tab's button GONE. The tab stays in Tumblr's list, so links into it still work.
 */
@Suppress("unused")
val hideExploreTabPatch = bytecodePatch(
    name = "Hide the Explore tab",
    description = "Takes the Explore and search button off the bottom bar. Starts off. Turn it on in Tumblr settings >" +
        " HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch, bottomBarTabsHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideExploreTab")
        requireStatusMethod("exploreTab")
        enableCapability("exploreTab")
        enableStatus("hideExploreTab")
    }
}
