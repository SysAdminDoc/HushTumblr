/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mature;

import android.os.SystemClock;

import java.util.IdentityHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.rootmute.RootPostMutes;
import app.hushtumblr.extension.tumblr.rootmute.RootPostMutes.AccountSnapshot;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** Changes presentation only after explicit adult eligibility for the current authenticated account. */
public final class AllowedMatureContent {
    private AllowedMatureContent() {}

    static final long CACHE_MS = 5 * 60_000L, RETRY_MS = 60_000L, REQUEST_MS = 30_000L;
    private static final Object LOCK = new Object();
    // Identity matters: a missing JSON field must not equal an explicitly parsed false value.
    private static final IdentityHashMap<Object, Boolean> ageGates = new IdentityHashMap<>();
    private static final IdentityHashMap<Object, Boolean> permissions = new IdentityHashMap<>();
    private static HushMatureService service;
    private static Request pending;
    private static Evidence evidence;
    static LongSupplier clockForTests;
    static Consumer<Runnable> workerForTests;

    private static final class Request {
        final AccountSnapshot account;
        final HushMatureService service;
        final long started;
        Request(AccountSnapshot account, HushMatureService service, long started) {
            this.account = account; this.service = service; this.started = started;
        }
    }
    private static final class Evidence {
        final AccountSnapshot account;
        final boolean allowed;
        final long expires;
        Evidence(AccountSnapshot account, boolean allowed, long expires) {
            this.account = account; this.allowed = allowed; this.expires = expires;
        }
    }

    /** Called on both successful JSON constructor paths, preserving the native default mask. */
    public static void ageGateParsed(Object value, int defaultMask) {
        synchronized (LOCK) {
            ageGates.remove(value);
            if ((defaultMask & 1) != 0 || !(value instanceof HushAgeVerification)) return;
            try {
                if (ageGates.size() >= 128) ageGates.clear();
                ageGates.put(value, ((HushAgeVerification) value).hushBlockApp());
            } catch (Throwable unreadable) { /* An unreadable gate stays unknown. */ }
        }
    }

    /** Missing can_edit defaults to true natively, so both permission keys need explicit presence. */
    public static void permissionParsed(Object value, int defaultMask) {
        synchronized (LOCK) {
            permissions.remove(value);
            if ((defaultMask & 0x30) != 0 || !(value instanceof HushMaturePermission)) return;
            if (permissions.size() >= 128) permissions.clear();
            permissions.put(value, true);
        }
    }

    /** Captures the existing native service. It makes no request until the option needs it. */
    public static void service(Object value) {
        if (!(value instanceof HushMatureService)) return;
        synchronized (LOCK) {
            if (service != value) { service = (HushMatureService) value; pending = null; evidence = null; }
        }
    }

    /** Every unknown, restricted, stale or disabled state returns the original native cover result. */
    public static boolean cover(boolean nativeCover) {
        if (!nativeCover) return false;
        try {
            if (!enabled()) { synchronized (LOCK) { pending = null; evidence = null; } return true; }
            HookStatus.invoked(FamilyNames.REVEAL_ALLOWED_MATURE_CONTENT);
            AccountSnapshot account = RootPostMutes.accountSnapshot();
            if (!adult(account)) return true;
            Request request = null;
            long now = now();
            synchronized (LOCK) {
                if (evidence != null && same(account, evidence.account) && now < evidence.expires) {
                    return !evidence.allowed || !RootPostMutes.isCurrent(account);
                }
                evidence = null;
                if (pending != null && (!same(account, pending.account) || pending.service != service)) pending = null;
                if (pending != null && now - pending.started >= REQUEST_MS) {
                    pending = null;
                    evidence = new Evidence(account, false, now + RETRY_MS);
                } else if (pending == null && service != null && RootPostMutes.isCurrent(account)) {
                    request = pending = new Request(account, service, now);
                }
            }
            if (request != null) fetch(request);
        } catch (Throwable unreadable) { /* Native eligibility and the native cover remain intact. */ }
        return true;
    }

    private static boolean enabled() {
        return Utils.settingsReady() && PatchFamily.REVEAL_ALLOWED_MATURE_CONTENT.inBuild()
                && PatchFamily.Capability.ACCOUNT_SAFE_MATURE_REVEAL.installed()
                && Settings.REVEAL_ALLOWED_MATURE_CONTENT.get();
    }
    private static boolean adult(AccountSnapshot account) {
        if (account == null || !(account.nativeAccount instanceof HushMatureAccount) || !RootPostMutes.isCurrent(account)) return false;
        HushMatureAccount value = (HushMatureAccount) account.nativeAccount;
        if (value.hushAge() < 18 || !value.hushCanModifySafeMode()) return false;
        Object age = value.hushAgeVerification();
        synchronized (LOCK) { return Boolean.FALSE.equals(ageGates.get(age)); }
    }
    private static boolean same(AccountSnapshot a, AccountSnapshot b) {
        return a != null && b != null && a.credentialEpoch == b.credentialEpoch && a.generation == b.generation
                && a.nativeAccount == b.nativeAccount && a.accountKey.equals(b.accountKey);
    }
    private static long now() { return clockForTests == null ? SystemClock.elapsedRealtime() : clockForTests.getAsLong(); }

    private static void fetch(Request request) {
        Runnable work = () -> {
            synchronized (LOCK) {
                if (pending != request || service != request.service || !RootPostMutes.isCurrent(request.account)) return;
            }
            if (!enabled()) { received(request, null); return; }
            try { request.service.hushFetchMaturePermission(request); }
            catch (Throwable unavailable) { received(request, null); }
        };
        try {
            if (workerForTests != null) workerForTests.accept(work);
            else if (!Utils.runOnBackgroundThread(work)) received(request, null);
        } catch (Throwable unavailable) { received(request, null); }
    }

    /** Handles immediate and suspended native results. Old-account completions never publish. */
    public static void received(Object token, Object response) {
        if (!(token instanceof Request)) return;
        Request request = (Request) token;
        boolean valid = false, allowed = false;
        try {
            if (enabled() && adult(request.account) && response instanceof HushMatureResponse) {
                HushMatureResponse result = (HushMatureResponse) response;
                Object body = result.hushResponse();
                if (result.hushStatus() == 200 && body instanceof HushMaturePermission) {
                    synchronized (LOCK) { valid = Boolean.TRUE.equals(permissions.get(body)); }
                    HushMaturePermission permission = (HushMaturePermission) body;
                    allowed = valid && Boolean.TRUE.equals(permission.hushCanEditMatureContent())
                            && Boolean.TRUE.equals(permission.hushShowMatureContent());
                }
            }
        } catch (Throwable unreadable) { valid = false; allowed = false; }
        synchronized (LOCK) {
            if (pending != request) return;
            pending = null;
            if (service != request.service || !RootPostMutes.isCurrent(request.account) || !enabled()) { evidence = null; return; }
            evidence = new Evidence(request.account, allowed, now() + (valid ? CACHE_MS : RETRY_MS));
        }
    }

    static void resetForTests() {
        synchronized (LOCK) { ageGates.clear(); permissions.clear(); service = null; pending = null; evidence = null; }
        clockForTests = null; workerForTests = null;
    }
}
