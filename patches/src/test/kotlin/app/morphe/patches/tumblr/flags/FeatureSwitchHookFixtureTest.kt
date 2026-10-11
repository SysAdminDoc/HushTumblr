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
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.strings
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Tumblr's feature check in each declared build, and the hook put right after its comparison of
 * a flag's server value: one call on the feature and the answer, its result taken back into the
 * answer, then the exposure log and the return Tumblr already had.
 */
@Category(FixtureTests::class)
class FeatureSwitchHookFixtureTest {
    @Test
    fun `each declared build sends the feature check's answer through the hook before it is logged`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val feature = classes.single { owner -> owner.superclass == "Ljava/lang/Enum;" && owner.methods.any { FEATURE_ENUM_TEXT in it.strings() } }
            val companion = classes.single { owner -> owner.methods.any { FEATURE_CHECK_TEXT in it.strings() } }
            val original = companion.methods.single { !it.isStatic() && it.hasShape(listOf(feature.type, "Ljava/lang/String;"), "Z") }
                .instructionList()

            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            featureSwitchHookPatch.execute(context)

            val patched = context.mutableClassDefBy(companion.type).methods
                .single { !it.isStatic() && it.hasShape(listOf(feature.type, "Ljava/lang/String;"), "Z") }
            val body = patched.instructionList()
            val hooks = body.indices.filter { body[it].methodRef()?.let { call -> "${call.definingClass}->${call.name}(${call.parameterTypes.joinToString("")})${call.returnType}" } == FEATURE_SWITCH_HOOK }
            assertEquals("${build.name}: one hook in the feature check", 1, hooks.size)
            val hook = hooks.single()
            assertEquals("${build.name}: two instructions added", original.size + 2, body.size)
            assertEquals(build.name, Opcode.INVOKE_STATIC, body[hook - 2].opcode)
            assertTrue(build.name, body[hook - 2].methodRef()!!.hasShape(listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"), "Z"))
            assertEquals(build.name, Opcode.MOVE_RESULT, body[hook - 1].opcode)
            val answer = (body[hook - 1] as OneRegisterInstruction).registerA
            val call = body[hook] as FiveRegisterInstruction
            // (this, feature, server value) are the last three registers.
            val featureRegister = patched.implementation!!.registerCount - 2
            assertEquals("${build.name}: the hook gets the feature and the answer", listOf(featureRegister, answer),
                listOf(call.registerC, call.registerD).take(call.registerCount))
            assertEquals(build.name, Opcode.MOVE_RESULT, body[hook + 1].opcode)
            assertEquals(build.name, answer, (body[hook + 1] as OneRegisterInstruction).registerA)
            assertEquals("${build.name}: what comes before is Tumblr's own code", original.take(hook).map { it.opcode },
                body.take(hook).map { it.opcode })
            assertEquals("${build.name}: what follows is Tumblr's own code", original.drop(hook).map { it.opcode },
                body.drop(hook + 2).map { it.opcode })
        }
    }

    /** The feature enum and its companion, the two classes holding the texts the patch looks for. */
    private fun read(build: File): List<ClassDef> {
        val texts = setOf(FEATURE_ENUM_TEXT, FEATURE_CHECK_TEXT)
        val found = FixtureDex.classesWhere(build, { true }) { method -> method.strings().any { it in texts } }
        assertEquals("${build.name}: the feature enum and its check", 2, found.size)
        return found
    }
}
