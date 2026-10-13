/*
 * Written for HushTumblr (Tumblr), 2026, after the settings logo long press in
 * https://github.com/SysAdminDoc/HushPinterest (GPL-3.0), itself after
 * https://github.com/SysAdminDoc/Hushfacebook (GPL-3.0).
 *
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.tumblr.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.misc.extension.patchLog
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

/**
 * Tumblr's "t" logo at the top of the dashboard. Its layout names the class, so the name is kept.
 * In 46.5.0.109 the dashboard's host fragment finds it as `t_logo_view_top_bar` (or `t_logo_view`
 * on the older top bar) and gives it a tap; nothing sets a long press or touch listener on it.
 */
internal const val LOGO_VIEW = "Lcom/tumblr/ui/widget/TLogoView;"

/** What gets the logo once Tumblr has given it its tap. */
internal const val DASHBOARD_LOGO_HOOK = "$ENTRY->onDashboardLogo(Landroid/view/View;)V"

private const val LOGO_TAP = "$LOGO_VIEW->setOnClickListener(Landroid/view/View\$OnClickListener;)V"

private const val WHAT = "HushTumblr settings (the dashboard logo's long press)"

/** Where Tumblr gives the logo its tap: the hook goes in at [index], passing the logo in [register]. */
internal class LogoSite(val method: Method, val index: Int, val register: Int)

/**
 * Every place outside the logo's own class where Tumblr gives the logo its tap. In 46.5.0.109 that's
 * the dashboard host fragment's top bar bind (`TabbedDashboardHostFragment.m6`, from
 * `onViewCreated`); the logo's constructor makes the only other call, to itself.
 */
internal fun BytecodePatchContext.dashboardLogoSites(): List<LogoSite> {
    val sites = mutableListOf<LogoSite>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT) || owner.type == LOGO_VIEW) return@classDefForEach
        owner.methods.forEach { method ->
            method.instructionList().forEachIndexed { index, instruction ->
                if (instruction.opcode != Opcode.INVOKE_VIRTUAL || instruction.methodRef()?.descriptor() != LOGO_TAP) return@forEachIndexed
                val tap = instruction as FiveRegisterInstruction
                if (tap.registerCount == 2) sites += LogoSite(method, index + 1, tap.registerC)
            }
        }
    }
    return sites
}

/**
 * Hands the dashboard logo to [DASHBOARD_LOGO_HOOK] right after Tumblr gives it its tap, so a long
 * press on the logo opens the HushTumblr screen while a tap stays Tumblr's. Answers false and changes
 * nothing when this build doesn't tap the logo exactly once that way: the logo then stays as it was,
 * and the Settings row, the icon shortcut and App info still open the screen.
 */
internal fun BytecodePatchContext.hookLogoLongPress(): Boolean {
    val sites = dashboardLogoSites()
    val site = sites.singleOrNull() ?: run {
        patchLog.warning("HushTumblr settings: ${sites.size} places give the dashboard logo its tap, so a long press on the logo doesn't open the settings")
        return false
    }
    // Only once there's a site to call it from: a call to a method the extension lacks would crash Tumblr.
    requireExtensionHook(WHAT, DASHBOARD_LOGO_HOOK)
    val method = mutableOf(site.method)
    // The logo is only in its register on the path from the tap's call.
    if ((method.implementation!!.instructions[site.index] as BuilderInstruction).location.labels.isNotEmpty()) {
        patchLog.warning("HushTumblr settings: ${site.method.definingClass}->${site.method.name} branches past the dashboard logo's tap, so a long press on the logo doesn't open the settings")
        return false
    }
    method.addInstruction(site.index, "invoke-static/range { v${site.register} .. v${site.register} }, $DASHBOARD_LOGO_HOOK")
    return true
}
