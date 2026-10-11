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
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.patches.tumblr.misc.extension

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.extension.EXTENSION_CLASS_DESCRIPTOR
import app.morphe.patches.shared.misc.extension.sharedExtensionPatch
import app.morphe.util.superclassChain

/**
 * The application class the manifest names. A manifest name is never obfuscated. Its `onCreate()`
 * is declared one class up, in `com.tumblr.CoreApp`.
 */
internal const val TUMBLR_APPLICATION = "Lcom/tumblr/App;"

/**
 * Merges the shared and Tumblr extensions and hands them the application context.
 *
 * <p>The context goes in at the start of the application's `onCreate()`, in whichever class of
 * its hierarchy declares one: every process Tumblr starts builds this application, and the
 * settings, the pause switch and safe mode all read preferences through that context before any
 * hook asks for a switch.
 */
val tumblrExtensionPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch(extensionName = "tumblr", isYouTubeOrYouTubeMusic = false))

    execute {
        // The chain starts at the application class itself and stops at the first framework class
        // the APK does not carry.
        val declaring = superclassChain(TUMBLR_APPLICATION)
            .mapNotNull { type -> mutableClassDefByOrNull(type) }
            .firstNotNullOfOrNull { classDef ->
                classDef.methods.singleOrNull { method ->
                    method.name == "onCreate" && method.parameterTypes.isEmpty() &&
                        method.returnType == "V" && method.implementation != null
                }
            } ?: throw PatchException(
                "Neither $TUMBLR_APPLICATION nor a superclass in the APK declares onCreate()",
            )

        declaring.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->setContext(Landroid/content/Context;)V",
        )
    }
}
