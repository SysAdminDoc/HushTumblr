/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model.post;

/** Stands in for Tumblr's DisplayType, with its constants. The filter reads them by name. */
public enum DisplayType {
    NORMAL,
    SPONSORED,
    IN_HOUSE,
    RADAR,
    TRACK_SILENTLY,
    RECOMMENDATION,
}
