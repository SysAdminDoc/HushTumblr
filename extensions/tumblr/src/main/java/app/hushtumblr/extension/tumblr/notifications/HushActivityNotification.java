/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.notifications;

import androidx.annotation.Nullable;

/** The native activity interface implements this through a default method added when patching. */
public interface HushActivityNotification {
    /** The server model's enum constant name. Null and new constants are kept by the filter. */
    @Nullable String hushNotificationType();
}
