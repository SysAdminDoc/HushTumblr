/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.notifications;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.shared.settings.HushTumblrPause;
import app.hushtumblr.extension.shared.settings.PauseForTests;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;
import app.hushtumblr.extension.tumblr.settings.Settings;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class ActivityNotificationsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        reset();
    }

    @After public void restore() {
        reset();
        PatchFamilyForTests.inBuild(null);
        PatchFamilyForTests.capabilities(null);
    }

    private void reset() {
        for (BooleanSetting setting : switches()) setting.resetToDefault();
        PauseForTests.resume();
        HookStatus.clear();
    }

    @Test public void allSwitchesStartOffAndNothingChangesUntilOneIsEnabled() {
        List<?> page = Arrays.asList(item("Like"), item("Reblog"), item("Reply"), item("Follower"),
                item("UserMention"), item("Ask"), item("Boop"));
        for (BooleanSetting setting : switches()) assertFalse(setting.key, setting.get());
        assertSame(page, ActivityNotifications.filter(page));
        assertNull(ActivityNotifications.filter(null));
        List<?> empty = Collections.emptyList();
        assertSame(empty, ActivityNotifications.filter(empty));
    }

    @Test public void eachSwitchHidesItsSingleAndRollupTypesWhileOthersStayInOrder() {
        String[][] groups = {
                {"Like", "LikeRollup"}, {"Reblog", "ReblogNaked", "ReblogNakedRollup"},
                {"Reply", "ReplyToComment"}, {"Follower", "FollowerRollup"},
                {"NoteMention", "UserMention"}, {"Ask", "AskAnswer"}, {"Boop", "BoopRollup"}
        };
        for (int selected = 0; selected < groups.length; selected++) {
            reset();
            switches().get(selected).save(true);
            List<Object> page = new ArrayList<>();
            List<Object> expected = new ArrayList<>();
            for (int group = 0; group < groups.length; group++) {
                for (String type : groups[group]) {
                    Object notification = item(type);
                    page.add(notification);
                    if (group != selected) expected.add(notification);
                }
            }
            List<Object> original = new ArrayList<>(page);
            assertEquals("switch " + selected, expected, ActivityNotifications.filter(page));
            assertEquals(original, page);
        }
    }

    @Test public void independentSwitchesCanHideAWholePageWithoutMutatingAnImmutableList() {
        Settings.HIDE_NOTIFICATION_LIKES.save(true);
        Settings.HIDE_NOTIFICATION_REBLOGS.save(true);
        List<?> page = Collections.unmodifiableList(Arrays.asList(item("LikeRollup"), item("ReblogNaked")));
        assertTrue(ActivityNotifications.filter(page).isEmpty());
        assertEquals(2, page.size());
        assertTrue(HookStatus.report().get(0).contains("notification removed 2"));
    }

    @Test public void unknownNullUnreadableAndNonInteractionItemsStayBesideKnownTypes() {
        for (BooleanSetting setting : switches()) setting.save(true);
        HushActivityNotification broken = () -> { throw new IllegalStateException("unreadable"); };
        Object ordinary = new Object();
        List<?> survivors = Arrays.asList(null, ordinary, item(null), item("Unknown"), item("FutureType"), broken,
                item("Conversational"), item("ConversationalRollup"), item("PostFlagged"), item("MilestoneLike"));
        List<Object> page = new ArrayList<>(survivors);
        page.add(2, item("Like"));
        assertEquals(survivors, ActivityNotifications.filter(page));
        assertTrue(HookStatus.report().get(0).contains("activity type"));
    }

    @Test public void noMatchingTypesReturnsTheSameList() {
        Settings.HIDE_NOTIFICATION_LIKES.save(true);
        List<?> page = Arrays.asList(item("Reply"), item("FutureType"));
        assertSame(page, ActivityNotifications.filter(page));
    }

    @Test public void pauseStartupAndMissingCapabilityKeepTheOriginalPageAndSavedChoice() {
        Settings.HIDE_NOTIFICATION_LIKES.save(true);
        List<?> page = Collections.singletonList(item("Like"));
        for (HushTumblrPause.Reason reason : HushTumblrPause.Reason.values()) {
            if (reason == HushTumblrPause.Reason.NONE) continue;
            PauseForTests.pause(reason);
            assertSame(reason.name(), page, ActivityNotifications.filter(page));
            assertTrue(Settings.HIDE_NOTIFICATION_LIKES.savedValue());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> assertSame(page, ActivityNotifications.filter(page)));
        SettingsContextRule.beforeThePauseIsDecided(() -> assertSame(page, ActivityNotifications.filter(page)));
        PatchFamilyForTests.capabilities(EnumSet.noneOf(PatchFamily.Capability.class));
        assertSame(page, ActivityNotifications.filter(page));
        PatchFamilyForTests.capabilities(EnumSet.allOf(PatchFamily.Capability.class));
        PatchFamilyForTests.inBuild(EnumSet.noneOf(PatchFamily.class));
        assertSame(page, ActivityNotifications.filter(page));
    }

    @Test public void aListFailureAfterAnEarlierRemovalReturnsTheEntireOriginalPage() {
        Settings.HIDE_NOTIFICATION_LIKES.save(true);
        List<Object> page = new AbstractList<Object>() {
            @Override public int size() { return 2; }
            @Override public Object get(int index) {
                if (index == 0) return item("Like");
                throw new IllegalStateException("unreadable page");
            }
        };
        assertSame(page, ActivityNotifications.filter(page));
        assertFalse(HookStatus.report().get(0).contains("notification removed"));
    }

    private static HushActivityNotification item(String type) { return () -> type; }

    private static List<BooleanSetting> switches() {
        return Arrays.asList(Settings.HIDE_NOTIFICATION_LIKES, Settings.HIDE_NOTIFICATION_REBLOGS,
                Settings.HIDE_NOTIFICATION_REPLIES, Settings.HIDE_NOTIFICATION_FOLLOWS,
                Settings.HIDE_NOTIFICATION_MENTIONS, Settings.HIDE_NOTIFICATION_ASKS,
                Settings.HIDE_NOTIFICATION_BOOPS);
    }
}
