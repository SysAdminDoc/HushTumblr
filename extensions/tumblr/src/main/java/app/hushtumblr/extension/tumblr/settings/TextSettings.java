/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import app.hushtumblr.extension.shared.settings.StringSetting;

/**
 * The settings that hold text rather than on or off. They live apart from {@link Settings}, whose
 * every setting is a switch a settings file can carry: a file holds true or false and nothing
 * else, so these stay out of backups until the format can carry text. While HushTumblr is paused
 * each answers empty, which leaves Tumblr as it is.
 */
public final class TextSettings {
    private TextSettings() {}

    /**
     * The words and phrases Hide posts with these words looks for, one a line or split by commas
     * ({@link app.hushtumblr.extension.tumblr.timeline.PostRules}). Read as each page loads.
     */
    public static final StringSetting HIDDEN_WORDS = new StringSetting("hushtumblr_hidden_words", "");
}
