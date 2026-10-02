import sys


def fannkuch(n: int) -> tuple[int, int]:
    perm1: list[int] = list(range(n))
    count: list[int] = [0] * n
    max_flips: int = 0
    checksum: int = 0
    perm_count: int = 0
    r: int = n
    while True:
        while r != 1:
            count[r - 1] = r
            r -= 1
        perm: list[int] = perm1[:]
        flips: int = 0
        k: int = perm[0]
        while k != 0:
            lo: int = 0
            hi: int = k
            while lo < hi:
                t: int = perm[lo]
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
            p0: int = perm1[0]
            for i in range(r):
                perm1[i] = perm1[i + 1]
            perm1[r] = p0
            count[r] -= 1
            if count[r] > 0:
                break
            r += 1
        perm_count += 1


def main() -> None:
    n: int = int(sys.argv[1])
    checksum, max_flips = fannkuch(n)
    print(f"{checksum} {max_flips}")


if __name__ == "__main__":
    main()
