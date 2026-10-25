import array
import sys


def bump_list(a: list[float], i: int) -> None:
    a[i] += 1.0


def bump_array(a: array.array[float], i: int) -> None:
    a[i] += 1.0


def bump_view(a: memoryview[float], i: int) -> None:
    a[i] += 1.0


def run_list(a: list[float], calls: int) -> None:
    n: int = len(a)
    for k in range(calls):
        bump_list(a, (k * 7919) % n)


def run_array(a: array.array[float], calls: int) -> None:
    n: int = len(a)
    for k in range(calls):
        bump_array(a, (k * 7919) % n)


def run_view(a: memoryview[float], calls: int) -> None:
    n: int = len(a)
    for k in range(calls):
        bump_view(a, (k * 7919) % n)


def main() -> None:
    # argv[1] = number of bump calls, argv[2] = array size (default 1000000). Each call touches one element
    # of a large buffer: a list argument is copied in and written back on every call (O(n)), an exact
    # array.array / memoryview argument is used in place (SPEC N-8, #149). Output: one checksum per variant.
    calls: int = int(sys.argv[1])
    size: int = int(sys.argv[2]) if len(sys.argv) > 2 else 1000000
    as_list: list[float] = [0.0] * size
    as_array: array.array[float] = array.array("d", [0.0]) * size
    backing: array.array[float] = array.array("d", [0.0]) * size
    as_view: memoryview[float] = memoryview(backing)
    run_list(as_list, calls)
    run_array(as_array, calls)
    run_view(as_view, calls)
    print(f"{sum(as_list)} {sum(as_array)} {sum(backing)}")


if __name__ == "__main__":
    main()
