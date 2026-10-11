/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.view.View;

import androidx.annotation.Nullable;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * Keeps the Premium diamond and the TumblrMart button out of Tumblr's top bars.
 *
 * <p>One method draws the diamond and its credit dot for the dashboard, Explore and notifications.
 * {@link #hideDiamond} goes first in it: it hides both views and the method returns before it sets
 * them up, so the diamond has no tap either. The TumblrMart button is looked up and shown on each of
 * those screens when its view is made, and {@link #hideTumblrMart} goes right after Tumblr sets its
 * visibility. The diamond is drawn again each time the screen comes back; the TumblrMart button only
 * when the screen is made, so a change shows fully after Tumblr restarts.
 *
 * <p>Explore's search bar narrows as its header collapses, to leave room for the icons beside it,
 * and Tumblr works out that room from its own feature flags rather than from what's shown.
 * {@link #tumblrMartRoom} and {@link #diamondRoom} take the hidden buttons' share out of it, so the
 * bar doesn't stop short of an empty space.
 */
public final class StoreButtons {
    private StoreButtons() {}

    /**
     * Injected at the start of the method that draws the diamond. Hides both views and answers
     * true while the switch is on. False with the switch off, paused, or before HushTumblr has its
     * settings, and Tumblr draws the diamond as usual. Never throws.
     */
    public static boolean hideDiamond(@Nullable View diamond, @Nullable View dot) {
        // Tumblr can draw its top bar while it starts, before HushTumblr may read a switch.
        if (!Utils.settingsReady()) return false;
        try {
            if (!PatchFamily.Capability.PREMIUM_DIAMOND.installed()) return false;
            HookStatus.invoked(FamilyNames.HIDE_TUMBLRMART);
            if (!Settings.HIDE_TUMBLRMART.get()) return false;
            if (diamond != null) diamond.setVisibility(View.GONE);
            if (dot != null) dot.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.HIDE_TUMBLRMART, "diamond hidden");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_TUMBLRMART, "diamond", failure);
            return false;
        }
    }

    /**
     * Injected where Explore's header has worked out the room its search bar leaves for the
     * TumblrMart button once the header collapses. None while the switch hides the button, so the
     * bar reaches the icons that are left. The room itself otherwise. Never throws.
     */
    public static int tumblrMartRoom(int room) {
        return room(room, PatchFamily.Capability.TUMBLRMART_BUTTONS);
    }

    /** {@link #tumblrMartRoom} for the diamond's room. */
    public static int diamondRoom(int room) {
        return room(room, PatchFamily.Capability.PREMIUM_DIAMOND);
    }

    /** No room while the switch hides the button [hook] hides, [room] otherwise. */
    private static int room(int room, PatchFamily.Capability hook) {
        if (room == 0 || !Utils.settingsReady()) return room;
        try {
            if (!hook.installed()) return room;
            HookStatus.invoked(FamilyNames.HIDE_TUMBLRMART);
            if (!Settings.HIDE_TUMBLRMART.get()) return room;
            HookStatus.counted(FamilyNames.HIDE_TUMBLRMART, "search bar room freed");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_TUMBLRMART, "search bar", failure);
            return room;
        }
    }

    /**
     * Injected right after Tumblr sets the TumblrMart button's visibility. Hides it while the
     * switch is on, and leaves it as Tumblr set it otherwise. Never throws.
     */
    public static void hideTumblrMart(@Nullable View button) {
        if (button == null || !Utils.settingsReady()) return;
        try {
            if (!PatchFamily.Capability.TUMBLRMART_BUTTONS.installed()) return;
            HookStatus.invoked(FamilyNames.HIDE_TUMBLRMART);
            if (!Settings.HIDE_TUMBLRMART.get()) return;
            button.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.HIDE_TUMBLRMART, "TumblrMart button hidden");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_TUMBLRMART, "TumblrMart button", failure);
        }
    }
}
