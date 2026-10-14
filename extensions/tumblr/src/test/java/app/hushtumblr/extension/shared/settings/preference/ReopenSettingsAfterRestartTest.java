/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb933 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtumblr.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.settings.BaseSettings;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.shared.settings.Setting;

/**
 * The marker the restart row leaves for the next start: written before the restart, taken once,
 * cleared before anything opens, and only good for {@link ReopenSettingsAfterRestart#MAX_AGE_MILLIS}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ReopenSettingsAfterRestartTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private SharedPreferences store;

    @Before
    public void emptyStore() {
        store = RuntimeEnvironment.getApplication().getSharedPreferences("reopen-settings-test", Context.MODE_PRIVATE);
        store.edit().clear().commit();
        PauseForTests.resume();
    }

    @After
    public void restore() {
        AbstractPreferenceFragment.restartPending.clear();
        BaseSettings.PAUSED.resetToDefault();
        PauseForTests.resume();
    }

    @Test
    public void aFreshMarkerComesBackOnceAndIsGone() {
        assertTrue(ReopenSettingsAfterRestart.remember(store, "Links", 10_000));
        assertEquals("Links", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        assertMarkerGone();
        assertNull("a marker was taken twice", ReopenSettingsAfterRestart.takeFresh(store, 10_000 + 89_999));
        // The first page is a page too: an empty route opens the screen, it doesn't drop the marker.
        ReopenSettingsAfterRestart.remember(store, "", 5);
        assertEquals("", ReopenSettingsAfterRestart.takeFresh(store, 6));
    }

    @Test
    public void anOldMarkerIsDroppedAndCleared() {
        ReopenSettingsAfterRestart.remember(store, "Feed", 1_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 1_000 + ReopenSettingsAfterRestart.MAX_AGE_MILLIS));
        assertMarkerGone();
        // Written before the phone restarted, it reads as from the future, and is no good either.
        ReopenSettingsAfterRestart.remember(store, "Feed", 50_000);
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2_000));
        assertMarkerGone();
        // Half a marker, or one of the wrong type, is cleared too.
        store.edit().putLong(ReopenSettingsAfterRestart.TIME_KEY, 1).commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone();
        store.edit().putInt(ReopenSettingsAfterRestart.PAGE_KEY, 3).putString(ReopenSettingsAfterRestart.TIME_KEY, "x").commit();
        assertNull(ReopenSettingsAfterRestart.takeFresh(store, 2));
        assertMarkerGone();
        assertNull("no marker opens nothing", ReopenSettingsAfterRestart.takeFresh(store, 2));
    }

    @Test
    public void theMarkerIsClearedBeforeTheScreenOpens() {
        ReopenSettingsAfterRestart.remember(store, "Pause, backup and diagnostics", 100);
        List<String> opened = new ArrayList<>();
        assertTrue(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> {
            assertMarkerGone();
            opened.add(page);
        }));
        assertEquals(java.util.Collections.singletonList("Pause, backup and diagnostics"), opened);

        // A screen that fails to open isn't tried again on the next start.
        ReopenSettingsAfterRestart.remember(store, "Feed", 100);
        try {
            ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> { throw new IllegalStateException("no activity"); });
            fail("the opener's failure was swallowed");
        } catch (IllegalStateException expected) {
            assertMarkerGone();
        }
        assertFalse(ReopenSettingsAfterRestart.reopenIfPending(store, 200, page -> fail("opened " + page)));
    }

    @Test
    public void theRestartRowWritesTheMarkerThenRestarts() {
        List<Context> restarted = new ArrayList<>();
        ReopenSettingsAfterRestart.setRestarterForTests(context -> {
            // Written and committed before the restart, which ends the process.
            assertTrue(Setting.preferences.preferences.contains(ReopenSettingsAfterRestart.PAGE_KEY));
            restarted.add(context);
        });
        Context app = RuntimeEnvironment.getApplication();
        ReopenSettingsAfterRestart.restartInto(app, "Links");
        assertEquals(java.util.Collections.singletonList(app), restarted);
        assertEquals("Links", ReopenSettingsAfterRestart.takeFresh(Setting.preferences.preferences, SystemClock.elapsedRealtime()));
    }

    /**
     * Both of the marker's commits are on the main thread on purpose: one right before the process
     * ends, one at the start that finds it. With debug logging on each says once how long it took.
     * A start with no marker commits nothing and says nothing.
     */
    @Test
    public void theMarkersCommitsSayHowLongTheyTookOnlyWithDebugLoggingOn() {
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearLogBuffer();
        ReopenSettingsAfterRestart.remember(store, "Feed", 100);
        assertEquals("Feed", ReopenSettingsAfterRestart.takeFresh(store, 200));
        String quiet = LogBufferManager.buildExportText();
        assertFalse(quiet, quiet.contains("Settings restart marker"));

        BaseSettings.DEBUG.save(true);
        try {
            assertNull(ReopenSettingsAfterRestart.takeFresh(store, 300));
            String none = LogBufferManager.buildExportText();
            assertFalse("a start with no marker logged a commit", none.contains("Settings restart marker"));

            ReopenSettingsAfterRestart.remember(store, "Feed", 400);
            assertEquals("Feed", ReopenSettingsAfterRestart.takeFresh(store, 500));
            String timed = LogBufferManager.buildExportText();
            assertEquals(timed, 1, timed.split("Settings restart marker written in \\d+ ms", -1).length - 1);
            assertEquals(timed, 1, timed.split("Settings restart marker taken in \\d+ ms", -1).length - 1);
        } finally {
            BaseSettings.DEBUG.resetToDefault();
            LogBufferManager.clearLogBuffer();
            LogBufferManager.clearLogBuffer();
        }
    }

    @Test
    public void changesWaitingCountsSwitchesAndAPauseOnce() {
        Context app = RuntimeEnvironment.getApplication();
        assertEquals(0, ReopenSettingsAfterRestart.changesWaiting(app));
        AbstractPreferenceFragment.restartPending.add("hushtumblr_hide_tumblr_tv");
        AbstractPreferenceFragment.restartPending.add("hushtumblr_fix_push_notifications");
        assertEquals(2, ReopenSettingsAfterRestart.changesWaiting(app));
        // Pause switched on: its key is owed, and the next start differs from this one. One change.
        BaseSettings.PAUSED.save(true);
        AbstractPreferenceFragment.restartPending.add(BaseSettings.PAUSED.key);
        assertEquals(3, ReopenSettingsAfterRestart.changesWaiting(app));
        AbstractPreferenceFragment.restartPending.clear();
        assertEquals("a pause the switch didn't record still counts", 1, ReopenSettingsAfterRestart.changesWaiting(app));
        BaseSettings.PAUSED.save(false);
        assertEquals(0, ReopenSettingsAfterRestart.changesWaiting(app));
    }

    private void assertMarkerGone() {
        assertFalse(store.contains(ReopenSettingsAfterRestart.PAGE_KEY));
        assertFalse(store.contains(ReopenSettingsAfterRestart.TIME_KEY));
    }
}
