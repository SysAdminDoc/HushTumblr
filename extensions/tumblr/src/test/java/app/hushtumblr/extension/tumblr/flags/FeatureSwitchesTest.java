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
import java.util.List;

import app.hushtumblr.extension.shared.SettingsContextRule;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;

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
