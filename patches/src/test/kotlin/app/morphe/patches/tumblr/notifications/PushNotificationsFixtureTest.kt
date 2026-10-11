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

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tumblr.misc.extension.SETTINGS_STATUS
import app.morphe.patches.tumblr.misc.settings.EXTENSION_ROOT
import app.morphe.patches.tumblr.privacy.TUMBLR_CERTIFICATE_SHA1
import app.morphe.patches.tumblr.privacy.callReference
import app.morphe.patches.tumblr.privacy.identity
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.value.ImmutableStringEncodedValue
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Firebase's request header builder in each declared APK, found by its texts, and the certificate
 * hook placed between the builder's own fingerprint answer and the certificate header's write.
 * Tumblr 46.5.0.109 writes the header as its name, the fingerprint call on the builder, that call's
 * answer and the write, so the write sits three instructions after the name.
 */
@Category(FixtureTests::class)
class PushNotificationsFixtureTest {
    @Test
    fun `each declared original APK sends the certificate header's value through the hook`() {
        for (build in Fixtures.declaredBuilds()) {
            val firebase = read(build)
            val owner = firebase.single { header(it) != null }
            val original = header(owner)!!
            val context = PatchContexts.of(ExtensionDex.classes() + firebase)
            val plan = context.resolveFirebaseHeader()
            assertEquals("${build.name}: requests", setOf(FirebaseRequest.CREATE, FirebaseRequest.TOKEN), plan.requests.keys)
            fixPushNotificationsPatch.execute(context)
            for (flag in listOf("fixPushNotifications", "firebaseCertificate")) {
                val first = context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == flag }.implementation!!.instructions.first()
                assertEquals("${build.name}: $flag", 1, (first as NarrowLiteralInstruction).narrowLiteral)
            }
            val before = instructions(original)
            val after = instructions(context.mutableClassDefBy(owner.type).methods.single { it.identity() == original.identity() })
            val name = before.indexOfFirst { it.string() == "X-Android-Cert" }
            val sink = name + 3
            val write = before[sink] as FiveRegisterInstruction
            assertEquals("${build.name}: the write the plan names", sink, plan.index)
            assertEquals("${build.name}: only the hook and its result are added", before.size + 2, after.size)
            assertEquals(build.name, before.take(sink).map { it.opcode }, after.take(sink).map { it.opcode })
            assertEquals(build.name, CERTIFICATE_HOOK, after[sink].callReference()?.identity())
            assertEquals("${build.name}: the hook takes the connection and the value",
                listOf(write.registerC, write.registerE), (after[sink] as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            assertEquals(build.name, Opcode.MOVE_RESULT_OBJECT, after[sink + 1].opcode)
            assertEquals(build.name, write.registerE, (after[sink + 1] as OneRegisterInstruction).registerA)
            assertEquals(build.name, before.drop(sink).map { it.opcode }, after.drop(sink + 2).map { it.opcode })
            println("${build.name}: Firebase's header builder ${original.identity()}, requests " +
                plan.requests.entries.joinToString { "${it.key} ${it.value.identity()}" })
        }
    }

    @Test
    fun `the extension answers with the SHA-1 of the certificate the source carries`() {
        val hook = ExtensionDex.classes().single { it.type == PUSH_NOTIFICATIONS }
        val value = hook.fields.single { it.name == "TUMBLR_CERTIFICATE_SHA1" }.initialValue
        assertEquals(TUMBLR_CERTIFICATE_SHA1.uppercase(), (value as StringEncodedValue).value)
    }

    @Test
    fun `a changed builder, an unknown caller or a missing hook refuses before any change`() {
        val firebase = read(Fixtures.declaredBuilds().last())
        val owner = firebase.single { header(it) != null }
        val builder = header(owner)!!
        val body = instructions(builder)
        val name = body.indexOfFirst { it.string() == "X-Android-Cert" }
        val sink = name + 3
        val write = body[sink] as FiveRegisterInstruction
        val extension = ExtensionDex.classes()
        fun without(type: String, method: String) = extension.map { if (it.type != type) it else copy(it, it.methods.filterNot { m -> m.name == method }) }
        fun builderWith(change: (MutableList<Instruction>) -> Unit) = firebase.map { if (it !== owner) it else
            copy(owner, owner.methods.map { m -> if (m !== builder) m else replace(m, instructions(m).toMutableList().also(change)) }) }
        val wrongCertificate = extension.map { if (it.type != PUSH_NOTIFICATIONS) it else ImmutableClassDef(it.type, it.accessFlags,
            it.superclass, it.interfaces, it.sourceFile, it.annotations, it.fields.map { field ->
                if (field.name != "TUMBLR_CERTIFICATE_SHA1") field else retitled(field, "0".repeat(40)) }, it.methods) }
        // A static method that hands the builder a URL and a key and has no request texts of its own.
        val stranger = ImmutableClassDef("Lfixture/Stranger;", 1, "Ljava/lang/Object;", null, null, null, null, listOf(
            ImmutableMethod("Lfixture/Stranger;", "ask", listOf(ImmutableMethodParameter(owner.type, null, null),
                ImmutableMethodParameter("Ljava/net/URL;", null, null), ImmutableMethodParameter("Ljava/lang/String;", null, null)),
                "V", 9, null, null, ImmutableMethodImplementation(3, listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, builder),
                    ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null))))
        val cases = listOf(
            Triple("no header builder", "request header builder has 0 matches", extension + firebase.filter { it !== owner }),
            Triple("two header builders", "request header builder has 2 matches", extension + firebase + moved(owner, "Lfixture/SecondHeaders;")),
            Triple("a caller that's no Firebase request", "has a caller it doesn't know", extension + firebase + stranger),
            Triple("missing hook", "certificate hook can't be called", without(PUSH_NOTIFICATIONS, "certificateHeader") + firebase),
            Triple("missing status stub", "no boolean method firebaseCertificate()", without(SETTINGS_STATUS, "firebaseCertificate") + firebase),
            Triple("an extension with another fingerprint", "doesn't answer with Tumblr's certificate", wrongCertificate + firebase),
            Triple("the value written is the header name", "straight into one header write", extension + builderWith {
                it[sink] = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, write.registerC, write.registerD, write.registerD, 0, 0,
                    (write as ReferenceInstruction).reference as MethodReference)
            }),
            // The texts all stay, so the builder is still found; only the API key write's value changes.
            Triple("the API key header takes another value", "no longer followed by the API key header", extension + builderWith {
                val apiWrite = it[sink + 2] as FiveRegisterInstruction
                it[sink + 2] = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, apiWrite.registerC, apiWrite.registerD, write.registerE, 0, 0,
                    (apiWrite as ReferenceInstruction).reference as MethodReference)
            }),
            Triple("another register returned", "the connection's return", extension + builderWith {
                it[sink + 3] = ImmutableInstruction11x(Opcode.RETURN_OBJECT, write.registerE)
            }),
        )
        for ((reason, says, input) in cases) {
            val context = PatchContexts.of(input)
            val before = snapshot(context, input)
            val failure = assertThrows(reason, PatchException::class.java) { fixPushNotificationsPatch.execute(context) }
            assertTrue("$reason: ${failure.message}", failure.message.orEmpty().contains(says))
            assertArrayEquals("$reason left retained edits", before, snapshot(context, input))
        }
    }

    @Test
    fun `a moved anchor, a changed address or a path around a header refuses before any change`() {
        for (build in Fixtures.declaredBuilds()) {
            val input = ExtensionDex.classes() + read(build)
            val changes = listOf(
                "key" to "request header builder has 0 matches",
                "sink" to "straight into one header write",
                "url" to "request URL factory has 0 matches",
                "create" to "has a caller it doesn't know",
                "token" to "has a caller it doesn't know",
                "bypass" to "reaches the certificate header write without its name",
                "fingerprint" to "straight into one header write",
                "apiKey" to "request header builder has 0 matches",
                "apiConnection" to "no longer followed by the API key header",
                "apiBypass" to "no longer followed by the API key header",
                "packageName" to "package header no longer goes into the same connection first",
                "packageConnection" to "package header no longer goes into the same connection first",
                "packageOverwritten" to "package header no longer goes into the same connection first",
                "packageBypass" to "reaches the certificate header without the package header",
                "packageValue" to "package header's value is no longer the app's package name",
                "connectionReassigned" to "connection register changes between the package header and the certificate header",
                "hiddenHook" to "certificate hook can't be called",
            )
            for ((change, says) in changes) {
                val context = PatchContexts.of(input)
                val plan = context.resolveFirebaseHeader()
                val method = plan.method
                val body = instructions(method)
                val write = body[plan.index] as FiveRegisterInstruction
                val packageName = body.indexOfFirst { it.string() == "X-Android-Package" }
                val packageWrite = (packageName + 1 until plan.index).first { body[it].callReference()?.name == "addRequestProperty" }
                val packageHeader = body[packageWrite] as FiveRegisterInstruction
                // Firebase reads the package name just before the header's name, or between the name and the write.
                val packageValue = if (packageWrite == packageName + 1) packageName - 2 else packageWrite - 2
                fun rewrite(index: Int, connection: Int, name: Int, value: Int) = method.replaceInstruction(index,
                    "invoke-virtual {v$connection, v$name, v$value}, ${body[index].callReference()!!.identity()}")
                // A branch on the key parameter, so the stock path stays reachable beside the new edge.
                fun bypassTo(index: Int) = method.addInstructionsWithLabels(0, "if-eqz v${method.implementation!!.registerCount - 1}, :bypass",
                    ExternalLabel("bypass", method.getInstruction(index)))
                fun text(owner: MutableMethod, from: String, to: String) {
                    val at = instructions(owner).indexOfFirst { it.string() == from }
                    owner.replaceInstruction(at, "const-string v${(instructions(owner)[at] as OneRegisterInstruction).registerA}, \"$to\"")
                }
                when (change) {
                    "key" -> text(method, "X-Android-Cert", "X-Unrelated-Cert")
                    "sink" -> rewrite(plan.index, write.registerC, write.registerE, write.registerD)
                    "url" -> text(context.mutableClassDefBy(method.definingClass).methods.single { owner ->
                        instructions(owner).any { it.string() == FIREBASE_INSTALLATIONS_HOST } }, FIREBASE_INSTALLATIONS_HOST, "unrelated.invalid")
                    "create", "token" -> {
                        val request = plan.requests.getValue(if (change == "create") FirebaseRequest.CREATE else FirebaseRequest.TOKEN)
                        text(context.mutableClassDefBy(request.definingClass).methods.single { it.identity() == request.identity() },
                            if (change == "create") "x-goog-fis-android-iid-migration-auth"
                            else "projects/%s/installations/%s/authTokens:generate", "changed-request")
                    }
                    "bypass" -> bypassTo(plan.index)
                    // The certificate's value comes from somewhere other than the builder's own fingerprint method.
                    "fingerprint" -> method.replaceInstruction(plan.index - 2,
                        "invoke-virtual {v${(body[plan.index - 2] as FiveRegisterInstruction).registerC}}, " +
                            "Ljava/lang/Object;->toString()Ljava/lang/String;")
                    "apiKey" -> text(method, "x-goog-api-key", "x-unrelated-key")
                    "apiConnection" -> (body[plan.index + 2] as FiveRegisterInstruction).let { rewrite(plan.index + 2, it.registerD, it.registerD, it.registerE) }
                    "apiBypass" -> bypassTo(plan.index + 2)
                    "packageName" -> rewrite(packageWrite, packageHeader.registerC, packageHeader.registerE, packageHeader.registerE)
                    "packageConnection" -> rewrite(packageWrite, packageHeader.registerE, packageHeader.registerD, packageHeader.registerE)
                    // The name register is set again between the name and the write, so the write sends another name.
                    "packageOverwritten" -> method.addInstruction(packageName + 1, "const-string v${packageHeader.registerD}, \"X-Other-Package\"")
                    "packageBypass" -> bypassTo(packageWrite + 1)
                    "packageValue" -> method.replaceInstruction(packageValue, "invoke-virtual {v${(body[packageValue] as FiveRegisterInstruction)
                        .registerC}}, Landroid/content/Context;->getOpPackageName()Ljava/lang/String;")
                    // The register that held the connection is set again before the certificate header.
                    "connectionReassigned" -> method.addInstruction(packageWrite + 1, "move-object v${write.registerC}, v${write.registerC}")
                    "hiddenHook" -> context.mutableClassDefBy(PUSH_NOTIFICATIONS).methods.single { it.name == "certificateHeader" }
                        .let { it.accessFlags = it.accessFlags and AccessFlags.PUBLIC.value.inv() }
                }
                val before = snapshot(context, input)
                val failure = assertThrows("${build.name}: $change", PatchException::class.java) { fixPushNotificationsPatch.execute(context) }
                assertTrue("${build.name}: $change: ${failure.message}", failure.message.orEmpty().contains(says))
                assertArrayEquals("${build.name}: $change left retained edits", before, snapshot(context, input))
            }
        }
    }

    private fun header(owner: ClassDef): Method? =owner.methods.singleOrNull { method ->
        method.returnType == "Ljava/net/HttpURLConnection;" && FIREBASE_HEADER_TEXTS.all { text -> instructions(method).any { it.string() == text } }
    }

    private fun instructions(method: Method): List<Instruction> = method.implementation?.instructions?.toList().orEmpty()

    private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun replace(method: Method, body: List<Instruction>) = ImmutableMethod(method.definingClass, method.name, method.parameters,
        method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(method.implementation!!.registerCount, body, method.implementation!!.tryBlocks,
            method.implementation!!.debugItems))

    private fun retitled(field: Field, value: String) = ImmutableField(field.definingClass, field.name, field.type, field.accessFlags,
        ImmutableStringEncodedValue(value), field.annotations, field.hiddenApiRestrictions)

    private fun copy(owner: ClassDef, methods: Iterable<Method>) = ImmutableClassDef(owner.type,
        owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations, owner.fields, methods)

    /** [owner] again as [type], each method defined there. */
    private fun moved(owner: ClassDef, type: String) = ImmutableClassDef(type, owner.accessFlags, owner.superclass,
        owner.interfaces, owner.sourceFile, owner.annotations, emptyList(), owner.methods.map {
            ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, it.annotations,
                it.hiddenApiRestrictions, it.implementation)
        })

    private fun snapshot(context: BytecodePatchContext, classes: List<ClassDef>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { pool.internClass(context.mutableClassDefBy(it.type)) }
        val store = MemoryDataStore()
        try {
            pool.writeTo(store)
            return store.data
        } finally {
            store.close()
        }
    }

    /** Every class of Tumblr's own that holds a Firebase Installations text: the header builder and its callers. */
    private fun read(build: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(build) { dex ->
            for (owner in dex.classes) {
                if (owner.type.startsWith(EXTENSION_ROOT)) continue
                val texts = owner.methods.flatMap { instructions(it) }.mapNotNull { it.string() }
                if (texts.any { it == "X-Android-Cert" || it == "Firebase-Installations" }) found += ImmutableClassDef.of(owner)
            }
        }
        if (found.count { header(it) != null } != 1) throw AssertionError("${build.name} has no single Firebase header builder")
        return found
    }
}
