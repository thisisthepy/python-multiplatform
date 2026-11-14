"""numba @njit version of py/wordfreq.py: numba.typed.Dict[unicode_type, int64].

Semantics that differ from CPython: int64 arithmetic (wraps), words are numba unicode strings,
the counts dict is a numba typed.Dict (hash order differs, but the result does not depend on
iteration order because ties are broken by string comparison).
"""
import numpy as np
from numba import njit, types
from numba.typed import Dict, List

VOCAB = 50000
MOD = 2147483647
OPTS = dict(cache=True, error_model="numpy")


@njit(**OPTS)
def next_state(s):
    return (s * 48271) % MOD


@njit(**OPTS)
def word_of(word_id):
    x = word_id + 17576
    w = ""
    while x > 0:
        w = chr(97 + x % 26) + w
        x //= 26
    return w


@njit(**OPTS)
def kernel(n):
    counts = Dict.empty(key_type=types.unicode_type, value_type=types.int64)
    s = 12345
    for _ in range(n):
        s = next_state(s)
        a = s % VOCAB
        s = next_state(s)
        b = s % VOCAB
        word = word_of(a * b // VOCAB)
        if word in counts:
            counts[word] += 1
        else:
            counts[word] = 1
    weighted = 0
    for w in counts:
        c = counts[w]
        weighted += c * c
    taken = List.empty_list(types.unicode_type)
    best_counts = np.zeros(5, dtype=np.int64)
    for k in range(5):
        best_word = ""
        best_count = -1
        for w in counts:
            c = counts[w]
            if w in taken:
                continue
            if c > best_count or (c == best_count and w < best_word):
                best_word = w
                best_count = c
        taken.append(best_word)
        best_counts[k] = best_count
    return len(counts), weighted, taken, best_counts


def run(n: int) -> str:
    size, weighted, taken, best_counts = kernel(n)
    parts = [f"{w}:{c}" for w, c in zip(taken, best_counts)]
    return f"{size} {weighted} {','.join(parts)}"
