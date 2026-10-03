package fixture.android

import python.multiplatform.reflection.PythonInternal

/**
 * The Python-facing surface of the Android fixture.
 *
 * Deliberately the same shapes `ksp-fixtures/library` uses on desktop -- a top-level function, a
 * narrowing `Int` signature, a `Unit` return, a class with a method and a mutable property, and
 * one `@PythonInternal` exclusion. The point is not to re-test the generator's policy (the
 * desktop fixture does that against a far bigger surface) but to prove the *same* generator
 * output is produced and runs when KSP is driven from a module carrying an Android plugin.
 */
fun androidDouble(x: Long): Long = x * 2

fun androidGreet(name: String): String = "Hello from Android, $name!"

fun androidScale(value: Int, by: Int): Int = value * by

fun androidNoArgs() {
    // exercises the Unit-return path
}

@PythonInternal
fun androidHidden(): String = "must not appear in the generated table"

class AndroidCounter(var count: Long = 0) {
    /** ROADMAP §13's second defect, on the AGP side of the generator: `isMutable` is true and the
     * setter is not something generated code may name. `ksp-fixtures/library` pins all three
     * restricted visibilities; this one only checks the same decision is made over here. */
    var lastLabel: String = ""
        private set

    fun increment(by: Long): Long {
        count += by
        return count
    }

    fun label(prefix: String): String {
        lastLabel = "$prefix$count"
        return lastLabel
    }
}
