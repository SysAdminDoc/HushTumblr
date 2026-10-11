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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.settings.TextSettings;

/**
 * The post switches of the timeline filter: Hide reblogs, Hide video posts, Hide photo posts, Hide
 * text posts and Hide posts with these words. {@link TimelineFilter} asks {@link #now} once a page
 * and hands each item's payload to {@link #removes}.
 *
 * <p>A post is read through {@link HushPost}, which the post bridge patch adds to Tumblr's post
 * model. A paid Blaze post carries its post inside, read by {@code getPost}, a name Tumblr keeps.
 * Anything that can't be read is kept.
 *
 * <p>A post's kind comes from all of its blocks, the ones in its reblog trail and its own: any
 * video block makes it a video post, any image block without a video makes it a photo post, and
 * everything else is a text post. A post with no blocks at all has no kind and stays.
 */
public final class PostRules {
    static final String VIDEO_BLOCK = "com.tumblr.rumblr.model.post.blocks.VideoBlock";
    static final String IMAGE_BLOCK = "com.tumblr.rumblr.model.post.blocks.ImageBlock";
    static final String BLAZED_POST = "com.tumblr.rumblr.model.BlazedPost";

    /** The families whose switches are here. A heading never goes with one of their posts. */
    static final Set<String> FAMILIES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            FamilyNames.HIDE_REBLOGS, FamilyNames.HIDE_VIDEO_POSTS, FamilyNames.HIDE_PHOTO_POSTS,
            FamilyNames.HIDE_TEXT_POSTS, FamilyNames.HIDE_POSTS_WITH_WORDS)));

    enum Kind { VIDEO, PHOTO, TEXT }

    final boolean reblogs;
    final boolean video;
    final boolean photo;
    final boolean text;
    /** The phrases to look for, lower case. Empty when the switch is off or the list is. */
    final List<String> words;

    PostRules(boolean reblogs, boolean video, boolean photo, boolean text, List<String> words) {
        this.reblogs = reblogs;
        this.video = video;
        this.photo = photo;
        this.text = text;
        this.words = words;
    }

    /** Which kind each block class is, null for neither, so a class is looked up once. */
    private static final Map<Class<?>, Kind> BLOCK_KINDS = new ConcurrentHashMap<>();

    /** The last word list read and what it parsed to, so a page doesn't parse it again. */
    @Nullable
    private static volatile Parsed parsed;

    /** {@code BlazedPost.getPost}, found the first time a Blaze post is read. */
    @Nullable
    private static volatile Method blazedPost;

    /** The post switches on now, or null when none is. Throws for TimelineFilter to report. */
    @Nullable
    static PostRules now() {
        boolean reblogs = on(PatchFamily.Capability.REBLOG_POSTS, FamilyNames.HIDE_REBLOGS, Settings.HIDE_REBLOGS);
        boolean video = on(PatchFamily.Capability.VIDEO_POSTS, FamilyNames.HIDE_VIDEO_POSTS, Settings.HIDE_VIDEO_POSTS);
        boolean photo = on(PatchFamily.Capability.PHOTO_POSTS, FamilyNames.HIDE_PHOTO_POSTS, Settings.HIDE_PHOTO_POSTS);
        boolean text = on(PatchFamily.Capability.TEXT_POSTS, FamilyNames.HIDE_TEXT_POSTS, Settings.HIDE_TEXT_POSTS);
        List<String> words = on(PatchFamily.Capability.POST_WORDS, FamilyNames.HIDE_POSTS_WITH_WORDS,
                Settings.HIDE_POSTS_WITH_WORDS) ? wordList(TextSettings.HIDDEN_WORDS.get()) : Collections.emptyList();
        if (!reblogs && !video && !photo && !text && words.isEmpty()) return null;
        return new PostRules(reblogs, video, photo, text, words);
    }

    /** Whether [capability]'s hook is in and [family]'s switch is on. */
    private static boolean on(PatchFamily.Capability capability, String family, BooleanSetting setting) {
        if (!capability.installed()) return false;
        HookStatus.invoked(family);
        return setting.get();
    }

    /** The first switch that's on, the family a failure is reported under. */
    String owner() {
        if (reblogs) return FamilyNames.HIDE_REBLOGS;
        if (video) return FamilyNames.HIDE_VIDEO_POSTS;
        if (photo) return FamilyNames.HIDE_PHOTO_POSTS;
        if (text) return FamilyNames.HIDE_TEXT_POSTS;
        return FamilyNames.HIDE_POSTS_WITH_WORDS;
    }

    /**
     * The patch name of the first switch that takes out the post in [payload], an item's data, or
     * null to keep it: anything that isn't a post, and a post that can't be read.
     */
    @Nullable
    String removes(@Nullable Object payload) throws ReflectiveOperationException {
        HushPost post = postOf(payload);
        if (post == null) return null;
        List<?> trail = post.hushTrail();
        if (reblogs && isReblog(post, trail)) return FamilyNames.HIDE_REBLOGS;
        if (video || photo || text) {
            Kind kind = kindOf(post, trail);
            if (video && kind == Kind.VIDEO) return FamilyNames.HIDE_VIDEO_POSTS;
            if (photo && kind == Kind.PHOTO) return FamilyNames.HIDE_PHOTO_POSTS;
            if (text && kind == Kind.TEXT) return FamilyNames.HIDE_TEXT_POSTS;
        }
        if (!words.isEmpty() && hasWord(post, trail, words)) return FamilyNames.HIDE_POSTS_WITH_WORDS;
        return null;
    }

    /** The post [payload] is or carries, or null when it's neither. */
    @Nullable
    static HushPost postOf(@Nullable Object payload) throws ReflectiveOperationException {
        if (payload instanceof HushPost) return (HushPost) payload;
        if (payload == null || !BLAZED_POST.equals(payload.getClass().getName())) return null;
        Method getter = blazedPost;
        if (getter == null || getter.getDeclaringClass() != payload.getClass()) {
            getter = payload.getClass().getMethod("getPost");
            blazedPost = getter;
        }
        Object inner = getter.invoke(payload);
        return inner instanceof HushPost ? (HushPost) inner : null;
    }

    /** A reblog says which post it reblogs, and carries the posts before it in its trail. */
    static boolean isReblog(HushPost post, @Nullable List<?> trail) {
        String from = post.hushRebloggedFrom();
        return (from != null && !from.isEmpty()) || (trail != null && !trail.isEmpty());
    }

    /** The kind of [post] from all of its blocks, or null when it has none. */
    @Nullable
    static Kind kindOf(HushPost post, @Nullable List<?> trail) {
        boolean any = false;
        boolean image = false;
        for (List<?> blocks : blockLists(post, trail)) {
            for (Object block : blocks) {
                if (block == null) continue;
                any = true;
                Kind kind = blockKind(block.getClass());
                if (kind == Kind.VIDEO) return Kind.VIDEO;
                if (kind == Kind.PHOTO) image = true;
            }
        }
        if (!any) return null;
        return image ? Kind.PHOTO : Kind.TEXT;
    }

    /** The trail entries' blocks, then the post's own. */
    private static List<List<?>> blockLists(HushPost post, @Nullable List<?> trail) {
        List<List<?>> lists = new ArrayList<>();
        if (trail != null) {
            for (Object entry : trail) {
                List<?> blocks = entry instanceof HushTrail ? ((HushTrail) entry).hushContent() : null;
                if (blocks != null) lists.add(blocks);
            }
        }
        List<?> own = post.hushContent();
        if (own != null) lists.add(own);
        return lists;
    }

    /** VIDEO for a video block of any sort, PHOTO for an image block, TEXT for anything else. */
    static Kind blockKind(Class<?> type) {
        Kind known = BLOCK_KINDS.get(type);
        if (known != null) return known;
        Kind kind = Kind.TEXT;
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            if (VIDEO_BLOCK.equals(at.getName())) {
                kind = Kind.VIDEO;
                break;
            }
            if (IMAGE_BLOCK.equals(at.getName())) {
                kind = Kind.PHOTO;
                break;
            }
        }
        BLOCK_KINDS.put(type, kind);
        return kind;
    }

    /** Whether any text block, tag or the summary of [post] holds one of [words] as whole words. */
    static boolean hasWord(HushPost post, @Nullable List<?> trail, List<String> words) {
        for (List<?> blocks : blockLists(post, trail)) {
            for (Object block : blocks) {
                if (block instanceof HushText && matches(((HushText) block).hushText(), words)) return true;
            }
        }
        List<?> tags = post.hushTags();
        if (tags != null) {
            for (Object tag : tags) {
                if (tag != null && matches(tag.toString(), words)) return true;
            }
        }
        return matches(post.hushSummary(), words);
    }

    /** Whether [text] holds any of [words], ignoring case, each as whole words. */
    static boolean matches(@Nullable String text, List<String> words) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (containsWord(lower, word)) return true;
        }
        return false;
    }

    /**
     * Whether [phrase] is in [text] with no letter or digit right before or after it, so "cat"
     * finds "Cat pics" and "my cat." but not "category". Both are lower case.
     */
    static boolean containsWord(String text, String phrase) {
        int from = 0;
        while (true) {
            int at = text.indexOf(phrase, from);
            if (at < 0) return false;
            int end = at + phrase.length();
            boolean starts = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean ends = end == text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (starts && ends) return true;
            from = at + 1;
        }
    }

    /**
     * The phrases in [saved]: one a line or split by commas, trimmed, lower case, each once, in the
     * order they were written. Blank entries are dropped.
     */
    public static List<String> wordList(@Nullable String saved) {
        if (saved == null || saved.trim().isEmpty()) return Collections.emptyList();
        Parsed last = parsed;
        if (last != null && last.saved.equals(saved)) return last.words;
        Set<String> words = new LinkedHashSet<>();
        for (String entry : saved.split("[,\\n\\r]")) {
            String word = entry.trim().toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) words.add(word);
        }
        List<String> list = Collections.unmodifiableList(new ArrayList<>(words));
        parsed = new Parsed(saved, list);
        return list;
    }

    private static final class Parsed {
        final String saved;
        final List<String> words;

        Parsed(String saved, List<String> words) {
            this.saved = saved;
            this.words = words;
        }
    }
}
