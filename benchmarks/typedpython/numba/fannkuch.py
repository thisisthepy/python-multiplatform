"""numba @njit version of py/fannkuch.py. `run(n) -> str` is the whole benchmark.

Semantics that differ from CPython: int64 numpy arrays and int64 counters (wrap instead of
growing), no bounds checks.
"""
import numpy as np
from numba import njit

OPTS = dict(cache=True, error_model="numpy")


@njit(**OPTS)
def fannkuch(n):
    perm1 = np.arange(n)
    count = np.zeros(n, dtype=np.int64)
    perm = np.zeros(n, dtype=np.int64)
    max_flips = 0
    checksum = 0
    perm_count = 0
    r = n
    while True:
        while r != 1:
            count[r - 1] = r
            r -= 1
        for i in range(n):
            perm[i] = perm1[i]
        flips = 0
        k = perm[0]
        while k != 0:
            lo = 0
            hi = k
            while lo < hi:
                t = perm[lo]
                perm[lo] = perm[hi]
                perm[hi] = t
                lo += 1
                hi -= 1
            flips += 1
            k = perm[0]
        if flips > max_flips:
            max_flips = flips
        if perm_count % 2 == 0:
            checksum += flips
        else:
            checksum -= flips
        while True:
            if r == n:
                return checksum, max_flips
            p0 = perm1[0]
            for i in range(r):
                perm1[i] = perm1[i + 1]
            perm1[r] = p0
            count[r] -= 1
            if count[r] > 0:
                break
            r += 1
        perm_count += 1


def run(n: int) -> str:
    checksum, max_flips = fannkuch(n)
    return f"{checksum} {max_flips}"
