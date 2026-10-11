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
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.localRegisterCount
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
 * gets the extension's count in place of its own first thing, and the community comment text and
 * the notes title go to the extension as they're written; each button gets the question in front
 * of its first instruction and returns at once on true, and the like, comment and share buttons'
 * community controls go to the extension at the end of the community footer's bind. Tumblr's code
 * is otherwise unchanged, and each patch turns its switch and its target on.
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
            val texts = listOf(
                context.resolveCommunityCommentsText() to COMMUNITY_COMMENTS_TEXT_HOOK,
                context.resolveNotesTitle() to NOTES_TITLE_HOOK,
            )
            val textsBefore = texts.map { (site, _) -> site.method.instructionList().map { it.opcode } }

            hideNoteCountsPatch.execute(context)

            val body = context.bodyOf(formatter)
            assertEquals("$identity: two instructions added", before.size + 2, body.size)
            assertEquals(identity, Opcode.INVOKE_STATIC, body[0].opcode)
            assertEquals("$identity: the extension is asked", NOTE_COUNT_HOOK, body[0].call())
            assertEquals("$identity: with the count", listOf(count), body[0].namedRegisters())
            assertEquals(identity, Opcode.MOVE_RESULT, body[1].opcode)
            assertEquals("$identity: its answer is the count", count, (body[1] as OneRegisterInstruction).registerA)
            assertEquals("$identity: what follows is Tumblr's own code", before, body.drop(2).map { it.opcode })
            for ((index, pair) in texts.withIndex()) {
                val (site, hook) = pair
                val where = "${site.method.definingClass}->${site.method.name}"
                val text = context.bodyOf(site.method)
                assertEquals("$where: two instructions added", textsBefore[index].size + 2, text.size)
                assertEquals("$where: right after the text is written", Opcode.MOVE_RESULT_OBJECT, text[site.at - 1].opcode)
                assertEquals(where, site.register, (text[site.at - 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: the extension is handed the text", hook, text[site.at].call())
                assertEquals(where, listOf(site.register), text[site.at].namedRegisters())
                assertEquals("$where: its answer is the text", Opcode.MOVE_RESULT_OBJECT, text[site.at + 1].opcode)
                assertEquals(where, site.register, (text[site.at + 1] as OneRegisterInstruction).registerA)
                assertEquals("$where: Tumblr's own code around it", textsBefore[index],
                    text.filterIndexed { at, _ -> at != site.at && at != site.at + 1 }.map { it.opcode })
            }
            assertSwitchedOn(context, build.name, "hideNoteCounts", "noteCounts")
        }
    }

    @Test
    fun `each declared build returns from the like button first thing when asked to`() =
        checkButton(hideLikeButtonPatch, LIKE_CONTROL, HIDE_LIKE_HOOK, "hideLikeButton", "likeButton",
            HIDE_COMMUNITY_REACTIONS_HOOK to COMMUNITY_REACTION_CONTROLS)

    @Test
    fun `each declared build returns from the reblog button first thing when asked to`() =
        checkButton(hideReblogButtonPatch, REBLOG_CONTROL, HIDE_REBLOG_HOOK, "hideReblogButton", "reblogButton", null)

    @Test
    fun `each declared build returns from the comment button first thing when asked to`() =
        checkButton(hideCommentButtonPatch, COMMENT_CONTROL, HIDE_COMMENT_HOOK, "hideCommentButton", "commentButton",
            HIDE_COMMUNITY_COMMENTS_HOOK to COMMUNITY_COMMENT_CONTROLS)

    @Test
    fun `each declared build returns from the share button first thing when asked to`() =
        checkButton(hideShareButtonPatch, SHARE_CONTROL, HIDE_SHARE_HOOK, "hideShareButton", "shareButton",
            HIDE_COMMUNITY_SHARE_HOOK to COMMUNITY_SHARE_CONTROLS)

    @Test
    fun `each declared build hands the community footer to every button patch it has`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = footerContext(build)
            val site = context.resolveCommunityFooterBind("test", HIDE_COMMUNITY_SHARE_HOOK, COMMUNITY_SHARE_CONTROLS)
            val before = site.method.instructionList().map { it.opcode }

            hideLikeButtonPatch.execute(context)
            hideCommentButtonPatch.execute(context)
            hideShareButtonPatch.execute(context)

            val body = context.bodyOf(site.method)
            val where = "${site.method.definingClass}->${site.method.name}"
            assertEquals("$where: one call per patch", before.size + 3, body.size)
            assertEquals("$where: each in front of the return",
                listOf(HIDE_COMMUNITY_REACTIONS_HOOK, HIDE_COMMUNITY_COMMENTS_HOOK, HIDE_COMMUNITY_SHARE_HOOK),
                body.subList(site.at, site.at + 3).map { it.call() })
            assertEquals(where, Opcode.RETURN_VOID, body.last().opcode)
        }
    }

    /**
     * Applies [patch] to each declared build and checks the button Compose calls [control] asks
     * [hook] first and returns at once on true, that [community]'s hook, when the patch has one, goes
     * in front of the community footer bind's return with the footer, and that [status] and
     * [capability] are on.
     */
    private fun checkButton(
        patch: BytecodePatch,
        control: String,
        hook: String,
        status: String,
        capability: String,
        community: Pair<String, List<String>>?,
    ) {
        for (build in Fixtures.declaredBuilds()) {
            val context = footerContext(build)
            val button = context.resolveFooterButton("test", control, hook)
            val identity = "${button.definingClass}->${button.name}"
            val before = button.instructionList().map { it.opcode }
            val site = community?.let { (communityHook, controls) ->
                context.resolveCommunityFooterBind("test", communityHook, controls)
            }
            val bindBefore = site?.method?.instructionList()?.map { it.opcode }

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
            if (community != null && site != null && bindBefore != null) {
                val where = "${site.method.definingClass}->${site.method.name}"
                val bind = context.bodyOf(site.method)
                assertEquals("$where: one call added", bindBefore.size + 1, bind.size)
                assertEquals("$where: the extension is handed the footer", community.first, bind[site.at].call())
                assertEquals("$where: this, the footer", listOf(site.method.localRegisterCount()), bind[site.at].namedRegisters())
                assertEquals("$where: right before the return", Opcode.RETURN_VOID, bind[site.at + 1].opcode)
                assertEquals("$where: Tumblr's own code around it", bindBefore,
                    bind.filterIndexed { at, _ -> at != site.at }.map { it.opcode })
            }
            assertSwitchedOn(context, build.name, status, capability)
        }
    }

    /**
     * The footer's controls, found by their source notes, and every class with a static method
     * turning an int into a String, which takes in the class that builds the buttons' states. Also
     * the community footer and its controls, the community comment text, and every reader of the
     * notes count plural, the notes title among them.
     */
    private fun footerContext(build: File): BytecodePatchContext {
        val classes = FixtureDex.classesWhere(build, { true }) { method ->
            method.strings().any { it.startsWith(FOOTER_CONTROLS) || it.startsWith(COMMUNITY_COMMENTS_TEXT) } ||
                (method.isStatic() && method.returnType == "Ljava/lang/String;" &&
                    method.parameterTypes.map { it.toString() } == listOf("I")) ||
                method.definingClass == COMMUNITY_FOOTER || method.definingClass.startsWith(COMMUNITY_CONTROLS) ||
                method.instructionList().any { it.fieldRef()?.name == "note_count_show" }
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
