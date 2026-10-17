# wordfreq and object-only compiled code (#150, 2026-10-17)

Quiet windows, interleaved against develop, 11 pairs, CPython 3.15.0rc1t.

| build | after / before (median, range) | vs CPython |
|---|---|---|
| #150 without the cost rule (`word_of` and `main` compiled) | 1.335 (1.314 to 1.767) | 1.1x to **0.8x** |
| #150 with the cost rule (only `next_state` compiled, as on develop) | 1.003 (0.974 to 1.027) | 1.1x to 1.1x |

Split, measured in one process: `word_of` compiled 248.6 ms against 95.7 ms interpreted (2.6x slower);
`main` 332 ms against 345 ms.

Cause (read in the generated C): in an impure function an `int` is a CPython object, so each loop
iteration boxes constants, calls generic `PyNumber_*` / `PyObject_RichCompare`, and looks up the
global `chr` through the module and builtins dicts. CPython 3.15's specialising interpreter
(`BINARY_OP_ADD_INT`, `LOAD_GLOBAL_BUILTIN` caches, the small-int cache) runs that faster.

Consequence: the cost rule (`typedpython/cost.py`) keeps an impure function interpreted when it has
loops and none of them does native work. The real fix, native ints in impure functions with in-place
promotion on overflow, pre-boxed constants and cached builtins, is #232.
