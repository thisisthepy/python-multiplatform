package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull


/**
 * That [AndroidNativePlatform] describes *this* device, checked against a reading taken from
 * outside the process.
 *
 * ### Why the expectations arrive in the environment
 *
 * The regression this guards is a constant: `versionCode = 0`, `arch = "Kotlin/Native"`. A constant
 * is indistinguishable from a real read as long as the only thing checking it is a range or a
 * "not blank" test -- `versionCode = 26` hardcoded would pass every assertion in
 * [PlatformIdentityTest] on both emulators. What separates the two is that a real read *differs
 * between devices*, and a single test process only ever sees one device, so it cannot observe the
 * difference on its own.
 *
 * Hardcoding the pair we happen to own (26 and 36) does not work either: the same binary is pushed
 * to whatever device is attached, so the suite would start failing the moment it ran on a phone, or
 * on an emulator someone recreated at a different level. The assertion would then be about our
 * machine rather than about the code.
 *
 * So the expected values are read over adb by the Gradle task that pushes this binary -- `getprop
 * ro.build.version.sdk`, `ro.build.version.release`, `ro.product.cpu.abi` -- and handed over in the
 * environment. That makes the oracle external to the process *and* device-specific: on the API 26
 * emulator this test demands 26 and on the API 36 one it demands 36, so a value that does not move
 * with the device cannot satisfy both, and no expectation is baked into the source.
 *
 * The variables are required, not optional. A test that quietly skips when its input is missing is
 * how this class would stop guarding anything the next time the harness is rewired; the only
 * supported way to run this binary is `:python-multiplatform:androidNative<Arch>Test`, which always
 * sets them.
 */
@OptIn(ExperimentalForeignApi::class)
class AndroidNativeDeviceIdentityTest {

    private fun harnessValue(name: String): String {
        val raw = getenv(name)?.toKString()
        assertNotNull(
            raw,
            "$name is not set. The expected values come from `adb shell getprop` in the " +
                "androidNative test task; run this binary through " +
                "`:python-multiplatform:androidNativeArm64Test` (or ...X64Test) rather than by hand."
        )
        assertEquals(true, raw.isNotBlank(), "$name is set but empty")
        return raw.trim()
    }

    @Test
    fun apiLevelMatchesTheDevice() {
        val expected = harnessValue("PMP_DEVICE_API_LEVEL").toInt()
        assertEquals(
            expected, currentPlatform.versionCode,
            "android_get_device_api_level() reported ${currentPlatform.versionCode}, but this " +
                "device's ro.build.version.sdk is $expected."
        )
    }

    @Test
    fun osReleaseMatchesTheDevice() {
        val expected = harnessValue("PMP_DEVICE_RELEASE")
        assertEquals(
            expected, currentPlatform.version,
            "Platform.version is '${currentPlatform.version}', but this device's " +
                "ro.build.version.release is '$expected'."
        )
    }

    /**
     * `uname(2)` gives the kernel's machine name and `ro.product.cpu.abi` gives Android's ABI name;
     * they are different spellings of the same fact, which is what makes one a usable check on the
     * other. Only the two ABIs this project builds for are mapped -- an unmapped one is a failure
     * rather than a skip, because it would mean the binary ran somewhere unplanned.
     */
    @Test
    fun archMatchesTheDeviceAbi() {
        val abi = harnessValue("PMP_DEVICE_ABI")
        val expected = when (abi) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> throw AssertionError(
                "This device reports ro.product.cpu.abi='$abi'. Only arm64-v8a and x86_64 are " +
                    "built (see abiFilters), so there is no expected uname machine name for it."
            )
        }
        assertEquals(
            expected, currentPlatform.arch,
            "uname().machine is '${currentPlatform.arch}', but this device's ABI is '$abi'."
        )
    }

    /**
     * The failure that started this: the measurement header, which is the only place these fields
     * are consumed, printed `Android Kotlin/Native (SDK 0, Kotlin/Native)` identically on both
     * emulators. Asserting on the composed string is what ties the fields above to the thing that
     * actually reads them.
     */
    @Test
    fun measurementHeaderIdentifiesTheDevice() {
        val api = harnessValue("PMP_DEVICE_API_LEVEL")
        val release = harnessValue("PMP_DEVICE_RELEASE")
        val name = currentPlatform.name
        assertEquals(
            "Android $release (SDK $api, ${currentPlatform.arch}) / Native", name,
            "The platform name that goes into every measurement header does not describe this device."
        )
        // Printed so the two devices' recorded output can be compared directly, which is the
        // observation this whole class exists to make possible.
        println("PLATFORM-IDENTITY $name")
    }
}
