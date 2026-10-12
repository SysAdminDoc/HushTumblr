/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.tumblr.timeline.HushRootPost;
import app.hushtumblr.extension.tumblr.timeline.TimelineFilter;
import app.hushtumblr.extension.tumblr.timeline.TimelineItems;
import com.tumblr.rumblr.model.TimelineObject;
import com.tumblr.rumblr.model.TimelineObjectType;
import com.tumblr.rumblr.model.Timelineable;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class RootPostMutesTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File file;

    @Before public void prepare() {
        PatchFamilyForTests.inBuild(EnumSet.of(PatchFamily.MUTE_ROOT_POSTS));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.ROOT_POST_MUTES));
        Settings.MUTE_ROOT_POSTS.save(true);
        PauseForTests.resume();
        HookStatus.clear();
        file = new File(temporary.getRoot(), "private.json");
        RootPostMutes.fileForTests = file;
        RootPostMutes.workerForTests = Runnable::run;
        RootPostMutes.resetForTests();
    }

    @After public void restore() {
        RootPostMutes.resetForTests();
        RootPostMutes.fileForTests = null;
        RootPostMutes.workerForTests = null;
        Settings.MUTE_ROOT_POSTS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    private void account(String id) {
        RootPostMutes.credentialsChanging(); RootPostMutes.credentialsChanged();
        begin(id);
        RootPostMutes.commitAccount(); RootPostMutes.requestExited();
        assertNotNull(RootPostMutes.capture());
    }
    private void begin(String id) {
        Object request = new Object();
        RootPostMutes.requestCreated(request); RootPostMutes.requestEntered(request);
        RootPostMutes.beginAccountChange((HushAccountIdentity) () -> id);
    }
    private HushRootPost post(String root) { return () -> root; }
    private RootPostMutes.Undo mute(String root) {
        AtomicReference<RootPostMutes.Undo> result = new AtomicReference<>();
        RootPostMutes.mute(RootPostMutes.capture(), root, result::set);
        assertNotNull(result.get());
        return result.get();
    }

    @Test public void originalAndReblogsShareOnlyTheirCanonicalRoot() {
        account("account-a");
        mute("123456789");
        assertTrue(RootPostMutes.shouldHide(post("123456789")));
        assertTrue(RootPostMutes.shouldHide(post("123456789")));
        assertFalse(RootPostMutes.shouldHide(post("987654321")));
        assertFalse(RootPostMutes.shouldHide(post(null)));
        assertFalse(RootPostMutes.shouldHide(new Object()));
        assertFalse(RootPostMutes.shouldHide((HushRootPost) () -> { throw new IllegalStateException("unreadable"); }));
    }

    @Test public void accountObserversSeeInvalidationAndFreshCommitBeforePrivateStorageCompletes() {
        account("account-a");
        AtomicInteger changes = new AtomicInteger();
        AtomicReference<RootPostMutes.AccountSnapshot> observed = new AtomicReference<>(RootPostMutes.accountSnapshot());
        Runnable observer = () -> { changes.incrementAndGet(); observed.set(RootPostMutes.accountSnapshot()); };
        RootPostMutes.addAccountObserver(observer); RootPostMutes.addAccountObserver(observer);
        RootPostMutes.beginAccountChange((HushAccountIdentity) () -> "unproven-response");
        assertNull(observed.get()); assertEquals(1, changes.get());
        RootPostMutes.credentialsChanged();
        begin("account-b");
        List<Runnable> queued = new ArrayList<>(); RootPostMutes.workerForTests = queued::add;
        RootPostMutes.commitAccount(); RootPostMutes.requestExited();
        assertEquals(4, changes.get()); assertNotNull(observed.get());
        assertTrue(RootPostMutes.isCurrent(observed.get())); assertNull(RootPostMutes.capture());
        assertEquals(1, queued.size()); queued.get(0).run();
        assertNotNull(RootPostMutes.capture()); assertEquals(4, changes.get());
        RootPostMutes.logout(); assertNull(observed.get()); assertEquals(5, changes.get());
    }

    @Test public void accountObserversRunOutsideTheAccountLockAndCannotInterruptNativePersistence() {
        AtomicBoolean outside = new AtomicBoolean();
        Runnable unreadable = () -> { throw new IllegalStateException("detached consumer"); };
        Runnable observer = () -> {
            CountDownLatch completed = new CountDownLatch(1);
            Thread reader = new Thread(() -> { RootPostMutes.accountSnapshot(); completed.countDown(); });
            reader.start();
            try { outside.set(completed.await(1, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        RootPostMutes.addAccountObserver(unreadable); RootPostMutes.addAccountObserver(observer);
        account("account-a");
        assertTrue(outside.get()); assertNotNull(RootPostMutes.accountSnapshot());
        RootPostMutes.logout(); assertTrue(outside.get()); assertNull(RootPostMutes.accountSnapshot());
    }

    @Test public void canonicalRootsNeverSubstituteTheCurrentIdOfAnUnknownRootReblog() {
        List<?> empty = java.util.Collections.emptyList();
        assertEquals("11", RootPostMutes.canonicalRoot("11", "22", "33", null, false));
        assertEquals("33", RootPostMutes.canonicalRoot(null, null, "33", empty, true));
        assertEquals("33", RootPostMutes.canonicalRoot("", "", "33", empty, true));
        assertNull(RootPostMutes.canonicalRoot(null, "22", "33", empty, true));
        assertNull(RootPostMutes.canonicalRoot(null, null, "33", java.util.List.of(new Object()), true));
        assertNull(RootPostMutes.canonicalRoot(null, null, "33", empty, false));
        assertNull(RootPostMutes.canonicalRoot(null, null, "33", null, true));
        assertNull(RootPostMutes.canonicalRoot("invalid", null, "33", empty, true));
        assertNull(RootPostMutes.canonicalRoot(null, null, "invalid", empty, true));
    }

    @Test public void aMuteOnlyPageDropsOriginalsAndReblogsButKeepsHeadingsAndUnknownRoots() {
        account("account-a"); String root = "12345678901234567"; mute(root);
        Object heading = TimelineItems.of(TimelineObjectType.TITLE);
        Object original = new TimelineObject(new RootPayload(null, null, root), false);
        Object reblog = new TimelineObject(new RootPayload(root, "987654321", "333333333"), false);
        Object unknown = new TimelineObject(new RootPayload(null, "987654321", "444444444"), false);
        Object unprovedReblog = new TimelineObject(new RootPayload(null, null, root, java.util.List.of(new Object()), true), false);
        Object missingTrail = new TimelineObject(new RootPayload(null, null, root, java.util.Collections.emptyList(), false), false);
        Object other = new TimelineObject(new RootPayload(null, null, "555555555"), false);
        List<?> page = java.util.Arrays.asList(heading, original, reblog, unknown, unprovedReblog, missingTrail, other);
        assertEquals(java.util.Arrays.asList(heading, unknown, unprovedReblog, missingTrail, other), TimelineFilter.filter(page));
        String report = String.join("\n", HookStatus.report(""));
        assertTrue(report, report.contains("post removed 2")); assertFalse(report, report.contains(root));
        RootPostMutes.logout(); assertSame(page, TimelineFilter.filter(page));
    }

    private static final class RootPayload implements Timelineable, HushRootPost {
        private final String root, parent, id;
        private final List<?> trail;
        private final boolean trailPresent;
        RootPayload(String root, String parent, String id) { this(root, parent, id, java.util.Collections.emptyList(), true); }
        RootPayload(String root, String parent, String id, List<?> trail, boolean trailPresent) {
            this.root = root; this.parent = parent; this.id = id; this.trail = trail; this.trailPresent = trailPresent;
        }
        @Override public String hushRootId() { return RootPostMutes.canonicalRoot(root, parent, id, trail, trailPresent); }
        @Override public String getId() { return id; }
        @Override public TimelineObjectType getTimelineObjectType() { return TimelineObjectType.POST; }
    }

    @Test public void logoutAndProcessRestartRequireAnotherSuccessfulAuthenticatedCommit() {
        account("account-a"); mute("11");
        RootPostMutes.logout();
        assertFalse(RootPostMutes.shouldHide(post("11")));
        RootPostMutes.commitAccount();
        assertFalse(RootPostMutes.shouldHide(post("11")));
        RootPostMutes.resetForTests();
        assertFalse(RootPostMutes.active());
        account("account-a");
        assertTrue(RootPostMutes.shouldHide(post("11")));
    }

    @Test public void switchingAccountsNeverSharesMutesOrOldMenuActions() {
        account("account-a"); mute("11");
        RootPostMutes.Session oldMenu = RootPostMutes.capture();
        account("account-b");
        assertFalse(RootPostMutes.shouldHide(post("11")));
        AtomicReference<RootPostMutes.Undo> stale = new AtomicReference<>();
        RootPostMutes.mute(oldMenu, "22", stale::set);
        assertNull(stale.get());
        mute("33");
        account("account-a");
        assertTrue(RootPostMutes.shouldHide(post("11")));
        assertFalse(RootPostMutes.shouldHide(post("33")));
        assertFalse(RootPostMutes.shouldHide(post("22")));
    }

    @Test public void anOlderLoadCannotPublishAfterAnAccountChange() {
        List<Runnable> queued = new ArrayList<>();
        RootPostMutes.workerForTests = queued::add;
        begin("account-a"); RootPostMutes.commitAccount(); RootPostMutes.requestExited();
        begin("account-b"); RootPostMutes.commitAccount(); RootPostMutes.requestExited();
        assertNull(RootPostMutes.capture());
        queued.get(0).run();
        assertNull(RootPostMutes.capture());
        queued.get(1).run();
        assertNotNull(RootPostMutes.capture());
        assertTrue(RootPostMutes.capture().roots.isEmpty());
    }

    @Test public void aFreshAccountSnapshotIsAvailableBeforePrivateStorageAndExpiresOnEveryTransition() {
        List<Runnable> queued = new ArrayList<>();
        RootPostMutes.workerForTests = queued::add;
        HushAccountIdentity nativeAccount = () -> "account-a";
        Object request = new Object(); RootPostMutes.requestCreated(request); RootPostMutes.requestEntered(request);
        RootPostMutes.beginAccountChange(nativeAccount); RootPostMutes.commitAccount(); RootPostMutes.requestExited();
        RootPostMutes.AccountSnapshot snapshot = RootPostMutes.accountSnapshot();
        assertNotNull(snapshot); assertSame(nativeAccount, snapshot.nativeAccount);
        assertEquals(64, snapshot.accountKey.length()); assertFalse(snapshot.accountKey.contains("account-a"));
        assertNull(RootPostMutes.capture()); assertTrue(RootPostMutes.isCurrent(snapshot));
        queued.get(0).run(); assertTrue(RootPostMutes.isCurrent(snapshot));
        RootPostMutes.credentialsChanging();
        assertNull(RootPostMutes.accountSnapshot()); assertFalse(RootPostMutes.isCurrent(snapshot));
        assertTrue(RootPostMutes.credentialEpoch() > snapshot.credentialEpoch);
        RootPostMutes.credentialsChanged(); assertNull(RootPostMutes.accountSnapshot());
        RootPostMutes.logout(); assertNull(RootPostMutes.accountSnapshot());
    }

    @Test public void anotherThreadLoggingOutInvalidatesAnAlreadyStartedCommit() throws Exception {
        begin("account-a");
        Thread logout = new Thread(RootPostMutes::logout);
        logout.start(); logout.join();
        RootPostMutes.commitAccount();
        assertNull(RootPostMutes.capture());
    }

    @Test public void unreadableOrMissingAccountIdentityInvalidatesThePreviousAccount() {
        account("account-a"); mute("11");
        for (Object account : new Object[]{new Object(), (HushAccountIdentity) () -> "", (HushAccountIdentity) () -> "email@example.com",
                (HushAccountIdentity) () -> { throw new IllegalStateException("unreadable"); }}) {
            Object request = new Object(); RootPostMutes.requestCreated(request); RootPostMutes.requestEntered(request);
            RootPostMutes.beginAccountChange(account); RootPostMutes.commitAccount();
            assertNull(RootPostMutes.capture());
            assertFalse(RootPostMutes.shouldHide(post("11")));
        }
    }

    @Test public void pauseMissingCapabilityAndTheOffSwitchKeepPostsWithoutDiscardingPrivateMutes() {
        account("account-a"); mute("11");
        Settings.MUTE_ROOT_POSTS.save(false);
        assertFalse(RootPostMutes.shouldHide(post("11")));
        Settings.MUTE_ROOT_POSTS.save(true);
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        assertFalse(RootPostMutes.shouldHide(post("11")));
        PauseForTests.resume();
        assertTrue(RootPostMutes.shouldHide(post("11")));
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertFalse(RootPostMutes.shouldHide(post("11")));
    }

    @Test public void undoIsLocalToTheLatestChangeAndItsAccountSession() {
        account("account-a");
        RootPostMutes.Undo first = mute("11");
        RootPostMutes.Undo second = mute("22");
        AtomicReference<Boolean> result = new AtomicReference<>();
        RootPostMutes.undo(first, result::set);
        assertFalse(result.get());
        RootPostMutes.undo(second, result::set);
        assertTrue(result.get());
        assertTrue(RootPostMutes.shouldHide(post("11")));
        assertFalse(RootPostMutes.shouldHide(post("22")));
        account("account-b"); RootPostMutes.undo(first, result::set);
        assertFalse(result.get());
    }

    @Test public void clearingAndUndoWorkWhilePausedAndRestoreOnlyThisAccount() {
        account("account-a"); mute("11"); mute("22"); PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        AtomicReference<RootPostMutes.Undo> clear = new AtomicReference<>();
        RootPostMutes.clear(RootPostMutes.capture(), clear::set);
        assertNotNull(clear.get()); assertTrue(RootPostMutes.capture().roots.isEmpty());
        AtomicReference<Boolean> restored = new AtomicReference<>();
        RootPostMutes.undo(clear.get(), restored::set);
        assertTrue(restored.get()); assertEquals(2, RootPostMutes.capture().roots.size());
    }

    @Test public void failedPersistenceDoesNotPublishTheNewMuteOrEraseTheOldOne() throws Exception {
        account("account-a"); mute("11");
        RootPostMutes.fileForTests = temporary.newFolder("unwritable-file");
        AtomicReference<RootPostMutes.Undo> result = new AtomicReference<>();
        RootPostMutes.mute(RootPostMutes.capture(), "22", result::set);
        assertNull(result.get()); assertTrue(RootPostMutes.shouldHide(post("11"))); assertFalse(RootPostMutes.shouldHide(post("22")));
    }

    @Test public void malformedAndOversizedPrivateDocumentsStayUnavailable() throws Exception {
        for (String text : new String[]{"{broken", "x".repeat(RootPostMuteStore.MAX_BYTES + 1)}) {
            RootPostMutes.resetForTests(); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            begin("account-a"); RootPostMutes.commitAccount();
            assertNull(RootPostMutes.capture()); assertFalse(RootPostMutes.active());
        }
    }

    @Test public void thePrivateDocumentDoesNotPersistTheRawAccountIdentity() throws Exception {
        account("account-a-unique-uuid"); mute("12345");
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("12345")); assertFalse(text.contains("account-a-unique-uuid"));
    }

    @Test public void responsesCreatedBeforeLogoutOrDuringCredentialReplacementCannotAuthenticate() {
        Object old = new Object(); RootPostMutes.requestCreated(old);
        RootPostMutes.logout();
        Object loggedOut = new Object(); RootPostMutes.requestCreated(loggedOut);
        RootPostMutes.credentialsChanging();
        Object transitional = new Object(); RootPostMutes.requestCreated(transitional);
        RootPostMutes.credentialsChanged();
        for (Object request : new Object[]{old, loggedOut, transitional}) {
            RootPostMutes.requestEntered(request);
            RootPostMutes.beginAccountChange((HushAccountIdentity) () -> "old-account"); RootPostMutes.commitAccount();
            assertNull(RootPostMutes.capture());
        }
        begin("new-account"); RootPostMutes.commitAccount(); assertNotNull(RootPostMutes.capture());
    }

    @Test public void anUnprovedResponseOrAnExitedCallbackCannotReuseTheRequestWindow() {
        account("account-a"); mute("11");
        RootPostMutes.beginAccountChange((HushAccountIdentity) () -> "unproved"); RootPostMutes.commitAccount();
        assertNull(RootPostMutes.capture());
        Object request = new Object(); RootPostMutes.requestCreated(request); RootPostMutes.requestEntered(request);
        RootPostMutes.requestExited();
        RootPostMutes.beginAccountChange((HushAccountIdentity) () -> "unrelated"); RootPostMutes.commitAccount();
        assertNull(RootPostMutes.capture());
    }
}
