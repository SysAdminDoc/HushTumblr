/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The bottom bar hooks in each declared build: Tumblr's tab list goes to the extension right before
 * it's frozen, on the list it built, and the bar right after its controller is built, on the view
 * the controller was given. Tumblr's own code around each call is unchanged, and each of the three
 * patches turns on its own switch and target.
 */
@Category(FixtureTests::class)
class BottomBarTabsFixtureTest {
    @Test
    fun `each declared build hands the tab list and the bar to the extension`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                method.instructionList().any { instruction ->
                    instruction.isDashboardBadge() || instruction.fieldRef()?.name.let { it == "Dashboard" || it == "top_nav_bar" }
                }
            }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val hooks = context.resolveBottomBarTabs()
            val list = "${hooks.tabList.definingClass}->${hooks.tabList.name}"
            val bar = "${hooks.bar.definingClass}->${hooks.bar.name}"
            val listBefore = hooks.tabList.instructionList().map { it.opcode }
            val barBefore = hooks.bar.instructionList().map { it.opcode }

            bottomBarTabsHookPatch.execute(context)

            val listBody = context.mutableClassDefBy(hooks.tabList.definingClass).methods
                .single { it.name == hooks.tabList.name && it.parameterTypes == hooks.tabList.parameterTypes }
                .instructionList()
            assertEquals("$list: one call added", listBefore.size + 1, listBody.size)
            val arranged = listBody[hooks.tabListIndex]
            assertEquals(list, ARRANGE_TABS_HOOK, arranged.descriptor())
            assertEquals("$list: on the list it built", listOf(hooks.tabListRegister), arranged.namedRegisters())
            assertEquals("$list: right before the copy", Opcode.INVOKE_STATIC, listBody[hooks.tabListIndex + 1].opcode)
            assertEquals("$list: of the same list", listOf(hooks.tabListRegister), listBody[hooks.tabListIndex + 1].namedRegisters())
            assertEquals(list, Opcode.RETURN_OBJECT, listBody.last().opcode)
            assertEquals("$list: Tumblr's own code around it", listBefore,
                listBody.filterIndexed { index, _ -> index != hooks.tabListIndex }.map { it.opcode })

            val barBody = context.mutableClassDefBy(hooks.bar.definingClass).methods
                .single { it.name == hooks.bar.name && it.parameterTypes == hooks.bar.parameterTypes }
                .instructionList()
            assertEquals("$bar: one call added", barBefore.size + 1, barBody.size)
            assertEquals("$bar: right after the controller is built", "<init>", barBody[hooks.barIndex - 1].methodRef()?.name)
            val built = barBody[hooks.barIndex]
            assertEquals(bar, ARRANGE_BAR_HOOK, built.descriptor())
            assertEquals("$bar: on the controller's view", listOf(barBody[hooks.barIndex - 1].namedRegisters()[1]),
                built.namedRegisters())
            assertEquals("$bar: Tumblr's own code around it", barBefore,
                barBody.filterIndexed { index, _ -> index != hooks.barIndex }.map { it.opcode })

            for ((patch, flags) in listOf(
                hideExploreTabPatch to listOf("hideExploreTab", "exploreTab"),
                hideNotificationsTabPatch to listOf("hideNotificationsTab", "notificationsTab"),
                reorderBottomBarPatch to listOf("reorderTabs", "tabOrder"),
            )) {
                patch.execute(context)
                for (flag in flags) {
                    val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                        .implementation!!.instructions.first()
                    assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
                }
            }
        }
    }

    private fun Instruction.descriptor(): String? = methodRef()?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }
}
