/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
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
import app.morphe.util.namedRegisters
import app.morphe.util.writesRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val PATCH = "Hide note counts"
internal const val NOTE_COUNT_HOOK = "$POST_FOOTER->noteCount(I)I"
internal const val COMMUNITY_COMMENTS_TEXT_HOOK = "$POST_FOOTER->communityCommentsText(Ljava/lang/String;)Ljava/lang/String;"
internal const val NOTES_TITLE_HOOK = "$POST_FOOTER->notesTitle(Ljava/lang/String;)Ljava/lang/String;"

/** Compose's source note for the function that writes a community post's comment count. */
internal const val COMMUNITY_COMMENTS_TEXT = "com.tumblr.ui.widget.postcontrol.rememberFormattedComments ("

/** The plurals Tumblr writes "3 comments" on a community post and "12 notes" over a post's notes with. */
private const val COMMUNITY_COMMENTS_PLURAL = "community_post_comments"
private const val NOTE_COUNT_PLURAL = "note_count_show"

private const val STRING = "Ljava/lang/String;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"

/** Where a count text hook goes: in front of instruction [at], on the text in [register]. */
internal class CountTextSite(val method: MutableMethod, val at: Int, val register: Int)

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
 *
 * Two more counts are words rather than numbers, so they get Tumblr's own word in their place:
 * a community post's comment button writes "3 comments" ([resolveCommunityCommentsText]), and a
 * post's notes screen is titled "12 notes" ([resolveNotesTitle]). Each is read as it's written.
 */
@Suppress("unused")
val hideNoteCountsPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the like, reblog and comment counts off the footer under posts, community posts" +
        " included, and the count off the top of a post's notes. The buttons stay and still work. Starts off." +
        " Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideNoteCounts")
        requireStatusMethod("noteCounts")
        // Found and checked before any edit, so a refusal leaves the APK as it was.
        val formatter = resolveCountFormatter()
        val texts = listOf(
            resolveCommunityCommentsText() to COMMUNITY_COMMENTS_TEXT_HOOK,
            resolveNotesTitle() to NOTES_TITLE_HOOK,
        )
        val count = formatter.parameterRegister(0)
        formatter.addInstructions(
            0,
            """
                invoke-static {$count}, $NOTE_COUNT_HOOK
                move-result $count
            """,
        )
        for ((site, hook) in texts) {
            site.method.addInstructions(
                site.at,
                """
                    invoke-static {v${site.register}}, $hook
                    move-result-object v${site.register}
                """,
            )
        }
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

/**
 * Right after a community post's comment button has its "3 comments" text, throwing when this build
 * has no such function, several, or one that writes the text another way.
 *
 * The function is the one whose source note starts with [COMMUNITY_COMMENTS_TEXT]. It reads the
 * [COMMUNITY_COMMENTS_PLURAL] plural and hands it to a call that answers the text; the hook goes
 * right after that answer is moved into its register (index 61 in 46.5.0.109), where nothing jumps.
 */
internal fun BytecodePatchContext.resolveCommunityCommentsText(): CountTextSite {
    requireExtensionHook(PATCH, COMMUNITY_COMMENTS_TEXT_HOOK)
    val writer = classDefByStrings(COMMUNITY_COMMENTS_TEXT, StringComparisonType.STARTS_WITH)
        .filter { !it.type.startsWith(EXTENSION_ROOT) }
        .distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { method -> method.strings().any { it.startsWith(COMMUNITY_COMMENTS_TEXT) } } }
        .only(PATCH, "the function that writes a community post's comment count")
    val body = writer.instructionList()
    val plural = body.indexOfFirst { it.readsPlural(COMMUNITY_COMMENTS_PLURAL) }
    requireShape(PATCH, plural >= 0, "the community comment count isn't written with $COMMUNITY_COMMENTS_PLURAL")
    val written = (plural + 1 until body.size).firstOrNull { body[it].methodRef()?.returnType == STRING }
    return writer.textSiteAfter(written, "the community comment count")
}

/**
 * Right after a post's notes screen has its "12 notes" title, throwing when this build has no such
 * method, several, or one that sets the title another way.
 *
 * The method is the one that reads the [NOTE_COUNT_PLURAL] plural and sets the activity's title
 * (`PostNotesFragment.I5`, a private `(I)V`, in 46.5.0.109). The hook goes right after the
 * `getQuantityString` answer is moved into its register (index 12), and that register has to reach
 * the `setTitle` call unchanged. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
internal fun BytecodePatchContext.resolveNotesTitle(): CountTextSite {
    requireExtensionHook(PATCH, NOTES_TITLE_HOOK)
    val titles = mutableListOf<Method>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.filterTo(titles) { method ->
            val body = method.instructionList()
            body.any { it.readsPlural(NOTE_COUNT_PLURAL) } && body.any { it.setsTitle() }
        }
    }
    val title = titles.only(PATCH, "the method that titles a post's notes with its count")
    val body = title.instructionList()
    val plural = body.indexOfFirst { it.readsPlural(NOTE_COUNT_PLURAL) }
    val written = (plural + 1 until body.size).firstOrNull { body[it].methodRef()?.name == "getQuantityString" }
    val site = title.textSiteAfter(written, "the notes title")
    val shown = (site.at until body.size).firstOrNull { body[it].setsTitle() }
    requireShape(PATCH, shown != null && body[shown].namedRegisters().getOrNull(1) == site.register &&
        (site.at until shown).none { writesRegister(body[it], site.register) },
        "the notes title isn't set from the count text unchanged")
    return site
}

/**
 * The place right after the call at [written] has its String answer moved into a register, ready
 * for a hook on it, throwing naming [what] when there's no such call, no move, or the place can't
 * take the hook.
 */
private fun Method.textSiteAfter(written: Int?, what: String): CountTextSite {
    val body = instructionList()
    val moved = written?.let { body.getOrNull(it + 1) }
    requireShape(PATCH, moved?.opcode == Opcode.MOVE_RESULT_OBJECT && body[written!!].methodRef()?.returnType == STRING,
        "$what isn't a String moved into a register")
    val register = (moved as OneRegisterInstruction).registerA
    val at = written!! + 2
    requireShape(PATCH, register <= 15, "$what is in v$register, past what the added call can name")
    requireShape(PATCH, at < body.size && !isJumpTarget(at), "something jumps past the hook on $what")
    return CountTextSite(mutableOf(this), at, register)
}

/** An `sget` of the plural resource named [name], from whichever R class holds it. */
private fun Instruction.readsPlural(name: String): Boolean =
    opcode == Opcode.SGET && fieldRef()?.let { it.name == name && it.definingClass.endsWith("R\$plurals;") } == true

/** A call of `Activity.setTitle(CharSequence)`. */
private fun Instruction.setsTitle(): Boolean =
    methodRef()?.let { it.name == "setTitle" && it.parameterTypes.map(CharSequence::toString) == listOf(CHAR_SEQUENCE) } == true
