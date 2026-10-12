/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mutual;

public interface HushMutualPage {
    java.util.List<?> hushMutualUsers();
    Object hushMutualPagination();
    Object hushMutualLinks();
}
