/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.flags;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;
import app.hushtumblr.extension.tumblr.settings.PatchFamilyForTests;

/**
 * The table behind the feature hook. What each family's switch does to its flags, paused and
 * before the settings are ready included, is held by PausedHooksTest's probes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FeatureSwitchesTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void restore() {
        HookStatus.clear();
    }

    /** The flag named [flag] as the hook sees it. */
    public static FakeFeature flag(String flag) {
        return FakeFeature.valueOf(flag);
    }

    @Test
    public void aFlagNoFamilyClaimsKeepsTumblrsAnswer() {
        assertTrue(FeatureSwitches.isEnabled(FakeFeature.UNCLAIMED_FLAG, true));
        assertFalse(FeatureSwitches.isEnabled(FakeFeature.UNCLAIMED_FLAG, false));
        assertTrue(FeatureSwitches.isEnabled(null, true));
        assertFalse(FeatureSwitches.isEnabled(null, false));
        SettingsContextRule.withoutContext(() -> assertTrue(FeatureSwitches.isEnabled(FakeFeature.UNCLAIMED_FLAG, true)));
    }

    /** A claim names a family by its constant and its patch name, and the two have to agree. */
    @Test
    public void everyClaimNamesAFamilyWithASwitch() {
        List<String> wrong = new ArrayList<>();
        for (String flag : FeatureSwitches.claimedFlags()) {
            FeatureSwitches.Claim claim = FeatureSwitches.claimOn(flag);
            assertNotNull(flag, claim);
            PatchFamily family;
            try {
                family = PatchFamily.valueOf(claim.family);
            } catch (IllegalArgumentException unknown) {
                wrong.add(flag + ": no family " + claim.family);
                continue;
            }
            if (!family.patchName.equals(claim.patchName)) wrong.add(flag + ": " + claim.patchName + " isn't " + family.patchName);
            if (family.switches.isEmpty()) wrong.add(flag + ": " + family.patchName + " has no switch to turn it off with");
        }
        assertEquals(new ArrayList<String>(), wrong);
    }

    /**
     * Each claimed flag answers off while its family's switch is on, and Tumblr's own answer while
     * it's off, while the family isn't in the build, and whenever Tumblr's answer is already off.
     */
    @Test
    public void everyClaimedFlagFollowsItsFamilysSwitch() {
        List<String> wrong = new ArrayList<>();
        try {
            PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
            for (String flag : FeatureSwitches.claimedFlags()) {
                PatchFamily family = PatchFamily.valueOf(FeatureSwitches.claimOn(flag).family);
                for (BooleanSetting setting : family.switches) setting.save(true);
                if (FeatureSwitches.isEnabled(flag(flag), true)) wrong.add(flag + " stayed on with its switch on");
                if (FeatureSwitches.isEnabled(flag(flag), false)) wrong.add(flag + " turned on");
                for (BooleanSetting setting : family.switches) setting.save(false);
                if (!FeatureSwitches.isEnabled(flag(flag), true)) wrong.add(flag + " went off with its switch off");
                for (BooleanSetting setting : family.switches) setting.save(true);
                PatchFamilyForTests.inBuild(EnumSet.complementOf(EnumSet.of(family)));
                if (!FeatureSwitches.isEnabled(flag(flag), true)) wrong.add(flag + " went off without its patch");
                PatchFamilyForTests.inBuild(EnumSet.allOf(PatchFamily.class));
                for (BooleanSetting setting : family.switches) setting.resetToDefault();
            }
        } finally {
            PatchFamilyForTests.inBuild(null);
        }
        assertEquals(new ArrayList<String>(), wrong);
    }

    /**
     * A claimed flag asked before the settings are ready keeps Tumblr's answer and is counted under
     * its family, so a read that comes too early for the switch shows in the report. An answer
     * that's already off has nothing to turn off and isn't counted.
     */
    @Test
    public void aClaimedFlagAskedBeforeTheSettingsIsCounted() {
        FeatureSwitches.Claim claim = FeatureSwitches.claimOn("LIVERAMP_ATS");
        SettingsContextRule.withoutContext(() -> {
            assertFalse(FeatureSwitches.isEnabled(flag("LIVERAMP_ATS"), false));
            assertTrue(FeatureSwitches.isEnabled(flag("LIVERAMP_ATS"), true));
        });
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains(claim.patchName));
        assertTrue(report, report.contains(FeatureSwitches.READ_EARLY + " 1"));
    }

    /** The probes reach a flag through {@link FakeFeature}, so it needs one constant per claimed flag. */
    @Test
    public void everyClaimedFlagHasAStandIn() {
        List<String> missing = new ArrayList<>();
        for (String flag : FeatureSwitches.claimedFlags()) {
            try {
                flag(flag);
            } catch (IllegalArgumentException absent) {
                missing.add(flag);
            }
        }
        assertEquals("add these to FakeFeature", new ArrayList<String>(), missing);
    }
}
