/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.rootmute

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.timeline.POST
import app.morphe.patches.tumblr.timeline.postJsonPresencePatch
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.*
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(FixtureTests::class)
class RootPostMutesFixtureTest {
    private val owners = setOf(POST, POST.removeSuffix(";") + "JsonAdapter;", ROOT_POST_WRAPPER, ROOT_MENU_OWNER,
        LOCAL_TIMELINE, ROOT_TIMELINE_OBJECT, "Lrbk;", "Lvgi;", "Ltj8;", "Lb6l;",
        "Lcom/tumblr/components/bottomsheet/b\$a;", "Lcom/tumblr/components/bottomsheet/b\$b;")

    @Test
    fun `native root bridges menu action and local cache rebind preserve their actual values`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            postJsonPresencePatch.execute(context)
            val plan = context.resolveRootPostMutes()
            assertEquals(listOf("$POST->K0()Ljava/lang/String;", "$POST->G0()Ljava/lang/String;", "$POST->z0()Ljava/lang/String;",
                "$POST->B0()Ljava/util/List;"), plan.roots[0].getters)
            muteRootPostsPatch.execute(context)
            for (root in plan.roots) {
                val owner = context.mutableClassDefBy(root.owner)
                assertTrue(HUSH_ROOT_POST in owner.interfaces)
                val bridge = owner.methods.single { it.name == "hushRootId" }.instructionList()
                if (root.owner == POST) {
                    assertEquals(root.getters + CANONICAL_ROOT, bridge.mapNotNull { it.methodRef()?.toString() })
                    assertEquals(ROOT_TRAIL_PRESENT, bridge[8].fieldRef().toString())
                    assertEquals(listOf(0, 1, 2, 3, 4), (bridge[9] as FiveRegisterInstruction)
                        .let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG) })
                } else assertEquals(WRAPPED_CANONICAL_ROOT, bridge.first().fieldRef().toString())
            }
            val capture = plan.wrapperConstructor.instructionList()
            assertEquals("$HUSH_ROOT_POST->hushRootId()Ljava/lang/String;", capture[1].methodRef().toString())
            assertEquals(WRAPPED_CANONICAL_ROOT, capture[3].fieldRef().toString())
            val reader = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "fromJson" && it.returnType == "Ljava/lang/Object;"
            }.instructionList()
            assertEquals(ROOT_JSON_SET, reader[2098].methodRef().toString())
            assertEquals(listOf(4, 154, 158), reader.subList(2095, 2098).map { (it as TwoRegisterInstruction).registerB })
            assertEquals(listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_FROM16, Opcode.MOVE_FROM16), reader.subList(2095, 2098).map { it.opcode })
            val presence = context.mutableClassDefBy(POST)
            assertEquals(setOf("hushRootTrailPresent", "hushPaidContextKnown"), presence.fields.filter {
                it.name in setOf("hushRootTrailPresent", "hushPaidContextKnown") && it.type == "Z"
            }.map { it.name }.toSet())
            assertEquals(1, presence.methods.count { it.name == "hushSetJsonPresence" })
            assertFalse(presence.methods.any { it.name == "hushPaidContextKnown" })
            val body = plan.menu.instructionList()
            assertEquals(ROOT_MENU_HOOK, body[plan.at + 2].methodRef().toString())
            assertEquals(listOf(11, plan.scratch[0], 10, plan.scratch[1]), (body[plan.at + 2] as FiveRegisterInstruction)
                .let { listOf(it.registerC, it.registerD, it.registerE, it.registerF) })
            val finalizer = plan.at + 3
            val flow = ControlFlow.of(plan.menu)
            assertEquals(listOf(finalizer - 1), flow.normal.indices.filter { finalizer in flow.normal[it] })
            val timeline = context.mutableClassDefBy(LOCAL_TIMELINE)
            val snapshot = timeline.methods.single { it.name == "hushTimelineItems" }.instructionList()
            assertEquals("$LOCAL_TIMELINE->z:Ljava/util/List;", snapshot[1].fieldRef().toString())
            assertEquals("Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V", snapshot[2].methodRef().toString())
            val local = timeline.methods.single { it.name == "hushApplyTimelineItems" }.instructionList()
            assertEquals("Lrbk;->FROM_CACHE:Lrbk;", local[0].fieldRef().toString())
            assertEquals("$LOCAL_TIMELINE->i6(Lrbk;Ljava/util/List;)V", local[1].methodRef().toString())
            assertTrue("Ltj8;" in context.mutableClassDefBy(ROOT_ACTION).interfaces)
            val add = context.mutableClassDefBy(ROOT_MENU).methods.single { it.name == "addNative" }.instructionList()
            assertEquals("Lcom/tumblr/components/bottomsheet/b\$b;->c(Ljava/lang/String;Ltj8;)Lcom/tumblr/components/bottomsheet/b\$b;", add[1].methodRef().toString())
            val unit = context.mutableClassDefBy(ROOT_MENU).methods.single { it.name == "unit" }.instructionList()
            assertEquals("Lb6l;->a:Lb6l;", unit[0].fieldRef().toString())
            for (name in listOf("muteRootPosts", "rootPostMutes")) assertEquals(1,
                (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
        }
    }

    @Test
    fun `a missing root key refuses before the post menu list or capability changes`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val writer = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "toJson" && it.parameterTypes.size == 2 && it.returnType == "V"
            }
            val at = writer.instructionList().indexOfFirst { it.stringRef() == "reblogged_root_id" }
            val register = (writer.instructionList()[at] as OneRegisterInstruction).registerA
            writer.replaceInstruction(at, "const-string v$register, \"unknown_root_key\"")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `an append cache mode refuses before installing a local action`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val mode = context.mutableClassDefBy("Lrbk;").methods.single { it.name == "f" }
            mode.replaceInstruction(4, "const/4 p0, 0x1")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `another JSON mask cannot prove the original post's trail was present`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val reader = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "fromJson" && it.returnType == "Ljava/lang/Object;"
            }
            reader.replaceInstruction(2067, "move/from16 v158, v6")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `a trail presence bit cleared into another register refuses before edits`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val reader = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "fromJson" && it.returnType == "Ljava/lang/Object;"
            }
            reader.replaceInstruction(372, "and-int/lit8 v4, v7, -0x2")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `a root only installation refuses a replaced first presence mask`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val reader = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "fromJson" && it.returnType == "Ljava/lang/Object;"
            }
            reader.replaceInstruction(2027, "move/from16 v154, v9")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `another successful return cannot bypass the shared presence hook`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val reader = context.mutableClassDefBy(POST.removeSuffix(";") + "JsonAdapter;").methods.single {
                it.name == "fromJson" && it.returnType == "Ljava/lang/Object;"
            }
            reader.replaceInstruction(2093, "return-object v4")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    @Test
    fun `a replaced current card parameter refuses before its menu is edited`() {
        for (build in Fixtures.declaredBuilds()) {
            val context = PatchContexts.of(ExtensionDex.classes() + FixtureDex.classes(build, owners).values)
            val menu = context.mutableClassDefBy(ROOT_MENU_OWNER).methods.single { it.name == "y" }
            menu.replaceInstruction(224, "move-object/from16 v24, v25")
            assertThrows(PatchException::class.java) { postJsonPresencePatch.execute(context); muteRootPostsPatch.execute(context) }
            assertUnchanged(context)
        }
    }

    private fun assertUnchanged(context: app.morphe.patcher.patch.BytecodePatchContext) {
        assertFalse(HUSH_ROOT_POST in context.mutableClassDefBy(POST).interfaces)
        assertFalse(HUSH_LOCAL_TIMELINE in context.mutableClassDefBy(LOCAL_TIMELINE).interfaces)
        assertFalse("Ltj8;" in context.mutableClassDefBy(ROOT_ACTION).interfaces)
        assertFalse(context.mutableClassDefBy(ROOT_MENU_OWNER).methods.single { it.name == "y" }.instructionList()
            .any { it.methodRef()?.toString() == ROOT_MENU_HOOK })
        for (name in listOf("muteRootPosts", "rootPostMutes")) assertEquals(0,
            (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.instructionList().first() as NarrowLiteralInstruction).narrowLiteral)
    }
}
