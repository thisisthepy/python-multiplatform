package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


/**
 * The contract every [Platform] implementation owes, checked on whichever target is running.
 *
 * This exists because `AndroidNativePlatform` shipped with `arch = "Kotlin/Native"`,
 * `version = "Kotlin/Native"` and `versionCode = 0` -- three `TODO`s that compiled, satisfied the
 * interface, and printed `Android Kotlin/Native (SDK 0, Kotlin/Native)` into every measurement
 * header the androidNative binary produced. Nothing was checking that these fields say anything
 * about the machine, so nothing noticed for as long as no androidNative test actually ran.
 *
 * The assertions here are the ones that hold on *any* device, so they can live in `commonTest` and
 * guard all five targets at once. The device-specific half -- that an API 26 emulator and an API 36
 * emulator report *different* values -- cannot be checked from inside a single test process, which
 * only ever sees one device; that check is `AndroidNativeDeviceIdentityTest` in `artTest`, which
 * compares against values the Gradle harness read over adb.
 */
class PlatformIdentityTest {

    /** Values that were once used as stand-ins for a real read, in this file's own history. */
    private val placeholders = setOf("kotlin/native", "unknown", "todo", "", "null")

    @Test
    fun archNamesAMachineAndNotARuntime() {
        val arch = currentPlatform.arch
        assertTrue(
            arch.lowercase() !in placeholders,
            "Platform.arch is a placeholder (\"$arch\"). It has to name the machine the code is " +
                "running on -- `Platform.is64Bit`, `isArm` and `isX86` all pattern-match on it, and " +
                "`manager.kt` picks which CPython to load from `isArm`."
        )
    }

    @Test
    fun versionNamesTheOperatingSystemRelease() {
        val version = currentPlatform.version
        assertTrue(
            version.lowercase() !in placeholders,
            "Platform.version is a placeholder (\"$version\"). Every other implementation puts the " +
                "user-visible OS release here (Build.VERSION.RELEASE, UIDevice.systemVersion, " +
                "os.version)."
        )
    }

    /**
     * `versionText` is `versionCode` with a label on it, so one is present exactly when the other
     * is. Both directions matter, and the missing one is what let a defect through: iOS declared
     * `versionText = "Build $versionCode"` unconditionally, so a null code was *interpolated* rather
     * than propagated and the field read `"Build null"` -- which `Platform.name` then carried into
     * every banner as `iOS 26.2 (Build null, arm64) / Native`.
     *
     * This test used to only check the `code != null && text != null` corner, which is precisely the
     * corner that case is not in, so it passed throughout. Asserting the full implication is the
     * point of the test; weakening it back to the guarded form re-admits the bug.
     */
    @Test
    fun versionTextAgreesWithVersionCode() {
        val code = currentPlatform.versionCode
        val text = currentPlatform.versionText

        if (code == null) {
            assertNull(
                text,
                "versionCode is null but versionText is \"$text\". A platform that cannot name a " +
                    "version number has nothing to label, so versionText has to be null too -- " +
                    "which is what Platform.versionText's own default getter does. A non-null " +
                    "value here means the null was interpolated into a string instead of " +
                    "propagated."
            )
            return
        }

        assertNotNull(
            text,
            "versionCode is $code but versionText is null; a platform that knows the number has " +
                "no reason to withhold the labelled form of it."
        )
        assertTrue(
            text.contains(code.toString()),
            "versionText (\"$text\") does not mention versionCode ($code); the two are supposed " +
                "to be the same number, one of them labelled."
        )
        assertTrue(
            "null" !in text.lowercase(),
            "versionText (\"$text\") contains the literal word \"null\", so a null was formatted " +
                "into it rather than handled."
        )
    }

    /**
     * [Platform.name] is the user-visible rendering of all of the above, and it is where the
     * `"Build null"` defect was actually observable. Its inputs are each checked above; this checks
     * that assembling them does not reintroduce a placeholder.
     */
    @Test
    fun nameDoesNotRenderAnyAbsentField() {
        val name = currentPlatform.name
        assertTrue(
            "null" !in name.lowercase(),
            "Platform.name is \"$name\". Absent fields are nullable so that `name` can leave them " +
                "out -- it drops versionText when it is null and platformVersion when it is null -- " +
                "so the word \"null\" appearing in the rendering means some field formatted a null " +
                "instead of omitting it."
        )
    }

    @Test
    fun nameCarriesTheFieldsItIsBuiltFrom() {
        val name = currentPlatform.name
        assertTrue(name.contains(currentPlatform.arch), "'$name' omits arch '${currentPlatform.arch}'")
        assertTrue(name.contains(currentPlatform.version), "'$name' omits version '${currentPlatform.version}'")
        assertTrue(name.contains(currentPlatform.os.toString()), "'$name' omits os '${currentPlatform.os}'")
    }

    @Test
    fun platformTypePredicatesAgreeWithPlatformType() {
        val type = currentPlatform.platformType
        assertEquals(type == PlatformType.JVM, currentPlatform.isJvm, "isJvm disagrees with $type")
        assertEquals(type == PlatformType.Native, currentPlatform.isNative, "isNative disagrees with $type")
    }

    @Test
    fun osPredicatesAgreeWithOs() {
        val os = currentPlatform.os
        assertEquals(os == OSType.Android, currentPlatform.isAndroid, "isAndroid disagrees with $os")
        assertEquals(os == OSType.IOS, currentPlatform.isIos, "isIos disagrees with $os")
    }

    /**
     * Android is the one OS in this tree reached through two entirely separate implementations --
     * `AndroidPlatform` over the ART VM and `AndroidNativePlatform` over cinterop -- and they are
     * supposed to describe the same device the same way. Whichever of the two is running has to
     * satisfy this, so the two cannot drift.
     */
    @Test
    fun androidReportsAnApiLevelAndASupportedAbi() {
        if (!currentPlatform.isAndroid) return

        val code = assertNotNull(
            currentPlatform.versionCode,
            "An Android platform must report an API level; Build.VERSION.SDK_INT and " +
                "android_get_device_api_level() both have one."
        )
        assertTrue(
            code >= 21,
            "API level $code is below this module's minSdk (21), so it is not a level any " +
                "supported device reports -- 0 is what the androidNative placeholder used to return."
        )
        assertEquals(
            "SDK $code", currentPlatform.versionText,
            "Android's versionText spelling is fixed by AndroidPlatform; androidNative has to match it."
        )

        // `abiFilters` and the packaged CPython builds cover exactly arm64-v8a and x86_64, so the
        // kernel machine name can only be aarch64 or x86_64 here -- and `manager.kt` routes on
        // precisely this distinction.
        assertTrue(
            currentPlatform.isArm != currentPlatform.isX86,
            "arch '${currentPlatform.arch}' is classified as neither arm nor x86, or as both."
        )
        assertTrue(
            currentPlatform.is64Bit,
            "arch '${currentPlatform.arch}' is not 64-bit, but only arm64-v8a and x86_64 are built."
        )
    }
}
