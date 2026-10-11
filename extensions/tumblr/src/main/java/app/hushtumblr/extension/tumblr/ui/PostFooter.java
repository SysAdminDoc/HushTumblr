/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * Which parts of the footer under a post Tumblr leaves out.
 *
 * <p>Tumblr draws the footer with Compose, one function per button. Each button hook is put first
 * in its button's function, and on true the function returns before it draws anything, so the rest
 * of the footer is drawn without it. Compose remembers what each footer drew last time and expects
 * the same buttons when it draws it again, so each button's answer is fixed the first time it's
 * asked once the settings are ready, and holds until Tumblr restarts.
 *
 * <p>The counts go through {@link #noteCount}, which Tumblr's footer asks for each count before it
 * turns it into text. Tumblr leaves a count of zero blank, so answering zero blanks it. That's only
 * text, so the counts switch shows on the next post that scrolls in.
 */
public final class PostFooter {
    private PostFooter() {}

    /** Each button's answer, by its hook, once the settings were ready to give one. */
    private static final Map<PatchFamily.Capability, Boolean> FIXED = new ConcurrentHashMap<>();

    /**
     * Injected at the start of the method that turns a like, reblog or comment count into its text.
     * Zero while Hide note counts is on, which Tumblr shows as no count. The count itself with the
     * switch off, paused, before HushTumblr has its settings, or when anything here fails.
     */
    public static int noteCount(int count) {
        // Tumblr builds footers while it starts, before HushTumblr may read a switch.
        if (!Utils.settingsReady()) return count;
        try {
            if (!PatchFamily.Capability.NOTE_COUNTS.installed()) return count;
            HookStatus.invoked(FamilyNames.HIDE_NOTE_COUNTS);
            if (count == 0 || !Settings.HIDE_NOTE_COUNTS.get()) return count;
            HookStatus.counted(FamilyNames.HIDE_NOTE_COUNTS, "count hidden");
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_NOTE_COUNTS, "switch read", failure);
            return count;
        }
    }

    /** Injected first in the footer's like button. True while Hide the like button is on. */
    public static boolean hideLikeButton() {
        return hides(PatchFamily.Capability.LIKE_BUTTON, FamilyNames.HIDE_LIKE_BUTTON, Settings.HIDE_LIKE_BUTTON,
                "like button hidden");
    }

    /** Injected first in the footer's reblog button. True while Hide the reblog button is on. */
    public static boolean hideReblogButton() {
        return hides(PatchFamily.Capability.REBLOG_BUTTON, FamilyNames.HIDE_REBLOG_BUTTON,
                Settings.HIDE_REBLOG_BUTTON, "reblog button hidden");
    }

    /** Injected first in the footer's comment button. True while Hide the comment button is on. */
    public static boolean hideCommentButton() {
        return hides(PatchFamily.Capability.COMMENT_BUTTON, FamilyNames.HIDE_COMMENT_BUTTON,
                Settings.HIDE_COMMENT_BUTTON, "comment button hidden");
    }

    /** Injected first in the footer's share button. True while Hide the share button is on. */
    public static boolean hideShareButton() {
        return hides(PatchFamily.Capability.SHARE_BUTTON, FamilyNames.HIDE_SHARE_BUTTON,
                Settings.HIDE_SHARE_BUTTON, "share button hidden");
    }

    /**
     * The button's answer: its switch as it was the first time it was asked once the settings were
     * ready. False before then, paused (the pause is decided once a run too), with its hook not in
     * this build, or when anything here fails. Never throws.
     */
    private static boolean hides(PatchFamily.Capability hook, String family, BooleanSetting setting,
            String counted) {
        if (!Utils.settingsReady()) return false;
        try {
            if (!hook.installed()) return false;
            HookStatus.invoked(family);
            if (!FIXED.computeIfAbsent(hook, unused -> setting.get())) return false;
            HookStatus.counted(family, counted);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(family, "switch read", failure);
            return false;
        }
    }

    /** Forgets every fixed answer, as a new run of Tumblr would. */
    static void forgetAnswersForTests() {
        FIXED.clear();
    }
}
