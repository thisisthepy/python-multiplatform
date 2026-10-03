package fixture.app

import python.multiplatform.ffi.PyObject

/**
 * Closes the Python object it is handed, inside the upcall (issue #98).
 *
 * The trampoline wraps a `PyObject` argument with `borrowed = true` (`PyTuple_GetItem` lends the
 * item), so the wrapper holds one reference of its own. `close()` gives back exactly that one, and
 * the wrapper's cleaner, which runs at most once, has nothing left to release when the JVM later
 * collects it. `PyObjectArgumentReleaseTest` calls this 10,000 times and checks the count.
 *
 * @return 1 when there was an object to close, 0 for `None`.
 */
fun closeArgument(value: PyObject?): Long {
    if (value == null) return 0L
    value.close()
    return 1L
}
