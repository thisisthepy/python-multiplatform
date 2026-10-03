import sys


class Node:
    __slots__ = ("left", "right")
    left: "Node | None"
    right: "Node | None"

    def __init__(self, left: "Node | None", right: "Node | None") -> None:
        self.left = left
        self.right = right


def make(depth: int) -> Node:
    if depth > 0:
        return Node(make(depth - 1), make(depth - 1))
    return Node(None, None)


def check(node: Node) -> int:
    left: Node | None = node.left
    right: Node | None = node.right
    if left is None or right is None:
        return 1
    return 1 + check(left) + check(right)


def main() -> None:
    n: int = int(sys.argv[1])
    min_depth: int = 4
    max_depth: int = max(min_depth + 2, n)
    stretch: int = check(make(max_depth + 1))
    long_lived: Node = make(max_depth)
    total: int = 0
    for depth in range(min_depth, max_depth + 1, 2):
        iterations: int = 1 << (max_depth - depth + min_depth)
        chk: int = 0
        for _ in range(iterations):
            chk += check(make(depth))
        total += chk
    print(f"{stretch} {total} {check(long_lived)}")


if __name__ == "__main__":
    main()
