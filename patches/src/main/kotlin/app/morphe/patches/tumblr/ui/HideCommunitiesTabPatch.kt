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
 * Turns on the bottom bar hook's Communities switch and the extension's claim on
 * COMMUNITIES_TOOLTIPS. Once the bar is built, `BottomBarTabs` sets the Communities tab's button
 * GONE, and the feature switch hook answers off for the flag the post editor reads before it shows
 * its Communities tips.
 *
 * The tab stays in Tumblr's list, and its own flag, COMMUNITIES_TAB_BAR_ITEM, stays Tumblr's. With
 * that flag off RootActivity leaves the tab out of its list, and the bar's controller then never
 * runs the one method that decides the tab's button. Tumblr 46.5.0.109's layout showed the button
 * unless that method hid it, so it stayed on the bar with nothing behind it (2026-10-13); 46.8.1.100
 * starts it gone, and hiding it after the controller runs holds for both (2026-10-22). Off, the
 * flag also leaves the tab's badge unread while the unread counts are still written to it.
 */
@Suppress("unused")
val hideCommunitiesTabPatch = bytecodePatch(
    name = "Hide the Communities tab",
    description = "Takes the Communities tab out of the bottom bar and stops the Communities tips in the post" +
        " editor. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch, featureSwitchHookPatch, bottomBarTabsHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideCommunitiesTab")
        requireStatusMethod("communitiesTabFlags")
        requireStatusMethod("communitiesTabButton")
        enableCapability("communitiesTabFlags")
        enableCapability("communitiesTabButton")
        enableStatus("hideCommunitiesTab")
    }
}
