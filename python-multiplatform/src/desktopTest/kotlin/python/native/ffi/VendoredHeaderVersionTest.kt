package python.native.ffi

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The vendored CPython header tree under `src/nativeInterop/cinterop/include` must describe the
 * same CPython the build actually ships.
 *
 * ## Why this needs a test, when the build itself cannot drift
 *
 * For the *build*, headers and runtime cannot disagree: `build.gradle.kts` derives both the
 * `cinterop` include path and the linked library from the one `pythonVersion`/`libVersion` pair,
 * out of a single checksum-verified extraction (`docs/design/ecosystem.md`, "Version check (2026-08-17)").
 * `build.gradle.kts` never references `src/nativeInterop/cinterop/include` at all -- only
 * `.../cinterop/license` and `.../cinterop/lib`.
 *
 * The *evidence layer* is where the drift lives, and nothing enforced it. Two suites open that
 * vendored directory and treat its text as the truth about the C API:
 *
 * - [EmbedApiSurfaceTest] derives the `Py_DEPRECATED(...)` schedule the `expect` surface is
 *   checked against.
 * - [JniCallConventionClassificationTest] derives the `PyAPI_FUNC` prototypes that decide each
 *   JNI registration's call convention.
 *
 * The tree landed at `b184cba5`, before `pythonVersion` became a Gradle property, and its
 * `patchlevel.h` read `PY_VERSION "3.13.0+"` while `gradle.properties` had moved to `3.14.7`. A
 * function deprecated or re-signatured in 3.14 would have been classified from 3.13's text, and
 * both suites would have agreed with themselves -- passing, and wrong. No test could see it,
 * because no test compared the two versions.
 *
 * ## What this test does not claim
 *
 * It compares versions, not contents. It cannot tell you that the tree is a faithful copy of the
 * shipped headers, only that it claims the same version number. That is the cheap check that would
 * have caught the actual 3.13/3.14 split, and it is deliberately not more than that: a
 * content-level comparison would need the extraction present, which makes the check unavailable
 * exactly when the extraction has not been run.
 *
 * ## Watched failing before it was trusted
 *
 * Against the tree as it stood before the header refresh, this test reported:
 *
 * ```
 * expected:<[3.14.7]> but was:<[3.13.0]>
 * ```
 *
 * with the message naming `patchlevel.h`'s `PY_VERSION "3.13.0+"` against `gradle.properties`'s
 * `pythonVersion=3.14.7`. [theEvidenceThisTestReadsIsPresentAndNotVacuous] is the canary for the
 * other direction: a parser that silently found nothing would make the version comparison pass
 * for the wrong reason.
 */
class VendoredHeaderVersionTest {

    @Test
    fun theVendoredHeaderTreeIsTheSameCPythonTheBuildShips() {
        val header = vendoredVersion()
        assertEquals(
            configuredPythonVersion,
            header,
            "the vendored CPython headers under src/nativeInterop/cinterop/include are version " +
                "$header, but gradle.properties declares pythonVersion=$configuredPythonVersion. " +
                "Two test suites (EmbedApiSurfaceTest, JniCallConventionClassificationTest) read " +
                "that tree as their source of truth about the C API -- a deprecation or signature " +
                "change between the two versions would be judged from the wrong headers, and both " +
                "suites would still pass. Refresh the tree from the extraction the build already " +
                "produces: python-multiplatform/build/python-standalone/extracted/" +
                "$configuredPythonVersion/android-aarch64/prefix/include/python$libVersion/"
        )
    }

    /**
     * Canary. Every assertion above is a comparison of two strings pulled out of files by regex;
     * if either regex stops matching, the comparison becomes vacuous rather than failing. So the
     * inputs are checked to exist and to be non-empty first.
     */
    @Test
    fun theEvidenceThisTestReadsIsPresentAndNotVacuous() {
        assertTrue(includeRoot.isDirectory, "no vendored CPython header tree at $includeRoot")
        assertTrue(
            File(includeRoot, "Python.h").isFile,
            "$includeRoot has no Python.h; this is not a CPython header tree"
        )
        val headerCount = includeRoot.walkTopDown().count { it.isFile && it.extension == "h" }
        assertTrue(
            headerCount > 100,
            "only $headerCount .h files under $includeRoot; the two suites that derive the " +
                "deprecation schedule and the JNI call conventions from this tree would be " +
                "reading a fraction of the C API and passing for that reason"
        )
        assertTrue(
            configuredPythonVersion.matches(Regex("""\d+\.\d+\.\d+""")),
            "pythonVersion in gradle.properties is '$configuredPythonVersion', which is not a " +
                "three-part version; the comparison in the other test would be against garbage"
        )
        assertTrue(
            vendoredVersion().matches(Regex("""\d+\.\d+\.\d+""")),
            "could not parse PY_MAJOR/MINOR/MICRO_VERSION out of $patchlevel"
        )
    }

    private companion object {

        /** `python-multiplatform/`, found by walking up from wherever Gradle started the JVM. */
        val moduleDirectory: File by lazy {
            val marker = "src/commonMain/kotlin/python/native/ffi/EmbedAPI.kt"
            var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
            while (candidate != null) {
                if (File(candidate, marker).isFile) return@lazy candidate
                val nested = File(candidate, "python-multiplatform")
                if (File(nested, marker).isFile) return@lazy nested
                candidate = candidate.parentFile
            }
            fail(
                "could not find python-multiplatform/$marker by walking up from " +
                    "${System.getProperty("user.dir")}; this test reads the vendored headers out " +
                    "of the sources and cannot run from a packaged artefact"
            )
        }

        val includeRoot: File by lazy { File(moduleDirectory, "src/nativeInterop/cinterop/include") }

        val patchlevel: File by lazy { File(includeRoot, "patchlevel.h") }

        /**
         * `pythonVersion` from the root `gradle.properties` -- the one variable the build derives
         * both the header include path and the linked library from.
         */
        val configuredPythonVersion: String by lazy {
            val properties = File(moduleDirectory.parentFile, "gradle.properties")
            assertTrue(properties.isFile, "no gradle.properties at $properties")
            val loaded = Properties().apply { properties.inputStream().use { load(it) } }
            loaded.getProperty("pythonVersion")
                ?: fail("gradle.properties at $properties declares no pythonVersion")
        }

        /** `3.14` from `3.14.7` -- CPython's own `include/pythonX.Y` directory name. */
        val libVersion: String by lazy { configuredPythonVersion.split(".").take(2).joinToString(".") }

        /**
         * `PY_MAJOR/MINOR/MICRO_VERSION` rather than `PY_VERSION`: the string form carries build
         * suffixes (the tree this test was written against read `"3.13.0+"`), and comparing those
         * to a plain `3.14.7` would fail for the wrong reason on any legitimate `+` build.
         */
        fun vendoredVersion(): String {
            assertTrue(patchlevel.isFile, "no patchlevel.h at $patchlevel")
            val text = patchlevel.readText()
            fun define(name: String): String =
                Regex("""^\s*#\s*define\s+$name\s+(\d+)\s*$""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1)
                    ?: fail("no #define $name in $patchlevel")
            return "${define("PY_MAJOR_VERSION")}.${define("PY_MINOR_VERSION")}.${define("PY_MICRO_VERSION")}"
        }
    }
}
