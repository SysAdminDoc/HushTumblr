/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.misc.settings

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The HushTumblr row in Tumblr's own Settings screen, put into each declared build's screen: one
 * row added before the list goes back, its key and its tap sent through the extension, and the
 * extension's title reader written for Tumblr's row class.
 */
class SettingsHubFixtureTest {
    @Test
    @Category(FixtureTests::class)
    fun `each declared build's Settings screen gets the HushTumblr row, its own key and its tap`() {
        for (build in Fixtures.declaredBuilds()) {
            val hub = FixtureDex.classesWhere(build, { true }) { it.definingClass.startsWith(SETTINGS_HUB) }
            // The row class lives outside the screen's package: the first one the row list makes.
            val rowType = hub.flatMap { it.methods }
                .single { method -> method.instructionList().any { it.fieldRef()?.name == "settings_hub_row_preferences" } }
                .instructionList().first { it.opcode == Opcode.NEW_INSTANCE }
                .let { ((it as ReferenceInstruction).reference as TypeReference).type }
            val extra = FixtureDex.classes(build, setOf(rowType, "Lcom/tumblr/compose/ds/icons/outline/HideKt;")).values
            // A lambda outside the package can reach the row's key through the screen's accessor.
            val callers = FixtureDex.classesWhere(build, { true }) { method -> !method.definingClass.startsWith(SETTINGS_HUB) &&
                method.instructionList().any { it.methodRef()?.definingClass?.startsWith(SETTINGS_HUB) == true } }
            val context = PatchContexts.of((ExtensionDex.classes() + hub + extra + callers).distinctBy { it.type })

            val plan = context.resolveSettingsHub()
            assertEquals("${build.name}: the row class", rowType, plan.rowType)
            val builderBefore = plan.builder.instructionList().size
            val keyBefore = plan.key.instructionList().size
            val clickBefore = plan.click.instructionList().size
            context.addSettingsHubRow(plan)

            val builder = plan.builder.instructionList()
            assertEquals("${build.name}: the row's code", builderBefore + 11, builder.size)
            val asked = builder.indexOfFirst { it.methodRef()?.let { call -> call.definingClass == SETTINGS_HUB_ROW && call.name == "titleRes" } == true }
            assertEquals("${build.name}: the title is asked for where the list goes back", plan.builderIndex, asked)
            assertEquals(build.name, Opcode.IF_EQZ, builder[asked + 2].opcode)
            assertEquals(build.name, Opcode.NEW_INSTANCE, builder[asked + 8].opcode)
            assertEquals(build.name, plan.rowConstructor, builder[asked + 9].methodRef()?.descriptor())
            val add = builder[asked + 10]
            assertEquals(build.name, "Ljava/util/List;->add(Ljava/lang/Object;)Z", add.methodRef()?.descriptor())
            assertEquals("${build.name}: the row goes into the list", plan.list, (add as FiveRegisterInstruction).registerC)
            assertEquals("${build.name}: then the list goes back as before", Opcode.INVOKE_STATIC, builder[asked + 11].opcode)
            assertEquals(build.name, Opcode.RETURN_OBJECT, builder.last().opcode)

            val key = plan.key.instructionList()
            assertEquals(build.name, keyBefore + 2, key.size)
            val keyed = key[key.size - 3]
            assertEquals("${build.name}: the key goes through the extension",
                "$SETTINGS_HUB_ROW->rowKey(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", keyed.methodRef()?.descriptor())
            assertEquals(build.name, listOf(plan.keyRow, plan.keyAnswer), (keyed as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            assertEquals(build.name, plan.keyAnswer, (key[key.size - 2] as OneRegisterInstruction).registerA)
            assertEquals(build.name, Opcode.RETURN_OBJECT, key.last().opcode)

            val click = plan.click.instructionList()
            assertEquals(build.name, clickBefore + 5, click.size)
            assertEquals("${build.name}: the tap reads its row first", plan.clickRowField, click[0].fieldRef()?.descriptor())
            assertEquals(build.name, "$SETTINGS_HUB_ROW->onClick(Ljava/lang/Object;)Z", click[1].methodRef()?.descriptor())
            assertEquals(build.name, Opcode.RETURN_VOID, click[4].opcode)

            val reader = context.mutableClassDefBy(SETTINGS_HUB_ROW).methods.single { it.name == "titleOf" }.instructionList()
            assertTrue("${build.name}: the title reader calls the row's title getter",
                reader.any { it.methodRef()?.descriptor() == plan.titleGetter })
            assertTrue(build.name, reader.none { it.opcode == Opcode.CONST_4 })
        }
    }

    @Test
    fun `the row's title is added once to Tumblr's strings`() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<resources><string name=\"app_name\">Tumblr</string></resources>".byteInputStream())
        strings.addSettingsRowTitle()
        val added = strings.getElementsByTagName("string").let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
            .single { it.attributes.getNamedItem("name").nodeValue == SETTINGS_ROW_TITLE }
        assertEquals(SETTINGS_ROW_LABEL, added.textContent)
        assertThrows(app.morphe.patcher.patch.PatchException::class.java) { strings.addSettingsRowTitle() }
    }

    /** The row's title name is the one the extension looks up. */
    @Test
    fun `the patch and the extension name the same title`() {
        assertEquals(SETTINGS_ROW_TITLE, ExtensionDex.stringConstant(SETTINGS_HUB_ROW, "TITLE"))
    }
}
