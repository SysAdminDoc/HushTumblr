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

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A dialog's choices as inset cards, each with its name on top and what it does under it.
 *
 * <p>A card leaves getView() with its padding and its surface already on it, so the dialog's list
 * measures every row at the height it shows. Styling Android's own rows after the dialog was shown
 * (tried on 2026-09-26) reached rows the list had already measured without it: a 72 dp row cut the
 * second choice off at the normal text size, and at twice the size the buttons covered it.
 */
final class ChoiceCards extends BaseAdapter {
    private final ScreenColors colors;
    private final CharSequence[] names;
    private final CharSequence[] details;

    ChoiceCards(ScreenColors colors, CharSequence[] names, CharSequence[] details) {
        if (names.length != details.length) {
            throw new IllegalArgumentException(names.length + " choices and " + details.length + " details");
        }
        this.colors = colors;
        this.names = names.clone();
        this.details = details.clone();
    }

    @Override
    public int getCount() {
        return names.length;
    }

    /** A choice's name, and what it does on the line under it. */
    @Override
    public CharSequence getItem(int position) {
        return new SpannableStringBuilder(names[position]).append('\n').append(details[position]);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Card card = convertView instanceof Card ? (Card) convertView : new Card(parent.getContext(), colors);
        card.name.setText(names[position]);
        card.detail.setText(details[position]);
        return card;
    }

    /** One choice on a card set in from the dialog's edges. */
    static final class Card extends LinearLayout {
        /** How far the card sits in from the list's sides, and from the next card. */
        static final int SIDE_INSET_DP = 16;
        static final int GAP_INSET_DP = 5;

        final TextView name;
        final TextView detail;

        Card(Context context, ScreenColors colors) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int side = dp(context, SIDE_INSET_DP);
            int gap = dp(context, GAP_INSET_DP);
            setBackground(colors.insetInteractive(this, colors.card, 12, SIDE_INSET_DP, GAP_INSET_DP));
            // Set after the background, which puts its insets in as the padding.
            setPaddingRelative(side + dp(context, 16), gap + dp(context, 12),
                    side + dp(context, 16), gap + dp(context, 12));
            // 48 dp of card to tap at the least, at any text size.
            setMinimumHeight(2 * gap + dp(context, 48));

            LinearLayout text = new LinearLayout(context);
            text.setOrientation(VERTICAL);
            addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setTextColor(colors.title);
            ScreenColors.Chevron chevron = new ScreenColors.Chevron(colors.summary, dp(context, 2));
            chevron.setBounds(0, 0, dp(context, 24), dp(context, 24));
            text.addView(name, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            detail = new TextView(context);
            detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            detail.setTextColor(colors.summary);
            LayoutParams under = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            under.topMargin = dp(context, 2);
            text.addView(detail, under);
            ScreenColors.startSide(text);
            android.widget.ImageView arrow = new android.widget.ImageView(context);
            arrow.setImageDrawable(chevron);
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams trailing = new LayoutParams(dp(context, 24), dp(context, 24));
            trailing.setMarginStart(dp(context, 8));
            addView(arrow, trailing);
        }

        private static int dp(Context context, int value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    context.getResources().getDisplayMetrics()));
        }
    }
}
