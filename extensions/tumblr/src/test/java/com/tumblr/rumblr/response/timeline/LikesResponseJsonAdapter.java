/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.response.timeline;

import java.util.function.Supplier;

/**
 * Stands in for Moshi's adapter of a page of likes, by name: the filter knows a feed by this class's
 * frame on the call stack. {@link #fromJson} runs the parse it's handed inside its own frame, as the real
 * adapter builds the page.
 */
public final class LikesResponseJsonAdapter {
    private LikesResponseJsonAdapter() {}

    public static <T> T fromJson(Supplier<T> page) {
        return page.get();
    }
}
