package app.hushtumblr.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.WorkerPoolForTests;
import app.hushtumblr.extension.shared.diagnostics.DiagnosticCategory;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BaseSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** The diagnostic clear row keeps a complete, one-tap way back. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 30)
public class ClearLogBufferPreferenceTest {
    private Context context;

    @Before public void setUp() throws Exception {
        awaitWork();
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        emptyEverythingAndDropUndo();
        LogBufferManager.clearedMessage = "Diagnostic data cleared. Tap again to put it back.";
        LogBufferManager.nothingToClearMessage = "There is no diagnostic data to clear";
        LogBufferManager.restoredMessage = "Diagnostic data put back";
        LogBufferManager.nothingToRestoreMessage = "There is no diagnostic data to put back";
        LogBufferManager.restoreFailedMessage =
                "Couldn't put back the diagnostic data. Try again.";
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        awaitWork();
        Utils.setContext(context);
        emptyEverythingAndDropUndo();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        // The sentences are static, so a later test class in the same sandbox would read these
        // in place of the catalog's.
        LogBufferManager.clearedMessage = null;
        LogBufferManager.nothingToClearMessage = null;
        LogBufferManager.restoredMessage = null;
        LogBufferManager.nothingToRestoreMessage = null;
        LogBufferManager.restoreFailedMessage = null;
        ShadowToast.reset();
    }

    @Test public void theNextTapRestoresEveryDiagnosticSurfaceAndKeepsNewFindings()
            throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old buffered event");
        HookStatus.bound("comments", "like_button");
        HookStatus.missingViewId("comments", "missing_button");
        LogBufferManager.persistCrashReport(context, "old java crash");
        LogBufferManager.persistNpthCrashReport(context, "old native crash");

        ClearLogBufferPreference first = row();
        assertTrue(first.getOnPreferenceClickListener().onPreferenceClick(first));
        awaitWork();
        assertTrue(LogBufferManager.canUndoClear());
        assertEquals("Cleared summary", first.getSummary());
        // A report still has the build's facts, and none of what was cleared.
        String cleared = LogBufferManager.buildExportText();
        assertFalse(cleared, cleared.contains("old buffered event"));
        assertFalse(cleared, cleared.contains("[LATEST JAVA CRASH]"));
        assertFalse(cleared, cleared.contains("[LATEST NATIVE CRASH SIGNAL]"));
        assertFalse(cleared, cleared.contains("[HOOK STATUS]"));
        assertFalse(HookStatus.anyMissing());
        assertEquals("", LogBufferManager.readCrashReport(context));
        assertEquals("", LogBufferManager.readNpthCrashReport(context));
        assertEquals("Diagnostic data cleared. Tap again to put it back.",
                ShadowToast.getTextOfLatestToast());

        // The row can be rebuilt while its offer survives. New evidence arriving meanwhile is
        // retained after the older data is put back in front of it.
        ClearLogBufferPreference rebuilt = row();
        assertEquals("Cleared summary", rebuilt.getSummary());
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "AfterClear", "INFO", "new buffered event");
        HookStatus.bound("inbox", "item_view");
        assertTrue(rebuilt.getOnPreferenceClickListener().onPreferenceClick(rebuilt));
        awaitWork();

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", rebuilt.getSummary());
        assertEquals("old java crash", LogBufferManager.readCrashReport(context));
        assertEquals("old native crash", LogBufferManager.readNpthCrashReport(context));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("old buffered event"));
        assertTrue(report, report.contains("new buffered event"));
        assertTrue(report, report.contains("comments: invoked 0, 1 found, 1 missing"));
        assertTrue(report, report.contains("inbox: invoked 0, 1 found, 0 missing"));

        // Restoring a raw miss key, rather than only its displayed sentence, keeps the normal
        // repeat guard working when the same hook runs again.
        HookStatus.missingViewId("comments", "missing_button");
        assertEquals(1, HookStatus.missing("comments").size());
        assertEquals("Diagnostic data put back", ShadowToast.getTextOfLatestToast());
    }

    @Test public void clearingNothingDoesNotPromiseAnUndoThatDoesNotExist() throws Exception {
        ClearLogBufferPreference preference = row();

        assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
        awaitWork();

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", preference.getSummary());
        assertEquals("There is no diagnostic data to clear",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void clearKeepsTheMainThreadAndRebuiltRowsResponsiveWhileFilesWait()
            throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old event");
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        ClearLogBufferPreference first = row();
        try {
            first.getOnPreferenceClickListener().onPreferenceClick(first);
            assertTrue("clear did not reach the file worker",
                    blocked.blocked.await(2, TimeUnit.SECONDS));
            assertFalse("file access ran on the main thread", blocked.blockedOnMainThread);
            assertFalse(first.isEnabled());
            assertEquals("Clearing diagnostic data", first.getSummary());

            // Both a status query and a replacement row must avoid the worker's generation lock.
            assertFalse(LogBufferManager.canUndoClear());
            ClearLogBufferPreference rebuilt = row();
            assertFalse(rebuilt.isEnabled());
            assertEquals("Clearing diagnostic data", rebuilt.getSummary());
            AtomicBoolean mainTurn = new AtomicBoolean();
            Utils.runOnMainThread(() -> mainTurn.set(true));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the main loop stopped while clearing files", mainTurn.get());
            // A stale click must not queue a second clear that would discard the Undo snapshot.
            rebuilt.getOnPreferenceClickListener().onPreferenceClick(rebuilt);
            blocked.release.countDown();
            awaitWork();
            assertTrue(first.isEnabled());
            assertTrue(rebuilt.isEnabled());
            assertTrue(LogBufferManager.canUndoClear());
            assertEquals("Cleared summary", rebuilt.getSummary());
        } finally {
            blocked.release.countDown();
            awaitWork();
        }
    }

    @Test public void undoKeepsItsOfferAndRebuiltRowsResponsiveUntilTheWorkerFinishes()
            throws Exception {
        LogBufferManager.persistCrashReport(context, "saved crash");
        LogBufferManager.clearLogBuffer();
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        ClearLogBufferPreference first = row();
        try {
            first.getOnPreferenceClickListener().onPreferenceClick(first);
            assertTrue("undo did not reach the file worker",
                    blocked.blocked.await(2, TimeUnit.SECONDS));
            assertFalse("file access ran on the main thread", blocked.blockedOnMainThread);
            assertTrue(LogBufferManager.canUndoClear());
            assertFalse(first.isEnabled());
            ClearLogBufferPreference rebuilt = row();
            assertFalse(rebuilt.isEnabled());
            assertEquals("Putting diagnostic data back", rebuilt.getSummary());
            AtomicBoolean mainTurn = new AtomicBoolean();
            Utils.runOnMainThread(() -> mainTurn.set(true));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue("the main loop stopped while restoring files", mainTurn.get());
            blocked.release.countDown();
            awaitWork();
            assertFalse(LogBufferManager.canUndoClear());
            assertTrue(first.isEnabled());
            assertTrue(rebuilt.isEnabled());
            assertEquals("Clear summary", rebuilt.getSummary());
            assertEquals("saved crash", LogBufferManager.readCrashReport(context));
        } finally {
            blocked.release.countDown();
            awaitWork();
        }
    }

    @Test public void aFullWorkerQueueLeavesTheRowReadyAndTheDataAvailable() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "must remain available");
        ClearLogBufferPreference preference = row();
        try (WorkerPoolForTests ignored = WorkerPoolForTests.fill()) {
            preference.getOnPreferenceClickListener().onPreferenceClick(preference);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(preference.isEnabled());
            assertEquals("Clear summary", preference.getSummary());
            assertFalse(LogBufferManager.canUndoClear());
            assertTrue(LogBufferManager.buildExportText().contains("must remain available"));
            assertEquals("Couldn't start that. Try again in a moment.",
                    ShadowToast.getTextOfLatestToast());
        }
        preference.getOnPreferenceClickListener().onPreferenceClick(preference);
        awaitWork();
        assertTrue(preference.isEnabled());
        assertTrue(LogBufferManager.canUndoClear());
    }

    @Test public void aFailedRestoreKeepsUndoReadyForAnotherTap() throws Exception {
        LogBufferManager.persistCrashReport(context, "saved crash");
        LogBufferManager.clearLogBuffer();
        Utils.setContext(new ContextWrapper(context) {
            @Override public File getFilesDir() {
                throw new IllegalStateException("storage temporarily unavailable");
            }
        });
        ClearLogBufferPreference preference = row();
        preference.getOnPreferenceClickListener().onPreferenceClick(preference);
        awaitWork();
        assertTrue(LogBufferManager.canUndoClear());
        assertTrue(preference.isEnabled());
        assertEquals("Cleared summary", preference.getSummary());
        assertEquals("Couldn't put back the diagnostic data. Try again.",
                ShadowToast.getTextOfLatestToast());

        Utils.setContext(context);
        preference.getOnPreferenceClickListener().onPreferenceClick(preference);
        awaitWork();
        assertFalse(LogBufferManager.canUndoClear());
        assertTrue(preference.isEnabled());
        assertEquals("Clear summary", preference.getSummary());
        assertEquals("saved crash", LogBufferManager.readCrashReport(context));
    }

    @Test public void anEventArrivingDuringClearStartsTheNewGeneration() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old event");
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread clear = thread("clear diagnostics", failure, LogBufferManager::clearLogBuffer);
        clear.start();
        assertTrue("clear never reached its snapshot boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("record during clear", failure, () ->
                LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                        "DuringClear", "INFO", "new event"));
        producer.start();
        blocked.release.countDown();
        join(clear);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        String afterClear = LogBufferManager.buildExportText();
        assertFalse(afterClear, afterClear.contains("old event"));
        assertTrue(afterClear, afterClear.contains("new event"));
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        String restored = LogBufferManager.buildExportText();
        assertTrue(restored, restored.contains("old event"));
        assertTrue(restored, restored.contains("new event"));
    }

    @Test public void undoDoesNotOverwriteACrashSavedWhileItRestores() throws Exception {
        LogBufferManager.persistCrashReport(context, "cleared crash");
        LogBufferManager.clearLogBuffer();
        assertTrue(LogBufferManager.canUndoClear());

        BlockingFilesContext blocked = new BlockingFilesContext(context, 2);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread undo = thread("restore diagnostics", failure, LogBufferManager::undoClear);
        undo.start();
        assertTrue("undo never reached its crash write boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("save newer crash", failure,
                () -> LogBufferManager.persistCrashReport(blocked, "newer crash"));
        producer.start();
        // In the broken implementation the producer reaches the file while Undo is paused
        // between its check and write. Wait for that path before releasing the boundary so the
        // final value proves which write won. In the fixed implementation it waits on the
        // diagnostic generation lock and reaches the file only after Undo.
        if (blocked.laterCall.await(300, TimeUnit.MILLISECONDS)) join(producer);
        blocked.release.countDown();
        join(undo);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        assertEquals("newer crash", LogBufferManager.readCrashReport(context));
    }

    /**
     * Clear and Undo read, delete and rewrite the crash files while holding the lock every logged
     * line waits on. With debug logging on each says once how long it held it, so the cost can be
     * read off a phone. With it off neither says anything.
     */
    @Test public void aClearAndItsUndoSayHowLongTheyHeldTheLockOnlyWithDebugLoggingOn() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER, "BeforeClear", "INFO", "quiet event");
        LogBufferManager.clearLogBuffer();
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        awaitWork();
        String quiet = LogBufferManager.buildExportText();
        assertTrue(quiet, quiet.contains("quiet event"));
        assertFalse(quiet, quiet.contains("held its lock for"));

        BaseSettings.DEBUG.save(true);
        try {
            LogBufferManager.clearLogBuffer();
            awaitWork();
            String cleared = LogBufferManager.buildExportText();
            assertEquals(cleared, 1, count(cleared, "Diagnostic data clear held its lock for "));
            assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
            awaitWork();
            String restored = LogBufferManager.buildExportText();
            assertTrue(restored, restored.contains("quiet event"));
            assertEquals(restored, 1, count(restored, "Diagnostic data clear held its lock for "));
            assertEquals(restored, 1, count(restored, "Diagnostic data undo (RESTORED) held its lock for "));
        } finally {
            BaseSettings.DEBUG.resetToDefault();
        }
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) found++;
        return found;
    }

    private ClearLogBufferPreference row() {
        ClearLogBufferPreference preference = new ClearLogBufferPreference(context);
        preference.setClearAndUndoSummaries("Clear summary", "Cleared summary");
        return preference;
    }

    private static void awaitWork() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void emptyEverythingAndDropUndo() {
        // The second empty clear replaces the first clear's snapshot with nothing.
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static Thread thread(
            String name,
            AtomicReference<Throwable> failure,
            ThrowingRunnable action
    ) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
    }

    private static void join(Thread thread) throws Exception {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.getName() + " did not finish", thread.isAlive());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Pauses one exact file lookup so a producer can be placed on the other side of Clear. */
    private static final class BlockingFilesContext extends ContextWrapper {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch laterCall = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final int blockAt;
        private final AtomicInteger calls = new AtomicInteger();
        volatile boolean blockedOnMainThread;

        BlockingFilesContext(Context base, int blockAt) {
            super(base);
            this.blockAt = blockAt;
        }

        @Override public File getFilesDir() {
            int call = calls.incrementAndGet();
            if (call > blockAt) laterCall.countDown();
            if (call == blockAt) {
                blockedOnMainThread = Utils.isCurrentlyOnMainThread();
                blocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("file boundary was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return super.getFilesDir();
        }
    }
}
