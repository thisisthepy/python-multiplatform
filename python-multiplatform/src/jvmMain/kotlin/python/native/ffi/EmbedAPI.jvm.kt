package python.native.ffi

/**
 * `NativePointer.address` always carries a boxed `Long` on both JVM leaves (Android's `JNIPointer`
 * is a typealias for `Long`), so this cast — and [toRawValue] below — behave identically on
 * Android and desktop and don't need a platform-specific `actual`.
 */
inline fun NativePointer.toPlatformPointer(): Long = this.address as Long

actual fun NativePointer.toRawValue(): Long = toPlatformPointer()
