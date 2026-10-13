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

import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.shared.settings.Setting;

/**
 * Quick setups: a set of switches flipped in one tap. Each choice is a map from switch to the
 * value it puts there; which switches go in which choice is the app's call, in its own class.
 *
 * <p>A switch row on this screen only saves through its page's worker, so a choice isn't applied
 * by setting rows. {@link #shown} keeps the switches this build has a row for, on the main thread,
 * and {@link #save} writes them in one commit on the worker, the way an import does, then notes a
 * restart for each switch that needs one. The page refreshes its rows afterwards. A switch whose
 * patch isn't in this build has no row and is left alone.
 */
@SuppressWarnings("deprecation")
public final class QuickSetups {
    private QuickSetups() {
    }

    /** Each of [settings] turned on. */
    public static Map<BooleanSetting, Boolean> allOn(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, Boolean.TRUE);
        return Collections.unmodifiableMap(values);
    }

    /** Each of [settings] back to the value it started with. */
    public static Map<BooleanSetting, Boolean> defaults(List<BooleanSetting> settings) {
        Map<BooleanSetting, Boolean> values = new LinkedHashMap<>();
        for (BooleanSetting setting : settings) values.put(setting, setting.defaultValue);
        return Collections.unmodifiableMap(values);
    }

    /** The part of [values] whose switch has a row on [screen]. Read on the main thread, where the rows live. */
    public static Map<BooleanSetting, Boolean> shown(PreferenceGroup screen, Map<BooleanSetting, Boolean> values) {
        Map<BooleanSetting, Boolean> kept = new LinkedHashMap<>();
        for (Map.Entry<BooleanSetting, Boolean> value : values.entrySet()) {
            if (value.getKey() == null || value.getValue() == null) continue;
            Preference row = screen.findPreference(value.getKey().key);
            if (row instanceof TwoStatePreference) kept.put(value.getKey(), value.getValue());
        }
        return Collections.unmodifiableMap(kept);
    }

    /**
     * Saves each of [values] that holds something else, in one commit, notes a restart for the
     * changed switches that need one, and answers how many changed. A switch that already holds its
     * value doesn't count. Call on a worker.
     *
     * @throws IOException when the commit didn't land. Nothing is noted then, and the store keeps
     *                     what {@link Setting#saveAll} could put back.
     */
    public static int save(Map<BooleanSetting, Boolean> values) throws IOException {
        synchronized (Setting.class) {
            Map<Setting<?>, Object> changes = new LinkedHashMap<>();
            Map<Setting<?>, Object> before = new LinkedHashMap<>();
            for (Map.Entry<BooleanSetting, Boolean> value : values.entrySet()) {
                BooleanSetting setting = value.getKey();
                Boolean wanted = value.getValue();
                if (setting == null || wanted == null) continue;
                Boolean was = setting.persistedValue();
                if (wanted.equals(was)) continue;
                changes.put(setting, wanted);
                before.put(setting, was);
            }
            if (changes.isEmpty()) return 0;
            Setting.saveAll(changes);
            AbstractPreferenceFragment.recordRestartPendingChanges(before);
            return changes.size();
        }
    }
}
