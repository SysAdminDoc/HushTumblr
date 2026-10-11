/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Forked from https://github.com/SysAdminDoc/HushTelegram at df79f7d (GPL-3.0),
 * modified for HushPinterest (Pinterest), 2026.
 *
 * Copyright 2026 HushTelegram contributors
 * https://github.com/SysAdminDoc/HushTelegram
 */
package app.morphe.patches.tumblr.notifications

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tumblr.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.tumblr.misc.extension.enableCapability
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.patches.tumblr.privacy.TUMBLR_CERTIFICATE_DER
import app.morphe.patches.tumblr.privacy.TUMBLR_CERTIFICATE_SHA1
import app.morphe.patches.tumblr.privacy.sha1OfHex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.RegisterKind
import app.morphe.util.RegisterKinds
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import app.morphe.util.writesRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

private const val PATCH = "Fix push notifications"
internal const val PUSH_NOTIFICATIONS = "$EXTENSION_PACKAGE/notifications/PushNotifications;"
internal const val CERTIFICATE_HOOK =
    "$PUSH_NOTIFICATIONS->certificateHeader(Ljava/net/URLConnection;Ljava/lang/String;)Ljava/lang/String;"
private const val STRING = "Ljava/lang/String;"
private const val HTTP = "Ljava/net/HttpURLConnection;"
private const val CONNECTION = "Ljava/net/URLConnection;"
private const val URL = "Ljava/net/URL;"

/** The header names only Firebase's request header builder holds, all of them. */
internal val FIREBASE_HEADER_TEXTS = listOf("X-Android-Cert", "X-Android-Package", "x-goog-api-key")

/** The text only the builder's own certificate fingerprint method holds. */
internal const val FIREBASE_FINGERPRINT_TEXT = "Could not get fingerprint hash for package: "

/** The host Firebase's request URL factory names. */
internal const val FIREBASE_INSTALLATIONS_HOST = "firebaseinstallations.googleapis.com"

/**
 * A Firebase API key restricted to an Android app answers only requests that name that app's own
 * signing certificate in X-Android-Cert. Firebase fills that header from the certificate the app is
 * installed with, so a patched build's Installations sign-up can be refused with a 403
 * (API_KEY_ANDROID_APP_BLOCKED), Firebase Messaging never gets a token, and no push notification
 * arrives. The header's value goes through the extension, which answers Tumblr's certificate for
 * Firebase's own Installations requests while the switch is on.
 *
 * Firebase's header builder is found by its texts and checked the way Firebase writes it: the
 * package header on every path first, then the certificate header, whose value is the builder's
 * own fingerprint method, then the API key header, then the connection returned. Every caller has
 * to be the Installations sign-up or token request through Firebase's own URL, or the patch
 * refuses. Found by reading Tumblr 46.5.0.109 (2026-10-11).
 */
@Suppress("unused")
val fixPushNotificationsPatch = bytecodePatch(
    name = PATCH,
    description = "Lets the patched app sign up for push notifications. Firebase can refuse a patched Tumblr whose " +
        "request doesn't name Tumblr's original signature, so this sends its fingerprint. On by default. Turn it " +
        "off in Tumblr settings > HushTumblr > More settings > Notifications.",
    default = true,
) {
    category("Notifications")
    dependsOn(settingsPatch, tumblrExtensionPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        requireStatusMethod("fixPushNotifications")
        requireStatusMethod("firebaseCertificate")
        val plan = resolveFirebaseHeader()
        plan.method.addInstructionsAtControlFlowLabel(plan.index, """
            invoke-static {v${plan.connection}, v${plan.value}}, $CERTIFICATE_HOOK
            move-result-object v${plan.value}
        """)
        enableCapability("firebaseCertificate")
        enableStatus("fixPushNotifications")
    }
}

internal enum class FirebaseRequest { CREATE, TOKEN, DELETE }

internal data class FirebaseHeaderPlan(
    val method: MutableMethod, val index: Int, val connection: Int, val value: Int,
    val requests: Map<FirebaseRequest, Method>,
)

/** Every check runs before the first edit. */
internal fun BytecodePatchContext.resolveFirebaseHeader(): FirebaseHeaderPlan {
    requireExtensionCertificate()
    val header = classDefByStrings("X-Android-Cert").filter { !it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { it.methods }.filter { method ->
            method.hasShape(listOf(URL, STRING), HTTP) && FIREBASE_HEADER_TEXTS.all { it in method.strings() }
        }.unique("Firebase's request header builder")
    shape(!AccessFlags.STATIC.isSet(header.accessFlags), "Firebase's request header builder lost its receiver")
    val owner = classDefByOrNull(header.definingClass) ?: throw PatchException("$PATCH: no ${header.definingClass}")
    val address = owner.methods.filter {
        it.hasShape(listOf(STRING), URL) && FIREBASE_INSTALLATIONS_HOST in it.strings()
    }.unique("Firebase's request URL factory")
    val fingerprint = owner.methods.filter {
        it.hasShape(emptyList(), STRING) && FIREBASE_FINGERPRINT_TEXT in it.strings()
    }.unique("Firebase's certificate fingerprint method")

    val requests = linkedMapOf<FirebaseRequest, Method>()
    classDefForEach { candidate ->
        if (candidate.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (caller in candidate.methods) {
            if (caller.instructions().none { it.call()?.same(header) == true }) continue
            val strings = caller.strings()
            // Firebase formats each path from a template, "projects/%s/installations" and so on.
            val role = when {
                strings.any { it.endsWith("/authTokens:generate") } && "POST" in strings -> FirebaseRequest.TOKEN
                strings.any { it.endsWith("/installations") } && "x-goog-fis-android-iid-migration-auth" in strings &&
                    "POST" in strings -> FirebaseRequest.CREATE
                strings.any { "/installations/" in it } && "DELETE" in strings -> FirebaseRequest.DELETE
                else -> throw PatchException("$PATCH: Firebase's header builder has a caller it doesn't know, $caller; " +
                    "refuses before any edit")
            }
            shape(requests.put(role, caller) == null, "more than one Firebase $role request")
            shape(caller.instructions().any { it.call()?.same(address) == true },
                "the Firebase $role request doesn't build its address with Firebase's URL factory")
        }
    }
    shape(FirebaseRequest.CREATE in requests && FirebaseRequest.TOKEN in requests,
        "the Installations sign-up or token request no longer goes through the header builder")

    val method = mutableClassDefBy(header.definingClass).methods.single { it.same(header) }
    val body = method.instructions()
    val literal = body.indices.filter { body[it].string() == "X-Android-Cert" }.unique("certificate header name")
    // The name, then the builder's own fingerprint method on its receiver, its answer, and the write.
    val sink = literal + 3
    val operands = body.getOrNull(sink)?.namedRegisters().orEmpty()
    val value = body.getOrNull(literal + 1)
    shape(body[literal].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        operands.size == 3 && operands.distinct().size == 3 && operands.all { it in 0..15 } &&
        body[literal].namedRegisters() == listOf(operands[1]) && body.getOrNull(sink)?.isHeaderWrite() == true &&
        value != null && value.opcode in setOf(Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL) &&
        value.call()?.same(fingerprint) == true && value.namedRegisters().size == 1 &&
        body[literal + 2].opcode == Opcode.MOVE_RESULT_OBJECT && body[literal + 2].namedRegisters() == listOf(operands[2]),
        "the certificate header no longer takes the builder's own fingerprint straight into one header write")
    val flow = ControlFlow.of(method)
    shape((literal + 1..sink).all { flow.straight(it) },
        "a path reaches the certificate header write without its name and fingerprint")
    // The hook only answers a request that already names Tumblr's package, so Firebase has to
    // write X-Android-Package to the same connection, in a straight run, on every path before.
    val packageName = body.indices.filter { body[it].string() == "X-Android-Package" }.unique("package header name")
    val packageWrite = (packageName + 1 until literal).firstOrNull { body[it].isHeaderWrite() } ?: -1
    val packageOperands = body.getOrNull(packageWrite)?.namedRegisters().orEmpty()
    val nameRegister = body[packageName].namedRegisters().singleOrNull()
    // Its value is the app's own package name, which the hook compares with Tumblr's. Firebase
    // reads it just before the name, or between the name and the write.
    val valueEnd = if (packageWrite == packageName + 1) packageName - 1 else packageWrite - 1
    shape(body[packageName].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) && packageWrite > packageName &&
        valueEnd >= 1 && packageOperands.size == 3 && packageOperands[0] == operands[0] && packageOperands[1] == nameRegister &&
        (packageName + 1 until packageWrite).none { nameRegister in body[it].namedRegisters() } &&
        (minOf(valueEnd - 1, packageName) + 1..packageWrite).all { flow.straight(it) },
        "the package header no longer goes into the same connection first")
    shape(body[valueEnd - 1].opcode == Opcode.INVOKE_VIRTUAL &&
        body[valueEnd - 1].call()?.toString() == "Landroid/content/Context;->getPackageName()Ljava/lang/String;" &&
        body[valueEnd].opcode == Opcode.MOVE_RESULT_OBJECT &&
        body[valueEnd].namedRegisters() == listOf(packageOperands[2]) &&
        (valueEnd + 1 until packageWrite).none { writesRegister(body[it], packageOperands[2]) },
        "the package header's value is no longer the app's package name")
    shape(!flow.reaches(literal, avoiding = packageWrite), "a path reaches the certificate header without the package header")
    shape(!flow.writesOnTheWay(packageWrite, literal, operands[0]),
        "the connection register changes between the package header and the certificate header")
    val kinds = RegisterKinds.of(method).at(sink)
    shape(kinds != null && kinds.getOrNull(operands[0]) == RegisterKind.ref(HTTP) &&
        kinds.getOrNull(operands[2]) in setOf(RegisterKind.ref(STRING), RegisterKind.ZERO),
        "the certificate header isn't written to a connection with a text value")
    // The API key header comes right after, with the method's own key parameter, then the connection is returned.
    val apiKey = body.getOrNull(sink + 1)
    val apiOperands = body.getOrNull(sink + 2)?.namedRegisters().orEmpty()
    shape(apiKey?.string() == "x-goog-api-key" && apiKey.opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        apiOperands.size == 3 && apiOperands.distinct().size == 3 && apiKey.namedRegisters() == listOf(apiOperands[1]) &&
        body.getOrNull(sink + 2)?.isHeaderWrite() == true && apiOperands[0] == operands[0] &&
        apiOperands[2] == method.implementation!!.registerCount - 1 &&
        flow.normal.indices.all { at -> sink + 2 !in flow.normal[at] || at == sink + 1 } &&
        flow.exceptional.none { sink + 2 in it } &&
        body.getOrNull(sink + 3)?.opcode == Opcode.RETURN_OBJECT && body.getOrNull(sink + 3)?.namedRegisters() == listOf(operands[0]),
        "the certificate header is no longer followed by the API key header and the connection's return")
    val hooks = classDefByOrNull(PUSH_NOTIFICATIONS)?.methods?.filter { hook ->
        hook.name == "certificateHeader" && hook.hasShape(listOf(CONNECTION, STRING), STRING) &&
            AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags) && hook.implementation != null
    }.orEmpty()
    shape(hooks.size == 1, "the extension's certificate hook can't be called")
    return FirebaseHeaderPlan(method, sink, operands[0], operands[2], requests)
}

/**
 * The extension answers Firebase with the fingerprint it carries. It has to be the SHA-1 of the
 * Tumblr certificate this source carries, in the upper case Firebase writes.
 */
internal fun BytecodePatchContext.requireExtensionCertificate() {
    if (sha1OfHex(TUMBLR_CERTIFICATE_DER) != TUMBLR_CERTIFICATE_SHA1) {
        throw PatchException("$PATCH: the carried certificate doesn't match its SHA-1")
    }
    val field = classDefByOrNull(PUSH_NOTIFICATIONS)?.fields?.singleOrNull { it.name == "TUMBLR_CERTIFICATE_SHA1" }
    val value = (field?.initialValue as? StringEncodedValue)?.value
    if (field?.type != STRING || value != TUMBLR_CERTIFICATE_SHA1.uppercase()) {
        throw PatchException("$PATCH: the extension doesn't answer with Tumblr's certificate fingerprint")
    }
}

/** [at] is entered only by falling through from the instruction before it. */
private fun ControlFlow.straight(at: Int) = at > 0 && at in normal[at - 1] &&
    normal.indices.none { from -> from != at - 1 && at in normal[from] } && exceptional.none { at in it }

/** Some path from the entry reaches [target] without [avoiding] completing; a write that throws adds nothing. */
private fun ControlFlow.reaches(target: Int, avoiding: Int): Boolean {
    val seen = BooleanArray(instructions.size)
    val pending = ArrayDeque(listOf(0))
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == target) return true
        if (seen[at]) continue
        seen[at] = true
        if (at != avoiding) pending.addAll(normal[at])
        pending.addAll(exceptional[at])
    }
    return false
}

/** Some instruction on a path from after [from] completes to [to] writes [register]. */
private fun ControlFlow.writesOnTheWay(from: Int, to: Int, register: Int): Boolean {
    val seen = BooleanArray(instructions.size)
    val pending = ArrayDeque(normal[from])
    while (pending.isNotEmpty()) {
        val at = pending.removeFirst()
        if (at == to || seen[at]) continue
        seen[at] = true
        if (writesRegister(instructions[at], register)) return true
        pending.addAll(normal[at])
        pending.addAll(exceptional[at])
    }
    return false
}

private fun Instruction.isHeaderWrite() = opcode == Opcode.INVOKE_VIRTUAL && call()?.let {
    it.definingClass in setOf(CONNECTION, HTTP) && it.name == "addRequestProperty" && it.hasShape(listOf(STRING, STRING), "V")
} == true
private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.strings() = instructions().mapNotNull { it.string() }.toSet()
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun MethodReference.hasShape(parameters: List<String>, result: String) =
    parameterTypes.map(CharSequence::toString) == parameters && returnType == result
private fun MethodReference.same(other: MethodReference) = definingClass == other.definingClass && name == other.name &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString) && returnType == other.returnType
private fun shape(valid: Boolean, reason: String) {
    if (!valid) throw PatchException("$PATCH: $reason; refuses before any edit")
}
private fun <T> List<T>.unique(what: String): T {
    shape(size == 1, "$what has $size matches")
    return single()
}
