package python.multiplatform

/** macosArm64 and linuxX64 link python-build-standalone's free-threaded CPython (issue #158). See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = true
