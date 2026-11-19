# TypedPython AOT mode: a standalone build with no interpreter

Design note for SPEC N-17 (#443). **No code exists for this mode.**

Provenance. What the owner decided (INTENT §1.4, 2026-11-16: "너가 제시한 AoT모드 좋은거 같아. 도입하도록
해."): the mode is opt-in per build; when the whole program passes the verifier it is emitted as a
standalone binary that does not ship the interpreter; code the verifier cannot prove is a compile error;
the aim is binary size and baseline memory near Rust's; the default mode is unchanged; an accepted
program must compute what CPython computes for it. Further decisions of 2026-11-16 on the options this
note first listed are recorded, with who made each, in section 10: the owner chose D4, D5 and D6; the
leader adopted the agent's proposal for D1, D2, D3, D7, D8, D9 and D10 (those stay marked not
owner-confirmed). **Everything else in this note is an agent proposal, not owner-confirmed.**

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
| Measurement | `benchmarks/typedpython/run.py` measures wall time only | Binary size and RSS measurement must be added (section 9) |

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
- **Marking.** An AOT build compiles every function of the program; `@compiled` is needed only to pass
  `parallel=True` / `fastmath=True` (owner decision D6, 2026-11-16).
- **Output.** One C translation unit plus the runtime pieces in use, compiled by the same C compiler
  the default mode uses, linked with section garbage collection so unused runtime code is dropped.
  Two outputs from the start (owner decision D5, 2026-11-16): an executable, and a C-ABI library that a
  Kotlin Multiplatform app links without CPython (section 8).

## 4. The accepted subset (N-17 item 1)

Proposed in tiers, so that each tier is a complete, testable mode and the measurement of section 9 can
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
  CPython prints, `ClassName: message`. The traceback lines above it are not reproduced (owner decision
  D4).
- Not accepted in any tier of this note: generators and `yield`, `async`, closures that outlive their
  defining call, `lambda` stored in data, decorators other than `@compiled`, `with` on user context
  managers, `match`, `global` rebinding from another module, numpy and `array.array` buffers (they are
  CPython objects). Calls to Kotlin are typed direct calls, not object calls (section 8).
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

This list is the first set (leader decision D8, not owner-confirmed).

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
| Region allocation failure | Raises `MemoryError`. Behaviour at memory exhaustion may differ from CPython's (owner decision D4) |
| i64 overflow of `+ - * // << `, negation | See below (D1) |
| Index out of range, division by zero, math domain errors, unset slot, unbound local | Not deopts today either: they raise CPython's exception with CPython's message. Unchanged; a check the verifier proves away is not emitted, as today |
| Recursion deeper than the recursion limit | No depth count: recursion runs until the C stack limit, where the runtime reports a `RecursionError` if the platform lets it detect the overflow and otherwise the process ends (owner decision D4: the limit of 1000 frames is not kept). A program that relies on catching `RecursionError` is outside the subset |

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

Adopted: (b), with (a) available as a build option for programs that want a guarantee of no bignum
code; (c) is excluded because its result differs from CPython's (leader decision D1, 2026-11-16, not
owner-confirmed).

**What is implemented (#462; design decision D1 (a), leader 2026-11-16, not owner-confirmed; the representation was
approved by the leader on 2026-11-16, not owner-confirmed; everything below that is not so marked is an agent proposal,
not owner-confirmed).**

- *Representation.* An int is RAW (an `int64_t`, computed exactly as before) or TAGGED (`tpa_int` of
  `runtime/tp_aot_int.h`: owned references that are released at the function's cleanup point and when a local is
  rebound, as lists are; parameters are borrowed; a returned value is owned by the caller). A program decides per name,
  parameter, return and tuple element by a fixpoint over all its functions (`aot/rep.py`, which states the rule and is
  tested at the level of the IR). A value is RAW when it is an int constant, `len(...)`, the loop variable of a `range`
  loop whose bounds are RAW, an element read from a `list[int]` of a RAW list group (below), a call result whose callee returns RAW, a parameter
  whose every call site passes RAW (a library export also receives int64 from outside), or the result of an operation
  the IR marks proved AND the sound re-proof (below) proves AND whose operands are all RAW. Everything else is TAGGED:
  an unproved operation, any operation with a TAGGED operand, `int(sys.argv[i])`, an element read from a big-capable
  list, and what flows from them.
- *Lists of ints (#465; static classification: leader decision 2026-11-16, not owner-confirmed; the rules are agent
  proposals, not owner-confirmed).* A `list[int]` is not one kind of list. Each `list[int]` name of the program (a
  local, a parameter, the list a function returns, a call result, a creation) belongs to a GROUP, and the names that may
  denote one list object are merged: `b = a`; an argument and the parameter it is passed to, at every call site; the
  list a function returns and the call result that receives it; all the lists a name ever holds (a name assigned twice,
  the two branches of a conditional); a list that crosses a library boundary (an export's list parameter and result are
  nodes like the others, and the class of their group is what the generated signature says). A copy `b = a[:]` is a new
  object, so it is not merged with `a`, but its elements come from `a`: when the group of `a` is big-capable the group of
  `b` is too (one-way). A group is BIG-CAPABLE when a TAGGED value can be stored into a member (`a[i] = v`, `a[i] op= v`),
  when a member is created by `[v] * n` with a TAGGED `v` or by a display with a TAGGED element, when it is a copy of a
  big-capable list, or when the analysis does not understand how a name is bound; every other group is RAW. On any
  doubt, big-capable. The class and the int inference are one fixpoint (an element read from a RAW group is a RAW int, one
  from a big-capable group is TAGGED, and a stored value is TAGGED or not by the int inference); both only rise, so it
  ends. The sound re-proof asks the same oracle: an element of a RAW group is an int64, one of a big-capable group is
  unbounded, which is the one place the proof machinery assumed "list elements are int64", and is now conditional.
  A RAW list is exactly what it was (the array of `int64_t`, the generated C, the ABI's `i64_list`). A big-capable list
  is the same counted heap object with elements of the tagged value (`tpa_int_list` of `runtime/tp_aot_int.h`): creation
  retains what it takes, an element read is a new reference, an element store takes the new element and releases the
  old, and the release of the list releases every element before it frees; all at the same cleanup point as every other
  owned value. What it costs: a list in a big-capable group pays tagged element access (one
  word, a retain and a release per read and store, and a call into the big-integer code for the arithmetic on what is
  read), and one store of a TAGGED value into any list that may alias it makes all of them pay; a list that only ever
  receives proved values stays a plain `int64_t` array even when its elements are read into TAGGED arithmetic. What the
  classification cannot find: it is per name and per program, never per object or per path, so a list that receives a
  big value on one path only makes every list it may alias big-capable (speed, never exactness). The diagnostic
  `python -m typedpython aot-build ENTRY.py --explain-lists` (also `aot-lib ... --explain-lists`) builds nothing and prints
  each group with its members (function, name, line), its class, each merge with the statement and line that caused it,
  and for a big-capable group the statement and line of each cause. fannkuch.py builds with `count` big-capable (`r`
  starts as the argument, so `count[r - 1] = r` and `count[r] -= 1` store TAGGED values) and `perm1` and `perm` raw.
- *A proved flag is not enough.* The front end's and the verifier's proofs read every int as one inside 64 bits (an
  operation that is not proved is clamped to int64, "it would have deopted"; a parameter or a call result is "any
  int64"), which is false of a TAGGED value; a guard `if a < b:` cuts the interval of `a` by that false interval of a
  TAGGED `b`, and the length the verifier gives a list created with a TAGGED length lets a proved index skip its bounds
  check on a list that is shorter. So the proved flags are a candidate only: `verify.sound_proofs` walks the verified IR
  again with intervals that hold of unbounded integers, given the inference as its oracle (a RAW name or return is
  int64, a TAGGED one, `int(sys.argv[i])` and every operation that is not proved are unbounded, nothing is clamped), and
  an operation is RAW, and a proved index skips its check, only when it is proved there too. Inference and re-proof are
  one fixpoint (the re-proof runs again after every rise to TAGGED). This replaced a "taint" rule (a name compared with
  a TAGGED value could not be trusted) that missed the list-length path. Agent proposal, not owner-confirmed. The cases
  it exists for (a RAW addition that would wrap for each kind of guard, an index past the end of a shorter list) are in
  the IR-level and differential tests.
- *Exactness.* Operations on TAGGED values are the runtime's exact functions (`+ - *`, `//`, `%`, comparison chains,
  `abs`, `min` and `max`, truthiness, `range` loops with TAGGED bounds, list indexing with the reference's IndexError
  for an index that does not fit, `print` and f-strings with the 4300-digit limit's ValueError). `int / int` is accepted
  for every operand (correctly rounded; two RAW operands within plus or minus 2**53 are divided directly). An int meets a
  float as CPython does: arithmetic converts the int with its OverflowError (where the operation runs, after the other
  operand was evaluated), comparison is exact and never converts. `int(sys.argv[i])` is exact for every argument: the
  reference's `repr` of the string in the message (a table of the non-printable code points of Unicode 17.0 generated by
  `python-multiplatform-ksp/tools/gen_aot_nonprintable.py`), and bytes that are not valid UTF-8 as the lone surrogates
  the reference's argument decoding produces. On Windows the arguments arrive as UTF-16 (`wmain`); that path is reasoned
  from the reference's documentation, not observed on the machine that wrote it.
- *Strict mode.* `--strict-int` on `aot-build` and `aot-lib` (`strict_int=True`) makes any int the compiler cannot prove
  inside 64 bits a compile error, `aot/int-unproven`, with the message of the earlier step. It is off by default.
- *Still refused.* An int literal or a module constant outside 64 bits is still `aot/int-literal` / `aot/constant`.
  The finding `aot/int-list-store` no longer exists (a store of a TAGGED value makes its group big-capable).
  spectral_norm.py, nbody.py (reading `sys.argv`) and fannkuch.py build and run.

CPython limits conversion between an int and a decimal string to 4300 digits by default and raises
ValueError beyond that, in `str`, `print`, f-strings and `int(str)`. The AOT runtime reproduces that
limit and its messages exactly (leader, 2026-11-16). CPython lets a program change the limit with
`sys.set_int_max_str_digits`, the `-X int_max_str_digits` option or the `PYTHONINTMAXSTRDIGITS`
environment variable; the provided `sys` has no such function and the executable reads no such setting,
so an AOT program always runs with 4300. A program that calls `sys.set_int_max_str_digits` is a compile
error, and a program that relies on the option or the environment variable computes something else
under CPython than as an AOT binary: that is a stated limit of the mode, not an accepted difference.

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
- Lists: a counted heap object, count and length and then the elements; `float` elements are unboxed `double`s.
  A `list[int]` is one of two kinds, decided for each group of list names that may be one list (section 5, "Lists of
  ints"): RAW, with unboxed `int64_t` elements, or BIG-CAPABLE, with one tagged word per element (the small-int /
  big-int value of D1, the list owns one reference to each element that is a big). The kind is static, so there is no
  check per element read for which kind it is, and a raw list pays nothing for the other. (Capacity belongs to the
  growable lists of tier S: a list of tier K never changes length.)
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
whose field types cannot reach the class again. For the remaining classes a small cycle collector is
linked, and only when the program has such a class (leader decision D2, 2026-11-16, not
owner-confirmed; a tracing collector for all objects was the rejected alternative, because its runtime
and baseline memory work against the aim).

`__del__` on an acyclic class runs when the count reaches zero, the same point as in CPython, because
CPython also frees such objects by reference count. That equivalence does not hold for objects in
regions or for elided counts, so a class with `__del__` is always reference counted with exact counts.

### 6.3.1 Tier O step 1 (PR-O1): headerless classes, reference counting, and acyclicity

Design for PR-O1 (`binary_trees.py`, before virtual objects and scope regions arrive in PR-O2; leader
review 2026-11-20, not owner-confirmed).

- **Struct layout.** Each compiled class `C` lowers to a plain C `struct tpa_cls_<C>` with no `PyObject`
  header and no class-id word (no inheritance or dynamic `isinstance` in PR-O1):
  - `tpa_rc_t rc` first (`int64_t` in an executable; `_Atomic int64_t` under `TPA_THREADS`).
  - Fields in declaration order: `float` as `double`, `bool` as `uint8_t`, `int` as `int64_t` (RAW) or
    `tpa_int` (TAGGED, owned), native lists as counted handles (owned), `D` as `tpa_cls_<D> *` (non-null,
    owned), and `D | None` as `tpa_cls_<D> *` (`NULL` represents `None`, owned).
  - Allocation sets `rc = 1` (failing with `MemoryError` on `NULL`). If `__init__` raises after setting
    some reference fields, the constructor releases those initialized fields and frees the struct.
  - Release decrements `rc`; at zero it releases owned reference fields and frees the struct. To avoid
    C stack overflow when freeing deep self-referential chains, the last self-referential field is
    released via a tail loop (`while (p != NULL)`).
- **Single immutability analysis (shared source of truth).** One whole-program pass classifies each
  field `(C, field)` as **immutable-after-init** if and only if no statement outside `C.__init__`
  anywhere in the program assigns to that field, and `C.__init__` does not store `self` into any field
  or let `self` escape into another call. A class `C` is **immutable-after-init** when every reference
  field of `C` is immutable-after-init. Both the acyclicity proof and the reference-counting elision
  rules below read this exact same analysis result so the two cannot disagree.
- **Cycle-freedom proof (PR-O1).** Because PR-O1 has no cycle collector yet, a class `C` is accepted
  only if it is proved acyclic by at least one of:
  1. *Type-graph acyclicity*: in the directed graph where `C -> D` if `C` has a field of type `D` or
     `D | None`, `C`'s strongly connected component has no cycle.
  2. *Immutable-after-init*: `C` (and every class reachable in its SCC) is proved immutable-after-init by
     the shared analysis above (so every reference points strictly to an already-constructed object,
     forming a causal DAG, as in `binary_trees.py`'s `Node`).
  Any class that fails both conditions is a compile error (`aot/cycle-possible`, naming the class and
  the mutating field store outside `__init__`) until PR-O2 adds the small cycle collector.
- **Reference counting and elision rules.** Ownership follows the same convention as native lists and
  `tpa_int`: parameters are **borrowed**; locals, return values and struct fields are **owned**.
  Elision is permitted only under the following statically proved conditions, and every other site emits
  explicit `retain` / `release`:
  1. *Borrowed parameter reads*: reading or forwarding a borrowed parameter directly does not retain or
     release it.
  2. *Single-consumption move*: an owned temporary (constructor or call result) consumed once as a local
     initializer, `return` value, or field/constructor initializer transfers ownership with no extra
     `retain` / `release`.
  3. *Field read into a local*: a local initialized from `x.field` may be treated as borrowed (no
     `retain` / `release`) **only if** `(C, field)` is proved immutable-after-init program-wide by the
     shared analysis above (and `x` itself outlives the local, e.g. `x` is a borrowed parameter or an
     unrebound local). "No field write in this function" is **not** sufficient because a callee or an
     alias can mutate `x.field` and free the old object; any mutable field read must `retain`.
  4. *Field passed as a borrowed argument*: passing `x.field` directly to `f(x.field)` may pass it as
     borrowed **only if** `(C, field)` is proved immutable-after-init program-wide (and `x` outlives the
     call). If `(C, field)` is mutable, the caller must `retain` the value into a temporary before the
     call and `release` it after the call, so `f` mutating `x.field` (directly or through an alias)
     cannot free its own argument.
- **Library mode (`TPA_THREADS`) restriction before Tier T.** Under `TPA_THREADS`, a concurrent writer
  to a mutable reference field could release the old object between another thread's load and `retain`
  (the same race `tpa_int_list` solves with a per-list lock). Until Tier T adds shared-object locking,
  an `aot-lib` export whose parameter or return type is a class with any mutable field (not
  immutable-after-init) is a compile error (`aot/library-mutable-class`). Immutable-after-init classes
  are safe to share across threads with atomic `rc` and are allowed.
- **Verification plan (PR-O1 tests).**
  - Differential tests against CPython 3.15t for scalar/list/class/`C | None` fields, methods, `is None`
    / `is`, and `benchmarks/typedpython/py/binary_trees.py`.
  - Adversarial lifetime tests under ASan/UBSan (`typedpython.yml` `sanitizers` job) specifically
    exercising: (a) a local read from a mutable field whose owner is mutated through an alias or callee
    before the local is read again, (b) `f(x.field)` where `f` mutates `x.field` while using its
    parameter, (c) partial `__init__` failure cleanup, and (d) deep tree deallocation.
  - Acyclicity acceptance/rejection tests (type-graph acyclic mutable class accepted; immutable-after-init
    self-referential `Node` accepted; mutable self-referential class rejected with `aot/cycle-possible`;
    `__init__` `self` escape rejected; `aot-lib` mutable class rejected with `aot/library-mutable-class`).
  - `aot-check` sync: `binary_trees.py` reports 0 blocking findings.

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
  `signal` is not provided; Ctrl-C ends the process with the same exit status CPython gives for an
  uncaught `KeyboardInterrupt` (death by SIGINT), without the `KeyboardInterrupt` text (owner decision
  D4); a program that catches `KeyboardInterrupt` is a compile error.

The first version has parallel loops only; `threading` is tier T (leader decision D7, not
owner-confirmed).

**Lists that may hold big ints (#465), the one shared container that exists today.** A library build
(`TPA_THREADS`) lets any host thread hold the same list handle. For a raw `i64_list` or an `f64_list` a
racing read and write yields the old or the new value, the same as a C array. For a big-capable
`tpa_int_list` the element is a small int or a counted pointer to a big integer, and the two steps of a
read (load the word, then retain it) and of a store (put the new word, release the old one, freeing it at
zero) are not atomic as a pair: a reader could retain what a writer had just freed, and two writers
could free the old element twice. CPython stays memory-safe under the same race, so AOT must too. The
rule of this section (per-container locking for shared containers) is applied with one small lock inside
each `tpa_int_list` (agent proposal, not owner-confirmed; the rule it serves is the per-container lock
above and the leader's decision of 2026-11-16 that there is no library-wide lock):

- Only under `TPA_THREADS`; the lock field exists only there, so an executable has the layout and the
  code it had, and the code for raw and float lists is unchanged.
- A spin lock on one flag (atomic exchange to take, atomic store of 0 to release, a CPU pause and, after
  64 tries, a yield of the time slice where the platform has a portable one). It cannot deadlock: it is per
  list, no operation holds two (a copy locks only its source), and the code between lock and unlock is
  one load, one count increment or one word exchange, with no allocation, no free, no callback and no
  other lock.
- Element read: under the lock, load the word and retain it; unlock. Element store: take the new
  reference first, under the lock exchange the slot's word, unlock, then release the OLD word (a release
  may free memory, which is not done under the lock). `a[:]` of a big-capable list holds the source's lock
  for its loop of loads and retains; the conversions from a raw list and `[v] * n`, `range` and displays
  write a list no other thread can see yet and take none; the release at count zero has no other owner.
- Result: a reader sees the old or the new element; nothing is freed twice or used after free. Which of
  two writers to the same slot wins is still the caller's business.

## 8. The C-ABI library and the Kotlin call path

Owner decision D5 (2026-11-16): besides the executable, an AOT build produces a C-ABI library that a
Kotlin Multiplatform app links without CPython, from the first version. The shape below is an agent
proposal, not owner-confirmed. It follows rules this repository already has: no runtime reflection and
no lookup by name (AGENTS.md 12.3, 12.4), bindings generated at build time, and the per-platform
calling rules of AGENTS.md 16.

### 8.1 What the library exports

- One C function per exported Python function, `<prefix>_<function>` (as built by #455), with a C signature
  derived from the Python annotations. Which functions are exported is stated in the build (a list of
  entry functions); everything they reach is compiled, the rest of the program is dropped by the linker.
- `<prefix>_init()` runs the module initialisers of section 3 once (idempotent, safe to call from any
  thread) and `<prefix>_shutdown()` frees what they allocated.
- A generated C header and, beside it, a machine-readable description of the same signatures for the
  Kotlin side (8.3). No symbol is found by name at run time. The description's `abi_version` is 2 since #465 (leader
  decision 2026-11-16, not owner-confirmed): the type vocabulary gained `int_list` (8.2), and its `lists` section has an
  `int_list` entry only for a library whose signatures use that kind; a library with no big-capable list at its boundary
  has the C text, the header and the description it had under version 1, except that `abi_version` is 2 (and the comment
  at the top of the C text says so).

### 8.2 Types at the boundary

| Python type | C ABI | Kotlin side |
|---|---|---|
| `int`, `float`, `bool` | `int64_t`, `double`, `uint8_t` | `Long`, `Double`, `Boolean` |
| `int` held as a big-integer-capable value inside the function (#462) | A parameter arrives as `int64_t` and is converted where the compiled function holds it so. A result (also an element of a tuple) is converted back with a fit check: one that does not fit `int64_t` fails the call with -1 and the error record `OverflowError`, message "the int result of `<function>` does not fit in 64 bits", and no out value is written. The same call with smaller inputs succeeds | `Long`; the failed call throws `TypedPythonAotException` as for any other error |
| `str` | pointer and length of UTF-8 bytes; an argument is borrowed for the call, a result is owned by the caller and released with `<prefix>_free` | `String`, copied at the boundary |
| `list[float]` / `list[int]`, as an argument and as a result | an opaque counted handle (`<prefix>_f64_list`, `<prefix>_i64_list`) made and read through exported list functions (`_new`, `_len`, `_data`, `_retain`, `_release`); an argument is borrowed for the call, a returned handle is owned by the caller | a class holding the handle, with `DoubleArray` / `LongArray` copied from or to the `_data` address as the app needs |
| a `list[int]` that the classification finds big-capable (#465), as an argument and as a result | the opaque counted handle `<prefix>_int_list`, with `_new` (n zeros), `_len`, `_retain`, `_release` as above and, because its elements are not `int64_t` in memory, `_get(l, i, &v)` and `_set(l, i, v)` instead of `_data`; both return 0 or -1 with the error record: an index out of range (a negative one counts from the end) is the source language's `IndexError` with its two messages, and `_get` of an element that does not fit `int64_t` fails with an `OverflowError` whose fixed message is "the element of the list does not fit in 64 bits" (nothing is written; after a `_set` of a smaller value the same element reads). Which kind a signature has follows from the classification: a list that crosses the boundary is one group with the outside, so the caller can only pass a handle of the kind the signature names, and a change in a helper that several exports share can change another export's list kind, so a header is regenerated and its callers rebuilt together. The two handle kinds are not interchangeable (a wrong one is memory corruption; only the C types prevent it). `_get` and `_set` take the list's own lock (section 7) | a class holding the handle with `get(i)` / `set(i, v)` (the generator's matching class is the next step) |
| an instance of a compiled class, or any other container | an opaque handle (a counted reference) with `<prefix>_release(handle)` | a Kotlin class holding the handle, released deterministically (`close`) with a cleaner as a safety net |
| `None` result | `void` | `Unit` |

The int rows are an agent proposal, not owner-confirmed (the representation they serve, design decision D1 (a), was
approved by the leader on 2026-11-16, not owner-confirmed). The result rule is an ABI-level rule with no counterpart in
the source language, like the null-handle `TypeError`: the source language's int has no limit and the C ABI's has, and
the generated header says so in its Errors paragraph (only for a library that has such a result; the header of a library
whose ints are all proved inside 64 bits is what it was). The `int_list` row and its `OverflowError` are an agent
proposal, not owner-confirmed, and the element rule is, like the int result rule, ABI-level with no counterpart in the
source language; the header says so in a paragraph that exists only in a library that uses the kind.

The list row is a leader decision of 2026-11-16, not owner-confirmed. Reason: an AOT list carries a 16-byte
header (count and length) before its elements, so a foreign array cannot be passed as it is; a handle
also keeps Python's list identity across calls (a function that returns its argument returns the same list,
and two parameters may be one list, as two names are in the source).

Errors: every exported function returns a status; on failure the exception's class name and message
are read with `<prefix>_last_error` on the same thread, and the generated Kotlin wrapper throws a
Kotlin exception carrying both. Nothing unwinds across the boundary.

Threads (leader decision 2026-11-16, not owner-confirmed; the library output is owner decision D5):
there is no library-wide lock and TypedPython has no GIL-like single lock. An exported function may be
called from any Kotlin thread and from several at once, so exported functions are reentrant. The error
record behind `<prefix>_last_error` is thread-local. List counts are atomic in a library build (and stay
non-atomic in an executable build). Module-level mutable state is refused for a library (`aot/library-state`):
tier K has none, since module constants are folded at compile time and a function that assigns a
module-level name makes a local. Concurrent reads and writes of the elements of one list from several threads are
memory-safe for every list kind (agent proposal, not owner-confirmed; the correction of #465): for `i64_list` and
`f64_list` they are what a C or Kotlin array gives, a racing read sees the old or the new value; for `int_list` the
library takes a small per-list lock inside the list for each element access (section 7), because a big element is a
counted object that a racing write could free under a reader. Which of two writers to the same element wins is the
caller's business for every kind, and the generated header says so. This replaces the earlier proposal of one library
lock around each exported call and the earlier text that left every element race to the caller.

### 8.3 Kotlin calling Python (the downcall)

Provenance. That the library output exists is owner decision D5 (2026-11-16). That the desktop JVM
comes first and that the generated Kotlin is self-contained (it reaches `java.lang.foreign` by
reflection once at link time, uses pointers as `JAVA_LONG` and calls through `invokeExact`, and does
not depend on the CPython-embedding library) are leader decisions of 2026-11-16, not owner-confirmed.
Everything below about the shape of the generated API is an agent proposal, not owner-confirmed.

**What exists (#458, desktop JVM).** `GenerateAotLibraryBindingsTask` in the Gradle plugin reads the
`<prefix>.json` of 8.1 (`abi_version` must be 2, and every other value, 1 included, fails the task and
names the supported version: there are no deployed version 1 libraries and the compiler here writes 2 only; a
type outside the vocabulary, or `int_list` with no `lists.int_list` entry, fails the task and names the
function) and writes four Kotlin files into one package, five when the library uses `int_list`:

- `AotFfm.kt`, the same text for every library: `TypedPythonAotException(className, message)` and a
  small runtime over `java.lang.foreign` (JDK 21 with `--enable-preview`, or JDK 22 and later). It
  resolves the API reflectively once, binds each symbol to a downcall handle whose type is made of
  `long`, `double`, `byte` and `int` only, and reads and writes native memory through handles bound once.
  Nothing outside `java.base`. A call afterwards is `invokeExact` and nothing else.
- `<Object>.kt`: an object with `load(libraryPath)` (idempotent, thread-safe: loads the library,
  binds every symbol, calls `<prefix>_init`) and one function per export. Names and parameter names
  are the Python names as they are (snake_case kept; a Kotlin hard keyword is backticked). Types:
  `Long`, `Double`, `Boolean`, the list classes, no result type for None, and a generated data
  class `<Name>Result(v0, v1, ...)` for a tuple (also for two elements, so there is one rule). A failed
  call (status -1) throws `TypedPythonAotException` built from `<prefix>_last_error` on the same thread.
- `F64List.kt`, `I64List.kt`: counted native handles with `size`, `get`/`set` (bounds checked on the
  Kotlin side), `toDoubleArray()`/`toLongArray()`, `of(array)`, and `close()` (releases once; use after
  close throws `IllegalStateException`). A list an exported function returns is wrapped as an owned
  handle; the same Kotlin list passed for two parameters is the same handle, so the parameters alias as
  in Python. An unclosed list is not freed by the garbage collector.
- `IntList.kt`, only for a library whose description has the `int_list` entry (#465; the kind and `abi_version`
  2 are a leader decision of 2026-11-16, not owner-confirmed; the class is an agent proposal, not
  owner-confirmed): the Kotlin side of a `list[int]` that may hold values outside 64 bits. It has `size`,
  `IntList(size)`, `IntList.of(LongArray)`, `get`/`set`, `toLongArray()` and `close()` with the ownership rules
  of the other two. It has no memory to read: every `get` and `set` is one `invokeExact` call into the
  library's `<prefix>_int_list_get` / `_set`, so it costs more than `I64List`, which reads native memory
  directly. A failure of that call is the library's error record as a `TypedPythonAotException`: an index out
  of range is `IndexError` with the messages of the source language (there is no Kotlin-side bounds check, so
  the caller sees the same exception as in Python; a negative index counts from the end), an element that does
  not fit in 64 bits is `OverflowError` "the element of the list does not fit in 64 bits" (also from
  `toLongArray()`), and use after `close()` stays an `IllegalStateException`. A signature that takes an
  `int_list` accepts only `IntList`, one that takes an `i64_list` only `I64List`: the classes share no type,
  because handing the library a handle of the wrong kind is memory corruption, so the Kotlin type system is
  the protection. A library with no `int_list` in its description generates the Kotlin it did under version 1,
  except for the `abi_version` number in the comment of the object file. An `int` result that does not fit in
  64 bits is `OverflowError` "the int result of `<function>` does not fit in 64 bits", through the ordinary
  status check, with no special case in the generator.

Out pointers are per-thread scratch memory: one 128-byte block per thread, allocated on first use from
an automatic arena that the garbage collector frees once the thread is gone. A call costs no
allocation and two threads never share a slot. A function may have at most 16 results.
The helper and the list classes have fixed names, so each library takes its own package.

**How the fixture uses it.** `python-multiplatform-ksp/fixtures/aot` declares the bindings plugin with
`apply false` and registers the task class itself, so no KSP, no `PYTHONHOME` staging and no dependency
on `python-multiplatform` come with it. A front-end-only Python step writes the JSON, the task
generates Kotlin from it into the test source set (and, via `resourceOutputDirectory`, writes
`META-INF/native-image/<packageName>/<prefix>/reachability-metadata.json` with every foreign downcall
descriptor and the `java.lang.foreign` reflection surface for GraalVM native-image without a tracing
agent), and `python -m typedpython aot-lib` links the library as a dependency of the test task only. The
test (`fixture.aot.AotLibraryCallTest`) calls the `nbody` kernels and a small library of failures and
list functions, compares floats bit for bit with CPython, checks exception classes and messages, runs two
threads, checks that every bound `MethodHandle` descriptor matches the generated metadata, and asserts
that no class of the CPython-embedding library is on the classpath and that the process loads no
libpython (Linux: the process's maps; macOS: `vmmap` when it can examine the JVM, otherwise the
classpath check only). The same 25 scenarios are also compiled by `aotNativeCompile` into a standalone
GraalVM native image (`AotNativeImageMainKt`, verified by `aotNativeRun` in `.github/workflows/typedpython.yml`
with `JAVA_HOME` and `PYTHONHOME` removed, #456).

**Not done.** Other platforms (Android `RegisterNatives`, Kotlin/Native cinterop, wasm); packaging of
the library and the generated Kotlin into an app (waits for the owner decision of #25 step 3).

### 8.4 Python calling Kotlin (the upcall)

Today an upcall goes through a function table generated at build time and marshals `PyObject`s. In an
AOT library the same table holds typed C function pointers, filled by the host at `<prefix>_init`:
a Python-side call to a Kotlin function whose stub gives it a signature in the types of 8.2 compiles to
an indirect C call through its table slot. This is the direct call of `typedpython.md` section 4.4,
whose stated prerequisite (stubs generated from the real Kotlin surface) is not done yet. A Kotlin
function with a signature outside 8.2, and every use of a Kotlin library through dynamic Python objects
(the `androidx` binder modules, Compose), is outside the subset and a compile error. So the first
library form serves compute code called from a Kotlin app, not Python-written UI.

A static table of function pointers has no reflection in it, so it fits the GraalVM native-image rule
(AGENTS.md 13); that it works there has to be shown by building the image, as for every upcall.

### 8.5 Platform order, coexistence, packaging

Desktop JVM is the first platform of the library form, then iOS and Android; an AOT library and the
default mode's CPython may coexist in one app (section 10, leader decisions of 2026-11-16). Open: the
artefact's packaging (static library, shared library, or both per target) and how pypackpack and the
toolchain carry it, which waits for the owner decision of #25 step 3.

## 9. Measurement plan

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
wordfreq), `binary_trees_region.py`, plus an empty program and hello world for the baseline. Each
benchmark's Rust counterpart exists and contains no `unsafe`.

SPEC N-12c and N-17 refer to "an object-heavy benchmark" that no file identified. Leader decision D9
(2026-11-16, not owner-confirmed): binary-trees and one more, proposed by this note and to be added to
the repository. Proposal: **`lru_cache`**, a fixed-capacity least-recently-used cache simulated over a
stream of pseudo-random integer keys: a `dict[int, Node]` plus a doubly linked list of `Node(key,
value, prev, next)`, with a hit moving the node to the front and a miss evicting the tail. Why this
one: binary-trees only allocates immutable, acyclic nodes that die together, which is the best case
for regions. `lru_cache` is the opposite on each count: objects are mutated after construction, the
`prev` / `next` links form cycles (so it exercises the cycle analysis and the collector of 6.3),
lifetimes are individual rather than nested, and a container holds the objects. The best safe Rust
for it is an index arena (`Vec<Node>` with integer links) or `Rc<RefCell<..>>`; both are measured and
the faster one is "the best safe Rust". It needs tiers O and S. A dispatch-heavy program (richards) is
a later addition once inheritance exists.

**Tooling.** `run.py` gains size and RSS columns and a result file per run. No measurement exists
before tier K is implemented; until then the only numbers are the Rust and default-mode columns, which
can be recorded first so the targets are concrete.

**Where** (leader decision D9, not owner-confirmed). Binary size is measured in CI. RSS is recorded in
CI and also measured once locally, alone, by the coordinating session. Time and scaling need a quiet
machine and are run by the coordinating session alone, as AGENTS.md requires for measurements.

**Targets** are recorded as ratios to Rust per metric and benchmark (leader decision D9). No ratio is
fixed in this note: the first recorded numbers are the Rust and default-mode columns, and "near Rust"
is the owner's phrase.

## 10. Decisions

The first draft of this note listed ten choices as options. They were answered on 2026-11-16 (relayed
by the leader in a team message of that date). Who decided is part of the record; a leader decision
adopts the agent's proposal and is **not owner-confirmed**.

**Owner decisions (2026-11-16, choices among the options asked).** "What a program computes differs in
nothing" stays as it is.

- **D4. Differences from CPython that are accepted: all four.** (1) The traceback lines of an uncaught
  exception; the final `Class: message` line and the exit status are kept. (2) Behaviour at memory
  exhaustion. (3) Ctrl-C without the `KeyboardInterrupt` text. (4) The recursion limit: recursion runs
  to the C stack limit instead of stopping at 1000 frames.
- **D5. What is built: (b).** The executable, and from the start also a C-ABI library that a Kotlin app
  links without CPython (section 8).
- **D6. Marking: (a).** An AOT build compiles every function of the program automatically; `@compiled`
  is for the `parallel` / `fastmath` options.

**Leader decisions (2026-11-16; agent proposal adopted, not owner-confirmed).**

- **D1. Integers that cannot be proved inside i64: (a).** Our own small-int / big-int value, linked
  only when needed, plus a strict build option that makes an unproven operation a compile error. Ending
  the program on overflow is excluded: its result differs from CPython's.
- **D2. Reclaiming memory: (a).** No object, then regions, then reference counting with static
  elision; a small cycle collector only when the program has a class that can form cycles.
- **D3. Order of the tiers: (a).** K, then O, then S, then T, measuring after each.
- **D7. Threads in the first version: (a).** Parallel loops only; `threading` is tier T.
- **D8. Provided standard library.** The first list of section 4.5 (part of builtins, `math`, `sys`,
  `time`), and the principle that a provided module is written in the subset itself where possible.
- **D9. Measurement.** Object-heavy benchmarks: binary-trees and one more proposed in section 9
  (`lru_cache`), added to the repository. Targets recorded as ratios to Rust. Size in CI; RSS in CI and
  once locally, alone, by the coordinating session.
- **D10. Runtime language: (a) C.** The leader notes that C rather than Rust is a direction the owner
  had already confirmed.

- **Lists of ints: static classification (leader decision 2026-11-16, not owner-confirmed).** Each group of
  `list[int]` names is RAW or big-capable by a whole-program classification that runs in the same fixpoint as the int
  representations (section 5, "Lists of ints"); a big-capable list has tagged elements, and the C ABI gets the
  `int_list` kind and `abi_version` 2 (section 8.2). The side-table design approved earlier the same day is
  withdrawn. Reason: a side table on every int list would make every element read a tagged value, which breaks
  the assumption the representation inference and the sound re-proof rest on (an element of a raw list is an int64, so
  `a[i] + 1` over a list nothing big ever reached stays plain machine arithmetic), and it would have cost every
  list, including the ones of spectral_norm, nbody and fannkuch's `perm`, a lookup that only a few lists need.

**First code after this note** (agreed by the leader): the check-only mode of section 11, which changes
no behaviour and reports every construct of a program that would be an AOT compile error.

**Leader decisions on the points left open (2026-11-16, not owner-confirmed).**

- First platform of the library form: desktop JVM (the cheapest to verify in CI); iOS and Android next.
- One app may hold both an AOT library and the default mode's embedded CPython. Neither passes its
  objects to the other (the boundary is the types of section 8.2 only) and each is initialised on its
  own.
- `lru_cache` is the second object-heavy benchmark.
- Packaging of the library artefact stays open: it waits for the same owner decision as #25 step 3.
- Verifying the Kotlin downcall inside a GraalVM native image is issue #456 and a completion condition of tier K
  (leader, 2026-11-16, not owner-confirmed; AGENTS.md 13 applies to the library as it does to upcalls).

## 11. What this note does not establish

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
