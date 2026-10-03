# cython: language_level=3, boundscheck=False, wraparound=False, cdivision=True, initializedcheck=False
# Same algorithm as py/binary_trees.py: one heap object per node, reference counted, freed by
# refcounting (not an arena). Semantics that differ from CPython: Node is a cdef class (no __dict__,
# attributes not visible from Python), C long counters, recursion uses the C stack (no
# RecursionError), no None checks on typed attributes.
import sys


cdef class Node:
    cdef Node left
    cdef Node right

    def __init__(self, Node left, Node right):
        self.left = left
        self.right = right


cdef Node make(int depth):
    if depth > 0:
        return Node(make(depth - 1), make(depth - 1))
    return Node(None, None)


cdef long check(Node node) noexcept:
    cdef Node left = node.left
    cdef Node right = node.right
    if left is None or right is None:
        return 1
    return 1 + check(left) + check(right)


def main():
    cdef int n = int(sys.argv[1])
    cdef int min_depth = 4
    cdef int max_depth = max(min_depth + 2, n)
    cdef int depth, i
    cdef long iterations, chk, total = 0
    cdef long stretch = check(make(max_depth + 1))
    cdef Node long_lived = make(max_depth)
    for depth in range(min_depth, max_depth + 1, 2):
        iterations = 1 << (max_depth - depth + min_depth)
        chk = 0
        for i in range(iterations):
            chk += check(make(depth))
        total += chk
    print(f"{stretch} {total} {check(long_lived)}")
