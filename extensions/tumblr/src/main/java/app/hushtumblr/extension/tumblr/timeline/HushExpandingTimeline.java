/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

/**
 * A timeline screen. The Collapse long posts patch makes Tumblr's timeline implement this, the
 * method building one of its items' rows again where the item is, the way Tumblr's own inline
 * expand refreshes the list.
 */
public interface HushExpandingTimeline {
    /**
     * Builds the rows of [item], one of this timeline's items, again in its place and redraws the
     * list. False when the list isn't there yet or doesn't hold [item].
     */
    boolean hushExpandPost(@Nullable Object item);
}
