package python.multiplatform.ffi

/**
 * Builds the cycle-collecting heap type, and -- on the targets this has been carried all the way
 * through -- puts a real base class in Python's hands for a generated proxy to subclass.
 *
 * `createProxyType()` alone was never the gap: `docs/roadmap/ROADMAP.md` §7 records that the type it builds has
 * carried `tp_traverse`/`tp_clear`/`tp_dealloc` since `work/cycles` and that a real cycle collects
 * through a hand-built instance of it (`CycleCollectionTest`). What stayed open is that no
 * *production* proxy -- the classes `python.multiplatform.ffi.upcall.PythonProxySource` renders --
 * was ever an instance of it: they store their handle in a Python attribute (`self._pm_handle`),
 * and `tp_traverse` reads it from `PyObject_GetTypeData`, a slot a plain Python attribute never
 * reaches. [installGcBase] is the other half: it publishes the type into `__main__` as
 * `_pm_proxy_base`, and [python.multiplatform.ffi.upcall.PythonProxySource]'s generated `_PmGcObject`
 * subclasses it -- through a `PyMemberDef` carrying `Py_RELATIVE_OFFSET` (CPython 3.12+) named
 * `_pm_handle`, so `self._pm_handle = ...` in the generated `__init__` writes straight into the C
 * slot with no change to the generated Python at all.
 */
expect object ProxyTypeFactory {
    fun createProxyType(): Long

    /**
     * Publishes [createProxyType]'s type into `__main__` as `_pm_proxy_base`, so a generated proxy
     * class can subclass a real `Py_TPFLAGS_HAVE_GC` type instead of a plain Python one.
     *
     * `true` on desktop, iOS and androidNative. Android and wasm still answer `false` and touch
     * nothing else -- [python.multiplatform.ffi.upcall.PythonProxySource] reads `_pm_proxy_base`
     * out of `globals()` at `exec` time and falls back to `_PmObject` when it is absent, which is
     * this codebase's existing behaviour there. Wiring a target in means giving its
     * `ProxyTypeFactory` a `PyMemberDef`-carrying handle slot (or an equivalent) and an
     * `installGcBase` that publishes the type the same way; `docs/roadmap/ROADMAP.md` §7 has the per-target
     * notes on what that needs.
     */
    fun installGcBase(): Boolean
}
