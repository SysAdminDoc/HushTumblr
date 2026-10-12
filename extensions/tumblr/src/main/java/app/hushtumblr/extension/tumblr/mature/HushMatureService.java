/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

/** The patch routes only the existing authenticated native GET through this bridge. */
public interface HushMatureService {
    void hushFetchMaturePermission(Object request);
}
