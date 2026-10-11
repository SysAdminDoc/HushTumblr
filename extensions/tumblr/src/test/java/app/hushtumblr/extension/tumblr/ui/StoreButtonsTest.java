/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

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

/** When Hide the diamond and TumblrMart hides those buttons, and when it leaves them as Tumblr set them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class StoreButtonsTest {
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
        Settings.HIDE_TUMBLRMART.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void theSwitchHidesTheDiamondItsDotAndTheTumblrMartButton() {
        Settings.HIDE_TUMBLRMART.save(true);
        View diamond = shown();
        View dot = shown();
        View store = shown();

        assertTrue("the drawer returns at once", StoreButtons.hideDiamond(diamond, dot));
        StoreButtons.hideTumblrMart(store);

        assertEquals(View.GONE, diamond.getVisibility());
        assertEquals(View.GONE, dot.getVisibility());
        assertEquals(View.GONE, store.getVisibility());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("diamond hidden 1"));
        assertTrue(report, report.contains("TumblrMart button hidden 1"));
    }

    @Test
    public void theSwitchTakesTheHiddenButtonsRoomOutOfExploresSearchBar() {
        assertEquals("off, Tumblr's room", 96, StoreButtons.tumblrMartRoom(96));
        assertEquals(96, StoreButtons.diamondRoom(96));

        Settings.HIDE_TUMBLRMART.save(true);
        assertEquals(0, StoreButtons.tumblrMartRoom(96));
        assertEquals(0, StoreButtons.diamondRoom(96));
        assertEquals("no room to take", 0, StoreButtons.diamondRoom(0));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("search bar room freed 2"));

        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PREMIUM_DIAMOND));
        assertEquals("its hook isn't in this build", 96, StoreButtons.tumblrMartRoom(96));
        assertEquals(0, StoreButtons.diamondRoom(96));

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertEquals(96, StoreButtons.diamondRoom(96));
        PauseForTests.resume();
        SettingsContextRule.withoutContext(() -> assertEquals(96, StoreButtons.diamondRoom(96)));
    }

    @Test
    public void theSwitchStartsOffSoTumblrDrawsBoth() {
        View diamond = shown();
        View store = shown();
        assertFalse(StoreButtons.hideDiamond(diamond, null));
        StoreButtons.hideTumblrMart(store);
        assertEquals(View.VISIBLE, diamond.getVisibility());
        assertEquals(View.VISIBLE, store.getVisibility());
    }

    @Test
    public void aMissingViewIsSkipped() {
        Settings.HIDE_TUMBLRMART.save(true);
        View diamond = shown();
        assertTrue(StoreButtons.hideDiamond(diamond, null));
        assertEquals(View.GONE, diamond.getVisibility());
        StoreButtons.hideTumblrMart(null);
    }

    @Test
    public void pauseABuildWithoutTheHookOrAColdStartLeavesThemAlone() {
        Settings.HIDE_TUMBLRMART.save(true);
        assertTrue("the control: the switch is on", StoreButtons.hideDiamond(null, null));

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        View store = shown();
        assertFalse(StoreButtons.hideDiamond(shown(), null));
        StoreButtons.hideTumblrMart(store);
        assertEquals(View.VISIBLE, store.getVisibility());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> {
            View cold = shown();
            assertFalse(StoreButtons.hideDiamond(cold, null));
            StoreButtons.hideTumblrMart(cold);
            assertEquals(View.VISIBLE, cold.getVisibility());
        });

        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.TUMBLRMART_BUTTONS));
        View diamond = shown();
        assertFalse("its hook isn't in this build", StoreButtons.hideDiamond(diamond, null));
        assertEquals(View.VISIBLE, diamond.getVisibility());
        StoreButtons.hideTumblrMart(store);
        assertEquals(View.GONE, store.getVisibility());

        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.PREMIUM_DIAMOND));
        View other = shown();
        StoreButtons.hideTumblrMart(other);
        assertEquals("its hook isn't in this build", View.VISIBLE, other.getVisibility());
    }

    private static View shown() {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setVisibility(View.VISIBLE);
        return view;
    }
}
