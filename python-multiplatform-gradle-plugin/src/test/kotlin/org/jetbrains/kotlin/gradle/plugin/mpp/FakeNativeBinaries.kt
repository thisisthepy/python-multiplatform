package org.jetbrains.kotlin.gradle.plugin.mpp

/**
 * Test doubles for the two Kotlin Gradle Plugin binary types `IosPythonXcframework.wireFrameworks`
 * tells apart (issue #90). The plugin carries no KGP types and matches `Framework` by its fully
 * qualified name, so the double has to live in KGP's package; only the members the wiring reads
 * reflectively are declared -- `getLinkerOpts()` (a `MutableList<String>`, as on KGP's `NativeBinary`)
 * and `getLinkTaskName()`.
 */
open class Framework(val linkTaskName: String) {
    val linkerOpts: MutableList<String> = mutableListOf()
}

/** What Gradle's instantiator hands back for a managed `Framework`: a generated subclass. */
class Framework_Decorated(linkTaskName: String) : Framework(linkTaskName)

/** A binary that is not a framework (KGP's test executable); must be left alone. */
class TestExecutable(val linkTaskName: String) {
    val linkerOpts: MutableList<String> = mutableListOf()
}
