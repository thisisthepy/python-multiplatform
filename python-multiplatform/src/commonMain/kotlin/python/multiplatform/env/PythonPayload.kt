package python.multiplatform.env

/**
 * The consumer's own Python code, put on `sys.path` before the first import.
 *
 * ### What was missing, and what the contract is
 *
 * Every path in this library was for the *standard library*: `PYTHONHOME` and [PythonHomeCheck] on
 * every platform, `PythonBootstrap.stageStdlib` on Android. Nothing read a consumer's own payload,
 * and this repository's own source said so twice in the same sentence (`PythonProxySource` and
 * `PythonxAdapter`: *"getting one generated file into each of those, and onto `sys.path` before the
 * first import, is a per-platform packaging problem this repository has not solved for anything"*).
 * `toolchain`'s `49da1d8` then delivered a payload into the desktop jar's root and the APK's assets
 * and recorded the same finding from the other side: nothing reads it there.
 *
 * The only written contract is the producing side's — `pypackpack`'s `ResourceBundler` KDoc:
 *
 * > **Payload root is `python/`.** The bundle directory holds a `python/` subdirectory intended to
 * > be placed on `sys.path` verbatim (staged into Android assets, an iOS resource directory, or a
 * > desktop resource folder).
 *
 * [PAYLOAD_ROOT] is that name, and it is deliberately the *only* constant: the three destinations
 * differ in where `python/` lands, not in what it is called.
 *
 * ### Where in start-up this happens, and why it cannot be earlier
 *
 * `Python3.initialize()` calls [installStagedRoots] immediately after `Py_Initialize()` has
 * returned and the main thread state has been parked, before it hands control back to its caller —
 * so before any `PyImport_ImportModule` this library can reach.
 *
 * It cannot happen earlier, and that is a property of CPython rather than a choice: **`sys.path`
 * does not exist until `Py_Initialize()` has built it.** `PySys_GetObject("path")` before that
 * point has no interpreter to read from. The two mechanisms that *do* run before initialisation —
 * `PYTHONHOME` (an environment variable read by `getenv(3)`) and [PythonHomeCheck] (a filesystem
 * probe of that variable) — are both about the standard library, and neither has a place to put a
 * consumer directory. So the payload is strictly a post-`Py_Initialize` step, and the useful
 * question is only whether it happens before the caller can import anything. It does.
 *
 * The alternative considered was `PYTHONPATH`, which *is* read at initialisation and would need no
 * post-step. It was rejected: a JVM cannot set an environment variable for itself (see
 * `desktopMain/README.md` — the same reason `PYTHONHOME` is set by the Gradle plugin as the
 * process is launched), so on the platform this had to work on first it is not available at all.
 *
 * ### It stays automatic
 *
 * [autoInstall] is on by default. An embedder who wants to place the payload themselves turns it
 * off before calling `Python3.initialize()` and calls [install] with a path of their own; the
 * discovery each platform does is [discoverStagedPayloadRoots].
 */
object PythonPayload {

    /**
     * `python` — `ResourceBundler.PYTHON_ROOT`, the directory name its manifest records as
     * `pythonRoot`, and what `toolchain`'s `PythonStagingLayout.PAYLOAD_ROOT` stages under on all
     * three platforms.
     */
    const val PAYLOAD_ROOT: String = "python"

    /**
     * Whether `Python3.initialize()` puts discovered payload roots on `sys.path` for you.
     *
     * Set it to false *before* initialising to take the job over — nothing re-reads it afterwards.
     */
    var autoInstall: Boolean = true

    private val installedRoots = mutableListOf<String>()

    /** The payload roots put on `sys.path` so far, in the order they were installed. */
    val installed: List<String> get() = installedRoots.toList()

    /**
     * Puts [directory] at `sys.path[0]` and records it.
     *
     * @return true when it was added, false when it was already on the path.
     * @throws IllegalArgumentException when [directory] is not a readable path. Refusing is the
     *   point: a payload root that silently is not there turns into a `ModuleNotFoundError` for
     *   the consumer's own package, which reads as "my code was not packaged" rather than "the
     *   directory I was given does not exist".
     */
    fun install(directory: String): Boolean {
        require(pathIsAccessible(directory)) {
            "Python payload root '$directory' does not exist or is not readable, so putting it on " +
                "sys.path would only turn a packaging failure into a ModuleNotFoundError later."
        }
        val added = PythonPath.prepend(directory)
        if (!installedRoots.contains(directory)) installedRoots.add(directory)
        return added
    }

    /**
     * Discovers this platform's staged payload roots and installs each one.
     *
     * Installed in reverse so that the first root [discoverStagedPayloadRoots] returned ends up
     * first on `sys.path`: each [install] prepends, so the last one prepended wins.
     *
     * @return the roots installed, in path order.
     */
    fun installStagedRoots(): List<String> {
        val roots = discoverStagedPayloadRoots()
        roots.asReversed().forEach { install(it) }
        return roots
    }

    /**
     * The `Python3.initialize()` hook. Does nothing when [autoInstall] is off, and does its work at
     * most once per process.
     *
     * The once-only flag is not an optimisation, though it is also that — discovery is filesystem
     * work on every platform and a class path scan on desktop. It is what lets
     * `Python3.initialize()` call this on the path where it *returns early* because the interpreter
     * was already up. That path is real and is exactly where a payload would otherwise be lost:
     * `Python3.isInitialized` is seeded from `Py_IsInitialized()`, so any component that reached
     * `Py_Initialize()` first — Android's `PythonInstrumentationRunner` did precisely this, see
     * `androidMain/README.md` — latches it to true and makes every later `initialize()` a no-op.
     *
     * Failures propagate rather than being swallowed. A payload that was staged but cannot be read
     * is a broken build, and the alternative — starting anyway — produces a `ModuleNotFoundError`
     * at whatever point the application first imports itself, with nothing left to say why.
     */
    internal fun installStagedRootsOnStartup() {
        if (!autoInstall || startupInstallDone) return
        startupInstallDone = true
        installStagedRoots()
    }

    private var startupInstallDone: Boolean = false
}

/**
 * Where this platform's packaging step left the consumer's `python/` payload, as directories that
 * can go on `sys.path` as they are.
 *
 * Returns an empty list when there is no payload, which is the normal state of an application that
 * has not configured one. Implementations may have to *materialise* the directory first — a
 * classpath resource inside a jar and an entry in an APK's asset archive are both readable by the
 * platform and not by CPython's importer, which opens files with `open(2)`.
 */
internal expect fun discoverStagedPayloadRoots(): List<String>
