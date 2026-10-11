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

/**
 * Whether Tumblr's rating prompt opens.
 *
 * <p>Tumblr opens its "What do you think of the Tumblr app?" screen, which leads to a store rating
 * or a feedback form, from one method. It asks its prompt rules, starts {@code RatingPromptActivity}
 * and saves when it last showed. The patch puts {@link #skip} first in that method and returns
 * straight away when it answers true, so the rules aren't asked, the screen doesn't open and nothing
 * is saved. The answer is read each time, so the switch works without a restart.
 */
public final class ReviewPrompts {
    private ReviewPrompts() {}

    /**
     * Injected at the start of the prompt launcher. True while the switch is on. False with the
     * switch off, paused, or before HushTumblr has its settings. Never throws.
     */
    public static boolean skip() {
        // Tumblr can ask while it starts, before HushTumblr may read a switch.
        if (!Utils.settingsReady()) return false;
        try {
            if (!PatchFamily.Capability.REVIEW_PROMPT.installed()) return false;
            HookStatus.invoked(FamilyNames.SKIP_REVIEW_PROMPTS);
            if (!Settings.SKIP_REVIEW_PROMPTS.get()) return false;
            HookStatus.counted(FamilyNames.SKIP_REVIEW_PROMPTS, "review prompt skipped");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SKIP_REVIEW_PROMPTS, "switch read", failure);
            return false;
        }
    }
}
