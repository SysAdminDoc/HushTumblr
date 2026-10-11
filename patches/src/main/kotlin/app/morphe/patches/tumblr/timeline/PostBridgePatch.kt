/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.timeline

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

// Shared by every post patch, so its messages name the bridge rather than one of them.
private const val PATCH = "Post bridge"

/** Tumblr's post, the trail entry of a reblog and a text block. Model classes, so R8 keeps their names. */
internal const val POST = "Lcom/tumblr/rumblr/model/post/Post;"
internal const val REBLOG_TRAIL = "Lcom/tumblr/rumblr/model/post/ReblogTrail;"
internal const val TEXT_BLOCK = "Lcom/tumblr/rumblr/model/post/blocks/TextBlock;"

/** The extension's interfaces the bridge makes them implement. */
internal const val HUSH_POST = "$EXTENSION_PACKAGE/timeline/HushPost;"
internal const val HUSH_TRAIL = "$EXTENSION_PACKAGE/timeline/HushTrail;"
internal const val HUSH_TEXT = "$EXTENSION_PACKAGE/timeline/HushText;"

private const val LIST = "Ljava/util/List;"
private const val STRING = "Ljava/lang/String;"

/** Methods every class has. A getter that turns out to be one of them is a wrong match. */
private val OBJECT_METHODS = setOf("hashCode", "equals", "toString", "getClass")

/** One interface method: its name, the JSON key its getter is written under, and what it returns. */
internal class BridgeGetter(val name: String, val key: String, val returnType: String)

/** A model class and the extension interface the bridge adds to it. */
internal class ModelBridge(val model: String, val bridge: String, val getters: List<BridgeGetter>) {
    /** Moshi's generated adapter, which Moshi finds by this name, so R8 keeps it. */
    val adapter: String get() = model.removeSuffix(";") + "JsonAdapter;"
}

internal val POST_BRIDGES = listOf(
    ModelBridge(
        POST, HUSH_POST,
        listOf(
            BridgeGetter("hushContent", "content", LIST),
            BridgeGetter("hushTrail", "trail", LIST),
            BridgeGetter("hushTags", "tags", LIST),
            BridgeGetter("hushSummary", "summary", STRING),
            BridgeGetter("hushRebloggedFrom", "reblogged_from_id", STRING),
        ),
    ),
    ModelBridge(REBLOG_TRAIL, HUSH_TRAIL, listOf(BridgeGetter("hushContent", "content", LIST))),
    ModelBridge(TEXT_BLOCK, HUSH_TEXT, listOf(BridgeGetter("hushText", "text", STRING))),
)

/** A bridge found in this build: each interface method with the getter it calls, `Lclass;->name()type`. */
internal class ResolvedBridge(val bridge: ModelBridge, val calls: Map<BridgeGetter, String>)

/**
 * Makes Tumblr's `Post`, `ReblogTrail` and `TextBlock` implement the extension's `HushPost`,
 * `HushTrail` and `HushText`, each added method calling the model's own getter, so the post rules
 * in `PostRules` can read a post's blocks, trail, tags, summary and where it was reblogged from.
 * It has no switch or name of its own: each post patch depends on it.
 *
 * The getters' names change with every build, so each is read from the model's Moshi adapter,
 * whose `toJson` writes every field the same way: `const-string` with the key, then the getter's
 * call on the model. Found by reading Tumblr 46.5.0.109 (2026-10-11): Post's content is `K()`,
 * trail `B0()`, tags `j1()`, summary `f1()` and reblogged_from_id `G0()`; ReblogTrail's content is
 * `e()`; TextBlock's text is `q()`.
 */
internal val postBridgePatch = bytecodePatch {
    dependsOn(tumblrExtensionPatch)

    execute {
        // Every getter is found before the first class changes, so a refusal leaves the APK as it was.
        val resolved = resolvePostBridges()
        resolved.forEach { addBridge(it) }
    }
}

/** Each of [POST_BRIDGES] resolved, throwing for the first model, adapter or getter this build lacks. */
internal fun BytecodePatchContext.resolvePostBridges(): List<ResolvedBridge> = POST_BRIDGES.map { bridge ->
    requireBridgeInterface(bridge)
    val model = classDefByOrNull(bridge.model) ?: throw PatchException("$PATCH: Tumblr's ${bridge.model} wasn't found")
    val adapter = classDefByOrNull(bridge.adapter) ?: throw PatchException("$PATCH: Tumblr's ${bridge.adapter} wasn't found")
    val toJson = adapter.methods.singleOrNull { it.name == "toJson" && it.parameterTypes.size == 2 && it.returnType == "V" }
        ?: throw PatchException("$PATCH: ${bridge.adapter} has no single toJson")
    val body = toJson.instructionList()
    val calls = bridge.getters.associateWith { getter ->
        if (model.methods.any { it.name == getter.name && it.parameterTypes.isEmpty() }) {
            throw PatchException("$PATCH: ${bridge.model} already has a method named ${getter.name}")
        }
        val key = body.indexOfFirst { it.stringRef() == getter.key }
        if (key < 0) throw PatchException("$PATCH: ${bridge.adapter} doesn't write \"${getter.key}\"")
        val call = body.drop(key + 1).firstNotNullOfOrNull { instruction ->
            instruction.methodRef()?.takeIf { instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == bridge.model }
        } ?: throw PatchException("$PATCH: no call on ${bridge.model} follows \"${getter.key}\"")
        val declared = model.methods.any {
            it.name == call.name && it.parameterTypes.isEmpty() && it.returnType == getter.returnType && !it.isStatic() &&
                AccessFlags.PUBLIC.isSet(it.accessFlags)
        }
        if (call.name in OBJECT_METHODS || call.parameterTypes.isNotEmpty() || call.returnType != getter.returnType || !declared) {
            throw PatchException("$PATCH: ${bridge.model}->${call.name} isn't a public getter of ${getter.returnType}")
        }
        "${bridge.model}->${call.name}()${getter.returnType}"
    }
    ResolvedBridge(bridge, calls)
}

/** Throws unless the extension has [bridge]'s interface declaring exactly its getters. */
private fun BytecodePatchContext.requireBridgeInterface(bridge: ModelBridge) {
    val type = classDefByOrNull(bridge.bridge)
    val declares = type != null && AccessFlags.INTERFACE.isSet(type.accessFlags) &&
        type.methods.count { AccessFlags.ABSTRACT.isSet(it.accessFlags) } == bridge.getters.size &&
        bridge.getters.all { getter ->
            type.methods.any { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == getter.returnType }
        }
    if (!declares) {
        throw PatchException("$PATCH: the extension has no ${bridge.bridge} declaring ${bridge.getters.joinToString { it.name }}")
    }
}

/** Adds [resolved]'s interface to its model, and one method per getter that calls it. */
private fun BytecodePatchContext.addBridge(resolved: ResolvedBridge) {
    val bridge = resolved.bridge
    val model = mutableClassDefBy(bridge.model)
    if (bridge.bridge !in model.interfaces) model.interfaces.add(bridge.bridge)
    resolved.calls.forEach { (getter, call) ->
        model.methods.add(
            ImmutableMethod(
                bridge.model, getter.name, emptyList(), getter.returnType, AccessFlags.PUBLIC.value,
                null, null, MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-virtual { p0 }, $call
                        move-result-object v0
                        return-object v0
                    """,
                )
            },
        )
    }
}
