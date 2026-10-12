/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.settings.Setting;
import app.hushtumblr.extension.shared.settings.FailingStore;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The settings dialog as Tumblr hosts it: a dialog fragment over an activity that isn't ours,
 * with the preference page in the dialog's child manager.
 *
 * <p>The recovery page's Retry used to commit through the activity's manager, which can't see the
 * dialog's container; its Back finished the activity when the activity's back stack was empty,
 * which inside Tumblr is Tumblr itself; and a new container id on every view creation left a
 * page restored after rotation with no container to go back into.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsDialogTest {
    private static final String TAG = "hushtumblr_settings";
    private static final String ERROR = "morphe_settings_error_message";
    private static final String RETRY = "morphe_settings_error_retry";
    private static final String BACK = "morphe_settings_error_back";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();

    @After
    public void tearDown() {
        HushTumblrPreferenceFragment.failNextInitialization = null;
        SettingsDialog.failNextMount = null;
        Settings.CHECK_FOR_RELEASES.resetToDefault();
        controller.close();
        PatchFamily.inBuildForTests = null;
    }

    @Test
    public void aFailedInitializationShowsATitledReadableRecoveryPage() {
        HushTumblrPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));

        Preference message = page.findPreference(ERROR);
        assertNotNull("no recovery page", message);
        assertEquals(String.valueOf(page.initializationErrorTitle(controller.get())), String.valueOf(message.getTitle()));
        assertTrue(String.valueOf(message.getTitle()), String.valueOf(message.getTitle()).contains("couldn't open"));
        for (String key : new String[]{ERROR, RETRY, BACK}) {
            Preference row = page.findPreference(key);
            assertNotNull(key, row);
            assertTrue(key + " has no title", row.getTitle() != null && row.getTitle().length() > 0);
            assertLightText(row);
        }
    }

    @Test
    public void failedWordsSavesKeepTheDraftAndEarlierSummary() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        TextSettings.HIDDEN_WORDS.save("earlier words");
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        Preference row = page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW);
        String earlier = row.getSummary().toString();
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.COMMIT_FALSE,
                FailingStore.Fault.COMMIT_THROWS}) {
            click(row);
            AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
            EditText field = editor.findViewById(android.R.id.edit);
            field.setText("attempted private words");
            try (FailingStore ignored = FailingStore.install(fault)) {
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            }
            ShadowLooper.idleMainLooper();
            assertTrue("failed Save closed the editor", editor.isShowing());
            assertEquals("attempted private words", field.getText().toString());
            assertEquals("earlier words", TextSettings.HIDDEN_WORDS.savedValue());
            assertEquals(earlier, row.getSummary());
            assertNotNull(ShadowToast.getTextOfLatestToast());
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        }
    }

    @Test
    public void longWordListsScrollWithinTheEditorInsteadOfHidingItsButtons() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        click(page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW));
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        EditText field = editor.findViewById(android.R.id.edit);
        String draft = "x".repeat(SettingsBackup.MAX_BYTES);
        field.setText(draft);
        field.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertTrue("word list height " + field.getMeasuredHeight() + " grew beyond viewport "
                + field.getResources().getDisplayMetrics().heightPixels + ", max " + field.getMaxHeight(), field.getMeasuredHeight()
                <= field.getResources().getDisplayMetrics().heightPixels / 4);
        assertEquals(draft, field.getText().toString());
        assertTrue(field.isVerticalScrollBarEnabled());
    }

    @Test
    public void aSuccessfulWordsSaveClearsTheRecreationDraft() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        click(page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW));
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        ((EditText) editor.findViewById(android.R.id.edit)).setText("saved words");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        controller.recreate();
        ShadowLooper.idleMainLooper();
        page = pageOf((SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG));
        assertFalse("a saved draft reopened", editor.isShowing());
        assertEquals("saved words", TextSettings.HIDDEN_WORDS.savedValue());
        click(page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW));
        assertEquals("saved words", ((EditText) ShadowAlertDialog.getLatestAlertDialog()
                .findViewById(android.R.id.edit)).getText().toString());
    }

    @Test
    public void theAllowedWordsEditorSharesTheTotalBudgetAndKeepsRefusedText() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        TextSettings.HIDDEN_WORDS.save("猫".repeat(18000));
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        Preference row = page.findPreference(HushTumblrPreferenceFragment.ALLOWED_WORDS_ROW);
        click(row);
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        EditText field = editor.findViewById(android.R.id.edit);
        String draft = "猫".repeat(5000);
        field.setText(draft);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.isShowing());
        assertEquals(draft, field.getText().toString());
        assertEquals("", TextSettings.ALLOWED_WORDS.savedValue());
        field.setText("allowed");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.isShowing());
        assertEquals("allowed", TextSettings.ALLOWED_WORDS.savedValue());
    }

    @Test
    public void recreationKeepsTheUnsavedWordsAndSelectionAndCancelDiscardsThem() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        TextSettings.HIDDEN_WORDS.save("saved");
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        click(page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW));
        AlertDialog old = ShadowAlertDialog.getLatestAlertDialog();
        EditText field = old.findViewById(android.R.id.edit);
        String draft = "caf\u00e9 " + "\u732b".repeat(12000);
        field.setText(draft);
        field.setSelection(2, 8);
        controller.recreate();
        ShadowLooper.idleMainLooper();
        AlertDialog restored = ShadowAlertDialog.getLatestAlertDialog();
        assertNotSame(old, restored);
        assertFalse(old.isShowing());
        EditText restoredField = restored.findViewById(android.R.id.edit);
        assertEquals(draft, restoredField.getText().toString());
        assertEquals(2, restoredField.getSelectionStart());
        assertEquals(8, restoredField.getSelectionEnd());
        assertEquals("saved", TextSettings.HIDDEN_WORDS.savedValue());
        restored.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        page = pageOf((SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG));
        click(page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW));
        AlertDialog fresh = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("saved", ((EditText) fresh.findViewById(android.R.id.edit)).getText().toString());
        fresh.dismiss();
    }

    @Test
    public void failedTabMovesAndResetKeepTheSavedOrderAndSummary() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        String saved = app.hushtumblr.extension.tumblr.ui.BottomBarTabs.join(
                app.hushtumblr.extension.tumblr.ui.BottomBarTabs.movedUp(
                        app.hushtumblr.extension.tumblr.ui.BottomBarTabs.choices(""), 2));
        TextSettings.TAB_ORDER.save(saved);
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        Preference row = page.findPreference(HushTumblrPreferenceFragment.TAB_ORDER_ROW);
        String summary = row.getSummary().toString();
        click(row);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        android.widget.ListAdapter labels = dialog.getListView().getAdapter();
        List<Object> earlier = new ArrayList<>();
        for (int i = 0; i < labels.getCount(); i++) earlier.add(labels.getItem(i));
        for (FailingStore.Fault fault : new FailingStore.Fault[]{FailingStore.Fault.COMMIT_FALSE,
                FailingStore.Fault.COMMIT_THROWS}) {
            try (FailingStore ignored = FailingStore.install(fault)) {
                dialog.getListView().performItemClick(null, 2, 2);
            }
            ShadowLooper.idleMainLooper();
            assertEquals(saved, TextSettings.TAB_ORDER.savedValue());
            assertEquals(summary, row.getSummary());
            for (int i = 0; i < labels.getCount(); i++) assertEquals(earlier.get(i), labels.getItem(i));
            try (FailingStore ignored = FailingStore.install(fault)) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            }
            ShadowLooper.idleMainLooper();
            assertTrue(dialog.isShowing());
            assertEquals(saved, TextSettings.TAB_ORDER.savedValue());
            assertEquals(summary, row.getSummary());
            for (int i = 0; i < labels.getCount(); i++) assertEquals(earlier.get(i), labels.getItem(i));
        }
        dialog.dismiss();
    }

    @Test
    public void overBudgetWordsStayEditableWithoutReplacingSavedWords() {
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        TextSettings.HIDDEN_WORDS.save("saved words");
        HushTumblrPreferenceFragment page = pageOf(show(controller.get()));
        Preference row = page.findPreference(HushTumblrPreferenceFragment.HIDDEN_WORDS_ROW);
        String summary = row.getSummary().toString();
        click(row);
        AlertDialog editor = ShadowAlertDialog.getLatestAlertDialog();
        EditText field = editor.findViewById(android.R.id.edit);
        String draft = "\u732b".repeat(SettingsBackup.MAX_BYTES / 2);
        field.setText(draft);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(editor.isShowing());
        assertEquals(draft, field.getText().toString());
        assertEquals("saved words", TextSettings.HIDDEN_WORDS.savedValue());
        assertEquals(summary, row.getSummary());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("too large"));
        field.setText("retry");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertEquals("retry", TextSettings.HIDDEN_WORDS.savedValue());
    }

    @Test
    public void retryRebuildsThePageInsideTheDialog() {
        HushTumblrPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        SettingsDialog dialog = show(controller.get());
        HushTumblrPreferenceFragment failed = pageOf(dialog);

        click(failed.findPreference(RETRY));
        dialog.getChildFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        HushTumblrPreferenceFragment rebuilt = pageOf(dialog);
        assertNotSame("Retry left the failed page in place", failed, rebuilt);
        assertNull("the rebuilt page is still the recovery page", rebuilt.findPreference(ERROR));
        assertTrue("the rebuilt page is empty", rebuilt.getPreferenceScreen().getPreferenceCount() > 0);
        assertEquals(1, pages(dialog).size());
        assertFalse(controller.get().isFinishing());
    }

    @Test
    public void theRecoveryPagesBackClosesOnlyTheSettings() {
        HushTumblrPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        Activity activity = controller.get();
        click(pageOf(show(activity)).findPreference(BACK));
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertNull("the settings stayed open", activity.getFragmentManager().findFragmentByTag(TAG));
        assertFalse("Back finished the host activity", activity.isFinishing());
    }

    @Test
    public void theBackKeyClosesOnlyTheSettings() {
        Activity activity = controller.get();
        show(activity).getDialog().onBackPressed();
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();

        assertNull("the settings stayed open", activity.getFragmentManager().findFragmentByTag(TAG));
        assertFalse("Back finished the host activity", activity.isFinishing());
    }

    /**
     * Recreation hands back the page the framework saved, with its state, rather than a fresh one
     * built over it. A marker in the saved page's arguments is how the test tells the two apart: a
     * dialog that built a new page on every view would pass the count and container checks alone,
     * and the list would jump back to the top on every rotation.
     */
    @Test
    public void recreationKeepsOnePageInItsContainer() {
        android.os.Bundle marker = new android.os.Bundle();
        marker.putString("marker", "the page the framework saved");
        pageOf(show(controller.get())).setArguments(marker);

        controller.recreate();
        ShadowLooper.idleMainLooper();

        SettingsDialog restored = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG);
        assertNotNull("the settings didn't come back after recreation", restored);
        assertEquals("pages after recreation", 1, pages(restored).size());
        HushTumblrPreferenceFragment page = pageOf(restored);
        assertNotNull("recreation replaced the saved page with a new one", page.getArguments());
        assertEquals("the page the framework saved", page.getArguments().getString("marker"));
        View view = page.getView();
        assertNotNull("the restored page has no view", view);
        assertEquals(SettingsDialog.CONTAINER_ID, ((View) view.getParent()).getId());
        assertNull(page.findPreference(ERROR));
        assertTrue(page.getPreferenceScreen().getPreferenceCount() > 0);
    }

    @Test
    @Config(sdk = {29, 30, 33, 36})
    public void aFailedMountOffersReadableFocusedActionsAndRetryCreatesOneChild() {
        SettingsDialog healthy = show(controller.get());
        int healthyListeners = preferenceListeners();
        healthy.dismiss();
        ShadowLooper.idleMainLooper();
        int listeners = preferenceListeners();
        SettingsDialog.failNextMount = new IllegalStateException("injected child mount failure");
        SettingsDialog dialog = show(controller.get());
        View recovery = recoveryOf(dialog);
        assertEquals(L10n.t(controller.get(), "HushTumblr settings couldn't open"),
                ((TextView) recovery.findViewById(android.R.id.title)).getText().toString());
        assertEquals(L10n.t(controller.get(), "Try again, or go back to Tumblr."),
                ((TextView) recovery.findViewById(android.R.id.summary)).getText().toString());
        assertTrue(pages(dialog).isEmpty());
        assertEquals(listeners, preferenceListeners());
        assertFalse("search took focus behind recovery", searchOf(dialog.getView()).hasFocus());
        assertEquals(View.GONE, ((View) searchOf(dialog.getView()).getParent()).getVisibility());
        Button retry = mountAction(dialog, SettingsDialog.MOUNT_RETRY);
        assertTrue("recovery left focus on the empty container", retry.hasFocus());
        for (String tag : new String[]{SettingsDialog.MOUNT_RETRY, SettingsDialog.MOUNT_BACK}) {
            Button action = mountAction(dialog, tag);
            AccessibilityNodeInfo node = action.createAccessibilityNodeInfo();
            assertEquals(Button.class.getName(), String.valueOf(node.getClassName()));
            assertTrue(node.isEnabled());
            assertTrue(node.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK));
            int surface = tag.equals(SettingsDialog.MOUNT_RETRY) ? ScreenColors.DEFAULT.accent : ScreenColors.DEFAULT.background;
            double text = Color.luminance(action.getCurrentTextColor());
            double background = Color.luminance(surface);
            assertTrue("recovery text lacks contrast", (Math.max(text, background) + 0.05) / (Math.min(text, background) + 0.05) >= 4.5);
        }

        retry.performClick();
        retry.performClick();
        ShadowLooper.idleMainLooper();
        HushTumblrPreferenceFragment page = pageOf(dialog);
        assertNull(dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));
        assertEquals(1, pages(dialog).size());
        assertEquals(healthyListeners, preferenceListeners());
        assertNotNull(page.navigation);
        assertTrue("successful Retry did not return focus to the settings list",
                page.getView().findViewById(android.R.id.list).hasFocus());
        assertFalse(controller.get().isFinishing());
        retry.performClick();
        ShadowLooper.idleMainLooper();
        assertSame("an obsolete Retry button remounted the healthy child", page, pageOf(dialog));
        assertEquals(healthyListeners, preferenceListeners());
    }

    @Test
    public void aFailedRetryKeepsItsActionsUsableUntilASuccessfulRetry() {
        SettingsDialog.failNextMount = new IllegalStateException("first failure");
        SettingsDialog dialog = show(controller.get());
        SettingsDialog.failNextMount = new IllegalStateException("retry failure");
        mountAction(dialog, SettingsDialog.MOUNT_RETRY).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull(recoveryOf(dialog));
        assertTrue(mountAction(dialog, SettingsDialog.MOUNT_RETRY).isEnabled());
        assertTrue(mountAction(dialog, SettingsDialog.MOUNT_BACK).isEnabled());
        assertTrue(pages(dialog).isEmpty());
        mountAction(dialog, SettingsDialog.MOUNT_RETRY).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, pages(dialog).size());
        assertNull(dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));
    }

    @Test
    public void theMountRecoverySurvivesRotationUntilThePersonRetries() {
        SettingsDialog.failNextMount = new IllegalStateException("initial failure");
        show(controller.get());
        controller.recreate();
        ShadowLooper.idleMainLooper();
        SettingsDialog dialog = restored();
        assertNotNull(recoveryOf(dialog));
        assertTrue("rotation silently retried the failed operation", pages(dialog).isEmpty());
        assertTrue(mountAction(dialog, SettingsDialog.MOUNT_RETRY).hasFocus());
        controller.recreate();
        ShadowLooper.idleMainLooper();
        dialog = restored();
        mountAction(dialog, SettingsDialog.MOUNT_RETRY).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(1, pages(dialog).size());
        assertNull(dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));
        controller.recreate();
        ShadowLooper.idleMainLooper();
        assertEquals(1, pages(restored()).size());
        assertNull(restored().getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));
    }

    @Test
    public void everyMountRecoveryBackPathClosesSettingsAndKeepsTheHostUsable() {
        for (int path = 0; path < 3; path++) {
            SettingsDialog.failNextMount = new IllegalStateException("mount failure");
            SettingsDialog dialog = show(controller.get());
            if (path == 0) mountAction(dialog, SettingsDialog.MOUNT_BACK).performClick();
            else if (path == 1) SettingsL10nTest.backOf(dialog).performClick();
            else dialog.getDialog().onBackPressed();
            controller.get().getFragmentManager().executePendingTransactions();
            ShadowLooper.idleMainLooper();
            assertNull("Back path " + path + " left settings open", controller.get().getFragmentManager().findFragmentByTag(TAG));
            assertFalse("Back path " + path + " finished Tumblr", controller.get().isFinishing());
        }
    }

    @Test
    public void restoredCategoryAndSearchRecoveryKeepOneChildAndTheirExistingListeners() {
        SettingsDialog dialog = show(controller.get());
        for (boolean search : new boolean[]{false, true}) {
            HushTumblrPreferenceFragment page = pageOf(dialog);
            page.navigation.navigate(search ? "" : "Updates");
            if (search) searchOf(dialog.getView()).setText("release");
            Bundle before = navigationState(page);
            int listeners = preferenceListeners();
            int observers = navigationObservers(page);
            SettingsDialog.failNextMount = new IllegalStateException("restored mount failure");
            controller.recreate();
            ShadowLooper.idleMainLooper();
            dialog = restored();
            HushTumblrPreferenceFragment restoredPage = pageOf(dialog);
            assertNotNull(recoveryOf(dialog));
            assertEquals(View.INVISIBLE, restoredPage.getView().getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, restoredPage.getView().getImportantForAccessibility());
            mountAction(dialog, SettingsDialog.MOUNT_RETRY).performClick();
            ShadowLooper.idleMainLooper();
            assertSame("Retry discarded a healthy restored page", restoredPage, pageOf(dialog));
            assertEquals(before.getString("route"), navigationState(restoredPage).getString("route"));
            assertEquals(before.getString("query"), navigationState(restoredPage).getString("query"));
            assertEquals(before.getString("query"), searchOf(dialog.getView()).getText().toString());
            assertEquals(1, pages(dialog).size());
            assertEquals(listeners, preferenceListeners());
            assertEquals(observers, navigationObservers(restoredPage));
            assertEquals(View.VISIBLE, restoredPage.getView().getVisibility());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO, restoredPage.getView().getImportantForAccessibility());
            assertFalse(searchOf(dialog.getView()).hasFocus());
            Settings.CHECK_FOR_RELEASES.save(true);
            ShadowLooper.idleMainLooper();
            assertTrue("the preserved preference listener stopped updating rows",
                    ((SwitchPreference) restoredPage.findPreference(Settings.CHECK_FOR_RELEASES.key)).isChecked());
            Settings.CHECK_FOR_RELEASES.save(false);
            ShadowLooper.idleMainLooper();
        }
    }

    @Test
    public void retryReplacesAChildWithoutItsContainerAndPreservesItsNavigationState() {
        SettingsDialog dialog = show(controller.get());
        HushTumblrPreferenceFragment original = pageOf(dialog);
        original.navigation.navigate("Updates");
        Bundle marker = new Bundle();
        marker.putString("marker", "saved child arguments");
        original.setArguments(marker);
        int listeners = preferenceListeners();
        int observers = navigationObservers(original);
        ((FrameLayout) dialog.getView().findViewById(SettingsDialog.CONTAINER_ID)).removeView(original.getView());
        SettingsDialog.failNextMount = new IllegalStateException("view mount failure");
        dialog.onViewCreated(dialog.getView(), null);
        mountAction(dialog, SettingsDialog.MOUNT_RETRY).performClick();
        ShadowLooper.idleMainLooper();
        HushTumblrPreferenceFragment replacement = pageOf(dialog);
        assertNotSame(original, replacement);
        assertEquals("saved child arguments", replacement.getArguments().getString("marker"));
        assertEquals("Updates", navigationState(replacement).getString("route"));
        assertEquals(1, pages(dialog).size());
        assertEquals(listeners, preferenceListeners());
        assertEquals(observers, navigationObservers(replacement));
        assertFalse(controller.get().isFinishing());
    }

    private SettingsDialog restored() {
        SettingsDialog dialog = (SettingsDialog) controller.get().getFragmentManager().findFragmentByTag(TAG);
        assertNotNull("settings did not survive rotation", dialog);
        return dialog;
    }

    private static View recoveryOf(SettingsDialog dialog) {
        View recovery = dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR);
        assertNotNull("no child-mount recovery view", recovery);
        return recovery;
    }

    private static Button mountAction(SettingsDialog dialog, String tag) {
        Button action = dialog.getView().findViewWithTag(tag);
        assertNotNull(tag, action);
        return action;
    }

    private static Bundle navigationState(HushTumblrPreferenceFragment page) {
        Bundle saved = new Bundle();
        page.navigation.save(saved);
        return saved.getBundle("hushtumblr_navigation");
    }

    private static int preferenceListeners() {
        Map<?, ?> listeners = ReflectionHelpers.getField(Setting.preferences.preferences, "mListeners");
        return listeners.size();
    }

    private static int navigationObservers(HushTumblrPreferenceFragment page) {
        Object observable = ReflectionHelpers.getField(page.getPreferenceScreen().getRootAdapter(), "mDataSetObservable");
        Collection<?> observers = ReflectionHelpers.getField(observable, "mObservers");
        return observers.size();
    }

    private static EditText searchOf(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = searchOf(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static SettingsDialog show(Activity activity) {
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(activity.getFragmentManager(), TAG);
        activity.getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    private static HushTumblrPreferenceFragment pageOf(SettingsDialog dialog) {
        Fragment page = dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertTrue("no preference page in the dialog: " + page, page instanceof HushTumblrPreferenceFragment);
        return (HushTumblrPreferenceFragment) page;
    }

    private static List<Fragment> pages(SettingsDialog dialog) {
        List<Fragment> pages = new ArrayList<>();
        for (Fragment fragment : dialog.getChildFragmentManager().getFragments()) {
            if (fragment instanceof HushTumblrPreferenceFragment) pages.add(fragment);
        }
        return pages;
    }

    private static void click(Preference row) {
        assertNotNull("no such row", row);
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    private static void assertLightText(Preference row) {
        TypedArray styled = row.getContext().obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList primary = styled.getColorStateList(0);
            assertNotNull(primary);
            assertTrue("\"" + row.getTitle() + "\" is drawn dark on the black page",
                    Color.luminance(primary.getDefaultColor()) > 0.5f);
        } finally {
            styled.recycle();
        }
    }
}
