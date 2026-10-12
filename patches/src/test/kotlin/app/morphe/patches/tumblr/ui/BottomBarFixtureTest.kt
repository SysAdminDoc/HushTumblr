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
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The bottom bar's badge pop-in in each declared build. Its badge is swapped for the extension's
 * answer first thing, and Tumblr's own null check on it comes straight after, so a null answer
 * returns at once. The rest of Tumblr's code is unchanged, and the patch turns its switch and
 * target on. The bar looks up each tab's badge by the name the extension tells it apart by.
 */
@Category(FixtureTests::class)
class BottomBarFixtureTest {
    @Test
    fun `the extension tells the badges apart by the names the patch requires`() {
        assertEquals(UNREAD_BADGES, listOf("DASHBOARD", "COMMUNITIES", "NOTIFICATIONS").map {
            ExtensionDex.stringConstant(BOTTOM_BAR, it)
        })
    }

    @Test
    fun `each declared build hands the badge pop-in the extension's badge`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                method.instructionList().any { it.isDashboardBadge() }
            }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val popIn = context.resolveBadgePopIn()
            val identity = "${popIn.definingClass}->${popIn.name}"
            val before = popIn.instructionList().map { it.opcode }
            val badge = popIn.parameterRegisterNumber(0)
            val read = context.mutableClassDefBy(popIn.definingClass).methods
                .flatMap { method -> method.instructionList().mapNotNull { it.viewIdRead() } }
            assertTrue("$identity: the bar looks up each tab's badge, $read", read.containsAll(UNREAD_BADGES))

            hideUnreadDotsPatch.execute(context)

            val body = context.mutableClassDefBy(popIn.definingClass).methods
                .single { it.name == popIn.name && it.parameterTypes == popIn.parameterTypes }
                .instructionList()
            assertEquals("$identity: two instructions added", before.size + 2, body.size)
            assertEquals(identity, Opcode.INVOKE_STATIC, body[0].opcode)
            assertEquals("$identity: the extension is asked", UNREAD_BADGE_HOOK, body[0].methodRef()?.let {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" })
            assertEquals("$identity: about the badge", listOf(badge), body[0].namedRegisters())
            assertEquals(identity, Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
            assertEquals("$identity: its answer is the badge", badge, (body[1] as OneRegisterInstruction).registerA)
            assertEquals("$identity: Tumblr's null check reads it next", Opcode.IF_EQZ, body[2].opcode)
            assertEquals(identity, badge, (body[2] as OneRegisterInstruction).registerA)
            assertEquals("$identity: what follows is Tumblr's own code", before, body.drop(2).map { it.opcode })
            for (flag in listOf("hideUnreadDots", "unreadDots")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
