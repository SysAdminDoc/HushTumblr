/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.shared.SettingsContextRule;

/**
 * Tumblr's own rows go through the row hooks untouched. The HushTumblr row itself needs the title
 * string the patch adds and the title reader it writes, so the fixture test and a device check hold
 * that half.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsHubRowTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test
    public void tumblrsRowsKeepTheirKeysAndTheirTaps() {
        Object row = new Object();
        Object key = "PREFERENCES";
        assertSame(key, SettingsHubRow.rowKey(row, key));
        assertSame(key, SettingsHubRow.rowKey(null, key));
        assertFalse(SettingsHubRow.onClick(row));
        assertFalse(SettingsHubRow.onClick(null));
    }

    @Test
    public void withoutTheTitleStringNoRowIsAdded() {
        assertEquals(0, SettingsHubRow.titleRes());
    }
}
