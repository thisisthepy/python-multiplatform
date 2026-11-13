package python.multiplatform

/** Android (ART) embeds python.org's GIL CPython until #328. See the `expect` in commonMain. */
internal actual val embedsFreeThreadedPython: Boolean = false
