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

/** Decisions for optional prompts, separate from subscriptions and purchase actions. */
public final class OptionalNudges {
    private static final String GIFT_KEY = "key_has_seen_ad_free_browsing_gift_popup";

    private OptionalNudges() {}

    /** Only the blog reminder's enabled decision calls this. It never writes Tumblr preferences. */
    public static boolean hideBlogNotificationReminder() {
        if (!Utils.settingsReady()) return false;
        try {
            if (!PatchFamily.Capability.BLOG_NOTIFICATION_REMINDER.installed()) return false;
            HookStatus.invoked(FamilyNames.HIDE_BLOG_NOTIFICATION_REMINDERS);
            if (!Settings.HIDE_BLOG_NOTIFICATION_REMINDERS.get()) return false;
            HookStatus.counted(FamilyNames.HIDE_BLOG_NOTIFICATION_REMINDERS, "blog reminder hidden");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_BLOG_NOTIFICATION_REMINDERS, "switch read", failure);
            return false;
        }
    }

    /**
     * The shared popup renderer asks when a queued callback actually runs. Crab and unknown keys
     * always keep Tumblr's behavior. Deliberate gifting opens its own activity without this call.
     */
    public static boolean hideGiftMessage(String seenKey) {
        if (!GIFT_KEY.equals(seenKey) || !Utils.settingsReady()) return false;
        try {
            if (!PatchFamily.Capability.GIFT_MESSAGE_POPUP.installed()) return false;
            HookStatus.invoked(FamilyNames.HIDE_GIFT_MESSAGE_POPUPS);
            if (!Settings.HIDE_GIFT_MESSAGE_POPUPS.get()) return false;
            HookStatus.counted(FamilyNames.HIDE_GIFT_MESSAGE_POPUPS, "gift popup hidden");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_GIFT_MESSAGE_POPUPS, "switch read", failure);
            return false;
        }
    }
}
