package python.multiplatform

/** Android (ART) embeds the project's own free-threaded CPython 3.15t (#328). See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = true
