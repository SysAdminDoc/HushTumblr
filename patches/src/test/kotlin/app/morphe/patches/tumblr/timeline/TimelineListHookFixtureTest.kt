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
import app.morphe.patches.tumblr.ads.hideAdsPatch
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The two constructors every timeline page is built with, in each declared build, and the filter
 * call put in front of each one's first instruction: the items go in, and what comes back replaces
 * them before Tumblr's own code reads them. Hide ads then turns its switch and its rule on.
 */
@Category(FixtureTests::class)
class TimelineListHookFixtureTest {
    @Test
    fun `each declared build filters both timeline pages' items first thing and Hide ads turns on`() {
        for (build in Fixtures.declaredBuilds()) {
            val types = TIMELINE_LISTS.map { it.substringBefore("->") }.toSet()
            val classes = FixtureDex.classes(build, types)
            assertEquals("${build.name}: both timeline classes", types, classes.keys)
            val originals = TIMELINE_LISTS.associateWith { constructor ->
                classes.getValue(constructor.substringBefore("->")).methods.single { it.identity() == constructor }
            }

            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)
            timelineListHookPatch.execute(context)
            hideAdsPatch.execute(context)

            for ((constructor, original) in originals) {
                val patched = context.mutableClassDefBy(constructor.substringBefore("->")).methods
                    .single { it.identity() == constructor }
                val body = patched.instructionList()
                val before = original.instructionList()
                assertEquals("$constructor: two instructions added", before.size + 2, body.size)
                val items = patched.parameterRegisterNumber(0)
                val call = body[0] as RegisterRangeInstruction
                assertEquals(constructor, Opcode.INVOKE_STATIC_RANGE, body[0].opcode)
                assertEquals("$constructor: the filter is called", TIMELINE_FILTER_HOOK, body[0].methodRef()?.let {
                    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" })
                assertEquals("$constructor: on the items", listOf(items, 1), listOf(call.startRegister, call.registerCount))
                assertEquals(constructor, Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
                assertEquals("$constructor: back into the items", items, (body[1] as OneRegisterInstruction).registerA)
                assertEquals("$constructor: what follows is Tumblr's own code", before.map { it.opcode },
                    body.drop(2).map { it.opcode })
            }
            for (flag in listOf("hideAds", "timelineAds")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    private fun Method.identity() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
}
