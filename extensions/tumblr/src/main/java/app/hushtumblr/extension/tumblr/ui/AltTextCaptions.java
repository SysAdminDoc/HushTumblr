/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import app.hushtumblr.extension.shared.ResourceType;
import app.hushtumblr.extension.shared.ResourceUtils;
import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.Settings;

/** Author-provided image descriptions displayed under their own native image frame. */
public final class AltTextCaptions {
    /** Tumblr's theme attribute for the text of a post, by the name the declared build gives it. */
    private static final String POST_TEXT_COLOR = "themeMainTextColor";
    private static final int UNREAD = -1;
    /** [POST_TEXT_COLOR]'s id in this build, zero when it has none, read once. */
    private static volatile int postTextAttribute = UNREAD;
    /** Stands in for Tumblr's attribute in tests, whose themes don't have it. */
    static Integer postTextAttributeForTests;
    private AltTextCaptions() {}

    // Neither side owns a view's lifetime. An old caption can still be removed if its frame moved.
    private static final Map<View, WeakReference<TextView>> CAPTIONS = new WeakHashMap<>();

    public static void photo(Object holder, Object block) {
        bind(holder, block, false);
    }

    public static void gallery(Object holder, Object block) {
        bind(holder, block, true);
    }

    /** Runs even if a gallery's native full bind returns before reaching its per-image binder. */
    public static void clearGallery(Object row) {
        try {
            if (!(row instanceof HushImageRow)) return;
            Object[] items = ((HushImageRow) row).hushImageItems();
            if (items == null) return;
            for (Object item : items) {
                if (item instanceof HushImageContainer) clear(((HushImageContainer) item).hushImageFrame());
            }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VISIBLE_ALT_TEXT, "gallery caption cleanup", failure);
        }
    }

    private static void bind(Object holder, Object block, boolean gallery) {
        try {
            if (!(holder instanceof HushImageContainer)) return;
            View frame = ((HushImageContainer) holder).hushImageFrame();
            clear(frame);
            if (frame == null || !Utils.settingsReady()) return;
            PatchFamily.Capability capability = gallery ? PatchFamily.Capability.GALLERY_ALT_TEXT_CAPTIONS
                    : PatchFamily.Capability.PHOTO_ALT_TEXT_CAPTIONS;
            if (!PatchFamily.VISIBLE_ALT_TEXT.inBuild() || !capability.installed()
                    || !Settings.SHOW_IMAGE_DESCRIPTIONS.get()) return;
            HookStatus.invoked(FamilyNames.VISIBLE_ALT_TEXT);
            if (!(block instanceof HushImageDescription)) return;
            String text = description(((HushImageDescription) block).hushAltText());
            if (text == null || !(frame.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) frame.getParent();
            if (parent.getOrientation() != LinearLayout.VERTICAL) return;
            int imageIndex = parent.indexOfChild(frame);
            if (imageIndex < 0) return;

            TextView caption = new TextView(frame.getContext());
            caption.setText(text);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            caption.setTextColor(captionColor(frame.getContext(), caption));
            caption.setGravity(Gravity.START);
            caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            caption.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            caption.setLayoutDirection(View.LAYOUT_DIRECTION_INHERIT);
            caption.setPaddingRelative(dp(frame, 12), dp(frame, 8), dp(frame, 12), dp(frame, 8));
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            caption.setFocusable(false);
            caption.setClickable(false);
            caption.setLinksClickable(false);
            caption.setAutoLinkMask(0);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (frame.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams imageLayout = (ViewGroup.MarginLayoutParams) frame.getLayoutParams();
                layout.setMarginStart(imageLayout.getMarginStart());
                layout.setMarginEnd(imageLayout.getMarginEnd());
            }
            parent.addView(caption, imageIndex + 1, layout);
            synchronized (CAPTIONS) { CAPTIONS.put(frame, new WeakReference<>(caption)); }
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.VISIBLE_ALT_TEXT, "image caption", failure);
        }
    }

    private static void clear(View frame) {
        if (frame == null) return;
        WeakReference<TextView> previous;
        synchronized (CAPTIONS) { previous = CAPTIONS.remove(frame); }
        TextView caption = previous == null ? null : previous.get();
        if (caption != null && caption.getParent() instanceof ViewGroup) {
            ((ViewGroup) caption.getParent()).removeView(caption);
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static String description(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.codePoints().allMatch(code -> Character.isWhitespace(code) || Character.isSpaceChar(code))) return null;
        switch (text.toLowerCase(Locale.ROOT)) {
            case "image": case "photo": case "picture": case "gif": case "image description": return null;
            default: return text;
        }
    }

    /**
     * The color for a caption: the one Tumblr's own post text has in the palette that's showing
     * (`graywater_dashboard_textblock` colors its text with this attribute). A caption sits on the
     * post card, and the framework's primary text color belongs to the page behind it. In True Blue
     * that color is near white for the navy page, and a caption in it can't be read on the white
     * card. A build without the attribute gets the primary text color, as before.
     */
    private static ColorStateList captionColor(Context context, TextView caption) {
        Integer forTests = postTextAttributeForTests;
        int postText;
        if (forTests != null) {
            postText = forTests;
        } else {
            postText = postTextAttribute;
            if (postText == UNREAD) {
                postText = ResourceUtils.getIdentifier(context, ResourceType.ATTR, POST_TEXT_COLOR);
                postTextAttribute = postText;
            }
        }
        for (int attribute : new int[] { postText, android.R.attr.textColorPrimary }) {
            if (attribute == 0) continue;
            TypedArray colors = context.obtainStyledAttributes(new int[] { attribute });
            try {
                ColorStateList found = colors.getColorStateList(0);
                if (found != null) return found;
            } finally {
                colors.recycle();
            }
        }
        return caption.getTextColors();
    }
}
