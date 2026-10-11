/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.hushtumblr.extension.shared.SettingsContextRule;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class NativeControlsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /**
     * Two paths in the shape the rows take. The step names are stand-ins: the real ones are read
     * from the declared Tumblr build when its paths are walked.
     */
    private static final NativeControls.Control THEME = new NativeControls.Control("App theme",
            "Light, dark or the phone's own theme.",
            new NativeControls.Step("settings_title", "Settings"),
            new NativeControls.Step("account_title", "Account"),
            new NativeControls.Step("theme_title", "App theme"));
    private static final NativeControls.Control NOTIFICATIONS = new NativeControls.Control("Notifications",
            "Which activity on your blogs sends a notification.",
            new NativeControls.Step("settings_title", "Settings"),
            new NativeControls.Step("notifications_title", "Notifications"));

    /** No row names a path nobody has walked in the declared build. */
    @Test public void noPathIsListedBeforeItIsWalkedInTheDeclaredBuild() {
        assertEquals(Collections.emptyList(), NativeControls.controls());
    }

    @Test public void eachStepFallsBackToHushTumblrsEnglishWhenTheBuildHasNoSuchLabel() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("Settings > Account > App theme", NativeControls.path(context, THEME));
        assertEquals(NOTIFICATIONS.covers + "\nIn Tumblr: \u2068Settings > Notifications\u2069",
                NativeControls.summary(context, NOTIFICATIONS));
    }

    @Test public void tumblrsOwnLabelWinsWhereTheBuildHasAUsableOne() {
        Map<String, String> strings = new HashMap<>();
        strings.put("settings_title", " Einstellungen ");
        // Blank, overlong or several lines: not a menu label, so HushTumblr's copy stands in.
        strings.put("account_title", "   ");
        strings.put("theme_title", new String(new char[81]).replace('\0', 'x'));
        strings.put("notifications_title", "Benachrichtigungen\nund mehr");
        Context context = withStrings(RuntimeEnvironment.getApplication(), strings);
        assertEquals("Einstellungen > Account > App theme", NativeControls.path(context, THEME));
        assertEquals("Einstellungen > Notifications", NativeControls.path(context, NOTIFICATIONS));
        strings.put("notifications_title", "Benachrichtigungen");
        assertEquals("Einstellungen > Benachrichtigungen", NativeControls.path(context, NOTIFICATIONS));
        assertEquals("Settings", NativeControls.label(null, new NativeControls.Step("settings_title", "Settings")));
    }

    /** [base] with Tumblr's strings by name answering from [strings], and every other name missing. */
    private static Context withStrings(Context base, Map<String, String> strings) {
        List<String> names = new ArrayList<>(strings.keySet());
        Resources real = base.getResources();
        @SuppressWarnings("deprecation")
        Resources fake = new Resources(real.getAssets(), real.getDisplayMetrics(), real.getConfiguration()) {
            @Override
            public int getIdentifier(String name, String defType, String defPackage) {
                return "string".equals(defType) && names.contains(name) ? 0x7f990000 + names.indexOf(name) : 0;
            }

            @Override
            public String getString(int id) {
                int index = id - 0x7f990000;
                if (index < 0 || index >= names.size()) throw new Resources.NotFoundException("no string " + id);
                return strings.get(names.get(index));
            }
        };
        return new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return fake;
            }
        };
    }
}
