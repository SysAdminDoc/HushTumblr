/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model;

/** Stands in for Tumblr's item payload interface, with the method the filter reads by name. */
public interface Timelineable {
    String getId();

    TimelineObjectType getTimelineObjectType();
}
