/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.EnumSet;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * When Dark background color puts its style over a theme. The test app has neither the patch's
 * style nor Tumblr's palette, so a framework dark overlay stands in for the style, and a light
 * framework theme shows whether it went on.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class DarkBackgroundTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        DarkBackground.ids = null;
        HookStatus.clear();
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        DarkBackground.styleForTests = null;
        DarkBackground.darkForTests = null;
        DarkBackground.ids = null;
        Settings.DARK_BACKGROUND.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchPaintsADarkModeTheme() {
        Settings.DARK_BACKGROUND.save(true);
        Resources.Theme theme = lightTheme();
        int before = background(theme);
        standIn(true);

        assertTrue(DarkBackground.paint(theme, app().getResources(), app().getPackageName()));
        assertNotEquals("the style went over the theme", before, background(theme));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("dark theme painted 1"));
    }

    @Test
    public void theSwitchStartsOffSoTheThemeStaysTumblrs() {
        Resources.Theme theme = lightTheme();
        int before = background(theme);
        standIn(true);

        assertFalse(DarkBackground.paint(theme, app().getResources(), app().getPackageName()));
        assertEquals(before, background(theme));
    }

    @Test
    public void anotherPaletteIsLeftAlone() {
        Settings.DARK_BACKGROUND.save(true);
        Resources.Theme theme = lightTheme();
        int before = background(theme);
        standIn(false);

        assertFalse(DarkBackground.paint(theme, app().getResources(), app().getPackageName()));
        assertEquals(before, background(theme));
    }

    @Test
    public void aBuildWithoutTheHookLeavesTheThemeAlone() {
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        Settings.DARK_BACKGROUND.save(true);
        Resources.Theme theme = lightTheme();
        standIn(true);

        assertFalse(DarkBackground.paint(theme, app().getResources(), app().getPackageName()));
    }

    @Test
    public void withoutThePatchStyleNothingIsPaintedAndTheReportSaysWhy() {
        Settings.DARK_BACKGROUND.save(true);
        Resources.Theme theme = lightTheme();
        int before = background(theme);

        assertFalse(DarkBackground.paint(theme, app().getResources(), app().getPackageName()));
        assertEquals(before, background(theme));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(DarkBackground.STYLE));
    }

    /** Dark Mode is told apart by the color resource its screen attribute points at. */
    @Test
    public void aThemeIsDarkWhenItsScreenColorIsTheDarkPalettes() {
        Resources.Theme theme = app().getResources().newTheme();
        theme.applyStyle(android.R.style.Theme_Material, true);
        TypedValue value = new TypedValue();
        assertTrue(theme.resolveAttribute(android.R.attr.colorBackground, value, true));
        int color = value.resourceId;
        assertNotEquals("the framework's dark background is a color resource", 0, color);

        assertTrue(DarkBackground.isDark(theme, android.R.attr.colorBackground, color));
        assertFalse("another color is another palette", DarkBackground.isDark(theme, android.R.attr.colorBackground, color + 1));
        assertFalse("a missing attribute is never dark", DarkBackground.isDark(theme, 0, color));
        assertFalse("a missing color is never dark", DarkBackground.isDark(theme, android.R.attr.colorBackground, 0));
    }

    @Test
    public void theHooksTakeNothingQuietly() {
        Settings.DARK_BACKGROUND.save(true);
        DarkBackground.onActivityTheme(null);
        DarkBackground.onComposeTheme(null);
        assertFalse(String.join("\n", HookStatus.report()).contains("painted"));
    }

    private static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    private static Resources.Theme lightTheme() {
        Resources.Theme theme = app().getResources().newTheme();
        theme.applyStyle(android.R.style.Theme_Material_Light, true);
        return theme;
    }

    private static int background(Resources.Theme theme) {
        TypedValue value = new TypedValue();
        theme.resolveAttribute(android.R.attr.colorBackground, value, true);
        return value.data;
    }

    private static void standIn(boolean dark) {
        DarkBackground.styleForTests = android.R.style.ThemeOverlay_Material_Dark;
        DarkBackground.darkForTests = ignored -> dark;
    }
}
