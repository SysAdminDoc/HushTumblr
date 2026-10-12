/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

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
        controller = Robolectric.buildActivity(Activity.class).setup().visible();
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
        clickFirstPost(old);
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

    @Test public void failedUnmuteKeepsThePrivateRowsAndOffersRetry() throws Exception {
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        File saved = RootPostMutes.fileForTests;
        RootPostMutes.fileForTests = temporary.newFolder("unmute-failure");
        clickFirstPost(dialog);
        assertTrue(dialog.isShowing());
        assertEquals(java.util.Set.of("11"), RootPostMutes.capture().roots);
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        assertTrue(list.isEnabled());
        assertTrue(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        assertTrue(notice(dialog).getText().toString().contains("Try again"));
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, notice(dialog).getAccessibilityLiveRegion());
        RootPostMutes.fileForTests = saved;
        clickFirstPost(dialog);
        assertTrue(dialog.isShowing());
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertEquals(0, list.getCount() - list.getHeaderViewsCount());
        assertTrue(inlineUndo(dialog).isShown());
        dialog.dismiss();
    }

    @Test public void failedClearKeepsTheListAndCanBeRetried() throws Exception {
        mute("11"); mute("22");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        File saved = RootPostMutes.fileForTests;
        RootPostMutes.fileForTests = temporary.newFolder("clear-failure");
        clear.performClick();
        assertTrue(dialog.isShowing());
        assertEquals(java.util.Set.of("11", "22"), RootPostMutes.capture().roots);
        assertTrue(clear.isEnabled());
        assertTrue(notice(dialog).getText().toString().contains("Try again"));
        RootPostMutes.fileForTests = saved;
        clear.performClick();
        assertTrue(dialog.isShowing());
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertFalse(clear.isEnabled());
        inlineUndo(dialog).performClick();
        assertEquals(java.util.Set.of("11", "22"), RootPostMutes.capture().roots);
        assertTrue(clear.isEnabled());
        dialog.dismiss();
    }

    @Test public void managerUndoStaysInItsVisibleWindowAndRetriesFailedPersistence() throws Exception {
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        clickFirstPost(dialog);
        assertTrue(dialog.isShowing());
        assertNull(find(controller.get().findViewById(android.R.id.content), Button.class));
        Button undo = inlineUndo(dialog);
        assertTrue(undo.isShown());
        File saved = RootPostMutes.fileForTests;
        RootPostMutes.fileForTests = temporary.newFolder("undo-failure");
        undo.performClick();
        assertTrue(dialog.isShowing());
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertTrue(undo.isEnabled());
        assertTrue(undo.isShown());
        assertTrue(notice(dialog).getText().toString().contains("Try again"));
        RootPostMutes.fileForTests = saved;
        undo.performClick();
        assertEquals(java.util.Set.of("11"), RootPostMutes.capture().roots);
        assertFalse(undo.isShown());
        dialog.dismiss();
    }

    @Test public void anAccountChangeClosesPrivateRowsAndPendingCallbacksStayInert() {
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        clickFirstPost(dialog);
        assertEquals(1, queued.size());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        account("account-b");
        ShadowLooper.idleMainLooper();
        assertFalse("Old account rows remain visible", dialog.isShowing());
        queued.get(0).run();
        assertFalse(dialog.isShowing());
        assertNull(find(controller.get().findViewById(android.R.id.content), Button.class));
    }

    @Test public void aManagerRowIsAnUnmuteButtonAndDuplicateActionsWaitForTheWorker() {
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        layoutManager(dialog);
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        int position = list.getHeaderViewsCount();
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        assertNotNull(row);
        AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
        assertEquals(Button.class.getName(), info.getClassName().toString());
        assertTrue(info.isClickable());
        assertTrue(info.getContentDescription().toString().contains("Unmute"));
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        assertTrue(row.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        assertFalse(list.isEnabled());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled());
        list.performItemClick(row, position, 0);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertTrue(dialog.isShowing());
        assertTrue(list.isEnabled());
        dialog.dismiss();
    }

    @Test public void menuUndoUsesTheAccessibleTimeoutAndRetainsFailedRestores() throws Exception {
        AccessibilityManager manager = controller.get().getSystemService(AccessibilityManager.class);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(60000);
        RootPostMuteUi.changed(controller.get(), "Post muted locally", mute("11"));
        Button undo = find(controller.get().findViewById(android.R.id.content), Button.class);
        assertNotNull(undo);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertNotNull(undo.getParent());
        File saved = RootPostMutes.fileForTests;
        RootPostMutes.fileForTests = temporary.newFolder("menu-undo-failure");
        undo.performClick();
        assertNotNull(undo.getParent());
        assertTrue(undo.isEnabled());
        RootPostMutes.fileForTests = saved;
        undo.performClick();
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        assertNull(find(controller.get().findViewById(android.R.id.content), Button.class));
        RootPostMuteUi.changed(controller.get(), "Post muted locally", mute("22"));
        Button expiring = find(controller.get().findViewById(android.R.id.content), Button.class);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61));
        assertNull(find(controller.get().findViewById(android.R.id.content), Button.class));
        expiring.performClick();
        assertEquals(java.util.Set.of("22"), RootPostMutes.capture().roots);
    }

    @Test public void inlineUndoHonorsTheAccessibleTimeoutThenMakesTheRetainedButtonInert() {
        AccessibilityManager manager = controller.get().getSystemService(AccessibilityManager.class);
        Shadows.shadowOf(manager).setInteractiveUiTimeout(60000);
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        clickFirstPost(dialog);
        Button undo = inlineUndo(dialog);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals(View.VISIBLE, undo.getVisibility());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(50));
        assertEquals(View.GONE, undo.getVisibility());
        undo.performClick();
        assertTrue(RootPostMutes.capture().roots.isEmpty());
        dialog.dismiss();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    public void aKeyboardCanReachTheManagersInlineUndo() {
        mute("11");
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        clickFirstPost(dialog);
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        Button undo = inlineUndo(dialog);
        Object root = org.robolectric.util.ReflectionHelpers.callInstanceMethod(
                dialog.getWindow().getDecorView(), "getViewRootImpl");
        org.robolectric.util.ReflectionHelpers.callInstanceMethod(root, "ensureTouchMode",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false));
        assertTrue(list.requestFocus());
        assertFalse(undo.hasFocus());
        list.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        list.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue("Keyboard focus skipped the visible Undo action", undo.hasFocus());
        undo.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
        undo.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
        ShadowLooper.idleMainLooper();
        assertEquals(java.util.Set.of("11"), RootPostMutes.capture().roots);
        dialog.dismiss();
    }

    private static void clickFirstPost(AlertDialog dialog) {
        layoutManager(dialog);
        ListView list = find(dialog.getWindow().getDecorView(), ListView.class);
        int position = list.getHeaderViewsCount();
        View row = list.getChildAt(position - list.getFirstVisiblePosition());
        assertNotNull(row);
        list.performItemClick(row, position, 0);
        layoutManager(dialog);
    }

    private static void layoutManager(AlertDialog dialog) {
        ShadowLooper.idleMainLooper();
        int width = dialog.getWindow().getAttributes().width;
        measure(dialog.getWindow().getDecorView(), width > 0 ? Math.min(780, width) : 716, 1688);
    }

    private static TextView notice(AlertDialog dialog) {
        TextView notice = dialog.getWindow().getDecorView().findViewWithTag("root_mute_notice");
        assertNotNull(notice);
        return notice;
    }

    private static Button inlineUndo(AlertDialog dialog) {
        Button undo = dialog.getWindow().getDecorView().findViewWithTag("root_mute_undo");
        assertNotNull(undo);
        return undo;
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-w390dp-h320dp-night-xhdpi")
    public void aShortScreenAtTwiceTheTextSizeKeepsActionsAndTheLastPrivateRowReachable() {
        Configuration config = new Configuration(controller.get().getResources().getConfiguration());
        config.fontScale = 2f;
        controller.get().getResources().updateConfiguration(config, null);
        for (int i = 1; i <= 20; i++) mute(String.valueOf(1000 + i));
        controller.visible();
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        ShadowLooper.idleMainLooper();
        View decor = dialog.getWindow().getDecorView();
        measure(decor, 716, 640);
        assertTrue(decor.getHeight() <= 640);
        for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button action = dialog.getButton(which);
            Rect visible = new Rect();
            assertTrue(action.getText().toString(), action.getGlobalVisibleRect(visible));
            assertEquals(action.getText().toString(), action.getHeight(), visible.height());
            android.text.Layout text = action.getLayout();
            assertTrue(action.getText().toString(), text.getHeight() <= action.getHeight()
                    - action.getCompoundPaddingTop() - action.getCompoundPaddingBottom());
        }
        ListView list = find(decor, ListView.class);
        assertTrue(list.getHeight() > 0 && list.getHeight() < 640);
        list.setSelection(list.getCount() - 1);
        measure(decor, 716, 640);
        assertEquals(list.getCount() - 1, list.getLastVisiblePosition());
        View last = list.getChildAt(list.getChildCount() - 1);
        assertTrue(last.getBottom() <= list.getHeight() - list.getPaddingBottom());
        dialog.dismiss();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    public void rendersTheEmptyPrivateManager() throws Exception {
        captureManager("root-mutes-empty");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w390dp-h844dp-night-xhdpi")
    public void rendersThePopulatedPrivateManager() throws Exception {
        mute("1001"); mute("1002"); mute("1003");
        captureManager("root-mutes-populated");
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "ar-rEG-w390dp-h844dp-night-xhdpi")
    public void rendersThePrivateManagerAtTwiceTheTextSizeInRtl() throws Exception {
        Configuration config = new Configuration(controller.get().getResources().getConfiguration());
        config.fontScale = 2f;
        controller.get().getResources().updateConfiguration(config, null);
        for (int i = 1; i <= 20; i++) mute(String.valueOf(1000 + i));
        captureManager("root-mutes-large-rtl");
    }

    private void captureManager(String name) throws Exception {
        controller.visible();
        AlertDialog dialog = RootPostMuteUi.manage(controller.get());
        assertNotNull(dialog);
        try {
            ShadowLooper.idleMainLooper();
            View decor = dialog.getWindow().getDecorView();
            int width = dialog.getWindow().getAttributes().width;
            if (width <= 0) width = 716;
            measure(decor, Math.min(780, width), 1688);
            assertTrue(decor.getWidth() > 0 && decor.getHeight() > 0);
            Bitmap image = Bitmap.createBitmap(780, 1688, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(image);
            canvas.drawColor(android.graphics.Color.BLACK);
            canvas.translate((780 - decor.getWidth()) / 2f, (1688 - decor.getHeight()) / 2f);
            decor.draw(canvas);
            File folder = new File("build/reports/settings-design");
            assertTrue(folder.isDirectory() || folder.mkdirs());
            try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { image.recycle(); }
        } finally { dialog.dismiss(); }
    }

    private static void measure(View decor, int width, int height) {
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
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
