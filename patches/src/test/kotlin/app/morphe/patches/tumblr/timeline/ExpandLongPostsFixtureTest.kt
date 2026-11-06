/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.timeline

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.PatchLogCapture
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.localRegisterCount
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.util.RegisterLiveness
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Read more under a post Collapse long posts cut opens the post on its own screen, away from the timeline. */
@Category(FixtureTests::class)
class ExpandLongPostsFixtureTest {
    private val patch = "Collapse long posts"
    private val types = setOf(
        POST_COMPOSER, BLOCK_ROW, TIMELINE_SCREEN, READ_MORE_BINDER, READ_MORE_SCREEN, READ_MORE_HOLDER,
        TIMELINE_ITEM, TIMELINE_CARD, TIMELINE_FRAGMENT, TIMELINE_ADAPTER, GRAYWATER_ADAPTER, RECYCLER_ADAPTER,
    )

    /** The patch's classes, and every class the binder builds, which holds the button's listener R8 names per build. */
    private fun context(build: File): BytecodePatchContext {
        val found = FixtureDex.classes(build, types)
        val built = found.getValue(READ_MORE_BINDER).methods.flatMap { it.instructionList() }
            .filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
            .toSet()
        return PatchContexts.of(ExtensionDex.classes() + found.values + FixtureDex.classes(build, built - types).values)
    }

    @Test fun `the post's own Read more hands its button and item over, its tap asks first and the timeline builds a post again`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            // The binder is the post row composer's d row; a trail entry gets a copy of it with the entry's id.
            assertTrue("${build.name}: the composer copies its d row for trail entries", context.classDefBy(POST_COMPOSER).methods.any { method ->
                val steps = method.instructionList()
                steps.indices.any { k ->
                    steps[k].opcode == Opcode.IGET_OBJECT && steps[k].fieldRef()?.let { it.definingClass == POST_COMPOSER && it.name == "d" } == true &&
                        steps.drop(k + 1).take(4).any { it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == READ_MORE_BINDER } &&
                        steps.drop(k + 1).let { after ->
                            val copied = after.indexOfFirst { step -> step.methodRef()?.let { it.definingClass == READ_MORE_BINDER && it.returnType == READ_MORE_BINDER && it.parameterTypes.isEmpty() } == true }
                            copied >= 0 && after.drop(copied + 1).any { step -> step.methodRef()?.let { it.definingClass == READ_MORE_BINDER && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") } == true }
                        }
                }
            })
            val site = context.resolveExpandSite(patch)
            val bindBefore = site.bind.instructionList().map(::fingerprint)
            val tapBefore = site.tap.instructionList().toList()
            val tapTargets = targets(tapBefore)
            assertTrue("${build.name}: the tap's scratch is a free local",
                site.scratch < site.tap.localRegisterCount() && site.scratch !in RegisterLiveness.of(site.tap).liveInto(0))
            val binderMethods = context.mutableClassDefBy(READ_MORE_BINDER).methods.associate { it.toString() to print(it) }
            val cardMethods = context.mutableClassDefBy(TIMELINE_CARD).methods.associate { it.toString() to print(it) }
            val timelineMethods = context.mutableClassDefBy(TIMELINE_FRAGMENT).methods.associate { it.toString() to print(it) }

            val warnings = PatchLogCapture.warnings { collapseLongPostsPatch.execute(context) }

            assertTrue(warnings.toString(), warnings.none { "Read more opens" in it })
            val bind = site.bind.instructionList()
            assertEquals(build.name, LONG_POSTS_READ_MORE_SHOWN_HOOK, bind[site.bindAt].methodRef().toString())
            assertEquals(build.name, listOf(site.button, site.item), bind[site.bindAt].namedRegisters())
            assertEquals(build.name, site.bind.parameterRegisterNumber(0), site.item)
            assertEquals("${build.name}: right before the button's listener", Opcode.NEW_INSTANCE, bind[site.bindAt + 1].opcode)
            assertEquals(build.name, bindBefore, bind.filterIndexed { k, _ -> k != site.bindAt }.map(::fingerprint))

            val tap = site.tap.instructionList()
            assertEquals(build.name, listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                tap.take(4).map { it.opcode })
            assertEquals(build.name, LONG_POSTS_READ_MORE_HOOK, tap[0].methodRef().toString())
            assertEquals(build.name, listOf(site.tap.localRegisterCount(), site.tap.parameterRegisterNumber(0)), tap[0].namedRegisters())
            assertEquals(build.name, listOf(site.scratch), tap[1].namedRegisters())
            assertEquals(build.name, listOf(site.scratch), tap[2].namedRegisters())
            assertEquals("${build.name}: no runs Tumblr's own tap", 4, target(tap, 2))
            assertEquals(build.name, tapBefore.map(::fingerprint), tap.drop(4).map(::fingerprint))
            for ((k, landing) in tapTargets) assertEquals("${build.name}: Tumblr's branch at $k", landing + 4, target(tap, k + 4))

            val binder = context.mutableClassDefBy(READ_MORE_BINDER)
            assertTrue(build.name, HUSH_READ_MORE_BINDER in binder.interfaces)
            val screen = binder.methods.single { it.toString() !in binderMethods }
            assertEquals("$READ_MORE_BINDER->hushReadMoreScreen()Ljava/lang/Object;", screen.toString())
            val answer = screen.instructionList()
            assertEquals(listOf(Opcode.IGET_OBJECT, Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT, Opcode.CONST_4, Opcode.RETURN_OBJECT),
                answer.map { it.opcode })
            assertEquals(listOf("$READ_MORE_BINDER->d:Ljava/lang/String;", "$READ_MORE_BINDER->b:$READ_MORE_SCREEN"),
                answer.mapNotNull { it.fieldRef()?.toString() })
            assertEquals("a trail entry's copy answers null", 4, target(answer, 1))
            val edited = setOf(site.bind.toString(), site.tap.toString())
            for (method in binder.methods) {
                val key = method.toString()
                if (key in binderMethods && key !in edited) assertEquals("${build.name}: keep $key", binderMethods.getValue(key), print(method))
            }

            val card = context.mutableClassDefBy(TIMELINE_CARD)
            assertTrue(build.name, HUSH_TIMELINE_ITEM in card.interfaces)
            val post = card.methods.single { it.toString() !in cardMethods }
            assertEquals("$TIMELINE_CARD->hushPost()Ljava/lang/Object;", post.toString())
            assertEquals(listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), post.instructionList().map { it.opcode })
            val getter = post.instructionList()[0].methodRef().toString()
            assertEquals("$TIMELINE_CARD->l()Lcom/tumblr/rumblr/model/Timelineable;", getter)
            assertTrue("${build.name}: $getter is Tumblr's", getter in cardMethods)
            for (method in card.methods) {
                val key = method.toString()
                if (key in cardMethods) assertEquals("${build.name}: keep $key", cardMethods.getValue(key), print(method))
            }

            val timeline = context.mutableClassDefBy(TIMELINE_FRAGMENT)
            assertTrue(build.name, HUSH_EXPANDING_TIMELINE in timeline.interfaces)
            val expand = timeline.methods.single { it.toString() !in timelineMethods }
            assertEquals("$TIMELINE_FRAGMENT->hushExpandPost(Ljava/lang/Object;)Z", expand.toString())
            val calls = expand.instructionList().mapNotNull { it.methodRef()?.toString() }
            assertEquals(
                listOf(
                    "$TIMELINE_FRAGMENT->z5()$TIMELINE_ADAPTER", "$GRAYWATER_ADAPTER->f0()Ljava/util/List;",
                    "Ljava/util/List;->indexOf(Ljava/lang/Object;)I", "$GRAYWATER_ADAPTER->y0(IZ)Ljava/lang/Object;",
                    "$GRAYWATER_ADAPTER->U(ILjava/lang/Object;Z)V", "$RECYCLER_ADAPTER->x()V",
                ),
                calls,
            )
            for (call in calls.filter { !it.startsWith("Ljava/") }) {
                val owner = call.substringBefore("->")
                assertTrue("${build.name}: $call is Tumblr's", context.classDefByOrNull(owner)?.methods?.any { it.toString() == call } == true)
            }
            for (method in timeline.methods) {
                val key = method.toString()
                if (key in timelineMethods) assertEquals("${build.name}: keep $key", timelineMethods.getValue(key), print(method))
            }
            assertFlag(context, "longPostExpand", 1)
            assertFlag(context, "longPostRows", 1)
        }
    }

    @Test fun `a binder already bridged leaves Read more opening the post by itself`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val site = context.resolveExpandSite(patch)
            context.mutableClassDefBy(READ_MORE_BINDER).interfaces.add(HUSH_READ_MORE_BINDER)
            assertSkipped(context, site, "already bridged")
        }
    }

    @Test fun `a tap that no longer reads the screen first leaves Read more opening the post by itself`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val site = context.resolveExpandSite(patch)
            site.tap.addInstructions(0, "nop")
            assertSkipped(context, site, "no longer hands the button and the trail entry to the screen")
        }
    }

    @Test fun `a bind that changes the button before its listener leaves Read more opening the post by itself`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val site = context.resolveExpandSite(patch)
            site.bind.addInstructions(site.bindAt, "const/4 v${site.button}, 0x0")
            assertSkipped(context, site, "no longer gives its button a tap that reaches the binder's tap")
        }
    }

    @Test fun `a timeline card without its post getter leaves Read more opening the post by itself`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val site = context.resolveExpandSite(patch)
            context.mutableClassDefBy(TIMELINE_CARD).methods.removeAll { it.name == "l" && it.parameterTypes.isEmpty() }
            assertSkipped(context, site, "no longer hands back its post")
        }
    }

    @Test fun `an adapter that can't take an item out leaves Read more opening the post by itself`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = context(build)
            val site = context.resolveExpandSite(patch)
            context.mutableClassDefBy(GRAYWATER_ADAPTER).methods.removeAll { it.name == "y0" }
            assertSkipped(context, site, "no longer takes an item out and back in")
        }
    }

    /** The patch still collapses posts, logs [reason], and leaves the binder, the card and the timeline as they were. */
    private fun assertSkipped(context: BytecodePatchContext, site: ExpandSite, reason: String) {
        val methods = listOf(site.bind, site.tap)
        val before = methods.map(::print)
        val classes = listOf(READ_MORE_BINDER, TIMELINE_CARD, TIMELINE_FRAGMENT).map { context.mutableClassDefBy(it) }
        val shapes = classes.map { listOf(it.interfaces.toList(), it.methods.map { method -> method.toString() }) }

        val warnings = PatchLogCapture.warnings { collapseLongPostsPatch.execute(context) }

        assertTrue(warnings.toString(), warnings.any { reason in it && "Read more opens a post it cut on its own screen" in it })
        assertEquals(before, methods.map(::print))
        assertEquals(shapes, classes.map { listOf(it.interfaces.toList(), it.methods.map { method -> method.toString() }) })
        assertFlag(context, "longPostExpand", 0)
        assertFlag(context, "longPostRows", 1)
    }

    private fun print(method: Method): List<String> = method.instructionList().map(::fingerprint)

    private fun fingerprint(i: Instruction): String = listOf(i.opcode, i.namedRegisters(), i.methodRef()?.toString(),
        i.fieldRef()?.toString(), i.stringRef(), (i as? NarrowLiteralInstruction)?.narrowLiteral).toString()

    private fun target(body: List<Instruction>, index: Int): Int {
        val addresses = body.runningFold(0) { address, i -> address + i.codeUnits }
        return addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
    }

    private fun targets(body: List<Instruction>): Map<Int, Int> {
        val targets = body.indices.filter { body[it] is OffsetInstruction }.associateWith { target(body, it) }
        assertTrue("every branch lands on an instruction", targets.values.none { it < 0 })
        return targets
    }

    private fun assertFlag(context: BytecodePatchContext, flag: String, expected: Int) {
        assertEquals(flag, expected, (context.mutableClassDefBy(SETTINGS_STATUS).methods.single {
            it.name == flag }.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
    }
}
