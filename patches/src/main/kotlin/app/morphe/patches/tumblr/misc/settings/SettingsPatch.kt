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
package app.morphe.patches.tumblr.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.TUMBLR_APPLICATION
import app.morphe.patches.tumblr.misc.extension.patchLog
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Element

internal const val ENTRY = "$EXTENSION_PACKAGE/settings/SettingsEntry;"

/**
 * The activity Tumblr's launcher opens: a splash screen that routes to the dashboard or to sign-in.
 * Tumblr declares it exported with the launcher's intent filter itself, with no alias. A manifest
 * name is never obfuscated.
 */
internal const val MAIN_ACTIVITY = "Lcom/tumblr/ui/activity/JumpoffActivity;"

/** [MAIN_ACTIVITY] as the manifest writes it. */
internal const val MAIN_ACTIVITY_NAME = "com.tumblr.ui.activity.JumpoffActivity"

/**
 * The activity alias the settings patch adds for Android's App info page. Its name sits in the
 * extension's package, so it can't meet a component of Tumblr's own.
 */
internal const val SETTINGS_ALIAS_NAME = "app.hushtumblr.extension.tumblr.settings.OpenSettings"

/** The intent Android's App info page sends to "Additional settings in the app". */
internal const val APPLICATION_PREFERENCES = "android.intent.action.APPLICATION_PREFERENCES"

/**
 * The first class in [type]'s hierarchy, as far as the APK carries it, that declares this
 * method with a body.
 */
internal fun BytecodePatchContext.declaredInHierarchy(
    type: String,
    name: String,
    vararg parameters: String,
): MutableMethod = superclassChain(type)
    .mapNotNull { mutableClassDefByOrNull(it) }
    .firstNotNullOfOrNull { classDef ->
        classDef.methods.singleOrNull { method ->
            method.name == name && method.returnType == "V" && method.implementation != null &&
                method.parameterTypes.map { it.toString() } == parameters.toList()
        }
    } ?: throw PatchException("No class of $type's hierarchy declares $name(${parameters.joinToString("")})V")

/**
 * Adds an alias of [MAIN_ACTIVITY] that answers [APPLICATION_PREFERENCES], which is what makes
 * Android's App info page for Tumblr show "Additional settings in the app". The alias opens
 * Tumblr's own launcher activity with that action, and the extension opens the settings from there.
 * The settings extension needs API 26, so the patched APK also declares that floor, preserving any
 * higher minimum Tumblr already requires.
 */
internal val settingsManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as? Element
                ?: throw PatchException("AndroidManifest.xml has no application element")
            val activities = document.getElementsByTagName("activity")
            val main = (0 until activities.length).map { activities.item(it) as Element }
                .singleOrNull { it.getAttribute("android:name") == MAIN_ACTIVITY_NAME }
                ?: throw PatchException("AndroidManifest.xml doesn't declare $MAIN_ACTIVITY_NAME")
            // An alias decides its own visibility, so the one added here is exported and the
            // activity is left as Tumblr declared it.
            val aliases = document.getElementsByTagName("activity-alias")
            if ((0 until aliases.length).any { (aliases.item(it) as Element).getAttribute("android:name") == SETTINGS_ALIAS_NAME }) {
                throw PatchException("AndroidManifest.xml already has $SETTINGS_ALIAS_NAME")
            }

            val sdkElements = document.getElementsByTagName("uses-sdk")
            if (sdkElements.length > 1) throw PatchException("AndroidManifest.xml has more than one uses-sdk element")
            val sdk = sdkElements.item(0) as? Element ?: document.createElement("uses-sdk").also {
                document.documentElement.insertBefore(it, document.documentElement.firstChild)
            }
            val stockMinSdk = if (!sdk.hasAttribute("android:minSdkVersion")) {
                1 // Android's default when a manifest declares no minimum.
            } else {
                sdk.getAttribute("android:minSdkVersion").toIntOrNull()?.takeIf { it > 0 }
                    ?: throw PatchException("AndroidManifest.xml has an invalid minSdkVersion")
            }
            sdk.setAttribute("android:minSdkVersion", maxOf(stockMinSdk, 26).toString())

            val alias = document.createElement("activity-alias").apply {
                setAttribute("android:name", SETTINGS_ALIAS_NAME)
                setAttribute("android:targetActivity", MAIN_ACTIVITY_NAME)
                setAttribute("android:exported", "true")
            }
            val filter = document.createElement("intent-filter")
            filter.appendChild(document.createElement("action").apply { setAttribute("android:name", APPLICATION_PREFERENCES) })
            filter.appendChild(
                document.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") },
            )
            alias.appendChild(filter)
            application.appendChild(alias)
        }
    }
}

/**
 * Makes the HushTumblr screen reachable three ways. Inside Tumblr, a HushTumblr row at the end of
 * Tumblr's own Settings screen opens it ([addSettingsHubRow]); a build whose Settings screen
 * changed shape keeps the other two, with a warning. From the home screen, a long-press shortcut on
 * Tumblr's launcher icon opens Tumblr with an extra; from Android's App info page for Tumblr,
 * "Additional settings in the app" opens it with [APPLICATION_PREFERENCES]. Tumblr's launcher
 * activity reports its intent, and the screen opens over the next Tumblr activity to resume.
 * Every name hooked for those two is a manifest component or a framework override or call.
 */
@Suppress("unused")
val settingsPatch = bytecodePatch(
    name = "HushTumblr settings",
    description = "Adds a HushTumblr page where you turn features on or off, pause HushTumblr, back up your " +
        "settings and read the licenses. Open it from Tumblr's Settings or by long-pressing the Tumblr icon. " +
        "Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Settings")
    dependsOn(tumblrExtensionPatch, settingsManifestPatch, settingsRowTitlePatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        // Before each return of the application's onCreate, after Tumblr's own startup: the
        // extension registers activity lifecycle callbacks there, and that's only safe once Tumblr
        // has set itself up. Tumblr's application declares no onCreate of its own; com.tumblr.CoreApp,
        // the class above it, does. The call names no register: by its returns, R8 has reused p0
        // (in 46.5.0.109 it holds a String there), and ART refused the whole class at launch.
        val applicationOnCreate = declaredInHierarchy(TUMBLR_APPLICATION, "onCreate")
        val returns = applicationOnCreate.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
        if (returns.isEmpty()) throw PatchException("$TUMBLR_APPLICATION.onCreate never returns")
        returns.asReversed().forEach { index ->
            applicationOnCreate.addInstruction(
                index,
                "invoke-static { }, $ENTRY->onApplicationCreate()V",
            )
        }

        declaredInHierarchy(MAIN_ACTIVITY, "onCreate", "Landroid/os/Bundle;").addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $ENTRY->onActivityCreate(Landroid/app/Activity;)V",
        )
        // JumpoffActivity declares no onNewIntent, so this lands in the AndroidX activity it
        // inherits from, which other activities share. The extension acts only on its own extra
        // or APPLICATION_PREFERENCES, which only the launcher activity and its alias receive.
        declaredInHierarchy(MAIN_ACTIVITY, "onNewIntent", "Landroid/content/Intent;").addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, $ENTRY->onNewIntent(Landroid/app/Activity;Landroid/content/Intent;)V",
        )

        // Tumblr adds its own dynamic shortcuts, and a launcher that shows only a few could cut the
        // settings shortcut off behind them.
        // Each of those calls now goes through the extension, which puts it back in front
        // afterwards. Framework names only, which the obfuscator keeps.
        rerouteShortcutCalls()

        // The row in Tumblr's own Settings screen. Everything it needs is found before any of it
        // goes in, so a build whose screen changed gets none of it and keeps the other two ways.
        val hub = try {
            resolveSettingsHub()
        } catch (refused: PatchException) {
            patchLog.warning("${refused.message}. HushTumblr settings still opens from the launcher shortcut and App info.")
            null
        }
        if (hub != null) addSettingsHubRow(hub)
    }
}
