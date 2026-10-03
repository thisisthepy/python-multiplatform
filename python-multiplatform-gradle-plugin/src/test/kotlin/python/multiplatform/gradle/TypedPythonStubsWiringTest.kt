package python.multiplatform.gradle

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC B-9 (python-multiplatform#27): the stubs task output reaches toolchain's `typedpythonStubs`
 * configuration by name, with the task dependency attached, whichever side appears first.
 */
class TypedPythonStubsWiringTest {

    private val configurationName = "typedpythonStubs"

    private fun projectWith(sourceSets: List<String>): Project {
        val project = ProjectBuilder.builder().build()
        val container = project.container(DummySourceSet::class.java) { name -> DummySourceSet(name, project) }
        sourceSets.forEach { container.create(it) }
        project.extensions.add("kotlin", DummyKotlinExt(container))
        sourceSets.forEach { project.configurations.create("${it}Classpath") }
        return project
    }

    private fun wire(project: Project, sourceSets: List<String>) {
        val extension = project.extensions.create("pythonBindingsUnderTest", PythonBindingsExtension::class.java)
        extension.artifactIncludePackages.set(listOf("junit.runner"))
        extension.artifactTargets.set(sourceSets.associate { "${it}Classpath" to it })
        PythonBindingsPlugin().configureArtifactBindings(project, extension)
    }

    private fun stubsDir(project: Project, sourceSet: String) =
        project.layout.buildDirectory.dir("generated/pythonStubs/$sourceSet").get().asFile

    @Test
    fun aConfigurationThatExistsBeforeTheWiringReceivesEverySourceSetsStubs() {
        val sets = listOf("desktopMain", "androidNativeArm64Main")
        val project = projectWith(sets)
        project.configurations.create(configurationName)
        wire(project, sets)

        val files = project.configurations.getByName(configurationName).files
        sets.forEach { assertTrue(stubsDir(project, it) in files, "$it missing from $files") }
    }

    @Test
    fun aConfigurationCreatedAfterTheWiringReceivesTheStubsToo() {
        val sets = listOf("desktopMain", "androidNativeArm64Main")
        val project = projectWith(sets)
        wire(project, sets)
        project.configurations.create(configurationName)

        val files = project.configurations.getByName(configurationName).files
        sets.forEach { assertTrue(stubsDir(project, it) in files, "$it missing from $files") }
    }

    @Test
    fun resolvingTheConfigurationDependsOnTheStubsTask() {
        val project = projectWith(listOf("desktopMain"))
        val configuration = project.configurations.create(configurationName)
        wire(project, listOf("desktopMain"))

        val deps = configuration.buildDependencies.getDependencies(null)
        val names = deps.map { it.name }
        assertTrue(
            names.any { it.startsWith("generatePythonStubs") },
            "the task must travel with the files; dependencies were $names",
        )
    }

    @Test
    fun withoutTheConfigurationNothingIsCreatedAndNothingFails() {
        val project = projectWith(listOf("desktopMain"))
        wire(project, listOf("desktopMain"))

        assertNull(project.configurations.findByName(configurationName))
        assertFalse(project.tasks.names.isEmpty())
    }
}
