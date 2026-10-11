/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtumblr.extension.tumblr.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.hushtumblr.extension.shared.settings.BaseSettings;
import app.hushtumblr.extension.shared.settings.BooleanSetting;

/**
 * The switches behind the hooks that ask before they act.
 *
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Hide advertising ID
 * and Fix push notifications keep their switches on: both patches are in Morphe Manager's default
 * selection, and a patched build gets no push notifications without the second one. A patch added
 * later joins the default selection with its switches off, so a build patched with the defaults
 * acts like Tumblr until one is turned on. While HushTumblr is paused, safe mode included
 * ({@link app.hushtumblr.extension.shared.settings.HushTumblrPause}), a switch answers off unless
 * {@link app.hushtumblr.extension.shared.settings.Setting#keepWhenPaused} marks it, and the hook behind
 * it takes Tumblr's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /**
     * Google's advertising ID getters answer an empty ID with ad tracking limited
     * ({@link app.hushtumblr.extension.tumblr.privacy.AdvertisingId}).
     */
    public static final BooleanSetting HIDE_ADVERTISING_ID =
            new BooleanSetting("hushtumblr_hide_ad_id", TRUE);

    /**
     * Firebase gets Tumblr's own certificate fingerprint when it signs this install up for push
     * notifications ({@link app.hushtumblr.extension.tumblr.notifications.PushNotifications}). On to
     * start: a patched build can get no push notifications at all without it.
     */
    public static final BooleanSetting FIX_PUSH_NOTIFICATIONS =
            new BooleanSetting("hushtumblr_fix_push_notifications", TRUE);

    /**
     * Once a day, when Tumblr starts, ask api.github.com whether a newer HushTumblr release is
     * out, and say so on the settings screen ({@link ReleaseCheck}). It's the settings entry's own
     * switch rather than a patch's, so every build has it ({@link PatchFamily#ENTRY_SWITCHES}). Off
     * by default, and a settings file never carries it: a file shouldn't be able to put a phone
     * online.
     */
    public static final BooleanSetting CHECK_FOR_RELEASES =
            new BooleanSetting("hushtumblr_check_releases", FALSE);
}
