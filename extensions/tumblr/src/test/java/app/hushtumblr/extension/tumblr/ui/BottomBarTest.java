/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * When Hide unread dots keeps a bottom bar badge hidden, and when it hands the badge back to
 * Tumblr. Each tab's badge has its own switch.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BottomBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private Map<String, Integer> ids;

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        HookStatus.clear();
        ids = BottomBarForTests.standInIds();
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        for (BottomBar.Badge badge : BottomBar.BADGES.values()) badge.setting.resetToDefault();
        BottomBarForTests.forget();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void eachSwitchHidesItsOwnBadgeAndHandsTumblrNone() {
        for (Map.Entry<String, BottomBar.Badge> on : BottomBar.BADGES.entrySet()) {
            on.getValue().setting.save(true);
            for (String name : BottomBar.BADGES.keySet()) {
                TextView shown = BottomBarForTests.badge(ids, name, View.VISIBLE);
                TextView waiting = BottomBarForTests.badge(ids, name, View.GONE);
                if (name.equals(on.getKey())) {
                    assertNull(name + ": a badge already showing goes", BottomBar.unreadBadge(shown));
                    assertEquals(name, View.GONE, shown.getVisibility());
                    assertNull(name + ": a badge about to pop in stays hidden", BottomBar.unreadBadge(waiting));
                    assertEquals(name, View.GONE, waiting.getVisibility());
                } else {
                    assertSame(name + " is another switch's", shown, BottomBar.unreadBadge(shown));
                    assertEquals(name, View.VISIBLE, shown.getVisibility());
                    assertSame(name, waiting, BottomBar.unreadBadge(waiting));
                }
            }
            on.getValue().setting.save(false);
        }

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("dashboard count hidden 2"));
        assertTrue(report, report.contains("communities count hidden 2"));
        assertTrue(report, report.contains("notifications count hidden 2"));
    }

    @Test
    public void theSwitchesStartOffSoTumblrGetsItsBadges() {
        for (String name : BottomBar.BADGES.keySet()) {
            TextView waiting = BottomBarForTests.badge(ids, name, View.GONE);
            assertSame(name, waiting, BottomBar.unreadBadge(waiting));
            assertEquals(name + ": Tumblr's own animation shows it", View.GONE, waiting.getVisibility());
        }
        assertNull("no badge stays no badge", BottomBar.unreadBadge(null));
    }

    @Test
    public void aBadgeItDoesntKnowIsLeftAlone() {
        for (BottomBar.Badge badge : BottomBar.BADGES.values()) badge.setting.save(true);
        TextView other = new TextView(RuntimeEnvironment.getApplication());
        other.setId(0x7f0a0fff);
        assertSame(other, BottomBar.unreadBadge(other));
        TextView unnamed = new TextView(RuntimeEnvironment.getApplication());
        assertSame("no id at all", unnamed, BottomBar.unreadBadge(unnamed));
        assertEquals(View.VISIBLE, other.getVisibility());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("unknown badge left alone 2"));
    }

    @Test
    public void anIdThisBuildDoesntHaveIsReportedAndTheOthersStillWork() {
        Map<String, Integer> partial = new HashMap<>(ids);
        partial.remove(BottomBar.DASHBOARD);
        BottomBar.idsForTests = partial;
        Settings.HIDE_NOTIFICATIONS_COUNT.save(true);

        assertNull(BottomBar.unreadBadge(BottomBarForTests.badge(ids, BottomBar.NOTIFICATIONS, View.VISIBLE)));
        assertEquals(Collections.singletonList("view id '" + BottomBar.DASHBOARD + "'"),
                HookStatus.missing(FamilyNames.HIDE_UNREAD_DOTS));
    }

    @Test
    public void pauseABuildWithoutTheHookOrAColdStartLeavesTheBadgeAlone() {
        Settings.HIDE_DASHBOARD_COUNT.save(true);
        assertNull("the control: the switch is on",
                BottomBar.unreadBadge(BottomBarForTests.badge(ids, BottomBar.DASHBOARD, View.VISIBLE)));

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        TextView paused = BottomBarForTests.badge(ids, BottomBar.DASHBOARD, View.VISIBLE);
        assertSame(paused, BottomBar.unreadBadge(paused));
        assertEquals(View.VISIBLE, paused.getVisibility());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            TextView cold = BottomBarForTests.badge(ids, BottomBar.DASHBOARD, View.VISIBLE);
            assertSame(cold, BottomBar.unreadBadge(cold));
            assertEquals(View.VISIBLE, cold.getVisibility());
        });

        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        TextView missing = BottomBarForTests.badge(ids, BottomBar.DASHBOARD, View.VISIBLE);
        assertSame("its hook isn't in this build", missing, BottomBar.unreadBadge(missing));
        assertEquals(View.VISIBLE, missing.getVisibility());
    }
}
