/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at 8c54a1d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushThreads at b141524 (GPL-3.0),
 * modified for HushTelegram (Telegram), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/Hushfacebook at c15d4f79 (GPL-3.0),
 * modified for HushThreads (Threads), 2026.
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.tumblr.misc.settings

import app.morphe.patches.tumblr.misc.extension.TUMBLR_APPLICATION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x

/**
 * Stand-ins for the Tumblr classes the settings patch hooks, for a test that runs the whole patch
 * over a few classes instead of an APK. They keep Tumblr's shape: the application's onCreate is
 * declared one class up, in CoreApp, and the launcher activity declares onCreate but inherits
 * onNewIntent from the AndroidX activity above it, so the patch has to find both in the hierarchy.
 */
internal object SettingsPatchHosts {
    fun parameters(vararg types: String) = types.map { ImmutableMethodParameter(it, null, null) }

    fun body(registers: Int, vararg instructions: Instruction) =
        ImmutableMethodImplementation(registers, instructions.toList(), null, null)

    /** The application class Tumblr's application extends, which declares its onCreate. */
    const val CORE_APP = "Lcom/tumblr/CoreApp;"

    /** The AndroidX activity the launcher activity inherits onNewIntent from. */
    const val ANDROIDX_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"

    /** The application and the launcher activity, with the classes they inherit the hooked methods from. */
    fun appAndMainActivity(): List<ClassDef> {
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        return listOf(
            ImmutableClassDef(
                CORE_APP, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Landroid/app/Application;",
                null, null, null, null,
                listOf(ImmutableMethod(CORE_APP, "onCreate", parameters(), "V", AccessFlags.PUBLIC.value, null, null, body(1, returns))),
            ),
            ImmutableClassDef(
                TUMBLR_APPLICATION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, CORE_APP, null, null, null, null,
                emptyList(),
            ),
            ImmutableClassDef(
                ANDROIDX_ACTIVITY, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null, null,
                listOf(
                    ImmutableMethod(ANDROIDX_ACTIVITY, "onNewIntent", parameters("Landroid/content/Intent;"), "V", AccessFlags.PROTECTED.value, null, null, body(2, returns)),
                ),
            ),
            ImmutableClassDef(
                MAIN_ACTIVITY, AccessFlags.PUBLIC.value, ANDROIDX_ACTIVITY, null, null, null, null,
                listOf(
                    ImmutableMethod(MAIN_ACTIVITY, "onCreate", parameters("Landroid/os/Bundle;"), "V", AccessFlags.PROTECTED.value, null, null, body(2, returns)),
                ),
            ),
        )
    }

    /** Every class the settings patch hooks. */
    fun all(): List<ClassDef> = appAndMainActivity()
}
