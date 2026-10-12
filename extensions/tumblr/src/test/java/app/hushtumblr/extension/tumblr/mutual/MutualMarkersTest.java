/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mutual;

import static org.junit.Assert.*;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.Shadows;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.rootmute.HushAccountIdentity;
import app.hushtumblr.extension.tumblr.rootmute.MatureAccountsForTests;
import app.hushtumblr.extension.tumblr.rootmute.RootPostMutes;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class MutualMarkersTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private final List<Runnable> work = new ArrayList<>();
    private Source source;
    private long clock;

    @Before public void setUp() {
        MatureAccountsForTests.reset(); MutualMarkers.resetForTests();
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.SHOW_MUTUAL_MARKERS.resetToDefault(); PauseForTests.resume();
        clock = 1_000; MutualMarkers.clockForTests = () -> clock;
        MutualMarkers.workerForTests = work::add;
        source = new Source(); MutualMarkers.sourceForTests(source);
    }
    @After public void restore() {
        MutualMarkers.resetForTests(); MatureAccountsForTests.restore();
        Settings.SHOW_MUTUAL_MARKERS.resetToDefault(); PauseForTests.resume();
        PatchFamilyForTests.inBuild(null); PatchFamilyForTests.capabilities(null);
    }
    private void enabled() { Settings.SHOW_MUTUAL_MARKERS.save(true); account("account-a", new Blog("primary", "primary-uuid", true), new Blog("side", "side-uuid", false)); }
    private void account(String id, Blog... blogs) { MatureAccountsForTests.commit(new Account(id, List.of(blogs))); }
    private TextView view(String name) { TextView view = new TextView(Utils.getContext()); view.setText(name); view.setContentDescription(name + " native description"); return view; }
    private void bind(TextView view, String uuid) { MutualMarkers.bind(view, new Blog("friend", uuid, false), "friend"); }
    private void run() { work.remove(0).run(); Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    private void tick() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1)); }

    @Test public void defaultOffStartupAndUnknownAccountNeverFetchOrChangeNativeText() {
        account("account-a", new Blog("primary", "primary-uuid", true));
        TextView name = view("friend"); bind(name, "friend-uuid");
        assertFalse(Settings.SHOW_MUTUAL_MARKERS.get()); assertEquals("friend", name.getText().toString()); assertTrue(work.isEmpty());
        Settings.SHOW_MUTUAL_MARKERS.save(true); RootPostMutes.logout(); bind(name, "friend-uuid"); assertTrue(work.isEmpty());
        SettingsContextRule.withoutContext(() -> bind(name, "friend-uuid"));
        SettingsContextRule.beforeThePauseIsDecided(() -> bind(name, "friend-uuid")); assertTrue(work.isEmpty());
    }
    @Test public void hundredsOfHeadersShareOnePrimaryBlogReadAndOnlyMutualUuidGetsMarked() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false)),
                new Follower(false, new Blog("other", "other-uuid", false)), new Object()), null));
        TextView friend = view("friend"), other = view("other");
        bind(friend, "friend-uuid"); for (int i = 0; i < 100; i++) bind(view("friend"), "friend-uuid");
        MutualMarkers.bind(other, new Blog("other", "other-uuid", false), "other");
        assertEquals(1, work.size()); assertEquals("friend", friend.getText().toString()); run();
        assertEquals(List.of("primary.tumblr.com"), source.first); assertEquals("friend · Mutual", friend.getText().toString());
        assertEquals("other", other.getText().toString()); assertEquals(1, source.executions);
        bind(view("friend"), "friend-uuid"); assertTrue(work.isEmpty());
    }
    @Test public void sideblogMissingPrimaryDuplicatePrimaryAndNameMismatchRemainUnknown() {
        Settings.SHOW_MUTUAL_MARKERS.save(true);
        for (Blog[] blogs : new Blog[][] { {}, {new Blog("side", "side-uuid", false)},
                {new Blog("one", "one-uuid", true), new Blog("two", "two-uuid", true)}, {new Blog("bad/name", "uuid", true)} }) {
            account("account-a", blogs); bind(view("friend"), "friend-uuid"); assertTrue(work.isEmpty());
        }
        enabled(); TextView name = view("source"); MutualMarkers.bind(name, new Blog("friend", "friend-uuid", false), "source");
        assertTrue(work.isEmpty()); assertEquals("source", name.getText().toString());
    }
    @Test public void allPagesStayInTheCapturedPrimaryScopeAndPublishOnlyAfterCompletion() {
        enabled(); String next = "https://api.tumblr.com/v2/blog/primary.tumblr.com/followers?offset=100&limit=100";
        source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), next));
        source.pages.add(page(List.of(new Follower(true, new Blog("second", "second-uuid", false))), null));
        TextView name = view("friend"); bind(name, "friend-uuid"); run();
        assertEquals(List.of(next), source.next); assertEquals(2, source.executions); assertEquals("friend · Mutual", name.getText().toString());
        assertTrue(source.timeouts.stream().allMatch(value -> value > 0 && value <= 15_000));
    }
    @Test public void maliciousSideblogPaginationAndCyclesNeverPublishPartialEvidence() {
        String[] bad = {"https://evil.example/v2/blog/primary.tumblr.com/followers", "https://api.tumblr.com/v2/blog/side.tumblr.com/followers",
                "//api.tumblr.com/v2/blog/primary.tumblr.com/followers", "http://api.tumblr.com/v2/blog/primary.tumblr.com/followers",
                "/v2/blog/primary.tumblr.com/posts", "https://x@api.tumblr.com/v2/blog/primary.tumblr.com/followers", "x".repeat(4097)};
        for (String next : bad) {
            enabled(); source.pages.clear(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), next));
            TextView name = view("friend"); bind(name, "friend-uuid"); run(); assertEquals("friend", name.getText().toString());
        }
        enabled(); String cycle = "/v2/blog/primary.tumblr.com/followers?offset=100";
        source.pages.clear(); source.pages.add(page(List.of(), cycle)); source.pages.add(page(List.of(), cycle));
        bind(view("friend"), "friend-uuid"); run(); assertTrue(work.isEmpty());
    }
    @Test public void boundedPagesAndRowsFailOpenAndFailuresBackOff() {
        enabled(); for (int i = 0; i < MutualMarkers.MAX_PAGES; i++) source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))),
                "/v2/blog/primary.tumblr.com/followers?offset=" + (i + 1) * 100));
        TextView name = view("friend"); bind(name, "friend-uuid"); run(); assertEquals(MutualMarkers.MAX_PAGES, source.executions); assertEquals("friend", name.getText().toString());
        for (int i = 0; i < 100; i++) bind(name, "friend-uuid"); assertTrue(work.isEmpty());
        clock += MutualMarkers.RETRY; source.pages.clear(); source.pages.add(page(java.util.Collections.nCopies(101, new Object()), null));
        bind(name, "friend-uuid"); run(); assertEquals("friend", name.getText().toString());
    }
    @Test public void staleQueuedWorkAndCompletionsCannotCrossAccountEpochOrGeneration() {
        enabled(); TextView name = view("friend"); bind(name, "friend-uuid");
        RootPostMutes.logout(); run(); assertEquals(0, source.executions);
        enabled(); bind(name, "friend-uuid"); assertTrue(work.isEmpty()); assertEquals("friend", name.getText().toString());
        RootPostMutes.credentialsChanged();
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        bind(name, "friend-uuid"); source.duringExecute = () -> RootPostMutes.credentialsChanging(); run();
        assertEquals("friend", name.getText().toString()); assertEquals(1, source.cancelled);
        RootPostMutes.credentialsChanged(); enabled(); source.pages.clear(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        bind(name, "friend-uuid"); source.duringExecute = () -> account("account-a", new Blog("primary", "primary-uuid", true)); run();
        assertEquals("friend", name.getText().toString());
    }
    @Test public void accountObserverRemovesExistingMarkersBeforePrivateMuteStorageIsReady() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        TextView name = view("friend"); bind(name, "friend-uuid"); run(); assertEquals("friend · Mutual", name.getText().toString());
        account("account-b", new Blog("otherprimary", "other-primary-uuid", true)); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("friend", name.getText().toString()); assertEquals("friend native description", name.getContentDescription().toString());
    }
    @Test public void preservesNativeSpansBadgesFollowActionsAndAccessibilityThenCleansRecycle() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        TextView name = view("friend ★"); StyleSpan badge = new StyleSpan(Typeface.BOLD);
        SpannableStringBuilder nativeText = new SpannableStringBuilder("friend ★"); nativeText.setSpan(badge, 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        name.setText(nativeText); final boolean[] clicked = {false}; name.setOnClickListener(view -> clicked[0] = true);
        bind(name, "friend-uuid"); run();
        assertEquals("friend ★ · Mutual", name.getText().toString()); assertEquals(7, ((Spanned) name.getText()).getSpanStart(badge));
        assertEquals("friend ★ native description, Mutual", name.getContentDescription().toString()); name.performClick(); assertTrue(clicked[0]);
        MutualMarkers.clear((HushMutualHeader) () -> name);
        assertEquals("friend ★", name.getText().toString()); assertEquals(7, ((Spanned) name.getText()).getSpanStart(badge));
        name.setText("recycled"); name.setContentDescription("new native description"); tick(); assertEquals("recycled", name.getText().toString());
        assertEquals("new native description", name.getContentDescription());
    }
    @Test public void pauseOptionOffMissingCapabilityAndExpiryRemoveTheMarkerAndRetainSavedChoice() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        TextView name = view("friend"); bind(name, "friend-uuid"); run(); clock += MutualMarkers.TTL; tick(); assertEquals("friend", name.getText().toString());
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) if (reason != HushTumblrPause.Reason.NONE) {
            PauseForTests.pause(reason); bind(name, "friend-uuid"); assertEquals("friend", name.getText().toString());
            assertTrue(Settings.SHOW_MUTUAL_MARKERS.savedValue()); PauseForTests.resume();
        }
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class)); bind(name, "friend-uuid"); assertTrue(work.isEmpty());
        Settings.SHOW_MUTUAL_MARKERS.save(false); tick(); assertEquals("friend", name.getText().toString());
    }
    @Test public void timeoutCancelsAndWaitsBeforeRetryAndHttpFailureKeepsNativeHeader() {
        enabled(); TextView name = view("friend"); bind(name, "friend-uuid"); clock += MutualMarkers.TIMEOUT;
        bind(name, "friend-uuid"); assertEquals(1, work.size()); run(); assertEquals(0, source.executions);
        clock += MutualMarkers.RETRY; source.status = 403; source.pages.add(page(List.of(), null)); bind(name, "friend-uuid"); run(); assertEquals("friend", name.getText().toString());
        for (int i = 0; i < 50; i++) bind(name, "friend-uuid"); assertTrue(work.isEmpty());
    }
    @Test public void retainedMarkersCleanOnPauseAndOptionOffWithoutRebindAndPendingReadCancels() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        TextView name = view("friend"); bind(name, "friend-uuid"); run();
        assertEquals("friend · Mutual", name.getText().toString());
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH); tick();
        assertEquals("friend", name.getText().toString()); assertEquals("friend native description", name.getContentDescription());
        assertTrue(Settings.SHOW_MUTUAL_MARKERS.savedValue()); PauseForTests.resume();
        source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        bind(name, "friend-uuid"); run(); assertEquals("friend · Mutual", name.getText().toString());
        Settings.SHOW_MUTUAL_MARKERS.save(false); tick();
        assertEquals("friend", name.getText().toString()); assertEquals("friend native description", name.getContentDescription());
        Settings.SHOW_MUTUAL_MARKERS.save(true);
        source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        bind(name, "friend-uuid");
        source.duringExecute = () -> { Settings.SHOW_MUTUAL_MARKERS.save(false); tick(); };
        run(); assertEquals(1, source.cancelled); assertEquals("friend", name.getText().toString());
        assertEquals("friend native description", name.getContentDescription()); assertTrue(work.isEmpty());
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeSpansRemainReadableInDarkLightRtlAndEnlargedText() {
        enabled(); source.pages.add(page(List.of(new Follower(true, new Blog("friend", "friend-uuid", false))), null));
        for (boolean dark : new boolean[] {true, false}) for (boolean rtl : new boolean[] {true, false}) {
            LinearLayout header = new LinearLayout(Utils.getContext()); header.setOrientation(LinearLayout.VERTICAL);
            header.setBackgroundColor(dark ? Color.BLACK : Color.WHITE); header.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            TextView name = view("friend ★"); name.setTextColor(dark ? Color.WHITE : Color.BLACK); name.setTextSize(32); name.setMaxLines(3);
            header.addView(name, new LinearLayout.LayoutParams(360, LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView attribution = view("Native source"); header.addView(attribution);
            bind(name, "friend-uuid"); if (!work.isEmpty()) run();
            header.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            header.layout(0, 0, header.getMeasuredWidth(), header.getMeasuredHeight());
            assertTrue(name.getText().toString().contains("Mutual")); assertTrue(name.getHeight() >= name.getLayout().getHeight() + name.getCompoundPaddingTop() + name.getCompoundPaddingBottom());
            for (int line = 0; line < name.getLineCount(); line++) assertEquals(0, name.getLayout().getEllipsisCount(line));
            assertTrue(attribution.getTop() >= name.getBottom()); assertEquals("Native source", attribution.getText().toString());
            MutualMarkers.clear(name);
        }
    }

    private static final class Account implements HushAccountIdentity, HushMutualAccount {
        final String id; final List<?> blogs; Account(String id, List<?> blogs) { this.id = id; this.blogs = blogs; }
        @Override public String hushAccountId() { return id; }
        @Override public List<?> hushMutualBlogs() { return blogs; }
    }
    private static final class Blog implements HushPrimaryBlog {
        final String name, uuid; final boolean primary;
        Blog(String name, String uuid, boolean primary) { this.name = name; this.uuid = uuid; this.primary = primary; }
        @Override public String hushMutualName() { return name; }
        @Override public String hushMutualUuid() { return uuid; }
        @Override public boolean hushMutualPrimary() { return primary; }
    }
    private static final class Follower implements HushMutualFollower {
        final boolean following; final Object blog; Follower(boolean following, Object blog) { this.following = following; this.blog = blog; }
        @Override public boolean hushMutualFollowing() { return following; }
        @Override public Object hushMutualFollowerBlog() { return blog; }
    }
    private static HushMutualPage page(List<?> users, String next) {
        return new HushMutualPage() {
            @Override public List<?> hushMutualUsers() { return users; }
            @Override public Object hushMutualPagination() { return (HushMutualPagination) () -> next == null ? null : (HushMutualLink) () -> next; }
            @Override public Object hushMutualLinks() { return null; }
        };
    }
    private final class Source implements HushMutualSource {
        final List<String> first = new ArrayList<>(), next = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>(); final List<HushMutualPage> pages = new ArrayList<>();
        int executions, cancelled, status = 200; Runnable duringExecute;
        @Override public Object first(String host) { first.add(host); return new Object(); }
        @Override public Object next(String link) { next.add(link); return new Object(); }
        @Override public Object execute(Object call, long timeout) {
            executions++; timeouts.add(timeout); Object page = pages.remove(0);
            if (duringExecute != null) { Runnable task = duringExecute; duringExecute = null; task.run(); }
            return new HushMutualResponse() {
                @Override public Object hushMutualResponse() { return page; }
                @Override public int hushMutualStatus() { return status; }
            };
        }
        @Override public void cancel(Object call) { cancelled++; }
    }
}
