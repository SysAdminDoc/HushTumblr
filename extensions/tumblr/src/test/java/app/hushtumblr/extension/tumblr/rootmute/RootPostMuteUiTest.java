/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RootPostMuteUiTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private ActivityController<Activity> controller;

    @Before public void prepare() {
        controller = Robolectric.buildActivity(Activity.class).setup();
        PatchFamilyForTests.inBuild(EnumSet.of(PatchFamily.MUTE_ROOT_POSTS));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.ROOT_POST_MUTES));
        Settings.MUTE_ROOT_POSTS.save(true); PauseForTests.resume();
        RootPostMutes.fileForTests = new File(temporary.getRoot(), "private.json");
        RootPostMutes.workerForTests = Runnable::run; RootPostMutes.resetForTests();
        account("account-a");
    }

    @After public void restore() {
        controller.close(); RootPostMutes.resetForTests();
        RootPostMutes.fileForTests = null; RootPostMutes.workerForTests = null;
        Settings.MUTE_ROOT_POSTS.resetToDefault(); PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null); PauseForTests.resume();
    }

    private void account(String id) {
        RootPostMutes.credentialsChanging(); RootPostMutes.credentialsChanged();
        Object request = new Object(); RootPostMutes.requestCreated(request); RootPostMutes.requestEntered(request);
        RootPostMutes.beginAccountChange((HushAccountIdentity) () -> id);
        RootPostMutes.commitAccount(); RootPostMutes.requestExited();
    }

    private RootPostMutes.Undo mute(String root) {
        AtomicReference<RootPostMutes.Undo> undo = new AtomicReference<>();
        RootPostMutes.mute(RootPostMutes.capture(), root, undo::set); assertNotNull(undo.get()); return undo.get();
    }

    @Test public void fragmentDismissalMakesRetainedManagerListAndClearActionsInert() {
        mute("11");
        RootPostMutePreference row = new RootPostMutePreference(controller.get()); row.onClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing());
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        row.closeDialogs(); assertFalse(dialog.isShowing());
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        list.performItemClick(null, 0, 0); clear.performClick();
        assertTrue(queued.isEmpty()); assertEquals(1, RootPostMutes.capture().roots.size());
    }

    @Test public void anAccountChangeMakesAnOpenManagerInert() {
        mute("11"); AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        account("account-b"); mute("22");
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        list.performItemClick(null, 0, 0); dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertTrue(queued.isEmpty()); assertEquals(java.util.Set.of("22"), RootPostMutes.capture().roots);
        dialog.dismiss();
    }

    @Test public void undoRestoresImmediatelyAndAnExpiredRetainedButtonDoesNothing() {
        RootPostMuteUi.changed(controller.get(), "Post muted locally", mute("11"));
        Button undo = find(controller.get().findViewById(android.R.id.content), Button.class);
        assertNotNull(undo); undo.performClick(); assertTrue(RootPostMutes.capture().roots.isEmpty());
        mute("22"); undo.performClick();
        assertEquals(java.util.Set.of("22"), RootPostMutes.capture().roots);
    }

    @Test public void aDismissedManagerDoesNotPublishItsQueuedCompletionIntoANewView() {
        mute("11"); RootPostMutePreference row = new RootPostMutePreference(controller.get()); row.onClick();
        AlertDialog old = ShadowAlertDialog.getLatestAlertDialog();
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        find(old.getWindow().getDecorView(), ListView.class).performItemClick(null, 0, 0);
        assertEquals(1, queued.size()); row.closeDialogs(); row.onClick();
        AlertDialog replacement = ShadowAlertDialog.getLatestAlertDialog(); assertNotSame(old, replacement);
        queued.get(0).run(); assertTrue(replacement.isShowing());
        assertEquals(0, ((ViewGroup) controller.get().findViewById(android.R.id.content)).getChildCount());
        row.closeDialogs();
    }

    @Test public void theMenuActionRemovesOriginalAndReblogNowAndUndoPreservesANewPage() {
        Object heading = new Object();
        app.hushtumblr.extension.tumblr.timeline.HushRootPost original = () -> "11";
        app.hushtumblr.extension.tumblr.timeline.HushRootPost reblog = () -> "11";
        app.hushtumblr.extension.tumblr.timeline.HushRootPost other = () -> "22";
        FakeTimeline timeline = new FakeTimeline(java.util.Arrays.asList(heading, original, reblog, other));
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        new RootPostMuteAction("11", controller.get(), timeline).run();
        assertEquals(1, queued.size()); assertEquals(4, timeline.items.size());
        app.hushtumblr.extension.tumblr.timeline.HushRootPost nextPage = () -> "33";
        timeline.items.add(nextPage);
        RootPostMutes.workerForTests = Runnable::run; queued.get(0).run();
        assertEquals(java.util.Arrays.asList(heading, other, nextPage), timeline.items);
        Button undo = find(controller.get().findViewById(android.R.id.content), Button.class);
        assertNotNull(undo);
        app.hushtumblr.extension.tumblr.timeline.HushRootPost laterPage = () -> "44";
        timeline.items.add(laterPage); undo.performClick();
        assertEquals(java.util.Arrays.asList(heading, original, reblog, other, nextPage, laterPage), timeline.items);
        assertTrue(RootPostMutes.capture().roots.isEmpty());
    }

    @Test public void aFilteredPageAlreadyMissingTheRootKeepsTheSavedMuteAndUndoDoesNotReplaceIt() {
        Object heading = new Object();
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        app.hushtumblr.extension.tumblr.timeline.HushRootPost other = () -> "22";
        FakeTimeline timeline = new FakeTimeline(java.util.Arrays.asList(heading, selected, other));
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        new RootPostMuteAction("11", controller.get(), timeline).run();
        assertEquals(1, queued.size());
        app.hushtumblr.extension.tumblr.timeline.HushRootPost newPage = () -> "33";
        timeline.items = new ArrayList<>(java.util.Arrays.asList(heading, other, newPage));
        RootPostMutes.workerForTests = Runnable::run; queued.get(0).run();
        assertEquals(java.util.Set.of("11"), RootPostMutes.capture().roots);
        assertEquals(0, timeline.applies);
        Button undo = find(controller.get().findViewById(android.R.id.content), Button.class);
        assertNotNull(undo); undo.performClick();
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertEquals(java.util.Arrays.asList(heading, other, newPage), timeline.items);
        assertEquals(0, timeline.applies);
    }

    @Test public void aRejectedLocalRebindRollsBackTheSavedMute() {
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        HushLocalTimeline refuses = new HushLocalTimeline() {
            @Override public List<?> hushTimelineItems() { return java.util.List.of(selected); }
            @Override public void hushApplyTimelineItems(List<?> items) { }
        };
        new RootPostMuteAction("11", controller.get(), refuses).run();
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertNull(find(controller.get().findViewById(android.R.id.content), Button.class));
    }

    @Test public void aNativeMenuCallbackUsesItsOwnTimelineAndKeepsDirectHostsWorking() {
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        FakeTimeline owner = new FakeTimeline(List.of(selected));
        HushRootMenuHost callback = () -> owner;
        RootPostMuteAction action = RootPostMenu.action(selected, controller.get(), callback);
        assertNotNull(action);
        assertNotNull(RootPostMenu.action(selected, controller.get(), owner));
        action.run();
        assertTrue(owner.items.isEmpty());
        assertEquals(java.util.Set.of("11"), RootPostMutes.capture().roots);
    }

    @Test public void aCallbackCannotSupplyAnAbsentForeignOrUnreadableTimeline() {
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        assertNull(RootPostMenu.action(selected, controller.get(), new Object()));
        assertNull(RootPostMenu.action(selected, controller.get(), (HushRootMenuHost) () -> null));
        assertNull(RootPostMenu.action(selected, controller.get(), (HushRootMenuHost) () -> new FakeTimeline(List.of())));
        assertNull(RootPostMenu.action(selected, controller.get(), (HushRootMenuHost) () -> { throw new IllegalStateException(); }));
        assertTrue(RootPostMutes.capture().roots.isEmpty());
    }

    @Test public void theCallbackMenuKeepsAccountPauseFamilyAndCanonicalRootGates() {
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        HushRootMenuHost callback = () -> new FakeTimeline(List.of(selected));
        assertNull(RootPostMenu.action(selected, null, callback));
        assertNull(RootPostMenu.action((app.hushtumblr.extension.tumblr.timeline.HushRootPost) () -> null, controller.get(), callback));
        Settings.MUTE_ROOT_POSTS.save(false);
        assertNull(RootPostMenu.action(selected, controller.get(), callback));
        Settings.MUTE_ROOT_POSTS.save(true);
        PauseForTests.pause(app.hushtumblr.extension.shared.settings.HushTumblrPause.Reason.SWITCH);
        assertNull(RootPostMenu.action(selected, controller.get(), callback));
        PauseForTests.resume();
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertNull(RootPostMenu.action(selected, controller.get(), callback));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.ROOT_POST_MUTES));
        RootPostMutes.logout();
        assertNull(RootPostMenu.action(selected, controller.get(), callback));
    }

    @Test public void anAccountChangeAfterCallbackMenuCreationMakesItsActionInert() {
        app.hushtumblr.extension.tumblr.timeline.HushRootPost selected = () -> "11";
        FakeTimeline owner = new FakeTimeline(List.of(selected));
        RootPostMuteAction action = RootPostMenu.action(selected, controller.get(), (HushRootMenuHost) () -> owner);
        assertNotNull(action);
        account("account-b");
        action.run();
        assertEquals(List.of(selected), owner.items);
        assertTrue(RootPostMutes.capture().roots.isEmpty());
    }

    private static final class FakeTimeline implements HushLocalTimeline {
        List<Object> items;
        int applies;
        FakeTimeline(List<?> items) { this.items = new ArrayList<>(items); }
        @Override public List<?> hushTimelineItems() { return new ArrayList<>(items); }
        @Override public void hushApplyTimelineItems(List<?> items) { this.items = new ArrayList<>(items); applies++; }
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            T found = find(((ViewGroup) view).getChildAt(i), type); if (found != null) return found;
        }
        return null;
    }
}
