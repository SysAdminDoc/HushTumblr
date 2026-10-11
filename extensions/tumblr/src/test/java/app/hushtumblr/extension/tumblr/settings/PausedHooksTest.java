/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Copyright 2026 HushThreads contributors
 * https://github.com/SysAdminDoc/HushThreads
 *
 * Built on SysAdminDoc/Hushfacebook (GPL-3.0).
 */
package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.hushtumblr.extension.tumblr.flags.FeatureSwitches;
import app.hushtumblr.extension.tumblr.flags.FeatureSwitchesTest;
import app.hushtumblr.extension.tumblr.notifications.PushNotificationsTest;
import app.hushtumblr.extension.tumblr.privacy.AdvertisingId;
import app.hushtumblr.extension.tumblr.timeline.TimelineItems;
import app.hushtumblr.extension.tumblr.ui.PostParts;
import app.hushtumblr.extension.tumblr.ui.ReviewPrompts;
import app.hushtumblr.extension.tumblr.ui.StoreButtons;
import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BaseSettings;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;

/**
 * What Pause and safe mode promise: every hook a switch runs takes Tumblr's own path, and every
 * saved value stays as it is.
 *
 * <p>Each probe is one hook with its switch on. It must change what Tumblr does while HushTumblr
 * runs, which is the control, and leave it alone while paused. A family that gains a switch
 * without a probe here fails the first test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PausedHooksTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** One hook with its switch on: true when it changed what Tumblr would have done. */
    interface Probe {
        boolean changedTumblr();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        for (BooleanSetting setting : settingsSwitches()) setting.resetToDefault();
        PatchFamily.capabilitiesForTests = null;
        PatchFamily.inBuildForTests = null;
        ReleaseCheckForTests.forget();
        Utils.setActivity(null);
        HookStatus.clear();
    }

    /**
     * The settings entry's own switches, which no family owns, one probe each, held to the same
     * promise as a family's: paused, or before the settings are ready, a start makes no request.
     */
    private static Map<BooleanSetting, Probe> entryProbes() {
        Map<BooleanSetting, Probe> probes = new LinkedHashMap<>();
        // Loads the check's own settings here, with the context, so a probe run without one reads
        // them rather than loading them.
        ReleaseCheck.Stored.CHECKED_AT.savedValue();
        // A Tumblr start a day after the last try asks GitHub for the newest release.
        probes.put(Settings.CHECK_FOR_RELEASES, ReleaseCheckForTests::aStartAsksGitHub);
        return probes;
    }

    private static Map<BooleanSetting, List<Probe>> probes() {
        // Every hook is in this build, so each hook reads its family's switch.
        PatchFamily.capabilitiesForTests = EnumSet.allOf(PatchFamily.Capability.class);
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        Map<BooleanSetting, List<Probe>> probes = new LinkedHashMap<>();
        // A page with an ad comes back without it, and Tumblr's own ad requests are turned off.
        probes.put(Settings.HIDE_ADS, Arrays.asList(
                TimelineItems::aPageLosesItsAd,
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("MAKE_ADX_REQUESTS"), true)));
        // Both getters of Google's advertising ID info answer as if the ID were deleted.
        probes.put(Settings.HIDE_ADVERTISING_ID, Arrays.asList(
                () -> !"real".equals(AdvertisingId.id("real")),
                () -> AdvertisingId.limitTracking(false)));
        // Tumblr's own tracking flags answer off.
        probes.put(Settings.DISABLE_ANALYTICS, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("LIVERAMP_ATS"), true)));
        // The dashboard's Ad-Free Browsing banner flag answers off.
        probes.put(Settings.HIDE_AD_FREE_BANNER, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("AD_FREE_CTA_BANNER"), true)));
        // The Tumblr TV tab's flag answers off.
        probes.put(Settings.HIDE_TUMBLR_TV, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("TUMBLR_TV_MOBILE_NAV"), true)));
        // The Communities tab's flag answers off.
        probes.put(Settings.HIDE_COMMUNITIES_TAB, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("COMMUNITIES_TAB_BAR_ITEM"), true)));
        // The Blaze button in your blog's header answers off.
        probes.put(Settings.HIDE_BLAZE_PROMPTS, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("BLAZE_DASHBOARD_ENTRYPOINT_IN_USER_BLOG_HEADER"), true)));
        // Tumblr's rating prompt is skipped.
        probes.put(Settings.SKIP_REVIEW_PROMPTS, Collections.singletonList(ReviewPrompts::skip));
        // Tumblr's screenshot watcher answers off.
        probes.put(Settings.TURN_OFF_SCREENSHOT_SHARING, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("SCREENSHOT_INTERCEPTION"), true)));
        // Tumblr's in-app update check answers off.
        probes.put(Settings.DISABLE_UPDATE_PROMPTS, Collections.singletonList(
                () -> !FeatureSwitches.isEnabled(FeatureSwitchesTest.flag("IN_APP_UPDATE"), true)));
        // A page with a suggested blog card comes back without it.
        probes.put(Settings.HIDE_SUGGESTED_BLOGS, Collections.singletonList(TimelineItems::aPageLosesItsBlogCard));
        // A page with a suggested community card comes back without it.
        probes.put(Settings.HIDE_SUGGESTED_COMMUNITIES, Collections.singletonList(TimelineItems::aPageLosesItsCommunityCard));
        // A page with a Watch it row comes back without it.
        probes.put(Settings.HIDE_WATCH_IT_ROWS, Collections.singletonList(TimelineItems::aPageLosesItsWatchItRow));
        // A post's tags are left out.
        probes.put(Settings.HIDE_HASHTAGS, Collections.singletonList(PostParts::hideTags));
        // A post header's Follow button is hidden.
        probes.put(Settings.HIDE_FOLLOW_BUTTON, Collections.singletonList(PostParts::hideFollowButton));
        // The Premium diamond is hidden.
        probes.put(Settings.HIDE_TUMBLRMART, Collections.singletonList(() -> StoreButtons.hideDiamond(null, null)));
        // Firebase's sign-up for push notifications names Tumblr's own certificate.
        probes.put(Settings.FIX_PUSH_NOTIFICATIONS, Collections.singletonList(PushNotificationsTest::fixesACertificate));
        return probes;
    }

    /** Every switch the settings screen can show, read off the class so a new one can't hide. */
    static List<BooleanSetting> settingsSwitches() {
        List<BooleanSetting> switches = new ArrayList<>();
        for (Field field : Settings.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != BooleanSetting.class) continue;
            try {
                switches.add((BooleanSetting) field.get(null));
            } catch (IllegalAccessException unreadable) {
                throw new AssertionError(unreadable);
            }
        }
        return switches;
    }

    private static Set<BooleanSetting> familySwitches() {
        Set<BooleanSetting> switched = new HashSet<>();
        for (PatchFamily family : PatchFamily.values()) {
            switched.addAll(family.switches);
        }
        return switched;
    }

    /** Adds a line to [wrong] for every probe that didn't answer [changes]. */
    private static void everyProbe(Map<BooleanSetting, List<Probe>> probes, boolean changes, String when,
                                   List<String> wrong) {
        for (Map.Entry<BooleanSetting, List<Probe>> entry : probes.entrySet()) {
            for (int i = 0; i < entry.getValue().size(); i++) {
                if (entry.getValue().get(i).changedTumblr() != changes) {
                    wrong.add(entry.getKey().key + ", probe " + i + ", " + when
                            + (changes ? ": left Tumblr alone" : ": still changed Tumblr"));
                }
            }
        }
    }

    /** Adds a line to [wrong] for every entry probe that didn't answer [changes]. */
    private static void everyEntryProbe(Map<BooleanSetting, Probe> probes, boolean changes, String when,
                                        List<String> wrong) {
        for (Map.Entry<BooleanSetting, Probe> entry : probes.entrySet()) {
            if (entry.getValue().changedTumblr() != changes) {
                wrong.add(entry.getKey().key + ", " + when + (changes ? ": left Tumblr alone" : ": still changed Tumblr"));
            }
        }
    }

    @Test
    public void everyHookASwitchRunsTakesTumblrsOwnPathWhilePaused() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<BooleanSetting, List<Probe>> probes = probes();
        assertEquals("every family's switch needs a probe here", familySwitches(), probes.keySet());
        Map<BooleanSetting, Probe> entry = entryProbes();
        assertEquals("every switch of the settings entry needs a probe here",
                new HashSet<>(PatchFamily.ENTRY_SWITCHES), entry.keySet());

        // Every hook is asked every time, so one run names every hook that broke the promise.
        List<String> wrong = new ArrayList<>();
        everyProbe(probes, true, "running", wrong);
        everyEntryProbe(entry, true, "running", wrong);

        for (HushTumblrPause.Reason why : new HushTumblrPause.Reason[]{
                HushTumblrPause.Reason.SWITCH, HushTumblrPause.Reason.CRASH_LOOP,
                HushTumblrPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            everyProbe(probes, false, "paused by " + why, wrong);
            everyEntryProbe(entry, false, "paused by " + why, wrong);
        }

        PauseForTests.resume();
        everyProbe(probes, true, "running again", wrong);
        everyEntryProbe(entry, true, "running again", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    /** With its switch off, each hook leaves Tumblr alone too, so a probe can't pass by luck. */
    @Test
    public void everyHookLeavesTumblrAloneWithItsSwitchOff() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(false);
        List<String> wrong = new ArrayList<>();
        everyProbe(probes(), false, "switched off", wrong);
        everyEntryProbe(entryProbes(), false, "switched off", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    @Test
    public void eachHookReadsItsOwnSwitchWhenEveryOtherSwitchIsOff() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(false);
        Map<BooleanSetting, List<Probe>> probes = probes();
        assertEquals("every family's switch needs a probe here", familySwitches(), probes.keySet());
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<BooleanSetting, List<Probe>> entry : probes.entrySet()) {
            entry.getKey().save(true);
            everyProbe(Collections.singletonMap(entry.getKey(), entry.getValue()), true, "its switch alone is on", wrong);
            entry.getKey().save(false);
        }
        assertEquals(Collections.emptyList(), wrong);
    }

    /**
     * Tumblr can call a hook before its application's onCreate hands HushTumblr the context,
     * from a thread it starts early, and again while setContext is still deciding whether this
     * start runs paused. Until both are done, every hook takes Tumblr's own path whatever is
     * saved (see Utils.settingsReady). This JVM's Setting class loaded with a context, so a hook
     * that reads its switch anyway answers on here and is named.
     */
    @Test
    public void untilTheSettingsAreReadyEveryHookTakesTumblrsOwnPath() {
        for (BooleanSetting setting : settingsSwitches()) setting.save(true);
        Map<BooleanSetting, List<Probe>> probes = probes();
        assertEquals("every family's switch needs a probe here", familySwitches(), probes.keySet());
        Map<BooleanSetting, Probe> entry = entryProbes();

        List<String> wrong = new ArrayList<>();
        SettingsContextRule.withoutContext(() -> {
            everyProbe(probes, false, "before the context is set", wrong);
            everyEntryProbe(entry, false, "before the context is set", wrong);
        });
        // Safe mode on, as after three crashed starts: the context is set and the pause undecided.
        BaseSettings.SAFE_MODE.save(true);
        try {
            SettingsContextRule.beforeThePauseIsDecided(() -> {
                everyProbe(probes, false, "before the pause is decided", wrong);
                everyEntryProbe(entry, false, "before the pause is decided", wrong);
            });
        } finally {
            BaseSettings.SAFE_MODE.resetToDefault();
        }
        everyProbe(probes, true, "once they're ready", wrong);
        everyEntryProbe(entry, true, "once they're ready", wrong);
        assertEquals(Collections.emptyList(), wrong);
    }

    @Test
    public void pausedEverySwitchAnswersOffAndKeepsWhatWasSaved() {
        List<BooleanSetting> switches = settingsSwitches();
        assertFalse("found no switches to check", switches.isEmpty());
        for (BooleanSetting setting : switches) setting.save(true);

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        for (BooleanSetting setting : switches) {
            assertFalse(setting.key + " answered on while paused", setting.get());
            assertTrue(setting.key + " lost what was saved", setting.savedValue());
        }

        PauseForTests.resume();
        for (BooleanSetting setting : switches) {
            assertTrue(setting.key + " stayed off after the pause ended", setting.get());
        }
    }

    /**
     * The Pause row and the paused card say Debug logging keeps working, which is how a paused
     * start gets logged for a report.
     */
    @Test
    public void debugLoggingKeepsWorkingWhilePaused() {
        BaseSettings.DEBUG.save(true);
        try {
            for (HushTumblrPause.Reason why : HushTumblrPause.Reason.values()) {
                if (why == HushTumblrPause.Reason.NONE) continue;
                PauseForTests.pause(why);
                assertTrue("Debug logging answered off while paused by " + why, BaseSettings.DEBUG.get());
            }
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }
}
