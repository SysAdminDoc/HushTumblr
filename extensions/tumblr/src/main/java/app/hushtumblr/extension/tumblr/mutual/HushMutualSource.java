/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mutual;

public interface HushMutualSource {
    Object first(String primaryHost);
    Object next(String link);
    Object execute(Object call, long timeoutMillis);
    void cancel(Object call);
}
