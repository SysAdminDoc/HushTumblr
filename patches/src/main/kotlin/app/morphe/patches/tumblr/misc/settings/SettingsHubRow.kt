/*
 * Copyright 2026 HushTumblr contributors
 * https://github.com/SysAdminDoc/HushTumblr
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
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
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.freeLocalsAt
import app.morphe.patches.tumblr.misc.extension.requireLocals
import app.morphe.patches.tumblr.misc.extension.requireStub
import app.morphe.patches.tumblr.misc.extension.writeStub
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val SETTINGS_HUB_ROW = "$EXTENSION_PACKAGE/settings/SettingsHubRow;"

/** The string the row's title reads. The extension's `SettingsHubRow.TITLE` looks it up by this name. */
internal const val SETTINGS_ROW_TITLE = "hushtumblr_settings_row"

/** The row's title, the project's name, so it needs no translation. */
internal const val SETTINGS_ROW_LABEL = "HushTumblr"

/** Where the row's title goes. ARSCLib takes a resource's type from its file's name. */
internal const val SETTINGS_ROW_STRINGS = "res/values/strings.xml"

/** The package of Tumblr's Settings screen, a kept name. */
internal const val SETTINGS_HUB = "Lcom/tumblr/settingshub/impl/hub/"

/** The kept R class and field the screen's first row is titled with. */
private const val ROW_STRINGS = "Lcom/tumblr/core/ui/R\$string;"
private const val PREFERENCES_ROW_TITLE = "settings_hub_row_preferences"

/** The kept package of the outline icons the screen's rows use. */
private const val ICONS = "Lcom/tumblr/compose/ds/icons/outline/"

/** The icon for the row, with the next ones to try if a build drops it. */
private val ROW_ICONS = listOf("HideKt" to "getHide", "ShieldKt" to "getShield", "SettingsKt" to "getSettings")

private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val WHAT = "HushTumblr settings (the row in Tumblr's Settings)"

/** Adds the row's title to Tumblr's default strings, refusing a file that already has one. */
internal fun Document.addSettingsRowTitle() {
    val resources = documentElement
    if (resources?.tagName != "resources") throw PatchException("$SETTINGS_ROW_STRINGS isn't a resources file")
    val strings = resources.getElementsByTagName("string")
    if ((0 until strings.length).any { (strings.item(it) as Element).getAttribute("name") == SETTINGS_ROW_TITLE }) {
        throw PatchException("$SETTINGS_ROW_STRINGS already has $SETTINGS_ROW_TITLE")
    }
    resources.appendChild(createElement("string").apply {
        setAttribute("name", SETTINGS_ROW_TITLE)
        textContent = SETTINGS_ROW_LABEL
    })
}

/** The row's title, the one resource the settings patch adds. */
internal val settingsRowTitlePatch = resourcePatch {
    execute {
        // Patcher 2.0 decodes only the files a patch asks for.
        decode(SETTINGS_ROW_STRINGS)
        document(SETTINGS_ROW_STRINGS).use { it.addSettingsRowTitle() }
    }
}

/** Everything the row needs, found before anything changes. */
internal class SettingsHubPlan(
    val builder: MutableMethod,
    val builderIndex: Int,
    val list: Int,
    val free: List<Int>,
    val rowType: String,
    val rowConstructor: String,
    val titleGetter: String,
    val kindField: String,
    val iconReceiverField: String,
    val iconGetter: String,
    val key: MutableMethod,
    val keyIndex: Int,
    val keyRow: Int,
    val keyAnswer: Int,
    val click: MutableMethod,
    val clickRowField: String,
)

/**
 * Finds the three places in Tumblr's Settings screen the row needs. Tumblr 46.5.0.109 builds the
 * screen's rows in one method of its view model, which titles the first with the kept
 * `R.string.settings_hub_row_preferences`: it adds each row to a list and hands the list back
 * through a static `(List)List` call. A row is plain data with a kind (an enum), an int title, an
 * icon and a nullable Integer subtitle, and only two methods read its kind: the static key the
 * screen's lazy list tells rows apart by, which a lambda calls, and the tap handler, which reads
 * its row from a field. Found by reading Tumblr 46.5.0.109 (2026-10-11). In 46.8.1.100 the key
 * is private and the lambda, outside the screen's package, reaches it through a synthetic
 * accessor, so the key hook follows that accessor to its one caller (2026-10-22).
 */
internal fun BytecodePatchContext.resolveSettingsHub(): SettingsHubPlan {
    requireExtensionHook(WHAT, "$SETTINGS_HUB_ROW->titleRes()I")
    requireExtensionHook(WHAT, "$SETTINGS_HUB_ROW->rowKey($OBJECT$OBJECT)$OBJECT")
    requireExtensionHook(WHAT, "$SETTINGS_HUB_ROW->onClick($OBJECT)Z")
    requireStub(SETTINGS_HUB_ROW, "titleOf")

    val hub = mutableListOf<Method>()
    classDefForEach { owner -> if (owner.type.startsWith(SETTINGS_HUB)) hub += owner.methods }

    val builderMethod = hub.filter { method ->
        !method.isStatic() && method.hasShape(emptyList(), LIST) && method.instructionList().any { instruction ->
            instruction.opcode == Opcode.SGET &&
                instruction.fieldRef()?.let { it.definingClass == ROW_STRINGS && it.name == PREFERENCES_ROW_TITLE } == true
        }
    }.only(WHAT, "the method that lists the Settings screen's rows")
    val builderBody = builderMethod.instructionList()

    // The row's class, from the first row the builder makes, and its constructor without defaults.
    val rowType = builderBody.firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
        ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        ?: throw PatchException("$WHAT: the row list makes no row")
    val rowClass = classDefByOrNull(rowType) ?: throw PatchException("$WHAT: no $rowType")
    val constructor = rowClass.methods.filter { method ->
        method.name == "<init>" && method.parameterTypes.size == 4 &&
            method.parameterTypes[1].toString() == "I" && method.parameterTypes[3].toString() == INTEGER
    }.only(WHAT, "the row's constructor taking a kind, a title, an icon and a subtitle")
    val kindType = constructor.parameterTypes[0].toString()
    val iconType = constructor.parameterTypes[2].toString()
    requireShape(WHAT, builderBody.any { it.opcode == Opcode.INVOKE_DIRECT && it.methodRef()?.sameAs(constructor) == true },
        "the row list never calls the row constructor it would add the HushTumblr row with")
    // The row is a data class, so its hashCode()I has the title getter's shape too.
    val titleGetter = rowClass.methods.filter { !it.isStatic() && it.name != "hashCode" && it.hasShape(emptyList(), "I") }
        .only(WHAT, "the row's title getter")
    val kindGetter = rowClass.methods.filter { !it.isStatic() && it.hasShape(emptyList(), kindType) }
        .only(WHAT, "the row's kind getter")

    // The kind it borrows: the first row's, Preferences.
    val kinds = builderBody.mapNotNull { instruction ->
        instruction.fieldRef()?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.type == kindType }
    }
    val kind = kinds.firstOrNull { it.name == "PREFERENCES" } ?: kinds.firstOrNull()
        ?: throw PatchException("$WHAT: the row list reads no row kind")

    // The icon: the receiver every outline icon getter in the list takes, then one of ROW_ICONS.
    val receiverType = builderBody.mapNotNull { instruction ->
        instruction.methodRef()?.takeIf { call ->
            instruction.opcode == Opcode.INVOKE_STATIC && call.definingClass.startsWith(ICONS) &&
                call.parameterTypes.size == 1 && call.returnType == iconType
        }?.parameterTypes?.single()?.toString()
    }.distinct().only(WHAT, "the receiver the row icons are drawn from")
    val receiverField = builderBody.mapNotNull { instruction ->
        instruction.fieldRef()?.takeIf { instruction.opcode == Opcode.SGET_OBJECT && it.type == receiverType }
    }.distinctBy { it.descriptor() }.only(WHAT, "the field holding the icons' receiver")
    val icon = ROW_ICONS.firstNotNullOfOrNull { (owner, name) ->
        classDefByOrNull("$ICONS$owner;")?.methods?.firstOrNull { method ->
            method.name == name && method.isStatic() && method.hasShape(listOf(receiverType), iconType)
        }
    } ?: throw PatchException("$WHAT: none of ${ROW_ICONS.joinToString { it.second }} is in this build")

    // Where the list goes back: a static (List)List call on it, its result, the return.
    val size = builderBody.size
    val handBack = builderBody.getOrNull(size - 3)
    requireShape(WHAT, handBack != null && builderBody[size - 1].opcode == Opcode.RETURN_OBJECT &&
        builderBody[size - 2].opcode == Opcode.MOVE_RESULT_OBJECT && handBack.opcode == Opcode.INVOKE_STATIC &&
        handBack.methodRef()?.hasShape(listOf(LIST), LIST) == true && (handBack as FiveRegisterInstruction).registerCount == 1,
        "the row list isn't handed back through one static call on it")
    val list = (handBack as FiveRegisterInstruction).registerC
    requireShape(WHAT, list <= 15, "the row list is in v$list, past what the added code can name")
    val builder = mutableOf(builderMethod)
    val free = builder.freeLocalsAt(WHAT, size - 3, 5, reads = listOf(list))

    // The key: the one static method reading a row's kind, and the one method that calls it.
    val keyFunction = hub.filter { method ->
        method.isStatic() && method.hasShape(listOf(rowType), OBJECT) &&
            method.instructionList().any { it.methodRef()?.sameAs(kindGetter) == true }
    }.only(WHAT, "the key the Settings screen's list holds a row by")
    var keyTarget: Method = keyFunction
    var keyCaller = hub.filter { method -> method.instructionList().any { it.methodRef()?.sameAs(keyTarget) == true } }
        .only(WHAT, "the call for a row's key")
    // 46.8.1.100 makes the key private: a one-register synthetic accessor in the screen's package
    // forwards to it, so the row and its key share v0 there. A lambda outside the package calls
    // that accessor with them apart, so the hook goes in the accessor's one caller instead.
    if (keyCaller.forwardsTo(keyFunction)) {
        keyTarget = keyCaller
        val callers = mutableListOf<Method>()
        classDefForEach { owner ->
            owner.methods.filterTo(callers) { method -> method.instructionList().any { it.methodRef()?.sameAs(keyTarget) == true } }
        }
        keyCaller = callers.only(WHAT, "the call for a row's key through its accessor")
    }
    val keyBody = keyCaller.instructionList()
    val keyCall = keyBody.indexOfFirst { it.methodRef()?.sameAs(keyTarget) == true }
    val keyResult = keyBody.getOrNull(keyCall + 1)
    val keyReturn = keyBody.getOrNull(keyCall + 2)
    requireShape(WHAT, keyResult?.opcode == Opcode.MOVE_RESULT_OBJECT && keyReturn?.opcode == Opcode.RETURN_OBJECT &&
        (keyReturn as OneRegisterInstruction).registerA == (keyResult as OneRegisterInstruction).registerA,
        "a row's key isn't handed straight back")
    val keyRow = (keyBody[keyCall] as FiveRegisterInstruction).registerC
    val keyAnswer = (keyResult as OneRegisterInstruction).registerA
    requireShape(WHAT, keyRow != keyAnswer && keyRow <= 15 && keyAnswer <= 15,
        "the row and its key don't sit in two registers the added call can name")

    // The tap: the one instance method reading a row's kind, on a class that holds its row in a field.
    val clickMethod = hub.filter { method ->
        !method.isStatic() && method.hasShape(emptyList(), "V") &&
            method.instructionList().any { it.methodRef()?.sameAs(kindGetter) == true }
    }.only(WHAT, "the tap on a row")
    val clickRowField = classDefByOrNull(clickMethod.definingClass)?.fields
        ?.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == rowType }.orEmpty()
        .only(WHAT, "the field the tap reads its row from")
    val click = mutableOf(clickMethod)
    click.requireLocals(WHAT, 1)

    return SettingsHubPlan(
        builder = builder,
        builderIndex = size - 3,
        list = list,
        free = free,
        rowType = rowType,
        rowConstructor = constructor.descriptor(),
        titleGetter = titleGetter.descriptor(),
        kindField = kind.descriptor(),
        iconReceiverField = receiverField.descriptor(),
        iconGetter = icon.descriptor(),
        key = mutableOf(keyCaller),
        keyIndex = keyCall + 2,
        keyRow = keyRow,
        keyAnswer = keyAnswer,
        click = click,
        clickRowField = clickRowField.descriptor(),
    )
}

/**
 * Adds the HushTumblr row at the end of Tumblr's Settings screen, gives it its own key and opens
 * HushTumblr settings when it's tapped. The row borrows the first row's kind, and only the key and
 * the tap read a row's kind, so both go through the extension before Tumblr acts on it.
 */
internal fun BytecodePatchContext.addSettingsHubRow(plan: SettingsHubPlan) {
    writeStub(SETTINGS_HUB_ROW, "titleOf", 2, """
        check-cast p0, ${plan.rowType}
        invoke-virtual {p0}, ${plan.titleGetter}
        move-result v0
        return v0
    """)

    val (title, kind, icon, none, row) = plan.free
    plan.builder.addInstructionsWithLabels(
        plan.builderIndex,
        """
            invoke-static {}, $SETTINGS_HUB_ROW->titleRes()I
            move-result v$title
            if-eqz v$title, :hush_settings_row_skip
            sget-object v$kind, ${plan.kindField}
            sget-object v$icon, ${plan.iconReceiverField}
            invoke-static {v$icon}, ${plan.iconGetter}
            move-result-object v$icon
            const/4 v$none, 0x0
            new-instance v$row, ${plan.rowType}
            invoke-direct {v$row, v$kind, v$title, v$icon, v$none}, ${plan.rowConstructor}
            invoke-interface {v${plan.list}, v$row}, $LIST->add($OBJECT)Z
        """,
        ExternalLabel("hush_settings_row_skip", plan.builder.getInstruction(plan.builderIndex)),
    )

    plan.key.addInstructions(
        plan.keyIndex,
        """
            invoke-static {v${plan.keyRow}, v${plan.keyAnswer}}, $SETTINGS_HUB_ROW->rowKey($OBJECT$OBJECT)$OBJECT
            move-result-object v${plan.keyAnswer}
        """,
    )

    plan.click.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, ${plan.clickRowField}
            invoke-static {v0}, $SETTINGS_HUB_ROW->onClick($OBJECT)Z
            move-result v0
            if-eqz v0, :hush_settings_row_tumblr
            return-void
        """,
        ExternalLabel("hush_settings_row_tumblr", plan.click.getInstruction(0)),
    )
}

/** A static synthetic accessor whose whole body hands its one argument to [target] and returns the result. */
private fun Method.forwardsTo(target: Method): Boolean {
    val body = instructionList()
    return isStatic() && AccessFlags.SYNTHETIC.isSet(accessFlags) && body.size == 3 &&
        body[0].opcode == Opcode.INVOKE_STATIC && body[0].methodRef()?.sameAs(target) == true &&
        (body[0] as FiveRegisterInstruction).registerCount == 1 &&
        body[1].opcode == Opcode.MOVE_RESULT_OBJECT && body[2].opcode == Opcode.RETURN_OBJECT
}

/** `Lclass;->name(params)result`, as smali names a method. */
internal fun MethodReference.descriptor(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** `Lclass;->name:type`, as smali names a field. */
internal fun FieldReference.descriptor(): String = "$definingClass->$name:$type"
