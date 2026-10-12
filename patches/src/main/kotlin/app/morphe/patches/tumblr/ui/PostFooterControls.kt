/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireThisIntact
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method

internal const val POST_FOOTER = "$EXTENSION_PACKAGE/ui/PostFooter;"

/**
 * Where the source note in each control of the footer under a post starts. Tumblr draws the footer
 * with Compose, and Compose's compiler puts "<package>.<Function> (<File>.kt:<line>)" in each
 * function for its tracing. R8 renames the class and the method but leaves the text, so a control
 * is found by its function's name. The line number moves with every release, so it's left off.
 */
internal const val FOOTER_CONTROLS = "com.tumblr.ui.widget.splitnotescount.composable."

/** Compose's name for the footer's comment button. */
internal const val COMMENT_CONTROL = "CommentEngagement"

/** Compose's name for the footer's like button. */
internal const val LIKE_CONTROL = "LikeEngagement"

/** Compose's name for the footer's reblog button. */
internal const val REBLOG_CONTROL = "ReblogEngagement"

/** Compose's name for the footer's share button. */
internal const val SHARE_CONTROL = "ShareEngagement"

/** Compose's name for the footer's Save button, which Tumblr draws only while its `POST_STASH` flag is on. */
internal const val SAVE_CONTROL = "SaveEngagement"

/** A community post's footer, made of Views. Tumblr names it in its layouts, so R8 keeps the name. */
internal const val COMMUNITY_FOOTER = "Lcom/tumblr/ui/widget/CommunityPostCardFooter;"

/** The package of a community footer's controls. Views Tumblr names in its layouts, so R8 keeps them. */
internal const val COMMUNITY_CONTROLS = "Lcom/tumblr/ui/widget/postcontrol/"

/** The controls `PostFooter` hides for each switch on a community post, by class name. */
internal val COMMUNITY_REACTION_CONTROLS = listOf("CommunitiesReactionControl", "CommunitiesAddReactionControl")
internal val COMMUNITY_COMMENT_CONTROLS = listOf("CommunitiesCommentsControl")
internal val COMMUNITY_SHARE_CONTROLS = listOf("CommunitiesSharingControl")

/** The tag the community footer's bind gives the footer, ahead of the post's ID. */
private const val COMMUNITY_FOOTER_TAG = "footer_"

/**
 * The function that draws the footer control Compose calls [control], throwing naming [patch] when
 * this build has none or several. Its own callers are the footer and the function Compose keeps to
 * draw it again, which calls it the same way.
 */
internal fun BytecodePatchContext.resolveFooterControl(patch: String, control: String): Method {
    val note = "$FOOTER_CONTROLS$control ("
    return classDefByStrings(note, StringComparisonType.STARTS_WITH)
        .filter { !it.type.startsWith(EXTENSION_ROOT) }
        .distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter { method -> method.strings().any { it.startsWith(note) } } }
        .only(patch, "the footer's $control")
}

/**
 * The footer button Compose calls [control], ready for [hook] in front of its first instruction.
 * A button is a static function returning nothing, so returning at once draws nothing: it does that
 * before it opens its own group in Compose's record, so there's nothing left to close.
 */
internal fun BytecodePatchContext.resolveFooterButton(patch: String, control: String, hook: String): MutableMethod {
    requireExtensionHook(patch, hook)
    val button = resolveFooterControl(patch, control)
    requireShape(patch, button.isStatic() && button.returnType == "V",
        "the footer's $control isn't a static function returning nothing")
    return mutableOf(button).also { it.requireLocals(patch, 1) }
}

/** Where a community footer hook goes: the footer's bind, and the index of its one return. */
internal class CommunityFooterSite(val method: MutableMethod, val at: Int)

/**
 * The end of the method that binds a community post's footer, ready for [hook], which hides the
 * [controls] it names by class. Throws naming [patch] when the footer, its bind or one of those
 * controls is missing, or when the bind doesn't end in one return with `this` still in its own
 * register there.
 *
 * Community posts don't use the Compose footer. Their footer is [COMMUNITY_FOOTER], and its bind is
 * the one instance method of it returning void that tags the footer with [COMMUNITY_FOOTER_TAG] and
 * the post's ID, the last thing it does after it has bound the reactions, comment, add reaction and
 * share controls (`m0`, return at 36, in 46.5.0.109). Each control's visibility is set there on
 * every bind, so a hook in front of the return sees them as Tumblr left them. Found by reading
 * Tumblr 46.5.0.109 (2026-10-11).
 */
internal fun BytecodePatchContext.resolveCommunityFooterBind(
    patch: String,
    hook: String,
    controls: List<String>,
): CommunityFooterSite {
    requireExtensionHook(patch, hook)
    for (control in controls) {
        requireShape(patch, classDefByOrNull("$COMMUNITY_CONTROLS$control;") != null,
            "Tumblr's community footer has no $control")
    }
    val footer = classDefByOrNull(COMMUNITY_FOOTER)
        ?: throw PatchException("$patch: Tumblr's $COMMUNITY_FOOTER wasn't found")
    val bind = footer.methods
        .filter { method ->
            !method.isStatic() && method.returnType == "V" && COMMUNITY_FOOTER_TAG in method.strings() &&
                method.instructionList().any { it.methodRef()?.name == "setTag" }
        }
        .only(patch, "the method that binds a community post's footer")
    val body = bind.instructionList()
    val at = body.lastIndex
    requireShape(patch, at > 0 && body[at].opcode == Opcode.RETURN_VOID &&
        body.count { it.opcode == Opcode.RETURN_VOID } == 1, "the community footer's bind doesn't end in its one return")
    requireShape(patch, !bind.isJumpTarget(at), "something in the community footer's bind jumps straight to its return")
    bind.requireThisIntact(patch, listOf(at))
    return CommunityFooterSite(mutableOf(bind), at)
}

/** Puts [hook] in front of the bind's return, handed the footer. */
internal fun CommunityFooterSite.addHook(hook: String) {
    method.addInstruction(at, "invoke-static {p0}, $hook")
}
