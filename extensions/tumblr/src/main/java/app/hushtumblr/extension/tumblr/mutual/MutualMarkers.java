/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.mutual;

import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.rootmute.RootPostMutes;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** One bounded followers snapshot for the current primary blog, shared by every post header. */
public final class MutualMarkers {
    private MutualMarkers() {}
    static final int MAX_PAGES = 20, MAX_ENTRIES = 2_000;
    static final long TTL = 5 * 60_000L, TIMEOUT = 30_000L, RETRY = 60_000L;
    private static final Object LOCK = new Object();
    private static final Map<TextView, Binding> BINDINGS = new WeakHashMap<>();
    private static boolean observing, ticking;
    private static final Runnable ACCOUNT_CHANGED = MutualMarkers::accountChanged;
    private static final Runnable TICK = () -> { ticking = false; refreshBindings(); };
    private static HushMutualSource source = new NativeMutualSource();
    private static Cache cache;
    private static Request pending;
    private static Scope failedScope;
    private static long retryAt;
    static LongSupplier clockForTests;
    static Consumer<Runnable> workerForTests;

    private static final class Scope {
        final RootPostMutes.AccountSnapshot account;
        final String primaryUuid, primaryHost;
        Scope(RootPostMutes.AccountSnapshot account, String uuid, String host) {
            this.account = account; primaryUuid = uuid; primaryHost = host;
        }
        boolean current() { return RootPostMutes.isCurrent(account); }
        boolean same(Scope other) { return other != null && account.generation == other.account.generation
                && account.credentialEpoch == other.account.credentialEpoch && account.nativeAccount == other.account.nativeAccount
                && primaryUuid.equals(other.primaryUuid) && primaryHost.equals(other.primaryHost); }
    }
    private static final class Request {
        final Scope scope;
        final HushMutualSource source;
        final long started;
        volatile Object call;
        Request(Scope scope, HushMutualSource source) { this.scope = scope; this.source = source; started = now(); }
    }
    private static final class Cache {
        final Scope scope;
        final Set<String> mutuals;
        final long expires;
        Cache(Scope scope, Set<String> mutuals) {
            this.scope = scope; this.mutuals = Collections.unmodifiableSet(new HashSet<>(mutuals)); expires = now() + TTL;
        }
    }
    private static final class Marker {}
    private static final class Binding {
        final Scope scope;
        final String uuid, nativeText;
        final Marker marker = new Marker();
        CharSequence nativeDescription, appliedDescription;
        Binding(Scope scope, String uuid, CharSequence text) { this.scope = scope; this.uuid = uuid; nativeText = text.toString(); }
    }
    private static long now() { return clockForTests == null ? android.os.SystemClock.elapsedRealtime() : clockForTests.getAsLong(); }
    private static boolean enabled() {
        return Utils.settingsReady() && PatchFamily.SHOW_MUTUAL_MARKERS.inBuild()
                && PatchFamily.Capability.PRIMARY_BLOG_MUTUALS.installed() && Settings.SHOW_MUTUAL_MARKERS.get();
    }

    /** The native binder supplies the exact blog used for this displayed name. */
    public static void bind(Object nameView, Object nativeBlog, String displayedName) {
        try {
            if (!(nameView instanceof TextView)) return;
            TextView view = (TextView) nameView;
            clear(view);
            if (!enabled() || !(nativeBlog instanceof HushMutualBlog)) return;
            HushMutualBlog blog = (HushMutualBlog) nativeBlog;
            String uuid = uuid(blog.hushMutualUuid());
            if (uuid == null || displayedName == null || !displayedName.equals(blog.hushMutualName()) || TextUtils.isEmpty(view.getText())) return;
            Scope scope = scope();
            if (scope == null || uuid.equals(scope.primaryUuid)) return;
            if (!observing) { RootPostMutes.addAccountObserver(ACCOUNT_CHANGED); observing = true; }
            HookStatus.invoked(FamilyNames.SHOW_MUTUAL_MARKERS);
            BINDINGS.put(view, new Binding(scope, uuid, view.getText()));
            ensureCache(scope);
            refreshBindings();
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_MUTUAL_MARKERS, "mutual header", failure);
        }
    }

    /** Unconditional full-bind and detach cleanup also handles disabled settings and recycled cards. */
    public static void clear(Object header) {
        try {
            Object object = header instanceof HushMutualHeader ? ((HushMutualHeader) header).hushMutualNameView() : header;
            if (!(object instanceof TextView)) return;
            TextView view = (TextView) object;
            Binding binding = BINDINGS.remove(view);
            if (binding != null) removeMarker(view, binding);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.SHOW_MUTUAL_MARKERS, "mutual cleanup", failure);
        }
    }

    private static Scope scope() {
        RootPostMutes.AccountSnapshot account = RootPostMutes.accountSnapshot();
        if (account == null || !(account.nativeAccount instanceof HushMutualAccount)) return null;
        List<?> blogs = ((HushMutualAccount) account.nativeAccount).hushMutualBlogs();
        if (blogs == null || blogs.size() > 100) return null;
        Scope found = null;
        for (Object value : blogs) {
            if (!(value instanceof HushPrimaryBlog)) return null;
            HushPrimaryBlog blog = (HushPrimaryBlog) value;
            if (!blog.hushMutualPrimary()) continue;
            if (found != null) return null;
            String uuid = uuid(blog.hushMutualUuid()), name = blog.hushMutualName();
            if (uuid == null || name == null || !name.matches("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?")) return null;
            found = new Scope(account, uuid, name + ".tumblr.com");
        }
        return found != null && found.current() ? found : null;
    }
    private static String uuid(String value) {
        return value != null && value.length() <= 256 && value.matches("[A-Za-z0-9:_-]+") ? value : null;
    }

    private static void ensureCache(Scope scope) {
        Request request;
        synchronized (LOCK) {
            long now = now();
            if (cache != null && cache.scope.same(scope) && now < cache.expires) return;
            if (pending != null) {
                if (pending.scope.same(scope) && now - pending.started < TIMEOUT) return;
                boolean timedOut = pending.scope.same(scope);
                cancel(pending); pending = null;
                if (timedOut) { failedScope = scope; retryAt = now + RETRY; return; }
            }
            if (scope.same(failedScope) && now < retryAt) return;
            pending = request = new Request(scope, source);
        }
        Runnable task = () -> read(request);
        if (workerForTests != null) workerForTests.accept(task);
        else if (!Utils.runOnBackgroundThread(task)) failed(request);
    }
    private static boolean active(Request request) {
        synchronized (LOCK) { return pending == request && request.source == source && request.scope.current()
                && now() - request.started < TIMEOUT && enabled(); }
    }
    private static void read(Request request) {
        try {
            Set<String> mutuals = new HashSet<>(), links = new HashSet<>();
            int entries = 0;
            String next = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                if (!active(request)) { failed(request); return; }
                request.call = page == 0 ? request.source.first(request.scope.primaryHost) : request.source.next(next);
                if (request.call == null || !active(request)) { cancel(request); failed(request); return; }
                long remaining = TIMEOUT - (now() - request.started);
                Object raw = request.source.execute(request.call, Math.min(15_000L, remaining));
                request.call = null;
                if (!active(request) || !(raw instanceof HushMutualResponse)) { failed(request); return; }
                HushMutualResponse response = (HushMutualResponse) raw;
                Object body = response.hushMutualResponse();
                if (response.hushMutualStatus() != 200 || !(body instanceof HushMutualPage)) { failed(request); return; }
                HushMutualPage result = (HushMutualPage) body;
                List<?> users = result.hushMutualUsers();
                if (users == null || users.size() > 100 || entries + users.size() > MAX_ENTRIES) { failed(request); return; }
                entries += users.size();
                for (Object value : users) if (value instanceof HushMutualFollower) {
                    HushMutualFollower user = (HushMutualFollower) value;
                    if (!user.hushMutualFollowing()) continue;
                    Object blog = user.hushMutualFollowerBlog();
                    if (blog instanceof HushMutualBlog) {
                        String uuid = uuid(((HushMutualBlog) blog).hushMutualUuid());
                        if (uuid != null && !uuid.equals(request.scope.primaryUuid)) mutuals.add(uuid);
                    }
                }
                Object pagination = result.hushMutualPagination();
                if (pagination == null) pagination = result.hushMutualLinks();
                if (pagination != null && !(pagination instanceof HushMutualPagination)) { failed(request); return; }
                Object link = pagination == null ? null : ((HushMutualPagination) pagination).hushMutualNext();
                if (link != null && !(link instanceof HushMutualLink)) { failed(request); return; }
                next = link == null ? null : ((HushMutualLink) link).hushMutualLink();
                if (link != null && TextUtils.isEmpty(next)) { failed(request); return; }
                if (next == null) {
                    synchronized (LOCK) {
                        if (!active(request)) { failed(request); return; }
                        cache = new Cache(request.scope, mutuals); pending = null; failedScope = null;
                    }
                    Utils.runOnMainThread(MutualMarkers::refreshBindings); return;
                }
                if (!safeNext(next, request.scope.primaryHost) || !links.add(next)) { failed(request); return; }
            }
            failed(request);
        } catch (Throwable failure) {
            failed(request);
            HookStatus.threw(FamilyNames.SHOW_MUTUAL_MARKERS, "mutual cache read", failure);
        }
    }

    static boolean safeNext(String link, String primaryHost) {
        try {
            if (link == null || link.length() > 4_096) return false;
            URI uri = new URI(link);
            if (uri.getFragment() != null || uri.getUserInfo() != null) return false;
            if (uri.isAbsolute()) {
                if (!"https".equals(uri.getScheme()) || !"api.tumblr.com".equals(uri.getHost()) || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            } else if (uri.getRawAuthority() != null) return false;
            String path = uri.getPath();
            return ("/v2/blog/" + primaryHost + "/followers").equals(path) || ("blog/" + primaryHost + "/followers").equals(path);
        } catch (Exception invalid) { return false; }
    }
    private static void cancel(Request request) {
        Object call = request.call;
        if (call != null) try { request.source.cancel(call); }
        catch (Throwable failure) { HookStatus.threw(FamilyNames.SHOW_MUTUAL_MARKERS, "mutual read cancellation", failure); }
    }
    private static void failed(Request request) {
        synchronized (LOCK) {
            if (pending != request) return;
            pending = null; cache = null; failedScope = request.scope; retryAt = now() + RETRY;
        }
        Utils.runOnMainThread(MutualMarkers::refreshBindings);
    }
    private static void accountChanged() {
        synchronized (LOCK) { if (pending != null) cancel(pending); pending = null; cache = null; failedScope = null; retryAt = 0; }
        Utils.runOnMainThread(MutualMarkers::refreshBindings);
    }
    private static void refreshBindings() {
        boolean enabled = enabled();
        Cache snapshot;
        synchronized (LOCK) {
            snapshot = cache;
            if (!enabled) { if (pending != null) cancel(pending); pending = null; cache = null; snapshot = null; }
        }
        for (Map.Entry<TextView, Binding> entry : new ArrayList<>(BINDINGS.entrySet())) {
            TextView view = entry.getKey(); Binding binding = entry.getValue();
            if (view == null) continue;
            if (!enabled || !binding.scope.current()) { clear(view); continue; }
            boolean mutual = snapshot != null && snapshot.scope.same(binding.scope) && now() < snapshot.expires && snapshot.mutuals.contains(binding.uuid);
            if (!mutual) { removeMarker(view, binding); continue; }
            if (hasMarker(view, binding)) continue;
            if (!binding.nativeText.contentEquals(view.getText())) { BINDINGS.remove(view); continue; }
            SpannableStringBuilder text = new SpannableStringBuilder(view.getText());
            int start = text.length(); text.append(" · ").append(L10n.t("Mutual"));
            text.setSpan(binding.marker, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(text);
            binding.nativeDescription = view.getContentDescription();
            if (binding.nativeDescription != null) {
                binding.appliedDescription = TextUtils.concat(binding.nativeDescription, ", ", L10n.t("Mutual"));
                view.setContentDescription(binding.appliedDescription);
            }
        }
        if (!BINDINGS.isEmpty() && !ticking) { ticking = true; new Handler(Looper.getMainLooper()).postDelayed(TICK, 1_000); }
    }
    private static boolean hasMarker(TextView view, Binding binding) {
        return view.getText() instanceof Spanned && ((Spanned) view.getText()).getSpanStart(binding.marker) >= 0;
    }
    private static void removeMarker(TextView view, Binding binding) {
        CharSequence current = view.getText();
        if (current instanceof Spanned) {
            Spanned spanned = (Spanned) current;
            int start = spanned.getSpanStart(binding.marker), end = spanned.getSpanEnd(binding.marker);
            if (start >= 0 && end >= start) { SpannableStringBuilder text = new SpannableStringBuilder(current); text.delete(start, end); text.removeSpan(binding.marker); view.setText(text); }
        }
        if (binding.appliedDescription != null && TextUtils.equals(view.getContentDescription(), binding.appliedDescription)) view.setContentDescription(binding.nativeDescription);
        binding.nativeDescription = null; binding.appliedDescription = null;
    }

    static void resetForTests() {
        for (TextView view : new ArrayList<>(BINDINGS.keySet())) clear(view);
        new Handler(Looper.getMainLooper()).removeCallbacks(TICK);
        synchronized (LOCK) { if (pending != null) cancel(pending); pending = null; cache = null; failedScope = null; retryAt = 0; }
        source = new NativeMutualSource(); clockForTests = null; workerForTests = null; observing = false; ticking = false;
    }
    static void sourceForTests(HushMutualSource value) { source = value; }
}
