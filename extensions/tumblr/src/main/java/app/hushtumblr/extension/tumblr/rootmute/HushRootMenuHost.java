/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

/** The immutable timeline owner of Tumblr's native post-menu callback. */
public interface HushRootMenuHost {
    HushLocalTimeline hushTimelineOwner();
}
