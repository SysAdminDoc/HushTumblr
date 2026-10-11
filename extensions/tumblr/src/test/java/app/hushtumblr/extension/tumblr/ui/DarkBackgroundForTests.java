/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.content.Context;
import android.content.res.Resources;

import org.robolectric.RuntimeEnvironment;

/** Reaches {@link DarkBackground} from other packages' tests, with its resource lookups stood in for. */
public final class DarkBackgroundForTests {
    private DarkBackgroundForTests() {}

    /**
     * PausedHooksTest's probe for Dark background color: true when a Dark palette theme gets the
     * style. The test app has neither the patch's style nor Tumblr's palette, so a framework overlay
     * stands in for the style and every theme counts as dark.
     */
    public static boolean paintsATheme() {
        Context app = RuntimeEnvironment.getApplication();
        Resources.Theme theme = app.getResources().newTheme();
        DarkBackground.styleForTests = android.R.style.ThemeOverlay_Material_Dark;
        DarkBackground.darkForTests = ignored -> true;
        try {
            return DarkBackground.paint(theme, app.getResources(), app.getPackageName());
        } finally {
            DarkBackground.styleForTests = null;
            DarkBackground.darkForTests = null;
        }
    }
}
