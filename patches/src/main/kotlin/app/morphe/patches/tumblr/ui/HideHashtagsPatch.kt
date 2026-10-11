/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch

private const val PATCH = "Hide hashtags"
internal const val POST_PARTS = "$EXTENSION_PACKAGE/ui/PostParts;"
internal const val HIDE_TAGS_HOOK = "$POST_PARTS->hideTags()Z"

/** The row under a post holding its tags. A view named in Tumblr's layouts, so R8 keeps its name. */
internal const val WRAPPED_TAGS = "Lcom/tumblr/ui/widget/PostCardWrappedTags;"

/** A tag as Tumblr's API sends it. A model class read by name from JSON, so R8 keeps its name. */
internal const val TAG_MODEL = "Lcom/tumblr/rumblr/model/blog/TagV2;"

internal const val EMPTY_LIST = "Ljava/util/Collections;->EMPTY_LIST:Ljava/util/List;"

/**
 * Hands the method that draws a post's tags an empty tag text and an empty tag list while the
 * switch is on, so it draws none. The method keeps both in the view, and the view's own check for
 * whether the row has anything to show reads them back, so a post whose row held only tags loses
 * the row, and one with a source link, a submission credit or a badge keeps those. That's the
 * path Tumblr takes for a post with no tags. The switch is read each time a card is bound, so it
 * works without a restart.
 *
 * The method is the one instance method of [WRAPPED_TAGS] returning void that takes exactly one
 * String (the tags as text) and one List (the tags as [TAG_MODEL]s) and calls a [TAG_MODEL]
 * method. Both of the view's bind methods, for posts and for the other timeline cards with tags,
 * call it. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 *
 * The check that decides whether a card gets the row at all (in the view's companion) also reads
 * the tag text, from the post itself, so a post with tags still gets the row bound. The view then
 * hides it with height zero when nothing else is in it.
 */
@Suppress("unused")
val hideHashtagsPatch = bytecodePatch(
    name = PATCH,
    description = "Takes the tags off the bottom of posts in your dashboard and other feeds, and keeps the" +
        " source link. Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("hideHashtags")
        requireStatusMethod("postTags")
        // Found and checked before the edit, so a refusal leaves the APK as it was.
        val drawTags = resolveTagDrawer()
        drawTags.handNoTagsWhenAsked()
        enableCapability("postTags")
        enableStatus("hideHashtags")
    }
}

/** The tags row's tag drawer, throwing when this build has none, several, or one with no local. */
internal fun BytecodePatchContext.resolveTagDrawer(): MutableMethod {
    requireExtensionHook(PATCH, HIDE_TAGS_HOOK)
    val view = classDefByOrNull(WRAPPED_TAGS) ?: throw PatchException("$PATCH: Tumblr's $WRAPPED_TAGS wasn't found")
    val drawer = view.methods
        .filter { method ->
            !method.isStatic() && method.returnType == "V" &&
                method.parameterTypes.count { it.toString() == "Ljava/lang/String;" } == 1 &&
                method.parameterTypes.count { it.toString() == "Ljava/util/List;" } == 1 &&
                method.instructionList().any { it.methodRef()?.definingClass == TAG_MODEL }
        }
        .only(PATCH, "the method that draws a post's tags")
    return mutableOf(drawer).also { it.requireLocals(PATCH, 1) }
}

/** The declared index of the drawer's one parameter of [type]. */
internal fun MutableMethod.tagParameter(type: String): Int =
    parameterTypes.indexOfFirst { it.toString() == type }

/**
 * Puts the question in front of the drawer's first instruction: on true its tag text becomes ""
 * and its tag list the shared empty list, and on false nothing changes. Borrows v0, which holds
 * nothing before the method's own first instruction.
 */
private fun MutableMethod.handNoTagsWhenAsked() {
    val text = parameterRegister(tagParameter("Ljava/lang/String;"))
    val list = parameterRegister(tagParameter("Ljava/util/List;"))
    val first = getInstruction(0)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $HIDE_TAGS_HOOK
            move-result v0
            if-eqz v0, :hush_keep
            const-string $text, ""
            sget-object $list, $EMPTY_LIST
        """,
        ExternalLabel("hush_keep", first),
    )
}
