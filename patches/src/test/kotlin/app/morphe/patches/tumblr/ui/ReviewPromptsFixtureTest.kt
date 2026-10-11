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
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The rating prompt's launcher in each declared build, and the question put in front of its first
 * instruction: on true it returns before Tumblr asks its prompt rules, and on false Tumblr's own
 * code runs unchanged. The patch then turns its switch and its target on.
 */
@Category(FixtureTests::class)
class ReviewPromptsFixtureTest {
    @Test
    fun `each declared build returns from the rating prompt launcher first thing when asked to`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { REVIEW_LAST_SHOWN in it.strings() }
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val launcher = context.resolveReviewPromptLauncher()
            val identity = "${launcher.definingClass}->${launcher.name}"
            val before = launcher.instructionList().map { it.opcode }

            skipReviewPromptsPatch.execute(context)

            val body = context.mutableClassDefBy(launcher.definingClass).methods
                .single { it.name == launcher.name && it.parameterTypes == launcher.parameterTypes }
                .instructionList()
            assertEquals("$identity: four instructions added", before.size + 4, body.size)
            assertEquals(identity, Opcode.INVOKE_STATIC, body[0].opcode)
            assertEquals("$identity: the extension is asked", REVIEW_PROMPTS_HOOK, body[0].methodRef()?.let {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" })
            assertEquals(identity, Opcode.MOVE_RESULT, body[1].opcode)
            assertEquals(identity, Opcode.IF_EQZ, body[2].opcode)
            assertEquals("$identity: the answer is what's tested",
                (body[1] as OneRegisterInstruction).registerA, (body[2] as OneRegisterInstruction).registerA)
            assertEquals("$identity: true returns at once", Opcode.RETURN_VOID, body[3].opcode)
            assertEquals("$identity: what follows is Tumblr's own code", before, body.drop(4).map { it.opcode })
            for (flag in listOf("skipReviewPrompts", "reviewPrompt")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
