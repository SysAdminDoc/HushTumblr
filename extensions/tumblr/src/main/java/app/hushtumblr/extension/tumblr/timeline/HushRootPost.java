/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

/** A verified root ID, or the current ID only when the native model proves this is an original. */
public interface HushRootPost {
    @Nullable String hushRootId();
}
