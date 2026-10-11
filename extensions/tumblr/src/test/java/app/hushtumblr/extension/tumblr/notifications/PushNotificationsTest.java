/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at df79f7d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.hushtumblr.extension.tumblr.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;
import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.shared.settings.SettingReadsForTests;

/** Header inputs only. These tests never ask Firebase for anything or open a connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class PushNotificationsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String TUMBLR_SHA1 = "CD177DD4057DAF3AF7ECA430825EE7DC08AD56FA";
    private static final String ORIGINAL = new String("installed-test-signer");
    private static final String CREATE = "https://firebaseinstallations.googleapis.com/v1/projects/test-project/installations";

    @Before public void setUp() {
        HookStatus.clear();
        PauseForTests.resume();
        PatchFamilyForTests.inBuild(EnumSet.of(PatchFamily.FIX_PUSH_NOTIFICATIONS));
        Settings.FIX_PUSH_NOTIFICATIONS.resetToDefault();
    }

    @After public void restore() {
        SettingReadsForTests.mend(Settings.FIX_PUSH_NOTIFICATIONS);
        Settings.FIX_PUSH_NOTIFICATIONS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    /** For PausedHooksTest: true when the hook hands Firebase Tumblr's certificate for a sign-up request. */
    public static boolean fixesACertificate() {
        try {
            return TUMBLR_SHA1.equals(PushNotifications.certificateHeader(connection(CREATE), ORIGINAL));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test public void theCertificateIsTumblrsOwn() {
        assertEquals(TUMBLR_SHA1, PushNotifications.TUMBLR_CERTIFICATE_SHA1);
        assertEquals(TUMBLR_SHA1.toUpperCase(Locale.ROOT), PushNotifications.TUMBLR_CERTIFICATE_SHA1);
    }

    @Test public void itStartsOnAndChangesOnlyTheCertificateValueWithoutConnecting() throws Exception {
        ProbeConnection connection = connection(CREATE);
        connection.setRequestProperty("X-Android-Cert", ORIGINAL);
        connection.setRequestProperty("x-goog-api-key", "test-api-key");
        connection.setRequestProperty("Authorization", "test-auth");
        Map<String, List<String>> before = new LinkedHashMap<>(connection.getRequestProperties());
        assertTrue(Settings.FIX_PUSH_NOTIFICATIONS.savedValue());
        assertEquals(TUMBLR_SHA1, PushNotifications.certificateHeader(connection, ORIGINAL));
        assertEquals(before, connection.getRequestProperties());
        assertEquals(0, connection.connects);
        assertEquals(Arrays.asList("Fix push notifications: invoked 1, 0 found, 0 missing. "
                + "Counted: certificate headers fixed 1"), HookStatus.report());
    }

    @Test public void signUpAndTokenRequestsAreFixedOnTheirOfficialEndpoint() throws Exception {
        for (String url : Arrays.asList(CREATE, CREATE + "/test-fid/authTokens:generate", CREATE + "/test-fid",
                CREATE.replace(".com/", ".com:443/"),
                CREATE.replace("firebaseinstallations.googleapis.com", "FIREBASEINSTALLATIONS.GOOGLEAPIS.COM"))) {
            ProbeConnection connection = connection(url);
            assertEquals(url, TUMBLR_SHA1, PushNotifications.certificateHeader(connection, ORIGINAL));
            assertEquals(0, connection.connects);
        }
        assertTrue(HookStatus.report().get(0).contains("certificate headers fixed 5"));
    }

    @Test public void aHeaderThatAlreadyNamesTumblrKeepsItsObjectAndIsNotCounted() throws Exception {
        String original = new String(TUMBLR_SHA1.toLowerCase(Locale.ROOT));
        assertSame(original, PushNotifications.certificateHeader(connection(CREATE), original));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
    }

    @Test public void aMissingFingerprintIsFixedToo() throws Exception {
        // Firebase sends no value when it couldn't read the installed certificate.
        assertEquals(TUMBLR_SHA1, PushNotifications.certificateHeader(connection(CREATE), null));
    }

    @Test public void offEveryPauseReasonAndABuildWithoutThePatchKeepTumblrsOwnValue() throws Exception {
        ProbeConnection connection = connection(CREATE);
        Settings.FIX_PUSH_NOTIFICATIONS.save(false);
        assertSame(ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
        Settings.FIX_PUSH_NOTIFICATIONS.save(true);
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertSame(reason.name(), ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
            PauseForTests.resume();
        }
        PatchFamilyForTests.inBuild(EnumSet.noneOf(PatchFamily.class));
        assertSame(ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
        assertTrue(Settings.FIX_PUSH_NOTIFICATIONS.savedValue());
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
        assertEquals(0, connection.connects);
    }

    @Test public void withoutSettingsOnTheMainThreadItAnswersAtOnceWithTumblrsOwnValue() throws Exception {
        ProbeConnection connection = connection(CREATE);
        long started = System.nanoTime();
        SettingsContextRule.withoutContext(() -> {
            assertSame(ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
            assertNull(PushNotifications.certificateHeader(connection, null));
        });
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(ORIGINAL,
                PushNotifications.certificateHeader(connection, ORIGINAL)));
        // setContext runs on the main thread, so a wait there could only time out.
        assertTrue(System.nanoTime() - started < 2_000_000_000L);
        assertEquals(Arrays.asList("Fix push notifications: invoked 3, 0 found, 0 missing. "
                + "Counted: requests before app start 3"), HookStatus.report());
    }

    @Test public void firebasesStartupRequestWaitsForSettingsOnItsWorkerAndIsFixed() throws Exception {
        // Firebase's init provider starts its token sync before Application.onCreate, so the first
        // Installations request can reach the hook before settings are ready.
        ProbeConnection connection = connection(CREATE);
        String[] answer = new String[1];
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            Thread worker = new Thread(() -> answer[0] = PushNotifications.certificateHeader(connection, ORIGINAL));
            worker.start();
            awaitWaiting(worker);
            SettingsContextRule.finishSetContext();
            join(worker);
        });
        assertEquals(TUMBLR_SHA1, answer[0]);
        assertEquals(0, connection.connects);
        assertEquals(Arrays.asList("Fix push notifications: invoked 1, 0 found, 0 missing. "
                + "Counted: requests held for app start 1, certificate headers fixed 1"), HookStatus.report());
    }

    @Test public void aStartupRequestStillKeepsTheSwitchAndPauseDecidedAfterItsWait() throws Exception {
        Settings.FIX_PUSH_NOTIFICATIONS.save(false);
        String[] answer = new String[1];
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            Thread worker = new Thread(() -> answer[0] = PushNotifications.certificateHeader(connectionOrFail(), ORIGINAL));
            worker.start();
            awaitWaiting(worker);
            SettingsContextRule.finishSetContext();
            join(worker);
        });
        assertSame(ORIGINAL, answer[0]);
        Settings.FIX_PUSH_NOTIFICATIONS.save(true);
        PauseForTests.pause(HushTumblrPause.Reason.SWITCH);
        SettingsContextRule.beforeThePauseIsDecided(() -> {
            Thread worker = new Thread(() -> answer[0] = PushNotifications.certificateHeader(connectionOrFail(), ORIGINAL));
            worker.start();
            awaitWaiting(worker);
            SettingsContextRule.finishSetContext();
            join(worker);
        });
        assertSame(ORIGINAL, answer[0]);
        assertFalse(HookStatus.report().get(0).contains("certificate headers fixed"));
    }

    @Test public void aStartupRequestThatNeverSeesSettingsGivesUpAndKeepsTumblrsOwnValue() throws Exception {
        long saved = PushNotifications.startupWaitMillis;
        PushNotifications.startupWaitMillis = 50L;
        String[] answer = new String[1];
        try {
            SettingsContextRule.beforeThePauseIsDecided(() -> {
                Thread worker = new Thread(() -> answer[0] = PushNotifications.certificateHeader(connectionOrFail(), ORIGINAL));
                worker.start();
                join(worker);
            });
        } finally {
            PushNotifications.startupWaitMillis = saved;
        }
        assertSame(ORIGINAL, answer[0]);
        assertEquals(Arrays.asList("Fix push notifications: invoked 1, 0 found, 0 missing. "
                + "Counted: requests before app start 1"), HookStatus.report());
    }

    @Test public void anotherPackageOrNoPackageHeaderKeepsTheValue() throws Exception {
        for (String packageName : Arrays.asList("com.tumblr.twa", "com.tumblr.other", "other.client", "COM.TUMBLR", "")) {
            ProbeConnection connection = connection(CREATE);
            connection.setRequestProperty("X-Android-Package", packageName);
            assertSame(packageName, ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
        }
        ProbeConnection missing = new ProbeConnection(new URL(CREATE));
        assertSame(ORIGINAL, PushNotifications.certificateHeader(missing, ORIGINAL));
        assertSame(ORIGINAL, PushNotifications.certificateHeader(null, ORIGINAL));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
    }

    @Test public void otherOrChangedAddressesKeepTheValue() throws Exception {
        for (String url : Arrays.asList(CREATE.replace("https:", "http:"),
                CREATE.replace("googleapis.com", "googleapis.com.other"),
                CREATE.replace("firebaseinstallations", "fcm"),
                CREATE.replace(".com/", ".com:8443/"),
                CREATE.replace("https://", "https://user@"), CREATE + "?key=test", CREATE + "#fragment",
                CREATE + "/", CREATE + "/test-fid/unknown", CREATE.replace("/v1/", "/v2/"),
                CREATE.replace("test-project", ".."), CREATE + "/%2F/authTokens:generate")) {
            assertSame(url, ORIGINAL, PushNotifications.certificateHeader(connection(url), ORIGINAL));
        }
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
    }

    @Test public void aBrokenSwitchOrConnectionKeepsTheValueAndShowsTheFailedHook() throws Exception {
        ProbeConnection connection = connection(CREATE);
        SettingReadsForTests.breakReads(Settings.FIX_PUSH_NOTIFICATIONS);
        assertSame(ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
        assertEquals(Arrays.asList("a working 'certificate header' hook (it threw java.lang.NullPointerException)"),
                HookStatus.missing(FamilyNames.FIX_PUSH_NOTIFICATIONS));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
        HookStatus.clear();
        SettingReadsForTests.mend(Settings.FIX_PUSH_NOTIFICATIONS);
        connection.broken = true;
        assertSame(ORIGINAL, PushNotifications.certificateHeader(connection, ORIGINAL));
        assertEquals(Arrays.asList("a working 'certificate header' hook (it threw java.lang.IllegalStateException)"),
                HookStatus.missing(FamilyNames.FIX_PUSH_NOTIFICATIONS));
        assertFalse(HookStatus.report().get(0).contains("Counted:"));
        assertEquals(0, connection.connects);
    }

    private static void awaitWaiting(Thread worker) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (worker.getState() != Thread.State.TIMED_WAITING) {
            if (!worker.isAlive() || System.nanoTime() > deadline) {
                throw new AssertionError("the startup request answered before settings were ready");
            }
            Thread.yield();
        }
    }

    private static void join(Thread worker) {
        try {
            worker.join(5_000L);
        } catch (InterruptedException interrupted) {
            throw new AssertionError(interrupted);
        }
        assertFalse("the startup request never finished", worker.isAlive());
    }

    private static ProbeConnection connectionOrFail() {
        try {
            return connection(CREATE);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static ProbeConnection connection(String url) throws Exception {
        ProbeConnection connection = new ProbeConnection(new URL(url));
        connection.setRequestProperty("X-Android-Package", "com.tumblr");
        return connection;
    }

    private static final class ProbeConnection extends URLConnection {
        int connects;
        boolean broken;
        ProbeConnection(URL url) { super(url); }
        @Override public void connect() {
            connects++;
            throw new AssertionError("the certificate fix tried to connect");
        }
        @Override public String getRequestProperty(String key) {
            if (broken) throw new IllegalStateException("unreadable test headers");
            return super.getRequestProperty(key);
        }
    }
}
