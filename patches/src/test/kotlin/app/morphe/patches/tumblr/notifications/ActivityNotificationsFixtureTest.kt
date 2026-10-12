/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.notifications

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(FixtureTests::class)
class ActivityNotificationsFixtureTest {
    @Test
    fun `a missing response refuses before adding the interface bridge or enabling either build fact`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classes(build, setOf(ACTIVITY_MODEL, ACTIVITY_TYPE))
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            assertThrows(PatchException::class.java) { filterActivityNotificationsPatch.execute(context) }
            val native = context.mutableClassDefBy(ACTIVITY_MODEL)
            assertFalse(HUSH_ACTIVITY in native.interfaces)
            assertFalse(native.methods.any { it.name == "hushNotificationType" })
            for (status in listOf("filterActivityNotifications", "activityNotificationList")) {
                val enabled = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == status }
                assertEquals(0, (enabled.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    @Test
    fun `the activity list is filtered while its pagination and the separate push response stay intact`() {
        for (build in Fixtures.declaredBuilds()) {
            val push = "Lcom/tumblr/rumblr/response/NotificationsResponse;"
            val classes = FixtureDex.classes(build, setOf(ACTIVITY_RESPONSE, ACTIVITY_MODEL, ACTIVITY_TYPE, push))
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            val original = classes.getValue(ACTIVITY_RESPONSE).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 2
            }
            filterActivityNotificationsPatch.execute(context)
            val patched = context.mutableClassDefBy(ACTIVITY_RESPONSE).methods.single {
                it.name == "<init>" && it.parameterTypes.size == 2
            }.instructionList()
            assertEquals(Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
            assertEquals(ACTIVITY_FILTER_HOOK, patched[0].methodRef().toString())
            assertEquals(1, (patched[0] as RegisterRangeInstruction).startRegister)
            assertEquals(1, (patched[0] as RegisterRangeInstruction).registerCount)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, patched[1].opcode)
            assertEquals(1, (patched[1] as OneRegisterInstruction).registerA)
            assertEquals(original.instructionList().map { it.opcode }, patched.drop(2).map { it.opcode })
            val pagination = patched.single { it.fieldRef()?.name == "links" }
            assertEquals(2, (pagination as TwoRegisterInstruction).registerA)
            assertEquals("Lcom/tumblr/rumblr/model/PaginationLink;", pagination.fieldRef()!!.type)
            for (method in context.mutableClassDefBy(push).methods) {
                assertFalse("${build.name}: push response remains unfiltered", method.instructionList().any {
                    it.methodRef()?.definingClass == ACTIVITY_FILTER
                })
            }
            for (status in listOf("filterActivityNotifications", "activityNotificationList")) {
                val enabled = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == status }
                assertEquals(1, (enabled.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    @Test
    fun `one nullable enum-name default on the native interface serves every activity implementation`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classes(build, setOf(ACTIVITY_RESPONSE, ACTIVITY_MODEL, ACTIVITY_TYPE))
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            filterActivityNotificationsPatch.execute(context)
            val native = context.mutableClassDefBy(ACTIVITY_MODEL)
            assertTrue(HUSH_ACTIVITY in native.interfaces)
            val bridge = native.methods.single { it.name == "hushNotificationType" }
            assertTrue(AccessFlags.PUBLIC.isSet(bridge.accessFlags))
            assertFalse(AccessFlags.ABSTRACT.isSet(bridge.accessFlags))
            val body = bridge.instructionList()
            assertEquals(listOf(Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4,
                Opcode.RETURN_OBJECT), body.map { it.opcode })
            assertEquals("$ACTIVITY_MODEL->getType()$ACTIVITY_TYPE", body[0].methodRef().toString())
            assertEquals("Ljava/lang/Enum;->name()Ljava/lang/String;", body[3].methodRef().toString())
            val absentAddress = body.take(6).sumOf { it.codeUnits }
            assertEquals(absentAddress, body.take(2).sumOf { it.codeUnits } + (body[2] as OffsetInstruction).codeOffset)
            assertEquals(0, (body[6] as NarrowLiteralInstruction).narrowLiteral)
        }
    }
}
