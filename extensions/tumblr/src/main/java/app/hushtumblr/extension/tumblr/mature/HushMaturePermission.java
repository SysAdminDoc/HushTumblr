/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

/** Nullable server values, before the host mapper supplies defaults. */
public interface HushMaturePermission {
    Boolean hushShowMatureContent();
    Boolean hushCanEditMatureContent();
}
