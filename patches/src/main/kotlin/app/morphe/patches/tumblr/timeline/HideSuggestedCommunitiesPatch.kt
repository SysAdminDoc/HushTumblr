/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.timeline

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

/**
 * Turns on the extension's suggested communities rule. The timeline hook takes each community card
 * and row, and carousels of them, out of a page before Tumblr shows it, with the heading in front
 * of it. A community's own header card stays. The kinds are in `TimelineFilter`.
 */
@Suppress("unused")
val hideSuggestedCommunitiesPatch = bytecodePatch(
    name = "Hide suggested communities",
    description = "Takes the communities Tumblr suggests you join out of your dashboard and other feeds, along with" +
        " their headings. Starts off. Turn it on in Tumblr settings > HushTumblr > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, tumblrExtensionPatch, timelineListHookPatch, timelineCarouselPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideSuggestedCommunities")
        requireStatusMethod("suggestedCommunityCards")
        enableCapability("suggestedCommunityCards")
        enableStatus("hideSuggestedCommunities")
    }
}
