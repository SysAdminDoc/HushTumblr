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
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

@Category(FixtureTests::class)
class AppFontFixtureTest {
    private val flags = listOf("appFont", "fontChoice")

    private fun holdsAFontName(method: Method): Boolean =
        method.strings().any { it == FONT_LOAD_FAILURE || it == MEDIUM_ROBOTO }

    /** The classes holding the two font names, and the loader's callback, which holds neither. */
    private fun hosts(build: File): List<ClassDef> {
        val named = FixtureDex.classesWhere(build, { true }, ::holdsAFontName)
        val callback = named.flatMap { it.methods }
            .single { FONT_LOAD_FAILURE in it.strings() && it.parameterTypes.size == 8 }
            .parameterTypes[4].toString()
        return named + FixtureDex.classesWhere(build, { true }) { it.definingClass == callback }
    }

    private fun opcodes(method: Method): List<Opcode> = method.instructionList().map { it.opcode }.filter { it != Opcode.NOP }

    @Test fun `the loader asks first, the picker's two answers go through the extension, and nothing else moves`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val plan = context.resolveFontPlan()
            val loader = opcodes(plan.load)
            val picker = opcodes(plan.picker)
            val others = classes.flatMap { it.methods }
                .filter { it.toString() != plan.load.toString() && it.toString() != plan.picker.toString() }
                .associate { it.toString() to opcodes(it) }

            appFontPatch.execute(context)

            val loading = plan.load.instructionList()
            assertEquals(build.name, FONT_REPLACEMENT_HOOK, loading[0].methodRef().toString())
            val registers = plan.load.implementation!!.registerCount
            val first = registers - 8
            assertEquals("${build.name}: the context and the id", listOf(first, first + 1), loading[0].namedRegisters())
            assertEquals(build.name, Opcode.MOVE_RESULT_OBJECT, loading[1].opcode)
            val face = (loading[1] as OneRegisterInstruction).registerA
            assertEquals(build.name, Opcode.IF_EQZ, loading[2].opcode)
            assertEquals(build.name, face, (loading[2] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.IF_EQZ, loading[3].opcode)
            assertEquals("${build.name}: the callback", first + 4, (loading[3] as OneRegisterInstruction).registerA)
            assertEquals(build.name, plan.deliver, loading[4].methodRef().toString())
            assertEquals("${build.name}: the callback, the face and the handler", listOf(first + 4, face, first + 5), loading[4].namedRegisters())
            assertEquals(build.name, Opcode.RETURN_OBJECT, loading[5].opcode)
            assertEquals(build.name, face, (loading[5] as OneRegisterInstruction).registerA)
            assertEquals("${build.name}: the loader's own steps", loader, opcodes(plan.load).drop(6))

            val picking = plan.picker.instructionList()
            val asks = picking.indices.filter { picking[it].methodRef()?.toString() == FONT_PROVIDED_HOOK }
            assertEquals("${build.name}: both Roboto answers", 2, asks.size)
            for (at in asks) {
                val given = picking[at].namedRegisters().single()
                assertEquals(build.name, Opcode.MOVE_RESULT_OBJECT, picking[at + 1].opcode)
                assertEquals(build.name, given, (picking[at + 1] as OneRegisterInstruction).registerA)
                assertEquals(build.name, Opcode.RETURN_OBJECT, picking[at + 2].opcode)
                assertEquals(build.name, given, (picking[at + 2] as OneRegisterInstruction).registerA)
            }
            assertEquals("${build.name}: the picker's own steps", picker, picking.filterIndexed { at, _ ->
                at !in asks && at - 1 !in asks
            }.map { it.opcode }.filter { it != Opcode.NOP })

            for (owner in classes) for (method in context.mutableClassDefBy(owner.type).methods) {
                val kept = others[method.toString()] ?: continue
                assertEquals("${build.name}: keep $method", kept, opcodes(method))
            }
            assertFlags(context, 1)
        }
    }

    @Test fun `a picker that doesn't answer right after choosing Roboto refuses before any edit`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + hosts(build))
            val plan = context.resolveFontPlan()
            val at = plan.answers.first().first
            plan.picker.replaceInstruction(at, "return-void")
            val loader = opcodes(plan.load)
            val picker = opcodes(plan.picker)
            assertThrows(PatchException::class.java) { appFontPatch.execute(context) }
            assertEquals(loader, opcodes(plan.load))
            assertEquals(picker, opcodes(plan.picker))
            assertFlags(context, 0)
        }
    }

    @Test fun `a method that only mentions the failure message can't stand in for the loader`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = hosts(build)
            assertTrue(build.name, classes.flatMap { it.methods }.count { FONT_LOAD_FAILURE in it.strings() } >= 1)
            val context = PatchContexts.of(ExtensionDex.classes() + classes.filter { owner ->
                owner.methods.none { FONT_LOAD_FAILURE in it.strings() }
            })
            assertThrows(PatchException::class.java) { appFontPatch.execute(context) }
            assertFlags(context, 0)
        }
    }

    private fun assertFlags(context: BytecodePatchContext, expected: Int) {
        for (flag in flags) assertEquals(flag, expected, (context.mutableClassDefBy(SETTINGS_STATUS).methods.single {
            it.name == flag }.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
    }
}
