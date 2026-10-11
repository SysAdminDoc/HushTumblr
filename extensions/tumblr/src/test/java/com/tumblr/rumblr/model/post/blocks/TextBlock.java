/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.tumblr.rumblr.model.post.blocks;

import app.hushtumblr.extension.tumblr.timeline.HushText;

/** Stands in for Tumblr's TextBlock as the post bridge patch leaves it, reading its text. */
public final class TextBlock implements HushText {
    private final String text;

    public TextBlock(String text) {
        this.text = text;
    }

    @Override
    public String hushText() {
        return text;
    }
}
