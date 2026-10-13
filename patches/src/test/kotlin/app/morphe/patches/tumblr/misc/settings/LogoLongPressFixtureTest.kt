/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.misc.settings

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.ui.DIAMOND_CLICKED
import app.morphe.patches.tumblr.ui.TUMBLRMART_IDS
import app.morphe.patches.tumblr.ui.VIEW_IDS
import app.morphe.patches.tumblr.ui.hideTumblrMartPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The dashboard's Tumblr logo in each declared build: one place outside the logo's class gives it
 * its tap, and the logo goes to the extension right after, in the register the tap went to, with
 * Tumblr's own code carrying on unchanged.
 */
@Category(FixtureTests::class)
class LogoLongPressFixtureTest {
    @Test
    fun `each declared build hands its dashboard logo to the extension right after Tumblr taps it`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                method.definingClass != LOGO_VIEW && method.instructionList().any { it.methodRef()?.descriptor() == TAP_CALL }
            }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val site = context.dashboardLogoSites().single()
            assertEquals("${build.name}: the dashboard's host fragment taps the logo",
                "Lcom/tumblr/tabbeddashboard/fragments/TabbedDashboardHostFragment;", site.method.definingClass)
            val before = site.method.instructionList()

            assertTrue("${build.name}: the hook goes in", context.hookLogoLongPress())

            val body = context.mutableClassDefBy(site.method.definingClass).methods
                .single { it.name == site.method.name && it.parameterTypes == site.method.parameterTypes }
                .instructionList()
            assertEquals("${build.name}: one instruction added", before.size + 1, body.size)
            val tap = body[site.index - 1]
            assertEquals("${build.name}: Tumblr's tap comes first", TAP_CALL, tap.methodRef()?.descriptor())
            val hook = body[site.index]
            assertEquals(build.name, Opcode.INVOKE_STATIC_RANGE, hook.opcode)
            assertEquals("${build.name}: the extension gets the logo", DASHBOARD_LOGO_HOOK, hook.methodRef()?.descriptor())
            val range = hook as RegisterRangeInstruction
            assertEquals("${build.name}: the hook reads the view the tap went to",
                (tap as FiveRegisterInstruction).registerC, range.startRegister)
            assertEquals(build.name, 1, range.registerCount)
            assertEquals("${build.name}: then Tumblr's own code carries on",
                before.drop(site.index).map { it.opcode }, body.drop(site.index + 1).map { it.opcode })
        }
    }

    /**
     * Hide the diamond and TumblrMart is on by default and puts its own hook into the same top bar
     * bind, before the logo's tap. With that hook in first, the logo's still lands right after the
     * tap, on the tap's register, rather than at the place the stock method had.
     */
    @Test
    fun `the logo's hook still follows its tap when the TumblrMart hook went into the same bind first`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                DIAMOND_CLICKED in method.strings() || method.instructionList().any { instruction ->
                    instruction.methodRef()?.descriptor() == TAP_CALL ||
                        instruction.fieldRef()?.let { it.definingClass == VIEW_IDS && it.name in TUMBLRMART_IDS } == true
                }
            }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val stock = context.dashboardLogoSites().single()
            val stockSize = stock.method.instructionList().size

            hideTumblrMartPatch.execute(context)
            assertTrue("${build.name}: the hook goes in", context.hookLogoLongPress())

            val body = context.mutableClassDefBy(stock.method.definingClass).methods
                .single { it.name == stock.method.name && it.parameterTypes == stock.method.parameterTypes }
                .instructionList()
            assertEquals("${build.name}: TumblrMart's hook and the logo's", stockSize + 2, body.size)
            val tapAt = body.indexOfFirst { it.methodRef()?.descriptor() == TAP_CALL }
            assertTrue("${build.name}: the TumblrMart hook went in before the tap", tapAt > stock.index - 1)
            val hook = body[tapAt + 1]
            assertEquals("${build.name}: the extension gets the logo right after its tap", DASHBOARD_LOGO_HOOK, hook.methodRef()?.descriptor())
            assertEquals("${build.name}: the hook reads the view the tap went to",
                (body[tapAt] as FiveRegisterInstruction).registerC, (hook as RegisterRangeInstruction).startRegister)
        }
    }

    private companion object {
        const val TAP_CALL = "$LOGO_VIEW->setOnClickListener(Landroid/view/View\$OnClickListener;)V"
    }
}
