/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.hushtumblr.extension.tumblr.flags;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import app.hushtumblr.extension.shared.Utils;
import app.hushtumblr.extension.shared.diagnostics.HookStatus;
import app.hushtumblr.extension.shared.settings.BooleanSetting;
import app.hushtumblr.extension.tumblr.settings.FamilyNames;
import app.hushtumblr.extension.tumblr.settings.PatchFamily;

/**
 * Turns some of Tumblr's own features off by answering its feature check.
 *
 * <p>Every boolean feature check in Tumblr ends in one method: it reads the server's value for the
 * flag, compares it with "true", logs the answer and returns it. The patch puts {@link #isEnabled}
 * right after that comparison, so a family whose switch is on can answer off for the flags it
 * claims here. Tumblr keeps the first answer for each flag until it restarts, so a switch that
 * works this way says "Restart Tumblr to see the change."
 *
 * <p>Tumblr checks flags while it starts, before HushTumblr has a context. Nothing here loads the
 * settings until {@link Utils#settingsReady()} says it may: the table names each family by its
 * {@link PatchFamily} constant and its patch name, both plain text, and until then every flag gets
 * Tumblr's own answer.
 */
public final class FeatureSwitches {
    private FeatureSwitches() {}

    /** The family that forces a flag off: its {@link PatchFamily} constant and its patch name. */
    static final class Claim {
        final String family;
        final String patchName;

        Claim(String family, String patchName) {
            this.family = family;
            this.patchName = patchName;
        }
    }

    /** Each claimed flag, by the constant name Tumblr's feature enum keeps for it. */
    private static final Map<String, Claim> CLAIMS = claims();

    private static Map<String, Claim> claims() {
        Map<String, Claim> claims = new HashMap<>();
        // Tumblr's own requests to its ad networks, and the pop-up and full-screen ads the timeline
        // filter can't see. Ads the server puts in a page come whatever these say.
        claim(claims, "HIDE_ADS", FamilyNames.HIDE_ADS,
                "MAKE_ADX_REQUESTS", "MAKE_APS_AD_REQUESTS", "MAKE_DISPLAY_IO_REQUEST",
                "MAKE_DISPLAY_IO_INTERSCROLLER_REQUEST", "MAKE_FAN_REQUESTS", "MAKE_NIMBUS_CLIENT_AD_REQUESTS",
                "GOOGLE_BANNER_ADS", "GOOGLE_NATIVE_ADS", "GOOGLE_REWARDED_ADS", "SMART_BANNER_ADS",
                "RENDER_NIMBUS_ADS", "RENDER_VUNGLE_ADS", "RENDER_DISPLAY_IO_INTERSCROLLER_S2S_ADS");
        // Tumblr's own tracking: LiveRamp's ID on its requests, Open Measurement and Moat ad
        // measurement, its log of which flags you were shown, and its network, performance and ad
        // supply logging. The two Moat flags have no reader in 46.5.0.109 and are here for the
        // builds that read them.
        claim(claims, "DISABLE_ANALYTICS", FamilyNames.DISABLE_ANALYTICS,
                "LIVERAMP_ATS", "ENABLE_OM_SDK", "MOAT_VIDEO_AD_BEACONING",
                "LITTLE_SISTER_MOAT_LOGGING", "SEND_FEATURE_EXPOSED_ANALYTICS",
                "SEND_NETWORK_USAGE_DATA", "MOBILE_PERFORMANCE_LOGGING", "SUPPLY_LOGGING");
        return Collections.unmodifiableMap(claims);
    }

    /** Adds [flags] to [claims] for one family. A flag claimed twice is a mistake in this table. */
    private static void claim(Map<String, Claim> claims, String family, String patchName, String... flags) {
        Claim claim = new Claim(family, patchName);
        for (String flag : flags) {
            if (claims.put(flag, claim) != null) throw new IllegalStateException(flag + " is claimed twice");
        }
    }

    /** Every claimed flag, sorted. */
    static Set<String> claimedFlags() {
        return Collections.unmodifiableSet(new TreeSet<>(CLAIMS.keySet()));
    }

    /** The claim on [flag], or null when no family claims it. */
    static Claim claimOn(String flag) {
        return CLAIMS.get(flag);
    }

    /**
     * Placed right after Tumblr's feature check compares a flag's server value with "true". Answers
     * false for a flag that a family in this build claims while that family's switch is on, and
     * Tumblr's own answer otherwise: for every other flag, before the settings are ready, while
     * paused, and when anything here fails. Never throws.
     */
    public static boolean isEnabled(Enum<?> feature, boolean server) {
        Claim claim = null;
        try {
            if (feature == null) return server;
            claim = CLAIMS.get(feature.name());
            if (claim == null || !Utils.settingsReady()) return server;
            // The hook is in every build with one flag-backed patch, so a claim of a family this
            // build leaves out is passed over before it's counted as that family's.
            PatchFamily family = PatchFamily.valueOf(claim.family);
            if (!family.inBuild()) return server;
            HookStatus.invoked(claim.patchName);
            if (!server || !anyOn(family)) return server;
            HookStatus.counted(claim.patchName, "Tumblr feature turned off");
            return false;
        } catch (Throwable failure) {
            // Only a claimed flag gets past the table lookup, so a failure always has a family.
            if (claim != null) HookStatus.threw(claim.patchName, "feature flag", failure);
            return server;
        }
    }

    /** Whether any of [family]'s switches answers on. Paused, every one answers off. */
    private static boolean anyOn(PatchFamily family) {
        for (BooleanSetting setting : family.switches) {
            if (setting.get()) return true;
        }
        return false;
    }
}
