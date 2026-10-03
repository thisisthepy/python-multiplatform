package python.multiplatform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The three @WasmExport trampoline lines ProxyTypeExports.kt used to hand-write.
 *
 * The rendered output is a pure function, so it is unit-tested here in isolation before the
 * Gradle task that writes it to disk is ever wired.
 *
 * The package name is the only variable part.
 */
class WasmProxyExportsRenderingTest {

    @Test
    fun renderedOutputStartsWithGeneratedNotice() {
        val source = renderWasmProxyExportsSource("python.multiplatform.test")
        assertTrue(
            source.startsWith("// GENERATED"),
            "Expected source to start with '// GENERATED', got: ${source.take(40)}",
        )
    }

    @Test
    fun renderedOutputOptsInToExperimentalWasmInterop() {
        val source = renderWasmProxyExportsSource("some.pkg")
        assertTrue(
            source.contains("@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)"),
            "Expected @file:OptIn for ExperimentalWasmInterop",
        )
    }

    @Test
    fun renderedOutputUsesTheGivenPackageName() {
        val source = renderWasmProxyExportsSource("my.executable.module")
        assertTrue(
            source.contains("package my.executable.module"),
            "Expected 'package my.executable.module', got:\n$source",
        )
    }

    @Test
    fun renderedOutputContainsAllThreeWasmExportAnnotations() {
        val source = renderWasmProxyExportsSource("test.pkg")
        assertTrue(source.contains("@kotlin.wasm.WasmExport(\"pmp_tp_traverse\")"), "missing traverse export")
        assertTrue(source.contains("@kotlin.wasm.WasmExport(\"pmp_tp_clear\")"), "missing clear export")
        assertTrue(source.contains("@kotlin.wasm.WasmExport(\"pmp_tp_dealloc\")"), "missing dealloc export")
    }

    @Test
    fun traverseTrampolineDelegatesToProxyType() {
        val source = renderWasmProxyExportsSource("test.pkg")
        assertTrue(
            source.contains("ProxyType.traverse("),
            "traverse body should delegate to ProxyType.traverse",
        )
    }

    @Test
    fun clearTrampolineDelegatesToProxyType() {
        val source = renderWasmProxyExportsSource("test.pkg")
        assertTrue(
            source.contains("ProxyType.clear("),
            "clear body should delegate to ProxyType.clear",
        )
    }

    @Test
    fun deallocTrampolineDelegatesToProxyType() {
        val source = renderWasmProxyExportsSource("test.pkg")
        assertTrue(
            source.contains("ProxyType.dealloc("),
            "dealloc body should delegate to ProxyType.dealloc",
        )
    }

    @Test
    fun traverseHasCorrectArity() {
        // tp_traverse: (PyObject *self, visitproc visit, void *arg) -> int — three i32 params
        val source = renderWasmProxyExportsSource("test.pkg")
        assertTrue(
            source.contains("selfPtr: Int, visitPtr: Int, argPtr: Int"),
            "traverse must declare three Int parameters (selfPtr, visitPtr, argPtr)",
        )
    }

    @Test
    fun clearHasCorrectArity() {
        // tp_clear: (PyObject *self) -> int — one i32 param
        val source = renderWasmProxyExportsSource("test.pkg")
        val clearFun = source.substringAfter("pmp_tp_clear\"")
        assertTrue(
            clearFun.contains("selfPtr: Int): Int"),
            "clear must have exactly (selfPtr: Int): Int signature",
        )
    }

    @Test
    fun deallocHasVoidReturn() {
        // tp_dealloc: (PyObject *self) -> void — no return type annotation (Unit)
        val source = renderWasmProxyExportsSource("test.pkg")
        val deallocFun = source.substringAfter("pmp_tp_dealloc\"")
        assertTrue(
            deallocFun.contains("selfPtr: Int) ="),
            "dealloc must be '(selfPtr: Int) =' — no return type (Unit/void)",
        )
    }

    @Test
    fun renderedOutputIsStableAcrossInvocations() {
        val a = renderWasmProxyExportsSource("pkg.x")
        val b = renderWasmProxyExportsSource("pkg.x")
        assertEquals(a, b, "Rendering must be deterministic")
    }

    @Test
    fun renderedOutputIncreasesWithExtraSlot() {
        val extraSlot = WasmExportSlot(
            exportName = "pmp_tp_call",
            kotlinName = "pmpTpCall",
            parameters = "selfPtr: Int, argsPtr: Int, kwdsPtr: Int",
            returnType = "Int",
            delegateCall = "ProxyType.call(selfPtr, argsPtr, kwdsPtr)"
        )
        
        val baseSlots = DEFAULT_PROXY_TYPE_SLOTS
        val extraSlots = baseSlots + extraSlot
        
        val three = renderWasmProxyExportsSource("pkg.x", slots = baseSlots)
        val four = renderWasmProxyExportsSource("pkg.x", slots = extraSlots)
        
        assertTrue(four.length > three.length, "Output should be longer with more slots")
        
        // base slots have traverse, clear, dealloc but not call
        assertTrue(three.contains("pmp_tp_traverse\""))
        assertTrue(three.contains("pmp_tp_clear\""))
        assertTrue(three.contains("pmp_tp_dealloc\""))
        assertTrue(!three.contains("pmp_tp_call\""))
        
        // extra slots have call as well
        assertTrue(four.contains("pmp_tp_traverse\""))
        assertTrue(four.contains("pmp_tp_clear\""))
        assertTrue(four.contains("pmp_tp_dealloc\""))
        assertTrue(four.contains("pmp_tp_call\""))
        assertTrue(four.contains("ProxyType.call(selfPtr, argsPtr, kwdsPtr)"))
    }
}
