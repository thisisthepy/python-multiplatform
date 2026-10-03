package python.multiplatform


/**
 * The embedded CPython version, as configured for the build.
 *
 * This used to be an enum whose only entry was `PYTHON_3_13_0`, validated against
 * [BuildConfig.pythonVersion] at class-initialisation time. That made every version bump a source
 * change, and failing to make it did not produce a build error — it produced an
 * `ExceptionInInitializerError` the first time anything touched the FFI at runtime, which is how
 * moving the build to 3.14.7 broke the Android path while every compile target still passed.
 *
 * The version is now parsed rather than whitelisted, so the configured value flows through without
 * a matching source edit.
 */
class Versions private constructor(val versionString: String) {

    companion object {
        val currentVersion: Versions by lazy { parse(BuildConfig.pythonVersion) }

        /**
         * Parses `major.minor[.patch][suffix]`, e.g. `3.14.7` or `3.15.0rc1`.
         *
         * Rejects anything without at least a major and minor component, since [compactVersionString]
         * — which names the shared library and the stdlib directory — depends on both.
         */
        fun parse(versionString: String): Versions {
            val parts = versionString.split(".")
            require(parts.size >= 2) {
                "Unsupported Python version '$versionString': expected at least major.minor"
            }
            require(parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                "Unsupported Python version '$versionString': major and minor must be numeric"
            }
            return Versions(versionString)
        }
    }

    override fun toString(): String = versionString

    override fun equals(other: Any?): Boolean =
        other is Versions && other.versionString == versionString

    override fun hashCode(): Int = versionString.hashCode()

    /** `major.minor`, as used in `libpython3.14.so` and the `python3.14/` stdlib directory. */
    val compactVersionString: String
        get() = versionString.split(".").take(2).joinToString(".")

    /**
     * CPython's ABI flags suffix: `t` on a free-threaded build (PEP 703), empty otherwise.
     *
     * This is a property of the *build*, not of the version -- 3.14 ships in both flavours out of
     * the same source tree -- so it comes from [BuildConfig.pythonFreeThreaded] rather than from
     * [versionString].
     */
    val abiFlags: String
        get() = if (BuildConfig.pythonFreeThreaded) "t" else ""

    /**
     * `major.minor` plus [abiFlags]: `3.14` on a default build, `3.14t` free-threaded.
     *
     * Everything CPython names after itself carries this rather than [compactVersionString]: the
     * shared library (`libpython3.14t.dylib`), the stdlib directory (`lib/python3.14t/`), the
     * interpreter (`bin/python3.14t`). Use it wherever a library name or a path is being built;
     * use [compactVersionString] only where the release line itself is what is meant.
     */
    val taggedVersionString: String
        get() = compactVersionString + abiFlags

    val majorVersion: Int
        get() = versionString.split(".")[0].toInt()

    val minorVersion: Int
        get() = versionString.split(".")[1].toInt()

    /**
     * The patch component, or `null` when the configured version carries none or carries a
     * pre-release suffix such as `0rc1` that is not a plain integer.
     */
    val patchVersion: Int?
        get() = versionString.split(".").getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull()
}
