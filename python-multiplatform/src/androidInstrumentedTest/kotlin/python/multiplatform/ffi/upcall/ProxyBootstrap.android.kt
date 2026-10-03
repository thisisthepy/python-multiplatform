package python.multiplatform.ffi.upcall

/**
 * `androidMain`'s `UpcallEntry.publish` installs both names, as `PyMethodDef`s whose `ml_meth` is a
 * C shim in `artMain/cinterop/jni_onload.def` that calls back into `UpcallCallbacks`.
 *
 * Flipped from `false` only after the tests below were watched to pass on `pmp_api26` and
 * `pmp_api36`; the constant is the claim, not the cause. Getting there took two C changes and not
 * the one that was recorded here -- see [publishesProxyEntryPoints]'s ART row.
 */
actual val publishesProxyEntryPoints: Boolean = true

/** §13.4's emulator run drives the `asyncio` fast path on `pmp_api26` and `pmp_api36` alike. */
actual val proxyBootstrapSupportsAsyncio: Boolean = true
