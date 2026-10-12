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
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * What `TimelineFilter` reads from an item by name, in each declared build. A name that's gone
     * keeps every item, so this is what says Hide ads can see a paid placement: the display block's
     * sponsored state and the display type, each with the SPONSORED constant the filter compares
     * against. An enum constant's name() is the string its <clinit> hands the constructor, so that's
     * checked as well as the field.
     */
    @Test
    fun `each declared build keeps the item members the filter reads by name`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classes(build, FILTER_READS.keys + SPONSORED_ENUMS)
            for ((type, getters) in FILTER_READS) {
                val methods = classes[type]?.methods?.toList().orEmpty()
                for (getter in getters) {
                    assertTrue("${build.name}: $type->$getter", methods.any {
                        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == getter &&
                            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
                    })
                }
            }
            for (type in SPONSORED_ENUMS) {
                val enumClass = classes[type]
                assertTrue("${build.name}: $type is an enum", enumClass != null && AccessFlags.ENUM.isSet(enumClass.accessFlags))
                assertTrue("${build.name}: $type has SPONSORED", enumClass!!.fields.any {
                    it.name == SPONSORED && it.type == type && AccessFlags.STATIC.isSet(it.accessFlags)
                })
                val clinit = enumClass.methods.single { it.name == "<clinit>" }
                assertTrue("${build.name}: $type names SPONSORED", clinit.instructionList().any { it.stringRef() == SPONSORED })
            }
        }
    }

    private fun Method.identity() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
}

private const val MODEL = "Lcom/tumblr/rumblr/model"
private const val SPONSORED = "SPONSORED"

/** Each class `TimelineFilter.Model` reads by name, with its getters as `name()returnType`. */
private val FILTER_READS = mapOf(
    "$MODEL/TimelineObject;" to listOf(
        "getData()$MODEL/Timelineable;",
        "isShowSponsoredLabel()Z",
        "getDisplay()$MODEL/Display;",
        "getDisplayType()$MODEL/post/DisplayType;",
    ),
    "$MODEL/Timelineable;" to listOf("getTimelineObjectType()$MODEL/TimelineObjectType;"),
    "$MODEL/Display;" to listOf("getSponsored()$MODEL/SponsoredState;"),
)

/** The enums whose SPONSORED constant marks a paid placement. */
private val SPONSORED_ENUMS = setOf("$MODEL/SponsoredState;", "$MODEL/post/DisplayType;")
