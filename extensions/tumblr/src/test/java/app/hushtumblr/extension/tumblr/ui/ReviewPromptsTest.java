/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.EnumSet;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** When Skip review prompts keeps Tumblr's rating prompt from opening, and when it lets it open. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReviewPromptsTest {
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
        Settings.SKIP_REVIEW_PROMPTS.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void thePromptIsSkippedWhileTheSwitchIsOn() {
        Settings.SKIP_REVIEW_PROMPTS.save(true);
        assertTrue(ReviewPrompts.skip());
        assertTrue(ReviewPrompts.skip());
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("review prompt skipped 2"));
    }

    @Test
    public void theSwitchStartsOffSoThePromptOpens() {
        assertFalse(ReviewPrompts.skip());
    }

    @Test
    public void pauseABuildWithoutTheHookOrAColdStartLetsThePromptOpen() {
        Settings.SKIP_REVIEW_PROMPTS.save(true);
        assertTrue("the control: the switch is on", ReviewPrompts.skip());

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertFalse(ReviewPrompts.skip());
        PauseForTests.resume();

        SettingsContextRule.withoutContext(() -> assertFalse(ReviewPrompts.skip()));

        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertFalse(ReviewPrompts.skip());
    }
}
