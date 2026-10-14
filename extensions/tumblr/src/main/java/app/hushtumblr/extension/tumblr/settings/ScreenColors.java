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

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.TwoStatePreference;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.hushtumblr.extension.shared.settings.preference.ImmediateAction;

/**
 * The colours of the HushTumblr screen, its rows and its dialogs: a black page with dark Material
 * rows, whatever the host activity's theme is. Every pair meets WCAG 2.2 AA, which ScreenColorsTest
 * works out.
 */
public final class ScreenColors {
    /** The page, behind the title bar and every row. */
    public final int background;
    /** A dialog's surface. */
    public final int dialog;
    /** Row and dialog titles, the title bar and its back arrow. */
    public final int title;
    /** Row summaries and a dialog's message. */
    public final int summary;
    /** Section titles. */
    public final int heading;
    /** A switch that's on, and the buttons of a dialog. */
    public final int accent;
    /** A switch that's off. */
    public final int switchOff;
    /** Grouped preference rows and their edge. */
    public final int card;
    public final int outline;
    /** Text on the filled primary dialog action. */
    public final int onAccent;
    /** The small tile behind a navigation icon. */
    final int iconSurface;

    /** The one palette the screen is drawn in. */
    public static final ScreenColors DEFAULT = new ScreenColors();

    /** The framework theme rows and dialogs are built with, so what this class doesn't paint still reads. */
    static final int THEME = android.R.style.Theme_Material_NoActionBar;

    private ScreenColors() {
        background = Color.BLACK;
        dialog = 0xFF101722;
        title = 0xFFF4F7FB;
        summary = 0xFF9EAEC2;
        heading = 0xFF92BFFF;
        accent = 0xFF92BFFF;
        switchOff = 0xFF788698;
        card = 0xFF101722;
        outline = 0xFF243044;
        onAccent = 0xFF0B192B;
        iconSurface = 0xFF172335;
    }

    /**
     * A light thumb distinguishes an off switch from the dark thumb on its blue on track.
     */
    int offThumb() {
        return title;
    }

    /**
     * [color], at Material's 38% on a disabled row. A plain colour replaced the theme's own
     * disabled state, so a row that ignored taps, such as Import while an export runs, looked
     * the same as one that would take them.
     */
    static ColorStateList dimmedWhenDisabled(int color) {
        int dimmed = (color & 0x00FFFFFF) | 0x61000000;
        return new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{dimmed, color});
    }

    /**
     * The text of a dialog's secondary actions, such as Cancel.
     */
    int secondaryActionText() {
        return heading;
    }

    /** A row's title and summary, its switch if it has one, and its chevron if a tap opens something. */
    void paintRow(View row, Preference preference) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(this.title));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            if (title.getParent() instanceof View) {
                View text = (View) title.getParent();
                text.setPadding(0, 0, 0, 0);
                text.setMinimumHeight(0);
            }
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(dimmedWhenDisabled(this.summary));
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            summary.setLineSpacing(dp(row, 2), 1);
            summary.setPadding(0, dp(row, 4), 0, 0);
        }
        View widget = row.findViewById(android.R.id.switch_widget);
        if (widget instanceof Switch) {
            int[][] states = {{-android.R.attr.state_enabled, android.R.attr.state_checked},
                    {-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            Switch toggle = (Switch) widget;
            GradientDrawable thumb = new GradientDrawable();
            thumb.setCornerRadius(dp(row, 4));
            thumb.setSize(dp(row, 16), dp(row, 16));
            thumb.setColor(Color.WHITE);
            int inset = dp(row, 4);
            int thumbBox = dp(row, 24);
            GradientDrawable track = new GradientDrawable() {
                @Override public boolean getPadding(Rect padding) {
                    padding.set(inset, 0, inset, 0);
                    return true;
                }
            };
            track.setCornerRadius(dp(row, 8));
            track.setSize(dp(row, 44), dp(row, 24));
            track.setColor(Color.WHITE);
            // Switch subtracts an InsetDrawable's optical insets from its track. Keep those
            // zero and inset the painted thumb ourselves, retaining its 24dp layout box.
            toggle.setThumbDrawable(new InsetDrawable(thumb, 0) {
                @Override public int getIntrinsicWidth() { return thumbBox; }
                @Override public int getIntrinsicHeight() { return thumbBox; }
                @Override public boolean getPadding(Rect padding) {
                    padding.set(inset, inset, inset, inset);
                    return true;
                }
                @Override protected void onBoundsChange(Rect bounds) {
                    getDrawable().setBounds(bounds.left + inset, bounds.top + inset,
                            bounds.right - inset, bounds.bottom - inset);
                }
            });
            toggle.setTrackDrawable(track);
            toggle.setSwitchMinWidth(dp(row, 44));
            toggle.setMinWidth(dp(row, 48));
            toggle.setMinHeight(dp(row, 48));
            toggle.setThumbTintList(new ColorStateList(states, new int[]{half(onAccent), half(offThumb()), onAccent, offThumb()}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{half(accent), half(switchOff), accent, switchOff}));
        }
        paintChevron(row, preference);
        ImageView icon = row.findViewById(android.R.id.icon);
        if (icon != null && preference.getIcon() != null) {
            boolean toggle = preference instanceof TwoStatePreference;
            icon.setVisibility(toggle ? View.GONE : View.VISIBLE);
            ViewGroup.LayoutParams imageSize = icon.getLayoutParams();
            imageSize.width = dp(row, 42);
            imageSize.height = dp(row, 42);
            icon.setLayoutParams(imageSize);
            // Override Samsung's nested large-text icon frame as well as the standard layout.
            icon.setPadding(dp(row, 9), dp(row, 9), dp(row, 9), dp(row, 9));
            icon.setBackground(surface(iconSurface, 8, row));
            if (icon.getParent() instanceof ViewGroup) {
                ViewGroup frame = (ViewGroup) icon.getParent();
                frame.setVisibility(toggle ? View.GONE : View.VISIBLE);
                frame.setPaddingRelative(0, 0, dp(row, 14), 0);
                ViewGroup.LayoutParams size = frame.getLayoutParams();
                size.width = dp(row, 56);
                frame.setLayoutParams(size);
                // Both layout variants start the text at the same edge.
                View line = frame;
                while (line.getParent() instanceof View && line.getParent() != row) line = (View) line.getParent();
                if (line.getParent() == row) {
                    startMargin(frame, 0);
                    startMargin(line, 0);
                }
            }
        }
        PreferenceGroup parent = preference.getParent();
        boolean grouped = parent instanceof PreferenceCategory;
        boolean first = !grouped || parent.getPreference(0) == preference;
        boolean last = !grouped || parent.getPreference(parent.getPreferenceCount() - 1) == preference;
        paintSurface(row, preference, first, last);
    }

    /** Group boundaries follow the visible rows, including a filtered search result. */
    void paintSurface(View row, Preference preference, boolean first, boolean last) {
        float radius = dp(row, 12);
        float[] corners = new float[]{
                first ? radius : 0, first ? radius : 0,
                first ? radius : 0, first ? radius : 0,
                last ? radius : 0, last ? radius : 0,
                last ? radius : 0, last ? radius : 0};
        Drawable surface = preference.isSelectable() ? states(row, card, corners, outline, accent)
                : shape(row, card, corners, outline, 1);
        Drawable inset = new InsetDrawable(surface, dp(row, 20), first ? dp(row, 5) : -dp(row, 1),
                dp(row, 20), last ? dp(row, 5) : 0);
        row.setBackground(preference.isSelectable()
                ? new RippleDrawable(ColorStateList.valueOf(half(accent)), inset, null) : inset);
        // Explicit padding after the inset background prevents its padding from moving the text.
        int vertical = 12;
        row.setPaddingRelative(dp(row, 36), dp(row, vertical + (first ? 5 : 0)),
                dp(row, 36), dp(row, vertical + (last ? 5 : 0)));
        row.setMinimumHeight(dp(row, 74));
    }

    /** An outlined surface shared by navigation icons and the back button. */
    GradientDrawable surface(int fill, int radius, View view) {
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(fill);
        surface.setCornerRadius(dp(view, radius));
        surface.setStroke(dp(view, 1), outline);
        return surface;
    }

    /** Ripple, focus and disabled feedback share the same shape in every settings control. */
    public RippleDrawable interactive(View view, int fill, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(half(accent)),
                states(view, fill, corners(view, radius), outline, accent), null);
    }

    RippleDrawable insetInteractive(View view, int fill, int radius, int side, int gap) {
        return new RippleDrawable(ColorStateList.valueOf(half(accent)),
                new InsetDrawable(states(view, fill, corners(view, radius), outline, accent),
                        dp(view, side), dp(view, gap), dp(view, side), dp(view, gap)), null);
    }

    /** A selected choice retains its tint while focus and pointer feedback stay visible. */
    RippleDrawable choiceSurface(View view) {
        StateListDrawable checked = new StateListDrawable();
        checked.addState(new int[]{android.R.attr.state_checked},
                states(view, iconSurface, corners(view, 12), accent, accent));
        checked.addState(new int[]{}, states(view, card, corners(view, 12), outline, accent));
        return new RippleDrawable(ColorStateList.valueOf(half(accent)),
                new InsetDrawable(checked, dp(view, 16), dp(view, 5), dp(view, 16), dp(view, 5)), null);
    }

    private Drawable states(View view, int fill, float[] corners, int border, int focus) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled},
                shape(view, fill == accent ? outline : background, corners, outline, 1));
        for (int state : new int[]{android.R.attr.state_focused, android.R.attr.state_selected,
                android.R.attr.state_hovered}) {
            states.addState(new int[]{android.R.attr.state_enabled, state}, shape(view, fill, corners, focus, 2));
        }
        states.addState(new int[]{}, shape(view, fill, corners, border, 1));
        return states;
    }

    private static float[] corners(View view, int radius) {
        float[] corners = new float[8];
        java.util.Arrays.fill(corners, dp(view, radius));
        return corners;
    }

    private static GradientDrawable shape(View view, int fill, float[] corners, int border, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadii(corners);
        shape.setStroke(dp(view, stroke), border);
        return shape;
    }

    /** A filled action with a 48dp touch target and a wrapping label. */
    public void primaryAction(Button button) {
        action(button, true, false);
    }

    /** An outlined action with the same focus, disabled and text behavior as the primary action. */
    public void secondaryAction(Button button) {
        action(button, false, false);
    }

    private void action(Button button, boolean primary, boolean dialogAction) {
        RippleDrawable background = new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                states(button, primary ? accent : dialog, corners(button, 8), primary ? accent : outline,
                        primary ? onAccent : accent), null);
        // Preserve the native Material button's fill insets inside its full touch target.
        int side = dialogAction ? dp(button, 4) : 0;
        background.setLayerInsetRelative(0, side, dp(button, 6), side, dp(button, 6));
        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTypeface(Typeface.create("sans-serif-medium", 0));
        button.setAllCaps(false);
        button.setSingleLine(false);
        button.setMaxLines(Integer.MAX_VALUE);
        button.setEllipsize(null);
        button.setMinHeight(dp(button, 48));
        button.setMinimumHeight(dp(button, 48));
        button.setPaddingRelative(dp(button, 16), dp(button, 6), dp(button, 16), dp(button, 6));
    }

    /** A section title. */
    void paintHeading(View row) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(heading);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            title.setLetterSpacing(0.07f);
            title.setAllCaps(true);
            startSide(title);
        }
        row.setPaddingRelative(dp(row, 20), dp(row, 20), dp(row, 20), dp(row, 8));
    }

    /** Marks the chevron a row was given, so a recycled row's can be found and taken off. */
    static final String CHEVRON = "hushtumblr_chevron";

    /**
     * Whether a tap on [preference] opens something: a dialog, a file picker, the browser. A
     * switch says what a tap does with its switch, and a row that acts the moment it's tapped
     * goes without a chevron, as the shared ImmediateAction asks, so the chevron keeps one meaning.
     */
    static boolean opensSomething(Preference preference) {
        return preference.isSelectable() && !(preference instanceof TwoStatePreference)
                && !(preference instanceof ImmediateAction && ((ImmediateAction) preference).actsOnTap());
    }

    /**
     * The chevron at the end of every row a tap opens something from. Only its title's colour set
     * such a row apart before, which is how "Licenses" and "Version" looked alike (WCAG 1.4.1). It
     * sits in the row's widget frame, where a switch row keeps its switch. Rows are recycled, and
     * one Row class draws plain lines too, so a row that shouldn't have one has it taken off.
     */
    private void paintChevron(View row, Preference preference) {
        View found = row.findViewById(android.R.id.widget_frame);
        if (!(found instanceof ViewGroup)) return;
        ViewGroup frame = (ViewGroup) found;
        View chevron = frame.findViewWithTag(CHEVRON);
        if (!opensSomething(preference)) {
            if (chevron != null) {
                frame.removeView(chevron);
                // Preference hides an empty frame only when it builds the row, which a recycled row
                // skips, and an empty frame still takes its padding's width from the text.
                if (frame.getChildCount() == 0) frame.setVisibility(View.GONE);
            }
            return;
        }
        ImageView image;
        if (chevron instanceof ImageView) {
            image = (ImageView) chevron;
        } else {
            image = new ImageView(row.getContext());
            image.setTag(CHEVRON);
            // The row already tells a screen reader it's a button; the shape adds nothing to hear.
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(image, new LinearLayout.LayoutParams(dp(row, 16), dp(row, 24)));
        }
        Drawable drawn = image.getDrawable();
        if (!(drawn instanceof Chevron) || ((Chevron) drawn).color != summary) {
            image.setImageDrawable(new Chevron(summary, dp(row, 2)));
        }
        image.setEnabled(preference.isEnabled());
        frame.setVisibility(View.VISIBLE);
    }

    /**
     * A chevron drawn in code, since Tumblr's APK has no resource for one. It points the way
     * the row reads, so it mirrors in a right-to-left layout, and it dims with its row.
     */
    static final class Chevron extends Drawable {
        /** The colour it's drawn in on a row that can be tapped now. */
        final int color;
        private final ColorStateList colors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Chevron(int color, float stroke) {
            this.color = color;
            colors = dimmedWhenDisabled(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        /**
         * One arm's end, the tip and the other arm's end, as x and y pairs. The tip is at the end
         * the row reads towards: the right, or the left in a right-to-left layout. Material's own
         * chevron is 4.6 dp wide and 9.2 dp tall in a 24 dp icon, and this keeps that shape.
         */
        float[] points() {
            Rect bounds = getBounds();
            float half = bounds.height() * 0.19f;
            float reach = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -half / 2 : half / 2;
            float x = bounds.exactCenterX();
            float y = bounds.exactCenterY();
            return new float[]{x - reach, y - half, x + reach, y, x - reach, y + half};
        }

        /** The colour it draws in now, which follows its row's state. */
        int currentColor() {
            return paint.getColor();
        }

        @Override
        public void draw(Canvas canvas) {
            float[] at = points();
            path.rewind();
            path.moveTo(at[0], at[1]);
            path.lineTo(at[2], at[3]);
            path.lineTo(at[4], at[5]);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public boolean onLayoutDirectionChanged(int layoutDirection) {
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return true;
        }

        @Override
        protected boolean onStateChange(int[] state) {
            int now = colors.getColorForState(state, color);
            if (now == paint.getColor()) return false;
            paint.setColor(now);
            invalidateSelf();
            return true;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter filter) {
            paint.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** Recovery is a choice, not another pair of settings rows. */
    private void paintRecoveryAction(View row, boolean primary) {
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(dimmedWhenDisabled(primary ? onAccent : secondaryActionText()));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(android.view.Gravity.CENTER);
            ViewGroup.LayoutParams size = title.getLayoutParams();
            size.width = ViewGroup.LayoutParams.MATCH_PARENT;
            title.setLayoutParams(size);
        }
        Drawable surface = states(row, primary ? accent : card, corners(row, 8),
                primary ? accent : outline, primary ? onAccent : accent);
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(half(primary ? onAccent : accent)),
                new InsetDrawable(surface, dp(row, 20), dp(row, 5), dp(row, 20), dp(row, 5)), null));
        row.setMinimumHeight(dp(row, 48) + 2 * dp(row, 5));
        row.setPadding(dp(row, 36), dp(row, 9), dp(row, 36), dp(row, 9));
    }

    static void recoveryMessage(View row) {
        ScreenColors palette = DEFAULT;
        HushTumblrPreferenceFragment.showAllText(row);
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        // Centred across the row: text wrapped to its own width has nothing to centre in.
        if (title != null) {
            fillWidth(title);
            title.setGravity(android.view.Gravity.CENTER);
            title.setTextSize(22);
            title.setTextColor(palette.title);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
            Drawable icon = SettingsIcons.icon(row.getContext(), SettingsIcons.ABOUT, palette.heading);
            icon.setBounds(0, 0, dp(row, 56), dp(row, 56));
            title.setCompoundDrawablesRelative(null, icon, null, null);
            title.setCompoundDrawablePadding(dp(row, 24));
        }
        if (summary != null) {
            fillWidth(summary);
            summary.setGravity(android.view.Gravity.CENTER);
            summary.setTextColor(palette.summary);
            summary.setTextSize(16);
            summary.setPadding(0, dp(row, 12), 0, 0);
        }
        row.setPadding(dp(row, 32), dp(row, 100), dp(row, 32), dp(row, 56));
    }

    private static void fillWidth(View text) {
        ViewGroup.LayoutParams size = text.getLayoutParams();
        if (size == null) return;
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        text.setLayoutParams(size);
    }

    /**
     * A dialog on show: its surface, title, message, buttons and text field. A list's rows keep the
     * theme's colours.
     */
    void paint(@Nullable AlertDialog dialog) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window != null) {
            GradientDrawable panel = new GradientDrawable();
            panel.setColor(this.dialog);
            panel.setCornerRadius(dp(window.getDecorView(), 12));
            panel.setStroke(dp(window.getDecorView(), 1), outline);
            window.setBackgroundDrawable(panel);
            int width = Math.min(dialog.getContext().getResources().getDisplayMetrics().widthPixels
                    - dp(window.getDecorView(), 32), dp(window.getDecorView(), 560));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        @SuppressWarnings("DiscouragedApi")
        int titleId = dialog.getContext().getResources().getIdentifier("alertTitle", "id", "android");
        TextView title = titleId == 0 ? null : dialog.findViewById(titleId);
        if (title != null) {
            title.setTextColor(this.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
        }
        // AlertDialog's own message, and the one a preference's dialog layout brings, which is the
        // one on show there: AlertDialog's own sits GONE above it, and findViewById finds that first.
        if (window != null) paintMessages(window.getDecorView());
        android.widget.ListView choices = dialog.getListView();
        if (choices != null && choices.getAdapter() != null
                && !(choices.getAdapter() instanceof DialogChoices) && !(choices.getAdapter() instanceof ChoiceCards)) {
            int selected = choices.getCheckedItemPosition();
            choices.setAdapter(new DialogChoices(choices.getAdapter(), this));
            if (selected >= 0) choices.setItemChecked(selected, true);
            choices.setDivider(null);
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button == null) continue;
            action(button, which == AlertDialog.BUTTON_POSITIVE, true);
        }
        fitDialogActions(dialog);
        View field = dialog.findViewById(android.R.id.edit);
        if (field instanceof EditText) paintField((EditText) field);
    }

    /** The native bar reserves only a peek of its second stacked action when the body is long. */
    private static void fitDialogActions(AlertDialog dialog) {
        Button[] buttons = {dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE), dialog.getButton(AlertDialog.BUTTON_POSITIVE)};
        ViewGroup old = null;
        for (Button button : buttons) if (button != null && button.getParent() instanceof ViewGroup) {
            old = (ViewGroup) button.getParent();
            break;
        }
        if (!(old instanceof LinearLayout) || old instanceof DialogActions || !(old.getParent() instanceof ViewGroup)) return;
        for (Button button : buttons) if (button != null && button.getParent() != old) return;
        ViewGroup parent = (ViewGroup) old.getParent();
        int index = parent.indexOfChild(old);
        View focused = old.findFocus();
        DialogActions actions = new DialogActions(old, buttons);
        ViewGroup.LayoutParams size = old.getLayoutParams();
        size.width = ViewGroup.LayoutParams.MATCH_PARENT;
        size.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        parent.removeView(old);
        parent.addView(actions, index, size);
        if (focused != null && focused.getParent() == actions) focused.requestFocus();
    }

    /** Keep every native button and callback, wrapping the action row only when it needs room. */
    private static final class DialogActions extends LinearLayout {
        final Button[] buttons;
        boolean stacked;

        DialogActions(ViewGroup source, Button[] buttons) {
            super(source.getContext());
            this.buttons = buttons;
            setId(source.getId());
            setPaddingRelative(source.getPaddingStart(), source.getPaddingTop(),
                    source.getPaddingEnd(), source.getPaddingBottom());
            setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            for (Button button : buttons) if (button != null) {
                source.removeView(button);
                addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            }
        }

        @Override protected void onMeasure(int width, int height) {
            int needed = getPaddingStart() + getPaddingEnd();
            for (Button button : buttons) if (button != null && button.getVisibility() != GONE) {
                button.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
                needed += button.getMeasuredWidth();
            }
            boolean nextStacked = MeasureSpec.getMode(width) != MeasureSpec.UNSPECIFIED
                    && needed > MeasureSpec.getSize(width);
            setOrientation(nextStacked ? VERTICAL : HORIZONTAL);
            if (nextStacked != stacked) {
                if (nextStacked) {
                    for (int index = buttons.length - 1; index >= 0; index--) {
                        if (buttons[index] != null) bringChildToFront(buttons[index]);
                    }
                } else {
                    for (Button button : buttons) if (button != null) bringChildToFront(button);
                }
                stacked = nextStacked;
            }
            for (Button button : buttons) if (button != null) {
                LayoutParams size = (LayoutParams) button.getLayoutParams();
                size.width = stacked ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
                size.height = LayoutParams.WRAP_CONTENT;
                size.weight = 0;
            }
            setMinimumHeight(0);
            super.onMeasure(width, height);
            setMinimumHeight(getMeasuredHeight());
        }
    }

    /**
     * A dialog's text field. The framework theme draws its underline, cursor and selection in its
     * own teal, so they take the accent the buttons have.
     */
    void paintField(EditText field) {
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        field.setHintTextColor(summary);
        field.setBackgroundTintList(null);
        field.setBackground(states(field, dialog, corners(field, 8), switchOff, accent));
        field.setPaddingRelative(dp(field, 12), dp(field, 10), dp(field, 12), dp(field, 10));
        field.setTextColor(title);
        paintSelection(field);
    }

    /** Search uses a separate boundary while sharing the editor's selection palette. */
    void paintSelection(EditText field) {
        field.setHighlightColor(half(accent));
        paintCursorAndHandles(field);
    }

    /**
     * Gives a field's cursor and selection handles the accent. Android 10 brought the calls that set
     * them, so before it they keep the framework's teal: a look, nothing a reader loses.
     */
    private void paintCursorAndHandles(EditText field) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        Drawable cursor = field.getTextCursorDrawable();
        if (cursor != null) {
            cursor = cursor.mutate();
            cursor.setTint(accent);
            field.setTextCursorDrawable(cursor);
        }
        Drawable handle = field.getTextSelectHandle();
        if (handle != null) {
            handle = handle.mutate();
            handle.setTint(accent);
            field.setTextSelectHandle(handle);
        }
        Drawable left = field.getTextSelectHandleLeft();
        if (left != null) {
            left = left.mutate();
            left.setTint(accent);
            field.setTextSelectHandleLeft(left);
        }
        Drawable right = field.getTextSelectHandleRight();
        if (right != null) {
            right = right.mutate();
            right.setTint(accent);
            field.setTextSelectHandleRight(right);
        }
    }

    private void paintMessages(View view) {
        if (view instanceof TextView && view.getId() == android.R.id.message) ((TextView) view).setTextColor(summary);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) paintMessages(group.getChildAt(index));
        }
    }

    /** Paints a row in the screen's colours. */
    static void row(View row, Preference preference) {
        DEFAULT.paintRow(row, preference);
    }

    /** Paints a section title in the screen's colours. */
    static void heading(View row) {
        DEFAULT.paintHeading(row);
    }

    /** Paints both recovery actions even if the ordinary settings page failed to initialize. */
    static void recoveryAction(View row, boolean primary) {
        DEFAULT.paintRecoveryAction(row, primary);
    }

    /** Paints a dialog in the screen's colours. */
    public static void dialog(@Nullable AlertDialog dialog) {
        DEFAULT.paint(dialog);
    }

    /**
     * Puts each short line of text under [view] on the side the screen starts from. Android sides
     * a full-width line by its first letter, so English shown in a right-to-left language stayed
     * at the left of the header, a heading or a dialog's label while the rows, which are only as
     * wide as their words, sat at the right. A button, a switch and anything centred or sided on
     * purpose keep their place. A long message isn't passed here: it fills its lines either way.
     */
    public static void startSide(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView text = (TextView) view;
            if ((text.getGravity() & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) == Gravity.START) {
                text.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) startSide(group.getChildAt(index));
        }
    }

    private static void startMargin(View view, int margin) {
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams size = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            size.setMarginStart(margin);
            view.setLayoutParams(size);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics()));
    }

    /** A switch's track: the thumb's colour at half strength, as Material's own switch draws it. */
    static int half(int color) {
        return (color & 0x00FFFFFF) | 0x80000000;
    }
}
