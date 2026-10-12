/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.rootmute;

import android.content.Context;
import android.app.AlertDialog;
import android.preference.Preference;

import app.hushtumblr.extension.shared.L10n;

/** Only the current verified account's private list can be managed here. */
@SuppressWarnings("deprecation")
public class RootPostMutePreference extends Preference {
    private AlertDialog dialog;
    public RootPostMutePreference(Context context) {
        super(context);
        setKey("hushtumblr_manage_root_mutes");
        setPersistent(false);
        setTitle(L10n.t("Manage muted posts"));
        setSummary(L10n.t("Unmute posts or clear this account's private list"));
    }
    @Override protected void onClick() { closeDialogs(); dialog = RootPostMuteUi.manage(getContext()); }
    public void closeDialogs() { if (dialog != null) { dialog.dismiss(); dialog = null; } }
}
