/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import androidx.annotation.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/**
 * The one place the timeline switches take items out of a page Tumblr loaded.
 *
 * <p>Tumblr's API client builds every page of the dashboard as a {@code Timeline}, and every page
 * of a blog's posts, likes and blog search as a {@code TimelineResponse}, each from the list of
 * {@code TimelineObject}s it parsed. The patch passes that list through {@link #filter} first thing
 * in both constructors, so what a switch takes out never reaches Tumblr's adapters or its ad
 * logging. With every switch off, paused, or before HushTumblr has its settings, the list goes
 * through untouched.
 *
 * <p>The rumblr model keeps its names, so the item's kind and its Sponsored label are read by name.
 * Anything that can't be read is kept: a filter that guesses could take a post out.
 */
public final class TimelineFilter {
    private TimelineFilter() {}

    static final String TIMELINE_OBJECT = "com.tumblr.rumblr.model.TimelineObject";
    static final String TIMELINEABLE = "com.tumblr.rumblr.model.Timelineable";

    /**
     * The kinds of item that are an ad or a paid placement whatever else they carry, by the constant
     * names Tumblr's {@code TimelineObjectType} keeps. {@code BLAZED_POST} is a post someone paid
     * Tumblr to show to people who don't follow them.
     */
    static final Set<String> AD_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GEMINI_AD", "FACEBOOK_BIDDAABLE", "NIMBUS_AD", "DISPLAY_IO_INTERSCROLLER_S2S_ADS", "VUNGLE_AD",
            "CLIENT_SIDE_AD", "CLIENT_SIDE_MEDIATION", "DISPLAY_IO_INTERSCROLLER_AD", "GOOGLE_NATIVE",
            "BLAZED_POST")));

    /** Tumblr's model methods, found the first time an item is read. */
    @Nullable
    private static volatile Model model;

    /** The item class whose methods couldn't be found, so each page doesn't look again. */
    @Nullable
    private static volatile Class<?> unreadable;

    /**
     * Injected at the start of both list constructors, on their list parameter. Returns the same list
     * when nothing goes, or a new one without the items that do, in the same order. Null stays
     * null. Never throws.
     */
    public static List<?> filter(List<?> items) {
        // Tumblr can load a page while it starts, before HushTumblr may read a switch.
        if (!Utils.settingsReady()) return items;
        Rules rules = Rules.now();
        if (rules == null || items == null || items.isEmpty()) return items;
        try {
            int size = items.size();
            String[] removedBy = new String[size];
            boolean any = false;
            for (int i = 0; i < size; i++) {
                removedBy[i] = rules.removes(items.get(i));
                any |= removedBy[i] != null;
            }
            if (!any) return items;
            List<Object> kept = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (removedBy[i] == null) kept.add(items.get(i));
                else HookStatus.counted(removedBy[i], removal(removedBy[i]));
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(rules.owner(), "timeline filter", failure);
            return items;
        }
    }

    /** What a removal by [family] is counted as. English, for the report. */
    static String removal(String family) {
        return "ad removed";
    }

    /** Which switches take items out of this page, read once a page. */
    static final class Rules {
        final boolean ads;

        private Rules(boolean ads) {
            this.ads = ads;
        }

        /** The switches on now, or null when none is. Never throws. */
        @Nullable
        static Rules now() {
            boolean ads = false;
            try {
                // Each family's patch marks its timeline capability once the hook is in.
                if (PatchFamily.Capability.TIMELINE_ADS.installed()) {
                    HookStatus.invoked(FamilyNames.HIDE_ADS);
                    ads = Settings.HIDE_ADS.get();
                }
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.HIDE_ADS, "switch read", failure);
                return null;
            }
            return ads ? new Rules(true) : null;
        }

        /** The family a failure is reported under. */
        String owner() {
            return FamilyNames.HIDE_ADS;
        }

        /** The patch name of the first switch that takes [item] out, or null to keep it. */
        @Nullable
        String removes(Object item) {
            return ads && isAd(item) ? FamilyNames.HIDE_ADS : null;
        }
    }

    /**
     * True when [item] is a timeline object Tumblr marks as an ad: by its kind, or by the Sponsored
     * label Tumblr is asked to draw on it. One that can't be read is kept.
     */
    static boolean isAd(Object item) {
        Model found = modelFor(item, FamilyNames.HIDE_ADS);
        if (found == null) return false;
        try {
            return AD_TYPES.contains(found.typeOf(item)) || Boolean.TRUE.equals(found.sponsored.invoke(item));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADS, "timeline item", failure);
            return false;
        }
    }

    /**
     * The model methods for [item], or null when it isn't a timeline object or they can't be found.
     * A lookup that fails is recorded under [family] once.
     */
    @Nullable
    static Model modelFor(@Nullable Object item, String family) {
        if (item == null) return null;
        Class<?> type = item.getClass();
        if (!TIMELINE_OBJECT.equals(type.getName()) || type == unreadable) return null;
        Model found = model;
        if (found != null && found.objectType == type) return found;
        try {
            found = Model.of(type);
        } catch (Throwable failure) {
            unreadable = type;
            HookStatus.missingMember(family, "method", TIMELINE_OBJECT,
                    "getData, isShowSponsoredLabel or Timelineable.getTimelineObjectType");
            return null;
        }
        model = found;
        return found;
    }

    /** The three model methods the filter reads, all names Tumblr keeps. */
    static final class Model {
        final Class<?> objectType;
        final Method data;
        final Method sponsored;
        final Method kind;

        private Model(Class<?> objectType, Method data, Method sponsored, Method kind) {
            this.objectType = objectType;
            this.data = data;
            this.sponsored = sponsored;
            this.kind = kind;
        }

        static Model of(Class<?> objectType) throws ReflectiveOperationException {
            Method data = objectType.getMethod("getData");
            Method sponsored = objectType.getMethod("isShowSponsoredLabel");
            Class<?> timelineable = Class.forName(TIMELINEABLE, false, objectType.getClassLoader());
            Method kind = timelineable.getMethod("getTimelineObjectType");
            return new Model(objectType, data, sponsored, kind);
        }

        /** The constant name of [item]'s kind, such as POST or GEMINI_AD, or null when it has none. */
        @Nullable
        String typeOf(Object item) throws ReflectiveOperationException {
            Object payload = data.invoke(item);
            Object type = payload == null ? null : kind.invoke(payload);
            return type instanceof Enum ? ((Enum<?>) type).name() : null;
        }
    }
}
