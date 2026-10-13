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

/** Each place [method] gives the logo its tap, as the index right after the call and the logo's register. */
private fun Method.logoTaps(): List<Pair<Int, Int>> =
    instructionList().withIndex().mapNotNull { (index, instruction) ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL || instruction.methodRef()?.descriptor() != LOGO_TAP) return@mapNotNull null
        val tap = instruction as FiveRegisterInstruction
        if (tap.registerCount == 2) index + 1 to tap.registerC else null
    }

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
            method.logoTaps().mapTo(sites) { (index, register) -> LogoSite(method, index, register) }
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
    val found = sites.singleOrNull() ?: run {
        patchLog.warning("HushTumblr settings: ${sites.size} places give the dashboard logo its tap, so a long press on the logo doesn't open the settings")
        return false
    }
    // Only once there's a site to call it from: a call to a method the extension lacks would crash Tumblr.
    requireExtensionHook(WHAT, DASHBOARD_LOGO_HOOK)
    val method = mutableOf(found.method)
    val where = "${method.definingClass}->${method.name}"
    // Hide the diamond and TumblrMart puts its hook into the same bind, earlier on, and either patch
    // can run first, so the place is read again from the method as it is now.
    val (index, register) = method.logoTaps().singleOrNull() ?: run {
        patchLog.warning("HushTumblr settings: $where no longer gives the dashboard logo its tap once, so a long press on the logo doesn't open the settings")
        return false
    }
    // The logo is only in its register on the path from the tap's call. Without exception ranges,
    // a label there can only be a branch target, which is also what the release check refuses.
    val implementation = method.implementation!!
    if (implementation.tryBlocks.isNotEmpty() ||
        (implementation.instructions[index] as BuilderInstruction).location.labels.isNotEmpty()
    ) {
        patchLog.warning("HushTumblr settings: $where branches past the dashboard logo's tap or catches around it, so a long press on the logo doesn't open the settings")
        return false
    }
    method.addInstruction(index, "invoke-static/range { v$register .. v$register }, $DASHBOARD_LOGO_HOOK")
    return true
}
