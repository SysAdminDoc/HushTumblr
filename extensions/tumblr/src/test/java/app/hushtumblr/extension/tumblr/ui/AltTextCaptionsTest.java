/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Spanned;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AltTextCaptionsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        Settings.SHOW_IMAGE_DESCRIPTIONS.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @After public void restore() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.resetToDefault();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void captionsStartOffAndRepeatedBindsReplaceOnlyTheSameImagesCaption() {
        Image image = image(context(false));
        assertFalse(Settings.SHOW_IMAGE_DESCRIPTIONS.get());
        AltTextCaptions.photo(image, description("First description"));
        assertEquals(2, image.parent.getChildCount());
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        AltTextCaptions.photo(image, description("First description"));
        TextView first = caption(image);
        AltTextCaptions.photo(image, description("Second description"));
        assertNull(first.getParent());
        assertEquals("Second description", caption(image).getText().toString());
        assertEquals(3, image.parent.getChildCount());
        assertSame(image.attribution, image.parent.getChildAt(2));
        assertEquals("Source blog", image.attribution.getText().toString());
    }

    @Test public void everyImageInAGalleryKeepsItsOwnDescriptionAndFullRebindClearsAllOldCaptions() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        Image first = image(context(false));
        Image second = image(context(false));
        AltTextCaptions.gallery(first, description("The left image"));
        AltTextCaptions.gallery(second, description("The right image"));
        assertEquals("The left image", caption(first).getText().toString());
        assertEquals("The right image", caption(second).getText().toString());
        HushImageRow row = () -> new Object[] { first, second, null, new Object() };
        AltTextCaptions.clearGallery(row);
        assertEquals(2, first.parent.getChildCount());
        assertEquals(2, second.parent.getChildCount());
        AltTextCaptions.gallery(first, description("New left image"));
        AltTextCaptions.gallery(second, description(null));
        assertEquals("New left image", caption(first).getText().toString());
        assertEquals(2, second.parent.getChildCount());
    }

    @Test public void optionOffPauseStartupAndMissingTargetRemovePreviousCaptions() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        Image image = image(context(false));
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            AltTextCaptions.photo(image, description("Description"));
            PauseForTests.pause(reason);
            AltTextCaptions.photo(image, description("Description"));
            assertEquals(reason.name(), 2, image.parent.getChildCount());
            PauseForTests.resume();
        }
        AltTextCaptions.photo(image, description("Description"));
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(false);
        AltTextCaptions.photo(image, description("Description"));
        assertEquals(2, image.parent.getChildCount());
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        AltTextCaptions.photo(image, description("Description"));
        SettingsContextRule.withoutContext(() -> AltTextCaptions.photo(image, description("Description")));
        assertEquals(2, image.parent.getChildCount());
        AltTextCaptions.photo(image, description("Description"));
        PatchFamilyForTests.capabilities(EnumSet.of(PatchFamily.Capability.GALLERY_ALT_TEXT_CAPTIONS));
        AltTextCaptions.photo(image, description("Description"));
        assertEquals(2, image.parent.getChildCount());
    }

    @Test public void emptyGenericMissingAndUnreadableDescriptionsNeverLeaveStaleText() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        Image image = image(context(false));
        for (String text : Arrays.asList(null, "", "  \n\t", "\u00a0\u2007", "Image", " PHOTO ", "GIF", "image description")) {
            AltTextCaptions.photo(image, description("Previous image"));
            AltTextCaptions.photo(image, description(text));
            assertEquals(String.valueOf(text), 2, image.parent.getChildCount());
        }
        AltTextCaptions.photo(image, description("Previous image"));
        AltTextCaptions.photo(image, new Object());
        assertEquals(2, image.parent.getChildCount());
        AltTextCaptions.photo(image, description("Previous image"));
        AltTextCaptions.photo(image, (HushImageDescription) () -> { throw new IllegalStateException("unreadable"); });
        assertEquals(2, image.parent.getChildCount());
    }

    @Test public void descriptionsStayPlainAndDoNotDuplicateOrChangeTheImagesAccessibilityText() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        Image image = image(context(false));
        String raw = "<b>Author text</b> https://example.test/image?a=1&b=2";
        AltTextCaptions.photo(image, description(raw));
        TextView caption = caption(image);
        assertEquals(raw, caption.getText().toString());
        assertFalse(caption.getText() instanceof Spanned);
        assertFalse(caption.isFocusable());
        assertFalse(caption.isClickable());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, caption.getImportantForAccessibility());
        assertEquals("Native screen reader description", image.image.getContentDescription());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, image.image.getImportantForAccessibility());
        assertEquals("ALT", image.badge.getText().toString());
    }

    @Test public void movingAnImageRemovesItsCaptionFromTheOldParentAndUnknownLayoutsAreLeftAlone() {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        Image image = image(context(false));
        AltTextCaptions.photo(image, description("Previous parent"));
        image.parent.removeView(image.frame);
        LinearLayout next = new LinearLayout(image.frame.getContext());
        next.setOrientation(LinearLayout.HORIZONTAL);
        next.addView(image.frame);
        AltTextCaptions.photo(image, description("Different layout"));
        assertEquals(1, image.parent.getChildCount());
        assertSame(image.attribution, image.parent.getChildAt(0));
        assertEquals(1, next.getChildCount());
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void darkLightEnlargedTextAndRtlRenderWithoutClippingTheCaptionOrAttribution() throws Exception {
        Settings.SHOW_IMAGE_DESCRIPTIONS.save(true);
        for (boolean dark : new boolean[] { false, true }) {
            Context base = context(dark);
            Configuration configuration = new Configuration(base.getResources().getConfiguration());
            configuration.fontScale = 1.8f;
            configuration.setLocale(new Locale("ar"));
            Context scaled = new ContextThemeWrapper(base.createConfigurationContext(configuration),
                    dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
            Image image = image(scaled);
            image.parent.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            image.parent.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
            AltTextCaptions.gallery(image, description("وصف الصورة الذي كتبه المؤلف. A long author description wraps below its own image and stays above the source attribution."));
            image.parent.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            image.parent.layout(0, 0, 540, image.parent.getMeasuredHeight());
            TextView caption = caption(image);
            assertNotNull(caption.getLayout());
            assertTrue("The enlarged author text must wrap at the image width", caption.getLineCount() > 1);
            assertTrue("Every caption line must fit inside the caption view",
                    caption.getPaddingTop() + caption.getLayout().getHeight() + caption.getPaddingBottom()
                            <= caption.getHeight());
            assertTrue(caption.getTop() >= image.frame.getBottom());
            assertTrue(image.attribution.getTop() >= caption.getBottom());
            assertTrue(image.parent.getHeight() >= image.attribution.getBottom());
            assertEquals(View.LAYOUT_DIRECTION_RTL, caption.getLayoutDirection());
            int color = caption.getCurrentTextColor();
            int brightness = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3;
            assertTrue(dark ? brightness > 128 : brightness < 128);
            Bitmap bitmap = Bitmap.createBitmap(540, image.parent.getHeight(), Bitmap.Config.ARGB_8888);
            image.parent.draw(new Canvas(bitmap));
            File target = new File("build/reports/alt-text-captions/" + (dark ? "dark" : "light") + "-rtl-large.png");
            assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
            try (FileOutputStream output = new FileOutputStream(target)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            bitmap.recycle();
        }
    }

    private static Context context(boolean dark) {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
                dark ? android.R.style.Theme_Material : android.R.style.Theme_Material_Light);
    }

    private static HushImageDescription description(String text) { return () -> text; }

    private static TextView caption(Image image) { return (TextView) image.parent.getChildAt(1); }

    private static Image image(Context context) { return new Image(context); }

    private static final class Image implements HushImageContainer {
        final LinearLayout parent;
        final FrameLayout frame;
        final ImageView image;
        final TextView badge;
        final TextView attribution;

        Image(Context context) {
            parent = new LinearLayout(context);
            parent.setOrientation(LinearLayout.VERTICAL);
            frame = new FrameLayout(context);
            image = new ImageView(context);
            image.setContentDescription("Native screen reader description");
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180));
            badge = new TextView(context);
            badge.setText("ALT");
            frame.addView(badge);
            parent.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180));
            attribution = new TextView(context);
            attribution.setText("Source blog");
            parent.addView(attribution);
        }

        @Override public View hushImageFrame() { return frame; }
    }
}
