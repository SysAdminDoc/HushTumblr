/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** Uses Tumblr's timestamp renderers without changing its saved preference or server settings. */
public final class PostTimestamps {
    private PostTimestamps() {}

    /** Off, paused, not installed and unreadable all preserve Tumblr's original answer. */
    public static boolean show(boolean original) {
        if (!Utils.settingsReady()) return original;
        try {
            if (!PatchFamily.SHOW_POST_TIMESTAMPS.inBuild()
                    || !PatchFamily.Capability.NATIVE_POST_TIMESTAMPS.installed()) return original;
            HookStatus.invoked(FamilyNames.SHOW_POST_TIMESTAMPS);
            return original || Settings.SHOW_POST_TIMESTAMPS.get();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_POST_TIMESTAMPS, "native timestamp preference", failure);
            return original;
        }
    }
}
