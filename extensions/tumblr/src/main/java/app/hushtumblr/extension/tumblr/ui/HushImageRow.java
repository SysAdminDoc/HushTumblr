/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

/** The native gallery row supplies its current image holders before each full bind. */
public interface HushImageRow {
    Object[] hushImageItems();
}
