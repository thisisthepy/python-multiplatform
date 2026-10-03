import math
import sys


def eval_a(i: int, j: int) -> float:
    return 1.0 / ((i + j) * (i + j + 1) // 2 + i + 1)


def mul_av(n: int, v: list[float], out: list[float]) -> None:
    for i in range(n):
        s: float = 0.0
        for j in range(n):
            s += eval_a(i, j) * v[j]
        out[i] = s


def mul_atv(n: int, v: list[float], out: list[float]) -> None:
    for i in range(n):
        s: float = 0.0
        for j in range(n):
            s += eval_a(j, i) * v[j]
        out[i] = s


def mul_atav(n: int, v: list[float], out: list[float], tmp: list[float]) -> None:
    mul_av(n, v, tmp)
    mul_atv(n, tmp, out)


def main() -> None:
    n: int = int(sys.argv[1])
    u: list[float] = [1.0] * n
    v: list[float] = [0.0] * n
    tmp: list[float] = [0.0] * n
    for _ in range(10):
        mul_atav(n, u, v, tmp)
        mul_atav(n, v, u, tmp)
    vbv: float = 0.0
    vv: float = 0.0
    for i in range(n):
        vbv += u[i] * v[i]
        vv += v[i] * v[i]
    print(f"{math.sqrt(vbv / vv):.9f}")


if __name__ == "__main__":
    main()
