/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

/** Immutable fields of the freshly persisted native account response. */
public interface HushMatureAccount {
    int hushAge();
    boolean hushCanModifySafeMode();
    Object hushAgeVerification();
}
