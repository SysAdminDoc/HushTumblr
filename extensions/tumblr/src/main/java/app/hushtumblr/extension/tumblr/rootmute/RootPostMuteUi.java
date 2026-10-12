/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.shared.Utils;

/** Private management and an immediate, nonmodal Undo. No post IDs leave this local screen. */
final class RootPostMuteUi {
    private RootPostMuteUi() {}
    private static WeakReference<View> notice = new WeakReference<>(null);

    static Activity activity(Context context) {
        for (int i = 0; i < 16 && context != null; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    static void changed(Context context, String message, RootPostMutes.Undo undo) {
        changed(context, message, undo, null);
    }

    static void changed(Context context, String message, RootPostMutes.Undo undo, Runnable afterRestore) {
        if (undo == null) { Utils.showToastShort(L10n.t("Couldn't update muted posts")); return; }
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !RootPostMutes.current(undo.account)) return;
        View previous = notice.get();
        if (previous != null && previous.getParent() instanceof ViewGroup) ((ViewGroup) previous.getParent()).removeView(previous);
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int padding = dp(context, 12);
        bar.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff101722);
        background.setCornerRadius(dp(context, 12));
        bar.setBackground(background);
        bar.setElevation(dp(context, 8));
        TextView label = new TextView(context);
        label.setText(message);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        bar.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button restore = new Button(context);
        restore.setText(L10n.t("Undo"));
        restore.setTextColor(0xff92bfff);
        restore.setOnClickListener(view -> {
            if (bar.getParent() != root || activity.isFinishing() || activity.isDestroyed()
                    || !RootPostMutes.current(undo.account)) { root.removeView(bar); return; }
            restore.setEnabled(false);
            RootPostMutes.undo(undo, restored -> {
                root.removeView(bar);
                if (RootPostMutes.current(undo.account)) {
                    if (restored && afterRestore != null) afterRestore.run();
                    Utils.showToastShort(L10n.t(restored ? "Muted posts restored" : "Couldn't restore muted posts"));
                }
            });
        });
        bar.addView(restore);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        position.setMargins(padding, padding, padding, padding * 2);
        root.addView(bar, position);
        notice = new WeakReference<>(bar);
        bar.announceForAccessibility(label.getText());
        Utils.runOnMainThreadDelayed(() -> root.removeView(bar), 10000);
    }

    static AlertDialog manage(Context context) {
        RootPostMutes.Session account = RootPostMutes.capture();
        Activity owner = activity(context);
        if (account == null || owner == null || owner.isFinishing() || owner.isDestroyed()) {
            Utils.showToastShort(L10n.t("Muted posts aren't available for this account yet")); return null;
        }
        Context themed = new ContextWrapperTheme(context);
        List<String> roots = new ArrayList<>(account.roots);
        Collections.reverse(roots);
        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(context, 16);
        content.setPadding(padding, padding, padding, padding);
        TextView help = new TextView(themed);
        help.setTextColor(Color.WHITE);
        help.setText(L10n.t("Stored only on this device for this Tumblr account. Tap a post to unmute it. Up to 500 posts are kept per account."));
        content.addView(help);
        ListView list = new ListView(themed);
        List<String> labels = new ArrayList<>();
        for (String root : roots) labels.add(L10n.t("Post") + " " + root);
        list.setAdapter(new ArrayAdapter<>(themed, android.R.layout.simple_list_item_1, labels));
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 260)));
        TextView empty = new TextView(themed);
        empty.setTextColor(0xffaab4c4);
        empty.setText(L10n.t("No muted posts"));
        content.addView(empty);
        list.setEmptyView(empty);
        AlertDialog dialog = new AlertDialog.Builder(themed).setTitle(L10n.t("Manage muted posts"))
                .setView(content).setNegativeButton(L10n.t("Done"), null).setNeutralButton(L10n.t("Clear muted posts"), null).create();
        AtomicBoolean alive = new AtomicBoolean(true);
        dialog.setOnDismissListener(dismissed -> alive.set(false));
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (!alive.get() || !dialog.isShowing() || owner.isFinishing() || owner.isDestroyed()
                    || !RootPostMutes.current(account)) return;
            list.setEnabled(false);
            RootPostMutes.remove(account, roots.get(position), undo -> {
                if (alive.get() && dialog.isShowing() && !owner.isFinishing() && !owner.isDestroyed()
                        && RootPostMutes.current(account)) { dialog.dismiss(); changed(context, L10n.t("Post unmuted"), undo); }
            });
        });
        dialog.setOnShowListener(shown -> {
            Button clear = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            clear.setTextColor(0xff92bfff);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(0xff92bfff);
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(0xff101722); panel.setCornerRadius(dp(context, 12));
            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(panel);
            clear.setEnabled(!roots.isEmpty());
            clear.setOnClickListener(view -> {
                if (!alive.get() || !dialog.isShowing() || owner.isFinishing() || owner.isDestroyed()
                        || !RootPostMutes.current(account)) return;
                clear.setEnabled(false);
                RootPostMutes.clear(account, undo -> {
                    if (alive.get() && dialog.isShowing() && !owner.isFinishing() && !owner.isDestroyed()
                            && RootPostMutes.current(account)) { dialog.dismiss(); changed(context, L10n.t("Muted posts cleared"), undo); }
                });
            });
        });
        dialog.show();
        return dialog;
    }

    private static final class ContextWrapperTheme extends android.view.ContextThemeWrapper {
        ContextWrapperTheme(Context context) { super(context, android.R.style.Theme_Material_Dialog_Alert); }
    }

    private static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
