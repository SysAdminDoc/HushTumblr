/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * What Tumblr's bottom bar leaves out.
 *
 * <p>The Dashboard, Communities and Notifications tabs each have an unread badge, hidden until
 * Tumblr has something to count. The bar shows one with a pop-in animation whose start makes it
 * visible, and Tumblr runs that each time the counts come in, through one method that first
 * returns when it has no badge. {@link #unreadBadge} is put first in that method and hands it no
 * badge while that badge's switch is on, so the next count leaves it hidden. Each badge is told
 * apart by its view id, which Tumblr keeps by name.
 */
public final class BottomBar {
    private BottomBar() {}

    /** The badges' view id names in Tumblr's resources. */
    static final String DASHBOARD = "dashboard_badge";
    static final String COMMUNITIES = "communities_badge";
    static final String NOTIFICATIONS = "notification_badge";

    /** Each badge by its view id name, with the switch that hides it and what the report counts. */
    static final Map<String, Badge> BADGES;

    static {
        Map<String, Badge> badges = new LinkedHashMap<>();
        badges.put(DASHBOARD, new Badge(Settings.HIDE_DASHBOARD_COUNT, "dashboard count hidden"));
        badges.put(COMMUNITIES, new Badge(Settings.HIDE_COMMUNITIES_COUNT, "communities count hidden"));
        badges.put(NOTIFICATIONS, new Badge(Settings.HIDE_NOTIFICATIONS_COUNT, "notifications count hidden"));
        BADGES = Collections.unmodifiableMap(badges);
    }

    /** A badge's switch and the line the report counts it under. */
    static final class Badge {
        final BooleanSetting setting;
        final String hidden;

        Badge(BooleanSetting setting, String hidden) {
            this.setting = setting;
            this.hidden = hidden;
        }
    }

    /** View ids looked up by name, zero for a missing one. */
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();

    /** Stands in for the view id lookups in a test. Null in the app. */
    @Nullable
    static volatile Map<String, Integer> idsForTests;

    /**
     * Injected at the start of the bottom bar's method that pops an unread badge in. While that
     * badge's switch is on, hides [badge] and answers null, which that method takes as no badge.
     * The badge itself with its switch off, paused, before HushTumblr has its settings, for a badge
     * this doesn't know, or when anything here fails. Never throws.
     */
    @Nullable
    public static TextView unreadBadge(@Nullable TextView badge) {
        // Tumblr sets up its bar while it starts, before HushTumblr may read a switch.
        if (badge == null || !Utils.settingsReady()) return badge;
        try {
            if (!PatchFamily.Capability.UNREAD_DOTS.installed()) return badge;
            HookStatus.invoked(FamilyNames.HIDE_UNREAD_DOTS);
            Badge which = which(badge);
            if (which == null) {
                HookStatus.counted(FamilyNames.HIDE_UNREAD_DOTS, "unknown badge left alone");
                return badge;
            }
            if (!which.setting.get()) return badge;
            badge.clearAnimation();
            badge.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.HIDE_UNREAD_DOTS, which.hidden);
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_UNREAD_DOTS, "switch read", failure);
            return badge;
        }
    }

    /** Which of the three badges [badge] is, by its view id, or null when it's none of them. */
    @Nullable
    static Badge which(TextView badge) {
        int id = badge.getId();
        if (id == View.NO_ID) return null;
        for (Map.Entry<String, Badge> entry : BADGES.entrySet()) {
            int known = id(badge, entry.getKey());
            if (known == 0) HookStatus.missingViewId(FamilyNames.HIDE_UNREAD_DOTS, entry.getKey());
            else if (known == id) return entry.getValue();
        }
        return null;
    }

    // The ids are Tumblr's, kept by name, so the extension has no R field for them.
    @SuppressWarnings("DiscouragedApi")
    private static int id(View view, String name) {
        Map<String, Integer> forced = idsForTests;
        if (forced != null) {
            Integer id = forced.get(name);
            return id == null ? 0 : id;
        }
        Integer known = IDS.get(name);
        if (known != null) return known;
        int id = view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
        IDS.put(name, id);
        return id;
    }

    /** Forgets the ids looked up, for tests. */
    static void forgetForTests() {
        IDS.clear();
    }
}
