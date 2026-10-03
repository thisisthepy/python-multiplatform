package python.native.ffi

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * The three CPython Stable ABI return kinds relevant to the shape vocabulary: no value,
 * a `long`-sized integer/pointer, or a `double`. See `docs/design/downcall-design.md`.
 */
internal enum class ReturnKind { VOID, LONG, DOUBLE }

/**
 * JDK-version-agnostic Panama FFI abstraction.
 *
 * Reaches `java.lang.foreign` (JDK 19+) or `jdk.incubator.foreign` (JDK 16-18)
 * entirely through reflection, so this file compiles on any JDK >= 16 without
 * needing either module at compile time.
 *
 * Every reflective lookup is cached once at init; hot-path calls go through
 * cached MethodHandles with zero reflective overhead.
 */
@PublishedApi
internal object Panama {

    // ---- public surface used by bindings.kt ----

    /** Pointer type carrier for MethodType signatures, always Long. */
    val POINTER_TYPE: Class<*> = Long::class.javaPrimitiveType!!

    /** The null-pointer sentinel as a Long. */
    const val NULL: Long = 0L

    /** Width of a native pointer. Every desktop target this build ships for is 64-bit
     *  (`macos-aarch64`, `macos-x86_64`, `linux-x86_64`, `windows-x86_64`). */
    private const val POINTER_BYTES: Long = 8L

    /** Create a native C string from a Kotlin String and return its address as Long. */
    val allocateUtf8String: (String) -> Long

    /** Read a null-terminated C string at the given address and return as Kotlin String?. */
    val readUtf8String: (Long) -> String?

    /** Look up a symbol in the loaded libraries and create a downcall handle. */
    val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle

    /** Resolve [name] to its raw process address without building a downcall handle around
     *  it. Returns `0L` if the symbol is not found (does not throw). Used for the shape
     *  vocabulary's symbol lookup (`ffiSymbolRaw` in `jvmMain`), where the caller passes
     *  the address as the leading argument to a shape trampoline instead of binding a
     *  dedicated handle per CPython function. */
    val findSymbolAddress: (String) -> Long

    /** Allocate a new null-terminated UTF-8 buffer backed by native `malloc`, independent
     *  of any [java.lang.foreign.Arena]. Unlike [allocateUtf8String] (which allocates from
     *  the global arena and is never freed -- acceptable only because the existing 305
     *  call sites are one-shot, short-lived strings), a buffer from this function **must**
     *  be released with [freeUtf8Address] by the caller. See `ShapeDowncalls.kt` for the
     *  `withUtf8` helper that makes leaking one hard. */
    val allocateUtf8Freeable: (String) -> Long

    /** Release a buffer obtained from [allocateUtf8Freeable]. Passing any other address
     *  (e.g. a CPython-owned `const char*`) is undefined behaviour, same as misusing `free()`. */
    val freeUtf8Address: (Long) -> Unit

    /** Allocate one pointer-sized, uninitialised native slot to serve as a C out-parameter.
     *
     *  CPython is steadily replacing borrowed-reference getters with `int f(..., PyObject **result)`
     *  forms -- `PyWeakref_GetRef` (which replaced the removed `PyWeakref_GetObject`),
     *  `PyDict_GetItemRef`, `PyObject_GetOptionalAttr`. Each needs somewhere for the callee to
     *  write, and on this backend that has to be real native memory. The other targets never need
     *  this: the JNI thunks in `jni_onload.def` take the slot from the C stack, and cinterop has
     *  `memScoped`. Desktop has no C of its own, so the slot is allocated here.
     *
     *  Release with [freePointerSlot]. */
    val allocatePointerSlot: () -> Long

    /** Read back the pointer a callee wrote into a slot obtained from [allocatePointerSlot].
     *
     *  Reads any 8 native bytes at the address, in the platform's byte order, so it is also the
     *  desktop's `*(int64_t *)addr` -- `ProxyType` reads its handle slot through it. */
    val readPointerSlot: (Long) -> Long

    /** Write a pointer-sized value (`*(int64_t *)addr = value`, native byte order).
     *
     *  The other half of [readPointerSlot]. It exists so that nothing on desktop reaches native
     *  memory through `sun.misc.Unsafe`: that class lives in the `jdk.unsupported` module, which a
     *  `jlink`ed runtime -- Compose Desktop's `createDistributable` among them -- does not contain
     *  unless asked to (issue #77, SPEC L-10). `java.lang.foreign` is in `java.base`, which every
     *  runtime has. */
    val writePointerSlot: (Long, Long) -> Unit

    /** Release a slot obtained from [allocatePointerSlot]. */
    val freePointerSlot: (Long) -> Unit

    /** Copy [ByteArray] verbatim into a new native `malloc` buffer and return its address.
     *
     *  For C strings that are not UTF-8: `Py_SetPythonHome` takes a `wchar_t *`, which is UTF-32
     *  on macOS and Linux and UTF-16 on Windows, so the caller encodes (terminator included) and
     *  this only places the bytes. Release with [freeUtf8Address] (both are `malloc`). Not
     *  available on the JDK 16-18 incubator backend, for the reason the pointer slots are not. */
    val allocateBytesFreeable: (ByteArray) -> Long

    /** One `MethodHandle` per ABI shape, **unbound** to any specific target function: its
     *  leading parameter is the callee's address (as a plain `long`, adapted from
     *  `MemorySegment`/`MemoryAddress` so the handle's static Java type is exactly
     *  `(long, long..., double...) -> long/double/void` with no boxing). Built once per
     *  shape by the caller (`ShapeDowncalls.desktop.kt`) and reused for every CPython
     *  function of that shape -- this is what makes `invokeExact` reachable: the call
     *  site's static signature and the handle's type are fixed and identical. */
    val unboundDowncallHandle: (intArgs: Int, floatArgs: Int, returnKind: ReturnKind) -> MethodHandle

    /** Create a Panama upcall stub for a (Long) -> Long MethodHandle */
    val createUpcallStubLongToLong: (MethodHandle) -> Long

    /** Create a Panama upcall stub for a (Long, Long) -> Long MethodHandle.
     *
     *  The argument-carrying upcall shape, and the only one argument passing needs: a callable
     *  handle plus a `PyObject *` argument tuple in, a new `PyObject *` reference out
     *  ([python.multiplatform.ffi.upcall.UpcallTrampoline]). Arity and per-argument types travel
     *  inside the tuple and the table entry rather than in the C signature, so this does not
     *  multiply with the number of exposed Kotlin functions.
     *
     *  It is also exactly `PyCFunction` (`PyObject *(PyObject *self, PyObject *args)`), so a
     *  generated proxy type's methods bind to a stub of this shape with `self` in the handle's
     *  place -- no further stub shape is needed for that step either. */
    val createUpcallStubII_L: (MethodHandle) -> Long

    /** Create a Panama upcall stub for a (Long, Long, Long) -> Int MethodHandle */
    val createUpcallStubIII_I: (MethodHandle) -> Long
    
    /** Create a Panama upcall stub for a (Long) -> Int MethodHandle */
    val createUpcallStubI_I: (MethodHandle) -> Long

    /** Create a Panama upcall stub for a (Long) -> Unit MethodHandle.
     *
     *  `destructor` -- the C type of `tp_dealloc` -- returns void, and the descriptor a stub is
     *  built from has to match the signature CPython will call it through. The int-returning
     *  shape above happens to survive the mismatch on both SysV x86-64 and AAPCS64 (the caller
     *  simply ignores the return register), but that is an ABI accident rather than a guarantee,
     *  so `tp_dealloc` gets its own void shape. */
    val createUpcallStubI_V: (MethodHandle) -> Long

    // ---- internals ----

    private enum class Backend { MODERN, INCUBATOR }

    init {
        val (backend, implData) = detectBackend()
        when (backend) {
            Backend.MODERN -> {
                val data = implData as ModernData
                allocateUtf8String = data.allocateUtf8String
                readUtf8String = data.readUtf8String
                findSymbol = data.findSymbol
                findSymbolAddress = data.findSymbolAddress
                allocateUtf8Freeable = data.allocateUtf8Freeable
                freeUtf8Address = data.freeUtf8Address
                allocatePointerSlot = data.allocatePointerSlot
                readPointerSlot = data.readPointerSlot
                writePointerSlot = data.writePointerSlot
                freePointerSlot = data.freePointerSlot
                allocateBytesFreeable = data.allocateBytesFreeable
                unboundDowncallHandle = data.unboundDowncallHandle
                createUpcallStubLongToLong = data.createUpcallStubLongToLong
                createUpcallStubII_L = data.createUpcallStubII_L
                createUpcallStubIII_I = data.createUpcallStubIII_I
                createUpcallStubI_I = data.createUpcallStubI_I
                createUpcallStubI_V = data.createUpcallStubI_V
            }
            Backend.INCUBATOR -> {
                val data = implData as IncubatorData
                allocateUtf8String = data.allocateUtf8String
                readUtf8String = data.readUtf8String
                findSymbol = data.findSymbol
                findSymbolAddress = data.findSymbolAddress
                allocateUtf8Freeable = data.allocateUtf8Freeable
                freeUtf8Address = data.freeUtf8Address
                allocatePointerSlot = data.allocatePointerSlot
                readPointerSlot = data.readPointerSlot
                writePointerSlot = data.writePointerSlot
                freePointerSlot = data.freePointerSlot
                allocateBytesFreeable = data.allocateBytesFreeable
                unboundDowncallHandle = data.unboundDowncallHandle
                createUpcallStubLongToLong = data.createUpcallStubLongToLong
                createUpcallStubII_L = data.createUpcallStubII_L
                createUpcallStubIII_I = data.createUpcallStubIII_I
                createUpcallStubI_I = data.createUpcallStubI_I
                createUpcallStubI_V = data.createUpcallStubI_V
            }
        }
    }

    // ---- Modern backend: java.lang.foreign (JDK 19+ preview, 22+ final) ----

    private class ModernData(
        val allocateUtf8String: (String) -> Long,
        val readUtf8String: (Long) -> String?,
        val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle,
        val findSymbolAddress: (String) -> Long,
        val allocateUtf8Freeable: (String) -> Long,
        val freeUtf8Address: (Long) -> Unit,
        val allocatePointerSlot: () -> Long,
        val readPointerSlot: (Long) -> Long,
        val writePointerSlot: (Long, Long) -> Unit,
        val freePointerSlot: (Long) -> Unit,
        val allocateBytesFreeable: (ByteArray) -> Long,
        val unboundDowncallHandle: (Int, Int, ReturnKind) -> MethodHandle,
        val createUpcallStubLongToLong: (MethodHandle) -> Long,
        val createUpcallStubII_L: (MethodHandle) -> Long,
        val createUpcallStubIII_I: (MethodHandle) -> Long,
        val createUpcallStubI_I: (MethodHandle) -> Long,
        val createUpcallStubI_V: (MethodHandle) -> Long
    )

    private fun initModern(): ModernData {
        val lookup = MethodHandles.lookup()

        // Core classes
        val arenaClass = Class.forName("java.lang.foreign.Arena")
        val memorySegmentClass = Class.forName("java.lang.foreign.MemorySegment")
        val linkerClass = Class.forName("java.lang.foreign.Linker")
        val symbolLookupClass = Class.forName("java.lang.foreign.SymbolLookup")
        val functionDescriptorClass = Class.forName("java.lang.foreign.FunctionDescriptor")
        val valueLazyoutClass = Class.forName("java.lang.foreign.ValueLayout")
        val memoryLayoutClass = Class.forName("java.lang.foreign.MemoryLayout")

        // ValueLayout constants
        val javaInt = valueLazyoutClass.getField("JAVA_INT").get(null)
        val javaLong = valueLazyoutClass.getField("JAVA_LONG").get(null)
        val javaFloat = valueLazyoutClass.getField("JAVA_FLOAT").get(null)
        val javaDouble = valueLazyoutClass.getField("JAVA_DOUBLE").get(null)
        val javaByte = valueLazyoutClass.getField("JAVA_BYTE").get(null)
        val javaShort = valueLazyoutClass.getField("JAVA_SHORT").get(null)
        val address = valueLazyoutClass.getField("ADDRESS").get(null)

        // Arena.global()
        val globalArena = arenaClass.getMethod("global").invoke(null)

        // MemorySegment.NULL
        val nullSegment = memorySegmentClass.getField("NULL").get(null)

        // Arena.allocateFrom(String) (JDK 22+) or allocateUtf8String (JDK 19-21)
        val allocateFromMH = try {
            lookup.findVirtual(
                arenaClass, "allocateFrom",
                MethodType.methodType(memorySegmentClass, String::class.java)
            )
        } catch (e: Exception) {
            lookup.findVirtual(
                arenaClass, "allocateUtf8String",
                MethodType.methodType(memorySegmentClass, String::class.java)
            )
        }

        // MemorySegment.address()
        val segmentAddressMH = lookup.findVirtual(
            memorySegmentClass, "address",
            MethodType.methodType(Long::class.javaPrimitiveType)
        )

        // MemorySegment.reinterpret(long)
        val reinterpretMH = lookup.findVirtual(
            memorySegmentClass, "reinterpret",
            MethodType.methodType(memorySegmentClass, Long::class.javaPrimitiveType)
        )

        // MemorySegment.getString(long) (JDK 22+) or getUtf8String (JDK 19-21)
        val getStringMH = try {
            lookup.findVirtual(
                memorySegmentClass, "getString",
                MethodType.methodType(String::class.java, Long::class.javaPrimitiveType)
            )
        } catch (e: Exception) {
            lookup.findVirtual(
                memorySegmentClass, "getUtf8String",
                MethodType.methodType(String::class.java, Long::class.javaPrimitiveType)
            )
        }

        // MemorySegment.ofAddress(long). Resolved reflectively because the class is not on the
        // compile-time classpath, then immediately unreflected: both of its users (readUtf8String,
        // allocateUtf8Freeable) are per-call, and Method.invoke boxes the long on every one.
        val ofAddressMH: MethodHandle =
            lookup.unreflect(memorySegmentClass.getMethod("ofAddress", Long::class.javaPrimitiveType!!))

        // Linker.nativeLinker()
        val linker = linkerClass.getMethod("nativeLinker").invoke(null)

        // Linker.defaultLookup()
        val defaultLookup = linkerClass.getMethod("defaultLookup").invoke(linker)

        // SymbolLookup.loaderLookup()
        val loaderLookup = symbolLookupClass.getMethod("loaderLookup").invoke(null)

        // Chain: loaderLookup.or(defaultLookup)
        val orMethod = symbolLookupClass.getMethod("or", symbolLookupClass)
        val combinedLookup = orMethod.invoke(loaderLookup, defaultLookup)

        // SymbolLookup.find(String) -> Optional<MemorySegment>
        val lookupFindMH = lookup.findVirtual(
            symbolLookupClass, "find",
            MethodType.methodType(java.util.Optional::class.java, String::class.java)
        )

        // Exact-callable form: the receiver is bound in (it never varies) and the return is
        // widened to Object, so the call site's static signature `(String) -> Any` matches the
        // handle's MethodType and `invokeExact` links. See "Always invokeExact" in this source
        // set's README.
        val findOptExact: MethodHandle = MethodHandles
            .insertArguments(lookupFindMH, 0, combinedLookup)
            .asType(MethodType.methodType(Any::class.java, String::class.java))

        // MemorySegment.address() with the receiver widened to Object, for the two sites that
        // hold a segment typed only as `Any`.
        val segmentAddressExact: MethodHandle = segmentAddressMH.asType(
            MethodType.methodType(Long::class.javaPrimitiveType, Any::class.java)
        )

        // Linker.downcallHandle(MemorySegment, FunctionDescriptor, Linker.Option...)
        val optionArrayClass = Class.forName("java.lang.foreign.Linker\$Option")
        val optionArrayType = java.lang.reflect.Array.newInstance(optionArrayClass, 0).javaClass
        val downcallHandleMethod = linkerClass.getMethod(
            "downcallHandle", memorySegmentClass, functionDescriptorClass, optionArrayType
        )
        val emptyOptions = java.lang.reflect.Array.newInstance(optionArrayClass, 0)

        val layoutArrayClass = java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        val fdOfMethod = functionDescriptorClass.getMethod(
            "of", memoryLayoutClass, layoutArrayClass
        )
        val fdOfVoidMethod = functionDescriptorClass.getMethod(
            "ofVoid", java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        )

        fun classToLayout(cls: Class<*>): Any = when (cls) {
            Byte::class.javaPrimitiveType -> javaByte
            Short::class.javaPrimitiveType -> javaShort
            Int::class.javaPrimitiveType -> javaInt
            Long::class.javaPrimitiveType -> javaLong
            Float::class.javaPrimitiveType -> javaFloat
            Double::class.javaPrimitiveType -> javaDouble
            else -> throw IllegalArgumentException("Unsupported type: $cls")
        }

        // ---- Hot-path string handles, folded into one exact-callable handle each ----
        //
        // `allocStr` and `readStr` each used to be two or three `MethodHandle.invoke` calls whose
        // intermediate value was a MemorySegment -- a type this file must not name, which is what
        // forced the inexact `invoke` and its per-call asType adaptation plus boxing of both the
        // segment and the returned long. Folding the intermediate away with filterReturnValue /
        // collectArguments removes the un-nameable type from the call site entirely: what is left
        // is `(String) -> long` and `(long) -> String`, both nameable, both `invokeExact`.

        // Arena.allocateFrom/allocateUtf8String with the global arena bound in, then .address()
        // folded onto the result: (String) -> long.
        val allocStrExact: MethodHandle = MethodHandles.filterReturnValue(
            MethodHandles.insertArguments(allocateFromMH, 0, globalArena),
            segmentAddressMH
        ).asType(MethodType.methodType(Long::class.javaPrimitiveType, String::class.java))

        // MemorySegment.ofAddress(addr).reinterpret(MAX_VALUE).getString(0): (long) -> String.
        val readStrExact: MethodHandle = MethodHandles.filterReturnValue(
            MethodHandles.filterReturnValue(
                ofAddressMH,
                MethodHandles.insertArguments(reinterpretMH, 1, Long.MAX_VALUE)
            ),
            MethodHandles.insertArguments(getStringMH, 1, 0L)
        ).asType(MethodType.methodType(String::class.java, Long::class.javaPrimitiveType))

        val allocStr: (String) -> Long = { str ->
            allocStrExact.invokeExact(str) as Long
        }

        val readStr: (Long) -> String? = { addr ->
            if (addr == 0L) null
            else readStrExact.invokeExact(addr) as String
        }

        val findSym: (String, Class<*>, Array<Class<*>>) -> MethodHandle = { name, retType, paramTypes ->
            val optAny: Any = findOptExact.invokeExact(name) as Any
            val optSeg = optAny as java.util.Optional<*>
            val seg = optSeg.orElseThrow {
                UnsatisfiedLinkError("Symbol not found: $name")
            }

            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, paramTypes.size)
            val mtParams = mutableListOf<Class<*>>()
            for (i in paramTypes.indices) {
                val p = paramTypes[i]
                if (p == POINTER_TYPE) {
                    java.lang.reflect.Array.set(layoutParams, i, javaLong)
                    mtParams.add(Long::class.javaPrimitiveType!!)
                } else {
                    java.lang.reflect.Array.set(layoutParams, i, classToLayout(p))
                    mtParams.add(p)
                }
            }

            val fd = if (retType == Void.TYPE) {
                fdOfVoidMethod.invoke(null, layoutParams)
            } else if (retType == POINTER_TYPE) {
                fdOfMethod.invoke(null, javaLong, layoutParams)
            } else {
                fdOfMethod.invoke(null, classToLayout(retType), layoutParams)
            }

            val rawHandle = downcallHandleMethod.invoke(
                linker, seg, fd, emptyOptions
            ) as MethodHandle
            
            rawHandle
        }

        // ---- Symbol address lookup (no downcall handle built) ----

        val findAddr: (String) -> Long = { name ->
            val optAny: Any = findOptExact.invokeExact(name) as Any
            val optSeg = optAny as java.util.Optional<*>
            if (optSeg.isPresent) {
                val seg: Any = optSeg.get() as Any
                segmentAddressExact.invokeExact(seg) as Long
            } else 0L
        }

        // ---- Freeable UTF-8 strings, backed by native malloc/free ----

        // `findSym` builds these from FunctionDescriptors made entirely of JAVA_LONG, so their
        // MethodTypes are literally (long) -> long and (long) -> void: nothing to adapt, and
        // `invokeExact` links against Kotlin's primitive `Long` directly.
        val mallocHandle = findSym("malloc", POINTER_TYPE, arrayOf(POINTER_TYPE))
        val freeHandle = findSym("free", Void.TYPE, arrayOf(POINTER_TYPE))

        // MemorySegment.ofArray(byte[]), return widened to Object: (byte[]) -> Any.
        val ofArrayExact: MethodHandle =
            lookup.unreflect(memorySegmentClass.getMethod("ofArray", ByteArray::class.java))
                .asType(MethodType.methodType(Any::class.java, ByteArray::class.java))

        // MemorySegment.copy(src, srcOff, dst, dstOff, bytes) with both segment parameters
        // widened to Object: (Any, long, Any, long, long) -> void.
        val copyExact: MethodHandle = lookup.unreflect(
            memorySegmentClass.getMethod(
                "copy", memorySegmentClass, Long::class.javaPrimitiveType, memorySegmentClass,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
        ).asType(
            MethodType.methodType(
                Void.TYPE, Any::class.java, Long::class.javaPrimitiveType, Any::class.java,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
            )
        )

        // MemorySegment.ofAddress(addr).reinterpret(len) as one handle: (long, long) -> Any.
        // collectArguments feeds ofAddress's result into reinterpret's receiver slot, so the
        // MemorySegment never reaches the call site.
        val addressToSegmentExact: MethodHandle =
            MethodHandles.collectArguments(reinterpretMH, 0, ofAddressMH).asType(
                MethodType.methodType(
                    Any::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType
                )
            )

        val allocFreeable: (String) -> Long = { str ->
            val bytes = str.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val len = bytes.size.toLong()
            val addr = mallocHandle.invokeExact(len) as Long
            if (addr == 0L) throw OutOfMemoryError("native malloc failed for UTF-8 string of length $len")
            val srcSeg: Any = ofArrayExact.invokeExact(bytes) as Any
            val dstSeg: Any = addressToSegmentExact.invokeExact(addr, len) as Any
            copyExact.invokeExact(srcSeg, 0L, dstSeg, 0L, len) as Unit
            addr
        }

        val freeAddr: (Long) -> Unit = { addr ->
            if (addr != 0L) {
                freeHandle.invokeExact(addr) as Unit
            }
        }

        // ---- Pointer-sized out-parameter slots ----
        //
        // Built from the same three handles `allocFreeable` uses, only with the copy running the
        // other way: native slot -> byte[] instead of byte[] -> native. Nothing new is looked up
        // reflectively, so this adds no additional JDK-version surface.

        val allocSlot: () -> Long = {
            val addr = mallocHandle.invokeExact(POINTER_BYTES) as Long
            if (addr == 0L) throw OutOfMemoryError("native malloc failed for a pointer slot")
            addr
        }

        val readSlot: (Long) -> Long = { addr ->
            val bytes = ByteArray(POINTER_BYTES.toInt())
            val srcSeg: Any = addressToSegmentExact.invokeExact(addr, POINTER_BYTES) as Any
            val dstSeg: Any = ofArrayExact.invokeExact(bytes) as Any
            copyExact.invokeExact(srcSeg, 0L, dstSeg, 0L, POINTER_BYTES) as Unit
            // The callee wrote a native `PyObject *`, so it has to be decoded in the platform's
            // byte order rather than ByteBuffer's big-endian default.
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).getLong(0)
        }

        // The same copy as `readSlot`, running the other way: byte[] -> native.
        val writeSlot: (Long, Long) -> Unit = { addr, value ->
            val bytes = ByteArray(POINTER_BYTES.toInt())
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).putLong(0, value)
            val srcSeg: Any = ofArrayExact.invokeExact(bytes) as Any
            val dstSeg: Any = addressToSegmentExact.invokeExact(addr, POINTER_BYTES) as Any
            copyExact.invokeExact(srcSeg, 0L, dstSeg, 0L, POINTER_BYTES) as Unit
        }

        val freeSlot: (Long) -> Unit = freeAddr

        // ---- Arbitrary bytes into a malloc buffer (non-UTF-8 C strings: wchar_t) ----
        //
        // `allocFreeable` above, minus its UTF-8 encoding: the caller hands over the finished
        // bytes, terminator included. Kept separate rather than having `allocFreeable` delegate to
        // it, so the per-string path that every `withUtf8` call takes gains no extra indirection.
        val allocBytes: (ByteArray) -> Long = { bytes ->
            val len = bytes.size.toLong()
            val addr = mallocHandle.invokeExact(len) as Long
            if (addr == 0L) throw OutOfMemoryError("native malloc failed for a $len-byte buffer")
            val srcSeg: Any = ofArrayExact.invokeExact(bytes) as Any
            val dstSeg: Any = addressToSegmentExact.invokeExact(addr, len) as Any
            copyExact.invokeExact(srcSeg, 0L, dstSeg, 0L, len) as Unit
            addr
        }

        // ---- Shape vocabulary: unbound downcall handles, one per (intArgs, floatArgs, returnKind) ----

        val downcallHandle2Method = linkerClass.getMethod("downcallHandle", functionDescriptorClass, optionArrayType)

        // Filter that converts an incoming `long` into the `MemorySegment` a raw downcall
        // handle's leading (target-address) parameter requires. filterArguments requires the
        // filter's return type to be identical to the target's parameter type at that
        // position, so asType() is used to give the (Any-returning) static adapter method the
        // exact reflectively-obtained MemorySegment return type.
        val longToSegmentExact = lookup.findStatic(
            Panama::class.java, "modernLongToSegment",
            MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
        ).asType(MethodType.methodType(memorySegmentClass, Long::class.javaPrimitiveType))

        val buildShape: (Int, Int, ReturnKind) -> MethodHandle = { intArgs, floatArgs, returnKind ->
            val total = intArgs + floatArgs
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, total)
            for (i in 0 until intArgs) java.lang.reflect.Array.set(layoutParams, i, javaLong)
            for (i in 0 until floatArgs) java.lang.reflect.Array.set(layoutParams, intArgs + i, javaDouble)

            val fd = when (returnKind) {
                ReturnKind.VOID -> fdOfVoidMethod.invoke(null, layoutParams)
                ReturnKind.LONG -> fdOfMethod.invoke(null, javaLong, layoutParams)
                ReturnKind.DOUBLE -> fdOfMethod.invoke(null, javaDouble, layoutParams)
            }

            val raw = downcallHandle2Method.invoke(linker, fd, emptyOptions) as MethodHandle
            MethodHandles.filterArguments(raw, 0, longToSegmentExact)
        }

        val upcallStubMethod = linkerClass.getMethod("upcallStub", MethodHandle::class.java, functionDescriptorClass, arenaClass, optionArrayType)
        // @UpcallShape(returnType = "long", parameterTypes = ["long"])
        val buildUpcallStub: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, javaLong)
            val fd = fdOfMethod.invoke(null, javaLong, layoutParams)
            
            // The handle expects a long, but upcall stub will pass a MemorySegment (in JDK 22+).
            // Actually, in JDK 22+, if fd specifies JAVA_LONG, the method handle must take a long!
            // Wait, in Panama, primitive types in fd map directly to primitives in MethodHandle.
            // So if fd is JAVA_LONG -> JAVA_LONG, the MethodHandle MUST have type (long)long.
            // No adaptation is needed!
            val stub: Any = upcallStubMethod.invoke(linker, handle, fd, globalArena, emptyOptions)
            segmentAddressExact.invokeExact(stub) as Long
        }

        // @UpcallShape(returnType = "long", parameterTypes = ["long", "long"])
        val buildUpcallStubII_L: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 2)
            java.lang.reflect.Array.set(layoutParams, 0, javaLong)
            java.lang.reflect.Array.set(layoutParams, 1, javaLong)
            val fd = fdOfMethod.invoke(null, javaLong, layoutParams)
            val stub: Any = upcallStubMethod.invoke(linker, handle, fd, globalArena, emptyOptions)
            segmentAddressExact.invokeExact(stub) as Long
        }

        // @UpcallShape(returnType = "int", parameterTypes = ["long", "long", "long"])
        val buildUpcallStubIII_I: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 3)
            java.lang.reflect.Array.set(layoutParams, 0, javaLong)
            java.lang.reflect.Array.set(layoutParams, 1, javaLong)
            java.lang.reflect.Array.set(layoutParams, 2, javaLong)
            val fd = fdOfMethod.invoke(null, javaInt, layoutParams)
            val stub: Any = upcallStubMethod.invoke(linker, handle, fd, globalArena, emptyOptions)
            segmentAddressExact.invokeExact(stub) as Long
        }

        // @UpcallShape(returnType = "int", parameterTypes = ["long"])
        val buildUpcallStubI_I: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, javaLong)
            val fd = fdOfMethod.invoke(null, javaInt, layoutParams)
            val stub: Any = upcallStubMethod.invoke(linker, handle, fd, globalArena, emptyOptions)
            segmentAddressExact.invokeExact(stub) as Long
        }

        // @UpcallShape(returnType = "void", parameterTypes = ["long"])
        val buildUpcallStubI_V: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, javaLong)
            val fd = fdOfVoidMethod.invoke(null, layoutParams)
            val stub: Any = upcallStubMethod.invoke(linker, handle, fd, globalArena, emptyOptions)
            segmentAddressExact.invokeExact(stub) as Long
        }

        return ModernData(allocStr, readStr, findSym, findAddr, allocFreeable, freeAddr, allocSlot, readSlot, writeSlot, freeSlot, allocBytes, buildShape, buildUpcallStub, buildUpcallStubII_L, buildUpcallStubIII_I, buildUpcallStubI_I, buildUpcallStubI_V)
    }

    private fun adaptModernHandle(
        raw: MethodHandle, retType: Class<*>, paramTypes: Array<Class<*>>,
        segmentClass: Class<*>,
        ofAddressMH: java.lang.reflect.Method,
        segmentAddressMH: MethodHandle,
        nullSegment: Any
    ): MethodHandle {
        var h = raw
        val lookup = MethodHandles.lookup()

        // Adapt return: MemorySegment -> long
        if (retType == POINTER_TYPE) {
            // raw returns MemorySegment, we want long
            // Insert an adapter: (MemorySegment) -> long via address()
            val segToLong = lookup.findStatic(
                Panama::class.java, "modernSegmentToLong",
                MethodType.methodType(Long::class.javaPrimitiveType, Any::class.java)
            ).asType(MethodType.methodType(Long::class.javaPrimitiveType, segmentClass))
            h = MethodHandles.filterReturnValue(h, segToLong)
        }

        // Adapt params: long -> MemorySegment for pointer positions
        for (i in paramTypes.indices) {
            if (paramTypes[i] == POINTER_TYPE) {
                val longToSeg = lookup.findStatic(
                    Panama::class.java, "modernLongToSegment",
                    MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
                ).asType(MethodType.methodType(segmentClass, Long::class.javaPrimitiveType))
                // The parameter index in the adapted handle: account for already-adapted params
                h = MethodHandles.filterArguments(h, i, longToSeg)
            }
        }

        return h
    }

    // Static adapters called via MethodHandle, must use Any to avoid importing MemorySegment
    @JvmStatic
    fun modernSegmentToLong(seg: Any?): Long {
        if (seg == null) return 0L
        return try {
            val cls = Class.forName("java.lang.foreign.MemorySegment")
            val m = cls.getMethod("address")
            m.invoke(seg) as Long
        } catch (e: Exception) { 
            e.printStackTrace()
            0L 
        }
    }

    @JvmStatic
    fun modernLongToSegment(addr: Long): Any {
        val cls = Class.forName("java.lang.foreign.MemorySegment")
        val m = cls.getMethod("ofAddress", Long::class.javaPrimitiveType!!)
        return m.invoke(null, addr)
    }

    // ---- Incubator backend: jdk.incubator.foreign (JDK 16-18) ----

    private class IncubatorData(
        val allocateUtf8String: (String) -> Long,
        val readUtf8String: (Long) -> String?,
        val findSymbol: (String, Class<*>, Array<Class<*>>) -> MethodHandle,
        val findSymbolAddress: (String) -> Long,
        val allocateUtf8Freeable: (String) -> Long,
        val freeUtf8Address: (Long) -> Unit,
        val allocatePointerSlot: () -> Long,
        val readPointerSlot: (Long) -> Long,
        val writePointerSlot: (Long, Long) -> Unit,
        val freePointerSlot: (Long) -> Unit,
        val allocateBytesFreeable: (ByteArray) -> Long,
        val unboundDowncallHandle: (Int, Int, ReturnKind) -> MethodHandle,
        val createUpcallStubLongToLong: (MethodHandle) -> Long,
        val createUpcallStubII_L: (MethodHandle) -> Long,
        val createUpcallStubIII_I: (MethodHandle) -> Long,
        val createUpcallStubI_I: (MethodHandle) -> Long,
        val createUpcallStubI_V: (MethodHandle) -> Long
    )

    private fun initIncubator(): IncubatorData {
        val lookup = MethodHandles.lookup()

        val clinkerClass = Class.forName("jdk.incubator.foreign.CLinker")
        val memoryAddressClass = Class.forName("jdk.incubator.foreign.MemoryAddress")
        val memoryScopeClass = Class.forName("jdk.incubator.foreign.ResourceScope")
        val symbolLookupClass = Class.forName("jdk.incubator.foreign.SymbolLookup")
        val functionDescriptorClass = Class.forName("jdk.incubator.foreign.FunctionDescriptor")
        val memoryLayoutClass = Class.forName("jdk.incubator.foreign.MemoryLayout")
        val valueLazyoutClass: Class<*>
        try {
            valueLazyoutClass = Class.forName("jdk.incubator.foreign.ValueLayout")
        } catch (_: ClassNotFoundException) {
            // JDK 16 uses CLinker constants directly
            throw UnsupportedOperationException("JDK 16 incubator not yet supported")
        }

        // CLinker.getInstance()
        val clinker = clinkerClass.getMethod("getInstance").invoke(null)

        // CLinker layout constants
        val cInt = clinkerClass.getField("C_INT").get(null)
        val cLongLong = clinkerClass.getField("C_LONG_LONG").get(null)
        val cFloat = clinkerClass.getField("C_FLOAT").get(null)
        val cDouble = clinkerClass.getField("C_DOUBLE").get(null)
        val cPointer = clinkerClass.getField("C_POINTER").get(null)
        val cChar = clinkerClass.getField("C_CHAR").get(null)
        val cShort = clinkerClass.getField("C_SHORT").get(null)

        // ResourceScope.newConfinedScope()
        val newScopeMethod = memoryScopeClass.getMethod("newConfinedScope")

        // CLinker.toCString(String, ResourceScope)
        val toCStringMethod = clinkerClass.getMethod("toCString", String::class.java, memoryScopeClass)

        // CLinker.toJavaString(MemorySegment)
        val memorySegmentClass = Class.forName("jdk.incubator.foreign.MemorySegment")
        val toJavaStringMethod = clinkerClass.getMethod("toJavaString", memorySegmentClass)

        // MemorySegment.address() -> MemoryAddress
        val segAddressMethod = memorySegmentClass.getMethod("address")

        // MemoryAddress.toRawLongValue()
        val toRawLongMethod = memoryAddressClass.getMethod("toRawLongValue")

        // MemoryAddress.ofLong(long)
        val ofLongMethod = memoryAddressClass.getMethod("ofLong", Long::class.javaPrimitiveType!!)

        // SymbolLookup.loaderLookup()
        val loaderLookup = symbolLookupClass.getMethod("loaderLookup").invoke(null)

        // SymbolLookup.lookup(String) -> Optional<MemoryAddress>
        val lookupMethod = symbolLookupClass.getMethod("lookup", String::class.java)

        // CLinker.downcallHandle(MemoryAddress, MethodType, FunctionDescriptor)
        val downcallMethod = clinkerClass.getMethod(
            "downcallHandle", memoryAddressClass,
            MethodType::class.java, functionDescriptorClass
        )

        // FunctionDescriptor.of / ofVoid
        val layoutArrayClass = java.lang.reflect.Array.newInstance(memoryLayoutClass, 0).javaClass
        val fdOfMethod = functionDescriptorClass.getMethod("of", memoryLayoutClass, layoutArrayClass)
        val fdOfVoidMethod = functionDescriptorClass.getMethod("ofVoid", layoutArrayClass)

        fun classToLayout(cls: Class<*>): Any = when (cls) {
            Byte::class.javaPrimitiveType -> cChar
            Short::class.javaPrimitiveType -> cShort
            Int::class.javaPrimitiveType -> cInt
            Long::class.javaPrimitiveType -> cLongLong
            Float::class.javaPrimitiveType -> cFloat
            Double::class.javaPrimitiveType -> cDouble
            else -> throw IllegalArgumentException("Unsupported type: $cls")
        }

        val allocStr: (String) -> Long = { str ->
            val scope = newScopeMethod.invoke(null)
            val seg = toCStringMethod.invoke(null, str, scope)
            val addr = segAddressMethod.invoke(seg)
            toRawLongMethod.invoke(addr) as Long
        }

        val readStr: (Long) -> String? = { addr ->
            if (addr == 0L) null
            else {
                val memAddr = ofLongMethod.invoke(null, addr)
                // MemoryAddress implements MemorySegment in the incubator
                toJavaStringMethod.invoke(null, memAddr) as String
            }
        }

        val findSym: (String, Class<*>, Array<Class<*>>) -> MethodHandle = { name, retType, paramTypes ->
            val optSeg = lookupMethod.invoke(loaderLookup, name) as java.util.Optional<*>
            val symAddr = optSeg.orElseThrow {
                UnsatisfiedLinkError("Symbol not found: $name")
            }

            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, paramTypes.size)
            val mtParams = mutableListOf<Class<*>>()
            for (i in paramTypes.indices) {
                val p = paramTypes[i]
                if (p == POINTER_TYPE) {
                    java.lang.reflect.Array.set(layoutParams, i, cLongLong)
                    mtParams.add(Long::class.javaPrimitiveType!!)
                } else {
                    java.lang.reflect.Array.set(layoutParams, i, classToLayout(p))
                    mtParams.add(p)
                }
            }

            val fd = if (retType == Void.TYPE) {
                fdOfVoidMethod.invoke(null, layoutParams)
            } else if (retType == POINTER_TYPE) {
                fdOfMethod.invoke(null, cLongLong, layoutParams)
            } else {
                fdOfMethod.invoke(null, classToLayout(retType), layoutParams)
            }

            val mtRet = if (retType == POINTER_TYPE) Long::class.javaPrimitiveType!!
                        else if (retType == Void.TYPE) Void.TYPE
                        else retType
            val mt = MethodType.methodType(mtRet, mtParams)

            val rawHandle = downcallMethod.invoke(clinker, symAddr, mt, fd) as MethodHandle

            rawHandle
        }

        // ---- Symbol address lookup (no downcall handle built) ----

        val findAddr: (String) -> Long = { name ->
            val optSeg = lookupMethod.invoke(loaderLookup, name) as java.util.Optional<*>
            if (optSeg.isPresent) toRawLongMethod.invoke(optSeg.get()) as Long else 0L
        }

        // ---- Freeable UTF-8 strings ----
        //
        // Unlike the modern (malloc/free) implementation, this reuses toCString's
        // ResourceScope: each allocation gets its own confined scope, tracked by address so
        // freeUtf8Address can close it (ResourceScope.close() releases the backing memory).
        // JDK 16-18 is a legacy fallback not exercised by this repo's verification matrix
        // (JDK 21+ always resolves the modern java.lang.foreign backend); this path is a
        // best-effort mirror of the modern one, not independently verified.

        val scopeCloseMethod = memoryScopeClass.getMethod("close")
        val freeableScopes = java.util.concurrent.ConcurrentHashMap<Long, Any>()

        val allocFreeable: (String) -> Long = { str ->
            val scope = newScopeMethod.invoke(null)
            val seg = toCStringMethod.invoke(null, str, scope)
            val addr = segAddressMethod.invoke(seg)
            val raw = toRawLongMethod.invoke(addr) as Long
            freeableScopes[raw] = scope
            raw
        }

        // ---- Pointer-sized out-parameter slots: not available on this backend ----
        //
        // Allocating one would be easy here, but *reading* it back is not: this backend reaches
        // native memory only through `toCString`/`toJavaString`, which stop at the first NUL and
        // so cannot recover a pointer, and the bulk-access class that could (`MemoryAccess`)
        // changed shape across JDK 16, 17 and 18. Guessing at it would produce a wrong
        // `PyObject *` -- a use-after-free at the caller, found far from here.
        //
        // So this refuses at the first step instead, which is also the only step that has not
        // yet allocated anything. JDK 21+ resolves the modern backend, where these are
        // implemented, so nothing this repo builds or tests reaches this message.
        val slotsUnsupported: () -> Nothing = {
            throw UnsupportedOperationException(
                "Pointer out-parameter slots are not implemented on the jdk.incubator.foreign " +
                    "backend (JDK 16-18). Run on JDK 19 or newer, where java.lang.foreign provides " +
                    "the bulk memory access this needs."
            )
        }
        val allocSlot: () -> Long = { slotsUnsupported() }
        val readSlot: (Long) -> Long = { slotsUnsupported() }
        val writeSlot: (Long, Long) -> Unit = { _, _ -> slotsUnsupported() }
        val freeSlot: (Long) -> Unit = { slotsUnsupported() }

        // Same reason as the slots: this backend writes native memory only through `toCString`,
        // which cannot place a wchar_t string. JDK 19+ resolves the modern backend.
        val allocBytes: (ByteArray) -> Long = {
            throw UnsupportedOperationException(
                "Writing arbitrary bytes to native memory is not implemented on the " +
                    "jdk.incubator.foreign backend (JDK 16-18). Run on JDK 19 or newer."
            )
        }

        val freeAddr: (Long) -> Unit = { addr ->
            freeableScopes.remove(addr)?.let { scopeCloseMethod.invoke(it) }
            Unit
        }

        // ---- Shape vocabulary: unbound downcall handles ----

        val downcallHandleUnboundMethod = clinkerClass.getMethod(
            "downcallHandle", MethodType::class.java, functionDescriptorClass
        )

        val longToAddrExact = lookup.findStatic(
            Panama::class.java, "incubatorLongToAddr",
            MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
        ).asType(MethodType.methodType(memoryAddressClass, Long::class.javaPrimitiveType))

        val buildShape: (Int, Int, ReturnKind) -> MethodHandle = { intArgs, floatArgs, returnKind ->
            val total = intArgs + floatArgs
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, total)
            for (i in 0 until intArgs) java.lang.reflect.Array.set(layoutParams, i, cLongLong)
            for (i in 0 until floatArgs) java.lang.reflect.Array.set(layoutParams, intArgs + i, cDouble)

            val fd = when (returnKind) {
                ReturnKind.VOID -> fdOfVoidMethod.invoke(null, layoutParams)
                ReturnKind.LONG -> fdOfMethod.invoke(null, cLongLong, layoutParams)
                ReturnKind.DOUBLE -> fdOfMethod.invoke(null, cDouble, layoutParams)
            }

            val mtParams = mutableListOf<Class<*>>()
            repeat(intArgs) { mtParams.add(Long::class.javaPrimitiveType!!) }
            repeat(floatArgs) { mtParams.add(Double::class.javaPrimitiveType!!) }
            val mtRet: Class<*> = when (returnKind) {
                ReturnKind.VOID -> Void.TYPE
                ReturnKind.LONG -> Long::class.javaPrimitiveType!!
                ReturnKind.DOUBLE -> Double::class.javaPrimitiveType!!
            }
            val mt = MethodType.methodType(mtRet, mtParams)

            val raw = downcallHandleUnboundMethod.invoke(clinker, mt, fd) as MethodHandle
            MethodHandles.filterArguments(raw, 0, longToAddrExact)
        }

        val upcallStubMethod = clinkerClass.getMethod("upcallStub", MethodHandle::class.java, functionDescriptorClass)
        // @UpcallShape(returnType = "long", parameterTypes = ["long"])
        val buildUpcallStub: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, cLongLong)
            val fd = fdOfMethod.invoke(null, cLongLong, layoutParams)
            
            val stub = upcallStubMethod.invoke(clinker, handle, fd)
            toRawLongMethod.invoke(segAddressMethod.invoke(stub)) as Long
        }

        // @UpcallShape(returnType = "long", parameterTypes = ["long", "long"])
        val buildUpcallStubII_L: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 2)
            java.lang.reflect.Array.set(layoutParams, 0, cLongLong)
            java.lang.reflect.Array.set(layoutParams, 1, cLongLong)
            val fd = fdOfMethod.invoke(null, cLongLong, layoutParams)
            val stub = upcallStubMethod.invoke(clinker, handle, fd)
            toRawLongMethod.invoke(segAddressMethod.invoke(stub)) as Long
        }

        // @UpcallShape(returnType = "int", parameterTypes = ["long", "long", "long"])
        val buildUpcallStubIII_I: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 3)
            java.lang.reflect.Array.set(layoutParams, 0, cLongLong)
            java.lang.reflect.Array.set(layoutParams, 1, cLongLong)
            java.lang.reflect.Array.set(layoutParams, 2, cLongLong)
            val fd = fdOfMethod.invoke(null, cInt, layoutParams)
            val stub = upcallStubMethod.invoke(clinker, handle, fd)
            toRawLongMethod.invoke(segAddressMethod.invoke(stub)) as Long
        }

        // @UpcallShape(returnType = "int", parameterTypes = ["long"])
        val buildUpcallStubI_I: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, cLongLong)
            val fd = fdOfMethod.invoke(null, cInt, layoutParams)
            val stub = upcallStubMethod.invoke(clinker, handle, fd)
            toRawLongMethod.invoke(segAddressMethod.invoke(stub)) as Long
        }

        // @UpcallShape(returnType = "void", parameterTypes = ["long"])
        val buildUpcallStubI_V: (MethodHandle) -> Long = { handle ->
            val layoutParams = java.lang.reflect.Array.newInstance(memoryLayoutClass, 1)
            java.lang.reflect.Array.set(layoutParams, 0, cLongLong)
            val fd = fdOfVoidMethod.invoke(null, layoutParams)
            val stub = upcallStubMethod.invoke(clinker, handle, fd)
            toRawLongMethod.invoke(segAddressMethod.invoke(stub)) as Long
        }

        return IncubatorData(allocStr, readStr, findSym, findAddr, allocFreeable, freeAddr, allocSlot, readSlot, writeSlot, freeSlot, allocBytes, buildShape, buildUpcallStub, buildUpcallStubII_L, buildUpcallStubIII_I, buildUpcallStubI_I, buildUpcallStubI_V)
    }

    private fun adaptIncubatorHandle(
        raw: MethodHandle, retType: Class<*>, paramTypes: Array<Class<*>>,
        memoryAddressClass: Class<*>,
        ofLongMethod: java.lang.reflect.Method,
        toRawLongMethod: java.lang.reflect.Method
    ): MethodHandle {
        var h = raw
        val lookup = MethodHandles.lookup()

        // Adapt return: MemoryAddress -> long
        if (retType == POINTER_TYPE) {
            val addrToLong = lookup.findStatic(
                Panama::class.java, "incubatorAddrToLong",
                MethodType.methodType(Long::class.javaPrimitiveType, Any::class.java)
            )
            h = MethodHandles.filterReturnValue(h, addrToLong)
        }

        // Adapt params: long -> MemoryAddress for pointer positions
        for (i in paramTypes.indices) {
            if (paramTypes[i] == POINTER_TYPE) {
                val longToAddr = lookup.findStatic(
                    Panama::class.java, "incubatorLongToAddr",
                    MethodType.methodType(Any::class.java, Long::class.javaPrimitiveType)
                )
                h = MethodHandles.filterArguments(h, i, longToAddr)
            }
        }

        return h
    }

    @JvmStatic
    fun incubatorAddrToLong(addr: Any?): Long {
        if (addr == null) return 0L
        return try {
            val cls = Class.forName("jdk.incubator.foreign.MemoryAddress")
            val m = cls.getMethod("toRawLongValue")
            m.invoke(addr) as Long
        } catch (e: Exception) { 
            e.printStackTrace()
            0L 
        }
    }

    @JvmStatic
    fun incubatorLongToAddr(v: Long): Any {
        val cls = Class.forName("jdk.incubator.foreign.MemoryAddress")
        val m = cls.getMethod("ofLong", Long::class.javaPrimitiveType!!)
        return m.invoke(null, v)
    }

    // ---- Detection ----

    private fun detectBackend(): Pair<Backend, Any> {
        // Try modern first (JDK 19+)
        try {
            Class.forName("java.lang.foreign.Linker")
            return Backend.MODERN to initModern()
        } catch (_: ClassNotFoundException) { }

        // Try incubator (JDK 16-18)
        try {
            Class.forName("jdk.incubator.foreign.CLinker")
            return Backend.INCUBATOR to initIncubator()
        } catch (_: ClassNotFoundException) { }

        val ver = System.getProperty("java.version", "unknown")
        throw UnsupportedOperationException(
            "No usable Panama FFI found on JDK $ver. " +
            "Requires JDK 22+ (java.lang.foreign), " +
            "JDK 19-21 with --enable-preview, " +
            "or JDK 16-18 with --add-modules=jdk.incubator.foreign."
        )
    }
}
