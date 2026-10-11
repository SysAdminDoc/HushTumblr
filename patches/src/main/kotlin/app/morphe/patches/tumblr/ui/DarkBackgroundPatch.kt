/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tumblr.lookup.fieldRef
import app.morphe.patches.tumblr.lookup.hasShape
import app.morphe.patches.tumblr.lookup.instructionList
import app.morphe.patches.tumblr.lookup.isStatic
import app.morphe.patches.tumblr.lookup.methodRef
import app.morphe.patches.tumblr.lookup.mutableOf
import app.morphe.patches.tumblr.lookup.only
import app.morphe.patches.tumblr.lookup.requireExtensionHook
import app.morphe.patches.tumblr.lookup.requireShape
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.parameterRegisterNumber
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.util.extendsClass
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList

private const val PATCH = "Dark background color"
internal const val DARK_BACKGROUND = "$EXTENSION_PACKAGE/ui/DarkBackground;"
internal const val ACTIVITY_THEME_HOOK = "$DARK_BACKGROUND->onActivityTheme(Landroid/app/Activity;)V"
internal const val COMPOSE_THEME_HOOK = "$DARK_BACKGROUND->onComposeTheme(Landroid/content/res/Resources\$Theme;)V"

/** The style the patch adds. The extension's `DarkBackground.STYLE` looks it up by this name. */
internal const val DARK_BACKGROUND_STYLE = "HushTumblrDarkBackground"

/** Where the style goes. ARSCLib takes a resource's type from its file's name. */
internal const val DARK_BACKGROUND_STYLES = "res/values/styles.xml"

/**
 * The palette attributes that take the color: the screen behind everything (the window background
 * reads it), its dark twin, the card and second card surfaces, and the navigation bar, which Tumblr
 * paints from its theme right after setting it. The first and third are required, as every other
 * palette surface follows them.
 */
internal val DARK_BACKGROUND_ATTRIBUTES = listOf(
    "themePrimaryColor",
    "themePrimaryColorDark",
    "themeMainContentBackgroundColor",
    "themeSecondaryMainContentBackgroundColor",
    "themeNavigationBarColor",
)
private val REQUIRED_ATTRIBUTES = setOf("themePrimaryColor", "themeMainContentBackgroundColor")

/** A highlighted surface, which gets the color raised by [HIGHLIGHT_STEP] so it still stands apart. */
internal const val HIGHLIGHTED_ATTRIBUTE = "themeHighlightedContentBackgroundColor"
internal const val HIGHLIGHT_STEP = 0x0D

/** The framework's own background attribute, set too when Tumblr's styles name it. */
internal const val WINDOW_BACKGROUND_ATTRIBUTE = "android:colorBackground"

/** The lightest a color may be, as relative luminance, so the palette's light text stays readable on it. */
private const val MAX_LUMINANCE = 0.05

/** The lightest gray [MAX_LUMINANCE] takes, for the option's advice. DarkBackgroundStyleTest holds it. */
internal const val LIGHTEST_ADMITTED_GRAY = "#3F3F3F"

/** Opaque black, as a color int. */
private const val BLACK = -0x1000000

private const val ACTIVITY = "Landroid/app/Activity;"
private const val CONTEXT = "Landroid/content/Context;"
private const val THEME_WRAPPER = "Landroid/view/ContextThemeWrapper;"

/** The kept attribute class and the card surface attribute Tumblr's Compose colors read. */
private const val THEME_ATTRIBUTES = "Lcom/tumblr/themes/R\$attr;"
private const val CARD_ATTRIBUTE = "themeMainContentBackgroundColor"

/**
 * The color the Background color option asks for: black when it's blank, or the `#RRGGBB` given,
 * `#FFRRGGBB` too, as a color picker writes it. Null for anything else, a color that isn't opaque or
 * one too light for the palette's light text included.
 */
internal fun darkBackgroundColor(value: String?): Int? =
    parsedColor(value)?.takeIf { luminance(it) <= MAX_LUMINANCE }

/** The opaque color [value] gives, black when it's blank, before its lightness is judged. */
private fun parsedColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#").orEmpty()
    if (hex.isEmpty()) return BLACK
    if (hex.length != 6 && hex.length != 8) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    if (hex.length == 8 && !hex.startsWith("ff", ignoreCase = true)) return null
    return (0xFF000000L or hex.takeLast(6).toLong(16)).toInt()
}

/** Why the Background color option refuses [value], in words the person patching can act on, or null. */
internal fun darkBackgroundProblem(value: String?): String? {
    val hex = value?.trim()?.removePrefix("#").orEmpty()
    val looksLikeAColor = (hex.length == 6 || hex.length == 8) && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    return when {
        !looksLikeAColor && hex.isNotEmpty() ->
            "Background color \"$value\" isn't a color. Type it as a six-digit color code (#RRGGBB), like #121212, " +
                "or leave it blank for black."
        parsedColor(value) == null ->
            "Background color $value is see-through. Type a solid six-digit color code (#RRGGBB), like #121212."
        darkBackgroundColor(value) == null ->
            "Background color $value is too light for Tumblr's text to stay readable. Pick a darker color. " +
                "Grays up to $LIGHTEST_ADMITTED_GRAY work."
        else -> null
    }
}

/** [color] with [step] added to each channel, as far as white. */
internal fun raisedColor(color: Int, step: Int): Int {
    fun channel(shift: Int) = minOf(0xFF, (color shr shift and 0xFF) + step) shl shift
    return BLACK or channel(16) or channel(8) or channel(0)
}

/** WCAG 2.2's relative luminance of an opaque color. */
private fun luminance(color: Int): Double {
    fun linear(shift: Int): Double {
        val channel = (color shr shift and 0xFF) / 255.0
        return if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
}

private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

/**
 * Adds [DARK_BACKGROUND_STYLE] to Tumblr's default styles: each of [DARK_BACKGROUND_ATTRIBUTES] set
 * to [color], [HIGHLIGHTED_ATTRIBUTE] a step lighter, and [WINDOW_BACKGROUND_ATTRIBUTE] when the file
 * names it. Each attribute is written the way the file's own styles write it, so an app attribute
 * keeps whatever package prefix the decoder gave it. The style has no parent: it only goes over a
 * theme that already has the rest. Refuses a file that already has the style, or whose styles never
 * set a required attribute.
 */
internal fun Document.addDarkBackgroundStyle(color: Int) {
    val resources = documentElement
    if (resources?.tagName != "resources") throw PatchException("$DARK_BACKGROUND_STYLES isn't a resources file")
    val styles = resources.getElementsByTagName("style").elements()
    if (styles.any { it.getAttribute("name") == DARK_BACKGROUND_STYLE }) {
        throw PatchException("$DARK_BACKGROUND_STYLES already has $DARK_BACKGROUND_STYLE")
    }
    val written = styles.flatMap { it.getElementsByTagName("item").elements() }.map { it.getAttribute("name") }.toSet()
    fun nameOf(attribute: String): String? = written.firstOrNull { name ->
        name == attribute || (!name.startsWith("android:") && name.substringAfter(':') == attribute)
    }

    val values = linkedMapOf<String, Int>()
    for (attribute in DARK_BACKGROUND_ATTRIBUTES) {
        val name = nameOf(attribute)
        if (name == null) {
            if (attribute in REQUIRED_ATTRIBUTES) {
                throw PatchException("$PATCH: no style in $DARK_BACKGROUND_STYLES sets $attribute")
            }
            continue
        }
        values[name] = color
    }
    nameOf(HIGHLIGHTED_ATTRIBUTE)?.let { values[it] = raisedColor(color, HIGHLIGHT_STEP) }
    if (WINDOW_BACKGROUND_ATTRIBUTE in written) values[WINDOW_BACKGROUND_ATTRIBUTE] = color

    resources.appendChild(createElement("style").apply {
        setAttribute("name", DARK_BACKGROUND_STYLE)
        values.forEach { (name, value) ->
            appendChild(createElement("item").apply {
                setAttribute("name", name)
                textContent = "#%08x".format(value)
            })
        }
    })
}

/** The style the extension puts over a Dark Mode theme, in the color [background] answers. */
private fun darkBackgroundStylePatch(background: () -> Int) = resourcePatch {
    execute {
        if (!get(DARK_BACKGROUND_STYLES, false).exists()) {
            throw PatchException("$PATCH: Tumblr has no $DARK_BACKGROUND_STYLES")
        }
        document(DARK_BACKGROUND_STYLES).use { it.addDarkBackgroundStyle(background()) }
    }
}

/**
 * Puts the patch's style over Tumblr's Dark Mode palette while its switch is on: right after Tumblr
 * sets an activity's theme, and right after its Compose colors make the theme they read from.
 * `DarkBackground` checks the theme is Dark Mode's (its screen color is Tumblr's own dark one) and
 * leaves every other palette alone. The color is the Background color option's, written into the
 * style when patching.
 */
@Suppress("unused")
val darkBackgroundPatch = bytecodePatch(
    name = PATCH,
    description = "Makes the background of Tumblr's Dark Mode palette black, which can save battery on OLED screens, or" +
        " another dark color you pick when patching. Other palettes stay as they are. Starts off. Turn it on in" +
        " Tumblr settings > HushTumblr > Interface.",
    default = true,
) {
    category("Interface")

    // Written into a style, so it's chosen when patching, not in HushTumblr's settings.
    val backgroundOption by colorOption(
        key = "backgroundColor",
        default = "#000000",
        title = "Background color",
        description = "The color the Dark Mode palette's background turns, as a six-digit color code (#RRGGBB), like " +
            "#121212. Leave it blank for black. Cards get the same color and highlighted parts a slightly lighter one. " +
            "Grays up to $LIGHTEST_ADMITTED_GRAY work, and lighter colors are refused so text stays readable.",
        required = false,
    ) { darkBackgroundColor(it) != null }
    val background = {
        darkBackgroundColor(backgroundOption)
            ?: throw PatchException(
                darkBackgroundProblem(backgroundOption) ?: "Background color $backgroundOption can't be used",
            )
    }

    dependsOn(settingsPatch, tumblrExtensionPatch, darkBackgroundStylePatch(background))
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("darkBackground")
        requireStatusMethod("themeBackground")
        // Both found and checked before either edit, so a refusal leaves the APK as it was.
        val hooks = resolveThemeHooks()
        hooks.activity.addInstructions(
            hooks.activityIndex,
            "invoke-static/range { v${hooks.activityRegister} .. v${hooks.activityRegister} }, $ACTIVITY_THEME_HOOK",
        )
        hooks.compose.addInstructions(
            hooks.composeIndex,
            "invoke-static/range { v${hooks.composeRegister} .. v${hooks.composeRegister} }, $COMPOSE_THEME_HOOK",
        )
        enableCapability("themeBackground")
        enableStatus("darkBackground")
    }
}

/** The two places a theme gets the style, each as a method, the index the call goes in at, and the register it reads. */
internal class ThemeHooks(
    val activity: MutableMethod,
    val activityIndex: Int,
    val activityRegister: Int,
    val compose: MutableMethod,
    val composeIndex: Int,
    val composeRegister: Int,
)

/**
 * Finds where Tumblr sets an activity's theme and where its Compose colors make theirs, throwing
 * when either is missing, doubled or shaped differently.
 *
 * The activity side is the one instance method taking an activity and an int that calls the
 * activity's own `setTheme(int)` on its first parameter and then reads its class's companion
 * instance (Tumblr 46.5.0.109's palette helper, `J` on the theme companion). The parameter's class
 * must reach `android.app.Activity`. The call goes in right after `setTheme`, before the helper
 * repaints the navigation bar from the theme.
 *
 * The Compose side is the one `(Context, int)` constructor that wraps the context in a
 * `ContextThemeWrapper`, takes its theme, and reads the kept card surface attribute from it. The
 * call goes in right after the theme's `move-result-object`, before any color is read. Neither shape
 * can be an Object method. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
internal fun BytecodePatchContext.resolveThemeHooks(): ThemeHooks {
    requireExtensionHook(PATCH, ACTIVITY_THEME_HOOK)
    requireExtensionHook(PATCH, COMPOSE_THEME_HOOK)
    val setters = mutableListOf<Method>()
    val composers = mutableListOf<Method>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.forEach { method ->
            if (method.setsActivityTheme()) setters += method
            if (method.makesComposeTheme()) composers += method
        }
    }

    val setter = setters.only(PATCH, "the method that sets an activity's theme from its palette")
    val activityType = setter.parameterTypes[0].toString()
    requireShape(PATCH, extendsClass(activityType, ACTIVITY),"$activityType, whose theme Tumblr sets, isn't an activity")
    val setterBody = setter.instructionList()
    val set = setterBody.indexOfFirst { it.isSetThemeOn(activityType) }
    val activityRegister = (setterBody[set] as FiveRegisterInstruction).registerC

    val composer = composers.only(PATCH, "the constructor that makes the theme Tumblr's Compose colors read")
    val composerBody = composer.instructionList()
    val asked = composerBody.withIndex().filter { (_, instruction) -> instruction.isThemeWrapperTheme() }
        .map { it.index }.only(PATCH, "the Compose colors' call for their theme")
    val answer = composerBody.getOrNull(asked + 1)
    requireShape(PATCH, answer?.opcode == Opcode.MOVE_RESULT_OBJECT, "the Compose colors' theme isn't kept in a register")

    return ThemeHooks(
        activity = mutableOf(setter),
        activityIndex = set + 1,
        activityRegister = activityRegister,
        compose = mutableOf(composer),
        composeIndex = asked + 2,
        composeRegister = (answer as OneRegisterInstruction).registerA,
    )
}

/** An instance `(activity, int)` method that calls `setTheme(int)` on its activity, then reads its companion. */
private fun Method.setsActivityTheme(): Boolean {
    if (isStatic() || returnType != "V" || parameterTypes.size != 2 || parameterTypes[1].toString() != "I") return false
    val activity = parameterTypes[0].toString()
    if (!activity.startsWith("L")) return false
    val body = instructionList()
    val set = body.indexOfFirst { it.isSetThemeOn(activity) }
    if (set < 0) return false
    val next = body.getOrNull(set + 1)
    return (body[set] as FiveRegisterInstruction).registerC == parameterRegisterNumber(0) &&
        next?.opcode == Opcode.SGET_OBJECT && next.fieldRef()?.type == definingClass
}

private fun Instruction.isSetThemeOn(activity: String): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && methodRef()?.let { call ->
        call.definingClass == activity && call.name == "setTheme" && call.hasShape(listOf("I"), "V")
    } == true

/** A `(Context, int)` constructor that makes a theme wrapper, asks it for its theme and reads the card surface attribute. */
private fun Method.makesComposeTheme(): Boolean {
    if (name != "<init>" || !hasShape(listOf(CONTEXT, "I"), "V")) return false
    val body = instructionList()
    return body.any { it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as? TypeReference)?.type == THEME_WRAPPER } &&
        body.any { it.isThemeWrapperTheme() } &&
        body.any { it.isCardAttribute() }
}

internal fun Instruction.isCardAttribute(): Boolean =
    opcode == Opcode.SGET && fieldRef()?.let { it.definingClass == THEME_ATTRIBUTES && it.name == CARD_ATTRIBUTE } == true

private fun Instruction.isThemeWrapperTheme(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && methodRef()?.let { call ->
        call.definingClass == THEME_WRAPPER && call.name == "getTheme" && call.parameterTypes.isEmpty()
    } == true
