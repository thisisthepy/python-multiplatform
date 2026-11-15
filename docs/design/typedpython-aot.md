# TypedPython AOT mode: a standalone build with no interpreter

Design note for SPEC N-17 (#443). **No code exists for this mode.**

Provenance. What the owner decided (INTENT §1.4, 2026-11-16: "너가 제시한 AoT모드 좋은거 같아. 도입하도록
해."): the mode is opt-in per build; when the whole program passes the verifier it is emitted as a
standalone binary that does not ship the interpreter; code the verifier cannot prove is a compile error;
the aim is binary size and baseline memory near Rust's; the default mode is unchanged; an accepted
program must compute what CPython computes for it. **Everything else in this note is an agent proposal,
not owner-confirmed.** Choices that are the owner's are collected in section 9 as options and are not
settled by being written here.

Facts about today's compiler come from a read-only inventory of develop at e48f33de (front end,
verifier, cgen, `runtime/tp_runtime.h`, SPEC N-1 to N-17, the benchmarks). Nothing in this note was
measured.

## 1. Where the default mode stands, and what AOT has to replace

| Area | Default mode today | Consequence for AOT |
|---|---|---|
| Program shape | Only module-level functions and classes marked `@compiled` are compiled. Module top-level code, and `main` in four of the five benchmarks, run interpreted | AOT must compile the whole program, including module bodies and the entry point |
| Scalars | `int` as checked i64, `float`, `bool` are native | Reused as they are |
| Unbounded ints | A pure function deopts on overflow; an impure one promotes to a CPython `PyLong` (N-13). There is no bignum of our own | Needs an answer with no CPython (section 5, decision D1) |
| Lists of numbers | `list[float]` / `list[int]` parameters are copied in and out of real Python lists, with guards for element types, aliasing and concurrent change | In a closed, typed program the guards have nothing to guard against (section 5) |
| Other containers | `dict`, `str`, general lists and tuples are generic C-API object operations | Need native representations (section 4) |
| Classes | A compiled class instance is a real CPython object with `__slots__`. Only N-12a virtual objects and N-12b regions avoid a `PyObject` | Needs a layout of our own (section 6) |
| Exceptions | Compiled code cannot `raise` or `try`. Runtime operations raise CPython's exceptions with CPython's messages | Needs an exception mechanism with no CPython (section 4.3) |
| Builtins | Native: `len` of an array, `float`, `range` in a `for`, nine `math` functions. `int`, `abs`, `min`, `max`, `print`, string methods are Python object calls | Each one the subset keeps needs a native implementation |
| Threads | `parallel=True` loops run on bare pthreads (Win32 threads on Windows) that touch no Python object | The workers are already CPython-free. The list copy-in around them is not |
| Runtime | `tp_runtime.h` and the generated C use about 300 distinct CPython API names: reference counts, exceptions, module and type setup, dicts | Regions (plain `malloc` chunks), checked arithmetic, local arrays and the parallel-loop workers do not depend on CPython and carry over |
| Measurement | `benchmarks/typedpython/run.py` measures wall time only | Binary size and RSS measurement must be added (section 8) |

Two things follow. First, AOT is not "the default mode minus the interpreter": most of what makes a
whole program run (entry point, printing, strings, containers, exceptions) is interpreted today.
Second, the closed world removes work: most run-time guards of the default mode exist because unknown
Python code can call a compiled function with anything, or change a class or a global behind its back.

## 2. Principles

1. **Meaning is CPython's.** For every accepted program, standard output, exit status and the values
   computed equal what CPython 3.15t produces. The mode narrows which programs are accepted, never what
   an accepted program means (INTENT §1.4). Anything that would need a different meaning is a compile
   error, not an approximation.
2. **Closed world.** The build sees every module of the program. There is no `eval`, `exec`, `import`
   by name at run time, monkey-patching, tracing or debugger. A property that the default mode guards
   at run time is either proved from the whole program or the construct is a compile error.
3. **The compiler is the type authority.** CPython does not enforce annotations, so AOT cannot trust
   them. Every value's type is established by construction (a literal, a constructor, the result type
   of a checked operation, a runtime function with a fixed result type), and an annotation that
   disagrees is a compile error. A program that passes computes the same thing under CPython, where the
   annotations are simply never checked.
4. **Pay only for what the program uses.** The runtime is a set of small pieces (bignum, strings, dict,
   cycle collection, threads) and a piece is linked only when the program needs it. A program of floats
   and arrays links none of them.
5. **One front end, one verifier.** AOT reuses the default mode's front end, IR and verifier. A
   function the default mode would leave interpreted is, in AOT, an error with the same reason text and
   location. This also gives the default mode an "AOT readiness" report for free.
6. **Differential tests decide.** Every accepted test program runs under CPython 3.15t and as an AOT
   binary; standard output and exit status must be identical. Runtime pieces that reimplement CPython
   behaviour (float formatting, bignum arithmetic, string operations, dict order) are also compared
   against CPython on generated inputs.

## 3. Program model

- **Input.** An entry module and the modules it imports, all inside the project. `import` of anything
  else is accepted only for the provided standard-library modules of section 4.5.
- **Module bodies.** Each module body compiles to an initialiser run once, in import order, before the
  entry module's body. `if __name__ == "__main__":` is resolved at compile time.
- **Globals.** A module global is a static slot with one type for the whole program. A global assigned
  values of two types, or assigned from outside its module, is a compile error at first. The dict
  watcher and the epoch of the default mode disappear: there is no module dict.
- **Functions.** Positional parameters with annotations, as today. Default values, keyword arguments
  and `*args` can be added later because every call site is visible; they are not needed for a first
  version.
- **Marking.** Whether every function must still carry `@compiled` is decision D6.
- **Output.** One C translation unit plus the runtime pieces in use, compiled by the same C compiler
  the default mode uses, linked with section garbage collection so unused runtime code is dropped.
  Whether a C-ABI library for a Kotlin host is also an output is decision D5.

## 4. The accepted subset (N-17 item 1)

Proposed in tiers, so that each tier is a complete, testable mode and the measurement of section 8 can
start early. Tier names are for this note only.

### 4.1 Tier K, the kernel (what is native today, made whole-program)

- Types: `int`, `float`, `bool`, `None` as a return, `list[float]` / `list[int]` of fixed length made by
  `[v] * n`, `list(range(n))`, `a[:]` and by a list display of scalars; fixed-shape tuples as return
  values and their unpacking at the call.
- Control flow: what the front end lowers today (`if`, `while`, `for ... in range`, `break`,
  `continue`, `return`), plus the conditional expression.
- Calls: compiled to compiled, recursion; `len`, `range`, `float`, `int` of a float or a string,
  `abs`, `min`, `max` of scalars; `math` (section 4.5).
- Output and arguments: `print` of scalars and strings, f-strings and `format` with constant format
  specs, `sys.argv`, `sys.exit`.
- Strings in this tier: literals, the results of formatting, and elements of `sys.argv`. No string
  operations beyond passing them to `print`, `int`, `float` and `len`.
- Enough for: nbody, spectral-norm and fannkuch as whole programs. Today their `main` and nbody's
  list-building helpers are interpreted ("list display is not supported", a list local that is not one
  of the three creation forms).

### 4.2 Tier O, objects

- Classes as in N-11: `@compiled` shape rules (fixed fields, no metaclass, no dynamic attribute
  hooks), any `__init__` whose body is in the subset, instance methods. Fields are typed and stored
  unboxed (section 6).
- `C | None` fields and locals, `is None`, `is` between instances of classes.
- Not accepted: `id()`, the default identity `hash()`, `weakref`, `__dict__`, `getattr` / `setattr`
  with a non-constant name, class attributes assigned after the class body, `is` between ints, floats
  or strings (its result depends on CPython's caching).
- Inheritance and method overriding are not needed for the benchmarks. A single-inheritance version
  with a class id for dispatch is a later step and is required by user exception classes (4.3).
- `__del__`: accepted only on classes the compiler proves acyclic, where reference counting runs it at
  the same point CPython does (section 6.3). Otherwise a compile error.
- Enough for: binary-trees.

### 4.3 Tier S, strings, general containers and exceptions

- `str`: immutable, indexed by code point, with the operations the provided set names (`+`, `==`,
  ordering, `len`, indexing, slicing, `in`, and a fixed list of methods: `split`, `join`, `strip`,
  `lower`, `upper`, `startswith`, `endswith`, `find`, `replace`, `isalpha`, `isdigit`, `encode` to
  UTF-8). Each method is differential-tested against CPython; a method outside the list is a compile
  error.
- `list[T]` that grows (`append`, `pop`, `extend`, `insert`, slicing, `sort` of scalars and strings,
  `in`, iteration), `dict[K, V]` with `K` one of `int`, `str`, `bool`, fixed tuples of those
  (insertion ordered, as CPython), `tuple` values of fixed shape, `bytes` as immutable byte arrays.
- `set[K]`: membership, `add`, `discard`, `len`. Iterating a set is a compile error unless it is
  through `sorted(...)`, because the order CPython gives depends on its hash table and, for strings, on
  the per-process hash seed.
- Comprehensions over the above, `enumerate`, `zip`, `sorted`, `sum`, `any`, `all`, `reversed`,
  `ord`, `chr`, `str`, `repr` of scalars and strings.
- Exceptions: `raise` of a built-in exception class or a user subclass of `Exception` with a message,
  `try` / `except` (by class, with `as e`), `else`, `finally`, `assert`. Runtime operations raise
  exactly the class and message CPython raises (already the case for the default mode's native
  operations). An uncaught exception ends the program with CPython's exit status and the last line
  CPython prints, `ClassName: message`. The traceback lines above it are not reproduced (decision D4).
- Not accepted in any tier of this note: generators and `yield`, `async`, closures that outlive their
  defining call, `lambda` stored in data, decorators other than `@compiled`, `with` on user context
  managers, `match`, `global` rebinding from another module, numpy and `array.array` buffers (they are
  CPython objects), Kotlin upcalls (decision D5).
- Enough for: wordfreq.

### 4.4 Tier T, threads

Section 7.

### 4.5 Provided standard library

Principle: a provided module is written in the accepted subset itself wherever possible, so it goes
through the same verifier and the same differential tests; only what must touch the operating system
or libm is C.

| Module | Provided | Notes |
|---|---|---|
| builtins | the functions named in 4.1 to 4.3 | everything else is a compile error naming the builtin |
| `math` | `sqrt exp log sin cos tan fabs atan2 hypot` (native today), plus `floor ceil trunc pow log2 log10 asin acos atan sinh cosh tanh isnan isinf isfinite copysign fmod`, `pi e inf nan tau` | CPython calls the platform libm for these, so linking the same libm gives the same bits on the same platform. `fsum`, `gcd`, `factorial`, `comb` later (integer ones need D1) |
| `sys` | `argv`, `exit`, `stdout.write`, `stderr.write`, `maxsize` | no `settrace`, `setrecursionlimit`, `modules`, `getsizeof` |
| `time` | `perf_counter`, `monotonic`, `time`, `sleep` | |
| `threading` | tier T | |

The first list to agree on is decision D8.

## 5. Guards and overflow with no interpreter (N-17 item 3)

Every reason the default mode leaves compiled code, and what AOT does instead. "Gone" means the
condition cannot occur in a closed, typed program, so no check is emitted.

| Default-mode guard or deopt | AOT |
|---|---|
| Wrong arity, keywords, argument of another type or an `int` outside i64 at a wrapper | Gone. There are no Python callers; every call site is typed by the compiler. Program input enters through runtime functions (`sys.argv`, `int(s)`) with one result type and CPython's own errors (`ValueError: invalid literal for int() ...`) |
| List element of another type at copy-in or at an element read; `Unbox` of an object | Gone. A `list[float]` is a native array for its whole life; no store of another type compiles |
| Two list arguments are the same object | Not a deopt any more: with no copy-in, aliased lists are simply the same array, as in CPython |
| Class version tag, class global rebound, method descriptor changed | Gone when the verifier proves, program-wide, that no code assigns a class attribute, rebinds a class name or uses a dynamic attribute hook. A program that does is a compile error naming that statement |
| Entry global missing, of another type, or changed during the call (epoch) | Gone. Globals are typed static slots (section 3), read where the interpreter reads them |
| Tracing, profiling, monitoring active (N-14) | Gone. No `sys.settrace` or `sys.monitoring` in the provided `sys` |
| OSR-style and statement-start exits (N-8) | Gone. They exist to hand a function over to the interpreter when a guard fails after an effect; none of those guards remain |
| numpy non-finite results, buffer shape guards | Not applicable: numpy and buffer objects are outside the subset |
| Region allocation failure | Raises `MemoryError` (decision D4 covers resource limits) |
| i64 overflow of `+ - * // << `, negation | See below (decision D1) |
| Index out of range, division by zero, math domain errors, unset slot, unbound local | Not deopts today either: they raise CPython's exception with CPython's message. Unchanged; a check the verifier proves away is not emitted, as today |
| Recursion deeper than the recursion limit | Decision D4 |

**Integer overflow.** Python ints are unbounded, so an i64 operation that overflows has a defined
result in CPython. The front end's interval analysis already proves many operations inside i64 and the
verifier re-proves them. For the rest there are three possible rules:

- (a) An unproven operation is a compile error. Exact, smallest runtime, but rejects ordinary code: a
  counter in a `while` loop or a sum of input values is not provable.
- (b) An unproven `int` is a small-int / big-int value: 63 bits inline, a pointer to a magnitude array
  otherwise, with our own add, subtract, multiply, floor-divide, modulo, power, shifts, comparison and
  decimal conversion. Exact for every program; the bignum code is linked only when some operation is
  unproven. This is N-13 with CPython's `PyLong` replaced.
- (c) An unproven operation that overflows ends the program with an error. Small and fast, but it gives
  an accepted program a different meaning than CPython, which the owner's rule excludes.

Proposal: (b), with (a) available as a build option for programs that want a guarantee of no bignum
code. This is decision D1.

## 6. Objects and memory (N-17 items 2 and 4)

### 6.1 What can be observed

In CPython a program can observe an object's layout or lifetime only through: `id()`, the identity
`hash()`, `is` on values CPython caches, `sys.getsizeof`, `__dict__` / `__slots__` reflection, `gc`,
`weakref`, `__del__`, and running out of memory. Section 4 excludes all of them except `__del__` on
acyclic classes and memory exhaustion. So in an accepted program **nothing can observe where an object
lives, how large it is, or when it is freed**, with those two exceptions, and the compiler is free to
choose.

### 6.2 Layout

- No `PyObject` header. An instance is its fields in declaration order: `float` 8 bytes, `bool` 1 byte,
  an `int` field 8 bytes (native when its range is proved, the tagged small/big value of D1 otherwise),
  a reference 8 bytes, `C | None` as a nullable pointer.
- A reference count word precedes the fields only for objects that are reference counted (6.3). An
  object in a region or a virtual object has none.
- A class id word is present only for classes that take part in dynamic dispatch or `isinstance`
  (inheritance, exception classes).
- Inside a region the N-12c compact layout applies: links may be 1, 2 or 4 byte offsets checked against
  the region's extent.
- Strings: length, a kind (1, 2 or 4 bytes per code point, as CPython's flexible representation, so
  indexing is O(1) and matches CPython's code-point semantics), the characters. Literals live in
  read-only data with no count.
- Lists: pointer, length, capacity, with unboxed elements for `float`, `int` (when proved) and `bool`.
  Dicts: an insertion-ordered entry array plus an index table, the structure CPython uses, so iteration
  order is identical by construction.

### 6.3 Reclaiming memory

Three mechanisms, tried in this order for each allocation site:

1. **No object.** N-12a virtual objects: fields in C locals.
2. **Region.** N-12b and N-12c: the object lives in a bump region owned by a scope and is freed with
   it. In AOT the "nothing can observe" conditions of N-12b hold far more often, because the unknown
   calls, real-object fields and globals that end a region in the default mode are now typed code the
   escape analysis can follow.
3. **Reference counting**, with counts elided where ownership is static (a value consumed exactly once
   moves; a borrowed parameter is not counted). Non-atomic when the object is proved local to one
   thread, atomic otherwise (section 7).

Cycles. Reference counting does not free a cycle. A class whose instances are never mutated after
construction cannot be part of one (binary-trees' `Node` is such a class), and neither can a class
whose field types cannot reach the class again. For the remaining classes the choice is between a
small cycle collector linked only when such a class exists, and a compile error. This is decision D2,
together with the alternative of a tracing garbage collector for everything.

`__del__` on an acyclic class runs when the count reaches zero, the same point as in CPython, because
CPython also frees such objects by reference count. That equivalence does not hold for objects in
regions or for elided counts, so a class with `__del__` is always reference counted with exact counts.

### 6.4 Exceptions

A function returns a status, as the generated C does today; the current exception (class id, message
string, optional fields) is a thread-local record. `try` is a test of the status and a jump. No unwind
tables are needed, which keeps the binary small and works the same on every target including wasm.

## 7. Threads on the free-threaded model

What CPython 3.15t guarantees and AOT must also give: a data race never corrupts memory or the
interpreter; each operation on a built-in container is atomic with respect to other operations on it;
which interleaving happens is unspecified. AOT must produce only results some CPython interleaving can
produce.

- **Parallel loops** (`@compiled(parallel=True)`, N-15) carry over unchanged: the workers already use
  only C and the platform's threads. With native lists there is no copy-in around the region.
- **`threading`.** `Thread(target=f, args=...)`, `start`, `join`, `Lock`, later `Event` and `Queue`.
  A thread-escape analysis marks each allocation site as thread-local or shared: an object is shared
  when it can be reached from a `Thread` argument, from a global that more than one thread function
  touches, or from another shared object.
  - Thread-local objects: plain operations, non-atomic counts.
  - Shared objects: atomic counts; each mutating container operation takes a lock inside the container
    (the role of CPython's per-object critical section); field and global reads and writes are atomic
    loads and stores, so a racing reader sees the old or the new value, never a torn one.
- **Signals.** With no eval breaker there is no point at which CPython-style signal handlers run.
  Proposal: `signal` is not provided; Ctrl-C ends the process with the same exit status CPython gives
  for an uncaught `KeyboardInterrupt` (death by SIGINT), without the traceback text; a program that
  catches `KeyboardInterrupt` is a compile error. Part of decision D4.

Whether `threading` is in the first version or parallel loops alone is decision D7.

## 8. Measurement plan

**What is measured** (N-17): binary size, baseline and peak RSS, CPU time, thread scaling. Each beside
the best safe Rust and beside the default mode.

| Metric | How | Notes |
|---|---|---|
| Binary size | size of the stripped executable; also unstripped, and the list of runtime pieces linked | Rust: `--release`, LTO, stripped, as `benchmarks/typedpython/rust/Cargo.toml` builds today. Default mode: the compiled extension plus the CPython runtime and standard library the app ships |
| Baseline RSS | peak RSS of an empty program and of `print("hello")` | this is the number the owner's aim names; the design note for the default mode quotes about 7.6 MB for an empty interpreter and 1.4 MB for an empty Rust program (an earlier investigation, not re-measured here) |
| Peak RSS | maximum resident set of each benchmark run (`getrusage` of the child, or `/usr/bin/time -l` / `-v`) | same input sizes as the time runs |
| CPU time | user plus system time and wall time, median of interleaved runs, as `run.py interleaved` does for wall time today | |
| Thread scaling | speedup at 1, 2, 4, 8 threads for the parallel-loop benchmarks, and for a `threading` workload once tier T exists | Rust column: `std::thread::scope` over the same chunks, no `unsafe` |

**Programs.** The five registered benchmarks (nbody, spectral-norm, fannkuch-redux, binary-trees,
wordfreq), `binary_trees_region.py`, plus an empty program and hello world for the baseline. SPEC
N-12c and N-17 refer to "an object-heavy benchmark" that no file identifies; choosing it is decision
D9. Each benchmark's Rust counterpart exists and contains no `unsafe`.

**Tooling.** `run.py` gains size and RSS columns and a result file per run. No measurement exists
before tier K is implemented; until then the only numbers are the Rust and default-mode columns, which
can be recorded first so the targets are concrete.

**Where.** Size and RSS are stable enough for a CI runner. Time and scaling need a quiet machine and
are run by the coordinating session alone, as AGENTS.md requires for measurements.

**Targets** are the owner's to set (decision D9). The note proposes none as fixed; "near Rust" is the
owner's phrase and a ratio per metric would make it testable.

## 9. Decisions needed

Options only. The first option of each is the agent's recommendation, with its reason; none is
settled.

- **D1. Integers that cannot be proved inside i64.** The constraint: Python ints are unbounded and the
  mode may not change what a program computes. (a) Our own small-int / big-int value, linked only when
  needed, with "compile error if unproven" as a stricter build option: exact, and ordinary counters and
  sums compile. (b) Compile error only: smallest runtime, rejects common code. (c) Checked i64 that
  ends the program on overflow: excluded by the owner's rule unless the owner allows that difference.
- **D2. Reclaiming memory.** (a) No object, then regions, then reference counting with static
  elision, and a small cycle collector linked only for classes that can form cycles: lowest baseline
  memory, deterministic `__del__`. (b) The same without a cycle collector: such classes are a compile
  error. (c) A tracing garbage collector for all objects: simplest to make correct, larger runtime and
  higher baseline memory, which works against the stated aim.
- **D3. Order of the tiers.** (a) K, then O, then S, then T, measuring after each: the first numbers
  arrive after the smallest piece of work and three benchmarks run on tier K alone. (b) S before O,
  because real programs need strings before classes. (c) Nothing is released until all tiers exist.
- **D4. Differences from CPython that are accepted.** The owner's sentence allows none in what is
  computed. Proposed list, each needing a yes or no: the traceback lines of an uncaught exception
  (the final `Class: message` line and the exit status are kept); behaviour at memory exhaustion;
  Ctrl-C without the `KeyboardInterrupt` text; and the recursion limit, where the options are (i) keep
  a per-thread depth count and raise `RecursionError` at CPython's default limit of 1000 frames, exact
  and one counter per call, or (ii) let recursion run until the C stack limit and treat the limit as a
  resource difference.
- **D5. What is built.** (a) An executable only, first: this is what INTENT §1.4 describes. (b) Also
  a C-ABI library that a Kotlin Multiplatform app links without CPython, which is where this project's
  apps would use it, but it needs the direct Kotlin call path (design 4.4 of typedpython.md, not
  implemented) and a new kind of artefact.
- **D6. Marking.** (a) An AOT build compiles every function of the program; `@compiled` is only needed
  to pass `parallel=True` / `fastmath=True`. (b) Every function must still carry `@compiled`, as in the
  default mode, and an unmarked function is a compile error.
- **D7. Threads in the first version.** (a) Parallel loops only; `threading` as tier T after the
  others, since it needs the escape analysis of section 7. (b) `threading` from the start.
- **D8. Provided standard library.** The list of section 4.5 as the first set, or another list. And
  whether provided modules are written in the subset where possible (proposed) or all in C.
- **D9. Measurement.** Which program is the "object-heavy benchmark"; whether targets are set as
  ratios to Rust per metric; and whether size and RSS may run on CI runners.
- **D10. Runtime language.** (a) C, compiled by the same C compiler as the default mode: no new
  dependency. (b) Rust for the runtime pieces: memory-safe runtime code, but a new toolchain in the
  build of every AOT program.

## 10. What this note does not establish

- No size, memory or time number for AOT exists. That the kernel tier lands near Rust is an
  expectation from the runtime it links (a C entry point, checked arithmetic, formatting), not an
  observation.
- Float formatting (`repr`, f-string specs) must reproduce CPython's digits exactly. A shortest
  round-trip algorithm gives the same `repr` digits; fixed-precision formatting through the platform's
  `printf` is correctly rounded on current glibc, musl, Apple libc and UCRT, but that has to be shown
  by differential tests on each target, or replaced by our own conversion.
- The thread-escape analysis of section 7 and the cycle analysis of section 6.3 are described, not
  designed in detail; each needs its own section before code.
- How much of the default mode's verifier can be reused unchanged for whole-program typing (principle
  3) is not known until the front end is run over whole benchmark programs in a check-only mode. That
  check-only mode is the suggested first piece of code once the decisions above are made: it changes
  no behaviour and reports, per program, every construct that is an AOT compile error.
