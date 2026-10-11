/*
 * Forked from https://github.com/SysAdminDoc/HushPinterest at debdac4 (GPL-3.0),
 * modified for HushTumblr (Tumblr), 2026.
 *
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.tumblr.privacy

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.tumblr.misc.extension.enableStatus
import app.morphe.patches.tumblr.misc.extension.tumblrExtensionPatch
import app.morphe.patches.tumblr.misc.extension.requireStatusMethod
import app.morphe.patches.tumblr.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import java.security.MessageDigest
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val PATCH = "Spoof signature for Google sign-in"

/** The application meta-data microG-RE reads: the SHA-1 of the certificate to show Google. */
internal const val SPOOFED_SIGNATURE_METADATA = "app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE"

/** The application meta-data signature spoofing modules such as XSpoofSignatures read: the certificate itself. */
internal const val FAKE_SIGNATURE_METADATA = "fake-signature"

/** SHA-1 of Tumblr's own signing certificate, the one the declared build is signed with. */
internal const val TUMBLR_CERTIFICATE_SHA1 = "cd177dd4057daf3af7eca430825ee7dc08ad56fa"

/**
 * Tumblr's own signing certificate (CN=Tumblr, OU=Tumblr, O=Tumblr, L=New York, ST=NY, C=US), DER encoded,
 * as lowercase hex. Copied from the v2 and v3 signing blocks of the declared build, and checked
 * against them by GoogleSignInSpoofManifestTest.
 */
internal const val TUMBLR_CERTIFICATE_DER =
    "30820237308201a0a00302010202044c660dcf300d06092a864886f70d01010505003060310b300906035504061302" +
        "5553310b3009060355040813024e593111300f060355040713084e657720596f726b310f300d060355040a13065475" +
        "6d626c72310f300d060355040b130654756d626c72310f300d0603550403130654756d626c72301e170d3130303831" +
        "343033333032335a170d3335303830383033333032335a3060310b3009060355040613025553310b30090603550408" +
        "13024e593111300f060355040713084e657720596f726b310f300d060355040a130654756d626c72310f300d060355" +
        "040b130654756d626c72310f300d0603550403130654756d626c7230819f300d06092a864886f70d01010105000381" +
        "8d0030818902818100d2968907237096d0f83cfe7dcd69b95bab0fb2afc3ec8df55206298397a639bcc5239b0cc110" +
        "5ec4e3b9cafa15fe0531d2c2204c97426d53596c9c2764fff6e878587e54b97c1fa55b36c2a3e41a6d0d613bd5a554" +
        "412708d684c83369125ae83fcfccb6b16ec2a0ea0d1cf25e25af391fc6eea2583738827eae6dfe65a47e1b02030100" +
        "01300d06092a864886f70d01010505000381810067e6f972410ac9b86d1ebb496bd0441b695f26923482851c3def54" +
        "5b39b3869f56c1d99ba7ff3881b98dc0d6477ee9411e3ddbb5350bfef88e1b80d284572a25e7bc140164c801548a86" +
        "fb22d87affe89a480d26673e231563eb95583f20d569f77786a8b876edd042c8343e567d95ae1dacfca59d231e5da3" +
        "d5b140ad4969ea"

/** The two application meta-data entries the patch adds, in the order it adds them. */
internal val SIGNATURE_SPOOF_METADATA: Map<String, String> = linkedMapOf(
    SPOOFED_SIGNATURE_METADATA to TUMBLR_CERTIFICATE_SHA1,
    FAKE_SIGNATURE_METADATA to TUMBLR_CERTIFICATE_DER,
)

/** Lowercase hex SHA-1 of [hex] read as bytes. */
internal fun sha1OfHex(hex: String): String {
    require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' }) { "not lowercase hex" }
    val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    return MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Adds both [SIGNATURE_SPOOF_METADATA] entries to the application. The certificate has to hash to
 * the SHA-1 beside it, and a manifest that already names either key refuses, so nothing a
 * signature module reads is ever written twice or left half changed. The FAKE_PACKAGE_SIGNATURE
 * permission is left out of the manifest: where something else already defines it, a second
 * definition from another signer makes Android refuse the install as a duplicate permission.
 */
internal fun addSignatureSpoofMetadata(document: Document) {
    if (sha1OfHex(TUMBLR_CERTIFICATE_DER) != TUMBLR_CERTIFICATE_SHA1) {
        throw PatchException("$PATCH: the certificate doesn't match its SHA-1")
    }
    val application = document.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("$PATCH: AndroidManifest.xml has no application element")
    val declared = application.childNodes.let { nodes -> (0 until nodes.length).mapNotNull { nodes.item(it) as? Element } }
        .filter { it.tagName == "meta-data" }.map { it.getAttribute("android:name") }
    SIGNATURE_SPOOF_METADATA.keys.firstOrNull { it in declared }?.let {
        throw PatchException("$PATCH: AndroidManifest.xml already declares $it")
    }
    for ((name, value) in SIGNATURE_SPOOF_METADATA) {
        application.appendChild(document.createElement("meta-data").apply {
            setAttribute("android:name", name)
            setAttribute("android:value", value)
        })
    }
}

/** Checks the status flag first, so a broken extension refuses before the manifest is changed. */
internal val signatureSpoofPreflightPatch = bytecodePatch {
    dependsOn(settingsPatch, tumblrExtensionPatch)
    execute { requireStatusMethod("spoofSignature") }
}

internal val signatureSpoofManifestPatch = resourcePatch {
    dependsOn(signatureSpoofPreflightPatch)
    execute { document("AndroidManifest.xml").use(::addSignatureSpoofMetadata) }
}

@Suppress("unused")
val googleSignInSpoofPatch = bytecodePatch(
    name = PATCH,
    description = "Helps Google sign-in work in the patched app by naming Tumblr's original signature. It only " +
        "helps with microG-RE or the XSpoofSignatures module. Email sign-in doesn't need it. It isn't " +
        "selected by default. Works as soon as you patch it in, with no switch.",
    default = false,
) {
    category("Privacy")
    dependsOn(settingsPatch, tumblrExtensionPatch, signatureSpoofManifestPatch)
    compatibleWith(*AppCompatibilities.tumblr())

    execute {
        enableStatus("spoofSignature")
    }
}
