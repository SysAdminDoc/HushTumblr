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
        try {
            RootPostMuteAction action = action(post, context, host);
            if (action != null) addNative(builder, L10n.t("Mute this post locally"), action);
        } catch (Throwable unreadable) {
            // A changed native model or menu leaves Tumblr's menu intact, without logging IDs.
        }
    }

    static RootPostMuteAction action(Object post, Context context, Object host) {
        if (!RootPostMutes.enabled() || RootPostMutes.capture() == null || context == null) return null;
        try {
            HushLocalTimeline timeline = host instanceof HushRootMenuHost ? ((HushRootMenuHost) host).hushTimelineOwner()
                    : host instanceof HushLocalTimeline ? (HushLocalTimeline) host : null;
            String root = root(post);
            return timeline != null && RootPostMuteStore.validRoot(root) && RootPostLocalTimeline.contains(timeline, root)
                    ? new RootPostMuteAction(root, context, timeline) : null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    static String root(Object post) {
        return post instanceof HushRootPost ? ((HushRootPost) post).hushRootId() : cardRoot(post);
    }
    public static String cardRoot(Object post) { return null; }
    public static void addNative(Object builder, String title, RootPostMuteAction callback) {}
    public static Object unit() { return null; }
}
