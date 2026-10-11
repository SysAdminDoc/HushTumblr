/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import com.tumblr.rumblr.model.TimelineObject;
import com.tumblr.rumblr.model.TimelineObjectType;
import com.tumblr.rumblr.model.Timelineable;

import java.util.Arrays;
import java.util.List;

/** Timeline objects built from the stand-ins in {@code com.tumblr.rumblr.model}. */
public final class TimelineItems {
    private TimelineItems() {}

    /** An item of [type] with no Sponsored label. */
    public static TimelineObject of(TimelineObjectType type) {
        return new TimelineObject(new Item(type), false);
    }

    public static TimelineObject post() {
        return of(TimelineObjectType.POST);
    }

    /** A plain post Tumblr is asked to label Sponsored. */
    public static TimelineObject sponsoredPost() {
        return new TimelineObject(new Item(TimelineObjectType.POST), true);
    }

    /** An item whose payload throws when its kind is read. */
    public static TimelineObject unreadable() {
        return new TimelineObject(new Item(null) {
            @Override
            public TimelineObjectType getTimelineObjectType() {
                throw new IllegalStateException("a payload that can't say what it is");
            }
        }, false);
    }

    /** PausedHooksTest's probe for Hide ads: true when a page with an ad comes back without it. */
    public static boolean aPageLosesItsAd() {
        List<Object> page = Arrays.asList(post(), of(TimelineObjectType.GEMINI_AD), post());
        return TimelineFilter.filter(page).size() == 2;
    }

    private static class Item implements Timelineable {
        private final TimelineObjectType type;

        Item(TimelineObjectType type) {
            this.type = type;
        }

        @Override
        public String getId() {
            return "1";
        }

        @Override
        public TimelineObjectType getTimelineObjectType() {
            return type;
        }
    }
}
