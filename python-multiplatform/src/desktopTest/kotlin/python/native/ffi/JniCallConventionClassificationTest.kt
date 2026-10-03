package python.native.ffi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ROADMAP §3 -- leaf vs re-entrant -- as something a machine re-derives on every run, instead of a
 * table in a markdown file that a human is supposed to keep in step with the code.
 *
 * The classification decides which JNI calling convention a function may be bound under.
 * `@FastNative` and `@CriticalNative` both stop the ART collector for the duration of the call, and
 * `@CriticalNative` gets no `JNIEnv`, so nothing underneath one may re-enter the runtime. A function
 * that can execute arbitrary Python -- a module's top-level code, a `__getattr__`, a `__del__`
 * reached by dropping the last reference -- must stay on ordinary JNI. Guessing wrong toward
 * ordinary costs 6-41 ns (`androidMain/README.md`); guessing wrong the other way is a crash, on a
 * device, once upcalls are live.
 *
 * Why this is a test and not a document: `docs/investigations/jni-call-convention-audit.md` classified 71
 * registrations, its own *Resolution* section re-read the table at 187, and the table this test
 * parses today holds a different number again. A hand-maintained classification is stale from the
 * next commit onward, and staleness in this particular table is invisible until it is a crash.
 *
 * ## What is derived and what is enumerated
 *
 * Derived from the CPython headers bundled in `src/nativeInterop/cinterop/include`, per
 * registration, by following the `JNINativeMethod` table into the C wrapper in `jni_onload.def` and
 * out to the CPython entry points that wrapper calls:
 *
 * - [Verdict.RE_ENTRANT_BY_SIGNATURE] -- a `PyObject *` (or thread/interpreter state) crosses the
 *   prototype of something it reaches. Then it can drop a reference, run a type slot, or allocate a
 *   GC-tracked object, and all three can reach `__del__`.
 * - [Verdict.RE_ENTRANT_BY_JNI_UPCALL] -- the wrapper dereferences `JNIEnv` and calls back into the
 *   runtime itself.
 * - [Verdict.LEAF_PROVEN] -- the wrapper body makes no call of any kind. This is the only leafness
 *   this file *proves*.
 * - [Verdict.UNDECIDED] -- everything else. **판정 불가.** No Python object crosses the signature, so
 *   the header cannot condemn it, and nothing available here can clear it either. `Py_Initialize`
 *   (`void Py_Initialize(void)`) and `PyRun_SimpleString` (`int PyRun_SimpleString(const char *)`)
 *   both land here, and both run arbitrary Python: a signature that mentions no `PyObject *` is not
 *   evidence of anything. [theSignatureRuleDoesNotClaimToProveLeafness] pins that down so nobody
 *   re-derives "unresolved entries: none", which is what the original audit claimed and what put
 *   three re-entrant functions on its safe-to-promote list.
 *
 * Enumerated, because it cannot be derived: [PROMOTED], the bindings allowed to carry a GC-blocking
 * convention. Every C API function can fail and CPython reports failure by allocating a GC-tracked
 * exception, so "provably cannot run Python" is not a property any of them has -- the honest test is
 * *leaf on the success path, and measured worth it*. Each entry carries its reason.
 *
 * [QUARANTINED] is the other enumerated set: declarations that are registered under a GC-blocking
 * convention, are re-entrant, and are kept only as benchmark twins. They are legal exactly as long
 * as nothing calls them, which is the invariant
 * [noLiveCallSiteReachesAGcBlockingBindingOutsideThePromotedSet] enforces.
 *
 * ## The parsers strip comments properly, and that is not a detail
 *
 * The audit's headline finding -- six re-entrant functions supposedly live under `@CriticalNative`
 * -- came from reading a block-commented region of dead pre-migration code as live. Two throwaway
 * parsers written while building this test reproduced the same class of error from the other side:
 *
 * - `bindings.kt` contains a banner line: two slashes followed by fifty asterisks. A parser that
 *   strips block comments before line comments finds a block-comment opener inside that banner and
 *   swallows 15 kB of live declarations -- 370 declarations read as 305, every `@FastNative` one
 *   gone.
 * - CPython's headers contain block comments whose last line holds a line-comment marker before the
 *   block terminator. Stripping line comments first eats the terminator, and then the block
 *   comment runs to the end of the next one -- `abstract.h` lost `PyNumber_FloorDivide` that way.
 *
 * So [stripComments] is a scanner, not a pair of regexes, and
 * [theParsersReadLiveCodeOnlyAndTheSurfaceIsNotVacuous] holds canaries at both ends.
 *
 * ## These assertions were watched failing before they were trusted
 *
 * The tree was already correct when this file was written, so every guard here would have passed
 * whether or not it worked. Each was therefore run against a deliberately broken tree first, and
 * each failure below was observed, not predicted:
 *
 * | mutation | failures |
 * |---|---|
 * | [PROMOTED] and [QUARANTINED] emptied -- the state before any classification existed | 3/10: the two set-membership guards and the call-site guard |
 * | `EmbedAPI.PyErr_Clear` moved from `bindings.PyErr_ClearN` to the `@CriticalNative` `bindings.PyErr_Clear` | 4/10, naming `PyErr_Clear called from [EmbedAPI.android.kt]` |
 * | `@FastNative` added to `PyDict_SetItemN`, which is re-entrant | same run, naming `PyDict_SetItemN -> f_PyDict_SetItemN RE_ENTRANT_BY_SIGNATURE` |
 * | [stripComments] replaced by the two regexes, block-first | 4/10; declarations read as 278 of 370, GC-blocking as 10 of 34 |
 * | the same two regexes, line-first | 1/10 -- and this is the interesting one: the suite stayed green apart from the `abstract.h` canary, while one registration slid from `RE_ENTRANT_BY_SIGNATURE` to `UNDECIDED` behind it |
 *
 * That last row is why the canaries are assertions rather than a comment. A parser that half-works
 * does not announce itself; it moves functions quietly into the bucket that asks fewer questions.
 */
class JniCallConventionClassificationTest {

    // ---------------------------------------------------------------------------------------
    // The two enumerated sets. Everything else in this file is derived.
    // ---------------------------------------------------------------------------------------

    /**
     * Bindings allowed to carry `@FastNative` or `@CriticalNative` at a live call site.
     *
     * Membership is a claim of *leaf on the success path* plus *the win was measured on this call*,
     * not a claim that Python cannot run. The failure path of every CPython entry here allocates a
     * GC-tracked exception; that is second-order and conditional, and it is why this set is short
     * and written down rather than derived from a rule.
     *
     * Convention twins are listed separately because the convention is a property of the Kotlin
     * declaration, not of the C function: `X` is the `@CriticalNative` binding, `XF` the
     * `@FastNative` one, and `EmbedAPI.android.kt` picks between them on `preferFastNative`.
     */
    private val PROMOTED: Map<String, String> = mapOf(
        "Py_IsInitialized" to "reads a global int; no object crosses the boundary",
        "Py_IsInitializedF" to "@FastNative twin of Py_IsInitialized",
        "Py_GetVersion" to "returns a pointer to a static const char[]",
        "Py_GetVersionF" to "@FastNative twin of Py_GetVersion",
        "PyErr_Occurred" to "borrowed read of the current thread state's exception slot",
        "PyErr_OccurredF" to "@FastNative twin of PyErr_Occurred",
        "PyLong_FromLongLong" to "non-GC allocation; ints are untracked, so no cyclic collection",
        "PyLong_FromLongLongF" to "@FastNative twin of PyLong_FromLongLong",
        "PyUnicode_FromString" to "non-GC allocation; str is untracked",
        "PyUnicode_FromStringF" to "@FastNative twin of PyUnicode_FromString",
        "PyUnicode_AsUTF8" to "fills and caches the object's own UTF-8 form; no slot dispatch",
        "PyUnicode_AsUTF8F" to "@FastNative twin of PyUnicode_AsUTF8",
        "PyList_Size" to "reads ob_size; the hot path of every bulk conversion",
        "PyList_SizeF" to "@FastNative twin of PyList_Size",
        "PyList_SizeFast" to "benchmark twin of PyList_Size under @FastNative (JniOverheadBenchmark)",
        "PyList_GetItemRaw" to
            "indexes the item array and returns it borrowed. Per-element call of bulk iteration: " +
            "ROADMAP §5 measured 50 ns/element on API 36 against a @CriticalNative net cost of " +
            "44.05 ns there, which is why it has a @FastNative twin rather than being pinned",
        "PyList_GetItemRawF" to "@FastNative twin of PyList_GetItemRaw",
        "echo0" to "probe; C body is `return x;`",
        "echo1" to "probe; C body is `return x;`",
        "echo2" to "probe; C body is `return x;`",
        "echoFast" to "probe; C body is `return x;`",
        "echoCriticalNamed" to
            "probe, and the only @CriticalNative binding reached by name-based linking rather " +
            "than RegisterNatives -- deliberately absent from the table (ROADMAP §2). Its body is " +
            "a Kotlin @CName export in artMain/JNIOnLoadExporter.kt, so the C-side proof this file " +
            "runs does not reach it",
    )

    /**
     * Registered under a GC-blocking convention *and* re-entrant. Each one is the pre-migration
     * twin of a function whose live call site is the `N`-suffixed ordinary binding, kept so a
     * benchmark can still measure the convention on a call that does real work.
     *
     * They are safe only because nothing calls them. Adding a call site to any of these is the
     * crash ROADMAP §3 is about, and it is what the call-site test catches.
     */
    private val QUARANTINED: Map<String, String> = mapOf(
        "Py_Initialize" to "imports site.py, which is arbitrary Python; live site is Py_InitializeN",
        "Py_InitializeF" to "same, @FastNative twin",
        "Py_Finalize" to "runs atexit callbacks and __del__ on survivors; live site is Py_FinalizeN",
        "Py_FinalizeF" to "same, @FastNative twin",
        "PyErr_Clear" to "decrefs the exception, type and traceback; zero runs __del__",
        "PyErr_ClearF" to "same, @FastNative twin",
        "PyRun_SimpleString" to "compiles and executes arbitrary source",
        "PyRun_SimpleStringF" to "same, @FastNative twin",
        "PyImport_ImportModule" to "executes the module's top-level code",
        "PyImport_ImportModuleF" to "same, @FastNative twin",
        "PyObject_GetAttrString" to "dispatches to __getattribute__/__getattr__/descriptor __get__",
        "PyObject_GetAttrStringF" to "same, @FastNative twin",
    )

    /**
     * `external fun` declarations deliberately absent from the `JNINativeMethod` table, named in
     * ROADMAP §2. Every other declaration must have an entry: an unregistered one falls back to a
     * `@CName` export with no `JNIEnv*`/`jclass` prologue, so the arguments arrive shifted by two
     * registers and the process dies on a truncated pointer.
     */
    private val UNREGISTERED_BY_DESIGN = setOf(
        "echoCriticalNamed", "ffiAllocUtf8", "ffiFreeUtf8", "ffiReadUtf8",
    )

    // ---------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------

    @Test
    fun theParsersReadLiveCodeOnlyAndTheSurfaceIsNotVacuous() {
        assertTrue(
            declarations.size > 300,
            "only ${declarations.size} live `external fun` declarations found in bindings.kt; the " +
                "parser has stopped seeing the surface and every other assertion here is vacuous"
        )
        assertTrue(
            registrations.size > 300,
            "only ${registrations.size} JNINativeMethod entries parsed from jni_onload.def"
        )
        assertTrue(
            prototypes.size > 900,
            "only ${prototypes.size} PyAPI_FUNC prototypes indexed from the bundled CPython headers"
        )

        // Canaries for the comment bug in each direction. Both names appear in bindings.kt, both
        // only inside the commented-out pre-migration block.
        for (dead in listOf("Py_BytesMain", "PyLong_AsInt", "Py_InitializeEx", "PyLong_AsLongLong")) {
            assertTrue(
                dead in bindingsSourceText,
                "$dead is no longer in bindings.kt at all, so it is not a canary any more -- pick " +
                    "another name that exists only inside the commented-out block"
            )
            assertTrue(
                dead !in declarations,
                "$dead was read as a live declaration. It only exists inside the commented-out " +
                    "block at the top of bindings.kt, so the comment scanner is broken -- which is " +
                    "exactly how the original audit concluded six re-entrant functions were live " +
                    "under @CriticalNative"
            )
        }
        // Canary for the other direction: a header prototype that a naive `//`-then-`/* */` strip
        // loses, because an orphaned terminator swallows the rest of abstract.h.
        assertTrue(
            "PyNumber_FloorDivide" in prototypes,
            "PyNumber_FloorDivide is missing from the header index. A comment scanner that strips " +
                "`//` before `/* */` loses it, and with it most of abstract.h, which silently moves " +
                "every function in that header into the UNDECIDED bucket"
        )
        assertEquals(
            "PyObject *", prototypes.getValue("PyList_Size").parameters,
            "the header parser is no longer reading parameter lists correctly"
        )
    }

    @Test
    fun everyRegistrationResolvesToAClassification() {
        val unclassified = registrations.filter { classify(it.jniName) == null }
        assertTrue(
            unclassified.isEmpty(),
            "these registrations could not be classified at all: ${unclassified.map { it.jniName }}"
        )

        val undeclared = registrations.map { it.jniName }.filter { it !in declarations }
        assertTrue(
            undeclared.isEmpty(),
            "registered in jni_onload.def with no `external fun` in bindings.kt: $undeclared. " +
                "RegisterNatives fails for a name the class does not declare, and the failure is a " +
                "silent one for every other function in the same call"
        )
    }

    @Test
    fun everyDeclarationIsRegisteredOrIsOneOfTheFourNamedExceptions() {
        val registered = registrations.mapTo(HashSet()) { it.jniName }
        val missing = declarations.keys.filter { it !in registered && it !in UNREGISTERED_BY_DESIGN }
        assertTrue(
            missing.isEmpty(),
            "declared in bindings.kt with no JNINativeMethod entry: $missing. Such a declaration " +
                "does not fail cleanly when it is finally reached -- it falls back to the @CName " +
                "export in nativeMain, whose arguments arrive shifted by two registers. " +
                "androidMain/README.md: there are exactly four deliberate exceptions, and adding a " +
                "fifth means writing down why"
        )
        val stale = UNREGISTERED_BY_DESIGN.filter { it !in declarations }
        assertTrue(stale.isEmpty(), "UNREGISTERED_BY_DESIGN names no longer declared: $stale")
    }

    /**
     * The invariant the whole section exists for: nothing that can run Python is reached under a
     * convention that stops the collector.
     */
    @Test
    fun noLiveCallSiteReachesAGcBlockingBindingOutsideThePromotedSet() {
        val offenders = callSites.entries
            .filter { (name, _) -> declarations[name].let { it != null && it != Convention.ORDINARY } }
            .filter { (name, _) -> name !in PROMOTED }
            .map { (name, files) ->
                val verdict = classify(name)
                "$name (${declarations[name]}, $verdict) called from ${files.sorted()}"
            }

        assertTrue(
            offenders.isEmpty(),
            "these call sites reach a GC-blocking binding that is not in the enumerated promoted " +
                "set:\n  ${offenders.joinToString("\n  ")}\n" +
                "Either the call site must move to the ordinary `N`-suffixed binding, or -- if the " +
                "function really is a leaf on its success path and the win was measured on this " +
                "call -- add it to PROMOTED with that reason. Step 4 of the decision procedure in " +
                "androidMain/README.md is the checklist."
        )
    }

    @Test
    fun everyGcBlockingDeclarationIsEitherPromotedOrQuarantined() {
        val gcBlocking = declarations.filterValues { it != Convention.ORDINARY }.keys
        assertEquals(
            (PROMOTED.keys + QUARANTINED.keys).toSortedSet(),
            gcBlocking.toSortedSet(),
            "the set of @FastNative/@CriticalNative declarations in bindings.kt no longer matches " +
                "the two enumerated sets in this test. A new one has to be justified as PROMOTED " +
                "(leaf on the success path, win measured) or recorded as QUARANTINED (re-entrant, " +
                "kept as a benchmark twin, never called); a removed one has to be deleted from " +
                "whichever set names it."
        )
    }

    @Test
    fun quarantinedDeclarationsHaveNoCallSiteAnywhere() {
        val called = QUARANTINED.keys.filter { it in callSites }
            .map { "$it called from ${callSites.getValue(it).sorted()} -- ${QUARANTINED.getValue(it)}" }
        assertTrue(
            called.isEmpty(),
            "a re-entrant function is being called under a GC-blocking convention:\n  " +
                called.joinToString("\n  ") +
                "\nThis is the crash ROADMAP §3 warns about. The ordinary binding is the same name " +
                "with an `N` suffix."
        )
    }

    /**
     * Registrations that are re-entrant or undecided must be ordinary. The previous two tests
     * approach this from the declaration side; this one approaches it from the verdict side, so a
     * classification that silently flips is caught even if both enumerated sets were edited to
     * match it.
     */
    @Test
    fun noReEntrantOrUndecidedRegistrationIsBoundGcBlockingWithoutAWrittenReason() {
        val unexplained = registrations
            .filter { declarations[it.jniName].let { c -> c != null && c != Convention.ORDINARY } }
            .filter { classify(it.jniName) != Verdict.LEAF_PROVEN }
            .filter { it.jniName !in PROMOTED && it.jniName !in QUARANTINED }
            .map { "${it.jniName} -> ${it.symbol} ${classify(it.jniName)}" }
        assertTrue(
            unexplained.isEmpty(),
            "bound GC-blocking, not proven a leaf, and with no recorded reason: $unexplained"
        )
    }

    /**
     * `@CriticalNative` receives neither `JNIEnv*` nor `jclass`; `@FastNative` and ordinary JNI
     * receive both. A wrapper written with the wrong prologue shifts every argument by two
     * registers, which is the failure mode ROADMAP §2 recorded twice as a death on a truncated
     * pointer. Checking it here means it is caught by compiling the repo rather than by running a
     * device.
     */
    @Test
    fun criticalNativeRegistrationsTakeNoJniEnvAndTheOtherTwoDo() {
        val wrong = registrations.mapNotNull { reg ->
            val convention = declarations[reg.jniName] ?: return@mapNotNull null
            val wrapper = wrappers[reg.symbol]
            // No wrapper means the table points straight at the CPython symbol, which by
            // definition has no JNI prologue -- only legal for @CriticalNative.
            val takesJniEnv = wrapper?.parameters?.contains("JNIEnv") ?: false
            when {
                convention == Convention.CRITICAL_NATIVE && takesJniEnv ->
                    "${reg.jniName} is @CriticalNative but ${reg.symbol} takes JNIEnv"
                convention != Convention.CRITICAL_NATIVE && !takesJniEnv ->
                    "${reg.jniName} is $convention but ${reg.symbol} has no JNIEnv prologue"
                else -> null
            }
        }
        assertTrue(wrong.isEmpty(), "calling-convention/prologue mismatches:\n  ${wrong.joinToString("\n  ")}")
    }

    /**
     * The honest half of the classification. A header prototype can prove a function re-entrant; it
     * can never prove one a leaf, and this test exists so that nobody reports "unresolved: none"
     * again -- the original audit did, and three of the eight functions on its safe-to-promote list
     * were not leaves.
     */
    @Test
    fun theSignatureRuleDoesNotClaimToProveLeafness() {
        val undecided = registrations.filter { classify(it.jniName) == Verdict.UNDECIDED }
        assertTrue(
            undecided.isNotEmpty(),
            "the UNDECIDED bucket is empty. Either the classifier gained a real source of evidence " +
                "-- in which case say what it is here -- or it has started treating absence of " +
                "evidence as proof, which is the mistake this test is named after"
        )

        // Both of these run arbitrary Python and neither mentions PyObject* anywhere in its
        // prototype. They are the standing counter-example to "the signature looks like a leaf".
        for (name in listOf("Py_Initialize", "PyRun_SimpleString")) {
            assertEquals(
                Verdict.UNDECIDED, classify(name),
                "$name is expected to sit in the UNDECIDED bucket: its prototype is " +
                    "`${prototypes[name.removeSuffix("N")]}` -- no Python object crosses it -- and " +
                    "it still executes arbitrary Python. If the classifier now calls it a leaf, the " +
                    "signature rule has been inverted into a promotion rule."
            )
        }

        // A promotion may override RE_ENTRANT_BY_SIGNATURE or UNDECIDED -- neither is a proof, and
        // overriding them with a measurement is the whole point of the enumerated set. It may not
        // override RE_ENTRANT_BY_JNI_UPCALL, which is not an inference: the wrapper calls back into
        // the runtime in its own body, and under @CriticalNative it has no JNIEnv to do it with.
        val promotedUpcalls = PROMOTED.keys.filter { classify(it) == Verdict.RE_ENTRANT_BY_JNI_UPCALL }
        assertTrue(
            promotedUpcalls.isEmpty(),
            "promoted despite calling into the Java runtime from the C wrapper: $promotedUpcalls"
        )
    }

    /** Not an assertion about correctness -- it prints what the run actually classified. */
    @Test
    fun theClassificationIsReportedSoDriftIsVisible() {
        val byVerdict = registrations.groupingBy { classify(it.jniName) }.eachCount()
        println(
            "JNI classification over ${registrations.size} registrations " +
                "(${declarations.size} declarations, ${prototypes.size} CPython prototypes): " +
                byVerdict.entries.sortedBy { it.key?.name }.joinToString { "${it.key}=${it.value}" }
        )
        println(
            "  GC-blocking declarations: ${declarations.count { it.value != Convention.ORDINARY }} " +
                "(${PROMOTED.size} promoted, ${QUARANTINED.size} quarantined); " +
                "live call sites reaching one: " +
                callSites.keys.count { declarations[it].let { c -> c != null && c != Convention.ORDINARY } }
        )
        assertTrue(registrations.isNotEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------

    enum class Convention { ORDINARY, FAST_NATIVE, CRITICAL_NATIVE }

    enum class Verdict {
        /** Reaches a CPython entry point through whose prototype a Python object pointer crosses. */
        RE_ENTRANT_BY_SIGNATURE,

        /** The C wrapper dereferences `JNIEnv` and calls back into the Java runtime. */
        RE_ENTRANT_BY_JNI_UPCALL,

        /** The wrapper body makes no call of any kind. */
        LEAF_PROVEN,

        /** 판정 불가. Nothing available here proves it either way. */
        UNDECIDED,
    }

    private data class Registration(val jniName: String, val descriptor: String, val symbol: String)
    private data class Wrapper(val parameters: String, val body: String)
    private data class Prototype(val returnType: String, val parameters: String) {
        override fun toString() = "$returnType (…$parameters)"
    }

    private fun classify(jniName: String): Verdict? {
        val symbol = registrations.firstOrNull { it.jniName == jniName }?.symbol
            ?: return if (jniName in PROMOTED || jniName in QUARANTINED) Verdict.UNDECIDED else null
        val reached = reachedCPythonEntryPoints(symbol)
        if (reached.any { prototypes.getValue(it).mentionsPythonObjectPointer() }) {
            return Verdict.RE_ENTRANT_BY_SIGNATURE
        }
        val wrapper = wrappers[symbol]
        if (wrapper != null && JNI_ENV_DEREFERENCE.containsMatchIn(wrapper.body)) {
            return Verdict.RE_ENTRANT_BY_JNI_UPCALL
        }
        if (wrapper != null && !CALL_SYNTAX.containsMatchIn(wrapper.body)) return Verdict.LEAF_PROVEN
        return Verdict.UNDECIDED
    }

    private fun Prototype.mentionsPythonObjectPointer(): Boolean =
        PYTHON_OBJECT_POINTER.containsMatchIn(returnType) || PYTHON_OBJECT_POINTER.containsMatchIn(parameters)

    private fun reachedCPythonEntryPoints(symbol: String, seen: MutableSet<String> = HashSet()): Set<String> {
        if (!seen.add(symbol)) return emptySet()
        val wrapper = wrappers[symbol] ?: return if (symbol in prototypes) setOf(symbol) else emptySet()
        val reached = LinkedHashSet<String>()
        for (match in CALL_SYNTAX.findAll(wrapper.body)) {
            val callee = match.groupValues[1]
            when {
                callee in prototypes -> reached += callee
                callee in wrappers -> reached += reachedCPythonEntryPoints(callee, seen)
            }
        }
        return reached
    }

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    private companion object {
        private val PYTHON_OBJECT_POINTER =
            Regex("""\bPy[A-Za-z_]*Object\s*\*|\bPyThreadState\s*\*|\bPyInterpreterState\s*\*""")
        private val CALL_SYNTAX = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val JNI_ENV_DEREFERENCE = Regex("""\(\s*\*\s*[A-Za-z_][A-Za-z0-9_]*\s*\)\s*->""")

        /** `python-multiplatform/`, found by walking up from wherever Gradle started the JVM. */
        val moduleDirectory: File by lazy {
            val marker = "src/androidMain/kotlin/python/native/ffi/bindings.kt"
            var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
            while (candidate != null) {
                if (File(candidate, marker).isFile) return@lazy candidate
                val nested = File(candidate, "python-multiplatform")
                if (File(nested, marker).isFile) return@lazy nested
                candidate = candidate.parentFile
            }
            fail(
                "could not find python-multiplatform/$marker by walking up from " +
                    "${System.getProperty("user.dir")}. This test reads the JNI surface out of the " +
                    "sources; it cannot run from a packaged artefact."
            )
        }

        val bindingsSourceText: String by lazy {
            File(moduleDirectory, "src/androidMain/kotlin/python/native/ffi/bindings.kt").readText()
        }

        private val jniOnLoadSourceText: String by lazy {
            File(moduleDirectory, "src/artMain/cinterop/jni_onload.def").readText()
        }

        /**
         * Removes comments and string literals in one left-to-right pass. Regex pairs get this
         * wrong in both directions -- see the class comment -- and the whole classification rests
         * on telling live code from commented-out code.
         *
         * @param nestable Kotlin nests block comments; C does not.
         */
        fun stripComments(source: String, nestable: Boolean): String = buildString {
            var i = 0
            var depth = 0
            while (i < source.length) {
                if (depth > 0) {
                    when {
                        nestable && source.startsWith("/*", i) -> { depth++; i += 2 }
                        source.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) append(' ') }
                        else -> { if (source[i] == '\n') append('\n'); i++ }
                    }
                    continue
                }
                when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i)
                        if (end < 0) return@buildString
                        append('\n'); i = end + 1
                    }
                    source.startsWith("/*", i) -> { depth = 1; i += 2 }
                    source.startsWith("\"\"\"", i) -> {
                        val end = source.indexOf("\"\"\"", i + 3)
                        append(' '); i = if (end < 0) source.length else end + 3
                    }
                    source[i] == '"' || source[i] == '\'' -> {
                        val quote = source[i]
                        var j = i + 1
                        while (j < source.length && source[j] != quote) {
                            if (source[j] == '\\') j++
                            j++
                        }
                        append(' '); i = j + 1
                    }
                    else -> { append(source[i]); i++ }
                }
            }
        }

        private fun matchingBracket(source: String, open: Int, opener: Char, closer: Char): Int {
            var depth = 0
            var i = open
            while (i < source.length) {
                if (source[i] == opener) depth++
                else if (source[i] == closer) { depth--; if (depth == 0) return i }
                i++
            }
            return -1
        }

        /** Every live `external fun` in `bindings.kt`, with the convention its annotations give it. */
        val declarations: Map<String, Convention> by lazy {
            val live = stripComments(bindingsSourceText, nestable = true)
            val result = LinkedHashMap<String, Convention>()
            for (match in Regex("""external fun ([A-Za-z0-9_]+)\s*\(""").findAll(live)) {
                // Annotations sit between the previous declaration and this one.
                val previous = live.lastIndexOf("external fun", match.range.first - 1)
                val preamble = live.substring(if (previous >= 0) previous else 0, match.range.first)
                result[match.groupValues[1]] = when {
                    "CriticalNative" in preamble -> Convention.CRITICAL_NATIVE
                    "FastNative" in preamble -> Convention.FAST_NATIVE
                    else -> Convention.ORDINARY
                }
            }
            result
        }

        /** The `JNINativeMethod` table: the JNI name, its descriptor, and the C symbol it binds. */
        val registrations: List<Registration> by lazy {
            // Comment stripping also removes string literals, and the table *is* string literals, so
            // this one is read from the raw text.
            val marker = "JNINativeMethod methods[] = {"
            val start = jniOnLoadSourceText.indexOf(marker)
            assertTrue(start >= 0, "no JNINativeMethod table in jni_onload.def")
            val end = matchingBracket(jniOnLoadSourceText, jniOnLoadSourceText.indexOf('{', start), '{', '}')
            assertTrue(end > start, "the JNINativeMethod table is not closed")
            Regex("""\{\s*"([A-Za-z0-9_]+)"\s*,\s*"([^"]*)"\s*,\s*\(void \*\)&([A-Za-z0-9_]+)\s*}""")
                .findAll(jniOnLoadSourceText.substring(start, end))
                .map { Registration(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                .toList()
        }

        /** Every C function *defined* in `jni_onload.def`, by name. */
        val wrappers: Map<String, Wrapper> by lazy {
            val source = stripComments(jniOnLoadSourceText, nestable = false)
            // Matched per line rather than across the file: a multiline pattern backtracks across
            // line boundaries and captures fragments of unrelated declarations.
            val signature = Regex(
                """^(?:static\s+)?[A-Za-z_][A-Za-z0-9_]*(?:\s*\*|\s+)""" +
                    """(?:[A-Za-z_][A-Za-z0-9_]*(?:\s*\*|\s+))*?([A-Za-z_][A-Za-z0-9_]*)\s*\("""
            )
            val result = LinkedHashMap<String, Wrapper>()
            var offset = 0
            for (line in source.lineSequence()) {
                val match = signature.find(line)
                if (match != null && match.range.first == 0) {
                    val open = offset + match.range.last
                    val close = matchingBracket(source, open, '(', ')')
                    if (close > 0) {
                        var brace = close + 1
                        while (brace < source.length && source[brace].isWhitespace()) brace++
                        if (brace < source.length && source[brace] == '{') {
                            val bodyEnd = matchingBracket(source, brace, '{', '}')
                            if (bodyEnd > 0) {
                                result.putIfAbsent(
                                    match.groupValues[1],
                                    Wrapper(source.substring(open + 1, close), source.substring(brace + 1, bodyEnd))
                                )
                            }
                        }
                    }
                }
                offset += line.length + 1
            }
            result
        }

        /**
         * `PyAPI_FUNC` prototypes from the bundled CPython headers -- the evidence base.
         *
         * That the bundled headers are the version the build ships is asserted by
         * [VendoredHeaderVersionTest], not assumed here: this tree read 3.13 while `pythonVersion`
         * was 3.14.7, which would have classified a re-signatured function from the wrong text
         * while this suite went on agreeing with itself. The refresh to 3.14.7 took this map from
         * 1129 prototypes to 1207 and left every classification below unchanged -- none of the six
         * prototypes 3.14 drops, nor the one whose signature moved (`_PyLong_NumBits`), is among
         * the 319 that this file's wrappers call.
         */
        val prototypes: Map<String, Prototype> by lazy {
            val includeRoot = File(moduleDirectory, "src/nativeInterop/cinterop/include")
            assertTrue(includeRoot.isDirectory, "no bundled CPython headers at $includeRoot")
            val result = LinkedHashMap<String, Prototype>()
            includeRoot.walkTopDown()
                // `internal/` is CPython's private API; nothing here binds it, and it duplicates
                // names from the public headers with different signatures.
                .filter { it.isFile && it.extension == "h" && "internal" !in it.path.split(File.separator) }
                .sortedBy { it.path }
                .forEach { header ->
                    val text = stripComments(header.readText(), nestable = false)
                    for (match in Regex("""PyAPI_FUNC\s*\(""").findAll(text)) {
                        val returnClose = matchingBracket(text, match.range.last, '(', ')')
                        if (returnClose < 0) continue
                        val returnType = text.substring(match.range.last + 1, returnClose).trim().normalizeSpaces()
                        val rest = text.substring(returnClose + 1)
                        val open = rest.indexOf('(')
                        if (open < 0) continue
                        val head = rest.substring(0, open)
                        // A `;` or `{` before the parenthesis means this was not a prototype.
                        if (head.any { it == ';' || it == '{' || it == '}' }) continue
                        val name = Regex("""[A-Za-z_][A-Za-z0-9_]*""").findAll(head).lastOrNull()?.value ?: continue
                        val close = matchingBracket(rest, open, '(', ')')
                        if (close < 0) continue
                        result.putIfAbsent(
                            name,
                            Prototype(returnType, rest.substring(open + 1, close).normalizeSpaces())
                        )
                    }
                }
            result
        }

        private fun String.normalizeSpaces() = trim().split(Regex("""\s+""")).joinToString(" ")

        /**
         * Every `bindings.<name>` reference in live Kotlin, and the file it is in.
         *
         * Instrumented tests count: they run on the device under the same conventions, so a
         * benchmark reaching a re-entrant `@CriticalNative` binding crashes exactly like production
         * code would.
         */
        val callSites: Map<String, Set<String>> by lazy {
            val result = LinkedHashMap<String, MutableSet<String>>()
            for (sourceSet in listOf("androidMain", "androidInstrumentedTest", "androidUnitTest")) {
                val root = File(moduleDirectory, "src/$sourceSet")
                if (!root.isDirectory) continue
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" && it.name != "bindings.kt" }
                    .forEach { file ->
                        val live = stripComments(file.readText(), nestable = true)
                        for (match in Regex("""\bbindings\.([A-Za-z0-9_]+)""").findAll(live)) {
                            result.getOrPut(match.groupValues[1]) { LinkedHashSet() } += file.name
                        }
                    }
            }
            result
        }
    }
}
