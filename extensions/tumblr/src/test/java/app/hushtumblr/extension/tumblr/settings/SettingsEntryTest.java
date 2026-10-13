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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.settings.preference.ReopenSettingsAfterRestart;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SettingsEntryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final SettingsEntry.OpenWhenResumed watcher = new SettingsEntry.OpenWhenResumed();

    @Before public void watch() {
        RuntimeEnvironment.getApplication().registerActivityLifecycleCallbacks(watcher);
    }

    @After public void stopWatching() {
        RuntimeEnvironment.getApplication().unregisterActivityLifecycleCallbacks(watcher);
        app.hushtumblr.extension.shared.Utils.setActivity(null);
        HushTumblrPreferenceFragment.failNextInitialization = null;
    }

    @Test public void registeredLifecycleProvidesTheLiveHostAndKeepsItDuringTheSavePicker() {
        ActivityController<Activity> first = Robolectric.buildActivity(Activity.class).setup();
        ActivityController<Activity> second = null;
        try {
            assertSame(first.get(), app.hushtumblr.extension.shared.Utils.getActivity());
            first.pause().stop();
            assertSame("the external save picker must keep its Tumblr host", first.get(),
                    app.hushtumblr.extension.shared.Utils.getActivity());
            second = Robolectric.buildActivity(Activity.class).setup();
            assertSame(second.get(), app.hushtumblr.extension.shared.Utils.getActivity());
            first.destroy();
            assertSame("destroying an old screen must keep its replacement", second.get(),
                    app.hushtumblr.extension.shared.Utils.getActivity());
            second.pause().stop().destroy();
            assertNull(app.hushtumblr.extension.shared.Utils.getActivity());
        } finally {
            if (!first.get().isDestroyed()) first.close();
            if (second != null && !second.get().isDestroyed()) second.close();
        }
    }

    /**
     * The launcher shortcut is labelled in the phone's language when it's first published, and a
     * phone that changes language gets it relabelled: one found under the old label is pushed
     * again. Kept as it was, it stayed in its first language for good.
     */
    @Test public void theShortcutFollowsThePhonesLanguage() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("HushTumblr settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals(app.hushtumblr.extension.shared.L10nTablesForTests.of("de").get("HushTumblr settings"),
                longLabel(manager));
        org.junit.Assert.assertEquals("one shortcut, relabelled, not two", 1, manager.getDynamicShortcuts().size());
    }

    /**
     * Tumblr sets its own language after the application starts, so the label published then
     * is in the phone's. On a German phone with Tumblr in English it stayed German next to an
     * English screen. The next Tumblr screen to resume labels it again in Tumblr's language.
     */
    @Test public void theShortcutIsLabelledAgainOnceThreadsHasSetItsLanguage() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        org.junit.Assert.assertEquals("HushTumblr settings", longLabel(manager));

        RuntimeEnvironment.setQualifiers("+de");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            app.hushtumblr.extension.shared.Utils.awaitBackgroundTasksForTests();
            org.junit.Assert.assertEquals(app.hushtumblr.extension.shared.L10nTablesForTests.of("de").get("HushTumblr settings"),
                    longLabel(manager));
            org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
        }
    }

    private static String longLabel(android.content.pm.ShortcutManager manager) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (SettingsEntry.SHORTCUT_ID.equals(shortcut.getId())) return String.valueOf(shortcut.getLongLabel());
        }
        return null;
    }

    /**
     * Tumblr pushes its own shortcuts at rank 0, and the platform ranks the newest push first, so
     * the HushTumblr one ended up last. A launcher that shows three or four of them, or two next
     * to a notification, cut it off. Tumblr's push still goes through, and then the HushTumblr
     * shortcut goes back in front.
     */
    @Test public void threadsOwnPushLeavesTheHushTumblrShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 3, "HushTumblr settings");

        SettingsEntry.pushDynamicShortcut(manager, threadsShortcut(context, "compose"));
        app.hushtumblr.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertNotNull("Tumblr's own push was lost", shortcut(manager, "compose"));
        org.junit.Assert.assertEquals("the HushTumblr shortcut stayed behind Tumblr's own",
                0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** An update of Tumblr's shortcuts can rank them again too, and its answer is Tumblr's own. */
    @Test public void threadsUpdateKeepsItsAnswerAndTheHushTumblrShortcutFirst() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        manager.pushDynamicShortcut(threadsShortcut(context, "search"));
        demote(context, manager, 1, "HushTumblr settings");

        boolean updated = SettingsEntry.updateShortcuts(manager,
                java.util.Collections.singletonList(threadsShortcut(context, "search")));
        app.hushtumblr.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Tumblr's update answered false", updated);
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** A call that replaces every dynamic shortcut took the HushTumblr one with it. */
    @Test public void threadsReplacingItsShortcutsPublishesTheHushTumblrOneAgain() throws Exception {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);

        boolean set = SettingsEntry.setDynamicShortcuts(manager,
                java.util.Collections.singletonList(threadsShortcut(context, "activity")));
        app.hushtumblr.extension.shared.Utils.awaitBackgroundTasksForTests();

        assertTrue("Tumblr's replacement answered false", set);
        assertNotNull("Tumblr's own shortcut was lost", shortcut(manager, "activity"));
        android.content.pm.ShortcutInfo ours = shortcut(manager, SettingsEntry.SHORTCUT_ID);
        assertNotNull("the HushTumblr shortcut stayed gone", ours);
        org.junit.Assert.assertEquals(0, ours.getRank());
        org.junit.Assert.assertEquals("HushTumblr settings", String.valueOf(ours.getLongLabel()));
    }

    /** Each start checks the place as well as the label, so a shortcut left behind comes back first. */
    @Test public void theNextStartPutsAShortcutLeftBehindBackInFront() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);
        demote(context, manager, 2, "HushTumblr settings");

        SettingsEntry.publishShortcutNow(context);

        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
        org.junit.Assert.assertEquals(1, manager.getDynamicShortcuts().size());
    }

    /**
     * The process Tumblr pushes from may not have Tumblr's language yet, so moving the shortcut
     * back keeps the label it has. Relabelled in the phone's language there, it flipped between
     * the two languages each time Tumblr pushed.
     */
    @Test public void movingTheShortcutBackKeepsItsLabel() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        String german = app.hushtumblr.extension.shared.L10nTablesForTests.of("de").get("HushTumblr settings");
        demote(context, manager, 3, german);

        SettingsEntry.keepFirstNow(context);

        org.junit.Assert.assertEquals(german, longLabel(manager));
        org.junit.Assert.assertEquals(0, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    /** What the platform leaves after Tumblr's pushes rank ahead of the HushTumblr shortcut. */
    private static void demote(android.content.Context context, android.content.pm.ShortcutManager manager,
                               int rank, String label) {
        manager.pushDynamicShortcut(new android.content.pm.ShortcutInfo.Builder(context, SettingsEntry.SHORTCUT_ID)
                .setShortLabel("HushTumblr")
                .setLongLabel(label)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(rank)
                .build());
        org.junit.Assert.assertEquals(rank, shortcut(manager, SettingsEntry.SHORTCUT_ID).getRank());
    }

    private static android.content.pm.ShortcutInfo threadsShortcut(android.content.Context context, String id) {
        return new android.content.pm.ShortcutInfo.Builder(context, id)
                .setShortLabel(id)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setRank(0)
                .build();
    }

    private static android.content.pm.ShortcutInfo shortcut(android.content.pm.ShortcutManager manager, String id) {
        for (android.content.pm.ShortcutInfo shortcut : manager.getDynamicShortcuts()) {
            if (id.equals(shortcut.getId())) return shortcut;
        }
        return null;
    }

    /**
     * The shortcut starts Tumblr's own launcher activity by name, with the extra that asks for the
     * screen. A component Tumblr doesn't have would leave the shortcut greyed out on the launcher.
     */
    @Test public void theShortcutStartsTumblrsLauncherActivity() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        SettingsEntry.publishShortcutNow(context);

        Intent intent = shortcut(manager, SettingsEntry.SHORTCUT_ID).getIntent();
        assertNotNull("the shortcut carries no intent", intent);
        org.junit.Assert.assertEquals(new android.content.ComponentName(context.getPackageName(),
                SettingsEntry.LAUNCHER_ACTIVITY), intent.getComponent());
        assertTrue("the shortcut doesn't ask for the screen",
                intent.getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));
    }

    /**
     * A Tumblr task the shortcut started keeps the shortcut's intent as its own. With a new task
     * alone, the next tap matched that task, Android only brought it forward and no screen opened
     * (seen on the API 36 emulator). The shortcut clears the task, so the launcher activity starts
     * on every tap, and a shortcut published before that is pushed again with the same label.
     */
    @Test public void theShortcutStartsTumblrAfreshAndAnOlderOneIsReplaced() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        android.content.pm.ShortcutManager manager = context.getSystemService(android.content.pm.ShortcutManager.class);
        manager.pushDynamicShortcut(new android.content.pm.ShortcutInfo.Builder(context, SettingsEntry.SHORTCUT_ID)
                .setShortLabel("HushTumblr")
                .setLongLabel("HushTumblr settings")
                .setIntent(new Intent(Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                .setRank(0)
                .build());

        SettingsEntry.publishShortcutNow(context);

        Intent intent = shortcut(manager, SettingsEntry.SHORTCUT_ID).getIntent();
        assertNotNull("the shortcut carries no intent", intent);
        org.junit.Assert.assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK,
                intent.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertTrue("the replacement doesn't ask for the screen",
                intent.getBooleanExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, false));
        org.junit.Assert.assertEquals("one shortcut, replaced, not two", 1, manager.getDynamicShortcuts().size());
        org.junit.Assert.assertEquals("HushTumblr settings", longLabel(manager));
    }

    /**
     * "Additional settings in the app" on Android's App info page starts the launcher activity with
     * ACTION_APPLICATION_PREFERENCES, and the screen opens over it. The action is spent, so a
     * recreated activity doesn't ask again and Tumblr goes on as if started from its icon.
     */
    @Test public void appInfosAdditionalSettingsOpensTheScreen() {
        Intent fromAppInfo = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, fromAppInfo).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();

        assertNotNull("App info's request didn't open the screen", dialogOver(activity.get()));
        org.junit.Assert.assertEquals(Intent.ACTION_MAIN, activity.get().getIntent().getAction());
    }

    /**
     * With Tumblr already running, App info's request reaches the activity through onNewIntent,
     * and the screen opens when it resumes. A plain launch from the icon opens nothing.
     */
    @Test public void appInfosRequestOpensTheScreenOverARunningThreads() {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class,
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        assertNull("a launch from the icon opened the screen", dialogOver(activity.get()));

        activity.pause();
        Intent fromAppInfo = new Intent(Intent.ACTION_APPLICATION_PREFERENCES);
        SettingsEntry.onNewIntent(activity.get(), fromAppInfo);
        activity.resume();
        ShadowLooper.idleMainLooper();

        assertNotNull("App info's request didn't open the screen", dialogOver(activity.get()));
        org.junit.Assert.assertEquals(Intent.ACTION_MAIN, fromAppInfo.getAction());
    }

    /**
     * Signed out, the shortcut's screen lands on the login screen, and Tumblr replaces that with
     * its logged-out screen a moment later. Android resumes the replacement before it destroys the
     * login screen, so the replacement is already in front when the request comes back, and it
     * never resumes again to pick it up. On a phone that left the request pending until it expired.
     */
    @Test public void theScreenFollowsItsHostToAReplacementAlreadyInFront() {
        ActivityController<Activity> login = openedOverNewActivity();

        ActivityController<Activity> loggedOut = replace(login);

        assertNotNull("the screen was lost when its host went away", dialogOver(loggedOut.get()));
    }

    @Test public void aScreenThePersonClosedStaysClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsEntry.onClosedByUser();

        ActivityController<Activity> loggedOut = replace(login);

        assertNull("a screen the person closed came back", dialogOver(loggedOut.get()));
    }

    /**
     * The three ways a person leaves the screen (the title bar's arrow, the Back key and Back on the
     * recovery page) have to tell the entry so, or the screen follows its host to the next Tumblr
     * screen as if nobody had closed it. The request is still inside its window here, so a close
     * the entry didn't hear about would reopen it.
     */
    @Test public void backOnTheRecoveryPageKeepsTheScreenClosedWhenItsHostGoesAway() {
        HushTumblrPreferenceFragment.failNextInitialization = new IllegalStateException("injected");
        ActivityController<Activity> login = openedOverNewActivity();
        android.preference.Preference back = pageOver(login.get()).findPreference("morphe_settings_error_back");
        assertNotNull("no recovery page", back);

        back.getOnPreferenceClickListener().onPreferenceClick(back);
        ShadowLooper.idleMainLooper();
        assertNull("Back left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person left it with Back", dialogOver(loggedOut.get()));
    }

    @Test public void theBackKeyKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();

        ((SettingsDialog) dialogOver(login.get())).getDialog().onBackPressed();
        ShadowLooper.idleMainLooper();
        assertNull("the Back key left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the Back key", dialogOver(loggedOut.get()));
    }

    /** The arrow dismisses rather than cancels, so it has to tell the entry itself. */
    @Test public void theTitleBarArrowKeepsTheScreenClosedWhenItsHostGoesAway() {
        ActivityController<Activity> login = openedOverNewActivity();
        SettingsDialog dialog = (SettingsDialog) dialogOver(login.get());
        ArrayList<View> labelled = new ArrayList<>();
        dialog.getView().findViewsWithText(labelled, "Back", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        View arrow = null;
        for (View view : labelled) {
            if (view instanceof android.widget.ImageButton && ((android.widget.ImageButton) view).getDrawable() != null) arrow = view;
        }
        assertNotNull("no arrow in the title bar among " + labelled, arrow);

        assertTrue("the arrow didn't take the tap", arrow.performClick());
        ShadowLooper.idleMainLooper();
        assertNull("the arrow left the screen open", dialogOver(login.get()));
        ActivityController<Activity> loggedOut = replace(login);

        assertNull("the screen came back after the person closed it with the arrow", dialogOver(loggedOut.get()));
    }

    /**
     * The restart row restarts Tumblr with a marker naming its page. The next start takes the
     * marker, the same way onApplicationCreate does, and the screen opens over the first Tumblr
     * screen to resume, on that page. Closing it drops the page, so a later open starts at the top.
     */
    @Test public void aStartAfterTheRestartRowOpensTheScreenOnItsPage() {
        android.content.SharedPreferences store = app.hushtumblr.extension.shared.settings.Setting.preferences.preferences;
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        try {
            org.junit.Assert.assertFalse("a start without a marker asked for the screen",
                    ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn));
            ReopenSettingsAfterRestart.remember(store, "Links", android.os.SystemClock.elapsedRealtime());
            assertTrue(ReopenSettingsAfterRestart.reopenIfPending(SettingsEntry::reopenOn));
            org.junit.Assert.assertFalse("the marker outlived the start that read it",
                    store.contains("hush_reopen_settings_page"));

            ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
            ShadowLooper.idleMainLooper();
            SettingsDialog dialog = (SettingsDialog) dialogOver(activity.get());
            assertNotNull("the restart didn't open the screen again", dialog);
            org.junit.Assert.assertEquals("Links", dialog.startPage());
            HushTumblrPreferenceFragment page = pageOver(activity.get());
            android.widget.ListView list = page.getView().findViewById(android.R.id.list);
            boolean onLinks = false;
            for (int i = 0; i < list.getCount(); i++) {
                Object row = list.getItemAtPosition(i);
                if (row instanceof android.preference.Preference && HushTumblrPreferenceFragment.SUPPORTED_LINKS
                        .equals(((android.preference.Preference) row).getKey())) onLinks = true;
            }
            assertTrue("the screen didn't open on Links", onLinks);

            SettingsL10nTest.backOf(dialog).performClick();
            SettingsL10nTest.backOf(dialog).performClick();
            SettingsL10nTest.backOf(dialog).performClick();
            ShadowLooper.idleMainLooper();
            assertNull("the screen stayed open", dialogOver(activity.get()));
            assertNull("closing the screen kept its page for the next open", SettingsEntry.requestedPage());
        } finally {
            SettingsEntry.onClosedByUser();
            PatchFamily.inBuildForTests = null;
        }
    }

    @Test public void aLongPressOnTheDashboardLogoOpensTheScreenAndATapStaysTumblrs() {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        try {
            int[] taps = {0};
            View logo = new android.widget.FrameLayout(activity.get());
            logo.setOnClickListener(tapped -> taps[0]++);
            SettingsEntry.onDashboardLogo(logo);
            // Tumblr binds the top bar again when the dashboard's view comes back.
            SettingsEntry.onDashboardLogo(logo);
            assertTrue("the logo got no long press", logo.isLongClickable());

            logo.performClick();
            ShadowLooper.idleMainLooper();
            org.junit.Assert.assertEquals("the tap stopped being Tumblr's", 1, taps[0]);
            assertNull("a tap opened the screen", dialogOver(activity.get()));

            // A page a restart left waiting doesn't decide where the logo's screen opens.
            SettingsEntry.reopenOn("Links");
            assertTrue("the long press wasn't taken", logo.performLongClick());
            ShadowLooper.idleMainLooper();
            SettingsDialog dialog = (SettingsDialog) dialogOver(activity.get());
            assertNotNull("the long press didn't open the screen", dialog);
            org.junit.Assert.assertEquals("the logo's screen opened on another page", "", dialog.startPage());
            org.junit.Assert.assertEquals("the long press counted as a tap too", 1, taps[0]);
        } finally {
            SettingsEntry.onClosedByUser();
            activity.close();
        }
    }

    @Test public void aLogoTumblrAlreadyLongPressesStaysTumblrs() {
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class).setup();
        try {
            int[] presses = {0};
            View logo = new android.widget.FrameLayout(activity.get());
            logo.setOnLongClickListener(pressed -> {
                presses[0]++;
                return true;
            });
            SettingsEntry.onDashboardLogo(logo);

            assertTrue(logo.performLongClick());
            ShadowLooper.idleMainLooper();
            org.junit.Assert.assertEquals("Tumblr's long press didn't run", 1, presses[0]);
            assertNull("the screen took Tumblr's long press", dialogOver(activity.get()));
        } finally {
            activity.close();
        }
    }

    @Test public void aLogoOutsideAnActivityLeavesTheLongPressAlone() {
        View logo = new android.widget.FrameLayout(RuntimeEnvironment.getApplication());
        // A press nothing takes goes on to the parent for a context menu, so the logo has one.
        new android.widget.FrameLayout(RuntimeEnvironment.getApplication()).addView(logo);
        SettingsEntry.onDashboardLogo(logo);
        org.junit.Assert.assertFalse("a press with no screen to open over was taken", logo.performLongClick());
    }

    @SuppressWarnings("deprecation")
    private static HushTumblrPreferenceFragment pageOver(Activity activity) {
        SettingsDialog dialog = (SettingsDialog) dialogOver(activity);
        assertNotNull("no screen over " + activity, dialog);
        return (HushTumblrPreferenceFragment) dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
    }

    /** An activity started by the launcher shortcut, with the screen open over it. */
    private static ActivityController<Activity> openedOverNewActivity() {
        Intent shortcut = new Intent().putExtra(SettingsEntry.EXTRA_OPEN_SETTINGS, true);
        ActivityController<Activity> activity = Robolectric.buildActivity(Activity.class, shortcut).create();
        SettingsEntry.onActivityCreate(activity.get());
        activity.start().resume();
        ShadowLooper.idleMainLooper();
        assertNotNull("the screen did not open over the shortcut's activity", dialogOver(activity.get()));
        return activity;
    }

    /** The order Android uses when an activity starts the next one and finishes. */
    private static ActivityController<Activity> replace(ActivityController<Activity> current) {
        current.pause();
        ActivityController<Activity> next = Robolectric.buildActivity(Activity.class).create().start().resume();
        current.stop().destroy();
        ShadowLooper.idleMainLooper();
        return next;
    }

    private static Object dialogOver(Activity activity) {
        return activity.getFragmentManager().findFragmentByTag("hushtumblr_settings");
    }
}
