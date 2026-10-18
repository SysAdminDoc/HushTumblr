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
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class OptionalNudgesTest {
    private static final String GIFT = "key_has_seen_ad_free_browsing_gift_popup";
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void installBothHooks() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.resetToDefault();
        Settings.HIDE_GIFT_MESSAGE_POPUPS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void bothStartOffAndChangeIndependentlyWithoutRestarting() {
        assertFalse(OptionalNudges.hideBlogNotificationReminder());
        assertFalse(OptionalNudges.hideGiftMessage(GIFT));
        assertFalse(Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.rebootApp);
        assertFalse(Settings.HIDE_GIFT_MESSAGE_POPUPS.rebootApp);
        Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.save(true);
        assertTrue(OptionalNudges.hideBlogNotificationReminder());
        assertFalse(OptionalNudges.hideGiftMessage(GIFT));
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(true);
        assertTrue(OptionalNudges.hideGiftMessage(GIFT));
        Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.save(false);
        assertFalse(OptionalNudges.hideBlogNotificationReminder());
        assertTrue(OptionalNudges.hideGiftMessage(GIFT));
    }

    @Test public void aCrabPopupOrAnUnknownKeyAlwaysKeepsTumblrsBehavior() {
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(true);
        for (String key : new String[]{null, "", "key_has_seen_summon_crabs_popup", "gift", GIFT + "_other"}) {
            assertFalse(String.valueOf(key), OptionalNudges.hideGiftMessage(key));
        }
        assertFalse(String.join("\n", HookStatus.report()).contains("gift popup hidden"));
        assertTrue(OptionalNudges.hideGiftMessage(GIFT));
    }

    @Test public void queuedGiftCallbacksReadTheCurrentSwitchAndPauseState() {
        AtomicBoolean hidden = new AtomicBoolean();
        Runnable pending = () -> hidden.set(OptionalNudges.hideGiftMessage(GIFT));
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(true);
        pending.run();
        assertTrue(hidden.get());
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(false);
        pending.run();
        assertFalse(hidden.get());
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(true);
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        pending.run();
        assertFalse(hidden.get());
        PauseForTests.resume();
        pending.run();
        assertTrue(hidden.get());
    }

    @Test public void pauseSafeModeColdStartAndMissingHooksKeepTheNativeDecisions() {
        Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.save(true);
        Settings.HIDE_GIFT_MESSAGE_POPUPS.save(true);
        for (HushTumblrPause.Reason reason : new HushTumblrPause.Reason[]{
                HushTumblrPause.Reason.SWITCH, HushTumblrPause.Reason.CRASH_LOOP, HushTumblrPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse(OptionalNudges.hideBlogNotificationReminder());
            assertFalse(OptionalNudges.hideGiftMessage(GIFT));
            assertTrue(Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.savedValue());
            assertTrue(Settings.HIDE_GIFT_MESSAGE_POPUPS.savedValue());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse(OptionalNudges.hideBlogNotificationReminder());
            assertFalse(OptionalNudges.hideGiftMessage(GIFT));
        });
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertFalse(OptionalNudges.hideBlogNotificationReminder());
        assertFalse(OptionalNudges.hideGiftMessage(GIFT));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.BLOG_NOTIFICATION_REMINDER));
        assertTrue(OptionalNudges.hideBlogNotificationReminder());
        assertFalse(OptionalNudges.hideGiftMessage(GIFT));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.GIFT_MESSAGE_POPUP));
        assertFalse(OptionalNudges.hideBlogNotificationReminder());
        assertTrue(OptionalNudges.hideGiftMessage(GIFT));
    }
}
