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

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** What Hide ads takes out of a page of posts, and what it leaves. */
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
}
