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
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.lookup.sameAs
import app.morphe.patches.tumblr.lookup.strings
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.returnEarlyWhen
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Hide blog notification reminders"
internal const val BLOG_NOTIFY_ENABLED = "blog_notify_enabled"
internal const val OPTIONAL_NUDGES = "$EXTENSION_PACKAGE/ui/OptionalNudges;"
internal const val BLOG_REMINDER_HOOK = "$OPTIONAL_NUDGES->hideBlogNotificationReminder()Z"

/**
 * The native preference only permits a reminder popup. It is not a subscription state: its sole
 * reader calls the popup's show decision, while deliberate subscription actions queue their own
 * PendingSubscriptionInfo. Returning false here neither saves the preference nor changes a blog.
 * In 46.5.0.109 the decision is rk2.l(), used only by rk2.s(View).
 */
@Suppress("unused")
val hideBlogNotificationRemindersPatch = bytecodePatch(
    name = PATCH,
    description = "Hides the pop-up suggesting you turn on notifications for a blog. Your subscriptions and" +
        " notification settings stay as they are. Starts off. Turn it on in Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())
    execute {
        requireStatusMethod("hideBlogNotificationReminders")
        requireStatusMethod("blogNotificationReminder")
        val decision = resolveBlogNotificationReminder()
        decision.returnEarlyWhen(PATCH, BLOG_REMINDER_HOOK, "const/4 v0, 0x0\nreturn v0")
        enableCapability("blogNotificationReminder")
        enableStatus("hideBlogNotificationReminders")
    }
}

internal fun BytecodePatchContext.resolveBlogNotificationReminder(): MutableMethod {
    requireExtensionHook(PATCH, BLOG_REMINDER_HOOK)
    val owner = appClassesWithString(BLOG_NOTIFY_ENABLED).filter { candidate ->
        candidate.fields.any { it.type == "Landroid/widget/PopupWindow;" } &&
            candidate.methods.any { method -> method.name == "<init>" && method.instructionList().any {
                it.fieldRef()?.let { field -> field.definingClass == "Lcom/tumblr/R\$string;" &&
                    field.name == "blog_cta_message" } == true
            } }
    }.only(PATCH, "the blog notification reminder popup")
    val decision = owner.methods.filter { method ->
        method.isStatic() && AccessFlags.PRIVATE.isSet(method.accessFlags) &&
            method.hasShape(emptyList(), "Z") && BLOG_NOTIFY_ENABLED in method.strings() &&
            method.instructionList().count { it.methodRef()?.let { call ->
                call.definingClass == "Lcom/tumblr/Remember;" && call.name == "c" &&
                    call.hasShape(listOf("Ljava/lang/String;", "Z"), "Z")
            } == true } == 1
    }.only(PATCH, "the reminder's enabled decision")
    val callers = owner.methods.filter { method -> method.instructionList().any { it.methodRef()?.sameAs(decision) == true } }
    requireShape(PATCH, callers.size == 1 && !callers.single().isStatic() &&
        callers.single().hasShape(listOf("Landroid/view/View;"), "Z"),
        "the enabled decision isn't confined to the reminder's show decision")
    return mutableOf(decision).also { it.requireLocals(PATCH, 1) }
}
