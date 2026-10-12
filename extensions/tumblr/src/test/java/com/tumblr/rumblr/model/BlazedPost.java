/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/**
 * Stands in for Tumblr's BlazedPost, one campaign in the list of your own Blaze dashboard, with the
 * post it promotes inside under {@code getPost}. The filters leave it alone.
 */
public final class BlazedPost implements Timelineable {
    private final Object post;

    public BlazedPost(Object post) {
        this.post = post;
    }

    public Object getPost() {
        return post;
    }

    @Override
    public String getId() {
        return "blazed";
    }

    @Override
    public TimelineObjectType getTimelineObjectType() {
        return TimelineObjectType.BLAZED_POST;
    }
}
