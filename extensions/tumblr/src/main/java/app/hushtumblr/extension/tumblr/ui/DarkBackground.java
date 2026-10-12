/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.app.Activity;
import android.content.res.Resources;
import android.util.TypedValue;

import androidx.annotation.Nullable;

import java.util.function.Predicate;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * Paints the background of Tumblr's Dark palette in the color picked when patching, black unless
 * another was chosen.
 *
 * <p>The patch adds a style, {@link #STYLE}, that sets the palette's background attributes to that
 * color. Tumblr's screens drawn with Views take their colors from the activity's theme, which
 * Tumblr sets in one place; its Compose screens build their colors from a theme of their own. Both
 * call in here right after, and while the switch is on the style goes over the theme, so every
 * color read from it afterwards is the new one. Only the Dark palette is touched, the one whose
 * screen color is Tumblr's {@code dark_theme_primary_color}, which is also what the System palette
 * picks at night. Every other palette, light ones included, stays as Tumblr made it.
 *
 * <p>A theme a screen already drew with keeps its colors, so the summary says a restart shows it.
 */
public final class DarkBackground {
    private DarkBackground() {}

    /** The style the patch adds. Its name is the patch's to choose, so it's looked up by name. */
    static final String STYLE = "HushTumblrDarkBackground";

    /** The theme attribute for the screen color, and the Dark palette's color resource behind it. */
    static final String SCREEN_ATTRIBUTE = "themePrimaryColor";
    static final String DARK_SCREEN_COLOR = "dark_theme_primary_color";

    /**
     * Resource ids looked up once: the style, the attribute and the color. Zero for a missing one.
     * Null until the first lookup; a test clears it to look again.
     */
    @Nullable
    static volatile int[] ids;

    /** Stand in for the resource lookups in a test. Null in the app. */
    @Nullable
    static volatile Integer styleForTests;
    @Nullable
    static volatile Predicate<Resources.Theme> darkForTests;

    /** Placed right after Tumblr sets an activity's theme. Never throws. */
    public static void onActivityTheme(@Nullable Activity activity) {
        if (activity == null || !Utils.settingsReady()) return;
        try {
            paint(activity.getTheme(), activity.getResources(), activity.getPackageName());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DARK_BACKGROUND, "activity theme", failure);
        }
    }

    /** Placed right after Tumblr's Compose colors make the theme they read from. Never throws. */
    public static void onComposeTheme(@Nullable Resources.Theme theme) {
        if (theme == null || !Utils.settingsReady()) return;
        try {
            paint(theme, theme.getResources(), Utils.getContext().getPackageName());
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.DARK_BACKGROUND, "Compose theme", failure);
        }
    }

    /**
     * Puts the style over [theme] when the switch is on and [theme] is the Dark palette's. True when
     * it did. Off, paused, before the settings are ready, on another palette, or without the style,
     * the theme is left alone. The readiness check is here, where the switch is read, so an entry
     * added later can't reach the switch without it.
     */
    static boolean paint(Resources.Theme theme, Resources resources, String packageName) {
        if (!Utils.settingsReady()) return false;
        if (!PatchFamily.Capability.THEME_BACKGROUND.installed()) return false;
        HookStatus.invoked(FamilyNames.DARK_BACKGROUND);
        if (!Settings.DARK_BACKGROUND.get()) return false;
        Integer forcedStyle = styleForTests;
        int[] found = forcedStyle == null ? ids(resources, packageName) : new int[] {forcedStyle, 0, 0};
        if (found[0] == 0) return false;
        Predicate<Resources.Theme> forcedDark = darkForTests;
        boolean dark = forcedDark != null ? forcedDark.test(theme) : isDark(theme, found[1], found[2]);
        if (!dark) return false;
        theme.applyStyle(found[0], true);
        HookStatus.counted(FamilyNames.DARK_BACKGROUND, "dark theme painted");
        return true;
    }

    /** Whether [theme]'s screen color is the Dark palette's own. */
    static boolean isDark(Resources.Theme theme, int attribute, int darkColor) {
        if (attribute == 0 || darkColor == 0) return false;
        TypedValue value = new TypedValue();
        return theme.resolveAttribute(attribute, value, true) && value.resourceId == darkColor;
    }

    // The style is the patch's, and the palette's names are kept, so neither has an R field here.
    @SuppressWarnings("DiscouragedApi")
    private static int[] ids(Resources resources, String packageName) {
        int[] known = ids;
        if (known != null) return known;
        int style = resources.getIdentifier(STYLE, "style", packageName);
        int attribute = resources.getIdentifier(SCREEN_ATTRIBUTE, "attr", packageName);
        int color = resources.getIdentifier(DARK_SCREEN_COLOR, "color", packageName);
        if (style == 0) HookStatus.missingMember(FamilyNames.DARK_BACKGROUND, "style", "resources", STYLE);
        if (attribute == 0) HookStatus.missingMember(FamilyNames.DARK_BACKGROUND, "attr", "resources", SCREEN_ATTRIBUTE);
        if (color == 0) HookStatus.missingMember(FamilyNames.DARK_BACKGROUND, "color", "resources", DARK_SCREEN_COLOR);
        known = new int[] {style, attribute, color};
        ids = known;
        return known;
    }
}
