/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
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

/** When the footer switches leave a button or the counts out of a post's footer, and when they don't. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostFooterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        PostFooter.forgetAnswersForTests();
        HookStatus.clear();
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        Settings.HIDE_NOTE_COUNTS.resetToDefault();
        Settings.HIDE_LIKE_BUTTON.resetToDefault();
        Settings.HIDE_REBLOG_BUTTON.resetToDefault();
        Settings.HIDE_COMMENT_BUTTON.resetToDefault();
        Settings.HIDE_SHARE_BUTTON.resetToDefault();
        PostFooter.forgetAnswersForTests();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void eachButtonSwitchHidesOnlyItsOwnButton() {
        Settings.HIDE_REBLOG_BUTTON.save(true);
        Settings.HIDE_SHARE_BUTTON.save(true);

        assertFalse("its switch is off", PostFooter.hideLikeButton());
        assertTrue(PostFooter.hideReblogButton());
        assertFalse("its switch is off", PostFooter.hideCommentButton());
        assertTrue(PostFooter.hideShareButton());
        assertTrue(PostFooter.hideShareButton());
        assertEquals("the buttons don't touch the counts", 12, PostFooter.noteCount(12));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("reblog button hidden 1"));
        assertTrue(report, report.contains("share button hidden 2"));
    }

    @Test
    public void aButtonKeepsItsFirstAnswerUntilTumblrRestarts() {
        Settings.HIDE_LIKE_BUTTON.save(true);
        assertTrue(PostFooter.hideLikeButton());
        Settings.HIDE_LIKE_BUTTON.save(false);
        assertTrue("the footer Compose drew keeps its buttons", PostFooter.hideLikeButton());

        assertFalse("another button has its own answer", PostFooter.hideCommentButton());
        Settings.HIDE_COMMENT_BUTTON.save(true);
        assertFalse(PostFooter.hideCommentButton());

        PostFooter.forgetAnswersForTests();
        assertFalse("the next start reads the switch again", PostFooter.hideLikeButton());
        assertTrue(PostFooter.hideCommentButton());
    }

    @Test
    public void theCountsSwitchBlanksEveryCountAndShowsAtOnce() {
        Settings.HIDE_NOTE_COUNTS.save(true);
        assertEquals(0, PostFooter.noteCount(12));
        assertEquals(0, PostFooter.noteCount(1));
        assertEquals("zero is already blank", 0, PostFooter.noteCount(0));
        assertFalse("the counts don't touch the buttons", PostFooter.hideLikeButton());

        Settings.HIDE_NOTE_COUNTS.save(false);
        assertEquals("the switch is read for each count", 12, PostFooter.noteCount(12));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("count hidden 2"));
    }

    @Test
    public void everySwitchStartsOffSoTheFooterIsTumblrs() {
        assertEquals(7, PostFooter.noteCount(7));
        assertFalse(PostFooter.hideLikeButton());
        assertFalse(PostFooter.hideReblogButton());
        assertFalse(PostFooter.hideCommentButton());
        assertFalse(PostFooter.hideShareButton());
    }

    @Test
    public void pauseABuildWithoutTheHookOrAColdStartLeavesTheFooterAlone() {
        Settings.HIDE_NOTE_COUNTS.save(true);
        Settings.HIDE_LIKE_BUTTON.save(true);

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertEquals(9, PostFooter.noteCount(9));
        assertFalse(PostFooter.hideLikeButton());
        PauseForTests.resume();
        PostFooter.forgetAnswersForTests();

        SettingsContextRule.withoutContext(() -> {
            assertEquals(9, PostFooter.noteCount(9));
            assertFalse(PostFooter.hideLikeButton());
        });
        assertTrue("before the settings were ready nothing was kept", PostFooter.hideLikeButton());
        assertEquals(0, PostFooter.noteCount(9));
        PostFooter.forgetAnswersForTests();

        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LIKE_BUTTON));
        assertEquals("its hook isn't in this build", 9, PostFooter.noteCount(9));
        assertTrue(PostFooter.hideLikeButton());
        PostFooter.forgetAnswersForTests();
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.NOTE_COUNTS));
        assertEquals(0, PostFooter.noteCount(9));
        assertFalse("its hook isn't in this build", PostFooter.hideLikeButton());
    }
}
