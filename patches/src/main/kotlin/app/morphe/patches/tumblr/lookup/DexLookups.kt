/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.lookup

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/*
 * Small readers the Tumblr patches share to find their targets from what R8 keeps: string
 * literals, kept class names and method shapes. None of them changes a class.
 */

/** The method's instructions in order, or none for a method with no body. */
internal fun Method.instructionList(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

/** The text a const-string instruction loads, or null for any other instruction. */
internal fun Instruction.stringRef(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** The method an invoke instruction calls, or null for any other instruction. */
internal fun Instruction.methodRef(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

/** The field a field instruction reads or writes, or null for any other instruction. */
internal fun Instruction.fieldRef(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/** Every string literal the method loads. */
internal fun Method.strings(): Set<String> = instructionList().mapNotNull { it.stringRef() }.toSet()

/** Whether the method takes exactly [parameters] and returns [result], all as type descriptors. */
internal fun MethodReference.hasShape(parameters: List<String>, result: String): Boolean =
    parameterTypes.map(CharSequence::toString) == parameters && returnType == result

/** Whether both name the same method: class, name, parameters and return type. */
internal fun MethodReference.sameAs(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** Whether the method is static. */
internal fun Method.isStatic(): Boolean = AccessFlags.STATIC.isSet(accessFlags)

/** Tumblr's own classes, never the extension's, with a method that loads exactly [text]. */
internal fun BytecodePatchContext.appClassesWithString(text: String): List<ClassDef> =
    classDefByStrings(text).filter { owner ->
        !owner.type.startsWith(EXTENSION_ROOT) && owner.methods.any { text in it.strings() }
    }.distinctBy { it.type }

/** The patchable copy of [method] in its own class. */
internal fun BytecodePatchContext.mutableOf(method: MethodReference): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }

/** The one element, or a refusal naming [patch] and [what] when there are none or several. */
internal fun <T> List<T>.only(patch: String, what: String): T =
    singleOrNull() ?: throw PatchException("$patch: $what has $size matches in this build instead of one")

/** Throws naming [patch] with [reason] unless [valid]. */
internal fun requireShape(patch: String, valid: Boolean, reason: String) {
    if (!valid) throw PatchException("$patch: $reason")
}

/**
 * Throws naming [patch] unless the extension has [hook], written `Lclass;->name(params)result`, as a
 * public static method with a body that Tumblr's code can call.
 */
internal fun BytecodePatchContext.requireExtensionHook(patch: String, hook: String) {
    val type = hook.substringBefore("->")
    val signature = hook.substringAfter("->")
    val method = classDefByOrNull(type)?.methods?.singleOrNull { method ->
        "${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}" == signature
    }
    if (method == null || !AccessFlags.PUBLIC.isSet(method.accessFlags) || !method.isStatic() || method.implementation == null) {
        throw PatchException("$patch: the extension has no callable $hook")
    }
}
