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
 * Turns on the extension's video post rule. A post with a video block of any sort (Tumblr's own,
 * YouTube or another) in its own content or its reblog trail is a video post, and the timeline hook
 * takes it out of a page before Tumblr shows it. The rule is in `PostRules`.
 */
@Suppress("unused")
val hideVideoPostsPatch = bytecodePatch(
    name = "Hide video posts",
    description = "Takes video posts out of your dashboard and other feeds as each page loads. A post with a" +
        " video anywhere in it, its reblog trail included, counts as a video post. Starts off. Turn" +
        " it on in Tumblr settings > HushTumblr > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, tumblrExtensionPatch, timelineListHookPatch, postBridgePatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideVideoPosts")
        requireStatusMethod("videoPosts")
        enableCapability("videoPosts")
        enableStatus("hideVideoPosts")
    }
}
