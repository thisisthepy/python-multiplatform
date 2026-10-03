"""numba @njit version of py/spectral_norm.py. `run(n) -> str` is the whole benchmark.

Semantics that differ from CPython: float64 numpy arrays, int64 index arithmetic (wraps),
error_model="numpy" (no ZeroDivisionError), no bounds checks.
"""
import math

import numpy as np
from numba import njit

OPTS = dict(cache=True, error_model="numpy")


@njit(**OPTS)
def eval_a(i, j):
    return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)


@njit(**OPTS)
def mul_av(n, v, out):
    for i in range(n):
        s = 0.0
        for j in range(n):
            s += eval_a(i, j) * v[j]
        out[i] = s


@njit(**OPTS)
def mul_atv(n, v, out):
    for i in range(n):
        s = 0.0
        for j in range(n):
            s += eval_a(j, i) * v[j]
        out[i] = s


@njit(**OPTS)
def mul_atav(n, v, out, tmp):
    mul_av(n, v, tmp)
    mul_atv(n, tmp, out)


@njit(**OPTS)
def kernel(n):
    u = np.ones(n)
    v = np.zeros(n)
    tmp = np.zeros(n)
    for _ in range(10):
        mul_atav(n, u, v, tmp)
        mul_atav(n, v, u, tmp)
    vbv = 0.0
    vv = 0.0
    for i in range(n):
        vbv += u[i] * v[i]
        vv += v[i] * v[i]
    return vbv, vv


def run(n: int) -> str:
    vbv, vv = kernel(n)
    return f"{math.sqrt(vbv / vv):.9f}"
