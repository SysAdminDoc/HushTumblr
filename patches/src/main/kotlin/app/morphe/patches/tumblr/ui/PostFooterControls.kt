/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
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
