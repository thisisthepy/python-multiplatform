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

#### 4.3.1 Tier S step 1 (PR-S1): strings

Design for PR-S1 (agent proposal, written 2026-11-21 after the leader's order K5, M1, O1, O2, S1, S2, S3, T1 and the
1.0 scope answer of the same day, "Start S1 right after the 1.0 items if time remains"; not owner-confirmed, leader
review pending). Tier S is in 1.0 (owner, 2026-11-21 21:55: "191, 443, 175 전부 1.0에 넣어", relayed by the leader;
the earlier "after 1.0" is withdrawn). This step changes nothing in a program that has no `str` value.

**What a program can do with a `str` (the accepted subset of this step).**

- Values: literals, `+`, `+=`, `*` by an int, `==` `!=` `<` `<=` `>` `>=` (code point order, as CPython), `len`,
  indexing (`s[i]`, negative included), slicing (`s[a:b]`, `s[a:b:c]`, CPython's index adjustment), `in` / `not in`
  (substring), iteration (`for ch in s`), the conditional expression, `str` locals, parameters, results, fields of a
  class (an owned field, acyclic) and module constants.
- Producers: `str(x)` and `repr(x)` of `int`, `float`, `bool` and `str` (the int and float formatters the kernel already
  has). `repr` of a `str`, also what `{s!r}` uses: single quotes unless the string holds `'` and no `"`; escapes
  `\\`, `\n`, `\r`, `\t`, the quote in use, and for a code point that is not printable (`str.isprintable`, from
  the table below) `\xNN` below U+0100, `\uNNNN` below U+10000, `\UNNNNNNNN` above (observed:
  `repr("\x7f\u00ad\U0001F600\n") == "'\\x7f\\xad\U0001F600\\n'"` with the emoji printed as itself); `chr`, `sys.argv[i]` (decoded as CPython decodes it: UTF-8 with `surrogateescape` on
  POSIX, the UTF-16 `wmain` arguments on Windows, which the int parser already reads), f-strings anywhere (not only in
  `print`), with `{s}`, `{s!r}`, `{x!r}` and the existing `{e}` / `{e:.Nf}`.
- Consumers: `print` of a `str` (also with other values, `sep` and `end`), `sys.stdout.write` and `sys.stderr.write`
  (their int result is the length, as CPython's text stream returns), `ord`, `int(s)` for any `str` (the parser of
  `int(sys.argv[i])`, with the reference's `repr` of the string in the error message).
- Methods (the list of section 4.3; any other is the compile error `aot/str-method`): `split()` / `split(sep)` /
  `split(sep, maxsplit)`, `join`, `strip()` / `lstrip()` / `rstrip()` with and without a `chars` argument, `lower`,
  `upper`, `startswith` / `endswith` (a `str` or a tuple of `str`, no `start` / `end` yet), `find` (no `start` / `end`
  yet), `replace` (with and without `count`), `isalpha`, `isdigit`. `encode` waits for `bytes` (S2).
- `list[str]` only as far as `split` and `join` need it, as the same runtime list type S2 generalises (a counted list
  of owned `tpa_str *`, the shape of `tpa_i64_list`), so nothing is thrown away: a list display of `str`, `split`'s result, indexing, `len`,
  iteration, `join`'s argument, a local or parameter. Growth (`append`, ...) is S2 with the other containers; a growing
  use is `aot/container` (pending, tier S), as today.
- Compile errors that stay: `is` / `is not` between two `str` operands (`aot/str-identity`: CPython's answer
  depends on interning and constant folding, so a program that relies on it has no defined result to reproduce;
  `s is None` / `s is not None` on a `str | None` stays accepted), `%` formatting and `str.format`
  (`aot/str-format`, pending S2), a `str` argument or result of an `aot-lib` export (`aot/library-str`; the C-ABI
  string kind is a later step with its own ABI version), and every method outside the list.

**Representation (`runtime/tp_aot_str.h`, linked only by a program that has a `str` value).**

- `struct tpa_str { tpa_rc_t rc; uint8_t kind; int64_t len; int64_t hash; <data> }`: CPython's PEP 393 layout without
  the object header. `kind` is 1, 2 or 4 bytes per code point and is always the narrowest kind that holds the largest
  code point (every constructor normalises), so two equal strings have equal kinds and `==` compares `kind`, `len` and
  then the bytes. Indexing and slicing are O(1) per element, as in CPython. A lone surrogate (from `surrogateescape`
  or from `chr(0xDC80)`) is an ordinary 2-byte code point, as in CPython; UTF-8 storage cannot hold it, which is why
  this layout and not UTF-8.
- `hash` is reserved for S2's `dict` / `set` (`-1` until computed); nothing in S1 reads it.
- Reference counting as the O1 objects (`tpa_str_retain` / `tpa_str_release`, `TPA_RC_*` macros, atomic under
  `TPA_THREADS`). A `str` holds no reference, so it is acyclic and never tracked by the collector. Literals and module
  constants are static objects with an immortal count (`TPA_RC_IMMORTAL`, retain and release skip it), so the library
  build's threads may share them with no write.
- Not a region or virtual object in S1 (the allocation classifier reports `heap-rc (str)`); a region object's `str`
  field is an owned heap reference released by the region walk, as a list field is (PR-O2b's R4 relaxation).
- `s += t` resizes `s` in place only when the local `s` **owns** its reference (it is not an elided borrow: not a
  parameter under PR-O1 elision rule 1, not a local read from an immutable-after-init field under rule 3, and not
  the source of a single-consumption move that is still live) **and** the count is 1 and not immortal; otherwise
  it copies. Ownership is the condition, not the count: a borrowed `s` can show a count of 1 that belongs to the
  caller's local or to the field, and resizing it would rewrite the caller's string (review of #510, F1). CPython's
  interpreter does the same through `unicode_concatenate`, which requires the frame's own local and a count of 1;
  unobservable, since nothing else holds `s`.

**Unicode data.** `lower`, `upper`, `isalpha`, `isdigit`, the whitespace of `split()` / `strip()` and `repr`'s
printable test depend on CPython's Unicode database. The AOT compiler runs on the reference CPython (3.15t), so it
generates the tables the program needs from that interpreter, at build time: for each code point, the full mapping of
`chr(c).lower()` and `chr(c).upper()` (one to three code points: `'ß'.upper() == 'SS'`, `'İ'.lower()` is two code
points), and bit sets for `str.isalpha`, `str.isdigit`, `str.isspace` and `str.isprintable`, compressed as two-level
tables. Only the tables the program uses are emitted; the generated C records `unicodedata.unidata_version`, and the build refuses to combine tables (or, in S2, string
hashes) generated by a different reference interpreter than the one the rest of the build used, so an incremental
rebuild cannot mix two Unicode versions (review of #510, F5). Final
sigma: `lower` applies CPython's `handle_capital_sigma` rule (U+03A3 becomes U+03C2 at the end of a word), in the
runtime, since it depends on context. The tables therefore match the interpreter that built the program bit for bit;
a program built by a different CPython minor version gets that version's tables, which is what that version prints.

**Output.** CPython 3.15 runs in UTF-8 mode by default (observed on the reference 3.15.0rc1t:
`sys.flags.utf8_mode == 1`, `sys.stdout.errors == "surrogateescape"`, `sys.stderr.errors == "backslashreplace"`).
`print` and `sys.stdout.write` therefore encode to UTF-8 from the kind array into the existing buffered `stdout`, a
code point U+DC80..U+DCFF (what `surrogateescape` decoded from a non-UTF-8 argument byte) is written back as that
raw byte, and any other surrogate raises `UnicodeEncodeError: 'utf-8' codec can't encode character '\ud800' in
position N: surrogates not allowed` (observed). `print` encodes and writes its arguments one at a time, so the
arguments before the failing one are written, followed by the separator `print` writes before each argument after
the first, and the failing one writes nothing (observed: `print("a", "x\ud800")` writes `a ` and raises), as
CPython's `TextIOWrapper` does.
`sys.stderr.write` and the uncaught-exception line use `backslashreplace`. What the Windows console path needs
(CPython writes a console through its own console I/O) is decided in S1a with a test on the Windows job; the kernel
tier's output is ASCII, so the question did not arise before.

**Exact messages (each one in the differential tests).** `IndexError: string index out of range`;
`ValueError: empty separator`; `ValueError: chr() arg not in range(0x110000)` (also for an int outside 64 bits,
`chr(2**70)`, observed on 3.15.0rc1t);
`TypeError: ord() expected a character, but string of length N found`; `int()`'s
`ValueError: invalid literal for int() with base 10: <repr>` (already exact for `sys.argv`); `OverflowError: repeated
string is too long` / `MemoryError` for `*` past the size limit (D4 accepts the memory-exhaustion behaviour, not the
size check, which is reproduced).

**Free threading.** Strings are immutable after construction and constants are immortal; the in-place `+=` applies
only to a count of 1 (no other holder on any thread). Nothing in S1 shares a mutable string between threads.

**Tests (PR-S1, `test_aot_tier_s1.py`).** Every operation and method differential-tested against CPython over a corpus
of ASCII, Latin-1, BMP, astral and lone-surrogate strings and the empty string, including each error message above;
exhaustive table tests (`lower`, `upper`, `isalpha`, `isdigit`, `isspace`, `repr` over every code point
0..0x10FFFF, printed in chunks and compared with the interpreter); `sys.argv` with non-UTF-8 bytes; the in-place
`+=` with a second holder, on a parameter whose caller still holds the string, and on a local read from an
immutable field (each must copy, and the caller's or the field's string must be unchanged); `print` of U+DC80 (the raw byte) and of U+D800 (the arguments before it written, the error line, status 1); leaks
under LeakSanitizer and use after free under ASan for strings in class fields, lists of strings and region objects;
a mutant per table (one wrong entry must fail its exhaustive test); the acyclic and string-free programs generate the
C they generated before (no `tp_aot_str.h`).

**Order.** S1a: representation, literals, operators, `len`, indexing, slicing, `in`, iteration, `print`, f-strings,
`str()` / `repr()`, `ord`, `chr`, `sys.argv[i]`, `int(s)`, `sys.std*.write`. S1b: the methods, the generated Unicode
tables, the minimal `list[str]` and `str` fields of classes (the containers of a str: they share the owned-field and
region-walk work). S2 starts with `str | None` (it needs `is not None` narrowing in the front end, which S2's optional
containers need too) and the Windows console. Each step is one PR; CI runs per group (S1a+S1b, S2+S3, `__del__`+T1;
leader, 2026-11-21).

**As implemented in S1a (agent proposal, not owner-confirmed).** A STR value is `ir.Type.STR` (AOT mode only; the
default mode never produces it) and every operation is its own IR node (`StrConcat`, `StrRepeat`, `StrCompare`,
`StrContains`, `StrLen`, `StrIndex`, `StrSlice`, `StrOf`, `StrOrd`, `StrChr`, `ArgvStr`, `StrParseInt`,
`StrWrite`, `ForStr`, and an `FString` of type STR). The emitter follows the TAGGED ints: a str name and each str
temporary own one reference, an operation writes an emptied temporary only on success, and the cleanup point
releases them; a parameter is borrowed, and one the body assigns works on an owned copy, so every name owns its
reference and `+=` may grow in place exactly when the count is 1. Indexing and iteration of Latin-1 text allocate
nothing (256 immortal one-character strings, as CPython caches them). `print` writes a literal without a surrogate as
its UTF-8 bytes, as before, and an f-string of numbers piece by piece, as before; an f-string with a str field,
`!r`, or text with a surrogate is made in full first, as CPython makes it, so nothing of it is written when it
cannot be encoded. `repr` uses the printable table tp_aot_int.h already has (`int()`'s message); S1b generates the
tables from the reference interpreter. The Windows console (a console, not a pipe) is the one open output question:
CPython writes a console through WriteConsoleW, and this step writes UTF-8 bytes through the C runtime, which a
pipe (every test) receives exactly as CPython's; S2 settles the console with a test on the Windows job.

#### 4.3.2 Tier S step 2 (PR-S2a, S2b, S2c): str | None, containers that change, dict, set, tuple, bytes

Design for S2 (agent proposal, written 2026-11-22; not owner-confirmed, leader review pending). In 1.0 (owner,
2026-11-21 21:55). Three PRs, one CI group with S3 (leader, 2026-11-21): **S2a** `str | None`, the Windows console,
lists that change and tuple values; **S2b** `dict`, `set` and the hash they need; **S2c** comprehensions, the
iteration builtins, `sorted` / `list.sort`, and `bytes`. Every operation is differential-tested against the reference
with CPython's exact results and messages, as S1; a program without these constructs generates the C it generated
before.

**`str | None` (S2a).** `Type.STR_OPT`, the same `tpa_str *` with NULL for None (so a STR flows into a STR_OPT slot
as it is). Accepted: assignment of a str or None, `x is None` / `x is not None`, `==` / `!=` with a str or None,
passing and returning, `print` / `str` / `repr` / f-string fields (`None` or the string), fields of classes. Every
other use needs a str and is accepted only where the front end proves `x` is not None: inside `if x is not None:`,
after `if x is None:` whose branch leaves (return, break, continue, `sys.exit`, raise from S3), in `a if x is not None
else b`, and in `if x:` / `while x is not None:`; the tests combine through `and`, `or` and `not`. Only a local or a
parameter is narrowed, never a field expression `o.x` (CPython reads the field again, and a call between the test and
the use can rebind it; a program copies the field to a local first). The kill rule as implemented: a narrowing ends at
an assignment of the name (an assignment of a str or of a proved name makes it proved again), and a loop removes, at
its head, every name its body assigns, so `while n is not None: ...; n = n.next` is accepted. `assert x is not None`
is not a narrowing until S3 compiles `assert` (an `assert` is refused today). The read is
then `StrNarrow(x)` (no runtime work; under `-DTP_CHECK_PROVEN` it aborts on NULL, so the sanitizer job checks every
proof). An unproved use is the finding `aot/optional`, naming the line: Pyrefly reports the same program as a type
error, so a checked program never hits it. The same narrowing serves `C | None` class values, which today are read
without a check (a None there is a NULL read in the generated C, undefined behaviour; fixed here: an unproved field
read or method call through an optional class value becomes `aot/optional` too). This refuses programs that compiled
before (AGENTS brief rule 5): the guard is test_aot_tier_s2a.py (the finding, not a crash) and the benchmarks
`binary_trees.py` / `binary_trees_region.py`, which test their locals and still generate the same C.

**Answers to the S2 design review (fable-pmp, 2026-11-22; agent proposal, not confirmed).**
*B1, lengths that change.* A list kind takes the growable layout only when some function changes the length of a list
of that kind (above); the other kinds keep the tier K layout and every tier K proof and the measured kernels as they
are. For a growable kind: (1) the back end never keeps a list's `d` pointer or length across statements (every element
access reads `l->d` and checks `l->len` at its own point, and a `len()` of a growable kind is read in evaluation order);
(2) the verifier proves no bound for a list kind that any function can shrink (pop, clear, remove, and later `del` and
slice assignment), through any alias, so such an access keeps its check; growing keeps every proof, since a length only
rises; (3) a store evaluates its value before the slot is checked (`l[i] = f()` raises `IndexError: list assignment
index out of range` when `f()` popped); (4) the self-referential operations copy from the moved block
(`l.extend(l)`, `l += l`, `l.append(l.pop())`, `l.insert(0, l[-1])`, and slice assignment `l[a:b] = l` when it comes),
each with a named test; (5) under `-DTP_CHECK_PROVEN` (the sanitizer job) every resize allocates a new block and frees
the old one, so a stale item pointer is a use after free AddressSanitizer reports every time. The AOT back end has no
parallel loop (N-15 belongs to the default mode), so no partition by length exists to invalidate.
*B2, the library's thread promise.* Option (a) of the review: a list kind the library resizes cannot cross the export
boundary (`aot/library-resize`, at the export) until tier T adds per-list locking; a kind resized only inside the
library takes the growable layout there and never reaches the host. The boundary layout and the header's promise
("the list never changes length", `_data` valid while the handle is held) stay true, so the ABI version stays 2.
*B3, tracked containers at the boundary.* A container type the cycle collector tracks (below, B4) is refused at the
export boundary with `aot/library-mutable-class` until tier T, as a mutable class is: the collector's state is
thread-local under `TPA_THREADS`. No parallel body exists in the AOT mode (B1).
*B4, containers in the cycle analysis.* Containers become nodes of the type graph of 6.3.1 (`list[T] -> T`,
`tuple[...] ->` each member, `dict[K, V] -> K, V`, a class `->` its field types); a list or dict node is never
immutable-after-init (it changes without any field assignment); the cyclic-capable set is, as in 6.3.2, every member of
a component that has a cycle and a mutable member. So `children: list[Node]` makes `Node` cyclic-capable, while
`list[list[int]]` and `list[Leaf]` (scalar fields) are not tracked. An element of an owned kind (big int, str, class,
list, tuple, dict) read out of any container is a new reference, whatever field holds the container (elision rules 3
and 4 of 6.3.1 never apply through a container); `pop` moves its reference out.
*N1, NaN.* CPython's `in`, `index`, `count`, `remove` and list `==` test identity before equality, which unboxed
doubles do not have. The AOT mode refuses those operations on a float list or a container holding floats
(`aot/identity`) instead of answering differently; ordering (`<`) and printing are unaffected.

**Windows console (S2a).** When standard output or error is a console (`GetConsoleMode` succeeds on its handle),
the text is written as UTF-16 with `WriteConsoleW`, as CPython's `_WindowsConsoleIO` does; the encoded UTF-8 is
decoded first, so a raw surrogateescape byte becomes U+FFFD, as it does in CPython. A pipe or a file gets the UTF-8
bytes as now. The runtime already includes `windows.h` on Windows (lean), so the console functions are the system's declarations; stdio is flushed before each console write, so text and numbers keep their order.
Test: the Windows job runs a program under a pseudo-console where the runner allows it; elsewhere the decision rests
on the pipe tests and the code review.

**Lists that change (S2a).** A list that can change size has the runtime layout `{count, length, capacity, kind,
items *}`: the items live in a separate block, so `append` / `insert` / `extend` / `pop` / slice assignment can move
them while every name of the list keeps the same handle (CPython's aliasing: `a = b; b.append(1)` changes `a`). `kind`
says what an item slot holds: an IEEE double, an int64 (RAW group), a `tpa_int` (big-capable group, owned), a str, a
class instance, a list, a tuple (owned references). Which lists take this layout is decided per list type for the
whole program (a list can only flow into names of its own type, so every alias of a list has its layout): when any
operation of the program changes the length of a `list[float]`, every `list[float]` takes the growable layout, with
element access `items[i]` and the same bounds rule; likewise for `list[int]` (both the RAW and the big-capable kind),
`list[str]` and each other list type. A list type the program never resizes keeps the tier K layout (`{count,
length, d[]}`, element access `d[i]`). (Per list group, as `aot/rep.py` groups `list[int]` names, would be finer; it
can replace this rule later without a visible change.) So a program that never resizes a list generates the C it
generated before, and the tier K loops (nbody, spectral_norm) keep one load per element. (Amendment 2026-11-22,
agent proposal, not confirmed: the first text of this section moved every list to the new layout, which contradicted
the "generates the C it generated before" rule above and added an indirection to the tier K loops.) Growth by CPython's
over-allocation formula (`list_resize`), so `sys.getsizeof` would agree (not exposed; the formula only sets the
amortised cost). Operations: `append`, `pop()` / `pop(i)` (`IndexError: pop from empty list`, `pop index out of
range`), `insert` (CPython's clamping), `extend` of a list, `remove` (`ValueError: list.remove(x): x not in list`),
`index` (`ValueError: list.index(x): x not in list`, the message of CPython 3.14 and later, which does not
format the value; so `index` of a class instance needs no repr), `count`, `clear`, `reverse`, `copy`, `+`, `*`, `+=` (extends in place:
the same object), `==` / `!=` / `<` ... (element-wise, CPython's rich comparison order), `in`, slicing and slice
assignment (`ValueError: attempt to assign sequence of size N to extended slice of size M`), `del l[i]` and
`del l[a:b]`, `list(xs)` copy, `print` / `repr`. Iteration follows CPython's list iterator: index and length read at
each step, so appending inside the loop is seen. Element types: int, float, bool, str, `str | None`, compiled classes,
lists, tuples, dicts and sets (S2b). A list of lists is a list whose items are lists (owned references); a cycle
through lists is possible (`a.append(a)`), so a list that can hold a list or a class is tracked by the cycle
collector of tier O, like a cyclic-capable class (6.3.2 step 3). Library ABI: the list handles stay opaque; `_data`
returns the item block, valid until the next call that may resize the list; ABI version 2.

**Tuples (S2a).** A tuple value of a fixed shape `tuple[int, str, ...]` (the shape in the type; no `tuple[T, ...]` of
variable length until S2c) is an immutable counted object with one slot per element and the slot kinds known
statically. Construction, indexing by a constant or a variable (bounds checked, `IndexError: tuple index out of
range`), unpacking, `len`, `==` / ordering, `in`, hashing (S2b), `print` / `repr` (`(1,)` for one element). The fixed
tuple returns of tier K stay as they are (an unboxed multi-value return), and become values only where a tuple is
stored, passed or compared.

First step, as implemented (amendment 2026-11-22, agent proposal, not confirmed): a tuple of int, float, bool and str
held by a local or a parameter of a module function has no observable identity in the accepted subset (no `is`, never
in a container or a field), so the front end splits it into one local per element on the AST it lowers, after Pyrefly
has typed the source (`typedpython/tuples.py`); the IR, the verifier and the back end see the element locals, the
tier K multi-value return and one node, `TupleIndex`, for a variable index. Covered: displays, `t = u`, `t = f(...)`,
`t[k]` for a constant k, `t[i]` for any int index of a tuple whose elements share a type (`TupleIndex`: the index, its
slot by CPython's rule with `IndexError: tuple index out of range`, then the chosen element local), `len`, unpacking and
`a, b = b, a`, `==` / `!=` / `<` ... (CPython's first differing item, then the lengths), `in`, `for v in t` when the
elements share a kind, `print` / `str` / `repr` / f-strings, passing a tuple name or display to a tuple parameter, and
returning a tuple name or a tuple-returning call. A slice `t[a:b:c]` whose bounds are absent or int constants
(amendment 2026-11-25, agent proposal, not confirmed) has a static shape, so it is the tuple of the element locals it
takes (positions by Python's own slice rules: negative bounds, bounds past either end, a negative step, an empty
result printed `()`), accepted wherever a tuple name is (bound, unpacked, passed, returned, compared, searched, `len`,
printed). Refused with the reason: a variable index into a tuple whose element types differ (its type is a union,
which the AOT mode does not type), a slice with a bound that is not an int constant (its length is known only at run
time, a `tuple[T, ...]`, S2c) or a step of 0, `is`, a tuple
anywhere else as a value, a comparison or `in` with a float item (CPython tests identity first: NaN, `aot/identity`),
a tuple parameter of a method, and a tuple parameter at the library boundary (`aot/export`). A returned tuple may hold
str elements (each a new reference the caller owns through its out slot); such a function is not exported from a
library yet (the Kotlin result class holds scalars).

Second step, lists of tuples (amendment 2026-11-23, agent proposal, not confirmed): a `list[tuple[int, str]]` local or
parameter of a module function whose elements are int, float or str is split the same way, into one list per element
position (`L__0: list[int]`, `L__1: list[str]`). A tuple read out of the list is an immutable value with no identity in
the accepted subset, so the parallel lists hold what CPython's list holds; every alias of the list (another name, a
parameter) holds the same parallel list objects, so a change through one is seen through all. Each list operation is
applied to every element list in turn, with the index and the values evaluated once, first, in CPython's order; one
that fails (IndexError) fails on the first element list, before any changed. Covered: a display, `[]`, another such
list, `+`, `+=`, `*`, a slice, `list(L)`; `append`, `insert`, `extend`, `pop` / `pop(i)` (a statement, bound or
unpacked), `clear`, `reverse`, `L[i] = t`, `del L[i]`, `del L[a:b]`; `len`, `L[i]` bound, unpacked, compared and indexed
by a constant (`L[i][0]` is `L__0[i]`); `for t in L` and `for a, b in L` (CPython's list iterator: the length read at
each step, an item appended in the body is visited); `print` / `str` / `repr` / f-strings (the repr is built in a loop
before the statement, so such a statement may call nothing else); passing to a `list[tuple[...]]` parameter of a module
function; `t in L`, `not in`, `L.count(t)`, `L.index(t)`, `L.remove(t)` (a loop over the element lists comparing each
item with `t` element by element, as CPython's tuple `==` does; the ValueError of `index` and `remove` is CPython's own,
raised by the same method of an empty `list[int]`, since an AOT program has no `raise` yet), and `==`, `!=`, `<`, `<=`,
`>`, `>=` of two such lists (`==` of each element list; an ordering finds the first index where the items differ, then
compares the lengths there or the two items as tuples). A search or an ordering is computed before its statement like a
repr, so the statement calls nothing else, a `while` condition may not hold one, and the tuple searched for is made of
names and constants. Refused with the reason: a bool element (there is no list of bool yet), a float element in a search
or a comparison (CPython tests each pair by identity first, a NaN equals itself there: `aot/identity`), sorting (S2c) or
returning such a list, and one in a field or a method parameter: these come with tuple objects.
The same change gives `xs: list[int] = []` and `list[float]` (the creation `[v] * 0`), and `words[i] = s` for a list of
str (the new item held before the old one is released; `IndexError: list assignment index out of range`).
`list[str | None]` (amendment 2026-11-23, agent proposal, not confirmed): the list[str] layout (`tpa_str_list`) with
NULL for a None item, a type of its own (`STR_OPT_LIST`) because lists are invariant: a list[str] name is never passed
or bound where a list[str | None] is wanted, or the callee could store None into the caller's list of str (refused
with that reason); a display of str and None, or of str only, bound to one is one. An item reads as a `str | None`
(narrowed by `is None` / `is not None` as any other); stores, the list methods, `+`, `*`, slices, `list(xs)`, loops and
`len` are the list[str] ones. What reads an item's value has its own runtime function: `in`, `count`, `index` and
`remove` compare as `str | None` `==` does (None equals only None, CPython's identity-or-equality gives the same),
`==` / `!=` likewise, an ordering that reaches a None item raises CPython's `TypeError: '<' not supported between
instances of 'NoneType' and 'str'` (the operator and the two types in the order written), and `repr` prints `None`.
`sep.join(xs)` stays refused (`list[str]` is what join takes), and so does a list[str | None] field.
Tuple fields and method parameters (amendment 2026-11-25, agent proposal, not confirmed): a class field
`p: tuple[int, str]` (elements int, float, bool, str; also named in `__slots__`) is split into one field per element,
`p__0: int` and `p__1: str`, for the same reason (no identity in the accepted subset, so the fields hold what the one
field holds). `o.p` is rewritten by the attribute name alone, so a name is split only when every declaration of it in
the program's classes is a tuple field of one shape (Pyrefly has checked that `o.p` names a declared attribute); any
other tuple field leaves its class refused with the field's type. A field is read through a local name (`o.p`, `self.p`)
wherever a tuple name is accepted (bound, unpacked, passed, returned, compared, searched, sliced with constant bounds,
`len`, printed, indexed by a constant, or by a variable when its elements share a type and the index is a name or a
constant: `TupleIndex` evaluates the index before the elements, and CPython reads the field first, which an index
expression with a call could rebind), and stored by `o.p = value` (every element of the value evaluated first, then
each element field stored). A tuple parameter of a method is split as a module function's, and so is every argument
of a call `o.m(...)`: by the method name alone, so only when every declaration of `m` in the program's classes is a
method with the same parameter types; a tuple parameter of `__init__` likewise through the call `C(...)`. Refused with
the reason: a tuple field used as a value anywhere else (`for v in o.p`), a method whose name is declared differently
elsewhere, a list of tuples field or method parameter. Classes cross the library boundary as opaque handles with no
field accessors, so a split field changes nothing there.
A method declared `-> tuple[...]` returns its elements through out slots as a module function does; `a, b = o.m()`,
`t = o.m()` and `return o.m()` take them, by the method name alone when every class declares `m` with that return.
Still to come in S2a: tuple objects where a tuple must be one value (returning a list of tuples, a list of tuples in a
field or a method parameter; dict keys in S2b).

**dict and set (S2b).** `dict[K, V]` with K int, str, bool or a tuple of those; V any S2 type. The layout is CPython's
compact dict (an index table of slots and an entry array in insertion order, open addressing with CPython's
perturbation probe), so iteration order is insertion order, as CPython guarantees. The hash function is not CPython's:
the str hash is seeded per process in CPython and so is unobservable (`hash()` stays `aot/identity`), and the int
hash only decides slot positions, never an order the program sees; equality is CPython's (`1 == True`, so a
`dict[int, ...]` keyed by `True` finds `1`, and the stored key keeps the type it was first inserted with, as CPython
keeps the first key object). Operations: `d[k]` (`KeyError: <repr of k>`), `d[k] = v`, `del d[k]`, `in`, `get`
(with default), `setdefault`, `pop` (with default), `keys` / `values` / `items` in loops and comprehensions,
`len`, `update` from a dict, `==`, `print` / `repr`, iteration (`RuntimeError: dictionary changed size during
iteration` exactly where CPython raises it). `set[K]`: `add`, `discard`, `remove` (`KeyError`), `in`, `len`, `|`
`&` `-` `^` into new sets, `==`; iterating a set, or printing it, depends on CPython's hash table and seed, so it is
`aot/set-order` except through `sorted(...)`, as section 4.3 says.

First step of S2b, as implemented (amendment 2026-11-25, agent proposal, not confirmed). The runtime
(`runtime/tp_aot_dict.h`) mirrors Objects/dictobject.c of CPython 3.14 and later rule for rule: the table sizes
(`calculate_log2_keysize` is the smallest power of two of at least `max(minsize, 8)`; 3.13 rounded `minsize | 8`
up, which gives other tables after a copy or an update), the presize of a display, the insertion resize, deleted
entries left in place until a resize compacts them, `popitem` truncating the entry count, `clear`, the clone and
merge paths of `copy` and `update`, and the iterator's two checks with CPython's messages. A test runs thousands of
random operation scripts on the runtime and on CPython's dict and compares what each prints, live iterators
included (`test_aot_dict_runtime.py`). The front end compiles `dict[K, V]` with K int, str or bool and V int, float,
bool, str or `str | None`, as locals, parameters and returns of module functions: displays, `d[k]`, `d[k] = v`,
`del d[k]`, `in` / `not in`, `get` (with a default; without one for str values), `setdefault`, `pop` (with and
without a default), `len`, truth, `update` from a dict, `copy()` and `dict(d)`, `clear()`, `==` / `!=`, `print`,
`str`, `repr` and f-strings, and `for` loops over the dict, `keys()`, `values()` and `items()`. A dict holds its
ints as `tpa_int` (any int; aot/rep.py), so a big int is a key like any other. A display is built as CPython's
bytecode builds it (chunks of 17 pairs; a chunk of at most 15 is presized, a longer one starts empty, later chunks
are merged), so a loop that changes the dict sees CPython's layout. `print` makes the repr of a dict (and of a list)
argument when it writes it, as CPython's print does. Refused with the reason: a value of another type than the
dict's (CPython keeps the object, so a bool in a `dict[str, int]` or an int in a `dict[str, float]` would print
differently), a bool key inserted into a dict of int (a loop would give a bool where the program reads an int; a
bool may look a key up), `==` of dicts of float values (CPython tests identity first: `aot/identity`), and a dict of
other kinds passed where a dict is wanted (dicts are invariant). Dict fields of classes, and dicts as method
parameters and returns, follow (same day): a field read of a dict is held for the operation, as a list field's, and
a class with a dict field is always a heap object. `popitem()` follows: unpacked into two names (`k, v =
d.popitem()`), bound to a tuple, returned from a function declared `-> tuple[K, V]`, or as a statement; it takes the
last entry, truncates the entry count as CPython does, and raises `KeyError: 'popitem(): dictionary is empty'`. A
dict may then be a dict's value (`dict[str, dict[int, int]]`, any depth): `d[k][j] = v`, `d.setdefault(k, {})[j] = v`,
methods and loops of `d[k]`, and an inner dict bound to a name is the same object (aliasing as in CPython). Its type is
a part of the outer type, so it cannot hold the outer dict back: no cycle, and reference counts free it. `d[k] op= v`
reads the dict and the key once, then `d[k]`, then the value, as CPython. A `list[dict[K, V]]` follows: a list of
counted dicts (the runtime's list of owned references), as a local, a parameter, a return and a field; displays (`[]`
where the type is known), `append`, `insert`, `pop`, `extend`, `clear`, `reverse`, `xs[i]` (the dict itself) and
`xs[i] = d`, `xs[i][k] = v`, `len`, `for d in xs` and the repr. Searching one (`in`, `index`, `count`, `remove`) compares
dicts by value with an identity test first, and is refused until it is done; so is `==` of two lists of dicts. Tuple
keys follow (`dict[tuple[int, str], V]`, elements int, str or bool): a key tuple is a counted runtime tuple made from
its elements in order (`d[a, b]`, `d[t]`, `t in d`, `get` / `pop` / `setdefault`), with CPython's equality and repr
(`(1, 'a')`, `KeyError: (5, 0)`); a loop or `popitem` binds the key to a tuple name (split into its element locals, as
any tuple) or to one name per element (`for (i, j), v in d.items()`). An element of another type than the key's (a bool
for an int) is refused: the tuple would keep it. `set[T]` follows (T int, str, bool or a tuple of them): a dict whose
values are unused, as a local, a parameter, a return and a field; displays, `set()`, `set(s)`, `add`, `discard`,
`remove` (`KeyError(x)`), `clear`, `copy`, `update`, `in`, `len`, truth, `==`, the subset tests (`<=`, `<`, `>=`, `>`,
`issubset`, `issuperset`, `isdisjoint`) and `|`, `&`, `-`, `^` (and the one-set `union`, `intersection`, `difference`,
`symmetric_difference`). What depends on the order of a set (a loop over one, its repr, `pop()`) follows CPython's set
table, which this one is not: refused as aot/set-order. The view objects follow where they are used at once:
`len(d.keys())`, `x in d.keys()`, `v in d.values()`, `(k, v) in d.items()` (CPython's dictitems_contains; float values
refused, aot/identity) and the repr `dict_keys([...])` / `dict_values([...])` / `dict_items([...])`, made when print
writes it. A view may then be bound to a name (`ks = d.keys()`, and `values()`, `items()`; agent proposal, not
confirmed): the name holds the dict itself (a counted reference, `ir.DictView`), so the view shows the dict as it is
when it is used and keeps that dict when the dict's name is bound again, as CPython's view object does. A view name is
used as the views above are, and by a `for` loop (CPython's dict iterator, so a dict that changes size raises), its
truth (`if ks`, `not ks`, `bool(ks)`), and binding it to another view name of the same view and kinds. Anything else of
a view name (subscript, a dict method, `==`, the set operators, `list()` / `dict()` of it, putting it in a list or a
dict) is refused naming the view. A view is also passed to a function and returned from one (agent proposal, not
confirmed): the parameter or result is annotated `KeysView[K]`, `ValuesView[V]` or `ItemsView[K, V]`, imported by
`from collections.abc import KeysView, ValuesView, ItemsView` (the one `from` import of an AOT program, for annotations
only), and it holds the view's dict as a view name does; a view of a dict made in the callee keeps that dict alive. A
KeysView does not name the values of its dict, nor a ValuesView its keys, and the view reads only its half, so a
`KeysView[str]` parameter takes the keys of a `dict[str, int]` and of a `dict[str, float]` alike (`ir.view_match`).
The argument is a view of that half (`d.keys()`, a view name, a call of a function returning one); a dict or another
view is refused naming both views. A call returning a view is used as a view is (a `for` loop, len(), `in`, its truth,
the repr, binding it to a view name, passing it on). A method returns a view the same way (`def names(self) ->
KeysView[str]: return self.items.keys()`, called as `obj.names()` on a local of the class, the call method_call
makes directly); a view does not cross the library boundary (aot/export). In a library an export may bind views of its dict
parameter: a view name is only read, so it is no write (aot/library-dict-write). `set(xs)` of a list[str] or list[int] name and `set([a, b])`
follow too. A dict crosses the library boundary as `dict[int, int]`, `dict[int, float]` and `dict[int, bool]` (8.1 to
8.3, ABI version 3): the library only reads a dict parameter of an export (aot/library-dict-write otherwise, through
the callees it is passed to), so one handle may be shared by threads without the per-dict lock of tier T. What S2b
listed is in place (agent proposal, not confirmed).

**S2c.** List, dict and set comprehensions (a hidden local per comprehension variable, Python 3 scoping, the
accumulator built in place). First step in (agent proposal, not confirmed): a comprehension that is the whole value
of an assignment to a name or of a `return` is rewritten into the loop it means before lowering
(typedpython/comprehensions.py). Each variable becomes a hidden local typed by Pyrefly at a probe (a
`[(x, y,) for x in xs for y in ys]` put before the statement in the copy Pyrefly types, the clauses without their
conditions, so a variable has its declared type). The accumulator is hidden and typed as its target, so the target is
bound once the comprehension is complete and `xs = [x + 1 for x in xs]` iterates the old list. The first iterable is
evaluated first, and a dict comprehension's key before its value (through a temporary of the key type). A
comprehension anywhere else (an operand, an argument) is refused as "bind it to a name first", and so are `:=` and a
comprehension, generator or lambda inside one. Second step in (agent proposal, not confirmed): `enumerate` and
`zip` as the iterable of a `for` loop or of a comprehension clause (typedpython/loops.py), each unpacked into names.
`for i, x in enumerate(it, start)` takes any iterable a loop takes and an int constant start; it is the loop over
`it` with a hidden counter bound to `i` before the body, so a `continue` counts and `i` keeps its last value after
the loop. `for a, b in zip(xs, ys)` takes names of lists or strs (or a str constant) that the loop does not rebind;
it reads each by index while the index is below every length, the lengths read at each step left to right, so a
list that grows in the loop is seen growing as CPython's list iterator sees it. zip's names are typed as the
elements of the operands, from the type Pyrefly gives each operand in the `for` header. A dict or a set operand
is refused (zip reads by index), and so are `strict=`, a single name as the target, and enumerate or zip anywhere
else. Then `zip` of other iterables, `reversed`, `range` objects as iterables, in `for` loops and comprehensions; `sum`, `any`, `all`, `min` / `max` of an iterable (`ValueError: min() iterable argument is
empty`); `sorted(xs, key=..., reverse=...)` and `list.sort(...)` with a key that is a compiled function or a
lambda written in place: CPython's own algorithm (timsort, `listsort` in Objects/listobject.c, ported as it is), so
the order of equal and of unordered elements (NaN) is CPython's, not merely some stable order. `bytes`: an immutable
byte array; literals, `s.encode()` / `encode("utf-8")` (strict: a surrogate raises CPython's UnicodeEncodeError),
`b.decode()` (strict UTF-8, CPython's UnicodeDecodeError message), `len`, indexing (an int), slicing, `+`, `*`, `==`,
`in`, iteration (ints), `print` / `repr` (`b'...'` with CPython's escapes), `bytes(n)` and `bytes(list[int])`
(`ValueError: bytes must be in range(0, 256)`).

**Tests.** `test_aot_tier_s2a.py`, `_s2b.py`, `_s2c.py`: differential over generated cases (random operation
sequences on lists and dicts checked against the reference, every error message), aliasing (two names of one list,
a list in a class field, a list in a list), the in-loop mutation rules, the cycle collector on list cycles under
`TPA_GC_STRESS`, LeakSanitizer on everything, the library ABI v2 fixture, and a timsort mutant (one changed
`MIN_GALLOP` must change the order of a NaN-holding list).

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
    released via a tail loop (`while (p != NULL)`). Every other field of a class type is released by a
    call, which nests; so the release of a class that has such a field counts its nesting depth on a
    thread-local counter, and past `TPA_TRASH_LIMIT` (1000) it parks the object (count zero, untracked)
    with the class's `_drop` function on a thread-local list that the outermost release drains (review of
    #508, F1; CPython's trashcan). A chain of any length through any field, or alternating between
    classes, is then freed with bounded C stack depth; only the order of frees beyond the limit changes,
    which nothing observes (AOT compiles no `__del__`, a freed object is unreachable). A class with no
    field of a class type keeps its release unchanged. Tests: `test_aot_release_depth.py` (500k chains,
    a mutant without the bound).
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
  Any class that fails both conditions was a compile error (`aot/cycle-possible`, naming the class and
  the mutating field store outside `__init__`) until PR-O2c added the small cycle collector (6.3.2 item
  3, "Step 3 as implemented"): such a class now compiles, tracked by the collector.
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
  C-ABI class handles are not yet exported in PR-O1, and an `aot-lib` export whose parameter or return
  type is a class that is not transitively immutable-after-init (checking every class reachable from
  its fields) is a compile error (`aot/library-mutable-class`).
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

### 6.3.2 Tier O step 2 (PR-O2): virtual objects, scope regions, cycle collector, and acyclic `__del__`

Design for PR-O2 (`binary_trees_region.py`, `binary_trees.py` region allocation, mutable cyclic classes,
and acyclic `__del__`; agent proposal, leader review 2026-11-20, not owner-confirmed).

Each allocation site (`New`) is classified statically in the following order. A class that defines
`__del__` skips steps 1 and 2 and always uses step 3 with exact reference counting so destructor timing
matches CPython.

1. **Virtual objects (N-12a, scalar replacement).**
   - *Conditions*: the instance never escapes its creating function (not returned, not stored in any
     field or container, not passed to another function), is not compared with `is` / `is not`, is not
     merged with another instance across a branch join (phi), and defines no `__del__`.
   - *Method calls*: a method call on a candidate virtual object is allowed **only if** the method is
     non-recursive and inlined at the call site; any non-inlined method call or recursion disqualifies
     the site and falls back to heap allocation (step 2 or 3).
   - *Layout*: no heap allocation and no C struct; each field is a plain C local variable, with owned
     fields following normal local cleanup.
2. **Scope bump regions (N-12b/c).**
   - *Layout*: region-allocated instances use a distinct C type `struct tpa_reg_<C>` with **no `rc`
     header** (zero reference counting). A region (`tpa_region`) owns a linked list of bump-allocated
     chunks and frees every chunk at once when its owning scope exits. Because `struct tpa_reg_<C> *`
     and `struct tpa_cls_<C> *` are incompatible C pointer types, mixing them is a compile-time error.
   - *Lifetime and non-escape invariants (dangling pointer prevention)*: an allocation site is promoted
     to a scope region `R` only when all five rules hold; violating any rule falls back to heap RC:
     - **(R1) No return escape**: the function owning `R` cannot return any pointer into `R` (or any
       struct/tuple containing one).
     - **(R2) No outliving store**: a pointer into `R` cannot be stored into any heap object, global
       slot, container, or **any other region (including an enclosing/outer region)** whose lifetime
       exceeds `R`. Storing an outer-region pointer into an inner-region object is safe and permitted,
       because the outer region outlives the inner region; the reverse direction is forbidden.
     - **(R3) Callee restriction**: a pointer into `R` may only be passed to non-escaping region
       specializations (e.g. `check__reg(tpa_reg_Node *n)`) that themselves satisfy (R1) and (R2).
     - **(R4) Destructor-free fields**: every field of a region-allocated class must be a scalar
       (`int64_t`, `double`, `bool`) or a pointer to a class in the same region `R` (or an enclosing
       region), so freeing `R`'s chunks requires zero per-object destructors and cannot leak heap
       references.
     - **(R5) Library boundary**: `aot-lib` exports never expose region pointers; a library may use a
       region only for a closed scope created and destroyed entirely inside an exported call.
   - *Exception safety*: every region-owning function frees `R` exactly once in its single `tp_cleanup:`
     exit block, covering both normal return and error (`rc != 0`) paths. Exceptions in AOT carry only
     `(class_id, message_string)` in the thread-local error record (`tpa_exc`), with no traceback frame
     objects (owner decision D4), so a region pointer can never be retained by an exception or traceback.
   - *Step 2 as implemented (PR-O2b, N-12b/c; agent proposal, not owner-confirmed)*: regions are
     intraprocedural. The unit of the decision is `(F, C)`: every non-virtual site of `C` in `F` and every
     local of `C` in `F` are region-typed together (`struct tpa_reg_<F>__<C>`, one region per `(F, C)`),
     or none is, so a local has one C type. R1 and R2 as written (a store into a virtual object counts as
     a heap store); R3 in its strict form, no callee at all (region specialisations such as `check__reg`
     are a later step, so `binary_trees.py` stays heap-rc with the reason reported); R4 relaxed: a region
     object's field of class `K` is a region pointer when `(F, K)` is region and an owned heap pointer
     otherwise, and lists and big ints are owned heap references; a generated per-class walk releases
     them when the region is freed or reset, which keeps "no heap object points into a region" and adds
     "a region object's heap references are released". The scope is the function body or one iteration of
     one loop (the innermost loop whose body holds every use of `C`, in which every local of `C` is
     definitely assigned before it is read, widened to the loop of any region object that holds pointers
     of `C`); a per-iteration region is reset at the top of each iteration and freed at `tp_cleanup:`,
     so `break`, `continue`, `return` and a failing operation need no path-specific code. Heap pointers of
     `C` flowing into `F` (a parameter, a call result, a field of a heap or virtual object) assigned to a
     local of `C`, or compared with `is` against a region pointer, make `(F, C)` heap-rc. A class with
     no fields is never region (review of #501): its region struct would be empty, every
     `tpa_region_alloc` of size 0 returns the same address, so `a is b` would be true for two objects
     (and MSVC rejects an empty struct); heap-rc is correct and costs nothing anyone measures. The
     expression walkers of `aot/alloc.py` and `aot/classes.py` share one list of child attributes
     (`EXPR_CHILD_ATTRS` / `EXPR_CHILD_SEQS`), so a node such as `NewArray` (`length`, `fill`) or
     `CallObject` (`callee`) cannot be skipped by one walker and seen by another.
3. **Reference counting + small cycle collector (D2) + acyclic `__del__`.**
   - *Pay only for what the program uses*: classes proved acyclic by the shared `aot/classes.py`
     analysis (PR-O1) keep zero GC overhead and do not link `runtime/tp_aot_gc.h`. Only when a program
     contains at least one potentially cyclic mutable class is `tp_aot_gc.h` included, and only those
     cyclic-capable classes carry the GC list header (`prev`, `next`, `gc_refs`).
   - *Safepoint invariant (borrowed references and RC elision)*: the trial-deletion collector requires
     that every live cyclic-capable object has at least one counted reference (`rc > 0`) at the moment
     collection runs. Collection is triggered **only at allocation safepoints** (entry to
     `tpa_cls_<C>_new` of a cyclic-capable class, after all constructor arguments have been retained as
     owned temporaries or are held by owned locals/fields in callers). Because every cyclic-capable
     class is mutable (not `immutable-after-init`), PR-O1's RC elision rules (3) and (4) are
     automatically disabled for every field of such a class: every field read into a local and every
     mutable field passed as a call argument is `retain`ed by the caller before any call. Therefore,
     at every allocation safepoint, no cyclic-capable object is kept alive solely by an uncounted
     borrowed reference.
   - *`__del__`*: permitted only on acyclic classes (always exact RC, no virtual/region/elision). A
     `__del__` on a cyclic-capable class is a compile error (`aot/cycle-del`).
   - *Step 3 as implemented (PR-O2c; agent proposal, not owner-confirmed)*: the cyclic-capable classes
     are every class of a strongly connected component of the type graph (edges `C -> D` for a field of
     type `D` or `D | None`) that has a cycle and at least one member not immutable-after-init: the
     whole component, not only its mutable members, because trial deletion subtracts only the references
     between tracked objects, and an untracked class on a cycle would hold a reference that is never
     subtracted (a root), so the cycle would never be found. An untracked class cannot be on a cycle (its
     component has none, or is immutable-after-init throughout); its reference to a tracked object is a
     root while it lives, and it is freed by its count. The header is `tpa_gc_head` (`runtime/tp_aot_gc.h`:
     `gc_prev`, `gc_next`, `gc_refs`, and `gc_type`, the class's traverse and clear functions, one word the
     list above did not have), the first member of `struct tpa_cls_<C>` before `rc`, so the collector reads
     the count through the common prefix `tpa_gc_object`. A region struct `tpa_reg_<F>__<C>` and a virtual
     object of a cyclic-capable class are unchanged: a region pointer is never counted and never reaches
     the heap (R2), so a cycle inside a region is freed with the region, and a region object's or virtual
     object's heap fields are counted (PR-O2a/b), so they are roots. The collector is CPython's one
     generation of trial deletion over the list of every tracked object alive: `gc_refs = rc` (an atomic
     load under `TPA_THREADS`); for every tracked object, `traverse` visits its fields of cyclic-capable
     classes and decrements each referent's `gc_refs`; an object left with `gc_refs > 0` is a root
     (something outside the tracked set holds it) and the objects it reaches are moved back to the live
     list; the rest are garbage: each is held by one extra count while its `clear` releases every owned
     field (tracked objects, acyclic objects, lists, big ints; the slot is emptied before the release) and is
     freed when its count reaches zero (at once, or when the garbage that still held it is cleared). The
     pass repeats while it freed something (it ends with a pass that frees nothing): an untracked object
     that garbage owns is freed by its holder's `clear` and releases the tracked objects it held, a
     reference that was a root in the pass that freed it, so a cycle held only through such an object
     (`A -> U -> B1`, `B1 <-> B2`, `U` untracked) is garbage only for the next pass; the repeat is cheaper than
     tracking every class that reaches a cyclic-capable one, at the price of one extra pass over the
     tracked list whenever a collection frees anything. No user code runs during a collection (no `__del__` in AOT), so it cannot re-enter; a flag guards it
     anyway. The safepoint is `tpa_cls_<C>_new` of a cyclic-capable class, before `calloc`: every
     constructor argument is then an owned temporary, an owned local, a borrowed parameter whose caller
     holds it, or a field of a live object, and `__init__` has not started; `__init__` allocating another
     tracked object sees the half-initialised object as a root (held by `_new`'s own local, fields NULL or
     owned). The elision rules (3) and (4) are off for every field of a cyclic-capable class
     (`ClassAnalysis.field_elidable`), and `aot/cycle-del` is reported beside the front end's refusal of
     `__del__`. Trigger: tracked allocations net of tracked frees since the last collection reach
     `max(TPA_GC_THRESHOLD, live tracked objects after the last collection)` (2000 by default, CPython's
     first-generation threshold), which bounds the memory held by garbage to one more live set and keeps
     the amortised cost per allocation constant; `-DTPA_GC_STRESS` collects at every safepoint and
     `-DTPA_GC_STATS` prints the counts at exit (tests only). One more collection runs after `__main__`
     returns, when every frame has released its locals and only cycles can remain (CPython's shutdown
     collection; it keeps LeakSanitizer quiet), and the same collection runs on the `sys.exit` path:
     `tpa_exit_status` (`runtime/tp_aot.h`) calls `tpa_exit_hook` once before `exit()`, and the generated
     `main` of a program that links the collector sets it to `tpa_gc_exit` (review of #503, note 2). There
     the frames still hold their locals; those are counted, so they are roots and stay, and nothing reads
     an object afterwards; `tpa_exit_int` reads the status before the hook runs. Step 4 holds every garbage
     object by one extra count before it clears any of them, then drops the extra counts (review of #503,
     note 3): a clear's release of a garbage referent then only decrements it, so no release cascades from
     one garbage object into the next and a long garbage chain through a field that is not the release
     loop's tail field is cleared in constant C stack depth (CPython's `delete_garbage` uses the trashcan
     for the same reason). A garbage object is referenced only by garbage (a reference from an untracked or
     reachable object makes it a root), so after every clear its count is the extra one alone and the
     drop frees it; `-DTP_CHECK_PROVEN` turns the opposite case into a fatal error. Under `TPA_THREADS` the collector's state is thread-local:
     a tracked object is touched only by the thread that created it (`aot/library-mutable-class` keeps
     mutable classes, transitively, out of the library boundary; AOT has no mutable globals), so one
     thread's collection never sees another thread's objects. Tier T's shared objects (section 7) must add
     the shared-object locking before a tracked object may be shared; this collector is not a
     stop-the-world collector. The library output never emitted class structs (before this step), so the
     collector is linked by executables only today; the runtime compiles in both builds.
4. **Diagnostics (`--explain-alloc` / `aot-check`).**
   - Every allocation site's chosen strategy (`virtual`, `region`, `heap-rc`, `heap-gc`) and the
     concrete reason for any fallback from `virtual` or `region` to `heap` (e.g. non-inlined method,
     R1 return escape, R2 outer-region/heap store, R4 heap-owned field, `__del__`) are reported via
     `python -m typedpython aot-build ENTRY.py --explain-alloc` (and surfaced in `aot-check`), so
     measurement runs can verify why an allocation did or did not stay off the heap.
   - PR-O2c: a heap site of a cyclic-capable class is reported as `heap-gc` with, after the virtual and
     region reasons, `[gc: <why the class may form a cycle>]` (the mutating store outside `__init__`, or
     the `self` escape in `__init__`, as `aot/cycle-possible` named it).

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
  at the top of the C text says so). It is 3 since #443 (tier S2b; agent proposal, not owner-confirmed, the leader
  decides the shape): the vocabulary gained `dict_i64_i64`, `dict_i64_f64` and `dict_i64_bool` (8.2), and the
  description a `dicts` object with one entry per dict kind a signature uses (`key`, `value` and the symbols `new`,
  `set`, `get`, `contains`, `next`, `len`, `retain`, `release`); a library with no dict at its boundary has the C text,
  the header and the description it had under version 2, except the `abi_version` number.

### 8.2 Types at the boundary

| Python type | C ABI | Kotlin side |
|---|---|---|
| `int`, `float`, `bool` | `int64_t`, `double`, `uint8_t` | `Long`, `Double`, `Boolean` |
| `int` held as a big-integer-capable value inside the function (#462) | A parameter arrives as `int64_t` and is converted where the compiled function holds it so. A result (also an element of a tuple) is converted back with a fit check: one that does not fit `int64_t` fails the call with -1 and the error record `OverflowError`, message "the int result of `<function>` does not fit in 64 bits", and no out value is written. The same call with smaller inputs succeeds | `Long`; the failed call throws `TypedPythonAotException` as for any other error |
| `str` | pointer and length of UTF-8 bytes; an argument is borrowed for the call, a result is owned by the caller and released with `<prefix>_free` | `String`, copied at the boundary |
| `list[float]` / `list[int]`, as an argument and as a result | an opaque counted handle (`<prefix>_f64_list`, `<prefix>_i64_list`) made and read through exported list functions (`_new`, `_len`, `_data`, `_retain`, `_release`); an argument is borrowed for the call, a returned handle is owned by the caller | a class holding the handle, with `DoubleArray` / `LongArray` copied from or to the `_data` address as the app needs |
| a `list[int]` that the classification finds big-capable (#465), as an argument and as a result | the opaque counted handle `<prefix>_int_list`, with `_new` (n zeros), `_len`, `_retain`, `_release` as above and, because its elements are not `int64_t` in memory, `_get(l, i, &v)` and `_set(l, i, v)` instead of `_data`; both return 0 or -1 with the error record: an index out of range (a negative one counts from the end) is the source language's `IndexError` with its two messages, and `_get` of an element that does not fit `int64_t` fails with an `OverflowError` whose fixed message is "the element of the list does not fit in 64 bits" (nothing is written; after a `_set` of a smaller value the same element reads). Which kind a signature has follows from the classification: a list that crosses the boundary is one group with the outside, so the caller can only pass a handle of the kind the signature names, and a change in a helper that several exports share can change another export's list kind, so a header is regenerated and its callers rebuilt together. The two handle kinds are not interchangeable (a wrong one is memory corruption; only the C types prevent it). `_get` and `_set` take the list's own lock (section 7) | a class holding the handle with `get(i)` / `set(i, v)` (the generator's matching class is the next step) |
| `dict[int, int]`, `dict[int, float]`, `dict[int, bool]` (#443, tier S2b), as an argument and as a result | the opaque counted handle `<prefix>_dict_i64_i64` / `_dict_i64_f64` / `_dict_i64_bool` (the runtime's `tpa_dict`; its int keys and values are always big-capable inside, so there is no raw / big split) with `_new()` (empty; clears the error record), `_set(d, key, v)`, `_get(d, key, &v)` (a missing key is the source language's `KeyError`, message the key), `_contains(d, key)` (1 or 0), `_next(d, &pos, &key, &v)` (the items in insertion order from `pos = 0`: 1, then 0 at the end), `_len`, `_retain`, `_release`; a key or value outside `int64_t` cannot be read (`OverflowError` "the key of the dict does not fit in 64 bits" / "the value of the dict does not fit in 64 bits", nothing written, `_next` past the item); a NULL handle is `TypeError` "argument 'd' is a null dict handle". The library never writes a dict parameter of an export (`aot/library-dict-write`: a store, `del`, a method that changes it, binding it to another name, holding it in a list, field or dict, or passing it to a callee parameter that is not read-only by the same rule; a callee that returns its parameter is not read-only, an export that returns it gives back the same handle), so one handle may be read and passed to calls from several threads at once; the caller builds a dict with `_new` and `_set` before sharing it (a `_set` racing a reader is the caller's to prevent). str, bool and tuple keys, str, dict and set values, sets and lists of dicts do not cross yet. Agent proposal, not owner-confirmed | `DictI64I64` / `DictI64F64` / `DictI64Bool`, read-only (8.3) |
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
`<prefix>.json` of 8.1 (`abi_version` must be 3, and every other value, 1 and 2 included, fails the task and
names the supported version: there are no deployed libraries of an earlier version and the compiler here writes 3
only; a type outside the vocabulary, `int_list` with no `lists.int_list` entry, or a dict type with no entry under
`dicts`, fails the task and names the function) and writes four Kotlin files into one package, plus `IntList.kt`
when the library uses `int_list` and one file per dict kind it uses:

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
- `DictI64I64.kt`, `DictI64F64.kt`, `DictI64Bool.kt`, one per entry of `dicts` (#443, `abi_version` 3; agent
  proposal, not owner-confirmed): the Kotlin side of a `dict[int, int | float | bool]`, read-only. It has `size`,
  `get(key)` (null for a missing key, the library's `KeyError`), `containsKey(key)`, `toMap()` (a `LinkedHashMap` in
  the insertion order), `of(Map)` (`_new`, then `_set` in the map's iteration order) and `close()` with the ownership
  rules of the lists. There is no `set`: the library never writes a dict that crossed its boundary, and with no write
  from Kotlin either, one object may be read and passed to calls from several threads at once. A key or value outside
  64 bits is the library's `OverflowError`, from `get` and `toMap()`. Every access is one `invokeExact` call.

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
classpath check only). The same 31 scenarios are also compiled by `aotNativeCompile` into a standalone
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

### 9.1 First recorded Tier K measurements (PR-M1, shared CI runner)

Recorded from CI run `44599101214` (artifact `typedpython-aot-measure-ubuntu`, `#479` / PR `#483`),
on a **shared GitHub Actions runner** (ratios to Rust only; no absolute-time conclusion is drawn from
shared-runner runs):

- **Commit**: `497765876cde1c3a9520f478955eb7e21693f057`
- **Runner image**: `ubuntu26 20261116.202.1`
- **CPU**: `INTEL(R) XEON(R) PLATINUM 8573C`
- **Toolchains**: `Python 3.15.2`, `rustc 1.100.0 (0ef1bc386 2026-11-09)` (`--release`, LTO, stripped), `repeat=3`

| Benchmark | Size | AOT stripped | Rust stripped | Size ratio (AOT/Rust) | RSS ratio (AOT/Rust) | CPU ratio (AOT/Rust) | Wall ratio (AOT/Rust) |
|---|---|---|---|---|---|---|---|
| `empty` | - | 14,464 B | 287,664 B | 0.050x | 1.000x | 0.733x | 0.788x |
| `hello` | - | 14,464 B | 290,912 B | 0.050x | 1.000x | 0.834x | 0.816x |
| `nbody` | 200000 | 47,304 B | 317,800 B | 0.149x | 1.000x | 3.324x | 3.311x |
| `spectral-norm` | 400 | 55,496 B | 317,384 B | 0.175x | 1.000x | 11.849x | 11.719x |
| `fannkuch-redux` | 9 | 35,016 B | 295,848 B | 0.118x | 1.000x | 3.284x | 3.279x |

Note on `max_rss_bytes` in this run: every child reported 38,531,072 B (37,628 KiB), which is the
parent Python process's own resident set inherited across `fork` before `exec` under Linux
`os.wait4(..., ru_maxrss)`. A follow-up should measure child RSS via `/usr/bin/time -f %M` or
`posix_spawn` so the child's own RSS is isolated from the harness process.

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
