/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.timeline

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.requireStub
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.extension.writeStub
import com.android.tools.smali.dexlib2.Opcode

// Shared by every card patch, so its messages name the bridge rather than one of them.
private const val PATCH = "Timeline carousel bridge"

/** A carousel of timeline items. A model class, so R8 keeps its name. */
internal const val CAROUSEL = "Lcom/tumblr/rumblr/model/carousel/Carousel;"

/** Moshi's generated adapter for it, kept by name because Moshi finds it by name. */
internal const val CAROUSEL_ADAPTER = "Lcom/tumblr/rumblr/model/carousel/CarouselJsonAdapter;"

/** The JSON key the adapter writes the carousel's items under. */
internal const val CAROUSEL_ITEMS_KEY = "elements"

/**
 * Gives `TimelineFilter.carouselItems` a body that reads a carousel's items, so the card patches can
 * judge a carousel by its first item. It has no switch or name of its own: each card patch depends
 * on it.
 *
 * The getter's name changes with every build, so it's read from the adapter's `toJson`, which
 * writes each field the same way: `const-string` with the key, then the getter's call on the
 * carousel. Found by reading Tumblr 46.5.0.109 (2026-10-11), where it's `c()`.
 */
internal val timelineCarouselPatch = bytecodePatch {
    dependsOn(tumblrExtensionPatch)

    execute {
        // Found before the stub is written, so a refusal leaves the extension as it was.
        val getter = resolveCarouselItems()
        writeStub(TIMELINE_FILTER, "carouselItems", 2, """
            check-cast p0, $CAROUSEL
            invoke-virtual {p0}, $getter
            move-result-object v0
            return-object v0
        """)
    }
}

/**
 * The carousel's items getter, written `Lclass;->name()Ljava/util/List;`: the first call on a
 * carousel after the adapter loads [CAROUSEL_ITEMS_KEY]. Throws when this build has no such call.
 */
internal fun BytecodePatchContext.resolveCarouselItems(): String {
    requireStub(TIMELINE_FILTER, "carouselItems")
    val adapter = classDefByOrNull(CAROUSEL_ADAPTER) ?: throw PatchException("$PATCH: Tumblr's $CAROUSEL_ADAPTER wasn't found")
    val toJson = adapter.methods.singleOrNull { it.name == "toJson" && it.parameterTypes.size == 2 && it.returnType == "V" }
        ?: throw PatchException("$PATCH: $CAROUSEL_ADAPTER has no single toJson")
    val body = toJson.instructionList()
    val key = body.indexOfFirst { it.stringRef() == CAROUSEL_ITEMS_KEY }
    if (key < 0) throw PatchException("$PATCH: $CAROUSEL_ADAPTER doesn't write \"$CAROUSEL_ITEMS_KEY\"")
    val call = body.drop(key + 1).firstNotNullOfOrNull { instruction ->
        instruction.methodRef()?.takeIf {
            instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == CAROUSEL
        }
    } ?: throw PatchException("$PATCH: no call on the carousel follows \"$CAROUSEL_ITEMS_KEY\"")
    if (call.parameterTypes.isNotEmpty() || call.returnType != "Ljava/util/List;") {
        throw PatchException("$PATCH: $CAROUSEL->${call.name} isn't a getter of a List")
    }
    return "$CAROUSEL->${call.name}()Ljava/util/List;"
}
