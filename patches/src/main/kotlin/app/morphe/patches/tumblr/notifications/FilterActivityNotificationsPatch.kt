/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.notifications

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegister
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PATCH = "Filter activity notifications"
internal const val ACTIVITY_RESPONSE = "Lcom/tumblr/rumblr/response/ActivityNotificationsResponse;"
internal const val ACTIVITY_MODEL = "Lcom/tumblr/rumblr/model/activity/ActivityNotification;"
internal const val ACTIVITY_TYPE = "Lcom/tumblr/rumblr/model/activity/ActivityNotificationType;"
internal const val HUSH_ACTIVITY = "$EXTENSION_PACKAGE/notifications/HushActivityNotification;"
internal const val ACTIVITY_FILTER = "$EXTENSION_PACKAGE/notifications/ActivityNotifications;"
internal const val ACTIVITY_FILTER_HOOK = "$ACTIVITY_FILTER->filter(Ljava/util/List;)Ljava/util/List;"
private const val LIST = "Ljava/util/List;"
private const val LINKS = "Lcom/tumblr/rumblr/model/PaginationLink;"
private const val STRING = "Ljava/lang/String;"

/** The in-app response is distinct from NotificationsResponse, which is also used for push. */
@Suppress("unused")
val filterActivityNotificationsPatch = bytecodePatch(
    name = PATCH,
    description = "Hides selected likes, reblogs, replies, follows, mentions, asks and boops from the activity list " +
        "as pages load. Each switch starts off. Choose them in Tumblr settings > HushTumblr > More settings > Notifications.",
    default = true,
) {
    category("Notifications")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("filterActivityNotifications")
        requireStatusMethod("activityNotificationList")
        val plan = resolveActivityNotifications()
        val model = mutableClassDefBy(ACTIVITY_MODEL)
        model.interfaces.add(HUSH_ACTIVITY)
        model.methods.add(plan.bridge)
        val items = plan.constructor.parameterRegister(0)
        plan.constructor.addInstructions(0, """
            invoke-static/range { $items .. $items }, $ACTIVITY_FILTER_HOOK
            move-result-object $items
        """)
        enableCapability("activityNotificationList")
        enableStatus("filterActivityNotifications")
    }
}

internal data class ActivityNotificationsPlan(val constructor: MutableMethod, val bridge: MutableMethod)

/** All shape checks and bridge assembly happen before either host class changes. */
internal fun BytecodePatchContext.resolveActivityNotifications(): ActivityNotificationsPlan {
    requireExtensionHook(PATCH, ACTIVITY_FILTER_HOOK)
    val extension = classDefByOrNull(HUSH_ACTIVITY)
    requireShape(PATCH, extension != null && AccessFlags.INTERFACE.isSet(extension.accessFlags)
        && extension.methods.count() == 1 && extension.methods.single().let {
            it.name == "hushNotificationType" && it.parameterTypes.isEmpty() && it.returnType == STRING
                && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.ABSTRACT.isSet(it.accessFlags)
        }, "the extension activity interface is missing or changed")
    val model = classDefByOrNull(ACTIVITY_MODEL)
    requireShape(PATCH, model != null && AccessFlags.INTERFACE.isSet(model.accessFlags)
        && HUSH_ACTIVITY !in model.interfaces && model.methods.none { it.name == "hushNotificationType" },
        "Tumblr's activity interface is missing or already bridged")
    requireShape(PATCH, model!!.methods.count {
        it.name == "getType" && it.parameterTypes.isEmpty() && it.returnType == ACTIVITY_TYPE
            && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.ABSTRACT.isSet(it.accessFlags)
    } == 1, "the activity interface has no unique public getType enum getter")
    val type = classDefByOrNull(ACTIVITY_TYPE)
    val names = setOf("Like", "LikeRollup", "Reblog", "ReblogNaked", "ReblogNakedRollup", "Reply", "ReplyToComment",
        "Follower", "FollowerRollup", "NoteMention", "UserMention", "Ask", "AskAnswer", "Boop", "BoopRollup")
    requireShape(PATCH, type != null && type.superclass == "Ljava/lang/Enum;" && AccessFlags.ENUM.isSet(type.accessFlags)
        && names.all { name -> type.fields.any { it.name == name && it.type == ACTIVITY_TYPE && AccessFlags.ENUM.isSet(it.accessFlags) } },
        "the activity type enum no longer declares the known interaction types")

    val response = classDefByOrNull(ACTIVITY_RESPONSE)
    val original = response?.methods?.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST, LINKS) && it.returnType == "V"
            && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.CONSTRUCTOR.isSet(it.accessFlags)
    }
    requireShape(PATCH, original?.implementation != null, "the in-app activity list constructor is missing")
    val body = original!!.instructionList()
    val items = original.parameterRegisterNumber(0)
    val links = original.parameterRegisterNumber(1)
    val receiver = items - 1
    requireShape(PATCH, body.map { it.opcode } == listOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_DIRECT,
        Opcode.IPUT_OBJECT, Opcode.IPUT_OBJECT, Opcode.RETURN_VOID)
        && body[0].methodRef().toString() == "Ljava/lang/Object;->getClass()Ljava/lang/Class;"
        && (body[0] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == items }
        && body[1].methodRef().toString() == "Ljava/lang/Object;-><init>()V"
        && (body[1] as FiveRegisterInstruction).let { it.registerCount == 1 && it.registerC == receiver }
        && body[2].fieldRef().toString() == "$ACTIVITY_RESPONSE->_notifications:$LIST"
        && (body[2] as TwoRegisterInstruction).let { it.registerA == items && it.registerB == receiver }
        && body[3].fieldRef().toString() == "$ACTIVITY_RESPONSE->links:$LINKS"
        && (body[3] as TwoRegisterInstruction).let { it.registerA == links && it.registerB == receiver },
        "the in-app response no longer stores its own list and untouched pagination links")

    // API 26 is Tumblr's floor. Its interface default calls the virtual getter of every subtype.
    val bridge = ImmutableMethod(ACTIVITY_MODEL, "hushNotificationType", emptyList(), STRING,
        AccessFlags.PUBLIC.value, null, null, MutableMethodImplementation(2)).toMutable().apply {
        addInstructions(0, """
            invoke-interface { p0 }, $ACTIVITY_MODEL->getType()$ACTIVITY_TYPE
            move-result-object v0
            if-eqz v0, :absent
            invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
            move-result-object v0
            return-object v0
            :absent
            const/4 v0, 0x0
            return-object v0
        """)
    }
    return ActivityNotificationsPlan(mutableClassDefBy(ACTIVITY_RESPONSE).methods.single {
        it.name == original.name && it.parameterTypes == original.parameterTypes
    }, bridge)
}
