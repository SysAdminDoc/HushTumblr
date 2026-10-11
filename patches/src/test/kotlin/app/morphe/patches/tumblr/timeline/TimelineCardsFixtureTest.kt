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
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The carousel bridge in each declared build: `TimelineFilter.carouselItems` gets a body that casts
 * to Tumblr's `Carousel` and returns what its items getter returns, the getter the adapter writes
 * under "elements". Each card patch then turns its switch and its rule on. What the rules take out
 * is TimelineFilterTest's.
 */
@Category(FixtureTests::class)
class TimelineCardsFixtureTest {
    @Test
    fun `Hide suggested blogs reads carousels and turns its switch on`() =
        check(hideSuggestedBlogsPatch, "hideSuggestedBlogs", "suggestedBlogCards")

    @Test
    fun `Hide suggested communities reads carousels and turns its switch on`() =
        check(hideSuggestedCommunitiesPatch, "hideSuggestedCommunities", "suggestedCommunityCards")

    @Test
    fun `Hide Watch it rows reads carousels and turns its switch on`() =
        check(hideWatchItRowsPatch, "hideWatchItRows", "watchItRows")

    private fun check(patch: BytecodePatch, status: String, capability: String) {
        for (build in Fixtures.declaredBuilds()) {
            val types = setOf(CAROUSEL, CAROUSEL_ADAPTER)
            val classes = FixtureDex.classes(build, types)
            assertEquals("${build.name}: the carousel and its adapter", types, classes.keys)
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)

            timelineCarouselPatch.execute(context)
            patch.execute(context)

            val stub = context.mutableClassDefBy(TIMELINE_FILTER).methods.single { it.name == "carouselItems" }
                .instructionList()
            assertEquals("${build.name}: cast, call, result, return",
                listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                stub.map { it.opcode })
            assertEquals("${build.name}: cast to the carousel", CAROUSEL,
                ((stub[0] as ReferenceInstruction).reference as TypeReference).type)
            val getter = stub[1].methodRef()!!
            assertEquals(CAROUSEL, getter.definingClass)
            assertTrue("${build.name}: ${getter.name} is a carousel getter of a List", classes.getValue(CAROUSEL).methods.any {
                it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;"
            })
            for (flag in listOf(status, capability)) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
