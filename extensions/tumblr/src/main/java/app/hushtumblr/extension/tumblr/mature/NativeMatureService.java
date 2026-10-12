/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

/** A separate wrapper avoids adding default methods to a Retrofit proxy interface. */
public final class NativeMatureService implements HushMatureService {
    private final Object service;
    public NativeMatureService(Object service) { this.service = service; }

    @Override public void hushFetchMaturePermission(Object request) {
        throw new UnsupportedOperationException("Native permission bridge is not installed");
    }
}
