/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.flags

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patches.tumblr.ads.hideAdFreeBannerPatch
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.privacy.disableAnalyticsPatch
import app.morphe.patches.tumblr.ui.hideBlazePromptsPatch
import app.morphe.patches.tumblr.ui.hideCommunitiesTabPatch
import app.morphe.patches.tumblr.ui.hideTumblrTvPatch
import app.morphe.patches.tumblr.ui.turnOffScreenshotSharingPatch
import app.morphe.patches.tumblr.ui.disableUpdatePromptsPatch
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Each patch that works through Tumblr's feature flags, against each declared build: every flag the
 * test names is a constant of Tumblr's feature enum there and is claimed in the extension's table,
 * and the patch turns its switch and its target on. The hook itself is FeatureSwitchHookFixtureTest's.
 */
@Category(FixtureTests::class)
class FlagPatchesFixtureTest {
    @Test
    fun `Disable analytics claims tracking flags Tumblr has and turns its switch on`() =
        check(disableAnalyticsPatch, "disableAnalytics", "analyticsFlags", "LIVERAMP_ATS", "ENABLE_OM_SDK", "MOAT_VIDEO_AD_BEACONING", "LITTLE_SISTER_MOAT_LOGGING", "SEND_FEATURE_EXPOSED_ANALYTICS", "SEND_NETWORK_USAGE_DATA", "MOBILE_PERFORMANCE_LOGGING", "SUPPLY_LOGGING")

    @Test
    fun `Hide the ad-free banner claims a flag Tumblr has and turns its switch on`() =
        check(hideAdFreeBannerPatch, "hideAdFreeBanner", "adFreeBannerFlags", "AD_FREE_CTA_BANNER")

    @Test
    fun `Hide Tumblr TV claims flags Tumblr has and turns its switch on`() =
        check(hideTumblrTvPatch, "hideTumblrTv", "tumblrTvFlags", "TUMBLR_TV_MOBILE_NAV", "TUMBLR_TV_ONBOARDING_REDIRECT")

    /**
     * Only the post editor's tips. The tab's own flag, COMMUNITIES_TAB_BAR_ITEM, is left to Tumblr, and
     * BottomBarTabsFixtureTest holds why and checks the claim table doesn't name it.
     */
    @Test
    fun `Hide the Communities tab claims a flag Tumblr has and turns its switch on`() =
        check(hideCommunitiesTabPatch, "hideCommunitiesTab", "communitiesTabFlags", "COMMUNITIES_TOOLTIPS")

    @Test
    fun `Hide Blaze prompts claims flags Tumblr has and turns its switch on`() =
        check(hideBlazePromptsPatch, "hideBlazePrompts", "blazePromptFlags", "BLAZE_DASHBOARD_ENTRYPOINT_IN_USER_BLOG_HEADER", "BLAZE_DASHBOARD_ENTRYPOINT_NEAR_PREMIUM", "BLAZE_NUDGE_AFTER_POST", "SHOW_BLAZE_SETTINGS_BANNER", "TUMBLR_BLAZE_LANDING_PAGE_BANNER")

    @Test
    fun `Turn off screenshot sharing claims a flag Tumblr has and turns its switch on`() =
        check(turnOffScreenshotSharingPatch, "turnOffScreenshotSharing", "screenshotSharingFlags", "SCREENSHOT_INTERCEPTION")

    @Test
    fun `Disable update prompts claims a flag Tumblr has and turns its switch on`() =
        check(disableUpdatePromptsPatch, "disableUpdatePrompts", "updatePromptFlags", "IN_APP_UPDATE")

    /**
     * Applies [patch] to the extension and checks it flips the [status] and [capability] stubs, after
     * checking each of [flags] against the feature enum of each declared build and the extension's
     * claim table.
     */
    private fun check(patch: BytecodePatch, status: String, capability: String, vararg flags: String) {
        val claimed = ExtensionDex.classes().single { it.type == FEATURE_SWITCHES }.methods.flatMap { it.strings() }.toSet()
        for (build in Fixtures.declaredBuilds()) {
            val feature = FixtureDex.classesWhere(build, { true }) { method ->
                method.name == "<clinit>" && FEATURE_ENUM_TEXT in method.strings()
            }.single { it.superclass == "Ljava/lang/Enum;" }
            val constants = feature.staticFields.filter { it.type == feature.type }.map { it.name }.toSet()
            for (flag in flags) {
                assertTrue("${build.name}: $flag isn't a constant of Tumblr's feature enum", flag in constants)
                assertTrue("$flag isn't claimed in the extension's table", flag in claimed)
            }
            val context = PatchContexts.of(ExtensionDex.classes())
            patch.execute(context)
            for (flag in listOf(status, capability)) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
