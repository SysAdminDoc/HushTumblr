package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import app.hushtumblr.extension.shared.L10n;
import app.hushtumblr.extension.shared.SettingsContextRule;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@SuppressWarnings("deprecation")
public class SettingsMastheadTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();
    private ActivityController<Activity> controller;

    @After public void tearDown() {
        SettingsDialog.failNextMount = null;
        if (controller != null) controller.close();
        PatchFamily.inBuildForTests = null;
        RuntimeEnvironment.setFontScale(1f);
    }

    @Test public void homeHasAReadableHeadingAndKeepsItsWindowRouteTitle() {
        SettingsDialog dialog = show(1f);
        TextView title = text(dialog.getView(), L10n.t("Your settings"));
        assertNotNull(title);
        AccessibilityNodeInfo node = title.createAccessibilityNodeInfo();
        assertTrue(node.isHeading());
        // The name a screen reader announces the window by. The attributes' own title is the
        // window manager's debug name, which Window.setTitle never touches.
        assertEquals("HushTumblr", String.valueOf(ReflectionHelpers.<CharSequence>getField(
                dialog.getDialog().getWindow().getAttributes(), "accessibilityTitle")));
        assertNotNull(text(dialog.getView(), L10n.t("Make Tumblr feel like yours.")));
    }

    @Test public void aResizedViewportKeepsBackSearchAndTheNativeListReachableAtTwiceTextSize() {
        SettingsDialog dialog = show(2f);
        ViewGroup root = (ViewGroup) dialog.getView();
        EditText search = find(root, EditText.class);
        assertNotNull(search);
        search.setText("word");
        ShadowLooper.idleMainLooper();
        measure(root, 320, 360);

        ImageButton back = find(root, ImageButton.class);
        View list = root.findViewById(android.R.id.list);
        assertNotNull(back);
        assertNotNull(list);
        assertTrue(back.getHeight() >= dp(48));
        assertWithin(root, back);
        assertWithin(root, search);
        assertTrue("the masthead leaves no usable native list", list.getHeight() >= dp(48));
        assertEquals(View.GONE, text(root, L10n.t("Make Tumblr feel like yours.")).getVisibility());
        assertEquals("word", search.getText().toString());

        measure(root, 320, 800);
        assertEquals(View.VISIBLE, text(root, L10n.t("Make Tumblr feel like yours.")).getVisibility());
        assertEquals("word", search.getText().toString());
        assertTrue(list.getHeight() >= dp(48));
    }

    // Real text measuring: the default lays every string out one pixel a letter on a single line,
    // so the heading never grows tall enough to crowd the list.
    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void aLongTranslatedTitleCompactsWhenANarrowTallViewportLeavesNoUsableList() {
        SettingsDialog dialog = show(2f);
        ViewGroup root = (ViewGroup) dialog.getView();
        String title = "Pause, Sicherungskopien und Diagnoseeinstellungen";
        String description = "Verwalte gespeicherte Einstellungen und Diagnoseberichte auf diesem Gerät.";
        dialog.showPage(title, false, "", description);

        measure(root, 220, 650);
        assertEquals(View.GONE, text(root, description).getVisibility());
        View list = root.findViewById(android.R.id.list);
        assertNotNull(list);
        assertTrue("the translated heading leaves no usable native list", list.getHeight() >= dp(48));
        TextView heading = text(root, title);
        assertNotNull(heading);
        assertTrue("the translated heading is clipped", heading.getLayout().getHeight()
                <= heading.getHeight() - heading.getCompoundPaddingTop() - heading.getCompoundPaddingBottom());
        assertWithin(root, find(root, ImageButton.class));

        measure(root, 360, 900);
        assertEquals(View.VISIBLE, text(root, description).getVisibility());
        assertTrue(list.getHeight() >= dp(48));
    }

    @Test public void retryRestoresTheRequestedCategoryDescriptionWithoutShowingHomeSearch() {
        SettingsDialog dialog = show(1f);
        HushTumblrPreferenceFragment page = (HushTumblrPreferenceFragment)
                dialog.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
        assertNotNull(page);
        page.navigation.navigate("Feed");
        assertNotNull(page.findPreference(Settings.HIDE_ADS.key));
        SettingsDialog.failNextMount = new IllegalStateException("injected mount failure");
        ReflectionHelpers.callInstanceMethod(dialog, "mountPage",
                ReflectionHelpers.ClassParameter.from(boolean.class, true));
        assertNotNull(dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));

        View retry = dialog.getView().findViewWithTag(SettingsDialog.MOUNT_RETRY);
        retry.performClick();
        ShadowLooper.idleMainLooper();
        measure(dialog.getView(), 360, 800);

        assertNull(dialog.getView().findViewWithTag(SettingsDialog.MOUNT_ERROR));
        assertNotNull(text(dialog.getView(), L10n.t("Feed")));
        TextView subtitle = text(dialog.getView(), L10n.t("Ads, reblogs and word filters"));
        assertNotNull(subtitle);
        assertEquals(View.VISIBLE, subtitle.getVisibility());
        assertEquals(L10n.t("Feed"), String.valueOf(ReflectionHelpers.<CharSequence>getField(
                dialog.getDialog().getWindow().getAttributes(), "accessibilityTitle")));
        TextView hideAds = text(dialog.getView(), L10n.t("Hide ads"));
        assertNotNull("Retry lost the Feed preferences", hideAds);
        assertTrue(hideAds.isShown());
        EditText search = find(dialog.getView(), EditText.class);
        assertFalse(search.isShown());
        assertFalse(controller.get().isFinishing());
    }

    private SettingsDialog show(float fontScale) {
        RuntimeEnvironment.setFontScale(fontScale);
        PatchFamily.inBuildForTests = java.util.EnumSet.allOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
        SettingsDialog dialog = new SettingsDialog();
        dialog.show(controller.get().getFragmentManager(), "masthead_settings");
        controller.get().getFragmentManager().executePendingTransactions();
        ShadowLooper.idleMainLooper();
        return dialog;
    }

    private void measure(View root, int width, int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(height), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, dp(width), dp(height));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                controller.get().getResources().getDisplayMetrics()));
    }

    private static void assertWithin(ViewGroup root, View child) {
        Rect bounds = new Rect();
        child.getDrawingRect(bounds);
        root.offsetDescendantRectToMyCoords(child, bounds);
        assertTrue("control is above the resized viewport", bounds.top >= 0);
        assertTrue("control is below the resized viewport", bounds.bottom <= root.getHeight());
    }

    private static TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = text(group.getChildAt(i), value);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> kind) {
        if (kind.isInstance(view)) return kind.cast(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                T result = find(group.getChildAt(i), kind);
                if (result != null) return result;
            }
        }
        return null;
    }
}
