/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import java.util.List;

/** The current displayed list and its native local-only replacement path. */
public interface HushLocalTimeline {
    List<?> hushTimelineItems();
    void hushApplyTimelineItems(List<?> items);
}
