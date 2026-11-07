import python.multiplatform.ffi.Python3

fun main() {
    // `./gradlew run` stages the CPython this release was built against and sets PYTHONHOME for it.
    Python3.initialize()
    Python3.exec(
        """
        import sys, json
        print("Python", sys.version.split()[0], "from Kotlin")
        print(json.dumps({"sum": sum(range(10))}))
        sys.stdout.flush()  # Python buffers stdout; the JVM does not flush it for us on exit
        """.trimIndent(),
    )
    // eval(expression, Py_eval_input = 258, globals, locals): the result is a Python object
    val globals = Python3.import("__main__").getAttr("__dict__")
    println("Kotlin sees: " + Python3.eval("6 * 7", 258, globals, globals))
}
