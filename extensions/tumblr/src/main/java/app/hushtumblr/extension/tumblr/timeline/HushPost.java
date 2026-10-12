/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * What the post filters read from a Tumblr post. Tumblr's {@code Post} model renames its getters in
 * every build, so the post bridge patch makes that class implement this interface, each method
 * calling the getter Moshi's adapter writes under the JSON key named on it. Nothing in the
 * extension implements it, so without the patch no object is a {@code HushPost} and every post is
 * kept.
 */
public interface HushPost {
    /** The post's own blocks, under "content". */
    @Nullable
    List<?> hushContent();

    /** The posts it reblogs, oldest first, each a {@link HushTrail}, under "trail". */
    @Nullable
    List<?> hushTrail();

    /** This post's own tags, without the #, under "tags". Reblog-trail tags aren't included. */
    @Nullable
    List<?> hushTags();

    /** Tumblr's short summary of the post, under "summary". */
    @Nullable
    String hushSummary();

    /** The id of the post this one reblogs, or null for an original post, under "reblogged_from_id". */
    @Nullable
    String hushRebloggedFrom();
}
