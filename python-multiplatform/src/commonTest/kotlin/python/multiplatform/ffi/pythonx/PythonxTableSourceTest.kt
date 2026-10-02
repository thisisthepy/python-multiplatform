package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.TypeTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated half of `pythonx`, as text, with no interpreter anywhere.
 *
 * `PythonProxySource`'s KDoc gives the reason for keeping the renderer a pure function and this
 * follows it: what crosses into Python per declaration is then a *readable artefact* that a person
 * can diff, rather than a behaviour that only exists while an interpreter is running. The other
 * half -- that the adapter does the right thing with these rows -- is `PythonxAdapterTest`, which
 * needs one.
 */
class PythonxTableSourceTest {

    private val extension = ExposedCallable(
        name = "androidx.compose.foundation.layout.padding__Dp",
        arity = 2,
        paramTypes = listOf(TypeTag.OBJECT, TypeTag.FLOAT),
        returnType = TypeTag.OBJECT,
        paramNames = listOf("<receiver>", "all"),
        paramTypeNames = listOf("androidx.compose.ui.Modifier", "androidx.compose.ui.unit.Dp"),
        returnTypeName = "androidx.compose.ui.Modifier",
        isExtension = true,
        receiverTypeName = "androidx.compose.ui.Modifier",
        paramHasDefault = listOf(false, false),
    ) { null }

    private val plain = ExposedCallable(
        name = "demo.calc.ping",
        arity = 0,
        paramTypes = emptyList(),
        returnType = TypeTag.INT,
        kind = CallableKind.FUNCTION,
    ) { 7L }

    /** One row per entry, and every field `docs/design/pythonx-adapter-design.md` §2.4 asked for. */
    @Test
    fun aWalkedExtensionRendersAsOneRowCarryingItsWholeDeclaration() {
        assertEquals(
            "import python_multiplatform.binding as _px_binding\n" +
                "_px_binding._register_table((\n" +
                "    ('androidx.compose.foundation.layout.padding__Dp', 2, 'FUNCTION', False, " +
                "('<receiver>', 'all'), ('OBJECT', 'FLOAT'), " +
                "('androidx.compose.ui.Modifier', 'androidx.compose.ui.unit.Dp'), 'OBJECT', " +
                "'androidx.compose.ui.Modifier', True, 'androidx.compose.ui.Modifier', " +
                "(False, False)),\n" +
                "))\n" +
                "_px_binding._boundary()\n" +
                "del _px_binding",
            PythonxAdapter.renderTable(listOf(extension)),
        )
    }

    /**
     * A producer that supplied no declaration metadata renders empty tuples rather than nothing.
     *
     * Every hand-written fragment in this repository's tests is such a producer
     * ([ExposedCallable.paramNames] is documented as empty-means-not-supplied), so the adapter has
     * to keep working positionally for them -- which it can only do if the row shape is fixed.
     */
    @Test
    fun anEntryWithNoDeclarationMetadataStillRendersAWellFormedRow() {
        val rendered = PythonxAdapter.renderTable(listOf(plain))
        assertTrue(
            rendered.contains("('demo.calc.ping', 0, 'FUNCTION', False, (), (), (), 'INT', None, False, None, ())"),
            rendered,
        )
    }

    /** A one-element tuple needs the trailing comma or it is not a tuple at all. */
    @Test
    fun aSingleParameterRendersAsATupleAndNotAsAParenthesisedValue() {
        val single = ExposedCallable(
            name = "demo.calc.shout",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
            paramNames = listOf("who"),
        ) { null }
        assertTrue(PythonxAdapter.renderTable(listOf(single)).contains("('who',), ('STRING',),"), "trailing comma")
    }

    /**
     * The mechanical constraint the delivery route imposes on the hand-written source, checked
     * where it can be broken rather than left to a `SyntaxError` inside a string nobody can see.
     */
    @Test
    fun theHandWrittenSourceSurvivesBeingWrappedInAPythonRawLiteral() {
        assertFalse(PythonxAdapter.SOURCE.contains("\"\"\""), "a triple double-quote would end the literal early")
        assertFalse(PythonxAdapter.SOURCE.endsWith("\\"), "a trailing backslash would escape the closing quote")
        val rendered = PythonxAdapter.render(listOf(plain))
        // The root module first (`KotlinSurface`), because the binding layer imports from it.
        assertTrue(rendered.startsWith("import sys as _pms_sys\n"), rendered.take(40))
        assertTrue(rendered.contains("\n_px_src = r\"\"\"\n"), "the binding layer's own literal")
        assertTrue(
            rendered.contains("_px_sys.modules['python_multiplatform.binding'] = _px_mod"),
            "the module has to reach sys.modules, under a binder-owned name",
        )
        assertFalse(rendered.contains("'pythonx'"), "the binder must not occupy the pythonx name")
    }

    /**
     * The layer's whole claim about itself, as a grep: **it names no Kotlin declaration.**
     *
     * `docs/design/pythonx-adapter-design.md` §7 says the count of per-component files is the symptom to
     * watch for, and this is that check one level finer -- a `Text`, a `Button` or a `padding` in
     * the hand-written source would mean a rule had been written as a special case. The two
     * package prefixes in the seeded map are the deliberate exception and are asserted to be the
     * only one.
     */
    @Test
    fun theHandWrittenSourceMentionsNoKotlinDeclaration() {
        val code = codeOnly(PythonxAdapter.SOURCE)
        val offenders = listOf("padding", "Modifier", "fillMaxWidth", "Button", "Text(", "material3")
            .filter { code.contains(it) }
        assertEquals(emptyList(), offenders, "a per-declaration name in a per-rule file")
        // Stronger than it used to be. This file used to assert that the source *did* seed
        // `register_package('pythonx.compose', 'androidx.compose')` and the `Dp` allowlist entry --
        // it pinned the binder knowing a UI library by name. The binder may not know one at all, so
        // the assertion is inverted: the mechanism is here, no library that uses it is.
        assertTrue(code.contains("def allow_raw_primitive("), "the seam has to exist")
        // `python.multiplatform.ffi.pythonx.PythonCallables.*` survives the filter below on purpose:
        // that is the true Kotlin FQN of this binder's own `PythonCallables.Fragment` entries.
        // Two rules, and the second is the stronger one. This file used to assert the *presence*
        // of `register_package('pythonx.compose', 'androidx.compose')`, pinning the binder knowing
        // a UI library by name. `register_package` itself is gone with the rest of the renaming:
        // a Kotlin package is importable under its own name, and what a `pythonx.*` module is
        // called is decided by the distribution that ships it, not here.
        assertEquals(
            emptyList(),
            listOf("androidx", "register_package", "'pythonx", "to_python_name", "to_kotlin_name")
                .filter { code.contains(it) },
            "the binder either names a library or renames a namespace; it may do neither",
        )
    }

    /**
     * `PythonCallables.Fragment`'s `body` slot renders as the `PyObject` sentinel, not `kotlin.Any`.
     *
     * `886a8e8f` fixed the argument-direction unwrapping bug for KSP-produced entries
     * (`PythonProxySource.argValues`/`PY_OBJECT`) and noted a latent instance of the same defect:
     * `pythonx.runtime.newFunction`'s `body` parameter was declared `kotlin.Any`, but the entry's own
     * callable does `args[0] as PyObject`. `argValues` only skips `_pm_unwrap` for a slot declared
     * *exactly* `python.multiplatform.ffi.PyObject`, so `kotlin.Any` there meant a proxy handed to
     * `body` through a rendered `PythonProxySource` call site would be unwrapped to its raw handle
     * and fail that cast -- `PythonCallablesProxyArgumentTest` (`desktopTest`) reproduces it against
     * the unfixed name and pins the corrected behaviour with a live interpreter.
     *
     * This is the row-level half of that fix, with no interpreter: the *only* row `PythonxAdapter`
     * emits for this declaration must carry the `PyObject` name for slot 0, or the argument-direction
     * fix does not reach it at all. It is a no-op for `PythonxAdapter`'s own runtime today --
     * `PythonxAdapter._make_function` is `pythonx.runtime.newFunction`'s only caller, and it invokes
     * through `_boundary()['invoke']` directly by resolved handle, never through `_TABLE`/`_coerce`,
     * so nothing in `_KOTLIN_PRIMITIVES` or `_is_value_class_over_primitive` ever inspects this row's
     * `param_type_names` -- but the row is exactly what a future caller reached through `_coerce`
     * would see, so it has to say the true thing regardless of who is asking today.
     */
    @Test
    fun theNewFunctionFragmentDeclaresItsBodySlotAsThePyObjectSentinelNotAny() {
        val rendered = PythonxAdapter.renderTable(PythonCallables.Fragment.entries())
        assertTrue(
            rendered.contains(
                "('python.multiplatform.ffi.pythonx.PythonCallables.newFunction', 5, 'FUNCTION', False, " +
                    "('body', 'jvmArity', 'composable', 'argTags', 'internKey'), " +
                    "('OBJECT', 'INT', 'BOOLEAN', 'STRING', 'STRING'), " +
                    "('python.multiplatform.ffi.PyObject', 'kotlin.Int', 'kotlin.Boolean', " +
                    "'kotlin.String', 'kotlin.String'), ",
            ),
            rendered,
        )
        assertFalse(
            rendered.contains("('kotlin.Any', 'kotlin.Int', 'kotlin.Boolean'"),
            "the body slot must not go back to declaring kotlin.Any, which argValues does not " +
                "recognise as the PyObject sentinel",
        )
    }

    /**
     * [PythonxAdapter.SOURCE] with its comments and docstrings removed.
     *
     * The distinction is the whole point of the test above: the *documentation* names `Modifier`
     * and `padding` constantly, because they are what the rules were written against and a reader
     * needs the example. The *code* may not, because a rule that names a declaration is not a rule.
     */
    private fun codeOnly(source: String): String {
        val out = StringBuilder()
        var inDocstring = false
        for (line in source.lines()) {
            if (inDocstring) {
                if (line.contains("'''")) inDocstring = false
                continue
            }
            if (line.trimStart().startsWith("#")) continue
            val fences = line.split("'''").size - 1
            if (fences == 1) {
                inDocstring = true
                out.appendLine(line.substringBefore("'''"))
                continue
            }
            out.appendLine(line.substringBefore("  # "))
        }
        return out.toString()
    }
}
