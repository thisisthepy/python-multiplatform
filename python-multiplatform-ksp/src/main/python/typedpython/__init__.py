"""TypedPython — static checking for the Python code of a PythonMultiplatform project.

See docs/design/typedpython.md. This package is the soundness gate (§4.2).
"""


def compiled[T](target: T) -> T:
    """Mark a function or class for native compilation. In CPython it does nothing."""
    return target
