/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.tumblr.rumblr.model.TimelineObjectType;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** What Hide ads and the suggestion card switches take out of a page of posts, and what they leave. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TimelineFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_SUGGESTED_BLOGS.resetToDefault();
        Settings.HIDE_SUGGESTED_COMMUNITIES.resetToDefault();
        Settings.HIDE_WATCH_IT_ROWS.resetToDefault();
        TimelineFilter.carouselItemsForTests = null;
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test
    public void everyAdKindIsTakenOutAndThePostsAroundItStayInOrder() {
        for (String type : TimelineFilter.AD_TYPES) {
            List<Object> page = Arrays.asList(TimelineItems.post(), TimelineItems.of(TimelineObjectType.valueOf(type)),
                    TimelineItems.post());
            List<?> shown = TimelineFilter.filter(page);
            assertEquals(type, 2, shown.size());
            assertSame(page.get(0), shown.get(0));
            assertSame(page.get(2), shown.get(1));
        }
    }

    @Test
    public void aPostTumblrLabelsSponsoredIsTakenOut() {
        List<Object> page = Arrays.asList(TimelineItems.sponsoredPost(), TimelineItems.post());
        List<?> shown = TimelineFilter.filter(page);
        assertEquals(1, shown.size());
        assertSame(page.get(1), shown.get(0));
    }

    /** A page with nothing to take out comes back as the very list Tumblr built. */
    @Test
    public void postsAndOtherItemsStay() {
        List<Object> page = Arrays.asList(TimelineItems.post(), TimelineItems.of(TimelineObjectType.TITLE),
                TimelineItems.of(TimelineObjectType.UNKNOWN), "not a timeline object", null);
        assertSame(page, TimelineFilter.filter(page));
    }

    @Test
    public void nullAndEmptyPagesComeBackAsTheyWere() {
        assertNull(TimelineFilter.filter(null));
        List<Object> empty = new ArrayList<>();
        assertSame(empty, TimelineFilter.filter(empty));
    }

    @Test
    public void theSwitchOffPauseOrABuildWithoutThePatchKeepsEverything() {
        List<Object> page = Arrays.asList(TimelineItems.post(), TimelineItems.of(TimelineObjectType.GEMINI_AD));
        assertEquals("the control: the switch is on to start", 1, TimelineFilter.filter(page).size());

        Settings.HIDE_ADS.save(false);
        assertSame(page, TimelineFilter.filter(page));
        Settings.HIDE_ADS.save(true);

        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertSame(page, TimelineFilter.filter(page));
        PauseForTests.resume();

        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertSame(page, TimelineFilter.filter(page));
    }

    @Test
    public void beforeTheSettingsAreReadyNothingIsTakenOut() {
        List<Object> page = Arrays.asList(TimelineItems.post(), TimelineItems.of(TimelineObjectType.GEMINI_AD));
        SettingsContextRule.withoutContext(() -> assertSame(page, TimelineFilter.filter(page)));
    }

    /** An item whose kind can't be read stays, and the failure is in the report. */
    @Test
    public void anItemThatCantBeReadIsKept() {
        List<Object> page = Arrays.asList(TimelineItems.unreadable(), TimelineItems.of(TimelineObjectType.NIMBUS_AD));
        List<?> shown = TimelineFilter.filter(page);
        assertEquals(1, shown.size());
        assertSame(page.get(0), shown.get(0));
        String report = String.join("\n", HookStatus.report(""));
        assertTrue(report, report.contains("timeline item"));
    }

    @Test
    public void eachSuggestionCardGoesOnlyWithItsOwnSwitch() {
        checkCards(Settings.HIDE_SUGGESTED_BLOGS, TimelineFilter.BLOG_TYPES);
        checkCards(Settings.HIDE_SUGGESTED_COMMUNITIES, TimelineFilter.COMMUNITY_TYPES);
        checkCards(Settings.HIDE_WATCH_IT_ROWS, TimelineFilter.WATCH_IT_TYPES);
    }

    /** With every card switch off, as they start, a page of cards and headings comes back as it was. */
    @Test
    public void theCardSwitchesStartOffAndKeepEverything() {
        List<Object> page = Arrays.asList(TimelineItems.of(TimelineObjectType.TITLE),
                TimelineItems.of(TimelineObjectType.BLOG_CARD), TimelineItems.of(TimelineObjectType.COMMUNITY_ROW),
                TimelineItems.of(TimelineObjectType.VIDEO_HUBS_ROW), TimelineItems.post(),
                TimelineItems.of(TimelineObjectType.TITLE));
        assertSame(page, TimelineFilter.filter(page));
    }

    @Test
    public void aCarouselGoesByItsFirstItem() {
        Settings.HIDE_SUGGESTED_BLOGS.save(true);
        Object blogs = TimelineItems.carousel(TimelineObjectType.CAROUSEL,
                TimelineItems.of(TimelineObjectType.TINY_BLOG_CAROUSEL_CARD),
                TimelineItems.of(TimelineObjectType.TINY_BLOG_CAROUSEL_CARD));
        Object avatars = TimelineItems.carousel(TimelineObjectType.AVATAR_CAROUSEL,
                TimelineItems.of(TimelineObjectType.BLOG_CARD));
        Object posts = TimelineItems.carousel(TimelineObjectType.CAROUSEL, TimelineItems.post(),
                TimelineItems.of(TimelineObjectType.BLOG_CARD));
        Object empty = TimelineItems.carousel(TimelineObjectType.CAROUSEL);
        List<Object> page = Arrays.asList(blogs, TimelineItems.post(), avatars, posts, empty);
        List<?> shown = TimelineFilter.filter(page);
        assertEquals(Arrays.asList(page.get(1), posts, empty), shown);
    }

    /** Without the patch's bridge the stub answers nothing, so every carousel stays. */
    @Test
    public void aCarouselWhoseItemsCantBeReadStays() {
        Settings.HIDE_SUGGESTED_BLOGS.save(true);
        Object blogs = TimelineItems.carousel(TimelineObjectType.CAROUSEL,
                TimelineItems.of(TimelineObjectType.BLOG_CARD));
        TimelineFilter.carouselItemsForTests = null;
        List<Object> page = Arrays.asList(blogs, TimelineItems.post());
        assertSame(page, TimelineFilter.filter(page));
    }

    /**
     * A heading goes with the card after it, one before a post stays, and one ending the page goes
     * while a card switch is on, since its card may start the next page.
     */
    @Test
    public void aHeadingGoesWithTheCardItIntroduces() {
        Settings.HIDE_SUGGESTED_COMMUNITIES.save(true);
        List<Object> page = Arrays.asList(TimelineItems.of(TimelineObjectType.TITLE),
                TimelineItems.of(TimelineObjectType.TITLE), TimelineItems.of(TimelineObjectType.COMMUNITY_CARD),
                TimelineItems.post(), TimelineItems.of(TimelineObjectType.TITLE), TimelineItems.post(),
                TimelineItems.of(TimelineObjectType.TITLE));
        List<?> shown = TimelineFilter.filter(page);
        assertEquals(Arrays.asList(page.get(3), page.get(4), page.get(5)), shown);
        String report = String.join("\n", HookStatus.report(""));
        assertTrue(report, report.contains("heading removed 3"));
        assertTrue(report, report.contains("card removed 1"));
    }

    /** With only Hide ads on, a heading before an ad goes with it and one ending the page stays. */
    @Test
    public void withOnlyHideAdsAHeadingEndingThePageStays() {
        List<Object> page = Arrays.asList(TimelineItems.of(TimelineObjectType.TITLE),
                TimelineItems.of(TimelineObjectType.GEMINI_AD), TimelineItems.post(),
                TimelineItems.of(TimelineObjectType.TITLE));
        List<?> shown = TimelineFilter.filter(page);
        assertEquals(Arrays.asList(page.get(2), page.get(3)), shown);
    }

    /** A community's own header card heads that community's page, so it isn't a suggestion. */
    @Test
    public void aCommunitysOwnHeaderCardStays() {
        Settings.HIDE_SUGGESTED_COMMUNITIES.save(true);
        List<Object> page = Arrays.asList(TimelineItems.of(TimelineObjectType.COMMUNITY_HUB_HEADER_CARD),
                TimelineItems.post());
        assertSame(page, TimelineFilter.filter(page));
    }

    /**
     * Each of [kinds] goes with [setting] on, and stays with it off, paused, or without its patch's
     * capability.
     */
    private static void checkCards(BooleanSetting setting, Set<String> kinds) {
        for (String kind : kinds) {
            List<Object> page = Arrays.asList(TimelineItems.post(), TimelineItems.of(TimelineObjectType.valueOf(kind)),
                    TimelineItems.post());
            assertSame(kind + " with the switch off", page, TimelineFilter.filter(page));
            setting.save(true);
            List<?> shown = TimelineFilter.filter(page);
            assertEquals(kind, Arrays.asList(page.get(0), page.get(2)), shown);
            PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
            assertSame(kind + " paused", page, TimelineFilter.filter(page));
            PauseForTests.resume();
            PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.TIMELINE_ADS));
            assertSame(kind + " without the patch", page, TimelineFilter.filter(page));
            PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
            setting.resetToDefault();
        }
    }
}
