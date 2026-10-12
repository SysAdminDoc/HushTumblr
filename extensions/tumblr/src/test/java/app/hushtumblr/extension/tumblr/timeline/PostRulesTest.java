/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.tumblr.rumblr.model.TimelineObject;
import com.tumblr.rumblr.model.TimelineObjectType;
import com.tumblr.rumblr.model.post.blocks.ImageBlock;
import com.tumblr.rumblr.model.post.blocks.TextBlock;
import com.tumblr.rumblr.model.post.blocks.VideoBlock;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.settings.TextSettings;

/** What the post switches take out of a page: reblogs, posts by kind, and posts with a hidden word. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PostRulesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void everyPatchIsIn() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.HIDE_ADS.save(false);
    }

    @After
    public void restore() {
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        Settings.HIDE_ADS.resetToDefault();
        Settings.HIDE_REBLOGS.resetToDefault();
        Settings.HIDE_VIDEO_POSTS.resetToDefault();
        Settings.HIDE_PHOTO_POSTS.resetToDefault();
        Settings.HIDE_TEXT_POSTS.resetToDefault();
        Settings.HIDE_POSTS_WITH_WORDS.resetToDefault();
        TextSettings.HIDDEN_WORDS.resetToDefault();
        HookStatus.clear();
    }

    private static final TimelineObject TEXT = TimelineItems.postWith(new TextBlock("hello"));
    private static final TimelineObject PHOTO = TimelineItems.postWith(new ImageBlock(), new TextBlock("caption"));
    private static final TimelineObject VIDEO = TimelineItems.postWith(new ImageBlock(), new VideoBlock());
    private static final TimelineObject YOUTUBE = TimelineItems.postWith(new VideoBlock.YouTube());

    private static List<?> shown(Object... items) {
        return TimelineFilter.filter(Arrays.asList(items));
    }

    @Test
    public void withEverySwitchOffEveryPostStays() {
        List<Object> page = Arrays.asList(TEXT, PHOTO, VIDEO);
        assertSame(page, TimelineFilter.filter(page));
    }

    /** Any video makes a video post, pictures without one a photo post, and anything else is text. */
    @Test
    public void aPostsKindComesFromAllItsBlocks() {
        Settings.HIDE_VIDEO_POSTS.save(true);
        assertEquals(Arrays.asList(TEXT, PHOTO), shown(TEXT, PHOTO, VIDEO, YOUTUBE));
        Settings.HIDE_VIDEO_POSTS.save(false);
        Settings.HIDE_PHOTO_POSTS.save(true);
        assertEquals(Arrays.asList(TEXT, VIDEO, YOUTUBE), shown(TEXT, PHOTO, VIDEO, YOUTUBE));
        Settings.HIDE_PHOTO_POSTS.save(false);
        Settings.HIDE_TEXT_POSTS.save(true);
        HookStatus.clear();
        assertEquals(Arrays.asList(PHOTO, VIDEO, YOUTUBE), shown(TEXT, PHOTO, VIDEO, YOUTUBE));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(FamilyNames.HIDE_TEXT_POSTS) && report.contains("post removed 1"));
    }

    /** A reblog's kind includes the post it reblogs, so a text comment on a video is a video post. */
    @Test
    public void aReblogsTrailCountsTowardItsKind() {
        Settings.HIDE_VIDEO_POSTS.save(true);
        TimelineObject reblog = TimelineItems.reblogOf(Collections.singletonList(new VideoBlock()), new TextBlock("lol"));
        assertEquals(Collections.singletonList(TEXT), shown(reblog, TEXT));
    }

    @Test
    public void aPostWithNoBlocksHasNoKindAndStays() {
        Settings.HIDE_TEXT_POSTS.save(true);
        TimelineObject empty = TimelineItems.postWith();
        assertEquals(Collections.singletonList(empty), shown(empty));
    }

    @Test
    public void hideReblogsTakesOutReblogsAndLeavesOriginalPosts() {
        Settings.HIDE_REBLOGS.save(true);
        TimelineObject reblog = TimelineItems.reblogOf(Collections.singletonList(new TextBlock("first")));
        assertEquals(Arrays.asList(TEXT, PHOTO), shown(TEXT, reblog, PHOTO));
    }

    /**
     * A campaign in your own Blaze dashboard carries your post, but it isn't a post in a feed, so no
     * post switch takes it out. The same posts in a feed go, as the control.
     */
    @Test
    public void aBlazeCampaignIsLeftAloneByThePostSwitches() {
        Settings.HIDE_REBLOGS.save(true);
        Settings.HIDE_VIDEO_POSTS.save(true);
        Settings.HIDE_TEXT_POSTS.save(true);
        TimelineObject reblog = TimelineItems.reblogOf(Collections.singletonList(new TextBlock("first")));
        TimelineObject campaign = TimelineItems.blazed(VIDEO);
        TimelineObject reblogCampaign = TimelineItems.blazed(reblog);
        TimelineObject textCampaign = TimelineItems.blazed(TEXT);
        assertEquals(Arrays.asList(campaign, reblogCampaign, textCampaign),
                shown(campaign, VIDEO, reblogCampaign, reblog, textCampaign, TEXT));
    }

    @Test
    public void wordsMatchWholeWordsIgnoringCaseInTextTrailTagsAndSummary() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        TextSettings.HIDDEN_WORDS.save("Cat\n  big news , ");
        TimelineObject inText = TimelineItems.postWith(new TextBlock("My CAT, again."));
        TimelineObject inTrail = TimelineItems.reblogOf(Collections.singletonList(new TextBlock("BIG NEWS: soon")),
                new TextBlock("lol"));
        TimelineObject inTag = TimelineItems.postTagged(null, "cat");
        TimelineObject inSummary = TimelineItems.postTagged("a cat");
        TimelineObject partOfAWord = TimelineItems.postWith(new TextBlock("category concatenate"));
        assertEquals(Collections.singletonList(partOfAWord), shown(inText, inTrail, inTag, inSummary, partOfAWord));
    }

    @Test
    public void anEmptyWordListHidesNothing() {
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        TextSettings.HIDDEN_WORDS.save(" ,\n ");
        List<Object> page = Arrays.asList(TEXT, PHOTO);
        assertSame(page, TimelineFilter.filter(page));
    }

    @Test
    public void theWordListIsOneEntryALineOrByCommasTrimmedAndOnce() {
        assertEquals(Arrays.asList("cat", "big news", "dog"), PostRules.wordList("Cat\r\n big news,dog, CAT,,"));
        assertTrue(PostRules.wordList("").isEmpty());
        assertTrue(PostRules.containsWord("#cat pics", "cat"));
        assertTrue(PostRules.containsWord("cat", "cat"));
        assertFalse(PostRules.containsWord("cats", "cat"));
        assertFalse(PostRules.containsWord("bobcat", "cat"));
    }

    /** A heading in front of a post the post switches take out heads the posts after it too. */
    @Test
    public void aHeadingStaysWhenThePostAfterItGoes() {
        Settings.HIDE_TEXT_POSTS.save(true);
        TimelineObject heading = TimelineItems.of(TimelineObjectType.TITLE);
        assertEquals(Arrays.asList(heading, PHOTO), shown(heading, TEXT, PHOTO));
    }

    @Test
    public void whilePausedEveryPostStays() {
        Settings.HIDE_TEXT_POSTS.save(true);
        Settings.HIDE_POSTS_WITH_WORDS.save(true);
        TextSettings.HIDDEN_WORDS.save("hello");
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        try {
            List<Object> page = Arrays.asList(TEXT, PHOTO);
            assertSame(page, TimelineFilter.filter(page));
        } finally {
            PauseForTests.resume();
        }
    }

    @Test
    public void aPostThatThrowsIsKeptAndReported() {
        Settings.HIDE_TEXT_POSTS.save(true);
        TimelineObject broken = new TimelineObject(new TimelineItems.Post(null, null, null, null, null) {
            @Override public List<?> hushContent() { throw new IllegalStateException("unreadable"); }
        }, false);
        assertEquals(Collections.singletonList(broken), shown(broken));
        assertEquals(Collections.singletonList("a working 'timeline post' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.HIDE_TEXT_POSTS));
    }
}
