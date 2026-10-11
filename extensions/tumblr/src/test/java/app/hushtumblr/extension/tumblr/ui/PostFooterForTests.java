/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import java.util.function.BooleanSupplier;

/** Lets a test outside this package ask a footer button hook as a fresh start of Tumblr would. */
public final class PostFooterForTests {
    private PostFooterForTests() {}

    /** Forgets the answers {@link PostFooter} kept, then asks [hook]. */
    public static boolean askedAfresh(BooleanSupplier hook) {
        PostFooter.forgetAnswersForTests();
        return hook.getAsBoolean();
    }
}
