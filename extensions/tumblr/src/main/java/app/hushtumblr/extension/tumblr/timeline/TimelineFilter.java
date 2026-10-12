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
import java.util.function.Function;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.rootmute.RootPostMutes;

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
 * <p>The rumblr model keeps its names, so the item's kind, its Sponsored label and how Tumblr is told
 * to show it are read by name. A carousel's own items are the one renamed getter, read through
 * {@link #carouselItems}. Anything that can't be read is kept: a filter that guesses could take a
 * post out.
 *
 * <p>Suggestion cards often come after a {@code TITLE} item carrying their heading ("Check out these
 * blogs"). A heading goes with the item right after it, and one that ends a page goes too while a
 * card switch is on, since its card may be the first item of the next page.
 *
 * <p>The post switches (reblogs, video, photo and text posts, and the word list) are
 * {@link PostRules}'. A heading never goes with a post they take out.
 */
public final class TimelineFilter {
    private TimelineFilter() {}

    static final String TIMELINE_OBJECT = "com.tumblr.rumblr.model.TimelineObject";
    static final String TIMELINEABLE = "com.tumblr.rumblr.model.Timelineable";

    /** The kind of a heading item, whose text the server sends. */
    static final String TITLE = "TITLE";

    /**
     * The constant name Tumblr's {@code SponsoredState} and {@code DisplayType} both give a paid
     * placement.
     */
    static final String SPONSORED = "SPONSORED";

    /**
     * The kinds of item that are an ad whatever else they carry, by the constant names Tumblr's
     * {@code TimelineObjectType} keeps. {@code BLAZED_POST} isn't one: it's a campaign in the list
     * of your own Blaze dashboard, which loads through the same page. A paid Blaze post in a feed is
     * a post served as sponsored, see {@link Model#isPaidPlacement}.
     */
    static final Set<String> AD_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "GEMINI_AD", "FACEBOOK_BIDDAABLE", "NIMBUS_AD", "DISPLAY_IO_INTERSCROLLER_S2S_ADS", "VUNGLE_AD",
            "CLIENT_SIDE_AD", "CLIENT_SIDE_MEDIATION", "DISPLAY_IO_INTERSCROLLER_AD", "GOOGLE_NATIVE")));

    /** Blogs Tumblr suggests you follow, as cards, rows and the small cards of a carousel. */
    static final Set<String> BLOG_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "BLOG_CARD", "COMPACT_BLOG_CARD", "BLOG_ROW", "TINY_BLOG_CAROUSEL_CARD")));

    /**
     * Communities Tumblr suggests you join. A community's own header card isn't here: it heads that
     * community's page.
     */
    static final Set<String> COMMUNITY_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "COMMUNITY_CARD", "COMMUNITY_ROW")));

    /** Tumblr TV's "Watch it" rows and the video hub cards in them. */
    static final Set<String> WATCH_IT_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "VIDEO_HUB_CARD", "VIDEO_HUB_FEATURED", "VIDEO_HUB_FEATURED_BORDERED", "VIDEO_HUBS_CONTAINER",
            "VIDEO_HUBS_ROW")));

    /** Containers that hold other items. Each is judged by its first item. */
    static final Set<String> CAROUSEL_TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "CAROUSEL", "AVATAR_CAROUSEL")));

    /** Tumblr's model methods, found the first time an item is read. */
    @Nullable
    private static volatile Model model;

    /** The item class whose methods couldn't be found, so each page doesn't look again. */
    @Nullable
    private static volatile Class<?> unreadable;

    /** Stands in for the patched {@link #carouselItems} in a test. Null in the app. */
    @Nullable
    static volatile Function<Object, List<?>> carouselItemsForTests;

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
            boolean[] heading = new boolean[size];
            boolean any = false;
            for (int i = 0; i < size; i++) {
                removedBy[i] = rules.removes(items.get(i));
                any |= removedBy[i] != null;
            }
            // From the end, so a run of headings all go with the item after the last of them.
            for (int i = size - 1; i >= 0; i--) {
                if (removedBy[i] != null) continue;
                String with = i + 1 < size ? removedBy[i + 1] : rules.cardOwner();
                // A heading over a run of posts heads the rest of them too, so it stays.
                if (with == null || isPostRemoval(with) || !isTitle(items.get(i), rules.owner())) continue;
                removedBy[i] = with;
                heading[i] = true;
                any = true;
            }
            if (!any) return items;
            List<Object> kept = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                if (removedBy[i] == null) kept.add(items.get(i));
                else HookStatus.counted(removedBy[i], heading[i] ? "heading removed" : removal(removedBy[i]));
            }
            return kept;
        } catch (Throwable failure) {
            HookStatus.threw(rules.owner(), "timeline filter", failure);
            return items;
        }
    }

    /** What a removal by [family] is counted as. English, for the report. */
    static String removal(String family) {
        if (FamilyNames.HIDE_ADS.equals(family)) return "ad removed";
        return isPostRemoval(family) ? "post removed" : "card removed";
    }

    private static boolean isPostRemoval(String family) {
        return PostRules.FAMILIES.contains(family) || FamilyNames.MUTE_ROOT_POSTS.equals(family);
    }

    /** Which switches take items out of this page, read once a page. */
    static final class Rules {
        final boolean ads;
        final boolean blogs;
        final boolean communities;
        final boolean watchIt;
        final boolean mutes;
        /** The post switches, or null when none is on. */
        @Nullable
        final PostRules posts;

        private Rules(boolean ads, boolean blogs, boolean communities, boolean watchIt, boolean mutes,
                      @Nullable PostRules posts) {
            this.ads = ads;
            this.blogs = blogs;
            this.communities = communities;
            this.watchIt = watchIt;
            this.mutes = mutes;
            this.posts = posts;
        }

        /** The switches on now, or null when none is. Never throws. */
        @Nullable
        static Rules now() {
            String reading = FamilyNames.HIDE_ADS;
            try {
                // Each family's patch marks its timeline capability once the hook is in.
                boolean ads = on(PatchFamily.Capability.TIMELINE_ADS, reading, Settings.HIDE_ADS);
                reading = FamilyNames.HIDE_SUGGESTED_BLOGS;
                boolean blogs = on(PatchFamily.Capability.SUGGESTED_BLOG_CARDS, reading, Settings.HIDE_SUGGESTED_BLOGS);
                reading = FamilyNames.HIDE_SUGGESTED_COMMUNITIES;
                boolean communities = on(PatchFamily.Capability.SUGGESTED_COMMUNITY_CARDS, reading,
                        Settings.HIDE_SUGGESTED_COMMUNITIES);
                reading = FamilyNames.HIDE_WATCH_IT_ROWS;
                boolean watchIt = on(PatchFamily.Capability.WATCH_IT_ROWS, reading, Settings.HIDE_WATCH_IT_ROWS);
                reading = FamilyNames.HIDE_REBLOGS;
                PostRules posts = PostRules.now();
                reading = FamilyNames.MUTE_ROOT_POSTS;
                boolean mutes = RootPostMutes.active();
                return ads || blogs || communities || watchIt || mutes || posts != null
                        ? new Rules(ads, blogs, communities, watchIt, mutes, posts) : null;
            } catch (Throwable failure) {
                HookStatus.threw(reading, "switch read", failure);
                return null;
            }
        }

        /** Whether [capability]'s hook is in and [family]'s switch is on. */
        private static boolean on(PatchFamily.Capability capability, String family, BooleanSetting setting) {
            if (!capability.installed()) return false;
            HookStatus.invoked(family);
            return setting.get();
        }

        /** The family a failure is reported under: the first switch that's on. */
        String owner() {
            if (ads) return FamilyNames.HIDE_ADS;
            String card = cardOwner();
            if (card != null) return card;
            return posts != null ? posts.owner() : mutes ? FamilyNames.MUTE_ROOT_POSTS : FamilyNames.HIDE_ADS;
        }

        /** The first card switch that's on, or null when none is. */
        @Nullable
        String cardOwner() {
            if (blogs) return FamilyNames.HIDE_SUGGESTED_BLOGS;
            if (communities) return FamilyNames.HIDE_SUGGESTED_COMMUNITIES;
            if (watchIt) return FamilyNames.HIDE_WATCH_IT_ROWS;
            return null;
        }

        /** The patch name of the first switch that takes [item] out, or null to keep it. */
        @Nullable
        String removes(Object item) {
            if (ads && isAd(item)) return FamilyNames.HIDE_ADS;
            String card = cardOwner();
            String byCard = card == null ? null : cardFamily(item, this, card);
            if (byCard != null) return byCard;
            if (mutes && isMutedPost(item)) return FamilyNames.MUTE_ROOT_POSTS;
            return posts == null ? null : postFamily(item, posts);
        }

        /** The patch name whose switch is on and whose cards include [kind], or null. */
        @Nullable
        String family(@Nullable String kind) {
            if (kind == null) return null;
            if (blogs && BLOG_TYPES.contains(kind)) return FamilyNames.HIDE_SUGGESTED_BLOGS;
            if (communities && COMMUNITY_TYPES.contains(kind)) return FamilyNames.HIDE_SUGGESTED_COMMUNITIES;
            if (watchIt && WATCH_IT_TYPES.contains(kind)) return FamilyNames.HIDE_WATCH_IT_ROWS;
            return null;
        }
    }

    private static boolean isMutedPost(Object item) {
        Model found = modelFor(item, FamilyNames.MUTE_ROOT_POSTS);
        if (found == null) return false;
        try {
            return RootPostMutes.shouldHide(found.data.invoke(item));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.MUTE_ROOT_POSTS, "timeline item", failure);
            return false;
        }
    }

    /**
     * True when [item] is a timeline object Tumblr marks as an ad: by its kind, by the Sponsored
     * label, as a paid placement, or by its structured paid header context. One that can't be read
     * is kept.
     */
    static boolean isAd(Object item) {
        Model found = modelFor(item, FamilyNames.HIDE_ADS);
        if (found == null) return false;
        try {
            return AD_TYPES.contains(found.typeOf(item)) || Boolean.TRUE.equals(found.sponsored.invoke(item))
                    || found.isPaidPlacement(item) || hasPaidHeader(found.data.invoke(item));
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HIDE_ADS, "timeline item", failure);
            return false;
        }
    }

    /**
     * Blaze and Sponsored header icons are server values, not localized label text. Followed and
     * editable posts stay on this path, as do unknown icons and an organic post's is_blazed flag.
     * A BLAZED_POST campaign wraps a Post and never implements this bridge itself.
     */
    private static boolean hasPaidHeader(@Nullable Object payload) {
        if (!(payload instanceof HushPaidPost)) return false;
        HushPaidPost post = (HushPaidPost) payload;
        if (!post.hushPaidContextKnown()) return false;
        if (post.hushFollowed() || post.hushCanEdit()) return false;
        String icon = post.hushHeaderIcon();
        return "blaze".equals(icon) || "money-alt".equals(icon);
    }

    /**
     * The patch name of the card switch that takes [item] out, or null to keep it. A carousel goes
     * by its first item, and its own heading goes with it. One that can't be read is kept, and the
     * failure is recorded under [owner].
     */
    @Nullable
    static String cardFamily(Object item, Rules rules, String owner) {
        Model found = modelFor(item, owner);
        if (found == null) return null;
        try {
            Object payload = found.data.invoke(item);
            String kind = found.kindOf(payload);
            if (kind != null && CAROUSEL_TYPES.contains(kind)) {
                List<?> children = readCarousel(payload);
                if (children == null || children.isEmpty()) return null;
                Object first = children.get(0);
                if (modelFor(first, owner) == null) return null;
                kind = found.typeOf(first);
            }
            return rules.family(kind);
        } catch (Throwable failure) {
            HookStatus.threw(owner, "timeline item", failure);
            return null;
        }
    }

    /**
     * The patch name of the post switch that takes [item] out, or null to keep it. One that can't be
     * read is kept, and the failure is recorded under the first post switch that's on.
     */
    @Nullable
    static String postFamily(Object item, PostRules posts) {
        String owner = posts.owner();
        Model found = modelFor(item, owner);
        if (found == null) return null;
        try {
            return posts.removes(found.data.invoke(item));
        } catch (Throwable failure) {
            HookStatus.threw(owner, "timeline post", failure);
            return null;
        }
    }

    /** True when [item] is a heading. One that can't be read isn't, and the failure goes to [owner]. */
    static boolean isTitle(Object item, String owner) {
        Model found = modelFor(item, owner);
        if (found == null) return false;
        try {
            return TITLE.equals(found.typeOf(item));
        } catch (Throwable failure) {
            HookStatus.threw(owner, "timeline item", failure);
            return false;
        }
    }

    @Nullable
    private static List<?> readCarousel(Object carousel) {
        Function<Object, List<?>> forTests = carouselItemsForTests;
        return forTests != null ? forTests.apply(carousel) : carouselItems(carousel);
    }

    /**
     * A carousel's items, each a timeline object. A stub: the timeline card patches write its body
     * to call Tumblr's {@code Carousel} getter, whose name changes with every build. Here it answers
     * null, which keeps every carousel.
     */
    @Nullable
    static List<?> carouselItems(Object carousel) {
        return null;
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
                    "getData, isShowSponsoredLabel, getDisplay, getDisplayType, Display.getSponsored"
                            + " or Timelineable.getTimelineObjectType");
            return null;
        }
        model = found;
        return found;
    }

    /** The model methods the filter reads, all names Tumblr keeps. */
    static final class Model {
        final Class<?> objectType;
        final Method data;
        final Method sponsored;
        final Method display;
        final Method displayType;
        final Method sponsoredState;
        final Method kind;

        private Model(Class<?> objectType, Method data, Method sponsored, Method display, Method displayType,
                Method sponsoredState, Method kind) {
            this.objectType = objectType;
            this.data = data;
            this.sponsored = sponsored;
            this.display = display;
            this.displayType = displayType;
            this.sponsoredState = sponsoredState;
            this.kind = kind;
        }

        static Model of(Class<?> objectType) throws ReflectiveOperationException {
            Method data = objectType.getMethod("getData");
            Method sponsored = objectType.getMethod("isShowSponsoredLabel");
            Method display = objectType.getMethod("getDisplay");
            Method displayType = objectType.getMethod("getDisplayType");
            Method sponsoredState = display.getReturnType().getMethod("getSponsored");
            Class<?> timelineable = Class.forName(TIMELINEABLE, false, objectType.getClassLoader());
            Method kind = timelineable.getMethod("getTimelineObjectType");
            return new Model(objectType, data, sponsored, display, displayType, sponsoredState, kind);
        }

        /**
         * Whether Tumblr serves [item] as a paid placement: its {@code Display} says it's sponsored,
         * or its {@code DisplayType} is SPONSORED. Tumblr reads those two to treat a card as an ad
         * (the ad menu with its "Why am I seeing this ad?" page, the close button, and the Blaze
         * follow and action buttons of a paid Blaze post). A plain post can instead carry only the
         * structured header context checked by {@link TimelineFilter#hasPaidHeader}. The generic
         * is_blazed flag isn't evidence of a paid placement.
         */
        boolean isPaidPlacement(Object item) throws ReflectiveOperationException {
            Object shown = display.invoke(item);
            Object state = shown == null ? null : sponsoredState.invoke(shown);
            return SPONSORED.equals(nameOf(state)) || SPONSORED.equals(nameOf(displayType.invoke(item)));
        }

        /** The constant name of [item]'s kind, such as POST or GEMINI_AD, or null when it has none. */
        @Nullable
        String typeOf(Object item) throws ReflectiveOperationException {
            return kindOf(data.invoke(item));
        }

        /** The constant name of the kind of an item's [payload], or null when it has none. */
        @Nullable
        String kindOf(@Nullable Object payload) throws ReflectiveOperationException {
            return nameOf(payload == null ? null : kind.invoke(payload));
        }

        /** The constant name of [value] when it's an enum constant, or null. */
        @Nullable
        private static String nameOf(@Nullable Object value) {
            return value instanceof Enum ? ((Enum<?>) value).name() : null;
        }
    }
}
