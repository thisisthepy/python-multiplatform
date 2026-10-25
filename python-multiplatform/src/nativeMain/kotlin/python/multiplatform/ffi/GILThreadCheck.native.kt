package python.multiplatform.ffi

/** No virtual threads on this target; nothing to check (see the common declaration). */
@PublishedApi
internal actual fun checkGILThread() {}
