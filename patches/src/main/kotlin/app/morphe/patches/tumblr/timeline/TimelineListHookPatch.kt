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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.iface.Method

internal const val TIMELINE_FILTER = "$EXTENSION_PACKAGE/timeline/TimelineFilter;"
internal const val TIMELINE_FILTER_HOOK = "$TIMELINE_FILTER->filter(Ljava/util/List;)Ljava/util/List;"

// Shared by every timeline patch, so its messages name the hook rather than one of them.
private const val PATCH = "Timeline list hook"

private const val LINK = "Lcom/tumblr/rumblr/model/PaginationLink;"

/**
 * The constructors each page of timeline items is built with, all in classes R8 keeps by name.
 * `Timeline` is the dashboard's page, wrapped by `WrappedTimelineResponse`. `TimelineResponse` is
 * the base of the blog posts, likes and blog search pages, whose constructors all call this one.
 * The items are the first parameter of both.
 */
internal val TIMELINE_LISTS = listOf(
    "Lcom/tumblr/rumblr/model/Timeline;-><init>(Ljava/util/List;$LINK)V",
    "Lcom/tumblr/rumblr/response/timeline/TimelineResponse;-><init>(Ljava/util/List;${LINK}Ljava/util/Map;)V",
)

/**
 * Passes the items each timeline page is built with through `TimelineFilter.filter`, first thing in
 * its constructor, so what a switch drops never reaches Tumblr's adapters. It has no switch or name
 * of its own: each timeline patch depends on it and adds its rule to the extension's filter.
 *
 * Both constructors take the items straight from Tumblr's JSON adapters, so every dashboard, blog,
 * likes and search page passes through. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
internal val timelineListHookPatch = bytecodePatch {
    dependsOn(settingsPatch, tumblrExtensionPatch)

    execute {
        // Every target is found before the first edit, so a refusal leaves the APK as it was.
        val constructors = resolveTimelineLists()
        constructors.forEach { constructor ->
            val items = constructor.parameterRegister(0)
            // Before the superclass constructor: only the items' own register is touched, and a
            // static call on it is allowed there.
            constructor.addInstructions(
                0,
                """
                    invoke-static/range { $items .. $items }, $TIMELINE_FILTER_HOOK
                    move-result-object $items
                """,
            )
        }
    }
}

/** Finds each of [TIMELINE_LISTS], throwing for the first one this build doesn't have. */
internal fun BytecodePatchContext.resolveTimelineLists(): List<MutableMethod> {
    requireExtensionHook(PATCH, TIMELINE_FILTER_HOOK)
    return TIMELINE_LISTS.map { constructor ->
        val type = constructor.substringBefore("->")
        val signature = constructor.substringAfter("->")
        val owner = classDefByOrNull(type) ?: throw PatchException("$PATCH: Tumblr's $type wasn't found")
        if (owner.methods.none { it.matches(signature) && it.implementation != null }) {
            throw PatchException("$PATCH: $constructor wasn't found")
        }
        mutableClassDefBy(type).methods.single { it.matches(signature) }
    }
}

private fun Method.matches(signature: String): Boolean =
    "$name(${parameterTypes.joinToString("")})$returnType" == signature
