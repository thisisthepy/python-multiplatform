package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.reflection.InstallsUpcallTable
import python.multiplatform.reflection.UpcallTable

/**
 * Hands the generated table to the runtime.
 *
 * `expect`/`actual` for one reason only: KSP emits `python.multiplatform.generated.FunctionTable`
 * into *each target's own compilation*, so `commonMain` -- compiled by every target and seeing
 * none of their generated directories -- cannot name it.
 *
 * There is no hand-written `actual` for this anywhere in the repository. `@InstallsUpcallTable`
 * makes the processor emit one into every leaf compilation it generates a `FunctionTable` for.
 * That is what removed the three `InstallTable.ios*.kt` files this sample used to carry -- one
 * identical line per iOS target, because `iosMain` is an intermediate source set and could host
 * neither the call nor the `actual`. ROADMAP §13 recorded that as the shape any real app hits.
 */
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()

/**
 * Resolves [UPCALL_ENTRY_NAME] and calls it, and says who made the call.
 *
 * Only desktop reaches Kotlin from *inside* the interpreter; see each actual.
 */
expect fun callKotlinFromPython(): String

/**
 * What KSP produced, read off the runtime table rather than off the build log. If the processor
 * never ran, this reports an empty table -- which is the failure worth seeing.
 */
fun upcallTableSummary(): String {
    val modules = UpcallTable.moduleNames
    return if (modules.isEmpty()) {
        "upcall table is empty -- the KSP processor did not run"
    } else {
        "${UpcallTable.callableCount} entries, ${UpcallTable.classCount} classes, " +
            "from ${modules.joinToString(", ")}"
    }
}

/** Whether the blacklist opt-out held: `@PythonInternal` must keep an entry out of the table. */
fun optOutHeld(): Boolean =
    UpcallTable.resolve(UPCALL_ENTRY_NAME).isValid && !UpcallTable.resolve(UPCALL_EXCLUDED_NAME).isValid
