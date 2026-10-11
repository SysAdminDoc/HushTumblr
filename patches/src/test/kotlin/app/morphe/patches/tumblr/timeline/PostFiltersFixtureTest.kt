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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The post bridge in each declared build: Tumblr's Post, ReblogTrail and TextBlock each implement
 * their extension interface, and every interface method calls the getter the model's Moshi adapter
 * writes under its key, one the model declares with the same return type. Each post patch then
 * turns its switch and its rule on. What the rules take out is PostRulesTest's.
 */
@Category(FixtureTests::class)
class PostFiltersFixtureTest {
    @Test
    fun `Hide reblogs bridges the post model and turns its switch on`() =
        check(hideReblogsPatch, "hideReblogs", "reblogPosts")

    @Test
    fun `Hide video posts bridges the post model and turns its switch on`() =
        check(hideVideoPostsPatch, "hideVideoPosts", "videoPosts")

    @Test
    fun `Hide photo posts bridges the post model and turns its switch on`() =
        check(hidePhotoPostsPatch, "hidePhotoPosts", "photoPosts")

    @Test
    fun `Hide text posts bridges the post model and turns its switch on`() =
        check(hideTextPostsPatch, "hideTextPosts", "textPosts")

    @Test
    fun `Hide posts with these words bridges the post model and turns its switch on`() =
        check(hidePostsWithWordsPatch, "hidePostsWithWords", "postWords")

    private fun check(patch: BytecodePatch, status: String, capability: String) {
        for (build in Fixtures.declaredBuilds()) {
            val types = POST_BRIDGES.flatMap { listOf(it.model, it.adapter) }.toSet()
            val classes = FixtureDex.classes(build, types)
            assertEquals("${build.name}: the models and their adapters", types, classes.keys)
            val context = PatchContexts.of(ExtensionDex.classes() + classes.values)

            postBridgePatch.execute(context)
            patch.execute(context)

            for (bridge in POST_BRIDGES) {
                val model = context.mutableClassDefBy(bridge.model)
                val original = classes.getValue(bridge.model)
                assertTrue("${build.name}: ${bridge.model} implements ${bridge.bridge}", bridge.bridge in model.interfaces)
                for (getter in bridge.getters) {
                    val added = model.methods.single { it.name == getter.name && it.parameterTypes.isEmpty() }
                    val where = "${build.name}: ${bridge.model}->${getter.name}"
                    assertEquals(where, getter.returnType, added.returnType)
                    assertTrue("$where is public", AccessFlags.PUBLIC.isSet(added.accessFlags))
                    val body = added.instructionList()
                    assertEquals("$where: call, result, return",
                        listOf(Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), body.map { it.opcode })
                    val call = body[0].methodRef()!!
                    assertEquals(where, bridge.model, call.definingClass)
                    assertTrue("$where calls ${call.name}, a getter of ${getter.returnType} the model declares",
                        original.methods.any {
                            it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == getter.returnType &&
                                call.name !in setOf("hashCode", "toString")
                        })
                }
            }
            for (flag in listOf(status, capability)) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }
                    .implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
        }
    }
}
