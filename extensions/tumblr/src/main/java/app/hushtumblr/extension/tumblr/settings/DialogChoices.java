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

import android.content.res.ColorStateList;
import android.database.DataSetObserver;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.ListAdapter;

/** Styles framework choice and tab-order rows without replacing their selection behavior. */
final class DialogChoices extends BaseAdapter {
    private final ListAdapter source;
    private final ScreenColors colors;

    DialogChoices(ListAdapter source, ScreenColors colors) {
        this.source = source;
        this.colors = colors;
    }

    @Override public int getCount() { return source.getCount(); }
    @Override public Object getItem(int position) { return source.getItem(position); }
    @Override public long getItemId(int position) { return source.getItemId(position); }
    @Override public boolean hasStableIds() { return source.hasStableIds(); }
    @Override public boolean isEnabled(int position) { return source.isEnabled(position); }
    @Override public boolean areAllItemsEnabled() { return source.areAllItemsEnabled(); }
    @Override public int getItemViewType(int position) { return source.getItemViewType(position); }
    @Override public int getViewTypeCount() { return source.getViewTypeCount(); }
    @Override public void registerDataSetObserver(DataSetObserver observer) { source.registerDataSetObserver(observer); }
    @Override public void unregisterDataSetObserver(DataSetObserver observer) { source.unregisterDataSetObserver(observer); }

    @Override public View getView(int position, View recycled, ViewGroup parent) {
        View row = source.getView(position, recycled, parent);
        row.setEnabled(source.isEnabled(position));
        if (row instanceof android.widget.TextView && !(row instanceof CheckedTextView)) {
            android.widget.TextView text = (android.widget.TextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            text.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            text.setTextSize(17);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setMinimumHeight(Math.round(48 * density) + 2 * Math.round(5 * density));
            text.setBackground(colors.insetInteractive(text, colors.card, 12, 16, 5));
            text.setPaddingRelative(Math.round(32 * density), Math.round(17 * density),
                    Math.round(32 * density), Math.round(17 * density));
        }
        if (row instanceof CheckedTextView) {
            CheckedTextView choice = (CheckedTextView) row;
            float density = row.getResources().getDisplayMetrics().density;
            choice.setTextColor(ScreenColors.dimmedWhenDisabled(colors.title));
            choice.setTextSize(17);
            choice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            choice.setSingleLine(false);
            choice.setMaxLines(Integer.MAX_VALUE);
            choice.setMinHeight(Math.round(48 * density) + 2 * Math.round(5 * density));
            ColorStateList indicator = new ColorStateList(new int[][]{
                    {-android.R.attr.state_enabled, android.R.attr.state_checked}, {-android.R.attr.state_enabled},
                    {android.R.attr.state_checked}, {}},
                    new int[]{ScreenColors.half(colors.accent), ScreenColors.half(colors.summary), colors.accent, colors.summary});
            choice.setCheckMarkTintList(indicator);
            // Material's framework layout uses a start drawable for its radio indicator.
            for (android.graphics.drawable.Drawable drawable : choice.getCompoundDrawablesRelative()) {
                if (drawable != null) drawable.mutate().setTintList(indicator);
            }
            choice.setBackground(colors.choiceSurface(choice));
            // Leave 48dp inside the surface, plus the two 5dp gaps, at every density.
            int verticalPadding = (int) (8 * density) + Math.round(5 * density);
            choice.setPaddingRelative(Math.round(32 * density), verticalPadding,
                    Math.round(32 * density), verticalPadding);
        }
        ScreenColors.startSide(row);
        return row;
    }
}
