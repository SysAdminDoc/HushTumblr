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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.shared.settings.FailingStore;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * A Quick setups choice saves its switches in one commit, counts only the ones that change, and
 * notes a restart for those that need one, the same as a tap on each would.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class QuickSetupsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * Hide Tumblr TV needs a restart and starts off, Hide suggested blogs doesn't and starts off,
     * and Hide advertising ID starts on. A method, not a static field: naming Settings while this
     * class loads sets Setting up before the rule hands the extension its context.
     */
    private static List<BooleanSetting> switches() {
        return Arrays.asList(Settings.HIDE_TUMBLR_TV, Settings.HIDE_SUGGESTED_BLOGS, Settings.HIDE_ADVERTISING_ID);
    }

    @After
    public void restore() {
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        AbstractPreferenceFragment.restartPending.clear();
    }

    @Test
    public void aChoiceIsEachSwitchOnOrEachBackToItsStart() {
        Map<BooleanSetting, Boolean> on = QuickSetups.allOn(switches());
        assertEquals(switches(), new ArrayList<>(on.keySet()));
        for (Boolean value : on.values()) assertTrue(value);
        Map<BooleanSetting, Boolean> defaults = QuickSetups.defaults(switches());
        assertEquals(switches(), new ArrayList<>(defaults.keySet()));
        for (BooleanSetting setting : switches()) assertEquals(setting.key, setting.defaultValue, defaults.get(setting));
    }

    @Test
    public void savingChangesOnlyWhatDiffersAndNotesTheRestart() throws Exception {
        assertEquals("the switch already on doesn't count", 2, QuickSetups.save(QuickSetups.allOn(switches())));
        for (BooleanSetting setting : switches()) {
            assertTrue(setting.key, setting.savedValue());
            assertTrue(setting.key + " wasn't written to the store", setting.persistedValue());
        }
        assertTrue(AbstractPreferenceFragment.restartPending.contains(Settings.HIDE_TUMBLR_TV.key));
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.HIDE_SUGGESTED_BLOGS.key));

        assertEquals("a second time changes nothing", 0, QuickSetups.save(QuickSetups.allOn(switches())));

        // Back to defaults puts the restart-gated switch where Tumblr started, so nothing is owed.
        assertEquals(2, QuickSetups.save(QuickSetups.defaults(switches())));
        for (BooleanSetting setting : switches()) assertEquals(setting.key, setting.defaultValue, setting.savedValue());
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.HIDE_TUMBLR_TV.key));

        // A choice with a gap in it skips the gap.
        Map<BooleanSetting, Boolean> partial = new LinkedHashMap<>();
        partial.put(Settings.HIDE_SUGGESTED_BLOGS, null);
        partial.put(Settings.HIDE_TUMBLR_TV, true);
        assertEquals(1, QuickSetups.save(partial));
        assertFalse(Settings.HIDE_SUGGESTED_BLOGS.savedValue());
    }

    @Test
    public void aCommitThatFailsChangesNothingAndNotesNothing() {
        try (FailingStore ignored = FailingStore.install(FailingStore.Fault.COMMIT_THROWS)) {
            QuickSetups.save(QuickSetups.allOn(switches()));
            fail("a failed commit was reported as saved");
        } catch (IOException expected) {
            // Setting.saveAll put the earlier values back.
        }
        assertFalse(Settings.HIDE_TUMBLR_TV.savedValue());
        assertFalse(Settings.HIDE_TUMBLR_TV.persistedValue());
        assertFalse(Settings.HIDE_SUGGESTED_BLOGS.savedValue());
        assertFalse(AbstractPreferenceFragment.restartPending.contains(Settings.HIDE_TUMBLR_TV.key));
    }
}
