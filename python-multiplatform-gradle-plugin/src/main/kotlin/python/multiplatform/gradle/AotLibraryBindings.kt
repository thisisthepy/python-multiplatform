package python.multiplatform.gradle

import groovy.json.JsonSlurper
import org.gradle.api.GradleException

/*
 * Kotlin wrappers for a TypedPython AOT library (SPEC N-17, #458).
 *
 * Provenance. That a build can produce a C-ABI library a Kotlin app calls without CPython is owner decision D5
 * (2026-11-16). That the desktop JVM comes first, and that the generated Kotlin is self-contained (java.lang.foreign
 * reached by reflection, pointers as JAVA_LONG, calls through invokeExact, no dependency on the CPython-embedding
 * library) are leader decisions of 2026-11-16, not owner-confirmed. The shape of the generated API (one object per
 * library, the list classes, the exception, the tuple data classes, the per-thread scratch memory) is an agent
 * proposal, not owner-confirmed.
 *
 * The input is the `<prefix>.json` description `python -m typedpython aot-lib` writes
 * (`typedpython/aot/library.py`); nothing else is read. These functions are pure: text in, text out.
 */

/**
 * The `abi_version` of the description this generator reads: 2, the only value, and every other value (1 included) is
 * rejected. There are no deployed version 1 libraries, and the compiler in this repository (`typedpython/aot/library.py`)
 * writes 2 only, so no reader of version 1 is kept. Version 2 added the type `int_list` and its `lists` entry (#465;
 * leader decision 2026-11-16, not owner-confirmed).
 */
const val AOT_LIBRARY_ABI_VERSION = 2

/** Out pointers of one function the per-thread scratch block of the helper holds (16 slots of 8 bytes). */
const val AOT_MAX_RETURNS = 16

/** The boundary vocabulary of `typedpython.aot.library.VOCABULARY`. */
val AOT_LIBRARY_TYPES: List<String> = listOf("i64", "f64", "bool", "f64_list", "i64_list", "int_list")

data class AotParam(val name: String, val type: String)

data class AotFunction(val name: String, val symbol: String, val params: List<AotParam>, val returns: List<String>)

/** The five C symbols of one list type. */
data class AotListSymbols(val new: String, val len: String, val data: String, val retain: String, val release: String)

/** The six C symbols of the `int_list` type: it has `get` and `set` and no `data` (its elements are not int64 in memory). */
data class AotIntListSymbols(
    val new: String, val len: String, val get: String, val set: String, val retain: String, val release: String,
)

data class AotLibraryDescription(
    val prefix: String,
    val library: String,
    val init: String,
    val lastError: String,
    val functions: List<AotFunction>,
    val f64List: AotListSymbols,
    val i64List: AotListSymbols,
    /** Present only when some signature of the library uses the type `int_list`. */
    val intList: AotIntListSymbols? = null,
)

private fun fail(message: String): Nothing = throw GradleException(message)

/** Reads a description. Fails with a [GradleException] on another `abi_version`, a missing key, or a type outside [AOT_LIBRARY_TYPES]. */
fun parseAotLibraryDescription(json: String): AotLibraryDescription {
    val root = (try {
        JsonSlurper().parseText(json)
    } catch (e: Exception) {
        fail("the AOT library description is not valid JSON: ${e.message}")
    }) as? Map<*, *> ?: fail("the AOT library description is not a JSON object")

    val version = (root["abi_version"] as? Number)?.toInt()
        ?: fail("the AOT library description has no \"abi_version\"")
    if (version != AOT_LIBRARY_ABI_VERSION) {
        fail(
            "the AOT library description has abi_version $version; this generator reads abi_version " +
                "$AOT_LIBRARY_ABI_VERSION only (generate the Kotlin with a plugin that matches the compiler that wrote it)",
        )
    }
    fun text(map: Map<*, *>, key: String, where: String): String =
        map[key] as? String ?: fail("the AOT library description has no string \"$key\" in $where")

    val prefix = text(root, "prefix", "the description")
    val runtime = root["runtime"] as? Map<*, *> ?: fail("the AOT library description has no \"runtime\" object")
    val lists = root["lists"] as? Map<*, *> ?: fail("the AOT library description has no \"lists\" object")

    fun listSymbols(key: String): AotListSymbols {
        val entry = lists[key] as? Map<*, *> ?: fail("the AOT library description has no \"$key\" under \"lists\"")
        return AotListSymbols(
            text(entry, "new", key), text(entry, "len", key), text(entry, "data", key),
            text(entry, "retain", key), text(entry, "release", key),
        )
    }

    val intList = (lists["int_list"] as? Map<*, *>)?.let { entry ->
        AotIntListSymbols(
            text(entry, "new", "int_list"), text(entry, "len", "int_list"), text(entry, "get", "int_list"),
            text(entry, "set", "int_list"), text(entry, "retain", "int_list"), text(entry, "release", "int_list"),
        )
    }

    val rawFunctions = root["functions"] as? List<*> ?: fail("the AOT library description has no \"functions\" array")
    val functions = rawFunctions.map { raw ->
        val f = raw as? Map<*, *> ?: fail("an entry of \"functions\" is not an object")
        val name = text(f, "name", "a function")
        val params = (f["params"] as? List<*> ?: fail("function `$name` has no \"params\" array")).map { p ->
            val pm = p as? Map<*, *> ?: fail("a parameter of function `$name` is not an object")
            val type = text(pm, "type", "a parameter of `$name`")
            if (type !in AOT_LIBRARY_TYPES) {
                fail("function `$name` has a parameter of type `$type`, which is outside the boundary vocabulary $AOT_LIBRARY_TYPES")
            }
            AotParam(text(pm, "name", "a parameter of `$name`"), type)
        }
        val returns = (f["returns"] as? List<*> ?: fail("function `$name` has no \"returns\" array")).map { r ->
            val type = r as? String ?: fail("a return type of function `$name` is not a string")
            if (type !in AOT_LIBRARY_TYPES) {
                fail("function `$name` returns type `$type`, which is outside the boundary vocabulary $AOT_LIBRARY_TYPES")
            }
            type
        }
        AotFunction(name, text(f, "symbol", "function `$name`"), params, returns)
    }
    if (intList == null) {
        for (f in functions) {
            if (f.params.any { it.type == "int_list" } || "int_list" in f.returns) {
                fail("function `${f.name}` uses the type `int_list`, but the description has no \"int_list\" under \"lists\"")
            }
        }
    }
    return AotLibraryDescription(
        prefix = prefix,
        library = text(root, "library", "the description"),
        init = text(runtime, "init", "\"runtime\""),
        lastError = text(runtime, "last_error", "\"runtime\""),
        functions = functions,
        f64List = listSymbols("f64_list"),
        i64List = listSymbols("i64_list"),
        intList = intList,
    )
}

private val KOTLIN_HARD_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)

/** Members of the generated object that an exported function must not take the name of. */
private val RESERVED_FUNCTION_NAMES = setOf("load", "native", "Handles", "toString", "hashCode", "equals")

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** A Kotlin identifier for `name`: backticked when it is a hard keyword. */
internal fun aotKotlinName(name: String): String = if (name in KOTLIN_HARD_KEYWORDS) "`$name`" else name

private fun kotlinType(type: String): String = when (type) {
    "i64" -> "Long"
    "f64" -> "Double"
    "bool" -> "Boolean"
    "f64_list" -> "F64List"
    "i64_list" -> "I64List"
    "int_list" -> "IntList"
    else -> fail("unknown boundary type `$type`")
}

/** The carrier letter of a parameter in the helper's descriptor strings: `J` long, `D` double, `B` byte (`uint8_t`). */
internal fun aotCarrier(type: String): Char = when (type) {
    "i64", "f64_list", "i64_list", "int_list" -> 'J'
    "f64" -> 'D'
    "bool" -> 'B'
    else -> fail("unknown boundary type `$type`")
}

/** The descriptor of an exported function's symbol: its parameters, then one `J` per out pointer, returning the `I` status. */
internal fun aotDescriptor(f: AotFunction): String =
    "(" + f.params.joinToString("") { aotCarrier(it.type).toString() } + "J".repeat(f.returns.size) + ")I"

/** A GraalVM foreign downcall descriptor in carrier names (`long`, `double`, `byte`, `int`, `void`). */
data class AotForeignDescriptor(val returnType: String, val parameterTypes: List<String>)

private fun carrierName(code: Char): String = when (code) {
    'J' -> "long"
    'D' -> "double"
    'B' -> "byte"
    'I' -> "int"
    'V' -> "void"
    else -> fail("unknown carrier type `$code`")
}

internal fun aotParseDescriptorSignature(signature: String): AotForeignDescriptor {
    val close = signature.indexOf(')')
    require(signature.startsWith("(") && close > 0 && close == signature.length - 2) {
        "malformed descriptor signature `$signature`"
    }
    val params = signature.substring(1, close).map(::carrierName)
    return AotForeignDescriptor(carrierName(signature[close + 1]), params)
}

/**
 * Every distinct foreign downcall descriptor the generated `Handles` class of [d] binds, sorted deterministically
 * (matching `GenerateReachabilityMetadata` in `python-multiplatform`).
 */
fun aotDowncallDescriptors(d: AotLibraryDescription): List<AotForeignDescriptor> {
    val signatures = LinkedHashSet<String>()
    signatures += "()I"      // init
    signatures += "(JJ)I"    // last_error
    signatures += "(J)J"     // f64_list / i64_list _new, _len, _data; int_list _new, _len
    signatures += "(J)V"     // f64_list / i64_list / int_list _retain, _release
    if (d.intList != null) {
        signatures += "(JJJ)I" // int_list _get, _set
    }
    for (f in d.functions) {
        signatures += aotDescriptor(f)
    }
    return signatures
        .map(::aotParseDescriptorSignature)
        .distinct()
        .sortedWith(
            compareBy<AotForeignDescriptor>(
                { it.parameterTypes.size },
                { it.returnType },
                { it.parameterTypes.joinToString(",") },
            ),
        )
}

/** Classpath location where GraalVM native-image reads per-library reachability metadata automatically. */
fun aotReachabilityMetadataPath(packageName: String, prefix: String): String =
    "META-INF/native-image/$packageName/$prefix/reachability-metadata.json"

/**
 * The spelling GraalVM's foreign configuration parser accepts for a carrier type. It does not take Java type names:
 * `MemoryLayoutParser` looks each name up in `Linker.nativeLinker().canonicalLayouts()` (bool, char, short, int, long,
 * long long, float, double, void*, size_t, wchar_t) and only the return type may also be `void`
 * (`ForeignFunctionsConfigurationParser`, sources shipped in the NIK as lib/svm/builder/svm-foreign.src.zip).
 * `long long` is the 64-bit layout on every platform (`long` is 32-bit on Windows); `char` is `JAVA_BYTE`.
 */
internal fun aotGraalLayoutName(carrier: String): String = when (carrier) {
    "long" -> "long long"
    "double" -> "double"
    "byte" -> "char"
    "int" -> "int"
    "void" -> "void"
    else -> fail("unknown carrier type `$carrier`")
}

internal fun aotCarrierNameOfGraalLayout(layout: String): String = when (layout) {
    "long long" -> "long"
    "double" -> "double"
    "char" -> "byte"
    "int" -> "int"
    "void" -> "void"
    else -> fail("unknown GraalVM layout `$layout`")
}

/**
 * GraalVM `reachability-metadata.json` for [d]: every foreign downcall descriptor bound by `Handles` and every
 * `java.lang.foreign` reflection entry used once at link time by `AotFfmRuntime`.
 */
fun renderAotReachabilityMetadata(d: AotLibraryDescription): String = buildString {
    val downcalls = aotDowncallDescriptors(d)
    append("{\n")
    append("  \"foreign\": {\n")
    append("    \"downcalls\": [\n")
    downcalls.forEachIndexed { index, desc ->
        val params = desc.parameterTypes.joinToString(", ") { "\"${aotGraalLayoutName(it)}\"" }
        val comma = if (index < downcalls.lastIndex) "," else ""
        append("      { \"returnType\": \"${aotGraalLayoutName(desc.returnType)}\", \"parameterTypes\": [$params] }$comma\n")
    }
    append("    ]\n")
    append("  },\n")
    append("  \"reflection\": [\n")
    val entries = listOf(
        """    { "type": "java.lang.foreign.MemorySegment", "allPublicMethods": true, "allPublicFields": true }""",
        """    { "type": "java.lang.foreign.Arena", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.Linker", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.Linker${'$'}Option", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.Linker${'$'}Option[]" }""",
        """    { "type": "java.lang.foreign.SymbolLookup", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.FunctionDescriptor", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.ValueLayout", "allPublicMethods": true, "allPublicFields": true }""",
        """    { "type": "java.lang.foreign.ValueLayout${'$'}OfLong", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.ValueLayout${'$'}OfDouble", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.ValueLayout${'$'}OfByte", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.MemoryLayout", "allPublicMethods": true }""",
        """    { "type": "java.lang.foreign.MemoryLayout[]" }""",
    )
    entries.forEachIndexed { index, entry ->
        val comma = if (index < entries.lastIndex) "," else ""
        append(entry).append(comma).append('\n')
    }
    append("  ]\n")
    append("}\n")
}

internal fun aotResultClassName(functionName: String): String =
    functionName.split('_').filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) } + "Result"

private fun validate(d: AotLibraryDescription, packageName: String, objectName: String) {
    if (packageName.isEmpty() || packageName.split('.').any { !IDENTIFIER.matches(it) || it in KOTLIN_HARD_KEYWORDS }) {
        fail("the package name `$packageName` is not a dotted Kotlin package name")
    }
    if (!IDENTIFIER.matches(objectName) || objectName in KOTLIN_HARD_KEYWORDS) {
        fail("the object name `$objectName` is not a Kotlin identifier")
    }
    if (objectName in setOf("F64List", "I64List", "IntList", "AotFfm", "AotFfmRuntime", "TypedPythonAotException")) {
        fail("the object name `$objectName` is one of the generated helper classes")
    }
    val seen = mutableSetOf<String>()
    val results = mutableMapOf<String, String>()
    for (f in d.functions) {
        if (!IDENTIFIER.matches(f.name)) fail("function `${f.name}` is not a Kotlin-compatible identifier")
        if (f.name in RESERVED_FUNCTION_NAMES) fail("function `${f.name}` has a name the generated object reserves")
        if (f.name.startsWith("__")) fail("function `${f.name}` starts with `__`, which the generated code reserves for its locals")
        if (!seen.add(f.name)) fail("function `${f.name}` is described twice")
        for (p in f.params) {
            if (!IDENTIFIER.matches(p.name)) fail("parameter `${p.name}` of function `${f.name}` is not a Kotlin-compatible identifier")
            if (p.name.startsWith("__")) fail("parameter `${p.name}` of function `${f.name}` starts with `__`, which the generated code reserves")
        }
        if (f.returns.size > AOT_MAX_RETURNS) {
            fail("function `${f.name}` returns ${f.returns.size} values; the scratch block holds $AOT_MAX_RETURNS")
        }
        if (f.returns.size > 1) {
            val cls = aotResultClassName(f.name)
            val other = results.put(cls, f.name)
            if (other != null) fail("functions `$other` and `${f.name}` would both generate the class `$cls`")
            if (cls == objectName) fail("function `${f.name}` would generate the class `$cls`, the name of the object")
        }
    }
}

private const val GENERATED = "// GENERATED by python-multiplatform-gradle-plugin (GenerateAotLibraryBindingsTask). Do not edit."

/**
 * The generated sources of one library, as relative file name to text: `AotFfm.kt` (the helper and the exception, the
 * same text for every library), `<objectName>.kt`, `F64List.kt` and `I64List.kt`, and `IntList.kt` only when the description has the `int_list` entry, all in
 * [packageName].
 */
fun renderAotLibraryBindings(d: AotLibraryDescription, packageName: String, objectName: String): Map<String, String> {
    validate(d, packageName, objectName)
    val files = linkedMapOf(
        "AotFfm.kt" to renderAotFfmHelper(packageName),
        "$objectName.kt" to renderAotObject(d, packageName, objectName),
        "F64List.kt" to renderAotList(packageName, objectName, "F64List", "f64", "Double", "DoubleArray", "Double", 8, "list[float]"),
        "I64List.kt" to renderAotList(packageName, objectName, "I64List", "i64", "Long", "LongArray", "Long", 8, "list[int]"),
    )
    if (d.intList != null) files["IntList.kt"] = renderAotIntList(packageName, objectName)
    return files
}

/** The text of the FFM helper: the same for every library but its package line. */
fun renderAotFfmHelper(packageName: String): String {
    val template = GenerateAotLibraryBindingsTask::class.java.getResourceAsStream("/aot/AotFfm.kt.txt")
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: fail("the plugin jar is missing its resource /aot/AotFfm.kt.txt")
    return template.replace("%PACKAGE%", packageName)
}

private fun renderAotObject(d: AotLibraryDescription, packageName: String, objectName: String): String {
    val sb = StringBuilder()
    fun line(text: String = "") {
        sb.append(text).append('\n')
    }
    line(GENERATED)
    line("//")
    line("// Library `${d.prefix}` (${d.library}), description abi_version $AOT_LIBRARY_ABI_VERSION. Owner decision D5 (2026-11-16) is that the")
    line("// library output exists; desktop JVM first and a self-contained generated file are leader decisions of 2026-11-16,")
    line("// not owner-confirmed; the shape of this API is an agent proposal, not owner-confirmed.")
    line("@file:Suppress(\"unused\", \"FunctionName\", \"ClassName\")")
    line()
    line("package $packageName")
    line()
    line("import java.lang.invoke.MethodHandle")
    line()
    for (f in d.functions.filter { it.returns.size > 1 }) {
        val fields = f.returns.mapIndexed { i, t -> "val v$i: ${kotlinType(t)}" }.joinToString(", ")
        line("/** The tuple `${f.name}` returns, in order. */")
        line("data class ${aotResultClassName(f.name)}($fields)")
        line()
    }
    line("/**")
    line(" * The compiled library `${d.prefix}`: one function per export, with no CPython in the process.")
    line(" *")
    line(" * Call [load] once with the path of `${d.library}` before anything else. A failure inside the library")
    line(" * arrives as a [TypedPythonAotException] with the class name and message the Python language gives. A list")
    line(" * argument is borrowed for the call; the same list object passed twice is the same list inside, as in Python.")
    line(" * The functions may be called from several threads at once; the library holds no state.")
    line(" */")
    line("object $objectName {")
    line("    @Volatile")
    line("    private var loaded: Handles? = null")
    line()
    line("    /**")
    line("     * Loads the shared library at [libraryPath] into this JVM, binds every symbol once and calls `${d.init}`.")
    line("     * Idempotent and thread-safe: a second call with the same path returns, with another path it throws.")
    line("     */")
    line("    @Synchronized")
    line("    fun load(libraryPath: String) {")
    line("        val path = java.io.File(libraryPath).absoluteFile.normalize().path")
    line("        val current = loaded")
    line("        if (current != null) {")
    line("            check(current.path == path) { \"${d.prefix} is already loaded from \" + current.path + \", not from \" + path }")
    line("            return")
    line("        }")
    line("        val runtime = AotFfm.runtime()")
    line("        runtime.loadLibrary(path)")
    line("        val handles = Handles(runtime, path)")
    line("        val status = handles.aotInit.invokeExact() as Int")
    line("        if (status != 0) throw runtime.lastError(handles.lastError)")
    line("        loaded = handles")
    line("    }")
    line()
    line("    internal fun native(): Handles = loaded ?: throw IllegalStateException(\"${d.prefix}: call $objectName.load(path) first\")")
    line()
    line("    /** Every downcall handle, bound once by [load]. The descriptor strings are the carrier types: J long or pointer, D double, B byte, I int. */")
    line("    internal class Handles(val rt: AotFfmRuntime, val path: String) {")
    line("        val aotInit: MethodHandle = rt.downcall(\"${d.init}\", \"()I\")")
    line("        val lastError: MethodHandle = rt.downcall(\"${d.lastError}\", \"(JJ)I\")")
    for ((prefix, s) in listOf("f64" to d.f64List, "i64" to d.i64List)) {
        line("        val ${prefix}ListNew: MethodHandle = rt.downcall(\"${s.new}\", \"(J)J\")")
        line("        val ${prefix}ListLen: MethodHandle = rt.downcall(\"${s.len}\", \"(J)J\")")
        line("        val ${prefix}ListData: MethodHandle = rt.downcall(\"${s.data}\", \"(J)J\")")
        line("        val ${prefix}ListRetain: MethodHandle = rt.downcall(\"${s.retain}\", \"(J)V\")")
        line("        val ${prefix}ListRelease: MethodHandle = rt.downcall(\"${s.release}\", \"(J)V\")")
    }
    d.intList?.let { s ->
        line("        val intListNew: MethodHandle = rt.downcall(\"${s.new}\", \"(J)J\")")
        line("        val intListLen: MethodHandle = rt.downcall(\"${s.len}\", \"(J)J\")")
        line("        val intListGet: MethodHandle = rt.downcall(\"${s.get}\", \"(JJJ)I\")")
        line("        val intListSet: MethodHandle = rt.downcall(\"${s.set}\", \"(JJJ)I\")")
        line("        val intListRetain: MethodHandle = rt.downcall(\"${s.retain}\", \"(J)V\")")
        line("        val intListRelease: MethodHandle = rt.downcall(\"${s.release}\", \"(J)V\")")
    }
    for (f in d.functions) {
        line("        val f_${f.name}: MethodHandle = rt.downcall(\"${f.symbol}\", \"${aotDescriptor(f)}\")")
    }
    line()
    line("        fun handles(): Map<String, MethodHandle> = linkedMapOf(")
    line("            \"aotInit\" to aotInit,")
    line("            \"lastError\" to lastError,")
    for (prefix in listOf("f64", "i64")) {
        for (op in listOf("New", "Len", "Data", "Retain", "Release")) {
            line("            \"${prefix}List$op\" to ${prefix}List$op,")
        }
    }
    if (d.intList != null) {
        for (op in listOf("New", "Len", "Get", "Set", "Retain", "Release")) {
            line("            \"intList$op\" to intList$op,")
        }
    }
    for (f in d.functions) {
        line("            \"f_${f.name}\" to f_${f.name},")
    }
    line("        )")
    line("    }")
    for (f in d.functions) {
        line()
        line(renderAotFunction(f))
    }
    line("}")
    return sb.toString()
}

private fun renderAotFunction(f: AotFunction): String {
    val params = f.params.joinToString(", ") { "${aotKotlinName(it.name)}: ${kotlinType(it.type)}" }
    val result = when (f.returns.size) {
        0 -> ""
        1 -> ": ${kotlinType(f.returns[0])}"
        else -> ": ${aotResultClassName(f.name)}"
    }
    val args = f.params.map {
        val n = aotKotlinName(it.name)
        when (it.type) {
            "bool" -> "(if ($n) 1 else 0).toByte()"
            "f64_list", "i64_list", "int_list" -> "$n.pointer()"
            else -> n
        }
    } + f.returns.indices.map { if (it == 0) "__out" else "__out + ${it * 8}L" }
    val read = f.returns.mapIndexed { i, t ->
        val at = if (i == 0) "__out" else "__out + ${i * 8}L"
        when (t) {
            "i64" -> "__h.rt.readLong($at)"
            "f64" -> "__h.rt.readDouble($at)"
            "bool" -> "__h.rt.readByte($at).toInt() != 0"
            "f64_list" -> "F64List.owned(__h, __h.rt.readLong($at))"
            "i64_list" -> "I64List.owned(__h, __h.rt.readLong($at))"
            "int_list" -> "IntList.owned(__h, __h.rt.readLong($at))"
            else -> fail("unknown boundary type `$t`")
        }
    }
    val sb = StringBuilder()
    sb.append("    /** `${f.name}`, the C symbol `${f.symbol}`. */\n")
    sb.append("    fun ${aotKotlinName(f.name)}($params)$result {\n")
    sb.append("        val __h = native()\n")
    if (f.returns.isNotEmpty()) sb.append("        val __out = __h.rt.scratch()\n")
    sb.append("        val __rc = __h.f_${f.name}.invokeExact(${args.joinToString(", ")}) as Int\n")
    sb.append("        if (__rc != 0) throw __h.rt.lastError(__h.lastError)\n")
    when (f.returns.size) {
        0 -> {}
        1 -> sb.append("        return ${read[0]}\n")
        else -> sb.append("        return ${aotResultClassName(f.name)}(${read.joinToString(", ")})\n")
    }
    sb.append("    }")
    return sb.toString()
}

@Suppress("LongParameterList")
private fun renderAotList(
    packageName: String,
    objectName: String,
    cls: String,
    prefix: String,
    element: String,
    array: String,
    accessor: String,
    width: Int,
    pythonType: String,
): String {
    val sb = StringBuilder()
    fun line(text: String = "") {
        sb.append(text).append('\n')
    }
    val h = "${prefix}List"
    line(GENERATED)
    line("//")
    line("// The Kotlin side of the library's counted `$pythonType` handle. Agent proposal, not owner-confirmed.")
    line("@file:Suppress(\"unused\")")
    line()
    line("package $packageName")
    line()
    line("/**")
    line(" * A `$pythonType` of the compiled library: a fixed-length array of $element in native memory, behind a counted handle.")
    line(" *")
    line(" * - Create one with `$cls(size)` (all zero) or `$cls.of(array)`; a list an exported function returns is wrapped as an")
    line(" *   owned handle. The list never changes length.")
    line(" * - [close] gives up this object's ownership, once; calling it again does nothing. Use after [close] throws")
    line(" *   [IllegalStateException]. An unclosed list is not freed by the garbage collector (agent proposal).")
    line(" * - Passing the same object for two parameters passes the same handle, so the parameters alias as they do in Python.")
    line(" *   A function that returns its argument gives a new object over the same native list: a write through one is seen")
    line(" *   through the other, and each is closed on its own.")
    line(" * - Element writes from several threads to the same list are the caller's responsibility, exactly as for a Kotlin")
    line(" *   array: a racing read sees the old or the new value. Do not call [close] while another thread is using the list.")
    line(" */")
    line("class $cls private constructor(private val lib: $objectName.Handles, pointer: Long) : AutoCloseable {")
    line("    private val owner = java.util.concurrent.atomic.AtomicLong(pointer)")
    line()
    line("    /** The number of elements. */")
    line("    val size: Int = Math.toIntExact(lib.${h}Len.invokeExact(pointer) as Long)")
    line()
    line("    private val data: Long = lib.${h}Data.invokeExact(pointer) as Long")
    line()
    line("    /** A list of [size] zeros. Needs the library loaded. */")
    line("    constructor(size: Int) : this($objectName.native(), allocate($objectName.native(), size))")
    line()
    line("    /** True once [close] has run. */")
    line("    val isClosed: Boolean get() = owner.get() == 0L")
    line()
    line("    /** The native handle for a call; throws [IllegalStateException] when closed. */")
    line("    internal fun pointer(): Long {")
    line("        val p = owner.get()")
    line("        if (p == 0L) throw IllegalStateException(\"$cls is closed\")")
    line("        return p")
    line("    }")
    line()
    line("    operator fun get(index: Int): $element {")
    line("        pointer()")
    line("        checkIndex(index)")
    line("        return lib.rt.read$accessor(data + index.toLong() * ${width}L)")
    line("    }")
    line()
    line("    operator fun set(index: Int, value: $element) {")
    line("        pointer()")
    line("        checkIndex(index)")
    line("        lib.rt.write$accessor(data + index.toLong() * ${width}L, value)")
    line("    }")
    line()
    line("    /** A copy of the elements. */")
    line("    fun to$array(): $array {")
    line("        pointer()")
    line("        val out = $array(size)")
    line("        for (i in out.indices) out[i] = lib.rt.read$accessor(data + i.toLong() * ${width}L)")
    line("        return out")
    line("    }")
    line()
    line("    private fun checkIndex(index: Int) {")
    line("        if (index < 0 || index >= size) throw IndexOutOfBoundsException(\"index \" + index + \" out of bounds for length \" + size)")
    line("    }")
    line()
    line("    /** Gives up this object's ownership of the native list, once. */")
    line("    override fun close() {")
    line("        val p = owner.getAndSet(0L)")
    line("        if (p != 0L) lib.${h}Release.invokeExact(p) as Unit")
    line("    }")
    line()
    line("    override fun toString(): String = \"$cls(size=\" + size + (if (isClosed) \", closed\" else \"\") + \")\"")
    line()
    line("    companion object {")
    line("        /** A new list holding a copy of [values]. Needs the library loaded. */")
    line("        fun of(values: $array): $cls {")
    line("            val list = $cls(values.size)")
    line("            try {")
    line("                for (i in values.indices) list[i] = values[i]")
    line("            } catch (e: Throwable) {")
    line("                list.close()")
    line("                throw e")
    line("            }")
    line("            return list")
    line("        }")
    line()
    line("        /** Wraps a handle the library returned: the caller owns one count of it. */")
    line("        internal fun owned(lib: $objectName.Handles, pointer: Long): $cls = $cls(lib, pointer)")
    line()
    line("        private fun allocate(lib: $objectName.Handles, size: Int): Long {")
    line("            require(size >= 0) { \"size must not be negative: \" + size }")
    line("            val pointer = lib.${h}New.invokeExact(size.toLong()) as Long")
    line("            if (pointer == 0L) throw lib.rt.lastError(lib.lastError)")
    line("            return pointer")
    line("        }")
    line("    }")
    line("}")
    return sb.toString()
}

/**
 * `IntList.kt`: the Kotlin side of the library's `int_list` handle, a `list[int]` that may hold values outside 64 bits
 * (ABI version 2, #465; leader decision 2026-11-16, not owner-confirmed; the shape of the class is an agent proposal,
 * not owner-confirmed). Unlike the other list classes it has no memory to read: every element access is a call into
 * the library, and a failure of that call (an index out of range, an element that does not fit in 64 bits) is the
 * library's own error record as a [TypedPythonAotException], with no bounds check on the Kotlin side.
 */
private fun renderAotIntList(packageName: String, objectName: String): String {
    val sb = StringBuilder()
    fun line(text: String = "") {
        sb.append(text).append('\n')
    }
    line(GENERATED)
    line("//")
    line("// The Kotlin side of the library's counted `list[int]` handle that may hold big ints (abi_version 2). Leader decision")
    line("// 2026-11-16 for the kind, not owner-confirmed; the shape of this class is an agent proposal, not owner-confirmed.")
    line("@file:Suppress(\"unused\")")
    line()
    line("package $packageName")
    line()
    line("/**")
    line(" * A `list[int]` of the compiled library whose elements may be outside 64 bits: a counted handle with no memory of")
    line(" * its own that Kotlin can read. A parameter or result the library types as this kind takes and gives only this class.")
    line(" *")
    line(" * - Cost: [get] and [set] are each a call into the library through a method handle, slower than [I64List], which reads")
    line(" *   native memory directly. Use [I64List] where the library's signature says it, and this class where it says this.")
    line(" * - [get] of an element that does not fit in 64 bits throws [TypedPythonAotException] with class `OverflowError`")
    line(" *   (the message says the element does not fit in 64 bits); the same element reads fine after a [set] of a smaller")
    line(" *   value. [toLongArray] fails the same way if any element does not fit. An index out of range, [get] or [set], throws")
    line(" *   [TypedPythonAotException] with class `IndexError` and the messages of the source language; a negative index counts")
    line(" *   from the end, as in Python. There is no Kotlin-side bounds check.")
    line(" * - Create one with `IntList(size)` (all zero) or `IntList.of(array)`; a list an exported function returns is wrapped")
    line(" *   as an owned handle. The list never changes length.")
    line(" * - Not interchangeable with [I64List] or [F64List]: the types are the protection. Passing a handle of the wrong kind")
    line(" *   at the C level is memory corruption, and the generated functions accept only the kind their signature names.")
    line(" * - [close] gives up this object's ownership, once; calling it again does nothing. Use after [close] throws")
    line(" *   [IllegalStateException]. An unclosed list is not freed by the garbage collector (agent proposal).")
    line(" * - Passing the same object for two parameters passes the same handle, so the parameters alias as they do in Python.")
    line(" * - Threads: [get] and [set] of the same list from several threads are memory-safe. The library takes a small lock")
    line(" *   inside the list for each element access (agent proposal, not owner-confirmed), so a reader sees the old or the")
    line(" *   new element and no big integer is freed twice or used after being freed, unlike [I64List] and [F64List], whose")
    line(" *   racing reads and writes behave as a Kotlin array does. Which of two writers to the same element wins is the")
    line(" *   caller's responsibility. Do not call [close] while another thread is using the list.")
    line(" * - Which list kind a signature has follows from the whole program: a change in a helper that several exports share")
    line(" *   can turn another export's [I64List] into an [IntList], so regenerate and rebuild the callers together.")
    line(" */")
    line("class IntList private constructor(private val lib: $objectName.Handles, pointer: Long) : AutoCloseable {")
    line("    private val owner = java.util.concurrent.atomic.AtomicLong(pointer)")
    line()
    line("    /** The number of elements. */")
    line("    val size: Int = Math.toIntExact(lib.intListLen.invokeExact(pointer) as Long)")
    line()
    line("    /** A list of [size] zeros. Needs the library loaded. */")
    line("    constructor(size: Int) : this($objectName.native(), allocate($objectName.native(), size))")
    line()
    line("    /** True once [close] has run. */")
    line("    val isClosed: Boolean get() = owner.get() == 0L")
    line()
    line("    /** The native handle for a call; throws [IllegalStateException] when closed. */")
    line("    internal fun pointer(): Long {")
    line("        val p = owner.get()")
    line("        if (p == 0L) throw IllegalStateException(\"IntList is closed\")")
    line("        return p")
    line("    }")
    line()
    line("    /** The element at [index] (negative counts from the end); `OverflowError` if it does not fit in 64 bits, `IndexError` out of range. */")
    line("    operator fun get(index: Int): Long {")
    line("        val p = pointer()")
    line("        val out = lib.rt.scratch()")
    line("        val rc = lib.intListGet.invokeExact(p, index.toLong(), out) as Int")
    line("        if (rc != 0) throw lib.rt.lastError(lib.lastError)")
    line("        return lib.rt.readLong(out)")
    line("    }")
    line()
    line("    /** Stores [value] at [index] (negative counts from the end); `IndexError` out of range. */")
    line("    operator fun set(index: Int, value: Long) {")
    line("        val p = pointer()")
    line("        val rc = lib.intListSet.invokeExact(p, index.toLong(), value) as Int")
    line("        if (rc != 0) throw lib.rt.lastError(lib.lastError)")
    line("    }")
    line()
    line("    /** A copy of the elements; fails with `OverflowError` if any element does not fit in 64 bits. */")
    line("    fun toLongArray(): LongArray {")
    line("        pointer()")
    line("        val out = LongArray(size)")
    line("        for (i in out.indices) out[i] = get(i)")
    line("        return out")
    line("    }")
    line()
    line("    /** Gives up this object's ownership of the native list, once. */")
    line("    override fun close() {")
    line("        val p = owner.getAndSet(0L)")
    line("        if (p != 0L) lib.intListRelease.invokeExact(p) as Unit")
    line("    }")
    line()
    line("    override fun toString(): String = \"IntList(size=\" + size + (if (isClosed) \", closed\" else \"\") + \")\"")
    line()
    line("    companion object {")
    line("        /** A new list holding a copy of [values]. Needs the library loaded. */")
    line("        fun of(values: LongArray): IntList {")
    line("            val list = IntList(values.size)")
    line("            try {")
    line("                for (i in values.indices) list[i] = values[i]")
    line("            } catch (e: Throwable) {")
    line("                list.close()")
    line("                throw e")
    line("            }")
    line("            return list")
    line("        }")
    line()
    line("        /** Wraps a handle the library returned: the caller owns one count of it. */")
    line("        internal fun owned(lib: $objectName.Handles, pointer: Long): IntList = IntList(lib, pointer)")
    line()
    line("        private fun allocate(lib: $objectName.Handles, size: Int): Long {")
    line("            require(size >= 0) { \"size must not be negative: \" + size }")
    line("            val pointer = lib.intListNew.invokeExact(size.toLong()) as Long")
    line("            if (pointer == 0L) throw lib.rt.lastError(lib.lastError)")
    line("            return pointer")
    line("        }")
    line("    }")
    line("}")
    return sb.toString()
}
