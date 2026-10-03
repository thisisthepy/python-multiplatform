package python.multiplatform.gradle

import org.gradle.testfixtures.ProjectBuilder
import python.multiplatform.gradle.artifact.PythonArtifactBindingsTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ROADMAP §16f item 1: the walker was verified against exactly one (configuration, source set)
 * pair, because `artifactConfiguration`/`artifactSourceSet` are single-valued.
 *
 * `artifactTargets` makes the answer plural without deriving it. Deriving it is what this plugin
 * cannot do -- it carries no Kotlin Gradle Plugin types (see `TEST_WORD`), so it cannot ask a
 * target what its compile classpath is called, and Android's variant-aware configurations do not
 * follow the `<target>CompileClasspath` convention a derivation would have to assume.
 *
 * The two things that have to hold once it is plural: a build that already sets the singular pair
 * keeps the task name it invokes (`generatePythonArtifactBindings`, unsuffixed), and two pairs
 * produce two tasks writing to two directories rather than one task silently winning.
 */
class ArtifactTargetsWiringTest {

    private fun projectWith(sourceSets: List<String>, configurations: List<String>): org.gradle.api.Project {
        val project = ProjectBuilder.builder().build()
        val container = project.container(DummySourceSet::class.java) { name -> DummySourceSet(name, project) }
        sourceSets.forEach { container.create(it) }
        project.extensions.add("kotlin", DummyKotlinExt(container))
        configurations.forEach { project.configurations.create(it) }
        return project
    }

    private fun extensionOf(project: org.gradle.api.Project): PythonBindingsExtension =
        project.extensions.create("pythonBindingsUnderTest", PythonBindingsExtension::class.java)

    @Test
    fun theSingularPairKeepsItsUnsuffixedTaskNameSoAnExistingBuildStillInvokesIt() {
        val project = projectWith(listOf("desktopMain"), listOf("desktopCompileClasspath"))
        val extension = extensionOf(project)
        extension.artifactIncludePackages.set(listOf("junit.runner"))
        extension.artifactConfiguration.set("desktopCompileClasspath")
        extension.artifactSourceSet.set("desktopMain")

        PythonBindingsPlugin().configureArtifactBindings(project, extension)

        assertNotNull(
            project.tasks.findByName("generatePythonArtifactBindings"),
            "the name an existing build already invokes: ${project.tasks.names}",
        )
        assertNull(
            project.tasks.findByName("generatePythonArtifactBindingsDesktopMain"),
            "a single pair must not be renamed: ${project.tasks.names}",
        )
    }

    @Test
    fun twoPairsEachGetTheirOwnTaskAndTheirOwnOutputDirectory() {
        val project = projectWith(
            sourceSets = listOf("desktopMain", "androidNativeArm64Main"),
            configurations = listOf("desktopCompileClasspath", "androidNativeArm64CompileClasspath"),
        )
        val extension = extensionOf(project)
        extension.artifactIncludePackages.set(listOf("junit.runner"))
        extension.artifactTargets.set(
            mapOf(
                "desktopCompileClasspath" to "desktopMain",
                "androidNativeArm64CompileClasspath" to "androidNativeArm64Main",
            ),
        )

        PythonBindingsPlugin().configureArtifactBindings(project, extension)

        val bindings = project.tasks.names.filter { it.startsWith("generatePythonArtifactBindings") }
        assertEquals(2, bindings.size, "one task per pair, got $bindings")

        // Named after the source set rather than the configuration: the source set is already what
        // distinguishes the output directories, so the two names cannot disagree about which pair
        // a task belongs to.
        val outputs = bindings.map { name ->
            (project.tasks.getByName(name) as PythonArtifactBindingsTask)
                .outputDirectory.get().asFile.path
        }
        assertEquals(outputs.size, outputs.toSet().size, "two pairs wrote to one directory: $outputs")
        assertTrue(outputs.any { it.endsWith("pythonArtifactBindings/desktopMain") }, outputs.toString())
        assertTrue(
            outputs.any { it.endsWith("pythonArtifactBindings/androidNativeArm64Main") },
            outputs.toString(),
        )
    }

    @Test
    fun theSingularPairAndTheMapCanBeSetTogetherWithoutRegisteringTheSameTaskTwice() {
        val project = projectWith(
            sourceSets = listOf("desktopMain", "androidNativeArm64Main"),
            configurations = listOf("desktopCompileClasspath", "androidNativeArm64CompileClasspath"),
        )
        val extension = extensionOf(project)
        extension.artifactIncludePackages.set(listOf("junit.runner"))
        extension.artifactConfiguration.set("desktopCompileClasspath")
        extension.artifactSourceSet.set("desktopMain")
        extension.artifactTargets.set(mapOf("androidNativeArm64CompileClasspath" to "androidNativeArm64Main"))

        PythonBindingsPlugin().configureArtifactBindings(project, extension)

        assertEquals(
            2,
            project.tasks.names.count { it.startsWith("generatePythonArtifactBindings") },
            "the singular pair folds into the map rather than duplicating it: ${project.tasks.names}",
        )
    }
}
