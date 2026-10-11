/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bottom bar and a tab list shaped like Tumblr's, for {@link BottomBarTabs}' tests and
 * PausedHooksTest's probes. The test app has none of Tumblr's view ids, so each gets a made-up one.
 */
public final class BottomBarTabsForTests {
    private BottomBarTabsForTests() {}

    /** Stands in for Tumblr's tab enum: the same names, in Tumblr's order. */
    public enum Tab { Dashboard, Search, TumblrTV, Communities, Notifications, Account }

    /** The order PausedHooksTest saves: Account first. */
    public static final String PROBE_ORDER = "Account";

    /** A made-up view id for each of Tumblr's names, set as the lookup's stand-in. */
    static Map<String, Integer> standInIds() {
        Map<String, Integer> ids = new HashMap<>();
        int next = 0x7f0a0001;
        for (String name : BottomBarTabs.BUTTONS.values()) ids.put(name, next++);
        ids.put(BottomBarTabs.BAR, next);
        BottomBarTabs.idsForTests = ids;
        return ids;
    }

    /** A root view holding the bar, its buttons in the layout's order, with a spacer after Dashboard. */
    static FrameLayout root(Map<String, Integer> ids) {
        Context app = RuntimeEnvironment.getApplication();
        FrameLayout root = new FrameLayout(app);
        LinearLayout bar = new LinearLayout(app);
        bar.setId(ids.get(BottomBarTabs.BAR));
        root.addView(bar);
        boolean first = true;
        for (String name : BottomBarTabs.BUTTONS.values()) {
            View button = new View(app);
            button.setId(ids.get(name));
            bar.addView(button);
            if (first) {
                bar.addView(new View(app));
                first = false;
            }
        }
        return root;
    }

    static View button(FrameLayout root, Map<String, Integer> ids, String tab) {
        return root.findViewById(ids.get(BottomBarTabs.BUTTONS.get(tab)));
    }

    /** Tumblr's full tab list, as its builder has it before freezing it. */
    static List<Object> tabs() {
        return new ArrayList<>(Arrays.asList(Tab.values()));
    }

    /** PausedHooksTest's probe for Hide the Explore tab: true when the bar comes up without it. */
    public static boolean hidesTheExploreTab() {
        return hides(BottomBarTabs.EXPLORE);
    }

    /** PausedHooksTest's probe for Hide the Notifications tab. */
    public static boolean hidesTheNotificationsTab() {
        return hides(BottomBarTabs.NOTIFICATIONS);
    }

    private static boolean hides(String tab) {
        Map<String, Integer> ids = standInIds();
        try {
            FrameLayout root = root(ids);
            BottomBarTabs.arrangeBar(root);
            return button(root, ids, tab).getVisibility() == View.GONE;
        } finally {
            forget();
        }
    }

    /** PausedHooksTest's probe for Reorder the bottom bar: true when the list puts Account first. */
    public static boolean reordersTheTabs() {
        try {
            List<Object> tabs = tabs();
            BottomBarTabs.arrangeTabs(tabs);
            return tabs.get(0) == Tab.Account;
        } finally {
            forget();
        }
    }

    /** Clears the stand-ins and what the extension remembers. */
    public static void forget() {
        BottomBarTabs.idsForTests = null;
        BottomBarTabs.forgetForTests();
    }
}
