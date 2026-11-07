# TypedPython region allocation (SPEC N-12b, #268)

Status: first step implemented (#268). Tests: `python-multiplatform-ksp/src/test/python/test_regions.py`.
Parent design: `typedpython.md` §4.3.4. Intent: INTENT §1.4, compiled code computes what CPython computes.

## 1. The problem and the scope the issue settled on

N-12a scalar-replaces function-local objects: one C local per field, so the count of objects is fixed by
the source text. A tree built by recursion (`make(d)`) has a count that depends on the data.

The completion criterion was changed on 2026-10-25: binary-trees as written (`main` is interpreted and
receives `make(d)`) cannot use a region, because the tree escapes to an interpreted caller and real objects
are never placed in a region (on 3.15t every real object is reachable by `gc.get_objects()`). The target
is therefore the case where the whole life of the tree is inside compiled code:

    def tree_sum(d: int) -> int:
        return check(make(d))          # make and check compiled, tree_sum compiled and pure

This design cannot find, and does not claim, a speed-up for an interpreted `main` that holds the tree.
That stays N-11 plus whatever N-12 refcount work can prove.

## 2. Why a region object is unobservable

A region object is a C struct in malloc'd chunks. No `PyObject` header exists, so it is not in any GC
generation, has no `id`, no refcount, no `__class__` that any code can ask. The only references are C
pointers on the host's stack and inside other region structs. For it to be observable, some code must
(a) obtain it, or (b) enumerate objects.

- (a) is excluded by verifier facts (section 3): the pointer never reaches a return, a global, a container,
  a real object's field, a call to unknown code, `id`/`hash`/`repr`/`is`.
- (b) `gc.get_objects`, `gc.get_referrers`, `tracemalloc` and other threads can only run during the span
  if the host calls unknown code or another thread runs. The host contains no unknown code (pure), and
  other threads cannot see anything (no PyObject), so they observe the world before the first node or after
  the last free, both valid CPython interleavings. This is the same argument as N-12a, applied to a span
  with a loop or recursion in it. It is why N-12b is restricted to pure hosts: an impure host would have
  to materialise the whole tree at every unknown call (the N-12 rule), and a tree of 2^20 nodes
  materialised for a `print` is a cost and an order-of-allocation question this step does not take on.

## 3. Verifier facts (recomputed, never trusted)

Names as implemented: `Function.regions` (the producers a host opens a region for), `Module.region_variants`
(the producers and consumers with an internal entry), `ClassDecl.has_del`, `verify._REGION_OK`,
`verify._RegionScan` (the escape check), rule `verify/region-escape`. The front end sets none of them
(`has_del` aside, which is a fact about the source); `verify` recomputes the rest and rejects a function
whose declared `regions` differ, and reports a module whose declared `region_variants` differ.

1. **Region class** C: N-11 class, no `__del__` (`ClassDecl.has_del`), no `__weakref__` slot (the exact
   `__slots__` rule already excludes it), no `FieldSet` on C anywhere in the
   project (the compiled project is closed-world in compiled mode, N-11 gate), and every `New(C, args)`
   argument is `Box` of a scalar, a `None` Const, or an expression of a region class. Stored values are
   therefore scalars or other region pointers: no refcount, nothing to run on free.
   Per-field kind is the join over all `New` sites: `i64`, `f64`, `bool`, or a pointer to one class (NULL is
   `None`). A field that is `None` at one site and `i64` at another is not a region class (no tagged unions
   in step 1), nor is a pointer field that points to two classes (a pointer has no class tag).
2. **Producer** P: pure function, return type a region class, node set within `_REGION_OK`, every `Return`
   is a `New` of a region class or a `Call` to a producer (itself: `make` is self-recursive).
3. **Consumer** K: pure function with a non-optional region-class parameter `n`, scalar return, `n` used only
   as the object of `FieldGet`, as the operand of `Is None`, or as an argument at a consumer's region
   parameter; `FieldGet` results of pointer kind are locals used under the same restriction. Reading a scalar
   field (`Unbox(FieldGet)`) yields the same value the unboxed path would. The front end cannot express that
   read today (`c.val` lowers to an object and `v: int = c.val` is refused), so scalar fields are covered
   by hand-built IR in `test_regions.py`; classes with pointer fields are covered end to end.
4. **Host** H: pure function, node set `_REGION_OK`, and in some statement a producer `Call` result is an
   argument at a consumer's region parameter, with nothing else holding it. The statement's value is a
   scalar (the region cannot be returned by construction). A region scope = that statement.
5. **Escape** is whatever fails 1-4: `Return` of the value, `Assign` to a global/field/container, any
   `CallObject`/`GetAttr`/`Global` read, `Is` between two nodes (`make(d) is make(d)`), `id`. Anything
   unproved compiles as N-11.

`_REGION_OK` = `_VIRTUAL_OK` plus `Call`; `_RegionScan` narrows it (no array node, no OBJ value outside the
three shapes above, no `Is` except against `None`). A `Call` is allowed here (it was
not in N-12a) because it is a pure compiled call: no user code runs. A pure callee can deopt, which is
handled in section 6.

The verifier computes producers, consumers and hosts together as a fixpoint over the module: a function is
a producer only if every `Call` in its body is to a producer or consumer or a plain pure function.

## 4. Mechanism: two entry points, region as a hidden parameter of the internal one only

Options considered:

| Option | Verdict |
|---|---|
| One entry per function, region as a hidden parameter | No. The exported entry must keep its Python signature, and an interpreted caller (`main`) calls it. A hidden parameter would need a NULL-means-real-objects mode on every `New` and every field read. |
| **Two entries**: the Python-visible one unchanged (real objects, N-11), and an internal `make__r(region*, depth)` / `check__r(node*)` with no Python-visible name | **Chosen.** The internal entry cannot be reached from Python, so a region pointer can never leak. Code size doubles for producers and consumers only. |
| Only when every call site is a compiled caller that keeps the result inside | Not needed: the two-entry scheme keeps correctness for all call sites, the region is used only at the call sites the host fact proves. |

A producer's `New(C, args)` in the region variant becomes `tp_region_alloc(r, sizeof(C_r))` plus field
stores. The class guard of `New` is kept (a changed class is a deopt, section 6). A consumer's `FieldGet`
becomes a struct member load: scalar kinds are loaded unboxed (`Unbox` of the `Box` the front end put there
folds away), `REF` kinds give a pointer. `Is None` is a NULL test.

Struct layout (as implemented): fields in declaration order, one 8-byte slot per field (a union of `i64`,
`f64`, pointer; `bool` is stored in the integer member), no header, so `Node` is 16 bytes. Chunks: the first is
4 KiB, each next one doubles up to 8 MiB; a bump pointer. A region is a C stack value of the host, one per
site (`tp_rgK`): `tp_region_open` right before the producer runs (reuses the chunks the previous execution of
the site grew), `tp_region_end` right after the consumer returned (the statement is over, the value is a
scalar), and `tp_region_free` in the host's common exit block, which every exit edge (return, deopt, error)
reaches. In a loop the chunks are therefore grown once and reused by every iteration. A bounded host (one
with a fast twin) uses real objects in the twin; the verifier's facts do not depend on that.

## 5. Lifetime and loops

The region belongs to a statement, not to the function. In `for _ in range(n): t += check(make(d))` the
region is freed (chunks kept for reuse, bump pointer reset) at the end of each statement, so memory is
bounded by one tree. The local form `t = make(d)` assigned once and read by consumers only is the same
fact over a longer span and is deferred to a second step: it adds "no read after reassignment" and
"freed at last use or function exit".

## 6. Deopt, errors, free-threading, tracing

- **Deopt in a pure host.** The existing contract: a pure function that deopts redoes the call interpreted
  from its arguments, because it has had no effect. A region object was never observable, so nothing has to
  be materialised: the host frees the region and returns the deopt signal. A deopt in a producer/consumer
  variant (int overflow in `check`, a changed class in a `New`, an argument guard) propagates exactly as a
  pure callee's deopt does today.
- **MemoryError.** `tp_region_alloc` fails when `malloc` of a chunk fails: this is a deopt, not a raise.
  The redo runs the interpreter, which raises `MemoryError` where CPython's own allocation fails, so the
  exception, its traceback and its timing come from the real thing. Known differences: a region node
  (about 16 bytes for `Node`) is smaller than a PyObject node (about 56), so a call may succeed where
  CPython would fail; and `tracemalloc` peak differs. Same nature as N-12a. A test hook
  (`__typedpython_region_fail_after__(n)`) fails the nth node allocation once to test the path; counters are
  `__typedpython_region_stats__()` = (opened, freed, objects) and `__typedpython_region_chunks__()` =
  (allocated, freed) chunks.
- **Recursion.** `make`/`check` recursion depth is the same as for compiled recursion today
  (`test_recursion.py`); a region does not change the C stack use of a frame by more than one pointer.
- **Free-threading (3.15t).** Region memory is reachable only from one thread's C stack. No refcount, no
  critical section, no atomic. The "no real object is thread-local" paragraph of N-12 does not apply
  because there is no real object. The counters used by tests are the only shared state (relaxed atomics).
  Tests: four threads call the host while a fifth scans `gc.get_objects()` for `Node`; it must find none.
- **N-14 tracing.** A traced thread's call of the host enters the interpreted function, so every event
  comes from CPython's frames. The internal region entries have no Python frame and are only reachable from
  a compiled pure host, which N-14 already says does not re-check on internal calls. If tracing starts
  while a host runs (only possible from another thread's `sys.monitoring` global event), behaviour is the
  documented N-14 known difference.

## 7. Which differential observations apply

| Observation | Applies? | Why / test |
|---|---|---|
| `id(x)`, default `hash`, `repr` | No: no region object can be passed to them; a host using them is not a region | negatives `ident`, `same` + mutation test |
| `is` between region nodes | No: `Is` of two producer results is outside the facts | `same` |
| `gc.get_objects` count/order, `gc.get_referrers` | Yes | count unchanged after calls; a scanner thread never finds a `Node` |
| `sys.getrefcount` | Trivially: no object reference is stored, so no counted reference changes | no field holds an object reference by fact 1 |
| `tracemalloc` current/peak | current: yes (no leak). peak: known difference | test pins the difference (peak far below the interpreter's) |
| `MemoryError` during construction | Yes | injected failure equals the interpreter |
| Results, exceptions | Yes | every depth equals the interpreter, deopt cases |
| Timing of cyclic GC | Outside the identity (user decision 2026-10-04) | not tested |

## 8. First implementation step (done)

Exactly the shape of `test_regions.py`: `tree_sum(d) = check(make(d))` and the loop form, `make`/`check` as
module-level compiled functions of one region class (`Node` with two pointer fields). Done: verifier facts
and `Function.regions` / `Module.region_variants`; cgen variants (`tp_rimpl_<name>`, no wrapper, no Python
name); runtime `tp_region_*`; counters, the failure hook, the deopt, error and thread tests; a benchmark
variant (`benchmarks/typedpython/py/binary_trees_region.py`). Not done: the native-image check (rule 13: the
region runtime is plain C, no reflection, but the image build has not been run for this change).
Out of scope for step 1: local-variable form, consumers returning region pointers, mixed field kinds,
methods (`n.check()`), impure hosts, region classes with object-typed fields, `FieldSet`.

## 9. What this audit cannot find

The criterion "can the tree be proved unobservable inside one pure compiled statement" cannot find
speed-ups for trees that cross an interpreted boundary (binary-trees `main`), for impure hosts, or for
classes with object-typed fields. Those are real losses, not an absence of work; see the open questions.

## 10. Decisions (maintainer, 2026-10-25)

1. **Only pure functions host a region.** An impure host (it prints, or calls Kotlin) is not a region host;
   no materialisation of a region is specified.
2. **A failed region allocation deopts and the interpreter's redo raises `MemoryError`.** That a region call
   can succeed where CPython would fail (the nodes are smaller) is a known difference, recorded in SPEC
   N-12b next to N-12a's.
3. **Fields are scalars, `None` and region pointers only.** Object-typed fields (strings, ints beyond i64)
   would be a separate issue later.
4. **`tracemalloc` peak and `sys.getallocatedblocks` differences during a call are a known difference,**
   recorded in SPEC N-12b.
5. **`benchmarks/typedpython/py/binary_trees.py` stays unchanged.** A separate file,
   `benchmarks/typedpython/py/binary_trees_region.py`, has a compiled `check_made(d) = check(make(d))` helper
   that the main loop calls. `run.py` takes its benchmarks from the hard-coded `BENCHMARKS` table, not from
   a directory listing, so the extra file changes no output of `run.py verify` or `measure` until someone
   registers it; no CI workflow or test reads the directory (`test_frontend*.py` name their files).
