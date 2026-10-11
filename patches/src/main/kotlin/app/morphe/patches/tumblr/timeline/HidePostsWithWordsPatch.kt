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
 * Turns on the extension's word list rule. The timeline hook takes a post out of a page before
 * Tumblr shows it when its text blocks, its reblog trail's text blocks, its tags or its summary hold
 * a word or phrase from the list on the Feed page, ignoring case, as whole words. The rule is in
 * `PostRules`.
 */
@Suppress("unused")
val hidePostsWithWordsPatch = bytecodePatch(
    name = "Hide posts with these words",
    description = "Takes posts out of your dashboard and other feeds when their text, reblog trail, tags or" +
        " summary has a word or phrase from your list, ignoring case and matching whole words. Starts" +
        " off. Turn it on in Tumblr settings > HushTumblr > Feed.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, tumblrExtensionPatch, timelineListHookPatch, postBridgePatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hidePostsWithWords")
        requireStatusMethod("postWords")
        enableCapability("postWords")
        enableStatus("hidePostsWithWords")
    }
}
