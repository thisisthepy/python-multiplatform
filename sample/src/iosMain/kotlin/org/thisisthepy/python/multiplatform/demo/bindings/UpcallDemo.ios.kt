package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.UpcallTable

/**
 * `installGeneratedUpcallTable` is **not** here, and still cannot be -- but it is no longer
 * written by hand anywhere either.
 *
 * KSP writes its output into the *leaf* target's source set --
 * `build/generated/ksp/iosSimulatorArm64/iosSimulatorArm64Main/kotlin` and one sibling per
 * target -- and `iosMain` is an intermediate source set those leaves depend on, not the other way
 * round. So `python.multiplatform.generated.FunctionTable` is unresolvable from here for the same
 * reason it is unresolvable from `commonMain`. That used to cost three identical
 * `InstallTable.ios*.kt` files, one per leaf.
 *
 * ROADMAP §13 closed it by making the processor emit those `actual`s: `commonMain` declares
 * `@InstallsUpcallTable expect fun installGeneratedUpcallTable()` and every leaf gets its own
 * one-liner generated into it. The constraint is unchanged -- the generated table is reachable
 * only from the source set of the target that generated it -- but nothing shared code writes has
 * to know that any more. `ksp-fixtures/app`'s `androidNativeMain` pins the same shape.
 */

/**
 * The generated table is real here -- same processor, same fragments, discovered through the
 * `.klib` rather than a jar. This particular call is still made from the **Kotlin** side, and says
 * so: reporting it as a call Python made would hide which half of the path was exercised.
 *
 * What is *not* true any more is the reason this file used to give. It said iOS had no boundary
 * shim at all, and that stopped being the case when `python.native.ffi.UpcallEntry` landed in
 * `nativeMain`: it publishes real `PyMethodDef`-backed builtins, which is the only route on this
 * target because this project's `Python.framework` ships no `_ctypes`. `ProxyDemo.ios.kt` next
 * door uses it, and records the one place the library's two halves disagree about the bootstrap.
 */
actual fun callKotlinFromPython(): String {
    val handle = UpcallTable.resolve(UPCALL_ENTRY_NAME)
    if (!handle.isValid) return "the name was not in the table (handle -1)"
    val value = UpcallTable.invoke(handle, emptyArray())
    return "table hit: handle ${handle.raw} -> $value  (Kotlin-side call; " +
        "this target has a PyMethodDef shim too -- ProxyDemo.ios.kt publishes and uses it -- " +
        "this particular demo just doesn't route through it)"
}
