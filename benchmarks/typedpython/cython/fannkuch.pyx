# cython: language_level=3, boundscheck=False, wraparound=False, cdivision=True, initializedcheck=False
# Same algorithm as py/fannkuch.py. Semantics that differ from CPython: C int arrays and C long
# counters (wrap instead of growing), no bounds checks, fixed capacity of 16 elements (n > 16 raises).
import sys


cdef (long, long) fannkuch(int n) noexcept:
    cdef int perm1[16]
    cdef int count[16]
    cdef int perm[16]
    cdef long max_flips = 0, checksum = 0, perm_count = 0, flips
    cdef int r = n, i, k, lo, hi, t, p0
    for i in range(n):
        perm1[i] = i
        count[i] = 0
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


def main():
    cdef int n = int(sys.argv[1])
    if n > 16:
        raise ValueError("fannkuch.pyx supports n <= 16")
    cdef long checksum, max_flips
    checksum, max_flips = fannkuch(n)
    print(f"{checksum} {max_flips}")
