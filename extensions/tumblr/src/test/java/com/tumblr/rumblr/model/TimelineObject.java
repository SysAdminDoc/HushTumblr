/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/**
 * Stands in for Tumblr's TimelineObject, a final class of the same name with the two methods the
 * filter reads: the payload and whether Tumblr is asked to label the item Sponsored.
 */
public final class TimelineObject {
    private final Timelineable data;
    private final boolean showSponsoredLabel;

    public TimelineObject(Timelineable data, boolean showSponsoredLabel) {
        this.data = data;
        this.showSponsoredLabel = showSponsoredLabel;
    }

    public Timelineable getData() {
        return data;
    }

    public boolean isShowSponsoredLabel() {
        return showSponsoredLabel;
    }
}
