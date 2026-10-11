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
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The dashboard's host fragment, which sets up its top bar's TumblrMart button. Kept by name. */
private const val DASHBOARD_HOST = "Lcom/tumblr/tabbeddashboard/fragments/TabbedDashboardHostFragment;"

/** The notifications screen, which has a TumblrMart button of its own. Kept by name. */
private const val NOTIFICATIONS = "Lcom/tumblr/ui/fragment/notification/NotificationFragment;"

/**
 * The diamond's drawer and every TumblrMart button lookup in each declared build. The drawer gets
 * the question in front of its first instruction, with its two views, and returns at once on true.
 * Each TumblrMart button gets the hook right after Tumblr sets its visibility, with the button's
 * own register. Tumblr's code is otherwise unchanged, and the patch turns its switch and both
 * targets on.
 */
@Category(FixtureTests::class)
class StoreButtonsFixtureTest {
    @Test
    fun `each declared build hides the diamond and every TumblrMart button when asked to`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                DIAMOND_CLICKED in method.strings() || method.instructionList().any { it.namesTumblrMart() }
            }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val drawer = context.resolveDiamondDrawer()
            val drawerId = "${drawer.definingClass}->${drawer.name}"
            val drawerBefore = drawer.instructionList().map { it.opcode }
            val views = listOf(drawer.parameterRegisterNumber(0), drawer.parameterRegisterNumber(1))
            val sites = context.resolveTumblrMartSites()
            val owners = sites.map { it.method.definingClass }
            assertTrue("${build.name}: the dashboard's top bar is one of them, $owners", DASHBOARD_HOST in owners)
            assertTrue("${build.name}: notifications is one of them, $owners", NOTIFICATIONS in owners)
            val rooms = context.resolveSearchBarRooms()
            assertEquals("${build.name}: TumblrMart's room, then the diamond's",
                listOf(TUMBLRMART_ROOM_HOOK, DIAMOND_ROOM_HOOK), rooms.map { it.hook })
            assertTrue("${build.name}: the rooms are in a header that shows a TumblrMart button",
                rooms.all { room -> sites.any { it.method.key() == room.method.key() } })
            val before = sites.associate { it.method.key() to it.method.instructionList().map { i -> i.opcode } }

            hideTumblrMartPatch.execute(context)

            val drawn = context.mutableClassDefBy(drawer.definingClass).methods
                .single { it.name == drawer.name && it.parameterTypes == drawer.parameterTypes }
                .instructionList()
            assertEquals("$drawerId: four instructions added", drawerBefore.size + 4, drawn.size)
            assertEquals(drawerId, Opcode.INVOKE_STATIC_RANGE, drawn[0].opcode)
            assertEquals("$drawerId: the extension is asked", HIDE_DIAMOND_HOOK, drawn[0].call())
            assertEquals("$drawerId: with the diamond and its dot", views, drawn[0].namedRegisters())
            assertEquals(drawerId, Opcode.MOVE_RESULT, drawn[1].opcode)
            assertEquals(drawerId, Opcode.IF_EQZ, drawn[2].opcode)
            assertEquals("$drawerId: the answer is what's tested",
                (drawn[1] as OneRegisterInstruction).registerA, (drawn[2] as OneRegisterInstruction).registerA)
            assertEquals("$drawerId: true returns at once", Opcode.RETURN_VOID, drawn[3].opcode)
            assertEquals("$drawerId: what follows is Tumblr's own code", drawerBefore, drawn.drop(4).map { it.opcode })

            for ((key, sitesHere) in sites.groupBy { it.method.key() }) {
                val method = sitesHere.first().method
                val roomsHere = rooms.filter { it.method.key() == key }
                val body = context.mutableClassDefBy(method.definingClass).methods
                    .single { it.name == method.name && it.parameterTypes == method.parameterTypes }
                    .instructionList()
                assertEquals("$key: one call per button, two instructions per room",
                    before.getValue(key).size + sitesHere.size + 2 * roomsHere.size, body.size)
                // Each earlier hook in the same method moves a later one down by what it added.
                fun moved(at: Int) = at + sitesHere.count { it.at < at } + 2 * roomsHere.count { it.at < at }
                val added = mutableSetOf<Int>()
                for (site in sitesHere) {
                    val at = moved(site.at)
                    assertEquals("$key: the hook follows the visibility call", "setVisibility", body[at - 1].methodRef()?.name)
                    assertEquals(key, site.register, body[at - 1].namedRegisters().first())
                    assertEquals(key, Opcode.INVOKE_STATIC_RANGE, body[at].opcode)
                    assertEquals("$key: the extension is told", HIDE_TUMBLRMART_HOOK, body[at].call())
                    assertEquals("$key: about the button", listOf(site.register), body[at].namedRegisters())
                    added += at
                }
                for (room in roomsHere) {
                    val at = moved(room.at)
                    assertEquals("$key: the room hook follows the room's field store", Opcode.IPUT, body[at - 1].opcode)
                    assertEquals(key, room.register, body[at - 1].namedRegisters().first())
                    assertEquals("$key: the extension is asked", room.hook, body[at].call())
                    assertEquals("$key: about the room", listOf(room.register), body[at].namedRegisters())
                    assertEquals("$key: its answer is the room", Opcode.MOVE_RESULT, body[at + 1].opcode)
                    assertEquals(key, room.register, (body[at + 1] as OneRegisterInstruction).registerA)
                    added += listOf(at, at + 1)
                }
                assertEquals("$key: the rest is Tumblr's own code", before.getValue(key),
                    body.filterIndexed { at, _ -> at !in added }.map { it.opcode })
            }
            for (flag in listOf("hideTumblrMart", "premiumDiamond", "tumblrMartButtons")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    private fun Instruction.namesTumblrMart(): Boolean =
        fieldRef()?.let { it.definingClass == VIEW_IDS && it.name in TUMBLRMART_IDS } == true

    private fun Instruction.call(): String? = methodRef()?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    private fun Method.key(): String =
        "$definingClass->$name(${parameterTypes.joinToString("")})"
}
