# typedpython: compiled
import array


def dot_arr(a: array.array[float], b: array.array[float]) -> float:
    s: float = 0.0
    for i in range(len(a)):
        s += a[i] * b[i]
    return s


def scale_arr(a: array.array[float], k: float) -> None:
    for i in range(len(a)):
        a[i] = a[i] * k


def dot_list(a: list[float], b: list[float]) -> float:
    s: float = 0.0
    for i in range(len(a)):
        s += a[i] * b[i]
    return s
