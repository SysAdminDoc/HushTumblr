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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.sameAs
import app.morphe.patches.tumblr.lookup.stringRef
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH = "Visible image descriptions"
internal const val IMAGE_BLOCK = "Lcom/tumblr/rumblr/model/post/blocks/ImageBlock;"
internal const val PHOTO_CONTAINER = "Lcom/tumblr/ui/widget/graywater/viewholder/PhotoContainer;"
internal const val PHOTO_HOLDER = "Lcom/tumblr/ui/widget/graywater/viewholder/PhotoViewHolder;"
internal const val GALLERY_ITEM = "Lcom/tumblr/ui/widget/graywater/viewholder/PhotosetRowItem;"
internal const val HUSH_IMAGE = "$EXTENSION_PACKAGE/ui/HushImageDescription;"
internal const val HUSH_IMAGE_CONTAINER = "$EXTENSION_PACKAGE/ui/HushImageContainer;"
internal const val HUSH_IMAGE_ROW = "$EXTENSION_PACKAGE/ui/HushImageRow;"
internal const val ALT_CAPTIONS = "$EXTENSION_PACKAGE/ui/AltTextCaptions;"
internal const val PHOTO_ALT_HOOK = "$ALT_CAPTIONS->photo(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val GALLERY_ALT_HOOK = "$ALT_CAPTIONS->gallery(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val CLEAR_GALLERY_ALT_HOOK = "$ALT_CAPTIONS->clearGallery(Ljava/lang/Object;)V"
private const val STRING = "Ljava/lang/String;"
private const val CONTEXT = "Landroid/content/Context;"

/** Three small model bridges keep host names and models out of the extension's view code. */
@Suppress("unused")
val visibleAltTextPatch = bytecodePatch(
    name = PATCH,
    description = "Shows author-provided image descriptions below each image, including galleries and reblogs. " +
        "Starts off. Turn it on in Tumblr settings > HushTumblr > Posts.",
    default = true,
) {
    category("Posts")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("visibleAltText")
        requireStatusMethod("photoAltTextCaptions")
        requireStatusMethod("galleryAltTextCaptions")
        val plan = resolveAltTextCaptions()
        plan.bridges.forEach { bridge ->
            val model = mutableClassDefBy(bridge.method.definingClass)
            model.interfaces.add(bridge.extension)
            model.methods.add(bridge.method)
        }
        val holder = plan.photo.parameterRegister(0)
        plan.photo.addInstructions(0, """
            iget-object v0, p0, ${plan.photoBlock}
            invoke-static { $holder, v0 }, $PHOTO_ALT_HOOK
        """)
        val galleryHolder = plan.gallery.parameterRegister(1)
        val galleryBlock = plan.gallery.parameterRegister(2)
        plan.gallery.addInstructions(0, """
            invoke-static/range { $galleryHolder .. $galleryBlock }, $GALLERY_ALT_HOOK
        """)
        val row = plan.galleryFull.parameterRegister(5)
        plan.galleryFull.addInstructions(0, """
            invoke-static/range { $row .. $row }, $CLEAR_GALLERY_ALT_HOOK
        """)
        enableCapability("photoAltTextCaptions")
        enableCapability("galleryAltTextCaptions")
        enableStatus("visibleAltText")
    }
}

internal data class CaptionBridge(val extension: String, val method: MutableMethod)
internal data class AltTextCaptionsPlan(
    val photo: MutableMethod, val photoBlock: FieldReference, val gallery: MutableMethod,
    val galleryFull: MutableMethod, val bridges: List<CaptionBridge>,
)

/** Every target and the scratch register are checked before any host class changes. */
internal fun BytecodePatchContext.resolveAltTextCaptions(): AltTextCaptionsPlan {
    listOf(PHOTO_ALT_HOOK, GALLERY_ALT_HOOK, CLEAR_GALLERY_ALT_HOOK).forEach { requireExtensionHook(PATCH, it) }
    val image = classDefByOrNull(IMAGE_BLOCK)
    requireShape(PATCH, image != null, "the native image block is missing")
    val writer = classDefByOrNull(IMAGE_BLOCK.removeSuffix(";") + "JsonAdapter;")?.methods?.filter {
        it.name == "toJson" && it.parameterTypes.size == 2 && it.returnType == "V"
    }.orEmpty().only(PATCH, "image JSON writer").instructionList()
    val key = writer.indexOfFirst { it.stringRef() == "alt_text" }
    requireShape(PATCH, key >= 0, "the image JSON writer has no alt_text field")
    val getter = writer.drop(key + 1).takeWhile { it.stringRef() == null }.firstNotNullOfOrNull { instruction ->
        instruction.methodRef()?.takeIf { instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == IMAGE_BLOCK }
    }
    requireShape(PATCH, getter != null && getter.hasShape(emptyList(), STRING) && image!!.methods.any {
        it.sameAs(getter) && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    }, "alt_text no longer has a public native string getter")
    val frame = nativeInterfaceGetter(PHOTO_CONTAINER, "Landroid/widget/FrameLayout;")
    for (holder in listOf(PHOTO_HOLDER, GALLERY_ITEM)) {
        requireShape(PATCH, PHOTO_CONTAINER in classDefByOrNull(holder)?.interfaces.orEmpty(), "$holder no longer implements the native photo container")
    }

    val badgeBinders = mutableListOf<Method>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
        owner.methods.filterTo(badgeBinders) { method ->
            method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) in
                listOf(listOf(PHOTO_HOLDER, IMAGE_BLOCK), listOf(CONTEXT, GALLERY_ITEM, IMAGE_BLOCK))
                && method.instructionList().any { it.methodRef()?.sameAs(getter!!) == true }
                && method.instructionList().any { it.methodRef()?.let { call ->
                    call.definingClass in setOf(PHOTO_HOLDER, GALLERY_ITEM) && call.hasShape(emptyList(), "Landroid/widget/TextView;")
                } == true }
                && method.instructionList().any { it.methodRef()?.name == "setOnClickListener" }
        }
    }
    val photoBadge = badgeBinders.filter { it.parameterTypes.size == 2 }.only(PATCH, "single-image ALT badge binder")
    val gallery = badgeBinders.filter { it.parameterTypes.size == 3 }.only(PATCH, "gallery ALT badge binder")
    val photoOwner = classDefByOrNull(photoBadge.definingClass)!!
    val photo = photoOwner.methods.filter { method ->
        method.returnType == "V" && method.parameterTypes.size == 3 && method.parameterTypes[0] == PHOTO_HOLDER
            && method.parameterTypes[2] == STRING && method.instructionList().any { it.methodRef()?.sameAs(photoBadge) == true }
    }.only(PATCH, "single-image full binder")
    val photoField = photoOwner.fields.filter { it.type == IMAGE_BLOCK }.only(PATCH, "the single-image binder's current image block")
    requireShape(PATCH, !AccessFlags.STATIC.isSet(photoField.accessFlags) && photo.implementation!!.registerCount == 5
        && photo.parameterRegisterNumber(0) == 2 && 0 !in RegisterLiveness.of(photo).liveInto(0)
        && photo.instructionList().any { it.fieldRef()?.toString() == photoField.toString() },
        "the single-image current-block field or its free local changed")
    val galleryFull = classDefByOrNull(gallery.definingClass)!!.methods.filter { method ->
        method.returnType == "V" && method.parameterTypes.size == 8 && method.parameterTypes[0] == CONTEXT
            && method.instructionList().any { it.methodRef()?.sameAs(gallery) == true }
    }.only(PATCH, "gallery full binder")
    val rowType = galleryFull.parameterTypes[5].toString()
    val row = nativeInterfaceGetter(rowType, "[$GALLERY_ITEM")
    val bridges = listOf(
        captionBridge(IMAGE_BLOCK, HUSH_IMAGE, "hushAltText", STRING, getter!!.toString(), false),
        captionBridge(PHOTO_CONTAINER, HUSH_IMAGE_CONTAINER, "hushImageFrame", "Landroid/view/View;", frame.toString(), true),
        captionBridge(rowType, HUSH_IMAGE_ROW, "hushImageItems", "[Ljava/lang/Object;", row.toString(), true),
    )
    return AltTextCaptionsPlan(mutableOf(photo), photoField, mutableOf(gallery), mutableOf(galleryFull), bridges)
}

private fun BytecodePatchContext.nativeInterfaceGetter(type: String, result: String): Method {
    val owner = classDefByOrNull(type)
    requireShape(PATCH, owner != null && AccessFlags.INTERFACE.isSet(owner.accessFlags), "the native $type interface is missing")
    return owner!!.methods.filter {
        it.hasShape(emptyList(), result) && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.ABSTRACT.isSet(it.accessFlags)
    }.only(PATCH, "$type getter of $result")
}

private fun BytecodePatchContext.captionBridge(
    native: String, extension: String, name: String, result: String, call: String, isInterface: Boolean,
): CaptionBridge {
    val contract = classDefByOrNull(extension)
    requireShape(PATCH, contract != null && AccessFlags.INTERFACE.isSet(contract.accessFlags)
        && contract.methods.count() == 1 && contract.methods.single().let {
            it.name == name && it.hasShape(emptyList(), result) && AccessFlags.PUBLIC.isSet(it.accessFlags)
                && AccessFlags.ABSTRACT.isSet(it.accessFlags)
        }, "the extension $extension bridge is missing or changed")
    val model = classDefByOrNull(native)!!
    requireShape(PATCH, extension !in model.interfaces && model.methods.none { it.name == name }, "$native is already bridged")
    val invoke = if (isInterface) "invoke-interface" else "invoke-virtual"
    val method = ImmutableMethod(native, name, emptyList(), result, AccessFlags.PUBLIC.value,
        null, null, MutableMethodImplementation(2)).toMutable().apply {
        addInstructions(0, """
            $invoke { p0 }, $call
            move-result-object v0
            return-object v0
        """)
    }
    return CaptionBridge(extension, method)
}
