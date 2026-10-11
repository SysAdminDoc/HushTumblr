/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.flags

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.lookup.appClassesWithString
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.patches.tumblr.misc.extension.requireParameterIntact
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val FEATURE_SWITCHES = "$EXTENSION_PACKAGE/flags/FeatureSwitches;"
internal const val FEATURE_SWITCH_HOOK = "$FEATURE_SWITCHES->isEnabled(Ljava/lang/Enum;Z)Z"

/** The text only the feature enum's static initialiser holds: the description of one of its flags. */
internal const val FEATURE_ENUM_TEXT = "Whether or not we should show the Tumblr Blaze landing page banner"

/** The text only the feature check's companion holds, in its warning about a flag that isn't a boolean. */
internal const val FEATURE_CHECK_TEXT = "You're checking for Feature.isEnabled() with a non-boolean bucket!"

// Shared by every flag-backed patch, so its messages name the hook rather than one of them.
private const val PATCH = "Feature switch hook"

private const val STRING = "Ljava/lang/String;"

/** Where the hook goes: [index] in [method], with the feature in [feature] and the answer in [answer]. */
internal data class FeatureCheckPlan(val method: MutableMethod, val index: Int, val feature: Int, val answer: Int)

/**
 * Sends the answer of Tumblr's feature check through `FeatureSwitches.isEnabled`, so a switch can
 * turn one of Tumblr's own features off. It has no switch or name of its own: each flag-backed
 * patch depends on it and claims its flags in the extension's table.
 *
 * Every boolean check in Tumblr 46.5.0.109 (the cached one, the plain one, the varargs one and the
 * exposure-logged ones) ends in one private method of the feature enum's companion: it takes the
 * feature and its server value, compares the value with "true" through a static
 * `(String, String, boolean)` helper, logs the answer and returns it. The hook goes right after the
 * helper's move-result, before the log, so Tumblr logs what it acts on. Found by reading Tumblr
 * 46.5.0.109 (2026-10-11).
 */
internal val featureSwitchHookPatch = bytecodePatch {
    dependsOn(settingsPatch, tumblrExtensionPatch)

    execute {
        val plan = resolveFeatureCheck()
        plan.method.addInstructions(
            plan.index,
            """
                invoke-static { v${plan.feature}, v${plan.answer} }, $FEATURE_SWITCH_HOOK
                move-result v${plan.answer}
            """,
        )
    }
}

/** Finds the feature check and the place for the hook. Every check runs before anything changes. */
internal fun BytecodePatchContext.resolveFeatureCheck(): FeatureCheckPlan {
    requireExtensionHook(PATCH, FEATURE_SWITCH_HOOK)
    val feature = appClassesWithString(FEATURE_ENUM_TEXT).filter { owner ->
        owner.superclass == "Ljava/lang/Enum;" &&
            owner.methods.any { it.name == "<clinit>" && FEATURE_ENUM_TEXT in it.strings() }
    }.only(PATCH, "Tumblr's feature enum")
    val companion = appClassesWithString(FEATURE_CHECK_TEXT).only(PATCH, "Tumblr's feature check")
    val check = companion.methods.filter { method ->
        !method.isStatic() && method.implementation != null && method.hasShape(listOf(feature.type, STRING), "Z")
    }.only(PATCH, "the feature check that compares a flag's server value")
    val method = mutableOf(check)
    val body = method.instructionList()
    val compare = body.indices.filter { at ->
        body[at].opcode == Opcode.INVOKE_STATIC &&
            body[at].methodRef()?.hasShape(listOf(STRING, STRING, "Z"), "Z") == true
    }.only(PATCH, "the comparison of a flag's server value")
    val result = body.getOrNull(compare + 1)
    requireShape(PATCH, result?.opcode == Opcode.MOVE_RESULT && compare + 2 < body.size,
        "the comparison's answer isn't taken into a register before the check goes on")
    val answer = (result as OneRegisterInstruction).registerA
    val index = compare + 2
    val featureRegister = method.parameterRegisterNumber(0)
    requireShape(PATCH, answer <= 15 && featureRegister <= 15,
        "the feature (v$featureRegister) or the answer (v$answer) is past v15, where the hook's call can't name it")
    method.requireParameterIntact(PATCH, 0, listOf(index))
    return FeatureCheckPlan(method, index, featureRegister, answer)
}
