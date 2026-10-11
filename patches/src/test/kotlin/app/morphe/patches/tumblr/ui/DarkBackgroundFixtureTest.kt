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
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Where Dark background color hands Tumblr's themes to the extension in each declared build: right
 * after the palette helper sets an activity's theme, on that activity, and right after the Compose
 * colors take their theme, on that theme. Tumblr's own code around each call is unchanged, and the
 * patch turns its switch and target on.
 */
@Category(FixtureTests::class)
class DarkBackgroundFixtureTest {
    @Test
    fun `each declared build hands both themes to the extension`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = FixtureDex.classesWhere(build, { true }) { method ->
                method.instructionList().any { it.methodRef()?.name == "setTheme" || it.isCardAttribute() }
            }
            // The activity's own class both calls setTheme and starts its chain, so it's kept once.
            val chain = activityChains(build, classes).filter { link -> classes.none { it.type == link.type } }
            val context = PatchContexts.of(ExtensionDex.classes() + classes + chain)
            val hooks = context.resolveThemeHooks()
            val activity = "${hooks.activity.definingClass}->${hooks.activity.name}"
            val compose = "${hooks.compose.definingClass}->${hooks.compose.name}"
            val activityBefore = hooks.activity.instructionList().map { it.opcode }
            val composeBefore = hooks.compose.instructionList().map { it.opcode }

            darkBackgroundPatch.execute(context)

            val activityBody = context.mutableClassDefBy(hooks.activity.definingClass).methods
                .single { it.name == hooks.activity.name && it.parameterTypes == hooks.activity.parameterTypes }
                .instructionList()
            assertEquals("$activity: one call added", activityBefore.size + 1, activityBody.size)
            assertEquals("$activity: right after setTheme", "setTheme",
                activityBody[hooks.activityIndex - 1].methodRef()?.name)
            val activityCall = activityBody[hooks.activityIndex]
            assertEquals(activity, ACTIVITY_THEME_HOOK, activityCall.descriptor())
            assertEquals("$activity: on the activity setTheme was called on",
                listOf(hooks.activityRegister), activityCall.namedRegisters())
            assertEquals("$activity: Tumblr's own code around it", activityBefore,
                activityBody.filterIndexed { index, _ -> index != hooks.activityIndex }.map { it.opcode })

            val composeBody = context.mutableClassDefBy(hooks.compose.definingClass).methods
                .single { it.name == "<init>" && it.parameterTypes == hooks.compose.parameterTypes }
                .instructionList()
            assertEquals("$compose: one call added", composeBefore.size + 1, composeBody.size)
            assertEquals("$compose: after getTheme", "getTheme", composeBody[hooks.composeIndex - 2].methodRef()?.name)
            assertEquals("$compose: once the theme is kept", Opcode.MOVE_RESULT_OBJECT,
                composeBody[hooks.composeIndex - 1].opcode)
            val composeCall = composeBody[hooks.composeIndex]
            assertEquals(compose, COMPOSE_THEME_HOOK, composeCall.descriptor())
            assertEquals("$compose: on the theme", listOf(hooks.composeRegister), composeCall.namedRegisters())
            assertEquals("$compose: Tumblr's own code around it", composeBefore,
                composeBody.filterIndexed { index, _ -> index != hooks.composeIndex }.map { it.opcode })

            for (flag in listOf("darkBackground", "themeBackground")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }

    /** The patch's style name is the one the extension looks up. */
    @Test
    fun `the patch and the extension name the same style`() {
        assertEquals(DARK_BACKGROUND_STYLE, ExtensionDex.stringConstant(DARK_BACKGROUND, "STYLE"))
    }

    /**
     * The classes each two-parameter method of [owners] takes first extends, up to the framework, so
     * the patch can see the one whose theme Tumblr sets is an activity.
     */
    private fun activityChains(build: File, owners: List<ClassDef>): Collection<ClassDef> {
        val supers = mutableMapOf<String, String?>()
        FixtureDex.forEach(build) { dex -> for (classDef in dex.classes) supers.putIfAbsent(classDef.type, classDef.superclass) }
        val wanted = mutableSetOf<String>()
        val starts = owners.flatMap { owner ->
            owner.methods.filter { it.parameterTypes.size == 2 }.map { it.parameterTypes[0].toString() }
        }
        for (start in starts) {
            var current: String? = start
            while (current != null && current in supers && wanted.add(current)) current = supers[current]
        }
        return FixtureDex.classes(build, wanted).values
    }

    private fun Instruction.descriptor(): String? = methodRef()?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }
}
