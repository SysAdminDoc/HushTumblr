/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.appClassesWithString
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.returnEarlyWhen
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Skip review prompts"
internal const val REVIEW_PROMPTS = "$EXTENSION_PACKAGE/ui/ReviewPrompts;"
internal const val REVIEW_PROMPTS_HOOK = "$REVIEW_PROMPTS->skip()Z"

/** The preference Tumblr saves the time it last showed its rating prompt under. */
internal const val REVIEW_LAST_SHOWN = "app_review_prompt_rules_last_shown"

/** The "What do you think of the Tumblr app?" screen. An activity, so R8 keeps its name. */
internal const val RATING_PROMPT = "Lcom/tumblr/rating/RatingPromptActivity;"

/**
 * Puts `ReviewPrompts.skip` first in the method that opens Tumblr's rating prompt, and returns from
 * it at once while the switch is on: the prompt rules aren't asked, the screen doesn't open and no
 * time is saved. The switch is read each time, so it works without a restart.
 *
 * The launcher is the one instance method taking a Context that both names the rating prompt
 * activity and saves [REVIEW_LAST_SHOWN]. Nothing else in 46.5.0.109 opens that activity, and the
 * second screen (the mood picker) only opens from a button on the first. Found by reading Tumblr
 * 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val skipReviewPromptsPatch = bytecodePatch(
    name = PATCH,
    description = "Stops the pop-up that asks what you think of the Tumblr app and then asks you for a store" +
        " rating or feedback. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("skipReviewPrompts")
        requireStatusMethod("reviewPrompt")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val launcher = resolveReviewPromptLauncher()
        launcher.returnEarlyWhen(PATCH, REVIEW_PROMPTS_HOOK, "return-void")
        enableCapability("reviewPrompt")
        enableStatus("skipReviewPrompts")
    }
}

/** The rating prompt's launcher, throwing when this build has none, several, or one with no local. */
internal fun BytecodePatchContext.resolveReviewPromptLauncher(): MutableMethod {
    requireExtensionHook(PATCH, REVIEW_PROMPTS_HOOK)
    val launcher = appClassesWithString(REVIEW_LAST_SHOWN)
        .flatMap { it.methods }
        .filter { method ->
            !method.isStatic() && method.hasShape(listOf("Landroid/content/Context;"), "V") &&
                REVIEW_LAST_SHOWN in method.strings() &&
                method.instructionList().any {
                    ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type == RATING_PROMPT
                }
        }
        .only(PATCH, "the method that opens the rating prompt")
    return mutableOf(launcher).also { it.requireLocals(PATCH, 1) }
}
