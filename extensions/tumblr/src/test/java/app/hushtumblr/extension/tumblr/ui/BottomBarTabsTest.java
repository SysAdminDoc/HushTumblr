/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.settings.TextSettings;
import app.hushtumblr.extension.tumblr.ui.BottomBarTabsForTests.Tab;

/** How the bottom bar's tabs are hidden and put in order, and when they're left as Tumblr made them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BottomBarTabsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Map<String, Integer> ids;

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        ids = BottomBarTabsForTests.standInIds();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        Settings.HIDE_EXPLORE_TAB.resetToDefault();
        Settings.HIDE_NOTIFICATIONS_TAB.resetToDefault();
        Settings.REORDER_TABS.resetToDefault();
        TextSettings.TAB_ORDER.resetToDefault();
        BottomBarTabsForTests.forget();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSavedOrderGoesFirstAndTheRestKeepTumblrsOrder() {
        Settings.REORDER_TABS.save(true);
        TextSettings.TAB_ORDER.save("Notifications, Search");
        List<Object> tabs = BottomBarTabsForTests.tabs();

        BottomBarTabs.arrangeTabs(tabs);

        assertEquals(Arrays.asList(Tab.Notifications, Tab.Search, Tab.Dashboard, Tab.TumblrTV, Tab.Communities,
                Tab.Account), tabs);
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(BottomBarTabs.ORDER_APPLIED + " 1"));
    }

    @Test
    public void theSwitchStartsOffSoTheListStaysTumblrs() {
        TextSettings.TAB_ORDER.save("Account");
        List<Object> tabs = BottomBarTabsForTests.tabs();
        BottomBarTabs.arrangeTabs(tabs);
        assertEquals(BottomBarTabsForTests.tabs(), tabs);
    }

    @Test
    public void anEmptyOrUnknownOrderKeepsTumblrsOrder() {
        Settings.REORDER_TABS.save(true);
        for (String saved : Arrays.asList("", " , ", "Home,Reels", "Dashboard")) {
            TextSettings.TAB_ORDER.save(saved);
            List<Object> tabs = BottomBarTabsForTests.tabs();
            BottomBarTabs.arrangeTabs(tabs);
            assertEquals(saved, BottomBarTabsForTests.tabs(), tabs);
        }
    }

    @Test
    public void pausedTheListStaysTumblrs() {
        Settings.REORDER_TABS.save(true);
        TextSettings.TAB_ORDER.save("Account");
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        List<Object> tabs = BottomBarTabsForTests.tabs();
        BottomBarTabs.arrangeTabs(tabs);
        assertEquals(BottomBarTabsForTests.tabs(), tabs);
    }

    /** Tumblr TV and Communities, when their own switches keep them out, are skipped by the order. */
    @Test
    public void aTabMissingFromTumblrsListIsSkipped() {
        Settings.REORDER_TABS.save(true);
        TextSettings.TAB_ORDER.save("TumblrTV,Account");
        List<Object> tabs = new ArrayList<>(Arrays.asList(Tab.Dashboard, Tab.Search, Tab.Notifications, Tab.Account));
        BottomBarTabs.arrangeTabs(tabs);
        assertEquals(Arrays.asList(Tab.Account, Tab.Dashboard, Tab.Search, Tab.Notifications), tabs);
        assertEquals("the settings list offers the tabs Tumblr built",
                Arrays.asList("Account", "Dashboard", "Search", "Notifications"), BottomBarTabs.choices("Account"));
    }

    @Test
    public void aListOfSomethingElseIsLeftAlone() {
        List<Object> things = new ArrayList<>(Arrays.asList("Dashboard", "Account"));
        assertSame(things, BottomBarTabs.ordered(things, Collections.singletonList("Account")));
    }

    @Test
    public void theBarsButtonsFollowTheListAndTheSpacerKeepsItsPlace() {
        Settings.REORDER_TABS.save(true);
        TextSettings.TAB_ORDER.save("Account,Notifications");
        BottomBarTabs.arrangeTabs(BottomBarTabsForTests.tabs());
        FrameLayout root = BottomBarTabsForTests.root(ids);
        ViewGroup bar = (ViewGroup) root.getChildAt(0);
        View spacer = bar.getChildAt(1);

        BottomBarTabs.arrangeBar(root);

        assertEquals(button(root, "Account"), bar.getChildAt(0));
        assertSame("anything else in the bar keeps its place", spacer, bar.getChildAt(1));
        assertEquals(button(root, "Notifications"), bar.getChildAt(2));
        assertEquals(button(root, "Dashboard"), bar.getChildAt(3));
        assertEquals(button(root, "Search"), bar.getChildAt(4));
        assertEquals(button(root, "TumblrTV"), bar.getChildAt(5));
        assertEquals(button(root, "Communities"), bar.getChildAt(6));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(BottomBarTabs.BAR_ARRANGED + " 1"));
    }

    @Test
    public void aListInTumblrsOrderLeavesTheBarAlone() {
        BottomBarTabs.arrangeTabs(BottomBarTabsForTests.tabs());
        FrameLayout root = BottomBarTabsForTests.root(ids);
        ViewGroup bar = (ViewGroup) root.getChildAt(0);
        View first = bar.getChildAt(0);
        BottomBarTabs.arrangeBar(root);
        assertSame(first, bar.getChildAt(0));
        assertFalse(String.join("\n", HookStatus.report()).contains(BottomBarTabs.BAR_ARRANGED));
    }

    @Test
    public void eachHideSwitchTakesOnlyItsOwnButton() {
        Settings.HIDE_EXPLORE_TAB.save(true);
        FrameLayout root = BottomBarTabsForTests.root(ids);

        BottomBarTabs.arrangeBar(root);

        assertEquals(View.GONE, button(root, "Search").getVisibility());
        assertEquals(View.VISIBLE, button(root, "Notifications").getVisibility());
        assertEquals(View.VISIBLE, button(root, "Dashboard").getVisibility());
        assertEquals(View.VISIBLE, button(root, "Account").getVisibility());

        Settings.HIDE_NOTIFICATIONS_TAB.save(true);
        FrameLayout again = BottomBarTabsForTests.root(ids);
        BottomBarTabs.arrangeBar(again);
        assertEquals(View.GONE, button(again, "Notifications").getVisibility());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(BottomBarTabs.TAB_HIDDEN));
    }

    @Test
    public void theHideSwitchesStartOff() {
        FrameLayout root = BottomBarTabsForTests.root(ids);
        BottomBarTabs.arrangeBar(root);
        assertEquals(View.VISIBLE, button(root, "Search").getVisibility());
        assertEquals(View.VISIBLE, button(root, "Notifications").getVisibility());
    }

    @Test
    public void aBuildWithoutTheHookLeavesTheButton() {
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        Settings.HIDE_EXPLORE_TAB.save(true);
        FrameLayout root = BottomBarTabsForTests.root(ids);
        BottomBarTabs.arrangeBar(root);
        assertEquals(View.VISIBLE, button(root, "Search").getVisibility());
    }

    @Test
    public void theHooksTakeNothingQuietly() {
        BottomBarTabs.arrangeTabs(null);
        BottomBarTabs.arrangeBar(null);
        assertFalse(String.join("\n", HookStatus.report()).contains("threw"));
    }

    @Test
    public void theSettingsListMovesATabUpAndKeepsTheFirst() {
        List<String> order = BottomBarTabs.choices("");
        assertEquals(BottomBarTabs.TABS, order);
        assertEquals(Arrays.asList("Search", "Dashboard", "TumblrTV", "Communities", "Notifications", "Account"),
                BottomBarTabs.movedUp(order, 1));
        assertEquals(order, BottomBarTabs.movedUp(order, 0));
        assertEquals("Search,Dashboard", BottomBarTabs.join(Arrays.asList("Search", "Dashboard")));
        assertEquals(Arrays.asList("Account", "Search"), BottomBarTabs.parse(" Account ,Search,Account,Home"));
    }

    private View button(FrameLayout root, String tab) {
        return BottomBarTabsForTests.button(root, ids, tab);
    }
}
