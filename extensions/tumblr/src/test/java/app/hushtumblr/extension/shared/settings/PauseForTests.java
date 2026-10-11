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
 */
package app.hushtumblr.extension.shared.settings;

/**
 * Pauses and resumes the process for a test in another package. {@link HushTumblrPause} keeps
 * its test hooks package-private, and the hooks a pause has to reach live in the Tumblr packages.
 */
public final class PauseForTests {
    private PauseForTests() {
    }

    public static void pause(HushTumblrPause.Reason why) {
        HushTumblrPause.pauseForTests(why);
    }

    public static void resume() {
        HushTumblrPause.pauseForTests(HushTumblrPause.Reason.NONE);
    }

    /** Runs [probe] inside the next first-of-process pause decision, or nothing when null. */
    public static void whileDeciding(Runnable probe) {
        HushTumblrPause.whileDecidingForTests = probe;
    }
}
