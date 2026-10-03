package org.thisisthepy.python.multiplatform.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.thisisthepy.python.multiplatform.demo.ui.App
import python.multiplatform.env.PythonBootstrap


private const val TAG = "PythonDemo"

/**
 * Android used to be the one platform where the host app had real work to do before
 * `Py_Initialize`: CPython's stdlib ships inside the library module's assets and has to be
 * unpacked to a readable directory, and `PYTHONHOME` has to name that directory's prefix.
 *
 * That was ~55 lines here — an ABI lookup, a version-derived stdlib path, a recursive
 * `AssetManager` copy, and an "already unpacked?" probe — all of it duplicated in the external
 * consumer app and in the library's own instrumentation fixture, and all of it wrong about the
 * same thing (a copy interrupted partway through looked complete to the probe). It is
 * [PythonBootstrap] now, shipped in the AAR, and the host app's share is one call.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val staging = PythonBootstrap.initialize(this)
        Log.i(
            TAG,
            if (staging.unpacked) {
                "unpacked ${staging.fileCount} stdlib files (${staging.bytes} bytes) for " +
                    "${staging.abi} into ${staging.prefix} in ${staging.elapsedMillis} ms"
            } else {
                "stdlib for ${staging.abi} already staged at ${staging.stdlibDir} " +
                    "(checked in ${staging.elapsedMillis} ms)"
            },
        )

        PythonDemo.start()

        setContent { App() }
    }
}
