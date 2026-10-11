/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

/**
 * A text block as the word filter reads it. The post bridge patch makes Tumblr's {@code TextBlock}
 * implement it, the method calling the getter Moshi's adapter writes under "text".
 */
public interface HushText {
    /** The block's text, without its formatting. */
    @Nullable
    String hushText();
}
