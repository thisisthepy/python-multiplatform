package python.multiplatform.ffi

/** No virtual threads on this target (#161): nothing to pin. */
@PublishedApi
internal actual fun needsCarrierPin(): Boolean = false

@PublishedApi
internal actual fun <R> runOnPinnedCarrier(body: () -> R): R = body()
