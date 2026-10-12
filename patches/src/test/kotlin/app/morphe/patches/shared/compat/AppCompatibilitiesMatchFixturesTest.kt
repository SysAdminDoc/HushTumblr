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
 * Modified for Hushfacebook (Facebook), 2026.
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.shared.compat

import app.morphe.FixtureTests
import app.morphe.Fixtures
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The signing certificate and version code the bundle declares have to be the ones on Tumblr's
 * own build. Morphe Manager warns about a picked APK whose certificate differs and treats a version
 * code it was not told about as unsupported, so a wrong value here would warn every user who
 * downloaded the genuine release.
 *
 * Each declared version is kept twice: the universal `.apk` the other fixture tests read and the
 * APKMirror `.apkm` split bundle a user downloads. The bundle is checked split by split. The
 * universal APK is checked directly when it verifies; Tumblr ships none, so the one kept is merged
 * from the bundle, and it's checked through [Fixtures.signedForm]: its code must be the bundle's
 * byte for byte, and the bundle's base.apk carries the signature.
 */
@Category(FixtureTests::class)
class AppCompatibilitiesMatchFixturesTest {

    @Test
    fun `every retained vendor build is signed by a declared certificate`() {
        val declared = AppCompatibilities.tumblr().single().signatures.orEmpty()
        assertEquals(
            "declared signatures",
            setOf(AppCompatibilities.TUMBLR_SIGNER_SHA256),
            declared,
        )
        val undeclared = mutableMapOf<String, Set<String>>()
        var checked = 0
        for (fixture in Fixtures.apks() + Fixtures.bundles()) {
            apksIn(fixture) { name, apk ->
                val result = ApkVerifier.Builder(apk).build().verify()
                assertTrue("$name does not verify: ${result.errors}", result.isVerified)
                // v3.1 rotation puts the newer key in its own block, for Android 13 and newer, so
                // the certificates of every v3 and v3.1 signer count beside the headline ones.
                val signers = (result.signerCertificates +
                    result.v3SchemeSigners.flatMap { it.certificates } +
                    result.v31SchemeSigners.flatMap { it.certificates })
                    .map { sha256(it.encoded) }.toSet()
                assertTrue("$name carries no signer", signers.isNotEmpty())
                val other = signers - declared
                if (other.isNotEmpty()) undeclared[name] = other
                checked++
            }
        }
        assertTrue("no fixture APK was checked", checked > 0)
        assertEquals("fixtures a certificate the bundle does not declare signed",
            emptyMap<String, Set<String>>(), undeclared)
    }

    /**
     * minSdk is declared metadata for Morphe Manager, not a mirror of the vendor manifest, so the
     * build's manifest value is only ever a lower bound on it: Manager must never offer the patches
     * on a device the vendor build itself refuses. Only the newest stable build is declared.
     */
    @Test
    fun `every declared target carries the version code of its vendor build and a floor at least as high`() {
        val targets = AppCompatibilities.tumblr().single().targets
        assertEquals(
            "declared versions: the newest stable build only",
            listOf(AppCompatibilities.TUMBLR_TARGET_VERSION),
            targets.map { it.version },
        )
        var checked = 0
        for (target in targets) {
            val version = checkNotNull(target.version)
            val codes = checkNotNull(target.versionCodes) { "the $version target declares no version codes" }
            val fixtures = Fixtures.files {
                (it.extension == "apk" || it.extension == "apkm") && it.name.startsWith("tumblr-$version-")
            }
            for (fixture in fixtures) {
                baseOf(fixture) { base ->
                    // Each read walks the buffer to its end, so each one gets its own view.
                    val manifest = RandomAccessFile(base, "r").use { file ->
                        ApkUtils.getAndroidManifest(DataSources.asDataSource(file))
                    }
                    assertEquals(
                        "${fixture.name} version code",
                        codes.values.toSet().single(),
                        ApkUtils.getVersionCodeFromBinaryAndroidManifest(manifest.duplicate()),
                    )
                    val vendorMinSdk = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest.duplicate())
                    assertTrue(
                        "${fixture.name}: declared minSdk ${target.minSdk} is lower than the build's own $vendorMinSdk, " +
                            "so Manager would offer this patch on a device the vendor build itself refuses",
                        checkNotNull(target.minSdk) { "the $version target declares no minSdk" } >= vendorMinSdk,
                    )
                    checked++
                }
            }
        }
        assertEquals("a universal APK and a bundle for each declared target", targets.size * 2, checked)
    }

    /**
     * One bundle serves every device, its native code split per ABI. Manager hides the patches on
     * a phone whose ABI the target leaves out, so the declared ABIs are exactly the bundle's
     * `split_config.<abi>.apk` splits: one missing would hide the patches from phones the bundle
     * installs on.
     */
    @Test
    fun `every declared target names exactly the ABIs its bundle carries`() {
        var checked = 0
        for (target in AppCompatibilities.tumblr().single().targets) {
            val version = checkNotNull(target.version)
            val declared = checkNotNull(target.versionCodes).keys.map { it.name.lowercase() }.toSortedSet()
            val bundles = Fixtures.files { it.extension == "apkm" && it.name.startsWith("tumblr-$version-") }
            for (bundle in bundles) {
                val splits = ZipFile(bundle).use { zip ->
                    zip.entries().asSequence().mapNotNull { entry ->
                        Regex("split_config\\.(arm64_v8a|armeabi_v7a|x86|x86_64)\\.apk").matchEntire(entry.name)?.groupValues?.get(1)
                    }.toSortedSet()
                }
                assertEquals("${bundle.name}: declared ABIs", splits, declared)
                checked++
            }
        }
        assertTrue("no fixture bundle was checked", checked > 0)
    }

    /** The APK whose manifest names the build: the universal APK itself, or base.apk in a bundle. */
    private fun baseOf(fixture: File, check: (File) -> Unit) {
        if (fixture.extension == "apk") return check(fixture)
        ZipFile(fixture).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${fixture.name} holds no base.apk" }
            val copy = File.createTempFile("fixture-base", ".apk")
            try {
                zip.getInputStream(base).use { input -> copy.outputStream().use { input.copyTo(it) } }
                check(copy)
            } finally {
                copy.delete()
            }
        }
    }

    /**
     * A universal APK's signed form (itself, or the base.apk of the bundle it was merged from), or
     * every split a bundle holds, each copied out to a temp file.
     */
    private fun apksIn(fixture: File, check: (String, File) -> Unit) {
        if (fixture.extension == "apk") {
            val signed = Fixtures.signedForm(fixture)
            return check(signed.name, signed.file)
        }
        ZipFile(fixture).use { zip ->
            val apks = zip.entries().asSequence().filter { it.name.endsWith(".apk") }.toList()
            assertTrue("${fixture.name} holds no APK", apks.isNotEmpty())
            for (entry in apks) {
                val copy = File.createTempFile("fixture-split", ".apk")
                try {
                    zip.getInputStream(entry).use { input -> copy.outputStream().use { input.copyTo(it) } }
                    check("${fixture.name}!${entry.name}", copy)
                } finally {
                    copy.delete()
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
