package python.native.ffi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Issue #4 -- "reclassify the Python/C API `expect` declarations, add the `actual` definitions" --
 * re-derived from the sources on every run instead of counted by hand once.
 *
 * ## Why this is a test and not a paragraph in a document
 *
 * `docs/design/ecosystem.md` §5c recorded "335 `expect` against 315 desktop `actual`, 20 unaccounted for"
 * and left it open. That number came from `grep -c`, and `grep` cannot see comments. Kotlin block
 * comments **nest**, so `EmbedAPI.kt` opened one doc-comment at line 28 that ran to line 249 and
 * swallowed a superseded first draft of Sections 1 and 2 -- twenty `expect` declarations that no
 * compiler ever saw, and exactly the twenty §5c could not account for. `EmbedAPI.native.kt` and
 * `EmbedAPI.android.kt` carried the matching `actual`s in the same shape. Counting read them as
 * live; diffing does not.
 *
 * The gap was therefore never a missing `actual`. It was dead code counted twice, and no amount of
 * re-counting would have found that -- which is why the check that replaces it is a set difference,
 * per platform, with the dead regions excluded by a scanner rather than by hope.
 *
 * ## The rule for a deprecated API: remove only when the Kotlin replacement is exact
 *
 * Issue #4 asks for the surface not to include deprecated APIs. Taken literally that deletes
 * capability, so the line drawn here -- and the line the one pre-existing case already drew -- is:
 *
 * - **Remove from `expect`/`actual`, keep the name as a `@Deprecated` shim**, when the shim is
 *   *exactly* the C function and not an approximation of it. `PyImport_ImportModuleNoBlock` (an
 *   exact alias of `PyImport_ImportModule` since 3.3) forwards; `PyEval_InitThreads` (an empty C
 *   body since 3.9) is a Kotlin no-op. Both are behaviour-preserving on every supported version.
 * - **Keep in the surface and annotate**, when the CPython replacement differs in reference
 *   contract or arity. `PyEval_GetBuiltins`/`GetLocals`/`GetGlobals` return *borrowed* references
 *   and their `PyEval_GetFrame*` replacements return *new* ones; `PyErr_Restore` takes the
 *   unnormalized `(type, value, traceback)` triple that `PyErr_SetRaisedException` cannot express.
 *   Deleting these would push callers onto a substitution that silently moves ownership, which is
 *   the class of bug that has already corrupted the heap in this repository once.
 *
 * Not covered by either: functions CPython documents as *discouraged* without deprecating --
 * `PyObject_HasAttr`, `PyObject_HasAttrString`, `PyMapping_HasKey`, `PyMapping_HasKeyString`,
 * `PyDict_GetItem`. Their KDoc says "for proper error handling, use the `WithError` variant", not
 * "deprecated", CPython names no removal schedule for them, and the error-swallowing behaviour is
 * what `PyDict.get` deliberately relies on ("null unambiguously means missing key here, not
 * error"). They stay, unannotated, on purpose.
 *
 * ## What each test claims
 *
 * - [theScannerSeesLiveCodeOnlyAndTheSurfaceIsNotVacuous] -- canaries at both ends, so that a
 *   scanner that has stopped working fails loudly instead of making every other test vacuous.
 * - [everyExpectHasAnActualOnEveryPlatform] -- the set difference §5c never performed. The Kotlin
 *   compiler already enforces this for the targets that are built, so this test's job is to state
 *   the answer in one place and to catch a platform that stops being compiled in CI.
 * - [noDeclarationIsStrandedInsideACommentedOutBlock] -- the dead-code guard. A declaration that
 *   exists *only* inside a comment is not a declaration; it is a trap for the next reader, and it
 *   was the direct cause of §5c's open question and of the JNI audit's phantom finding recorded in
 *   [JniCallConventionClassificationTest].
 * - [noCPythonDeprecatedSymbolIsInTheExpectSurface] -- the reclassification half of issue #4,
 *   derived from `Py_DEPRECATED(...)` in the CPython headers bundled under
 *   `src/nativeInterop/cinterop/include`, not from anyone's memory of the deprecation schedule.
 *   That those headers are the version the build actually ships is not assumed here; it is a
 *   separate assertion, in [VendoredHeaderVersionTest], because for a year they were not.
 * - [everyDocumentedDeprecationIsMarkedInKotlin] -- the softer half. CPython documents some
 *   deprecations only in prose ("Deprecated since version 3.13: use X instead"), and that prose is
 *   already in this repository, copied into the KDoc of the declaration it belongs to. Where the
 *   replacement is not a drop-in -- a different reference contract, a different arity -- the
 *   function stays in the surface and carries `@Deprecated` instead of being deleted out from under
 *   its callers. This test is what makes "carries `@Deprecated`" a rule rather than a habit.
 *
 * ## These assertions were watched failing before they were trusted
 *
 * Run against the tree as it stood at the start of this work (`develop` @ `3fde8bd6`):
 *
 * | test | result before the fix | why |
 * |---|---|---|
 * | [noDeclarationIsStrandedInsideACommentedOutBlock] | FAIL, 66 stranded declarations | 21 in `EmbedAPI.kt` (28-249), 22 in `EmbedAPI.android.kt` (30-73), 22 in `EmbedAPI.native.kt` (46-128), 1 in `EmbedAPI.desktop.kt` |
 * | [noCPythonDeprecatedSymbolIsInTheExpectSurface] | FAIL, `PyEval_InitThreads` | `ceval.h:114` marks it `Py_DEPRECATED(3.9)`, and its C body has been empty since 3.9 |
 * | [everyDocumentedDeprecationIsMarkedInKotlin] | FAIL, 5 unmarked | `PyEval_InitThreads`, `PyErr_Restore`, `PyEval_GetBuiltins`, `PyEval_GetLocals`, `PyEval_GetGlobals` |
 * | [everyExpectHasAnActualOnEveryPlatform] | PASS | it was already true; §5c's "20 missing" was an artefact of counting |
 *
 * The last row matters as much as the others: the honest result of the investigation is that half
 * of issue #4 was already done and the reported gap did not exist.
 *
 * That row is also the one that could have been vacuous, so it was mutated rather than trusted:
 * dropping `desktopMain` from [platforms] made it report "desktop is missing 314 actual(s)", which
 * is the whole surface. The set difference works; the tree was simply already correct.
 */
class EmbedApiSurfaceTest {

    /**
     * Which source sets contribute `actual` declarations to each compiled target.
     *
     * `nativeMain` is shared by iOS and androidNative, which is why both appear: checking only one
     * of them has let the other go missing before (`AGENTS.md`, "검증에 androidNative 컴파일을 포함한다").
     */
    private val platforms: Map<String, List<String>> = mapOf(
        "desktop" to listOf("jvmMain", "desktopMain"),
        "android" to listOf("jvmMain", "androidMain"),
        "iosSimulatorArm64" to listOf("nativeMain", "iosMain"),
        "androidNativeArm64" to listOf("nativeMain", "artMain"),
        "wasmJs" to listOf("wasmJsMain"),
    )

    /**
     * The three files that carried the superseded drafts. Kept as an explicit list rather than a
     * walk of the whole tree: a stranded declaration anywhere is a defect, but these are the three
     * where it actually happened, and naming them keeps the failure message pointed at the cause.
     */
    private val embedApiFiles = listOf(
        "src/commonMain/kotlin/python/native/ffi/EmbedAPI.kt",
        "src/desktopMain/kotlin/python/native/ffi/EmbedAPI.desktop.kt",
        "src/androidMain/kotlin/python/native/ffi/EmbedAPI.android.kt",
        "src/nativeMain/kotlin/python/native/ffi/EmbedAPI.native.kt",
        "src/wasmJsMain/kotlin/python/native/ffi/EmbedAPI.wasmJs.kt",
    )

    // -------------------------------------------------------------------------------------------

    @Test
    fun theScannerSeesLiveCodeOnlyAndTheSurfaceIsNotVacuous() {
        assertTrue(
            expects.size > 300,
            "only ${expects.size} live `expect` declarations found in EmbedAPI.kt; the scanner has " +
                "stopped seeing the surface and every other assertion in this file is vacuous"
        )

        // Positive canary: a declaration that is unambiguously live.
        assertTrue("PyErr_Occurred" in expects, "PyErr_Occurred is missing from the live `expect` set")

        // Overload canary. Nothing in this surface is overloaded, which is what lets the rest of
        // this file match `expect` to `actual` by name alone. If that ever stops being true, the
        // matching is wrong before any of the other messages get a chance to be misleading.
        val duplicated = declarationsIn(embedApiSource, "expect")
            .groupBy { it.name }.filterValues { it.size > 1 }
        assertTrue(
            duplicated.isEmpty(),
            "the same name is declared `expect` more than once, so name-based matching is no longer " +
                "sound: " + duplicated.mapValues { (_, v) -> v.map { it.line } }
        )

        // Line-alignment canary. `everyDocumentedDeprecationIsMarkedInKotlin` reads KDoc out of the
        // raw text at line numbers found in the stripped text, which only works because
        // `stripComments` emits one newline per newline consumed.
        assertEquals(
            embedApiSource.count { it == '\n' },
            stripComments(embedApiSource, nestable = true).count { it == '\n' },
            "stripComments is no longer line-preserving, so KDoc lookup by line number is reading " +
                "the wrong function's documentation"
        )

        assertTrue(
            cpythonDeprecatedSymbols.size > 20,
            "only ${cpythonDeprecatedSymbols.size} Py_DEPRECATED symbols found in the bundled " +
                "CPython headers; the header scan is broken and the deprecation test is vacuous"
        )
        assertTrue(
            "PyEval_InitThreads" in cpythonDeprecatedSymbols,
            "PyEval_InitThreads is no longer recognised as Py_DEPRECATED in ceval.h -- either the " +
                "bundled CPython moved, or the header scan regressed"
        )
    }

    @Test
    fun everyExpectHasAnActualOnEveryPlatform() {
        val expected = expects
        val report = StringBuilder()
        for ((platform, sourceSets) in platforms) {
            val actuals = actualsFor(sourceSets)
            val missing = (expected - actuals).sorted()
            if (missing.isNotEmpty()) {
                report.append("\n$platform is missing ${missing.size} actual(s): $missing")
            }
        }
        assertTrue(
            report.isEmpty(),
            "issue #4's `actual`-definition half is incomplete:$report"
        )
    }

    @Test
    fun noDeclarationIsStrandedInsideACommentedOutBlock() {
        val stranded = LinkedHashMap<String, List<String>>()
        for (relative in embedApiFiles) {
            val file = File(moduleDirectory, relative)
            if (!file.isFile) fail("$relative does not exist; this list is out of step with the tree")
            val raw = file.readText()
            val commentedOut = commentedOutText(raw, nestable = true)
            val names = (declarationsIn(commentedOut, "expect") + declarationsIn(commentedOut, "actual"))
                .map { "${it.name} (line ${it.line})" }
            if (names.isNotEmpty()) stranded[relative] = names
        }
        assertTrue(
            stranded.isEmpty(),
            "these `expect`/`actual` declarations exist only inside a commented-out region. Kotlin " +
                "block comments nest, so a `/**` opened above a section swallows everything down to " +
                "the matching `*/` and `grep` still reports the declarations as present -- which is " +
                "exactly how docs/design/ecosystem.md §5c came to report a 20-declaration gap that was not " +
                "there. Delete them or revive them; leaving them is the failure mode.\n" +
                stranded.entries.joinToString("\n") { (f, n) -> "  $f: $n" }
        )
    }

    @Test
    fun noCPythonDeprecatedSymbolIsInTheExpectSurface() {
        val offenders = expects.filter { it in cpythonDeprecatedSymbols }.sorted()
        assertTrue(
            offenders.isEmpty(),
            "these functions are marked Py_DEPRECATED in the CPython headers bundled under " +
                "src/nativeInterop/cinterop/include, and issue #4 asks for the `expect` surface not " +
                "to include them: $offenders. A compiler-enforced deprecation is also a removal " +
                "schedule -- CPython deletes the symbol a release or two later and the `actual` " +
                "stops linking. Take it out of the surface and leave a @Deprecated shim if callers " +
                "need the name."
        )
    }

    @Test
    fun everyDocumentedDeprecationIsMarkedInKotlin() {
        val unmarked = expects
            .mapNotNull { name -> declarationByName[name] }
            .filter { "Deprecated since version" in kdocOf(it) }
            .filter { "@Deprecated" !in annotationsOf(it) }
            .map { "${it.name} (line ${it.line}): ${deprecationNotice(kdocOf(it))}" }
        assertTrue(
            unmarked.isEmpty(),
            "CPython's own documentation -- already copied into the KDoc of these declarations -- " +
                "says they are deprecated, but the Kotlin declaration does not say so, so nothing " +
                "warns a caller. Where the replacement is a drop-in, delete the `expect` and " +
                "forward from a @Deprecated function (PyImport_ImportModuleNoBlock is the " +
                "precedent). Where it is not -- PyEval_GetBuiltins returns a *borrowed* reference " +
                "and PyEval_GetFrameBuiltins a *new* one, so swapping them silently changes who " +
                "owns the decref -- keep the function and annotate it.\n  " +
                unmarked.joinToString("\n  ")
        )
    }

    // -------------------------------------------------------------------------------------------
    // Parsing
    // -------------------------------------------------------------------------------------------

    private data class Decl(val name: String, val line: Int)

    private val expects: Set<String> by lazy {
        declarationsIn(embedApiSource, "expect").map { it.name }.toSet()
    }

    private val declarationByName: Map<String, Decl> by lazy {
        declarationsIn(embedApiSource, "expect").associateBy { it.name }
    }

    private fun actualsFor(sourceSets: List<String>): Set<String> {
        val result = LinkedHashSet<String>()
        for (sourceSet in sourceSets) {
            val root = File(moduleDirectory, "src/$sourceSet")
            if (!root.isDirectory) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { result += declarationsIn(it.readText(), "actual").map { d -> d.name } }
        }
        return result
    }

    /**
     * The documentation attached to [decl] -- everything between the previous declaration and this
     * one, read out of the raw text.
     *
     * Read from the raw text rather than by re-parsing, because the point of the lookup is the
     * comment, and because `stripComments` is line-preserving the line number found in the stripped
     * text indexes the raw text unchanged. [theScannerSeesLiveCodeOnlyAndTheSurfaceIsNotVacuous]
     * asserts that property rather than assuming it.
     */
    private fun kdocOf(decl: Decl): String = blockAbove(embedApiSource, decl.line)

    /**
     * The annotations attached to [decl].
     *
     * Read from the *stripped* text: there, a KDoc block has collapsed to blank lines, so the
     * contiguous non-blank run immediately above a declaration is exactly its annotations -- which
     * also handles `@Deprecated(` spread over four lines without special-casing it.
     */
    private fun annotationsOf(decl: Decl): String {
        val lines = stripComments(embedApiSource, nestable = true).split("\n")
        val collected = ArrayList<String>()
        var i = decl.line - 2
        while (i >= 0 && lines[i].isNotBlank()) { collected += lines[i].trim(); i-- }
        return collected.joinToString("\n")
    }

    private fun blockAbove(source: String, line: Int): String {
        val lines = source.split("\n")
        val collected = ArrayList<String>()
        var i = line - 2
        while (i >= 0 && !DECLARATION_START.containsMatchIn(lines[i]) && line - i <= 200) {
            collected += lines[i]
            i--
        }
        return collected.asReversed().joinToString("\n")
    }

    private fun deprecationNotice(kdoc: String): String =
        kdoc.split("\n").firstOrNull { "Deprecated since version" in it }?.trim('*', ' ') ?: ""

    private companion object {

        /** `python-multiplatform/`, found by walking up from wherever Gradle started the JVM. */
        val moduleDirectory: File by lazy {
            val marker = "src/commonMain/kotlin/python/native/ffi/EmbedAPI.kt"
            var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
            while (candidate != null) {
                if (File(candidate, marker).isFile) return@lazy candidate
                val nested = File(candidate, "python-multiplatform")
                if (File(nested, marker).isFile) return@lazy nested
                candidate = candidate.parentFile
            }
            fail(
                "could not find python-multiplatform/$marker by walking up from " +
                    "${System.getProperty("user.dir")}; this test reads the FFI surface out of the " +
                    "sources and cannot run from a packaged artefact"
            )
        }

        val embedApiSource: String by lazy {
            File(moduleDirectory, "src/commonMain/kotlin/python/native/ffi/EmbedAPI.kt").readText()
        }

        private val MODIFIER =
            "inline|external|internal|public|private|protected|value|noinline|crossinline|suspend|" +
                "operator|infix|tailrec|open|final|override|actual|expect"

        private fun declarationRegex(keyword: String) = Regex(
            """^[ \t]*$keyword\b((?:[ \t]+(?:$MODIFIER))*)[ \t]+(fun|val|var)\b([^\n]*)""",
            RegexOption.MULTILINE
        )

        /** Where [blockAbove] stops walking: the previous declaration, whatever kind it is. */
        val DECLARATION_START =
            Regex("""^\s*(expect|actual|fun|val|var|class|object|interface|typealias|internal|private|public)\b""")

        private val GENERIC_PREFIX = Regex("""^\s*<[^>]*>\s*""")
        private val RECEIVER_AND_NAME = Regex("""^([\w.]+)(?:<[^>]*>)?\??\.(\w+)""")
        private val PLAIN_NAME = Regex("""^(\w+)""")

        /**
         * Every top-level `expect`/`actual` `fun`/`val` in [source], with comments removed first.
         *
         * Names carry the receiver when there is one (`NativePointer.toRawValue`), because
         * `toNativePointer` exists three times with three different receivers.
         */
        fun declarationsIn(source: String, keyword: String): List<Decl> {
            val live = if (keyword == "expect" || keyword == "actual") stripComments(source, nestable = true) else source
            return declarationRegex(keyword).findAll(live).mapNotNull { match ->
                var rest = match.groupValues[3].trim()
                if (match.groupValues[2] == "fun") rest = GENERIC_PREFIX.replace(rest, "")
                val name = RECEIVER_AND_NAME.find(rest)?.let { "${it.groupValues[1]}.${it.groupValues[2]}" }
                    ?: PLAIN_NAME.find(rest)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                Decl(name, live.substring(0, match.range.first).count { it == '\n' } + 1)
            }.toList()
        }

        /**
         * The inverse of [stripComments]: everything that *is* inside a comment, with line numbers
         * preserved, so a declaration found here is one no compiler ever saw.
         */
        fun commentedOutText(source: String, nestable: Boolean): String = buildString {
            var i = 0
            var depth = 0
            while (i < source.length) {
                if (depth > 0) {
                    when {
                        nestable && source.startsWith("/*", i) -> { depth++; append("  "); i += 2 }
                        source.startsWith("*/", i) -> { depth--; append("  "); i += 2 }
                        else -> { append(source[i]); i++ }
                    }
                    continue
                }
                when {
                    source.startsWith("//", i) -> {
                        val end = source.indexOf('\n', i)
                        val stop = if (end < 0) source.length else end
                        append(source, i + 2, stop)
                        i = stop
                    }
                    source.startsWith("/*", i) -> { depth = 1; append("  "); i += 2 }
                    else -> { append(if (source[i] == '\n') '\n' else ' '); i++ }
                }
            }
        }

        /**
         * Removes comments and string literals in one left-to-right pass, preserving newlines.
         *
         * Deliberately identical in behaviour to the scanner in
         * [JniCallConventionClassificationTest], including the reasons it is a scanner rather than
         * a pair of regexes: a `//` followed by fifty asterisks is a banner and not a block
         * comment, and stripping `//` before `/* */` eats block terminators.
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

        private val DEPRECATED_MARKER = Regex("""(?:_Py)?Py_DEPRECATED(?:_EXTERNALLY)?\s*\(""")
        private val CALLED_IDENTIFIER = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        private val TRAILING_IDENTIFIER = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*(?:\[[^\]]*\])?\s*$""")
        private val MACROS = setOf(
            "Py_DEPRECATED", "_Py_DEPRECATED_EXTERNALLY", "PyAPI_FUNC", "PyAPI_DATA",
            "extern", "static", "inline", "typedef", "if", "while", "for", "return", "sizeof",
        )

        /**
         * Symbols the bundled CPython headers mark with `Py_DEPRECATED(...)`.
         *
         * Derived rather than listed: the deprecation schedule belongs to CPython, and a list
         * copied into Kotlin goes stale the next time the bundled interpreter moves. `internal/`
         * is skipped -- those are private symbols this project may not call at all.
         *
         * "Derived" only helps if the tree derived from is the right one. It went stale anyway --
         * the tree sat at 3.13 while `pythonVersion` moved to 3.14.7 -- so the version agreement
         * is asserted separately in [VendoredHeaderVersionTest] rather than assumed here. The
         * refresh to 3.14.7 took this set from 81 symbols to 101 and changed no verdict: none of
         * the 23 newly deprecated names is in the `expect` surface.
         */
        val cpythonDeprecatedSymbols: Set<String> by lazy {
            val root = File(moduleDirectory, "src/nativeInterop/cinterop/include")
            val result = LinkedHashSet<String>()
            root.walkTopDown()
                .filter { it.isFile && it.extension == "h" && "internal" !in it.path }
                .forEach { header ->
                    val live = stripComments(header.readText(), nestable = false)
                    for (marker in DEPRECATED_MARKER.findAll(live)) {
                        val end = live.indexOfAny(charArrayOf(';', '{'), marker.range.last)
                        val statement = live.substring(marker.range.first, if (end < 0) live.length else end)
                        val called = CALLED_IDENTIFIER.findAll(statement)
                            .map { it.groupValues[1] }
                            .filter { it !in MACROS }
                            .toList()
                        if (called.isNotEmpty()) {
                            result += called
                        } else {
                            TRAILING_IDENTIFIER.find(statement.trimEnd())?.let { result += it.groupValues[1] }
                        }
                    }
                }
            result
        }
    }
}
