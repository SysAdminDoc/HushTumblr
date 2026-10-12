/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.notifications;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** Filters only in-app activity pages. The push notification response never calls this hook. */
public final class ActivityNotifications {
    private ActivityNotifications() {}

    enum Kind { LIKES, REBLOGS, REPLIES, FOLLOWS, MENTIONS, ASKS, BOOPS }

    /**
     * Runs on the activity response's list parameter before the response stores it. Pagination
     * links stay with the response, including when all items on a page are hidden. The original
     * list is never changed. Before startup, while paused and with every switch off it is returned
     * unchanged. An unreadable item stays in its original position.
     */
    public static List<?> filter(List<?> items) {
        if (!Utils.settingsReady()) return items;
        try {
            if (!PatchFamily.FILTER_ACTIVITY_NOTIFICATIONS.inBuild()
                    || !PatchFamily.Capability.ACTIVITY_NOTIFICATION_LIST.installed()
                    || items == null || items.isEmpty()) return items;
            HookStatus.invoked(FamilyNames.FILTER_ACTIVITY_NOTIFICATIONS);
            EnumSet<Kind> hidden = EnumSet.noneOf(Kind.class);
            if (Settings.HIDE_NOTIFICATION_LIKES.get()) hidden.add(Kind.LIKES);
            if (Settings.HIDE_NOTIFICATION_REBLOGS.get()) hidden.add(Kind.REBLOGS);
            if (Settings.HIDE_NOTIFICATION_REPLIES.get()) hidden.add(Kind.REPLIES);
            if (Settings.HIDE_NOTIFICATION_FOLLOWS.get()) hidden.add(Kind.FOLLOWS);
            if (Settings.HIDE_NOTIFICATION_MENTIONS.get()) hidden.add(Kind.MENTIONS);
            if (Settings.HIDE_NOTIFICATION_ASKS.get()) hidden.add(Kind.ASKS);
            if (Settings.HIDE_NOTIFICATION_BOOPS.get()) hidden.add(Kind.BOOPS);
            if (hidden.isEmpty()) return items;

            List<Object> kept = null;
            int removed = 0;
            for (int i = 0, size = items.size(); i < size; i++) {
                Object item = items.get(i);
                if (removes(item, hidden)) {
                    if (kept == null) {
                        kept = new ArrayList<>(size);
                        kept.addAll(items.subList(0, i));
                    }
                    removed++;
                } else if (kept != null) {
                    kept.add(item);
                }
            }
            if (kept == null) return items;
            for (int i = 0; i < removed; i++) {
                HookStatus.counted(FamilyNames.FILTER_ACTIVITY_NOTIFICATIONS, "notification removed");
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FILTER_ACTIVITY_NOTIFICATIONS, "activity list", failure);
            return items;
        }
    }

    private static boolean removes(Object item, EnumSet<Kind> hidden) {
        if (!(item instanceof HushActivityNotification)) return false;
        try {
            Kind kind = kind(((HushActivityNotification) item).hushNotificationType());
            return kind != null && hidden.contains(kind);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FILTER_ACTIVITY_NOTIFICATIONS, "activity type", failure);
            return false;
        }
    }

    /** Only these exact kept enum names are known. Conversations and moderation notices stay. */
    private static Kind kind(String type) {
        if (type == null) return null;
        switch (type) {
            case "Like": case "LikeRollup": return Kind.LIKES;
            case "Reblog": case "ReblogNaked": case "ReblogNakedRollup": return Kind.REBLOGS;
            case "Reply": case "ReplyToComment": return Kind.REPLIES;
            case "Follower": case "FollowerRollup": return Kind.FOLLOWS;
            case "NoteMention": case "UserMention": return Kind.MENTIONS;
            case "Ask": case "AskAnswer": return Kind.ASKS;
            case "Boop": case "BoopRollup": return Kind.BOOPS;
            default: return null;
        }
    }
}
