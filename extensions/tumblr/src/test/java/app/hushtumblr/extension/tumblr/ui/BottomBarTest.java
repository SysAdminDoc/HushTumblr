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

import java.util.EnumSet;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** When Hide unread dots keeps a bottom bar badge hidden, and when it hands the badge back to Tumblr. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class BottomBarTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        HookStatus.clear();
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        Settings.HIDE_UNREAD_DOTS.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchHidesTheBadgeAndHandsTumblrNone() {
        Settings.HIDE_UNREAD_DOTS.save(true);
        TextView shown = badge(View.VISIBLE);
        TextView waiting = badge(View.GONE);

        assertNull("a badge already showing goes", BottomBar.unreadBadge(shown));
        assertEquals(View.GONE, shown.getVisibility());
        assertNull("a badge about to pop in stays hidden", BottomBar.unreadBadge(waiting));
        assertEquals(View.GONE, waiting.getVisibility());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("unread dot hidden 2"));
    }

    @Test
    public void theSwitchStartsOffSoTumblrGetsItsBadge() {
        TextView waiting = badge(View.GONE);
        assertSame(waiting, BottomBar.unreadBadge(waiting));
        assertEquals("Tumblr's own animation shows it", View.GONE, waiting.getVisibility());
        assertNull("no badge stays no badge", BottomBar.unreadBadge(null));
    }

    @Test
    public void pauseABuildWithoutTheHookOrAColdStartLeavesTheBadgeAlone() {
        Settings.HIDE_UNREAD_DOTS.save(true);
        assertNull("the control: the switch is on", BottomBar.unreadBadge(badge(View.VISIBLE)));

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        TextView paused = badge(View.VISIBLE);
        assertSame(paused, BottomBar.unreadBadge(paused));
        assertEquals(View.VISIBLE, paused.getVisibility());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            TextView cold = badge(View.VISIBLE);
            assertSame(cold, BottomBar.unreadBadge(cold));
            assertEquals(View.VISIBLE, cold.getVisibility());
        });

        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        TextView missing = badge(View.VISIBLE);
        assertSame("its hook isn't in this build", missing, BottomBar.unreadBadge(missing));
        assertEquals(View.VISIBLE, missing.getVisibility());
    }

    private static TextView badge(int visibility) {
        TextView badge = new TextView(RuntimeEnvironment.getApplication());
        badge.setVisibility(visibility);
        return badge;
    }
}
