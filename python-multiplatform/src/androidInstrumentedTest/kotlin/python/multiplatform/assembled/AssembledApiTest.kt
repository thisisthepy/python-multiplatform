package python.multiplatform.assembled

import python.multiplatform.Versions

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import python.multiplatform.ffi.Python3
import python.native.ffi.PythonOnDevice

/**
 * The assembled layer on Android: `Python3` and the object model, as an application would use
 * them.
 *
 * ### Why this file exists
 *
 * Android's instrumented tests all reach through `bindings` and rebuild each call by hand, and
 * `commonTest` — which does exercise the object model — only runs on iOS and desktop. Nothing
 * ran the real API on a device, so nothing noticed that it does not work there: `Python3.exec`
 * crashes the process on its **first** call, on both API 26 and API 36, because
 * `PyImport_AddModuleRef`, `PyObject_GetAttrString` and `PyRun_String` still take a Kotlin
 * `String` straight across JNI and a Kotlin/Native `String` is not a `jstring`.
 *
 * Fourteen green instrumented tests said nothing about that, which is the point: a test that
 * does not walk the path callers walk proves only that some other path works.
 *
 * ### Why it is disabled
 *
 * These crash the instrumentation **process**, not just the test — a crashed runner takes every
 * other test on the device with it, so leaving them enabled would hide the suite rather than
 * report a failure.
 *
 * Enable this file when ROADMAP §2 lands (the remaining ~340 functions migrated to the
 * primitive-only JNI surface). It is the check for that work: if these pass, the object model
 * is usable on Android; until they do, it is not.
 */
@RunWith(AndroidJUnit4::class)
class AssembledApiTest {

    @Test
    fun interpreterReportsItsVersion() {
        PythonOnDevice.ensureInitialised()
        // Against the *configured* version rather than a literal -- same reason as the four
        // assertions ROADMAP §9 already converted: this checks the interpreter loaded is the one
        // the build configured, instead of pinning a release line that needs hand-editing.
        val expected = Versions.currentVersion.compactVersionString
        val version = Python3.version
        assertTrue("expected $expected.x, got $version", version.startsWith(expected))
    }

    @Test
    fun execRunsAStatement() {
        PythonOnDevice.ensureInitialised()
        Python3.exec("_assembled_probe = 1 + 1")
    }

    @Test
    fun importReturnsAUsableModule() {
        PythonOnDevice.ensureInitialised()
        val sys = Python3.import("sys")
        try {
            val version = sys.getAttr("version")
            try {
                val expected = Versions.currentVersion.compactVersionString
                assertTrue("sys.version was $version", version.toString().startsWith(expected))
            } finally {
                version.close()
            }
        } finally {
            sys.close()
        }
    }

    @Test
    fun attributeAccessRoundTrips() {
        PythonOnDevice.ensureInitialised()
        val sys = Python3.import("sys")
        try {
            val path = sys.getAttr("path")
            try {
                assertTrue("sys.path should be non-empty", path.isTruthy)
            } finally {
                path.close()
            }
        } finally {
            sys.close()
        }
    }

    @Test
    fun callingABuiltinReturnsAValue() {
        PythonOnDevice.ensureInitialised()
        val builtins = Python3.import("builtins")
        try {
            val lenFn = builtins.getAttr("len")
            val strType = builtins.getAttr("str")
            try {
                val s = strType()
                try {
                    val n = lenFn(s)
                    try {
                        assertEquals("0", n.toString())
                    } finally {
                        n.close()
                    }
                } finally {
                    s.close()
                }
            } finally {
                strType.close()
                lenFn.close()
            }
        } finally {
            builtins.close()
        }
    }
}
