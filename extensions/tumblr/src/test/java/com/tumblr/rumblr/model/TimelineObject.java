/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

import com.tumblr.rumblr.model.post.DisplayType;

/**
 * Stands in for Tumblr's TimelineObject, a final class of the same name with the methods the filter
 * reads: the payload, whether Tumblr is asked to label the item Sponsored, and how the server tells
 * Tumblr to show it.
 */
public final class TimelineObject {
    private final Timelineable data;
    private final boolean showSponsoredLabel;
    private final Display display;
    private final DisplayType displayType;

    public TimelineObject(Timelineable data, boolean showSponsoredLabel) {
        this(data, showSponsoredLabel, null, null);
    }

    public TimelineObject(Timelineable data, boolean showSponsoredLabel, Display display, DisplayType displayType) {
        this.data = data;
        this.showSponsoredLabel = showSponsoredLabel;
        this.display = display;
        this.displayType = displayType;
    }

    public Timelineable getData() {
        return data;
    }

    public boolean isShowSponsoredLabel() {
        return showSponsoredLabel;
    }

    public Display getDisplay() {
        return display;
    }

    public DisplayType getDisplayType() {
        return displayType;
    }
}
