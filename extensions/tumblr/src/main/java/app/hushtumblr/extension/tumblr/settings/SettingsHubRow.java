/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import android.app.Activity;

import app.hushtumblr.extension.shared.Logger;
import app.hushtumblr.extension.shared.ResourceUtils;
import app.hushtumblr.extension.shared.Utils;

/**
 * The HushTumblr row in Tumblr's own Settings screen.
 *
 * <p>Tumblr builds that screen's rows as plain data: a kind, a title string, an icon and an
 * optional subtitle. The settings patch adds one more at the end, titled with the {@link #TITLE}
 * string it adds to Tumblr's resources, and sends two things about every row through here: the key
 * the screen's list tells rows apart by, and a tap. Tumblr keys a row by its kind's name, and the
 * HushTumblr row borrows one of Tumblr's kinds, so it gets a key of its own here. A tap on it opens
 * HushTumblr settings instead of the page that kind would open.
 *
 * <p>The row is a way into the settings, so it shows whatever HushTumblr is doing, paused included.
 */
public final class SettingsHubRow {
    private SettingsHubRow() {}

    /** The string resource the settings patch adds for the row's title. */
    static final String TITLE = "hushtumblr_settings_row";

    /** The key the screen's list holds the HushTumblr row by. Tumblr's keys are its kinds' names. */
    static final String KEY = "HUSHTUMBLR";

    /** The title's resource id once it's been found, zero until then. */
    private static volatile int titleRes;

    /**
     * Placed before Tumblr hands its list of rows back: the title of the row to add, or zero to
     * add none, which is what a build without the title string gets. Never throws.
     */
    public static int titleRes() {
        int known = titleRes;
        if (known != 0) return known;
        try {
            int found = ResourceUtils.getStringIdentifier(TITLE);
            if (found != 0) titleRes = found;
            return found;
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings row: could not look up the row's title", failure);
            return 0;
        }
    }

    /**
     * The title resource of one of Tumblr's rows. A stub: the settings patch writes its body to
     * read Tumblr's row class, whose name changes with every build. Here it answers zero, which is
     * no row's title.
     */
    static int titleOf(Object row) {
        return 0;
    }

    /** Whether [row] is the HushTumblr row. Never throws. */
    static boolean isOurs(Object row) {
        if (row == null) return false;
        try {
            int title = titleOf(row);
            return title != 0 && title == titleRes();
        } catch (Throwable failure) {
            Logger.printException(() -> "Settings row: could not read a row's title", failure);
            return false;
        }
    }

    /**
     * Placed after the screen's list asks a row for its key. The HushTumblr row gets {@link #KEY},
     * so it never shares a key with the Tumblr row whose kind it borrows; every other row keeps
     * Tumblr's.
     */
    public static Object rowKey(Object row, Object key) {
        return isOurs(row) ? KEY : key;
    }

    /**
     * Placed at the start of a row's tap. For the HushTumblr row it opens HushTumblr settings over
     * the screen in front and answers true, so Tumblr's own handling is skipped. Every other row
     * gets false and goes where Tumblr sends it.
     */
    public static boolean onClick(Object row) {
        if (!isOurs(row)) return false;
        Activity activity = Utils.getActivity();
        if (activity == null) {
            Logger.printInfo(() -> "Settings row: tapped with no Tumblr screen in front to open over");
        } else {
            SettingsEntry.open(activity);
        }
        return true;
    }
}
