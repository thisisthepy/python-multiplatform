package python.multiplatform.ffi

import kotlinx.cinterop.*
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlin.experimental.ExperimentalNativeApi
import platform.android.*
import platform.posix.*
import python.native.ffi.*


private const val packageName = "python_multiplatform_ffi"
private const val exportClassName = "Python3"
private const val namePrefix = "Java_${packageName}_${exportClassName}_"


@CName("${namePrefix}initialize")
@OptIn(ExperimentalNativeApi::class)
fun initialize() {
    Python3.initialize()
}

@CName("${namePrefix}pyInitializeEx")
@OptIn(ExperimentalNativeApi::class)
fun pyInitializeEx() {
    println("Python initialization start")
    Py_InitializeEx(0)
    println("Python initialization end")
}

//@OptIn(ExperimentalForeignApi::class)
//fun setConfigString(env: CPointer<JNIEnvVar>, config: CPointer<PyConfig>, configStr: CPointer<CPointerVar<wchar_t>>, value: jstring): PyStatus {
//    val valueUtf8 = env.pointed.pointed?.GetStringUTFChars!!.invoke(env, value, null) ?: error("Failed to get UTF-8 string")
//
//    val pyString = PyUnicode_FromStringAndSize(valueUtf8, strlen(valueUtf8).toLong().toULong())
//    if (pyString == null) {
//        env.pointed.pointed?.ReleaseStringUTFChars!!.invoke(env, value, valueUtf8)
//        return PyStatus_Error("Failed to create Python string")
//    }
//
//    val status = PyStatus_Ok()
//    // pyString을 사용해서 필요한 작업을 수행
//    // configStr 또는 config에 pyString을 사용해 설정하는 로직 추가
//
//    Py_DECREF(pyString)
//    env.pointed.pointed?.ReleaseStringUTFChars!!.invoke(env, value, valueUtf8)
//
//    return status
//}


//@CName("${namePrefix}pyInitializeFromConfig")
//@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
//fun pyInitializeFromConfig(env: CPointer<JNIEnvVar>, home: jstring, runModule: jstring): Int {
//    return memScoped {
//        val config = alloc<PyConfig>()
//        PyConfig_InitIsolatedConfig(config.ptr)
//
//        PyConfig_SetString
//
//        var status = setConfigString(env, config.ptr, config.home.ptr, home)
//        if (PyStatus_Exception(status) == 1) {
//            println("Failed to set home")
//            return 1
//        }
//
//        status = setConfigString(env, config.ptr, config.run_module.ptr, runModule)
//        if (PyStatus_Exception(status) == 1) {
//            println("Failed to set run_module")
//            return 1
//        }
//
//        config.install_signal_handlers = 1
//
//        val status = Py_InitializeFromConfig(config.ptr)
//        if (PyStatus_Exception(status) == 1) {
//            println("Failed to initialize from config")
//            return 1
//        }
//        println("Succeed to initialize from config")
//
//        return Py_RunMain()
//    }
//}

@CName("${namePrefix}internalIsInitialized")
@OptIn(ExperimentalNativeApi::class)
fun internalIsInitialized(): Boolean {
    return Python3.isInitialized
}

@CName("${namePrefix}finalize")
@OptIn(ExperimentalNativeApi::class)
fun finalize() {
    Python3.finalize()
}


@OptIn(ExperimentalNativeApi::class)
@CName("${namePrefix}sayHello")
fun sayHello() {
    __android_log_print(ANDROID_LOG_INFO.toInt(), "Kn", "Hello %s", "Native")
}

//@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
//@CName("${namePrefix}stringFromJNI")
//fun stringFromJNI(env: CPointer<JNIEnvVar>, thiz: jobject): jstring {
//    memScoped {
//        return env.pointed.pointed!!.NewStringUTF!!.invoke(env, "This is from Kotlin Native!!".cstr.ptr)!!
//    }
//}
//
//@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
//@CName("${namePrefix}callJava")
//fun callJava(env: CPointer<JNIEnvVar>, thiz: jobject): jstring {
//    memScoped {
//        val jniEnvVal = env.pointed.pointed!!
//        val jclass = jniEnvVal.GetObjectClass!!.invoke(env, thiz)
//        val methodId = jniEnvVal.GetMethodID!!.invoke(env, jclass,
//            "callFromNative".cstr.ptr, "()Ljava/lang/String;".cstr.ptr)
//        return jniEnvVal.CallObjectMethodA!!.invoke(env, thiz, methodId, null) as jstring
//    }
//}

/**
 * Diagnostic only: returns its argument unchanged.
 *
 * Exported under `python.native.ffi.bindings` so the Android JVM side can call it through the same
 * path as every other binding. Its whole purpose is to make an argument-slot mismatch visible —
 * if the JNI calling convention does not match this export's signature, the value read back will
 * not be the value passed in.
 */
@CName("Java_python_native_ffi_bindings_diagEcho")
@kotlin.OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)
fun diagEcho(env: Long, clazz: Long, x: Long): Long = x
