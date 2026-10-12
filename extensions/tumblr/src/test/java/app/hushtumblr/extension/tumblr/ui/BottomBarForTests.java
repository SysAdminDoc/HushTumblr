/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.view.View;
import android.widget.TextView;

import org.robolectric.RuntimeEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * The bottom bar's unread badges, for {@link BottomBar}'s tests and PausedHooksTest's probes. The
 * test app has none of Tumblr's view ids, so each badge gets a made-up one.
 */
public final class BottomBarForTests {
    private BottomBarForTests() {}

    /** A made-up view id for each badge's name, set as the lookup's stand-in. */
    static Map<String, Integer> standInIds() {
        Map<String, Integer> ids = new HashMap<>();
        int next = 0x7f0a0101;
        for (String name : BottomBar.BADGES.keySet()) ids.put(name, next++);
        BottomBar.idsForTests = ids;
        return ids;
    }

    /** The badge named [name] with its stand-in id, at [visibility]. */
    static TextView badge(Map<String, Integer> ids, String name, int visibility) {
        TextView badge = new TextView(RuntimeEnvironment.getApplication());
        badge.setId(ids.get(name));
        badge.setVisibility(visibility);
        return badge;
    }

    /** PausedHooksTest's probe for the Dashboard switch: true when its badge isn't handed back. */
    public static boolean hidesTheDashboardCount() {
        return hides(BottomBar.DASHBOARD);
    }

    /** PausedHooksTest's probe for the Communities switch. */
    public static boolean hidesTheCommunitiesCount() {
        return hides(BottomBar.COMMUNITIES);
    }

    /** PausedHooksTest's probe for the Notifications switch. */
    public static boolean hidesTheNotificationsCount() {
        return hides(BottomBar.NOTIFICATIONS);
    }

    private static boolean hides(String name) {
        try {
            return BottomBar.unreadBadge(badge(standInIds(), name, View.GONE)) == null;
        } finally {
            forget();
        }
    }

    /** Clears the stand-ins and what the extension remembers. */
    public static void forget() {
        BottomBar.idsForTests = null;
        BottomBar.forgetForTests();
    }
}
