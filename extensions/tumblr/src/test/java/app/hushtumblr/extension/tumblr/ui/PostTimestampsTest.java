/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostTimestampsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.SHOW_POST_TIMESTAMPS.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.SHOW_POST_TIMESTAMPS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void itStartsOffAndPreservesBothNativeChoices() {
        assertFalse(Settings.SHOW_POST_TIMESTAMPS.get());
        assertNativeChoices();
    }

    @Test public void optingInEnablesTheNativeLayoutAndOptingOutRestoresTheNativeChoice() {
        Settings.SHOW_POST_TIMESTAMPS.save(true);
        assertTrue(PostTimestamps.show(false));
        assertTrue(PostTimestamps.show(true));
        Settings.SHOW_POST_TIMESTAMPS.save(false);
        assertNativeChoices();
    }

    @Test public void everyPauseReasonRestoresNativeChoicesWithoutForgettingTheSwitch() {
        Settings.SHOW_POST_TIMESTAMPS.save(true);
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertNativeChoices();
            assertTrue(Settings.SHOW_POST_TIMESTAMPS.savedValue());
            PauseForTests.resume();
        }
    }

    @Test public void startupAndMissingBuildFactsPreserveTheNativePreference() {
        Settings.SHOW_POST_TIMESTAMPS.save(true);
        SettingsContextRule.withoutContext(this::assertNativeChoices);
        SettingsContextRule.beforeThePauseIsDecided(this::assertNativeChoices);
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertNativeChoices();
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        PatchFamilyForTests.inBuild(EnumSet.noneOf(PatchFamily.class));
        assertNativeChoices();
    }

    private void assertNativeChoices() {
        assertFalse(PostTimestamps.show(false));
        assertTrue(PostTimestamps.show(true));
    }
}
