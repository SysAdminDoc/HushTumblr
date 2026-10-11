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
 * <p>A switch's default is the second argument of its {@link BooleanSetting}. Hide ads, Disable
 * analytics, Hide advertising ID and Fix push notifications keep their switches on: they're what
 * most people patch for, and a patched build gets no push notifications without the last one. A
 * patch added later joins the default selection with its switches off, so a build patched with
 * the defaults acts like Tumblr until one is turned on. While HushTumblr is paused, safe mode included
 * ({@link app.hushtumblr.extension.shared.settings.HushTumblrPause}), a switch answers off unless
 * {@link app.hushtumblr.extension.shared.settings.Setting#keepWhenPaused} marks it, and the hook behind
 * it takes Tumblr's own path.
 */
@SuppressWarnings("unused")
public class Settings extends BaseSettings {
    /**
     * Ads and paid Blaze posts leave each page of posts Tumblr loads, and Tumblr's own requests to
     * its ad networks are turned off ({@link app.hushtumblr.extension.tumblr.timeline.TimelineFilter},
     * {@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). On to start, like the patch.
     */
    public static final BooleanSetting HIDE_ADS =
            new BooleanSetting("hushtumblr_hide_ads", TRUE);

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
     * Tumblr's own tracking and ad measurement flags answer off
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). The manifest half of Disable
     * analytics was set when patching and stays whatever this says. On to start, like the patch.
     */
    public static final BooleanSetting DISABLE_ANALYTICS =
            new BooleanSetting("hushtumblr_disable_analytics", TRUE);

    /**
     * The dashboard's banner selling Ad-Free Browsing stays hidden
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting HIDE_AD_FREE_BANNER =
            new BooleanSetting("hushtumblr_hide_ad_free_banner", FALSE);

    /**
     * The Tumblr TV tab leaves the bottom bar, and sign-up no longer ends in Tumblr TV
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting HIDE_TUMBLR_TV =
            new BooleanSetting("hushtumblr_hide_tumblr_tv", FALSE);

    /**
     * The Communities tab leaves the bottom bar, and the post editor stops showing Communities tips
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting HIDE_COMMUNITIES_TAB =
            new BooleanSetting("hushtumblr_hide_communities_tab", FALSE);

    /**
     * Tumblr's buttons, banners and nudges selling Blaze stay hidden
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting HIDE_BLAZE_PROMPTS =
            new BooleanSetting("hushtumblr_hide_blaze_prompts", FALSE);

    /**
     * Tumblr's pop-up asking you to rate it doesn't open
     * ({@link app.hushtumblr.extension.tumblr.ui.ReviewPrompts}). Off to start.
     */
    public static final BooleanSetting SKIP_REVIEW_PROMPTS =
            new BooleanSetting("hushtumblr_skip_review_prompts", FALSE);

    /**
     * Tumblr stops watching for screenshots and offering to save the post as an image
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting TURN_OFF_SCREENSHOT_SHARING =
            new BooleanSetting("hushtumblr_turn_off_screenshot_sharing", FALSE);

    /**
     * Tumblr doesn't ask Google Play for a newer version or show Play's update prompt
     * ({@link app.hushtumblr.extension.tumblr.flags.FeatureSwitches}). Off to start.
     */
    public static final BooleanSetting DISABLE_UPDATE_PROMPTS =
            new BooleanSetting("hushtumblr_disable_update_prompts", FALSE);

    /**
     * The "Check out these blogs" cards and carousels, and their headings, come out of each page
     * ({@link app.hushtumblr.extension.tumblr.timeline.TimelineFilter}). Off to start.
     */
    public static final BooleanSetting HIDE_SUGGESTED_BLOGS =
            new BooleanSetting("hushtumblr_hide_suggested_blogs", FALSE);

    /**
     * The "Check out these communities" cards and carousels, and their headings, come out of each
     * page ({@link app.hushtumblr.extension.tumblr.timeline.TimelineFilter}). Off to start.
     */
    public static final BooleanSetting HIDE_SUGGESTED_COMMUNITIES =
            new BooleanSetting("hushtumblr_hide_suggested_communities", FALSE);

    /**
     * Tumblr TV's "Watch it" rows and video hub cards, and their headings, come out of each page
     * ({@link app.hushtumblr.extension.tumblr.timeline.TimelineFilter}). Off to start.
     */
    public static final BooleanSetting HIDE_WATCH_IT_ROWS =
            new BooleanSetting("hushtumblr_hide_watch_it_rows", FALSE);

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
