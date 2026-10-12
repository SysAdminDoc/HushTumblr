/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import androidx.annotation.Nullable;

/** Reads only the author's alt_text field, never a generated image description or caption. */
public interface HushImageDescription {
    @Nullable String hushAltText();
}
