/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * One entry of a reblog's trail as the post filters read it. The post bridge patch makes Tumblr's
 * {@code ReblogTrail} implement it, the method calling the getter Moshi's adapter writes under
 * "content".
 */
public interface HushTrail {
    /** That entry's blocks. */
    @Nullable
    List<?> hushContent();
}
