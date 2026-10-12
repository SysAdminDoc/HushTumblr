/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

/** The authenticated user UUID in the account object whose native commit just succeeded. */
public interface HushAccountIdentity {
    String hushAccountId();
}
