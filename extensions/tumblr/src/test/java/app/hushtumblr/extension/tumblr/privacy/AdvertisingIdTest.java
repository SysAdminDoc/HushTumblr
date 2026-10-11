/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.EnumSet;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AdvertisingIdTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String REAL = "38400000-8cf0-11bd-b23e-10b96e40000d";

    @Before public void installedBuild() throws ReflectiveOperationException {
        installed(true);
        HookStatus.clear();
    }

    @After public void restore() throws ReflectiveOperationException {
        HookStatus.clear();
        PauseForTests.resume();
        Settings.HIDE_ADVERTISING_ID.resetToDefault();
        installed(null);
    }

    @Test public void readersGetTheDeletedIdAnswerWhileTheSwitchIsOn() {
        assertEquals("00000000-0000-0000-0000-000000000000", AdvertisingId.id(REAL));
        assertEquals(AdvertisingId.ZERO, AdvertisingId.id(null));
        assertTrue(AdvertisingId.limitTracking(false));
        assertTrue(AdvertisingId.limitTracking(true));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("advertising ID read 2"));
        assertTrue(report, report.contains("ad tracking limit read 2"));
    }

    @Test public void pausedDisabledColdStartAndMissingBuildsHandBackTheRealAnswer() throws ReflectiveOperationException {
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertFalse(AdvertisingId.limitTracking(false));
        PauseForTests.resume();
        Settings.HIDE_ADVERTISING_ID.save(false);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertNull(AdvertisingId.id(null));
        assertFalse(AdvertisingId.limitTracking(false));
        Settings.HIDE_ADVERTISING_ID.save(true);
        SettingsContextRule.withoutContext(() -> assertEquals(REAL, AdvertisingId.id(REAL)));
        installed(false);
        assertEquals(REAL, AdvertisingId.id(REAL));
        assertFalse(AdvertisingId.limitTracking(false));
    }

    private static void installed(Boolean present) throws ReflectiveOperationException {
        Field field = PatchFamily.class.getDeclaredField("inBuildForTests");
        field.setAccessible(true);
        field.set(null, present == null ? null : present ? EnumSet.of(PatchFamily.HIDE_ADVERTISING_ID) : Collections.emptySet());
    }
}
