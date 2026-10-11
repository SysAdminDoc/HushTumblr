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
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
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
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/**
 * The footer's count formatter and each of its four buttons in each declared build. The formatter
 * gets the extension's count in place of its own first thing; each button gets the question in
 * front of its first instruction and returns at once on true. Tumblr's code is otherwise unchanged,
 * and each patch turns its switch and its target on.
 */
@Category(FixtureTests::class)
class PostFooterFixtureTest {
    @Test
    fun `each declared build hands the count formatter the extension's count`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = footerContext(build)
            val formatter = context.resolveCountFormatter()
            val identity = "${formatter.definingClass}->${formatter.name}"
            val before = formatter.instructionList().map { it.opcode }
            val count = formatter.parameterRegisterNumber(0)

            hideNoteCountsPatch.execute(context)

            val body = context.bodyOf(formatter)
            assertEquals("$identity: two instructions added", before.size + 2, body.size)
            assertEquals(identity, Opcode.INVOKE_STATIC, body[0].opcode)
            assertEquals("$identity: the extension is asked", NOTE_COUNT_HOOK, body[0].call())
            assertEquals("$identity: with the count", listOf(count), body[0].namedRegisters())
            assertEquals(identity, Opcode.MOVE_RESULT, body[1].opcode)
            assertEquals("$identity: its answer is the count", count, (body[1] as OneRegisterInstruction).registerA)
            assertEquals("$identity: what follows is Tumblr's own code", before, body.drop(2).map { it.opcode })
            assertSwitchedOn(context, build.name, "hideNoteCounts", "noteCounts")
        }
    }

    @Test
    fun `each declared build returns from the like button first thing when asked to`() =
        checkButton(hideLikeButtonPatch, LIKE_CONTROL, HIDE_LIKE_HOOK, "hideLikeButton", "likeButton")

    @Test
    fun `each declared build returns from the reblog button first thing when asked to`() =
        checkButton(hideReblogButtonPatch, REBLOG_CONTROL, HIDE_REBLOG_HOOK, "hideReblogButton", "reblogButton")

    @Test
    fun `each declared build returns from the comment button first thing when asked to`() =
        checkButton(hideCommentButtonPatch, COMMENT_CONTROL, HIDE_COMMENT_HOOK, "hideCommentButton", "commentButton")

    @Test
    fun `each declared build returns from the share button first thing when asked to`() =
        checkButton(hideShareButtonPatch, SHARE_CONTROL, HIDE_SHARE_HOOK, "hideShareButton", "shareButton")

    /**
     * Applies [patch] to each declared build and checks the button Compose calls [control] asks
     * [hook] first and returns at once on true, and that [status] and [capability] are on.
     */
    private fun checkButton(patch: BytecodePatch, control: String, hook: String, status: String, capability: String) {
        for (build in Fixtures.declaredBuilds()) {
            val context = footerContext(build)
            val button = context.resolveFooterButton("test", control, hook)
            val identity = "${button.definingClass}->${button.name}"
            val before = button.instructionList().map { it.opcode }

            patch.execute(context)

            val body = context.bodyOf(button)
            assertEquals("$identity: four instructions added", before.size + 4, body.size)
            assertEquals(identity, Opcode.INVOKE_STATIC, body[0].opcode)
            assertEquals("$identity: the extension is asked", hook, body[0].call())
            assertEquals(identity, Opcode.MOVE_RESULT, body[1].opcode)
            assertEquals(identity, Opcode.IF_EQZ, body[2].opcode)
            assertEquals("$identity: the answer is what's tested",
                (body[1] as OneRegisterInstruction).registerA, (body[2] as OneRegisterInstruction).registerA)
            assertEquals("$identity: true returns at once", Opcode.RETURN_VOID, body[3].opcode)
            assertEquals("$identity: what follows is Tumblr's own code", before, body.drop(4).map { it.opcode })
            assertSwitchedOn(context, build.name, status, capability)
        }
    }

    /**
     * The footer's controls, found by their source notes, and every class with a static method
     * turning an int into a String, which takes in the class that builds the buttons' states.
     */
    private fun footerContext(build: File): BytecodePatchContext {
        val classes = FixtureDex.classesWhere(build, { true }) { method ->
            method.strings().any { it.startsWith(FOOTER_CONTROLS) } ||
                (method.isStatic() && method.returnType == "Ljava/lang/String;" &&
                    method.parameterTypes.map { it.toString() } == listOf("I"))
        }
        return PatchContexts.of(ExtensionDex.classes() + classes)
    }

    private fun BytecodePatchContext.bodyOf(method: Method): List<Instruction> =
        mutableClassDefBy(method.definingClass).methods
            .single { it.name == method.name && it.parameterTypes == method.parameterTypes }
            .instructionList()

    private fun Instruction.call(): String? = methodRef()?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    /** Each of [flags] in the status class now answers true. */
    private fun assertSwitchedOn(context: BytecodePatchContext, build: String, vararg flags: String) {
        for (flag in flags) {
            val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                .implementation!!.instructions.first()
            assertEquals("$build: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
        }
    }
}
