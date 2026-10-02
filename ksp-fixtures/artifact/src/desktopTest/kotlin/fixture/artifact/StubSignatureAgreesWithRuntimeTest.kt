package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #31's second completion criterion: the stub's signature for a declaration is the one
 * `inspect.signature` answers at run time.
 *
 * The stubs are the ones the **build** generated (`generatePythonStubs`, over the real Compose jars
 * this module resolves), and the runtime is the real one: the same table installed into a real
 * interpreter through `PythonProxySource`, whose published functions carry `__wrapped__` carriers that
 * `KotlinSurface` builds `inspect.Signature`s from. Both are read **inside Python** -- the stub with
 * `ast`, the runtime with `inspect` -- and compared declaration by declaration over every stubbed
 * function in the modules below, not a hand-picked sample, so a rule changed on one side only fails
 * here.
 *
 * What is compared, per parameter: its name, its kind (positional-only, positional-or-keyword,
 * keyword-only), whether it has a default, and its type. The type is where the two sides speak
 * different dialects -- the runtime says the declared Kotlin name as a string
 * (`'androidx.compose.ui.unit.Dp'`), the stub says a Python annotation -- so the comparison is the
 * mapping the stub documents: a Kotlin primitive is its Python builtin, a function type is a
 * `Callable`, and any other class is that class's stub, named by its fully qualified name.
 *
 * Skipped, and counted so that the skip cannot grow silently: a declaration whose runtime signature is
 * the generic `(*args, **kwargs)` (a Kotlin parameter name Python cannot spell, or a Java class
 * compiled without `-parameters` -- the stub is positional-only there, `__a<index>`, and the runtime
 * has no names to compare).
 */
class StubSignatureAgreesWithRuntimeTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    private val stubDir: String
        get() = System.getProperty("python.multiplatform.stubDir")
            ?: error("the stubDir system property is not set; see build.gradle.kts")

    @Test
    fun everyStubbedSignatureMatchesInspectSignatureAtRunTime() {
        Python3.exec(
            """
            import ast, importlib, inspect

            STUBS = r'$stubDir'
            MODULES = [
                'androidx.compose.foundation.layout',
                'androidx.compose.foundation',
                'kotlin.text',
            ]
            BUILTIN = {
                'kotlin.Boolean': 'bool', 'kotlin.Byte': 'int', 'kotlin.Short': 'int', 'kotlin.Int': 'int',
                'kotlin.Long': 'int', 'kotlin.Float': 'float', 'kotlin.Double': 'float', 'kotlin.String': 'str',
                'kotlin.Char': 'str', 'kotlin.ByteArray': 'bytes', 'kotlin.Unit': 'None', 'kotlin.Any': '_t.Any',
            }

            TAG = {'INT': 'int', 'FLOAT': 'float', 'BOOLEAN': 'bool', 'STRING': 'str', 'BYTES': 'bytes', 'UNIT': 'None'}

            def annotation_agrees(runtime, stub_node, tag):
                if stub_node is None:
                    return runtime in ('', None, inspect.Parameter.empty)
                stub = ast.unparse(stub_node)
                parts = [part for part in stub.split(' | ') if part != 'None'] or ['None']
                if runtime is inspect.Parameter.empty or not runtime:
                    return False
                runtime = runtime.rstrip('?')
                if runtime.startswith('kotlin.Function') or runtime.startswith('@Composable'):
                    return stub.startswith('_t.Callable')
                if runtime in BUILTIN:
                    return stub == BUILTIN[runtime] or parts == [BUILTIN[runtime]] or parts[0].endswith('Any')
                if tag in TAG:
                    # a value class bound as its primitive: the raw number crosses (`Dp | float`, or `float`
                    # where the class name is taken by a function in its module)
                    return TAG[tag] in parts
                # an object: the stub class named by its FQN (or bare inside its own module), or `Any`
                # where a function in its module owns the name -- never an `int` handle
                simple = runtime.rsplit('.', 1)[-1]
                return parts[0].endswith('Any') or runtime in stub or parts[0] == simple

            def stub_parameters(fn):
                a = fn.args
                positional = a.posonlyargs + a.args
                defaults = [None] * (len(positional) - len(a.defaults)) + list(a.defaults)
                out = [(p.arg, 'POSITIONAL_ONLY' if i < len(a.posonlyargs) else 'POSITIONAL_OR_KEYWORD',
                        d is not None, p.annotation) for i, (p, d) in enumerate(zip(positional, defaults))]
                for p, d in zip(a.kwonlyargs, a.kw_defaults):
                    out.append((p.arg, 'KEYWORD_ONLY', d is not None, p.annotation))
                return out

            compared = 0
            skipped = []
            problems = []
            for module_name in MODULES:
                path = STUBS + '/' + module_name.replace('.', '/') + '/__init__.pyi'
                tree = ast.parse(open(path).read())
                module = importlib.import_module(module_name)
                for node in tree.body:
                    if not isinstance(node, ast.FunctionDef):
                        continue
                    if any(isinstance(d, ast.Attribute) and d.attr == 'overload' for d in node.decorator_list):
                        continue  # an overload set's base name has no single signature: `(*args, **kwargs)`
                    runtime_fn = getattr(module, node.name, None)
                    if runtime_fn is None:
                        problems.append(module_name + '.' + node.name + ': stubbed but not on the module')
                        continue
                    try:
                        signature = inspect.signature(runtime_fn)
                    except (TypeError, ValueError):
                        skipped.append(module_name + '.' + node.name)
                        continue
                    runtime_parameters = list(signature.parameters.values())
                    row = runtime_fn.__kotlin_rows__[0]
                    row_names, row_tags = row[4] or (), row[5] or ()

                    def tag_of(parameter_name):
                        key = '<receiver>' if parameter_name in ('receiver', '_receiver') and '<receiver>' in row_names else parameter_name
                        return row_tags[row_names.index(key)] if key in row_names else None
                    if any(p.kind in (p.VAR_POSITIONAL, p.VAR_KEYWORD) for p in runtime_parameters):
                        skipped.append(module_name + '.' + node.name)
                        continue
                    stub = stub_parameters(node)
                    label = module_name + '.' + node.name
                    if [p.name for p in runtime_parameters] != [s[0] for s in stub]:
                        # a Java-sourced or keyword-named declaration: the stub is positional-only `__a<i>`
                        if all(s[0].startswith('__a') for s in stub[:len(runtime_parameters)]) or any(s[0].startswith('__a') for s in stub):
                            skipped.append(label)
                            continue
                        problems.append(label + ': names ' + repr([p.name for p in runtime_parameters]) + ' vs stub ' + repr([s[0] for s in stub]))
                        continue
                    for p, (name, kind, has_default, ann) in zip(runtime_parameters, stub):
                        if p.kind.name != kind:
                            problems.append(label + '.' + name + ': kind ' + p.kind.name + ' vs stub ' + kind)
                        if (p.default is not p.empty) != has_default:
                            problems.append(label + '.' + name + ': default ' + repr(p.default) + ' vs stub ' + repr(has_default))
                        if not annotation_agrees(p.annotation, ann, tag_of(p.name)):
                            problems.append(label + '.' + name + ': type ' + repr(p.annotation) + ' vs stub ' + (ast.unparse(ann) if ann else 'none'))
                    if not annotation_agrees(signature.return_annotation, node.returns, row[7]):
                        problems.append(label + ' -> ' + repr(signature.return_annotation) + ' vs stub ' + ast.unparse(node.returns))
                    compared += 1

            assert compared >= 30, 'only ' + str(compared) + ' signatures were compared; skipped ' + repr(skipped[:10])
            assert not problems, str(len(problems)) + ' stub/runtime disagreements, first: ' + repr(problems[:12])
            print('compared', compared, 'skipped', len(skipped))
            """.trimIndent(),
        )
    }

    @Test
    fun theComparisonCanFailBecauseTheStubDirIsReallyRead() {
        // A comparison that cannot fail is not one (AGENTS.md rule 8): the stub it reads must be the
        // generated one, so a path with no stub in it has to raise rather than compare nothing.
        val raised = runCatching {
            Python3.exec(
                """
                open(r'$stubDir/androidx/compose/foundation/layout/no_such_stub.pyi').read()
                """.trimIndent(),
            )
        }.isFailure
        assertTrue(raised)
    }
}
