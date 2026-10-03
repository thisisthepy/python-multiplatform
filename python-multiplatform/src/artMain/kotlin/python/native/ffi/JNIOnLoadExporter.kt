package python.native.ffi

import kotlinx.cinterop.*
import python.native.ffi.jni.*
import platform.android.*

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("JNI_OnLoad")
fun JNI_OnLoad(vm: CPointer<out CPointed>?, reserved: COpaquePointer?): Int {
    return CustomJNI_OnLoad(vm?.reinterpret(), reserved)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiAllocUtf8")
fun Java_python_native_ffi_bindings_ffiAllocUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    str: jstring?
): jlong {
    if (env == null || str == null) return 0L
    val getChars = env.pointed.pointed!!.GetStringUTFChars ?: return 0L
    val cStr = getChars(env, str, null) ?: return 0L
    val kString = cStr.toKString()
    val releaseChars = env.pointed.pointed!!.ReleaseStringUTFChars ?: return 0L
    releaseChars(env, str, cStr)
    return ffiAllocUtf8(kString)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiFreeUtf8")
fun Java_python_native_ffi_bindings_ffiFreeUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    ptr: jlong
) {
    ffiFreeUtf8(ptr)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_ffiReadUtf8")
fun Java_python_native_ffi_bindings_ffiReadUtf8(
    env: CPointer<JNIEnvVar>?,
    clazz: jclass?,
    ptr: jlong
): jstring? {
    if (env == null) return null
    val kString = ffiReadUtf8(ptr) ?: return null
    val newStringUTF = env.pointed.pointed!!.NewStringUTF ?: return null
    val scope = Arena()
    val cstr = kString.cstr.getPointer(scope)
    val result = newStringUTF(env, cstr)
    scope.clear()
    return result
}

/**
 * A @CriticalNative echo reached by NAME-BASED linking instead of RegisterNatives.
 *
 * Deliberately absent from the JNINativeMethod table in jni_onload.def, so ART has to resolve
 * it through the usual `Java_<class>_<method>` symbol lookup. Everything else about it is
 * identical to `critical_echo0`: same convention, same one-line body.
 *
 * This exists to test one hypothesis. Google advises binding @CriticalNative through
 * RegisterNatives *before Android 12*, and measurement shows the convention collapsing on
 * newer releases (net 1.83ns on API 26, 24ns on API 34, 49ns on API 36 hardware) while
 * @FastNative stays ~2ns. If explicitly-registered critical natives are the ones losing the
 * fast path on modern ART, this name-linked variant should be cheap where the registered one
 * is not.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("Java_python_native_ffi_bindings_echoCriticalNamed")
fun echoCriticalNamed(x: Long): Long = x
