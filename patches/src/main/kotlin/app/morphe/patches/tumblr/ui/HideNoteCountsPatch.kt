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
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.ClassDef

private const val PATCH = "Hide note counts"
internal const val NOTE_COUNT_HOOK = "$POST_FOOTER->noteCount(I)I"

/**
 * Hands the method that turns a like, reblog or comment count into the footer's text the count
 * `PostFooter.noteCount` answers, zero while the switch is on. Tumblr shows no count for zero, so
 * the buttons stay and their counts go. The switch is read for each count, so it shows on the next
 * post that scrolls in, without a restart.
 *
 * Each footer button that shows a count takes its own state as its first parameter: the comment,
 * like and reblog buttons, found by their source notes ([resolveFooterControl]). One class builds
 * all three states, the one with a static method returning each, and the formatter is its one
 * static method taking an int and returning a String. Every footer built with Tumblr's split note
 * counts goes through it, reblog notes included. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val hideNoteCountsPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the like, reblog and comment counts off the footer under posts in your dashboard and" +
        " other feeds. The buttons stay and still work. Starts off. Turn it on in Tumblr settings > HushTumblr >" +
        " Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideNoteCounts")
        requireStatusMethod("noteCounts")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val formatter = resolveCountFormatter()
        val count = formatter.parameterRegister(0)
        formatter.addInstructions(
            0,
            """
                invoke-static {$count}, $NOTE_COUNT_HOOK
                move-result $count
            """,
        )
        enableCapability("noteCounts")
        enableStatus("hideNoteCounts")
    }
}

/**
 * The footer's count formatter, throwing when this build has none, several, or one that doesn't
 * turn its count into text the way Tumblr's does.
 */
internal fun BytecodePatchContext.resolveCountFormatter(): MutableMethod {
    requireExtensionHook(PATCH, NOTE_COUNT_HOOK)
    val states = listOf(COMMENT_CONTROL, LIKE_CONTROL, REBLOG_CONTROL).map { control ->
        val button = resolveFooterControl(PATCH, control)
        requireShape(PATCH, button.parameterTypes.isNotEmpty(), "the footer's $control takes no state")
        button.parameterTypes.first().toString()
    }
    val builders = mutableListOf<ClassDef>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        val built = owner.methods.filter { it.isStatic() }.map { it.returnType }.toSet()
        if (built.containsAll(states)) builders += owner
    }
    val builder = builders.distinctBy { it.type }.only(PATCH, "the class that builds the footer's button states")
    val formatter = builder.methods
        .filter { method ->
            method.isStatic() && method.returnType == "Ljava/lang/String;" &&
                method.parameterTypes.map { it.toString() } == listOf("I")
        }
        .only(PATCH, "the footer's count formatter")
    requireShape(
        PATCH,
        "" in formatter.strings() && formatter.instructionList().any { instruction ->
            instruction.methodRef()?.let { it.definingClass == "Ljava/lang/String;" && it.name == "valueOf" } == true
        },
        "the footer's count formatter doesn't leave zero blank and write the rest as digits",
    )
    return mutableOf(formatter)
}
