# cython: language_level=3, boundscheck=False, wraparound=False, cdivision=True, initializedcheck=False
# Same algorithm as py/spectral_norm.py. Semantics that differ from CPython: loop and index
# arithmetic is C long (wraps instead of growing; not reachable at these sizes), cdivision (no
# ZeroDivisionError), no bounds checks (malloc'd C arrays).
from libc.math cimport sqrt
from libc.stdlib cimport malloc, free
import sys


cdef inline double eval_a(long i, long j) noexcept nogil:
    return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)


cdef void mul_av(long n, double* v, double* out) noexcept nogil:
    cdef long i, j
    cdef double s
    for i in range(n):
        s = 0.0
        for j in range(n):
            s += eval_a(i, j) * v[j]
        out[i] = s


cdef void mul_atv(long n, double* v, double* out) noexcept nogil:
    cdef long i, j
    cdef double s
    for i in range(n):
        s = 0.0
        for j in range(n):
            s += eval_a(j, i) * v[j]
        out[i] = s


cdef void mul_atav(long n, double* v, double* out, double* tmp) noexcept nogil:
    mul_av(n, v, tmp)
    mul_atv(n, tmp, out)


def main():
    cdef long n = int(sys.argv[1])
    cdef long i
    cdef double* u = <double*> malloc(n * sizeof(double))
    cdef double* v = <double*> malloc(n * sizeof(double))
    cdef double* tmp = <double*> malloc(n * sizeof(double))
    cdef double vbv = 0.0, vv = 0.0
    try:
        for i in range(n):
            u[i] = 1.0
            v[i] = 0.0
            tmp[i] = 0.0
        for i in range(10):
            mul_atav(n, u, v, tmp)
            mul_atav(n, v, u, tmp)
        for i in range(n):
            vbv += u[i] * v[i]
            vv += v[i] * v[i]
    finally:
        free(u)
        free(v)
        free(tmp)
    print(f"{sqrt(vbv / vv):.9f}")
