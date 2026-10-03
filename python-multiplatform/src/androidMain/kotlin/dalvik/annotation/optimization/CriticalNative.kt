package dalvik.annotation.optimization

/**
 * Local declaration of ART's @CriticalNative. The public SDK only exposes it from API 34, but
 * ART matches the annotation by fully-qualified name at runtime, so declaring it here works on
 * every level that honours it.
 *
 * A @CriticalNative method receives ONLY its declared arguments -- no JNIEnv*, no jclass -- and
 * ART skips the GC thread-state transition. It must therefore be static, take and return only
 * primitives, and must never call back into the JVM or block.
 *
 * Google advises binding these through RegisterNatives rather than name-based linking before
 * Android 12, which is what JNI_OnLoad does here. Verified on API 26 (the earliest level that
 * honours the annotation, and this project's minSdk) and on API 34.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class CriticalNative

/**
 * Local declaration of ART's @FastNative, kept alongside @CriticalNative for benchmarking.
 *
 * @FastNative still receives JNIEnv* and jclass -- so it may touch the JVM -- but skips the GC
 * thread-state transition. It sits between ordinary JNI and @CriticalNative in cost, and is the
 * right choice for a native call that must use JNIEnv yet stays short.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class FastNative
