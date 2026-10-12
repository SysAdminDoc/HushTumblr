/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mutual;

/** Replaced by exact, read-only native Retrofit calls when the patch is installed. */
public final class NativeMutualSource implements HushMutualSource {
    @Override public Object first(String primaryHost) { throw new UnsupportedOperationException("Native followers bridge"); }
    @Override public Object next(String link) { throw new UnsupportedOperationException("Native followers bridge"); }
    @Override public Object execute(Object call, long timeoutMillis) { throw new UnsupportedOperationException("Native followers bridge"); }
    @Override public void cancel(Object call) { throw new UnsupportedOperationException("Native followers bridge"); }
}
