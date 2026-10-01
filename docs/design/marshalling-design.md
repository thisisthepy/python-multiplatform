# String marshalling across the FFI boundary

## The problem, measured

Every composed-versus-per-call result in this project turned out to be about string
marshalling rather than crossing count. Two observations pin that down:

- `Python3.exec`'s per-call cost is **identical on API 26 and API 36** (17798 vs 17799 ns) on
  devices whose per-crossing cost differs by 20x.
- 7.7 µs spread over ~11 crossings would be 700 ns each, two orders of magnitude above the ~7 ns
  a crossing actually costs.

The cost is the round trip that produces a C string. Today Android does it the expensive way:

    ffiAllocUtf8(s)   JNI crossing → GetStringUTFChars → malloc → copy → return address
    ffiFreeUtf8(p)    JNI crossing → free

Two crossings and a heap allocation **per string per call**. Desktop pays ~200 ns per string
where Android pays ~2500 ns, and the whole difference is that Panama allocates off-heap from
inside the JVM and never crosses.

## What was measured

`getAttr`, same device, four ways:

| ns | API 36 | API 26 |
|---|---|---|
| was current (`ffiAllocUtf8` per call) | 2238.25 | 2488.62 |
| composed natively (`asmGetAttr`) | 528.85 | 255.19 |
| direct buffer + `toByteArray` | 694.03 | 800.96 |
| direct buffer + ASCII fast path | 373.18 | 248.94 |
| **interned C string** | **160.25** | **167.33** |

Interning wins on both levels and wins *uniformly* — 160 against 167 — where composition
differs by 2x between the same two devices. It is 3.3x better than composing on API 36 and
1.5x better on API 26.

Attribute names, module names and method names are repeated literals, so encoding them again
on every call is pure waste. CPython interns its own strings for the same reason.

### Status: adopted on Android, not on desktop

The first row is labelled "was current" because it no longer describes Android. `internedUtf8`
and `encodeScratchUtf8` are implemented on both JVM platforms, and Android's call path uses them
— `EmbedAPI.android.kt`'s `PyObject_GetAttrString` goes through `internedUtf8`, so the live cost
is the bottom row, not the top one. Quoting 2238 ns as Android's current `getAttr` cost is wrong,
and it has been quoted that way.

**Desktop has the primitives and does not use them.** `bindings.kt` has 59 `withUtf8` call sites
and zero `internedUtf8` ones, so every desktop call still allocates and frees a C string. That
went unnoticed because desktop was already at ~150 ns per string — Panama allocates off-heap
without crossing a boundary, which is the whole reason Android needed interning to catch up
rather than the other way round. Wiring it on desktop would take the repeated names down to a
map lookup plus a 2.65 ns crossing.

This is cheaper to adopt than it looks and cheaper than composition, which ROADMAP §6 closed on
desktop: the primitives already exist and only the call sites change.

**Do not swap all 59 blindly.** The two primitives exist for different lifetimes:

| | for | lifetime |
|---|---|---|
| `internedUtf8` | repeated identifiers — attribute, module, method names | kept, bounded at 4096 entries |
| `encodeScratchUtf8` | arbitrary content — `exec` source, user strings | thread-local scratch, freed on the next call |

Interning an `exec` source string would blow the cache. Android's actual interns only where the
argument is a name, which is the rule to follow.

## The design

Centre the abstraction on **interning**, not on a general allocator.

    // jvmMain — shared by Android and desktop
    internal expect fun internedUtf8(s: String): Long
    internal expect fun encodeScratchUtf8(s: String): Long
    internal expect fun freeUtf8(address: Long)

`internedUtf8` returns a stable address that stays valid for the process. Callers never free
it. On a miss it encodes once and caches.

`encodeScratchUtf8` writes into a per-thread scratch buffer and returns its address. Nothing to
free.

**The scratch pool holds four slots per thread**, handed out round-robin, on both desktop and
Android. One slot would have been enough for the "encode, then immediately pass to C" pattern,
but not for a call that needs two arbitrary strings at once —
`PyImport_ExecCodeModuleWithPathnames` needs two, `PyUnicode_DecodeLocale` needs two. Four
leaves headroom without pretending the addresses last. The fifth consecutive encode on a thread
invalidates the first, and `StringMarshallingTest.testMultiSlotScratchReuseLimit` pins exactly
that boundary, so it is a tested guarantee rather than an implementation detail.

`freeUtf8` exists for the cases that genuinely need an independent lifetime.

### Which argument takes which path

**The judgement is per argument, not per function.** `Py_CompileString(source, filename)` interns
`filename` and scratches `source`; they are arguments to the same call. Desktop and Android make
the same split for the same argument, and `desktopMain/.../bindings.kt` is the reference.

| interned — the argument is a name | scratched — the argument is content |
|---|---|
| attribute names (`PyObject_*AttrString*`) | source text (`Py_CompileString.str`, `PyRun_*`) |
| dict / mapping keys (`PyDict_*ItemString`, `PyMapping_*String`) | exception and warning messages |
| module names (`PyImport_*`, `PySys_*`, `PyErr_NewException.name`) | file paths a module was loaded *from* (`pathname`, `cpathname`) |
| codec names (`encoding`) | error-handler names (`errors`), comparands, docstrings, `PySequence_Fast.m` |
| `PyUnicode_InternFromString.str` — the caller is asserting it repeats | `PyBytes_FromString.v` — payload |

Two cases are worth stating because they read the wrong way at a glance:

- `PyErr_WarnExplicit`'s `filename` and `module` **intern** even though its `message` does not:
  CPython uses those two as keys in the once-registry that decides whether a warning has already
  been shown, so they repeat by construction.
- `PyErr_SyntaxLocation`'s `filename` **scratches**, unlike the `filename` above. It labels one
  error being reported, not a registry key.

No migrated function holds more than two scratch encodes live at once, so none of them can reach
the four-slot boundary.

### Cache policy

Interning without a bound is a leak, because callers can pass arbitrary strings — an attribute
name built at runtime, a code fragment. The cache therefore:

- holds a bounded number of entries; beyond that, misses take the scratch path instead of
  evicting, since evicting would invalidate an address a caller may still hold
- is keyed by the string itself, so equal strings share one C buffer
- never frees an interned entry, which is what makes the returned address safe to hold

The bound turns "unbounded growth" into "predictable ceiling, then fall back to scratch". A
literal-heavy workload — which is the normal case — stays entirely in the cache.

### ASCII fast path

`String.toByteArray(UTF_8)` allocates a `byte[]` on every call, and API 26's ART is markedly bad
at it: 800.96 ns against 248.94 ns for writing chars straight into the buffer. Python
identifiers are ASCII in practice, so the scratch encoder should take that path and fall back to
full UTF-8 only when it sees a character above 0x7F.

### Platform implementations

| | allocation | address |
|---|---|---|
| desktop | Panama, via the existing `Panama` allocator | already a `long` |
| android | `ByteBuffer.allocateDirect` | `GetDirectBufferAddress`, **once per buffer** |

Android's one JNI call per buffer is amortised to nothing; what matters is that no crossing
happens per *string*. That is the same shape Panama has on desktop, and the same shape
PanamaPort would have provided — its `allocateFrom(String)` runs through
`AndroidUnsafe.allocateMemory` to `sun.misc.Unsafe`, a JVM intrinsic with no crossing.

### What this replaces

Composition for string-carrying operations, at every API level. `getAttr` composed costs
528.85 ns on API 36; interned costs 160.25 ns, and needs no `artMain` export, no per-operation
native function, and no JVM-side wiring. It also applies to every string-carrying operation at
once rather than one at a time.

Composition remains justified only where crossing count genuinely scales with N — bulk
iteration, measured at 11x for a 1000-element list.

## Non-goals

- A general `MemorySegment`/`Arena` API. Non-string buffers still need allocation, but that is a
  smaller surface and not what the measurements point at.
- Vendoring PanamaPort. Its memory model is the part that mattered here, and this obtains the
  same result in a few dozen lines without ART-internal offset tables or GPL vendoring.
