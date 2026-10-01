# Object lifetime across the boundary

Two runtimes, two memory models, and no shared view between them. This is what has been
settled, what is measured, and what is still open.

## Kotlin holding Python — solved and verified

CPython counts references; a Kotlin wrapper takes one on construction and gives it back when it
closes.

    Python creates the object       refcount 1
    a Kotlin wrapper takes it       refcount 2
    Python drops its reference      refcount 1, still alive
    the Kotlin wrapper closes       refcount 0, freed

`RefCountTest` checks this in both directions, because both failure modes are silent: releasing
one too few leaks, and releasing one too many frees an object still in use — that crash lands
somewhere unrelated, which is how the interpreter corruption during the lifetime work first
appeared, as a segfault inside `Py_Finalize` far from its cause.

~~The wrapper's own release is currently **explicit only**. GC-driven release is implemented but
blocked: `Python3.initialize()` still holds the GIL, so a cleaner thread calling
`PyGILState_Ensure` blocks forever.~~ **Unblocked.** ROADMAP §1 closed: `Python3.initialize()` now
parks its thread state with `PyEval_SaveThread()`, so a cleaner thread's `PyGILState_Ensure`
attaches instead of blocking. ROADMAP §4 closed on top of that — GC-driven release is verified on
both collectors (desktop `java.lang.ref.Cleaner`, iOS `createCleaner`), and Android below API 33
takes the `PhantomReference` fallback. See ROADMAP §1 and §4.

## Python holding Kotlin — the handle table

There is no counterpart to `Py_INCREF` for a JVM or Kotlin/Native object, so something on the
Kotlin side has to act as a GC root:

    private val liveHandles = HashMap<Long, Any>()   // handle -> Kotlin object

    fun register(obj: Any): Long { val h = next++; liveHandles[h] = obj; return h }
    fun release(h: Long)        { liveHandles.remove(h) }

Python holds only the integer handle; the map holds the object. This is what JNI's
`NewGlobalRef` does, made explicit.

### Who calls `release`, and what happened when nobody did

The sketch above has no answer to that, and neither did the implementation. `HandleTable` is
generational precisely so that a double release is safe, and its class doc named the counterpart
exactly — *"the proxy's `tp_dealloc` calling `release` is the entire lifetime contract"* — but
`PythonProxySource` rendered a proxy class whose `__init__` took a handle and which had no path
back at all. Every Kotlin object Python ever constructed stayed rooted for the life of the process.

It was found by a measurement that was not looking for it. `GeneratedProxyCostTest`'s constructor
row builds ten thousand proxies and discards every one; the desktop run ended with **78 002 live
handles**, and the constructor figure was unusable because every row in the report had been timed
against a table that grew by tens of thousands of entries while it ran.

Two separate defects wore that one number, and it is worth keeping them apart:

| | who owns the root | what was wrong |
|---|---|---|
| a proxy instance (`Counter(10)`) | the Python object | the **library**: no `__del__` was rendered. Fixed; `ProxyHandleLifetimeTest` pins it, including for a proxy inside a reference cycle |
| a bare handle from `_pm_invoke` on a CONSTRUCTOR entry | the **caller** | nothing was wrong with the library. An integer has nothing to hang a finaliser off, so the caller must call `_pm_release`; the measurement harness was not doing it |

The leak was **identical on desktop and on the iOS simulator** — 64 handles registered, 64 still
live after `gc.collect()`, on both — which is what one would expect of a defect in generated Python
that `commonMain` renders. The earlier impression that "iOS was stable" was about the *measurement*
and not about the table: the iOS boundary costs about 3.5 µs against desktop's 0.5 µs, so the same
drift was proportionally invisible there.

`__del__` was chosen over `weakref.finalize` on price — the alternatives and their measured costs
are tabulated in `PythonProxySource`'s KDoc. The pre-PEP-442 objection to `__del__` (an object with
one, inside a cycle, was never finalised at all) has not applied since CPython 3.4, and the cycle
case is asserted rather than assumed.

Note what this does **not** solve: the table still holds *strongly*, so the `K1 -> P -> K2 -> K1`
case below is untouched. What changed is only that "Python has finished with it" now reaches Kotlin
at all, which is the precondition for anything else.

A note for anyone reading older discussion: Panama has no equivalent of `NewGlobalRef`, and it
does not need one. Panama is an API for *native memory*, not for JVM object lifetime — a
`MemorySegment` holding an address does not keep a Kotlin object alive. The GC root is an
ordinary Kotlin data structure, not anything Panama provides. Looking for the answer inside
Panama was looking at the wrong layer.

## Cycles

### Why reference counting cannot do it

Not a boundary problem — an inherent limit of reference counting, in any language:

    a = []; b = []
    a.append(b); b.append(a)
    del a, b        # each still holds one, count never reaches 0

This is why CPython carries a cyclic collector on top of refcounting. So "refcounting cannot
free cycles" is not a defeat; it was never refcounting's job.

### What the cyclic collector needs

CPython's algorithm is simple, and it turns on one requirement:

1. each tracked object's `tp_traverse` enumerates the `PyObject`s it holds
2. references discovered that way are subtracted from the refcount
3. if the remainder is zero, the object is kept alive only from inside the cycle — collectable
4. `tp_clear` drops those references and breaks the cycle

**Everything depends on step 1 being able to see through whatever holds the reference.**

### The cycle we can create

    P  ->  PK (Python proxy)  ->  [handle]  ->  K (Kotlin)  ->  PyObject wrapper  ->  P

Without help this never collects, and each collector has a defensible reason:

- CPython's GC traverses `PyObject`s. `PK` holds an opaque integer, so traversal stops there and
  `P` looks externally referenced.
- The JVM's GC collects cycles fine, but `K` is held by a strong entry in the handle map, which
  is a GC root.

Each side sees a live root it cannot reason about, and concludes the other is responsible.

### The resolution

Make the Kotlin object's Python references visible to `tp_traverse`. `PK`'s traverse reaches
through the handle and enumerates the `PyObject`-typed fields of `K`; CPython then sees the
cycle as internal and can collect it.

**This is cheap here specifically because the exposure table is generated at build time.** KSP
already walks every exposed class to build the function table, and at that point it knows which
fields are `PyObject`-typed. A traverse entry is one more generated function:

    // generated
    fun traverse_MyClass(obj: MyClass, visit: (Long) -> Unit) {
        obj.someField?.let { visit(it.pointer.raw()) }
        obj.otherField?.let { visit(it.pointer.raw()) }
    }

No reflection — which is the whole reason the table exists. A reflection-based binding would
find this expensive; that is why mature ones (JPype and similar) document "do not create
cycles" instead of solving it. We are not in that position.

### What is still hard

~~**`tp_traverse` runs during collection.** CPython requires it not to allocate and not to run
arbitrary code, and ours has to call into the JVM. The generated traverse must be restricted to
field reads and `visit` calls — no allocation, no user code, no locking beyond what is already
held.~~ **Built and tested this way.** `ProxyTypeFactory`'s `tp_traverse` (every target) calls only
the generated `traverse` function and `visit`; `CycleCollectionTest`/`WasmCycleCollectionTest`/
`RefHolderCycleCollectionTest` exercise it through a real `gc.collect()`.

~~**`tp_clear` has to mutate Kotlin state.** Breaking the cycle means nulling `K`'s `PyObject`
fields from inside a collection. A `val` field cannot be cleared at all, so either exposed
reference-holding fields must be `var`, or clearing goes through a generated accessor that
knows how.~~ **Solved a different way than this section predicted — no field mutation needed.**
`ProxyTypeFactory.tp_clear` (`desktopMain/.../ProxyTypeFactory.kt:96-108`, mirrored on every other
target) does not touch `K`'s fields at all; it calls `HandleTable.release()` on `PK`'s handle and
nothing else, deliberately not `close()`-ing the held `PyObject`s (the code comment there explains
why: CPython's own cyclic collector, not this callback, is what reclaims the other side, and
calling `close()` here would double-free). Breaking `PK -> [handle] -> K`'s strong root is enough
to let the JVM collect `K` once CPython's collector also breaks `P`'s incoming edge — no `var`, no
generated clearing accessor, and no Kotlin-side field ever needed to be nulled.

**Cycles that close on the Kotlin side.** `K1 -> P -> K2 -> K1` needs the JVM's collector to see
through `P`, and there is no traverse hook on that side. Worse, the handle map holds `K2`
strongly, so it is a root regardless.

The likely answer is to make the handle map hold **weakly**, with the strong reference supplied
by the Python proxy for as long as it lives — so "Python stopped using it" becomes "the JVM may
collect it". That inverts who owns the strength and has not been worked through.

### Status of an older claim

An earlier discussion concluded that "cycles are handled by each language's GC" on the strength
of the four-step refcount sequence at the top of this document. That sequence is correct, and it
is implemented and tested — but it describes the **non-cyclic** case. Step 4, "the Kotlin side
releases", is exactly what a cycle prevents from ever happening. The claim does not follow, and
the same discussion walked it back a few messages later.

## Conversion caching, and where it stops

`PyContext` chooses how far a Python value is converted toward Kotlin, per
`ConversionStrategy`; `withContext` swaps and restores the strategy even on throw, and all five
variants dispatch. `PyValue`'s lazy path is filled in too: the stub that used to end in
`cachedNativeValue!!` — an NPE on any genuine cache miss — is gone. No `!!` on that field remains
anywhere in the library sources; the only occurrence of the expression left in the tree is a
comment in `ConversionTest` recording that it used to be there.

What makes the cache safe is a **per-type rule**, because a cached native value and the
`PyObject` it came from have different lifetimes. Caching a value that copied itself out of
CPython is fine; caching one that still points into Python-owned memory is a use-after-free
waiting for the address to be reused. The rule below is derived from what each conversion
actually returns, not from what the type looks like.

### The rule, per source type

`PyProxy.toKotlinOrNull` converts through `pyObjectToNative`, whose dispatch compares the source's
**exact** type object against `PyTypeChecks`' cached builtin types — `PyLong_Check` semantics, not
`isinstance`. So the table is by exact type; a subclass falls through to the last row.

| Python type | Conversion | Kotlin result | Points into Python memory? | Cached? |
|---|---|---|---|---|
| `None` | `PyNone.isNone`, a pointer comparison | `null` | no | re-derived; `null` cannot be told apart from "not computed", and the comparison costs no FFI call |
| `bool` | `PyLong_AsLongLong(...) != 0` | `Boolean` | no — a scalar copy | yes |
| `int` | `PyLong_AsLongLong` | `Long` | no — a scalar copy (64-bit range only; wider ints do not round-trip) | yes |
| `float` | `PyFloat_AsDouble` | `Double` | no — a scalar copy | yes |
| `str` | `PyUnicode_AsUTF8` | `String` | **the C call returns a `const char*` owned by CPython**, but that pointer never leaves the binding: `EmbedAPI`'s `expect` returns `String?`, and each platform decodes at the boundary (`toKString()` on Kotlin/Native, `Panama.readUtf8String` on desktop, `Wasm.readUtf8String` on wasmJs). The `String` is a copy. | yes — the `String`, never the pointer |
| `list`, `tuple` | `toNativeList()`, recursive | `List<Any?>` | no — every element is itself one of the rows above | yes, as a **snapshot** |
| `dict` | `toNativeMap()`, recursive over a snapshot of the entries | `Map<Any?, Any?>` | no | yes, as a **snapshot** |
| `set`, `frozenset` | `toNativeSet()`, recursive over `PySequence_Tuple` | `Set<Any?>` | no | yes, as a **snapshot** |
| anything else *inside a container* | `pyObjectToNative`'s `else`: `str(obj)` | `String` | no — a copy, but lossy and one-way | yes |
| `bytes`, `bytearray` | `bytes.hex()` across the boundary, decoded to bytes on this side | `ByteArray` | yes — a copy, and `bytearray` a **snapshot** | yes |
| `memoryview` | **none — refused** | `null` / `PyTypeError` | the buffer protocol hands out a pointer *into* the object, and a copy would still not carry format, shape and strides | never |
| user-defined classes, subclasses of builtins, `complex`, ... | **none — refused** | `null` / `PyTypeError` | — | never |

The container rows are the answer to "what happens when the elements are themselves `PyObject`s":
they are not. `pyObjectToNative` recurses, so a converted container bottoms out in `Long`,
`Double`, `Boolean`, `String` and `null`, and holds no `PyObject`, no `NativePointer` and no
borrowed C pointer. `PyValueLazyConversionTest` walks a converted nested container and fails on
anything else, so this is checked rather than asserted in prose.

Being a copy is also what makes a container **stale** rather than dangling: mutating the Python
container afterwards does not update it. `PyProxy.invalidateNativeCache()` is the way out, and the
staleness and the recovery are both pinned by tests.

### The rule, per strategy

| `ConversionStrategy` | Hands back | Cacheable |
|---|---|---|
| `RAW` | a bare `NativePointer` — no wrapper, no incref, nothing that releases it | **never.** It is an address, not a reference. `PyValue`'s constructor rejects one as `initialNativeValue`, because storing it would fail only once the address had been reused, far from the cause |
| `UNMANAGED` | the `PyObject` unchanged, under Python's own refcounting | no native value exists to cache |
| `TYPED` / `DEFAULT` | a `PyValue` over a wrapper it owns a reference to | per the type table above |
| `NATIVE` | the fully-converted value, eagerly | nothing holds it; there is no cache in this path |

### How the rule is enforced

`isIndependentOfPythonMemory` in `PyProxy.kt` is the rule as code: `true` for `null`, the Kotlin
scalars, `String`, `ByteArray` and containers built recursively out of those; `false` for a
`NativePointer` (an unowned address) and for a `PyObject` (a reference, but not a native value),
and false for anything unrecognised. Every store to `cachedNativeValue` on the conversion path
passes through it, and a value it rejects is still returned to the caller — it is simply
re-converted next time instead of kept. Today every branch the walk can reach passes; the guard
exists so a branch added later cannot acquire a cache without someone deciding that it should.

### The mirror-image rule, and the bug it caught

The Python side has the opposite rule: `cachedPyObjectValue` must hold an **owned** reference,
never a borrowed one, because the proxy can outlive whatever handed the object over.

`PyContext.proxyConvert` did not honour it. `typedWrap` returns a fresh wrapper — built
`borrowed = true`, i.e. with its own incref — for the nine builtins it knows, and returns *the
caller's wrapper unchanged* for everything else. On that second path the `PyValue` held no
reference of its own, so a caller closing its wrapper (which callers do; `hasNativeCounterpart`
does it to its own scratch wrapper) left the `PyValue` pointing at a released object. Measured as
a missing `+1` on `sys.getrefcount` — 3 where 4 was required — by
`PyValueLazyConversionTest.pyContextGivesEveryPyValueItsOwnReference`, and fixed by taking an
independent reference on the fallback path. Both branches now hand the `PyValue` a wrapper nobody
else holds.

### What this does not cover

- **`bytes` and `bytearray` convert through `hex()`, not through the buffer.** This entry used to
  say they had no conversion at all and that the bindings could not get there. The second half is
  still true: `PyBytes_AsString`/`PyByteArray_AsString` are declared to return `String?`, so each
  platform decodes the buffer as a NUL-terminated UTF-8 string and truncates binary data at the
  first zero byte. They cannot represent arbitrary bytes.

  What changed is that the buffer is not the only route. `bytes.hex()` is exact, pure ASCII, and
  costs three FFI crossings whatever the length, where walking the sequence costs one per byte.
  The price is four passes over the payload where a direct copy would make one, which makes this
  the largest per-byte cost in the conversion layer — the case for adding a real
  `PyBytes_AsStringAndSize` binding later. That binding is not free either: a non-primitive value
  would have to cross four platform boundaries against `androidMain`'s primitives-only rule.
- **Subclasses of builtins are refused** because the dispatch is by exact type. That is
  deliberate and consistent with `pyObjectToNative`, but it means `class MyInt(int)` gets no
  conversion even though `PyLong_AsLongLong` would work on it.
- **The cache is not synchronised.** Two threads converting the same proxy can both convert; the
  result is idempotent, so this costs work rather than correctness.
- **A self-referential container recurses.** `pyObjectToNative` has no visited set, so a `list`
  that contains itself has no defined behaviour here. Not measured; not reached by any current
  caller.
