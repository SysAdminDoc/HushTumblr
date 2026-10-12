/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

/** The patch adds the exact host Continuation interface and its empty context getter. */
public final class NativeMatureContinuation {
    private final Object request;
    public NativeMatureContinuation(Object request) { this.request = request; }
    public void resumeWith(Object response) { AllowedMatureContent.received(request, response); }
}
