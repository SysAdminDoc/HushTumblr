/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import android.content.Context;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.tumblr.timeline.HushRootPost;

/** Appends one local action to the native menu after proving a current local timeline host. */
public final class RootPostMenu {
    private RootPostMenu() {}

    public static void add(Object builder, Object post, Context context, Object host) {
        if (!RootPostMutes.enabled() || RootPostMutes.capture() == null || !(host instanceof HushLocalTimeline) || context == null) return;
        try {
            String root = root(post);
            HushLocalTimeline timeline = (HushLocalTimeline) host;
            if (RootPostMuteStore.validRoot(root) && RootPostLocalTimeline.contains(timeline, root))
                addNative(builder, L10n.t("Mute this post locally"), new RootPostMuteAction(root, context, timeline));
        } catch (Throwable unreadable) {
            // A changed native model or menu leaves Tumblr's menu intact, without logging IDs.
        }
    }

    static String root(Object post) {
        return post instanceof HushRootPost ? ((HushRootPost) post).hushRootId() : cardRoot(post);
    }
    public static String cardRoot(Object post) { return null; }
    public static void addNative(Object builder, String title, RootPostMuteAction callback) {}
    public static Object unit() { return null; }
}
