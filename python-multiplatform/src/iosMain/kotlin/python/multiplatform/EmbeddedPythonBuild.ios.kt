package python.multiplatform

/** iOS embeds the project's own free-threaded Python.xcframework, 3.15t (#328). See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = true
