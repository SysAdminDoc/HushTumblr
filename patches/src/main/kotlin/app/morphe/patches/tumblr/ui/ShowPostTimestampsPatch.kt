/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.sameAs
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Show post timestamps"
internal const val TIMESTAMP_USER_INFO = "Lcom/tumblr/UserInfo;"
internal const val TIMESTAMP_HEADER = "Lcom/tumblr/ui/widget/PostCardHeader;"
internal const val TIMESTAMP_PREFERENCE = "pref_is_timestamps"
internal const val POST_TIMESTAMPS = "$EXTENSION_PACKAGE/ui/PostTimestamps;"
internal const val POST_TIMESTAMPS_HOOK = "$POST_TIMESTAMPS->show(Z)Z"
private const val REMEMBER = "Lcom/tumblr/Remember;"

/** Tumblr's own post, reblog and note renderers share this preference getter. */
@Suppress("unused")
val showPostTimestampsPatch = bytecodePatch(
    name = PATCH,
    description = "Shows when posts were made using Tumblr's own timestamp layout. Starts off. " +
        "Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("showPostTimestamps")
        requireStatusMethod("nativePostTimestamps")
        val plan = resolvePostTimestamps()
        plan.method.addInstructions(4, """
            invoke-static/range { v${plan.result} .. v${plan.result} }, $POST_TIMESTAMPS_HOOK
            move-result v${plan.result}
        """)
        enableCapability("nativePostTimestamps")
        enableStatus("showPostTimestamps")
    }
}

internal data class PostTimestampsPlan(val method: MutableMethod, val result: Int)

/** Refuses a changed preference path before any edit. No preference writer is changed. */
internal fun BytecodePatchContext.resolvePostTimestamps(): PostTimestampsPlan {
    requireExtensionHook(PATCH, POST_TIMESTAMPS_HOOK)
    val owner = classDefByOrNull(TIMESTAMP_USER_INFO)
    val getter = owner?.methods?.toList().orEmpty().filter { method ->
        method.hasShape(emptyList(), "Z") && AccessFlags.PUBLIC.isSet(method.accessFlags)
            && AccessFlags.STATIC.isSet(method.accessFlags)
            && method.instructionList().any { it.stringRef() == TIMESTAMP_PREFERENCE }
    }.only(PATCH, "native timestamp preference getter")
    val body = getter.instructionList()
    requireShape(PATCH, body.map { it.opcode } == listOf(Opcode.CONST_STRING, Opcode.CONST_4,
        Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.RETURN), "the native timestamp preference getter changed shape")
    val key = (body[0] as OneRegisterInstruction).registerA
    val fallback = (body[1] as OneRegisterInstruction).registerA
    val result = (body[4] as OneRegisterInstruction).registerA
    val read = body[2].methodRef()
    requireShape(PATCH, body[0].stringRef() == TIMESTAMP_PREFERENCE && key != fallback
        && (body[1] as NarrowLiteralInstruction).narrowLiteral == 0
        && read != null && read.definingClass == REMEMBER && read.hasShape(listOf("Ljava/lang/String;", "Z"), "Z")
        && (body[2] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerC == key && it.registerD == fallback }
        && (body[3] as OneRegisterInstruction).registerA == result,
        "the timestamp getter no longer returns its own native boolean preference")
    requireShape(PATCH, classDefByOrNull(REMEMBER)?.methods?.any {
        it.sameAs(read!!) && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            && it.implementation != null
    } == true, "the native boolean preference reader is missing")
    requireShape(PATCH, classDefByOrNull(TIMESTAMP_HEADER)?.methods?.any { method ->
        method.hasShape(emptyList(), "Z") && method.instructionList().any {
            it.opcode == Opcode.INVOKE_STATIC && it.methodRef()?.sameAs(getter) == true
        }
    } == true, "the native post header no longer consumes the timestamp preference")
    return PostTimestampsPlan(mutableOf(getter), result)
}
