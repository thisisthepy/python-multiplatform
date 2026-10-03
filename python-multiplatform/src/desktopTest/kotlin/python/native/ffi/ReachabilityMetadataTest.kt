package python.native.ffi

import python.multiplatform.ffi.PythonTestFixture
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the class of failure that no compiler catches: a GraalVM native image builds clean and
 * then dies at the first call with `MissingForeignRegistrationError`, because some
 * `FunctionDescriptor` the FFI actually links was never declared in
 * `META-INF/native-image/.../reachability-metadata.json`.
 *
 * `GenerateReachabilityMetadata` (python-multiplatform/build.gradle.kts) derives that file by
 * *parsing* the FFI sources. This test checks the same file the other way round: it asks the
 * loaded classes what descriptors they actually built -- `MethodHandle.type()` is the descriptor,
 * after the FFM linker has already resolved it -- and asserts every one of them is declared. The
 * two paths share no code, so a parser that quietly stops recognising a declaration form shows up
 * here as a missing descriptor rather than as a binary that only fails on a customer's machine.
 *
 * It cannot run on a JVM where the FFI failed to load at all, so it is skipped (loudly) in that
 * case -- [PythonTestFixture.available] already reports that condition on its own.
 */
class ReachabilityMetadataTest {

    private data class Descriptor(val returnType: String, val parameterTypes: List<String>) {
        override fun toString() = "$returnType (${parameterTypes.joinToString(", ")})"
    }

    // -- the shipped metadata ---------------------------------------------------------------

    private val metadataResource =
        "/META-INF/native-image/org.thisisthepy/python-multiplatform/reachability-metadata.json"

    private fun readMetadata(): String =
        javaClass.getResourceAsStream(metadataResource)?.bufferedReader()?.readText()
            ?: fail(
                "$metadataResource is not on the test runtime classpath. It is produced by the " +
                    "`generateDesktopReachabilityMetadata` task and wired into desktopMain's " +
                    "resources; if that wiring is gone, every native image built from this " +
                    "library ships without foreign registrations."
            )

    /**
     * Deliberately a regex and not a JSON parser: the test must not depend on a serialization
     * library that native-image consumers do not have, and the generator emits one descriptor per
     * line in a fixed shape.
     */
    private fun parseSection(json: String, section: String): Set<Descriptor> {
        val start = json.indexOf("\"$section\": [")
        assertTrue(start >= 0, "reachability metadata has no `$section` array")
        // `parameterTypes` arrays are nested inside this one, so the closing bracket has to be
        // found by depth, not by the first `]`.
        var depth = 0
        var end = -1
        for (i in json.indexOf('[', start) until json.length) {
            if (json[i] == '[') depth++
            if (json[i] == ']') {
                depth--
                if (depth == 0) { end = i; break }
            }
        }
        assertTrue(end > start, "reachability metadata's `$section` array is not closed")
        val body = json.substring(start, end)
        return Regex("""\{\s*"returnType"\s*:\s*"(\w+)"\s*,\s*"parameterTypes"\s*:\s*\[([^]]*)]\s*}""")
            .findAll(body)
            .mapTo(LinkedHashSet()) { match ->
                Descriptor(
                    match.groupValues[1],
                    match.groupValues[2].split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                )
            }
    }

    // -- what the code actually links -------------------------------------------------------

    private fun carrier(cls: Class<*>): String = when (cls) {
        Void.TYPE -> "void"
        java.lang.Long.TYPE -> "long"
        Integer.TYPE -> "int"
        java.lang.Double.TYPE -> "double"
        java.lang.Float.TYPE -> "float"
        java.lang.Short.TYPE -> "short"
        java.lang.Byte.TYPE -> "byte"
        Character.TYPE -> "char"
        java.lang.Boolean.TYPE -> "boolean"
        else -> fail(
            "MethodHandle carrier type $cls is not a primitive. The desktop FFI boundary is " +
                "supposed to be primitives only (see desktopMain/README.md); a reference type " +
                "here means the reachability metadata cannot describe it either."
        )
    }

    private fun MethodType.toDescriptor(dropLeadingArguments: Int = 0): Descriptor =
        Descriptor(carrier(returnType()), parameterList().drop(dropLeadingArguments).map { carrier(it) })

    private fun methodHandleFields(owner: Class<*>, instance: Any?): List<Pair<String, MethodHandle>> =
        owner.declaredFields
            .filter { MethodHandle::class.java.isAssignableFrom(it.type) }
            .map { field ->
                field.isAccessible = true
                // Field.get ignores the receiver for static fields, so one call covers both the
                // instance fields of a Kotlin `object` and the static fields of a file facade.
                field.name to (field.get(instance) as MethodHandle)
            }

    // -- tests ------------------------------------------------------------------------------

    /**
     * Every `find(...)` handle in `bindings` -- one per CPython entry point -- must have its
     * descriptor declared. This is the list that grows whenever `EmbedAPI.kt` gains a signature,
     * and the reason the metadata cannot be maintained by hand.
     */
    @Test
    fun bindingsDowncallDescriptorsAreAllRegistered() {
        assertTrue(PythonTestFixture.available, "CPython could not be initialized: ${PythonTestFixture.failureReason}")

        val declared = parseSection(readMetadata(), "downcalls")
        val handles = methodHandleFields(bindings.javaClass, bindings)
        assertTrue(
            handles.size > 300,
            "expected the full CPython binding surface, found only ${handles.size} MethodHandle " +
                "fields on `bindings` -- the reflection this test relies on has stopped working"
        )

        val missing = handles
            .map { (name, handle) -> name to handle.type().toDescriptor() }
            .filter { (_, descriptor) -> descriptor !in declared }

        assertTrue(
            missing.isEmpty(),
            "reachability metadata is missing ${missing.map { it.second }.toSet().size} downcall " +
                "descriptor(s) that `bindings` links at runtime; a native image would fail with " +
                "MissingForeignRegistrationError on the first call to any of " +
                "${missing.take(5).map { it.first }}. Missing: ${missing.map { it.second }.toSet()}"
        )
    }

    /**
     * The 14-entry shape vocabulary is built from unbound handles whose leading argument is the
     * target address (bound by `filterArguments`, so it is on the `MethodHandle` but not on the
     * `FunctionDescriptor`); the leading argument is dropped before comparing.
     */
    @Test
    fun shapeVocabularyDescriptorsAreAllRegistered() {
        assertTrue(PythonTestFixture.available, "CPython could not be initialized: ${PythonTestFixture.failureReason}")

        val declared = parseSection(readMetadata(), "downcalls")
        val facade = Class.forName("python.native.ffi.ShapeDowncalls_desktopKt")
        val handles = methodHandleFields(facade, null)
        assertTrue(
            handles.isNotEmpty(),
            "no MethodHandle fields found on ${facade.name}; the shape vocabulary this test " +
                "checks has moved and the check is now vacuous"
        )

        val missing = handles
            .map { (name, handle) -> name to handle.type().toDescriptor(dropLeadingArguments = 1) }
            .filter { (_, descriptor) -> descriptor !in declared }

        assertTrue(
            missing.isEmpty(),
            "reachability metadata is missing shape-vocabulary downcall descriptor(s): $missing"
        )
    }

    /**
     * Upcall descriptors are assembled reflectively inside `Panama`'s stub builders, so there is
     * no handle to read them off. What can be checked is that the number of stub entry points has
     * not changed behind the metadata's back: each `createUpcallStub*` on `Panama` is one shape
     * the generator has to know about (via the `// @UpcallShape(...)` markers it parses).
     *
     * This is the check that would have caught the bug this test was written after: the
     * checked-in metadata declared only `long (long)` while `ProxyTypeFactory` was already
     * calling `createUpcallStubIII_I` and `createUpcallStubI_I`.
     */
    @Test
    fun everyUpcallStubEntryPointHasADeclaredDescriptor() {
        val declared = parseSection(readMetadata(), "upcalls")

        val entryPoints = Panama::class.java.declaredFields
            .map { it.name }
            .filter { it.startsWith("createUpcallStub") }
            .toSortedSet()

        assertEquals(
            setOf(
                "createUpcallStubI_I",
                "createUpcallStubI_V",
                "createUpcallStubII_L",
                "createUpcallStubIII_I",
                "createUpcallStubLongToLong",
            ),
            entryPoints.toSet(),
            "Panama's set of upcall stub entry points changed. Each one links a FunctionDescriptor " +
                "that must appear under `foreign.upcalls`: add a `// @UpcallShape(...)` marker next " +
                "to the new builder in Panama.kt (the generator fails the build without one) and " +
                "update this test's expected set."
        )

        assertEquals(
            setOf(
                Descriptor("long", listOf("long")),
                // The argument-carrying trampoline, and the shape a PyCFunction slot takes:
                // (callable handle, args tuple) -> new reference. See UpcallTrampoline.
                Descriptor("long", listOf("long", "long")),
                Descriptor("int", listOf("long")),
                Descriptor("int", listOf("long", "long", "long")),
                // tp_dealloc: destructor returns void, so this shape exists as of the
                // handle-leak fix. The int shape only survived that mismatch by ABI accident.
                Descriptor("void", listOf("long")),
            ),
            declared,
            "the declared upcall descriptors no longer match the stub shapes Panama builds"
        )
    }
}
