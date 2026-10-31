import sys


@compiled
def dot(n: int) -> float:
    s = 0.0
    for i in range(n):
        x = i * 0.5
        s += x * x
    return s


@compiled
def collatz_steps(limit: int) -> int:
    total = 0
    for start in range(1, limit):
        k = start
        while k != 1:
            if k % 2 == 0:
                k = k // 2
            else:
                k = 3 * k + 1
            total += 1
    return total


def main() -> None:
    n = 2_000_000
    if len(sys.argv) > 1:
        n = int(sys.argv[1])
    print(dot(n), collatz_steps(n // 20))


if __name__ == "__main__":
    main()
