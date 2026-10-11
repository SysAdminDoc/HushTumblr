/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/**
 * Stands in for Tumblr's BlazedPost, a paid promotion of a post. The post filters find it by this
 * class name and read the post inside through {@code getPost}, a name Tumblr keeps.
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
