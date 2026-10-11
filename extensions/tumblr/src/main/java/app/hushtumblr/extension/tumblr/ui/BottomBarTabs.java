/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.settings.TextSettings;

/**
 * The bottom bar's tabs: Hide the Explore tab, Hide the Notifications tab and Reorder the bottom bar.
 *
 * <p>Tumblr builds one list of its tabs, once a run, and everything else follows it: the bar's
 * buttons are bound in its order, a tap finds its tab by the button's place in it, the pages are
 * made from it, and the start tab and every link into a tab ask it where that tab is. The tab list
 * hook hands it to {@link #arrangeTabs} before Tumblr freezes it, so a new order there reaches all
 * of those at once. Only the buttons' places on screen come from the layout, so the bar hook
 * ({@link #arrangeBar}) puts them in the list's order once the bar is built.
 *
 * <p>A hidden tab stays in the list and only its button goes, since Tumblr's links into a tab and
 * its saved place would land on the wrong tab, or none, if the list lost one. Dashboard and Account
 * can't be hidden, so the bar always has a tab to open on. Tumblr TV and Communities have their
 * own switches, which keep them out of the list altogether; the order then skips them.
 *
 * <p>Off, paused, before the settings are read, or with an order naming no tab, the list and the
 * bar stay Tumblr's.
 */
public final class BottomBarTabs {
    private BottomBarTabs() {}

    /** The tabs, by their names in Tumblr's tab enum, in Tumblr's order. */
    public static final List<String> TABS = Collections.unmodifiableList(
            Arrays.asList("Dashboard", "Search", "TumblrTV", "Communities", "Notifications", "Account"));

    /** The tabs that have a hide switch here. */
    static final String EXPLORE = "Search";
    static final String NOTIFICATIONS = "Notifications";

    /** Each tab's button on the bar, a view id. Resource names are kept. */
    static final Map<String, String> BUTTONS;

    static {
        Map<String, String> buttons = new LinkedHashMap<>();
        buttons.put("Dashboard", "dashboard_button_wrapper");
        buttons.put("Search", "topnav_explore_button_img_active");
        buttons.put("TumblrTV", "topnav_tumblr_tv_button");
        buttons.put("Communities", "communities_button_wrapper");
        buttons.put("Notifications", "notification_button_wrapper");
        buttons.put("Account", "topnav_account_button_img_active");
        BUTTONS = Collections.unmodifiableMap(buttons);
    }

    /** The bar the buttons sit in, a view id. */
    static final String BAR = "top_nav_bar";

    static final String ORDER_APPLIED = "tab order applied";
    static final String BAR_ARRANGED = "bar arranged";
    static final String TAB_HIDDEN = "tab hidden";

    /** The tabs as Tumblr last built its list, by name. Null before. */
    @Nullable
    private static volatile List<String> tumblrOrder;

    /** The tabs as the list was handed back after a new order, by name. Null when it kept Tumblr's. */
    @Nullable
    private static volatile List<String> shownOrder;

    /** View ids looked up by name, zero for a missing one. */
    private static final Map<String, Integer> IDS = new ConcurrentHashMap<>();

    /** Stands in for the view id lookups in a test. Null in the app. */
    @Nullable
    static volatile Map<String, Integer> idsForTests;

    /**
     * Placed in Tumblr's tab list builder, right before the list is frozen: puts [tabs] in the
     * saved order, in place. Never throws.
     */
    public static void arrangeTabs(@Nullable List<Object> tabs) {
        if (tabs == null) return;
        try {
            shownOrder = null;
            tumblrOrder = names(tabs);
            if (!Utils.settingsReady() || !PatchFamily.Capability.TAB_ORDER.installed()) return;
            HookStatus.invoked(FamilyNames.REORDER_TABS);
            if (!Settings.REORDER_TABS.get()) return;
            List<Object> shown = ordered(tabs, parse(TextSettings.TAB_ORDER.get()));
            if (shown == tabs) return;
            tabs.clear();
            tabs.addAll(shown);
            shownOrder = names(shown);
            HookStatus.counted(FamilyNames.REORDER_TABS, ORDER_APPLIED);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REORDER_TABS, "tab list", failure);
        }
    }

    /**
     * Placed right after Tumblr builds the bottom bar's controller, on the view holding the bar:
     * hides the buttons whose switch is on and puts the rest in the list's order. Never throws.
     */
    public static void arrangeBar(@Nullable ViewGroup root) {
        if (root == null || !Utils.settingsReady()) return;
        try {
            hide(root, EXPLORE, PatchFamily.Capability.EXPLORE_TAB, FamilyNames.HIDE_EXPLORE_TAB,
                    Settings.HIDE_EXPLORE_TAB);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_EXPLORE_TAB, "bottom bar", failure);
        }
        try {
            hide(root, NOTIFICATIONS, PatchFamily.Capability.NOTIFICATIONS_TAB, FamilyNames.HIDE_NOTIFICATIONS_TAB,
                    Settings.HIDE_NOTIFICATIONS_TAB);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_NOTIFICATIONS_TAB, "bottom bar", failure);
        }
        try {
            arrangeButtons(root);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.REORDER_TABS, "bottom bar", failure);
        }
    }

    /** Sets [tab]'s button GONE when its switch is on. True when it did. */
    static boolean hide(ViewGroup root, String tab, PatchFamily.Capability capability, String family,
            BooleanSetting setting) {
        if (!capability.installed()) return false;
        HookStatus.invoked(family);
        if (!setting.get()) return false;
        View button = find(root, BUTTONS.get(tab), family);
        if (button == null) return false;
        button.setVisibility(View.GONE);
        HookStatus.counted(family, TAB_HIDDEN);
        return true;
    }

    /**
     * Puts the tabs' buttons in the bar in the order the list was handed back in, each into a place a
     * tab's button held, so anything else in the bar keeps its place. True when it moved any.
     */
    static boolean arrangeButtons(ViewGroup root) {
        List<String> shown = shownOrder;
        if (shown == null) return false;
        View found = find(root, BAR, FamilyNames.REORDER_TABS);
        if (!(found instanceof ViewGroup)) return false;
        ViewGroup bar = (ViewGroup) found;

        Map<Integer, String> tabById = new LinkedHashMap<>();
        for (Map.Entry<String, String> button : BUTTONS.entrySet()) {
            int id = id(root, button.getValue());
            if (id != 0) tabById.put(id, button.getKey());
        }
        List<View> children = new ArrayList<>();
        List<Integer> places = new ArrayList<>();
        Map<String, View> buttons = new LinkedHashMap<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            children.add(child);
            String tab = tabById.get(child.getId());
            if (tab != null && !buttons.containsKey(tab)) {
                places.add(i);
                buttons.put(tab, child);
            }
        }
        List<View> wanted = new ArrayList<>();
        for (String tab : wanted(shown)) {
            View button = buttons.get(tab);
            if (button != null) wanted.add(button);
        }
        if (wanted.size() != places.size()) return false;
        List<View> arranged = new ArrayList<>(children);
        for (int i = 0; i < places.size(); i++) arranged.set(places.get(i), wanted.get(i));
        if (arranged.equals(children)) return false;
        bar.removeAllViews();
        for (View child : arranged) bar.addView(child);
        HookStatus.counted(FamilyNames.REORDER_TABS, BAR_ARRANGED);
        return true;
    }

    /** [shown], then every other tab in Tumblr's order. */
    private static List<String> wanted(List<String> shown) {
        Set<String> wanted = new LinkedHashSet<>(shown);
        wanted.addAll(TABS);
        return new ArrayList<>(wanted);
    }

    /**
     * A copy of [tabs] with the tabs [order] names first, in that order, then the others in the order
     * they came, or [tabs] itself when that's the order they're in already, [order] is empty, or the
     * list holds something that isn't a tab.
     */
    static List<Object> ordered(List<Object> tabs, List<String> order) {
        if (order.isEmpty() || tabs.isEmpty()) return tabs;
        for (Object tab : tabs) {
            if (!(tab instanceof Enum)) return tabs;
        }
        List<Object> shown = new ArrayList<>(tabs.size());
        for (String name : order) {
            for (Object tab : tabs) {
                if (((Enum<?>) tab).name().equals(name) && !shown.contains(tab)) shown.add(tab);
            }
        }
        for (Object tab : tabs) {
            if (!shown.contains(tab)) shown.add(tab);
        }
        return shown.equals(tabs) ? tabs : shown;
    }

    /** The tab names in [saved], in order, without blanks, repeats or names that aren't tabs. */
    public static List<String> parse(@Nullable String saved) {
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        Set<String> names = new LinkedHashSet<>();
        for (String part : saved.split(",")) {
            String name = part.trim();
            if (TABS.contains(name)) names.add(name);
        }
        return new ArrayList<>(names);
    }

    /** [names] as the saved order. */
    public static String join(List<String> names) {
        return String.join(",", names);
    }

    /**
     * The tabs for the settings list, in the order the bar shows them with [saved] applied: the tabs
     * in Tumblr's list as it last built it, or every tab before it has.
     */
    public static List<String> choices(@Nullable String saved) {
        List<String> built = tumblrOrder;
        List<String> base = new ArrayList<>();
        if (built != null) {
            for (String name : built) {
                if (TABS.contains(name)) base.add(name);
            }
        }
        if (base.isEmpty()) base.addAll(TABS);
        List<String> chosen = new ArrayList<>();
        for (String name : parse(saved)) {
            if (base.contains(name)) chosen.add(name);
        }
        for (String name : base) {
            if (!chosen.contains(name)) chosen.add(name);
        }
        return chosen;
    }

    /** [names] with the one at [index] moved one place toward the start. The first stays where it is. */
    public static List<String> movedUp(List<String> names, int index) {
        List<String> moved = new ArrayList<>(names);
        if (index > 0 && index < moved.size()) Collections.swap(moved, index, index - 1);
        return moved;
    }

    private static List<String> names(List<?> tabs) {
        List<String> names = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (tab instanceof Enum) names.add(((Enum<?>) tab).name());
        }
        return names;
    }

    @Nullable
    private static View find(ViewGroup root, @Nullable String name, String family) {
        if (name == null) return null;
        int id = id(root, name);
        if (id == 0) {
            HookStatus.missingMember(family, "id", "resources", name);
            return null;
        }
        View view = root.findViewById(id);
        if (view == null) HookStatus.missingMember(family, "view", "bottom bar", name);
        return view;
    }

    // The ids are Tumblr's, kept by name, so the extension has no R field for them.
    @SuppressWarnings("DiscouragedApi")
    private static int id(ViewGroup root, String name) {
        Map<String, Integer> forced = idsForTests;
        if (forced != null) {
            Integer id = forced.get(name);
            return id == null ? 0 : id;
        }
        Integer known = IDS.get(name);
        if (known != null) return known;
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        IDS.put(name, id);
        return id;
    }

    /** Forgets the tab list as last built and the ids looked up, for tests, the settings page's included. */
    public static void forgetForTests() {
        tumblrOrder = null;
        shownOrder = null;
        IDS.clear();
    }
}
