/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * What Tumblr's bottom bar leaves out.
 *
 * <p>The Dashboard, Communities and Notifications tabs each have an unread badge, hidden until
 * Tumblr has something to count. The bar shows one with a pop-in animation whose start makes it
 * visible, and Tumblr runs that each time the counts come in, through one method that first
 * returns when it has no badge. {@link #unreadBadge} is put first in that method and hands it no
 * badge while the switch is on, so the next count leaves the dot hidden.
 */
public final class BottomBar {
    private BottomBar() {}

    /**
     * Injected at the start of the bottom bar's method that pops an unread badge in. While Hide
     * unread dots is on, hides [badge] and answers null, which that method takes as no badge. The
     * badge itself with the switch off, paused, before HushTumblr has its settings, or when
     * anything here fails. Never throws.
     */
    @Nullable
    public static TextView unreadBadge(@Nullable TextView badge) {
        // Tumblr sets up its bar while it starts, before HushTumblr may read a switch.
        if (badge == null || !Utils.settingsReady()) return badge;
        try {
            if (!PatchFamily.Capability.UNREAD_DOTS.installed()) return badge;
            HookStatus.invoked(FamilyNames.HIDE_UNREAD_DOTS);
            if (!Settings.HIDE_UNREAD_DOTS.get()) return badge;
            badge.clearAnimation();
            badge.setVisibility(View.GONE);
            HookStatus.counted(FamilyNames.HIDE_UNREAD_DOTS, "unread dot hidden");
            return null;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_UNREAD_DOTS, "switch read", failure);
            return badge;
        }
    }
}
