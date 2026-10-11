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
 * Turns on the bottom bar hook's order: `BottomBarTabs` puts Tumblr's tab list in the saved order
 * before Tumblr freezes it, and the bar's buttons in the same order once it's built.
 */
@Suppress("unused")
val reorderBottomBarPatch = bytecodePatch(
    name = "Reorder the bottom bar",
    description = "Puts the bottom bar's tabs in an order you pick, from a list in its settings. Starts off. Turn it on in" +
        " Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Navigation")
    dependsOn(settingsPatch, tumblrExtensionPatch, bottomBarTabsHookPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("reorderTabs")
        requireStatusMethod("tabOrder")
        enableCapability("tabOrder")
        enableStatus("reorderTabs")
    }
}
