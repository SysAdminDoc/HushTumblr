/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/** Stands in for Tumblr's SponsoredState, with its constants. The filter reads them by name. */
public enum SponsoredState {
    SPONSORED,
    NOT_SPONSORED,
    UNKNOWN,
}
