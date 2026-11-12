package python.multiplatform

/** iOS embeds the GIL Python.xcframework until #328. See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = false
