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

import androidx.annotation.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How the settings search matches a row: every word typed has to appear somewhere in the row's
 * texts, in any order, with accents and case ignored. "cafe" finds "Café", and "links strip"
 * finds "Strip link tracking". A text a row doesn't have adds nothing, so a row with no summary
 * can't come up for the word "null".
 *
 * <p>Kept apart from the screen so the matching can be tested without one, and so every Hush
 * settings screen matches the same way.
 */
public final class SettingsSearch {
    private SettingsSearch() {
    }

    /** One row as the search sees it: its key, its title and summary, and any other words that should find it. */
    public static final class Entry {
        @Nullable public final String key;
        @Nullable public final CharSequence title;
        @Nullable public final CharSequence summary;
        private final CharSequence[] extra;

        public Entry(@Nullable String key, @Nullable CharSequence title, @Nullable CharSequence summary,
                     CharSequence... extra) {
            this.key = key;
            this.title = title;
            this.summary = summary;
            this.extra = extra == null ? new CharSequence[0] : extra.clone();
        }

        /** The texts a query is matched against. The key isn't one of them: nobody types a key. */
        CharSequence[] texts() {
            CharSequence[] texts = Arrays.copyOf(extra, extra.length + 2);
            texts[extra.length] = title;
            texts[extra.length + 1] = summary;
            return texts;
        }
    }

    /** Text as the search compares it: accents taken off, lower case in no particular language. */
    public static String normalized(@Nullable CharSequence value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }

    /** The words of [query], normalized. None for a blank query. */
    public static List<String> terms(@Nullable CharSequence query) {
        String text = normalized(query).trim();
        if (text.isEmpty()) return Collections.emptyList();
        return Collections.unmodifiableList(Arrays.asList(text.split("\\s+")));
    }

    /** Whether every word of [query] is in one of [texts]. A blank query asks for nothing, so it matches. */
    public static boolean matches(@Nullable CharSequence query, CharSequence... texts) {
        return matchesTerms(terms(query), texts);
    }

    /** As {@link #matches}, for words already split by {@link #terms}, so a long list splits the query once. */
    public static boolean matchesTerms(List<String> terms, CharSequence... texts) {
        if (terms.isEmpty()) return true;
        String[] normalized = new String[texts == null ? 0 : texts.length];
        for (int i = 0; i < normalized.length; i++) normalized[i] = normalized(texts[i]);
        for (String term : terms) {
            boolean found = false;
            for (String text : normalized) {
                if (text.contains(term)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    /** The entries [query] finds, in the order given. A blank query keeps them all. */
    public static List<Entry> filter(List<Entry> entries, @Nullable CharSequence query) {
        List<String> terms = terms(query);
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries) if (matchesTerms(terms, entry.texts())) found.add(entry);
        return found;
    }
}
