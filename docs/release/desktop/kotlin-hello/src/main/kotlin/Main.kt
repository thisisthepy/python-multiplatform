import python.multiplatform.ffi.Python3

fun main() {
    // `./gradlew run` stages the CPython this release was built against and sets PYTHONHOME for it.
    Python3.initialize()
    Python3.exec(
        """
        import sys, json
        print("Python", sys.version.split()[0], "from Kotlin")
        print(json.dumps({"sum": sum(range(10))}))
        """.trimIndent(),
    )
    // eval(expression) evaluates in __main__; the result is a Python object.
    println("Kotlin sees: " + Python3.eval("6 * 7"))
    // Python buffers sys.stdout; finalize() flushes it and ends the interpreter.
    Python3.finalize()
}
