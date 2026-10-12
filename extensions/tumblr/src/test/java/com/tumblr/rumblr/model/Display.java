/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/** Stands in for Tumblr's Display, an item's "display" block, with the one getter the filter reads. */
public final class Display {
    private final SponsoredState sponsored;

    public Display(SponsoredState sponsored) {
        this.sponsored = sponsored;
    }

    public SponsoredState getSponsored() {
        return sponsored;
    }
}
