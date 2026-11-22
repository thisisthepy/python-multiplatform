package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.attrAsStringList
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python package: a module with a `__path__`, whose submodules can be imported
 * (#524, the baseline's `types/modules/PyPackage.kt`).
 */
open class PyPackage(pointer: NativePointer, borrowed: Boolean) : PyModule(pointer, borrowed) {
    companion object {
        /** [obj] as a package (a new wrapper with its own reference); throws if it is not a module with `__path__`. */
        fun from(obj: PyObject): PyPackage {
            require(Python3.withPython { python.multiplatform.ffi.PyModule_Check(obj.pointer) } == 1) {
                "expected a package, got ${obj.Type.name}"
            }
            val hasPath = obj.getAttrOrNull("__path__")?.use { true } ?: false
            require(hasPath) { "expected a package, got a module with no __path__" }
            return PyPackage(obj.pointer, borrowed = true)
        }

        /** `import name`, which must be a package. */
        fun import(name: String): PyPackage = Python3.import(name).use { from(it) }
    }

    /** `__path__`: the directories the package's submodules are found in. */
    val path: List<String> get() = attrAsStringList("__path__")

    /** `import <package>.<name>`: the submodule (a [PyModule]; a subpackage is one too). */
    fun submodule(name: String): PyModule = Python3.import("${this.name}.$name")

    /** `import <package>.<name>` as a package. */
    fun subpackage(name: String): PyPackage = import("${this.name}.$name")
}
