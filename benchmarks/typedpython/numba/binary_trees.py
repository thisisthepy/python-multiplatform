"""numba version of py/binary_trees.py, using numba.experimental.jitclass.

Node is a jitclass with a deferred (self-referential) Optional type, the closest numba has to a
heap object per node; allocation and release go through numba's NRT reference counting, like the
CPython program. (An index-based tree in preallocated arrays would be faster but changes the
allocation pattern into an arena, so it is not used.)

Semantics that differ from CPython: counters are int64, recursion uses the native stack (no
RecursionError). The module must be registered in sys.modules before it runs (run.py does).
"""
from numba import deferred_type, int64, njit, optional
from numba.core.types import UniTuple
from numba.experimental import jitclass

node_type = deferred_type()


@jitclass([("left", optional(node_type)), ("right", optional(node_type))])
class Node:
    def __init__(self, left, right):
        self.left = left
        self.right = right


NodeT = Node.class_type.instance_type
node_type.define(NodeT)

# cache=True is not usable here: numba raises AttributeError: 'Optional' object has no attribute
# 'type' while hashing the recursive jitclass type for the cache index. So this benchmark has no
# cached run; every process compiles (at import, see below).
OPTS = dict(cache=False)


# Explicit signatures: numba cannot infer the return type of the self-recursive make() through
# the jitclass's deferred Optional type (AttributeError: 'Optional' object has no attribute
# 'type'). The consequence is that these two functions compile when this module is imported, not
# on the first call; run.py counts the import time as compile time.
@njit(NodeT(int64), **OPTS)
def make(depth):
    if depth > 0:
        return Node(make(depth - 1), make(depth - 1))
    return Node(None, None)


@njit(int64(optional(NodeT)), **OPTS)
def check(node):
    left = node.left
    right = node.right
    if left is None or right is None:
        return 1
    return 1 + check(left) + check(right)


@njit(UniTuple(int64, 3)(int64), **OPTS)
def kernel(n):
    min_depth = 4
    max_depth = max(min_depth + 2, n)
    stretch = check(make(max_depth + 1))
    long_lived = make(max_depth)
    total = 0
    for depth in range(min_depth, max_depth + 1, 2):
        iterations = 1 << (max_depth - depth + min_depth)
        chk = 0
        for _ in range(iterations):
            chk += check(make(depth))
        total += chk
    return stretch, total, check(long_lived)


def run(n: int) -> str:
    stretch, total, long_lived = kernel(n)
    return f"{stretch} {total} {long_lived}"
