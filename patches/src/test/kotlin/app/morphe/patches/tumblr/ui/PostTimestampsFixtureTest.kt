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
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(FixtureTests::class)
class PostTimestampsFixtureTest {
    @Test
    fun `the native preference result passes through the option and its writer stays intact`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classes(build, setOf(TIMESTAMP_USER_INFO, TIMESTAMP_HEADER, "Lcom/tumblr/Remember;"))
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            val plan = context.resolvePostTimestamps()
            assertEquals("L", plan.method.name)
            assertEquals(0, plan.result)
            showPostTimestampsPatch.execute(context)
            val body = plan.method.instructionList()
            assertEquals(listOf(Opcode.CONST_STRING, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT,
                Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.RETURN), body.map { it.opcode })
            assertEquals(TIMESTAMP_PREFERENCE, body[0].stringRef())
            assertEquals("Lcom/tumblr/Remember;->c(Ljava/lang/String;Z)Z", body[2].methodRef().toString())
            assertEquals(POST_TIMESTAMPS_HOOK, body[4].methodRef().toString())
            assertEquals(0, (body[4] as RegisterRangeInstruction).startRegister)
            assertEquals(1, (body[4] as RegisterRangeInstruction).registerCount)
            assertEquals(0, (body[5] as OneRegisterInstruction).registerA)
            assertEquals(0, (body[6] as OneRegisterInstruction).registerA)
            val originalWriter = classes.getValue(TIMESTAMP_USER_INFO).methods.single { it.name == "x0" }
            val writer = context.mutableClassDefBy(TIMESTAMP_USER_INFO).methods.single { it.name == "x0" }
            assertEquals(originalWriter.instructionList().map { it.opcode }, writer.instructionList().map { it.opcode })
            assertEquals(originalWriter.instructionList().map { it.methodRef()?.toString() },
                writer.instructionList().map { it.methodRef()?.toString() })
            for (status in listOf("showPostTimestamps", "nativePostTimestamps")) {
                val enabled = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == status }
                assertEquals(1, (enabled.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    @Test
    fun `a missing native renderer refuses before the getter or either build fact changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classes(build, setOf(TIMESTAMP_USER_INFO, "Lcom/tumblr/Remember;"))
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            assertThrows(PatchException::class.java) { showPostTimestampsPatch.execute(context) }
            val getter = context.mutableClassDefBy(TIMESTAMP_USER_INFO).methods.single { it.name == "L" }
            assertEquals(5, getter.instructionList().size)
            for (status in listOf("showPostTimestamps", "nativePostTimestamps")) {
                val enabled = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == status }
                assertEquals(0, (enabled.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
