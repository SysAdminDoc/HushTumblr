/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.Utils;

/**
 * Tumblr runs from Android 8.0, and so does HushTumblr. The other tests here run on Android 10 or
 * newer, where every call below exists. These run where some don't, so a call left without its
 * version check throws NoSuchMethodError here as it would on the phone, and each check's other
 * path is what's held.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 27, 28})
public class OlderAndroidTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private ActivityController<Activity> controller;

    @Before
    public void everyPatchIn() {
        PatchFamily.inBuildForTests = EnumSet.allOf(PatchFamily.class);
        controller = Robolectric.buildActivity(Activity.class).setup();
    }

    @After
    public void restore() {
        PatchFamily.inBuildForTests = null;
        controller.close();
    }

    /**
     * The screen opens and binds every row. A section title is a heading from Android 9, which
     * brought the flag; before that it's read as plain text, and the screen still opens.
     */
    @Test
    public void theSettingsScreenOpensAndMarksHeadingsWhereAndroidCan() {
        SettingsDialog dialog = SettingsL10nTest.show(controller.get());
        ListView list = dialog.getView().findViewById(android.R.id.list);
        assertNotNull("no list in the dialog", list);
        HushTumblrPreferenceFragment page = SettingsL10nTest.pageOf(dialog);
        list.setAdapter(page.getPreferenceScreen().getRootAdapter());
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(40000, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 40000);
        assertEquals("the list left rows off", list.getAdapter().getCount(), list.getChildCount());
        int sections = 0;
        for (int index = 0; index < list.getChildCount(); index++) {
            Preference row = (Preference) list.getItemAtPosition(index);
            if (!(row instanceof PreferenceCategory)) continue;
            sections++;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                assertTrue(row.getTitle() + " isn't a heading", list.getChildAt(index).createAccessibilityNodeInfo().isHeading());
            }
        }
        assertTrue("no section on the screen", sections >= 5);
    }

    /** A dialog's text field still takes the screen's colors when its cursor can't. */
    @Test
    public void aFieldTakesItsColorsWithoutTheCursorCalls() {
        EditText field = new EditText(controller.get());
        ScreenColors.DEFAULT.paintField(field);
        assertEquals(ScreenColors.DEFAULT.title, field.getCurrentTextColor());
        assertNotNull("the outline wasn't drawn", field.getBackground());
    }

    /** The report's push section says delegates came with Android 10 rather than ending the export. */
    @Test
    public void theReportSaysNotificationDelegatesCameWithAndroid10() {
        List<String> lines = PushReadiness.REPORT.lines();
        String expected = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? "notification_delegate: none (delegation is optional)"
                : "notification_delegate: not available before Android 10";
        assertTrue(lines.toString(), lines.contains(expected));
    }

    /** Before Android 9 the version code comes from the int field, the whole of Tumblr's code. */
    @Test
    @SuppressWarnings("deprecation")
    public void theVersionCodeIsReadWhereverAndroidKeepsIt() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        PackageInfo info = Shadows.shadowOf(app.getPackageManager()).getInternalMutablePackageInfo(app.getPackageName());
        info.versionCode = 1460500109;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.setLongVersionCode(1460500109L);
        Field cached = Utils.class.getDeclaredField("versionCode");
        cached.setAccessible(true);
        cached.setLong(null, -1L);
        try {
            assertEquals(1460500109L, Utils.getAppVersionCode());
        } finally {
            cached.setLong(null, -1L);
        }
    }
}
