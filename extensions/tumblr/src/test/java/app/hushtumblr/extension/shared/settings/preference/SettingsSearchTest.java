/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at 44fb933 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.hushtumblr.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The settings search's matching, over rows given as (title, summary, key) and their extra words. */
public class SettingsSearchTest {
    private static final List<SettingsSearch.Entry> ROWS = Arrays.asList(
            new SettingsSearch.Entry("hide_ads", "Hide ads", "Takes ads and paid Blaze posts out of your dashboard.", "Feed"),
            new SettingsSearch.Entry("ad_id", "Hide advertising ID", "Gives apps an empty advertising ID.", "Privacy",
                    "Ad tracking"),
            new SettingsSearch.Entry("push", "Fix push notifications", "Lets Tumblr sign up for push notifications.",
                    "Notifications", "Push alerts"),
            new SettingsSearch.Entry("cafe", "Café mode", null, "Interface"),
            new SettingsSearch.Entry("bare", "Bare row", null));

    @Test
    public void everyWordHasToMatchInAnyOrderAndAnyText() {
        assertEquals(keys("ad_id"), keys(SettingsSearch.filter(ROWS, "advertising empty")));
        // One word in the title, one in the summary, one in the section's name.
        assertEquals(keys("hide_ads"), keys(SettingsSearch.filter(ROWS, "feed blaze ads")));
        assertEquals(keys("hide_ads", "ad_id"), keys(SettingsSearch.filter(ROWS, "hide")));
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "blaze advertising")));
    }

    @Test
    public void accentsCaseAndSpacesDoNotMatter() {
        assertEquals(keys("cafe"), keys(SettingsSearch.filter(ROWS, "  CAFE   Mode ")));
        assertEquals(keys("cafe"), keys(SettingsSearch.filter(ROWS, "café")));
        assertEquals("cafe", SettingsSearch.normalized("CAFÉ"));
        assertEquals(Arrays.asList("alerts", "push"), SettingsSearch.terms("  ÁLERTS \t push "));
    }

    @Test
    public void extraWordsFindARowButTheKeyDoesNot() {
        assertEquals(keys("push"), keys(SettingsSearch.filter(ROWS, "alerts")));
        assertEquals(keys("ad_id"), keys(SettingsSearch.filter(ROWS, "tracking")));
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "hide_ads")));
    }

    @Test
    public void textARowDoesNotHaveAddsNothing() {
        // A row with no summary used to carry the word "null" into the search text.
        assertEquals(keys(), keys(SettingsSearch.filter(ROWS, "null")));
        assertTrue(SettingsSearch.matches("bare", "Bare row", null));
        assertFalse(SettingsSearch.matches("null", (CharSequence) null));
    }

    @Test
    public void aBlankQueryKeepsEveryRow() {
        assertEquals(ROWS.size(), SettingsSearch.filter(ROWS, "   ").size());
        assertEquals(ROWS.size(), SettingsSearch.filter(ROWS, null).size());
        assertEquals(Collections.emptyList(), SettingsSearch.terms(""));
        assertTrue(SettingsSearch.matches("", "anything"));
    }

    private static List<String> keys(String... keys) {
        return Arrays.asList(keys);
    }

    private static List<String> keys(List<SettingsSearch.Entry> entries) {
        List<String> keys = new ArrayList<>();
        for (SettingsSearch.Entry entry : entries) keys.add(entry.key);
        return keys;
    }
}
