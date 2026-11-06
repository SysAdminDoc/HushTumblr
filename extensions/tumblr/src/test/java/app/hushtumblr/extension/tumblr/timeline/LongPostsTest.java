/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.timeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

import java.util.ArrayList;
import java.util.Arrays;
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

/** A long post takes several screens in a timeline when its author didn't cut it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LongPostsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.COLLAPSE_LONG_POSTS.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.COLLAPSE_LONG_POSTS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private static HushBlockRow row(Object... blocks) {
        List<Object> list = Arrays.asList(blocks);
        return () -> list;
    }

    /** A paragraph [lines] lines long as the estimate reads it. */
    private static HushBlockRow paragraph(int lines) {
        char[] text = new char[(lines - 1) * LongPosts.LINE];
        Arrays.fill(text, 'a');
        return row(new TextBlock(new String(text)));
    }

    private static List<Object> paragraphs(int count, int lines) {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(paragraph(lines));
        return rows;
    }

    private String report() {
        return String.join("\n", HookStatus.report());
    }

    @Test public void startsOffSoTumblrsRowsStay() {
        assertFalse(Settings.COLLAPSE_LONG_POSTS.get());
        assertNull(LongPosts.post(true, paragraphs(20, 5)));
        assertFalse(report(), report().contains("Counted"));
    }

    @Test public void aLongPostKeepsItsFirstScreen() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        List<Object> shown = LongPosts.post(true, rows);
        assertEquals("eight five line paragraphs fill the cap", rows.subList(0, 8), shown);
        assertEquals("Tumblr's own list is left alone", 20, rows.size());
        assertTrue(report(), report().contains(LongPosts.COLLAPSED + " 1"));
    }

    @Test public void aShortPostOrOneJustOverTheCapStaysWhole() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        assertNull("40 lines fit", LongPosts.post(true, paragraphs(8, 5)));
        assertNull("5 lines over isn't worth a cut", LongPosts.post(true, paragraphs(9, 5)));
        assertEquals("10 lines over is", 8, LongPosts.post(true, paragraphs(10, 5)).size());
        assertTrue(report(), report().contains(LongPosts.SHORT + " 2"));
    }

    @Test public void theFirstRowAlwaysStays() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = new ArrayList<>(paragraphs(1, 60));
        rows.addAll(paragraphs(3, 5));
        assertEquals(rows.subList(0, 1), LongPosts.post(true, rows));
        assertNull("a single block can't be cut inside", LongPosts.post(true, paragraphs(1, 200)));
    }

    @Test public void imagesAndVideosCountByTheirHeight() {
        assertEquals(LongPosts.IMAGE, LongPosts.lines(row(new ImageBlock())));
        assertEquals("three across share the width", 5, LongPosts.lines(row(new ImageBlock(), new ImageBlock(), new ImageBlock())));
        assertEquals("never under a link card", LongPosts.OTHER,
                LongPosts.lines(row(new ImageBlock(), new ImageBlock(), new ImageBlock(), new ImageBlock(), new ImageBlock())));
        assertEquals(LongPosts.VIDEO, LongPosts.lines(row(new VideoBlock.YouTube())));
        assertEquals("a block the bridge can't read", LongPosts.OTHER, LongPosts.lines(row(new Object())));
        assertEquals("a row the bridge doesn't reach", LongPosts.OTHER, LongPosts.lines("row"));
        assertEquals(LongPosts.OTHER, LongPosts.lines(row()));
        assertEquals(1, LongPosts.lines(row(new TextBlock(null))));
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> photoset = new ArrayList<>();
        for (int i = 0; i < 10; i++) photoset.add(row(new ImageBlock()));
        assertEquals("two full width images fit", 2, LongPosts.post(true, photoset).size());
    }

    @Test public void whereTumblrDrawsNoCutsTheRowsStay() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        assertNull("the post opened by itself", LongPosts.post(false, paragraphs(20, 5)));
        assertNull(LongPosts.post(true, null));
        assertFalse(report(), report().contains("Counted"));
    }

    @Test public void pausedOrWithoutItsHookOrSettingsTumblrsRowsStay() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertNull(reason.name(), LongPosts.post(true, paragraphs(20, 5)));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertNull(LongPosts.post(true, paragraphs(20, 5))));
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertNull(LongPosts.post(true, paragraphs(20, 5)));
    }

    @Test public void aRowThatThrowsKeepsTumblrsRowsAndIsReported() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        rows.add(0, (HushBlockRow) () -> { throw new IllegalStateException("parcel"); });
        assertNull(LongPosts.post(true, rows));
        List<String> missing = HookStatus.missing(FamilyNames.COLLAPSE_LONG_POSTS);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(LongPosts.STEP));
    }
}
