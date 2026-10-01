# commonMain — rules

The object model and the `expect` declarations every platform implements.

## Layering — the object model must not reach past the FFI layer

    python.multiplatform.ffi        PyObject, PyType, collections, conversion
            |  may call
            v
    python.native.ffi               EmbedAPI: expect/actual over the C API
            |  may call (platform implementations only)
            v
    python.native.ffi.bindings      JNI externals / Panama handles

`PyObject` and friends call EmbedAPI functions and nothing below them. `bindings` is a platform
detail that only `EmbedAPI.<platform>.kt` may know about — it does not exist at all on the
native targets, which use cinterop.

A composed operation is a platform implementation difference, so it belongs in the FFI layer as
an `expect`/`actual`, **not** in the object model. Composition therefore does not require
reopening `PyObject` as `expect`/`actual`. See `docs/design/architecture.md`.

## Every C API call needs the GIL

Including reference counting. `withPython { }` and `withGIL { }` attach a thread state; a call
made without one is undefined behaviour, and the resulting segfault lands far from its cause.

This is true on free-threaded builds too. Dropping the global lock removes contention, not the
requirement that a thread be attached before touching any object.

## An entry point reached from C must not trust the nesting depth

`withGIL` skips `PyGILState_Ensure` when this thread's depth is already non-zero. That counter
records scopes *Kotlin* opened, and C is free to have dropped the GIL inside one of them:
`ctypes.CFUNCTYPE` releases it around every foreign call (`PYFUNCTYPE` does not). So a callback
entered from C can run with a non-zero depth and no thread state at all, and the first C API call
segfaults inside `_PyThreadState_GET` — observed here as `PyErr_Occurred+0x1c`.

Anything Python calls into therefore takes its own `PyGILState_Ensure`/`Release` pair
unconditionally and restores the counters around it; `python.multiplatform.ffi.upcall
.UpcallTrampoline.attached` is that. It does **not** apply to CPython's own type slots
(`tp_traverse`, `tp_clear`, `tp_dealloc`), which are entered with the GIL held and must neither
take nor release it — see `ProxyTypeFactory`.

### An asynchronous completion is a second entry point, on a thread nobody attached

An exposed `suspend fun` returns an `asyncio.Future` and its value arrives later, on whatever
thread resumed the coroutine — which is generally not the thread that took the upcall, and may be
one CPython has never seen. That thread takes its own `withGIL` scope before touching anything
(`python.multiplatform.ffi.upcall.AsyncUpcall`), and `withGIL` is right here where it is wrong for
a C entry point: this is *not* reached from C, so the thread's nesting depth is its own honest
record. A completer thread with depth 0 takes a real `PyGILState_Ensure`; a completion delivered
from inside a later upcall correctly skips, under the depth that upcall's `attached` set.

The same acquisition is what publishes `PendingCall`'s fields. That class is unsynchronised on the
stated condition that every mutation happens on a GIL-holding thread — so **a dispatcher that
resumes an exposed call's continuation must hold the GIL while it does so**, not merely take it
afterwards to talk to Python. Measured: `AsyncCompletionProbeTest` (a Kotlin-created thread can
take the GIL while the interpreter is parked in `run_until_complete`) and
`AsyncUpcallDeliveryTest`.

## Attaching is not enough — someone has to reach an eval-loop checkpoint

`_Py_HandlePending` is the only place CPython merges the free-threaded build's deferred
reference-count queue, processes QSBR-deferred frees, runs a *scheduled* cyclic collection and
drains pending calls and signals. Nothing in the C API calls it; only the `_CHECK_PERIODIC` uop
that opens every Python-level frame does. So an embedder that never executes bytecode never
reaches one, and a reference the cleaner gave back on a free-threaded build stays unreclaimed
however long you wait — the count is right, the memory is not returned.

`Python3.drainPendingReleases()` reaches one, by calling a cached empty Python function
(measured 355 ns, against 11 µs for `exec("pass")`). It also runs automatically once every
`Python3.autoDrainInterval` outermost `withGIL` scopes, which defaults to on for free-threaded
builds and off otherwise. **Never take a checkpoint from a cleaner**: the queue belongs to the
thread that *owns* the object, so it would drain nothing while running Python exactly where §1
proved that deadlocks. See ROADMAP §9.

### The GIL build needs the same checkpoint, for cyclic garbage specifically

`_PY_GC_SCHEDULED_BIT` — set by `_Py_ScheduleGC` on an allocation that crosses a generation
threshold, and how allocation has triggered a cyclic collection since 3.12 — is read by the same
`_Py_HandlePending` as the free-threaded merge above, and nowhere else. That part of the problem
is **not free-threading-specific**: an embedder that only calls `PyObject_Call` and friends never
runs the cyclic collector on either build, because neither build ever executes a bytecode frame on
its own. A plain (non-cyclic) reference still frees immediately on the GIL build — there is no
queue there — but a reference cycle accumulates exactly as it would on the free-threaded build's
deferred-release queue. Measured: 10,000 cyclic groups built purely through the C API leave
20,000+ objects uncollected indefinitely with `autoDrainInterval = 0`; the same workload with
`autoDrainInterval` on leaves only what has accumulated since the last checkpoint. See
`docs/investigations/gc-scheduling-investigation.md` §1, §6 and §7.

`Python3.autoDrainInterval` therefore defaults to **off** on the GIL build too, and that default
is deliberate rather than an oversight: turning it on is a real behavioural change, not a free
one. A checkpoint tripped inside an ordinary `withGIL` scope can run whatever the collector or the
release queue was holding onto — `__del__`, weakref callbacks, pending calls — at a point the
caller never asked to yield from. Measured reentrant-safe (a `__del__` that calls back into
Kotlin mid-collection completes without deadlocking, looping or crashing —
`GCSchedulingMeasurementTest.testReentrancyDuringCheckpoint`), and cheap (~5.5 ns per outermost
`withGIL` scope, ~2.9% of the floor, amortised over the interval) — but still a side effect an
embedder has to opt into with eyes open, by setting `autoDrainInterval` explicitly or calling
`Python3.drainPendingReleases()`. An embedder that would rather force an immediate, unconditional
collection without touching either can call `PyGC_Collect()` directly (`python.native.ffi`, or
`python.multiplatform.ffi.utils.PyGC.collect()` for the `gc.collect()`-equivalent route) — the
same function `drainPendingReleases()` documents as the way to reclaim on behalf of *another*
thread.

## Reference conventions, stated at every call site

CPython's C API is inconsistent about ownership and getting it wrong is silent. Write which one
applies:

- `PyTuple_SetItem`, `PyList_SetItem` **steal** — do not release afterwards
- `PyTuple_GetItem`, `PyDict_GetItem` **borrow** — do not release; incref to keep
- most `PyObject_*` return **new references** — release when done

`RefCountTest` checks the balance in both directions. One too few leaks; one too many frees an
object still in use, and that crash surfaces somewhere unrelated.

### A converted value has a different lifetime from the object it came from

`PyValue`/`PyProxy` cache the Kotlin projection of a Python object, and the two do not die
together. The rule is per type, not per shape: cache what copied itself out of CPython (`Long`,
`Double`, `Boolean`, a decoded `String`, a container recursively built out of those), refuse what
would still point into Python-owned memory (`bytes`/`bytearray`/`memoryview`, whose native form is
a pointer into the object's own buffer), and never store a bare `NativePointer` — what
`ConversionStrategy.RAW` hands back is an address, not a reference.

`isIndependentOfPythonMemory` in `conversion/PyProxy.kt` is that rule as code, and every store to
`cachedNativeValue` on the conversion path goes through it. The full per-type table, the snapshot
semantics of a cached container, and the borrowed-reference bug this caught in `PyContext` are in
`docs/design/object-lifetime.md`, "Conversion caching, and where it stops".

## `expect` declarations cannot be `inline` in an intermediate source set

Combining `expect inline fun` with an intermediate source set's `expect`/`actual` crashes Kotlin
2.0.20 with `Internal error in file lowering`. 132 of the 147 `inline` markers here already
carry a compiler warning saying inlining gains nothing; drop the marker rather than working
around the crash.
