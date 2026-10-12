/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

/** Exercises the shared authenticated commit window without mute-file IO. */
public final class MatureAccountsForTests {
    private MatureAccountsForTests() {}
    public static void reset() {
        RootPostMutes.resetForTests();
        RootPostMutes.workerForTests = ignored -> {};
    }
    public static void restore() { RootPostMutes.resetForTests(); RootPostMutes.workerForTests = null; }
    public static void commit(Object account) {
        Object request = new Object();
        RootPostMutes.requestCreated(request);
        RootPostMutes.requestEntered(request);
        RootPostMutes.beginAccountChange(account);
        RootPostMutes.commitAccount();
        RootPostMutes.requestExited();
    }
}
