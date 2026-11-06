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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.settings.TextSettings;

/** A long post takes several screens in a timeline when its author didn't cut it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class LongPostsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.COLLAPSE_LONG_POSTS.resetToDefault();
        TextSettings.LONG_POST_SIZE.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.COLLAPSE_LONG_POSTS.resetToDefault();
        TextSettings.LONG_POST_SIZE.resetToDefault();
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

    @Test public void thePickedLengthIsTheCap() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        TextSettings.LONG_POST_SIZE.save("20");
        assertEquals("about half a screen", rows.subList(0, 4), LongPosts.post(true, rows));
        TextSettings.LONG_POST_SIZE.save("80");
        assertEquals("about two screens", rows.subList(0, 16), LongPosts.post(true, rows));
        assertNull("two screens with 5 lines over stays whole", LongPosts.post(true, paragraphs(17, 5)));
        Integer cut = LongPosts.trail(null, null, rows, blocks(rows));
        assertEquals("a trail entry keeps the same rows", 16, tumblrKeeps(rows, blocks(rows), cut));
        for (String never : new String[]{"", "13", "40 ", "0", "-20", "eighty"}) {
            TextSettings.LONG_POST_SIZE.save(never);
            assertEquals("'" + never + "' reads as about a screen", rows.subList(0, 8), LongPosts.post(true, rows));
        }
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

    /** Every block of [rows], in order, the way a trail entry keeps them. */
    private static List<Object> blocks(List<?> rows) {
        List<Object> blocks = new ArrayList<>();
        for (Object row : rows) blocks.addAll(((HushBlockRow) row).hushBlocks());
        return blocks;
    }

    /**
     * The rows Tumblr 47.0's trail entry copies for [cut]: for each block of each row it adds the row
     * to a set, then stops at the first block whose index in [blocks] is the cut, and copies every
     * row when none is. So a row with no blocks is never copied and equal rows are copied once.
     */
    private static List<Object> tumblrCopies(List<?> rows, List<?> blocks, int cut) {
        Set<Object> copied = new LinkedHashSet<>();
        for (Object row : rows) {
            for (Object block : ((HushBlockRow) row).hushBlocks()) {
                copied.add(row);
                if (blocks.indexOf(block) == cut) return new ArrayList<>(copied);
            }
        }
        return new ArrayList<>(copied);
    }

    private static int tumblrKeeps(List<?> rows, List<?> blocks, int cut) {
        return tumblrCopies(rows, blocks, cut).size();
    }

    /** A row equal to another holding the same blocks, as Tumblr's block row compares. */
    private static final class EqualRow implements HushBlockRow {
        private final List<Object> blocks;

        EqualRow(List<Object> blocks) {
            this.blocks = blocks;
        }

        @Override public List<?> hushBlocks() {
            return blocks;
        }

        @Override public boolean equals(Object other) {
            return other instanceof EqualRow && ((EqualRow) other).blocks.equals(blocks);
        }

        @Override public int hashCode() {
            return blocks.hashCode();
        }
    }

    @Test public void aRowWithNoBlocksEndsWhatTheCutKeeps() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        rows.set(4, row());
        List<Object> blocks = blocks(rows);
        Integer cut = LongPosts.trail(null, null, rows, blocks);
        assertEquals("Tumblr never copies the empty fifth row, so the cut keeps the four above it",
                rows.subList(0, 4), tumblrCopies(rows, blocks, cut));
        rows.set(0, row());
        assertNull("an entry that starts with one stays whole", LongPosts.trail(null, null, rows, blocks(rows)));
        assertTrue(report(), report().contains(LongPosts.TRAIL_WHOLE + " 1"));
    }

    @Test public void aRowEqualToAnEarlierOneEndsWhatTheCutKeeps() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(new EqualRow(new ArrayList<>(paragraph(5).hushBlocks())));
        rows.set(5, new EqualRow(new ArrayList<>(((EqualRow) rows.get(2)).blocks)));
        List<Object> blocks = blocks(rows);
        Integer cut = LongPosts.trail(null, null, rows, blocks);
        assertEquals("Tumblr copies the sixth row once, as the third, so the cut keeps the five above it",
                rows.subList(0, 5), tumblrCopies(rows, blocks, cut));
    }

    @Test public void aLongTrailEntryIsCutWhereItsFirstScreenEnds() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        List<Object> blocks = blocks(rows);
        Integer cut = LongPosts.trail(null, null, rows, blocks);
        assertEquals("the last block of the eighth row", Integer.valueOf(7), cut);
        assertEquals("Tumblr keeps the first screen", 8, tumblrKeeps(rows, blocks, cut));
        assertEquals("Tumblr's own lists are left alone", 20, rows.size());
        assertTrue(report(), report().contains(LongPosts.TRAIL_COLLAPSED + " 1"));
    }

    @Test public void aRowOfSeveralBlocksIsKeptWhole() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) rows.add(row(new ImageBlock(), new ImageBlock()));
        List<Object> blocks = blocks(rows);
        Integer cut = LongPosts.trail(null, null, rows, blocks);
        // Two images across are eight lines each, so five rows fit the cap.
        assertEquals("a block of the fifth row", 5, tumblrKeeps(rows, blocks, cut));
    }

    @Test public void aBlockEqualToAnEarlierOneIsNeverTheCut() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add(row(new String("block " + i)));
        // A link card is four lines, so ten fit, but the tenth row's block equals the fourth's.
        rows.set(9, row(new String("block 3")));
        List<Object> blocks = blocks(rows);
        Integer cut = LongPosts.trail(null, null, rows, blocks);
        assertEquals("Tumblr would stop at the fourth row for that block, so the cut moves up a row",
                9, tumblrKeeps(rows, blocks, cut));
    }

    @Test public void anEntryNoBlockCanCutStaysWhole() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 20; i++) rows.add("row the bridge doesn't reach");
        assertNull(LongPosts.trail(null, null, rows, new ArrayList<>()));
        assertTrue(report(), report().contains(LongPosts.TRAIL_WHOLE + " 1"));
    }

    @Test public void anAuthorsCutAnAskOrAShortEntryKeepsTumblrsCut() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        List<Object> blocks = blocks(rows);
        assertEquals("the author's cut", Integer.valueOf(3), LongPosts.trail(3, null, rows, blocks));
        assertNull("an ask", LongPosts.trail(null, new Object(), rows, blocks));
        List<Object> brief = paragraphs(9, 5);
        assertNull("5 lines over isn't worth a cut", LongPosts.trail(null, null, brief, blocks(brief)));
        assertNull(LongPosts.trail(null, null, null, blocks));
        assertNull(LongPosts.trail(null, null, rows, null));
        assertTrue(report(), report().contains(LongPosts.TRAIL_SHORT + " 1"));
        assertFalse(report(), report().contains(LongPosts.TRAIL_COLLAPSED));
    }

    @Test public void offPausedOrWithoutItsHookTrailEntriesKeepTumblrsCut() {
        List<Object> rows = paragraphs(20, 5);
        List<Object> blocks = blocks(rows);
        assertNull("starts off", LongPosts.trail(null, null, rows, blocks));
        Settings.COLLAPSE_LONG_POSTS.save(true);
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertNull(reason.name(), LongPosts.trail(null, null, rows, blocks));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertNull(LongPosts.trail(null, null, rows, blocks)));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LONG_POST_ROWS));
        assertNull("a build without the trail hook", LongPosts.trail(null, null, rows, blocks));
        assertEquals("the post's own rows still collapse there", 8, LongPosts.post(true, rows).size());
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.LONG_TRAIL_ROWS));
        assertNull("and the other way round", LongPosts.post(true, rows));
        assertEquals(Integer.valueOf(7), LongPosts.trail(null, null, rows, blocks));
    }

    @Test public void aTrailRowThatThrowsKeepsTumblrsCutAndIsReported() {
        Settings.COLLAPSE_LONG_POSTS.save(true);
        List<Object> rows = paragraphs(20, 5);
        List<Object> blocks = blocks(rows);
        rows.add(0, (HushBlockRow) () -> { throw new IllegalStateException("parcel"); });
        assertNull(LongPosts.trail(null, null, rows, blocks));
        List<String> missing = HookStatus.missing(FamilyNames.COLLAPSE_LONG_POSTS);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains(LongPosts.TRAIL_STEP));
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
