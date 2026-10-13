package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;

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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.EnumSet;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 30, 36, 37}, qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SuppressWarnings("deprecation")
public class SettingsWidgetRenderingTest {
    @Rule public final SettingsContextRule context = new SettingsContextRule();

    @After public void restore() {
        PatchFamily.inBuildForTests = null;
    }

    @Test public void drawnSwitchKeepsItsTrackThumbAndInsetsInBothDirections() {
        android.content.Context activity = RuntimeEnvironment.getApplication();
        float density = activity.getResources().getDisplayMetrics().density;
        int target = Math.round(48 * density);
        int inset = Math.round(4 * density);
        Switch toggle = new Switch(activity);
        toggle.setId(android.R.id.switch_widget);
        toggle.setShowText(false);
        LinearLayout row = new LinearLayout(activity);
        row.addView(toggle);
        ScreenColors.DEFAULT.paintRow(row, new SwitchPreference(activity));

        for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            toggle.setLayoutDirection(direction);
            for (boolean checked : new boolean[]{false, true}) {
                toggle.setChecked(checked);
                toggle.jumpDrawablesToCurrentState();
                toggle.measure(View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(target, View.MeasureSpec.EXACTLY));
                toggle.layout(0, 0, target, target);
                Bitmap image = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
                toggle.draw(new Canvas(image));
                Rect track = toggle.getTrackDrawable().getBounds();
                Rect thumb = ((InsetDrawable) toggle.getThumbDrawable()).getDrawable().getBounds();
                assertEquals("drawn track width", Math.round(44 * density), track.width());
                assertEquals("drawn track height", Math.round(24 * density), track.height());
                assertEquals("drawn thumb width", Math.round(16 * density), thumb.width());
                assertEquals("drawn thumb height", Math.round(16 * density), thumb.height());
                assertEquals("thumb top inset", inset, thumb.top - track.top);
                assertEquals("thumb bottom inset", inset, track.bottom - thumb.bottom);
                if (checked != (direction == View.LAYOUT_DIRECTION_RTL)) {
                    assertEquals("thumb end inset", inset, track.right - thumb.right);
                } else {
                    assertEquals("thumb start inset", inset, thumb.left - track.left);
                }
                assertTrue(toggle.getWidth() >= target && toggle.getHeight() >= target);
                image.recycle();
            }
        }
    }

    @Test public void tabMoveGlyphsDrawUpwardInBothDirections() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        TextSettings.TAB_ORDER.resetToDefault();
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            SettingsDialog settings = SettingsL10nTest.show(controller.get());
            HushTumblrPreferenceFragment page = (HushTumblrPreferenceFragment)
                    settings.getChildFragmentManager().findFragmentById(SettingsDialog.CONTAINER_ID);
            page.navigation.navigate("Interface");
            Preference preference = page.findPreference(HushTumblrPreferenceFragment.TAB_ORDER_ROW);
            preference.getOnPreferenceClickListener().onPreferenceClick(preference);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView list = dialog.getListView();
            for (int direction : new int[]{View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
                for (int position = 0; position < list.getAdapter().getCount(); position++) {
                    TextView label = (TextView) list.getAdapter().getView(position, null, list);
                    label.setLayoutDirection(direction);
                    Drawable arrow = label.getCompoundDrawablesRelative()[2];
                    assertNotNull(arrow);
                    int size = arrow.getBounds().width();
                    Bitmap actual = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    arrow.draw(new Canvas(actual));
                    Drawable back = SettingsIcons.icon(label.getContext(), SettingsIcons.BACK,
                            position == 0 ? ScreenColors.DEFAULT.summary : ScreenColors.DEFAULT.accent);
                    back.setAutoMirrored(false);
                    back.setState(label.getDrawableState());
                    back.setBounds(0, 0, size, size);
                    Bitmap expected = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(expected);
                    canvas.rotate(90, size / 2f, size / 2f);
                    back.draw(canvas);
                    assertTrue("move action must draw upward at position " + position,
                            actual.sameAs(expected));
                    actual.recycle();
                    expected.recycle();
                }
            }
            dialog.dismiss();
        }
    }
}
