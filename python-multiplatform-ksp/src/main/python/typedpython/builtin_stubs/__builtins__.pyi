# TypedPython's builtin names (docs/design/typedpython.md §2.3). A `__builtins__.pyi` on the type
# checker's search path declares names the interpreter provides without an import.
from collections.abc import Callable
from typing import overload

@overload
def compiled[T](f: T, /) -> T: ...
@overload
def compiled[T](*, parallel: bool = ..., fastmath: bool = ...) -> Callable[[T], T]: ...
