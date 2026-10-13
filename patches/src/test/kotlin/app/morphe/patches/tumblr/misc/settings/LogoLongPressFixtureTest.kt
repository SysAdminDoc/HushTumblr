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
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
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
        val tapCall = "$LOGO_VIEW->setOnClickListener(Landroid/view/View\$OnClickListener;)V"
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                method.definingClass != LOGO_VIEW && method.instructionList().any { it.methodRef()?.descriptor() == tapCall }
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
            assertEquals("${build.name}: Tumblr's tap comes first", tapCall, tap.methodRef()?.descriptor())
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
}
