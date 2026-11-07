// Maven Central publishing, applied by the three published components (the library, the KSP
// processor and the Gradle plugin, which is an included build):
//
//     apply(from = rootProject.file("gradle/central-publishing.gradle.kts"))   // root build
//     apply(from = rootDir.resolve("../gradle/central-publishing.gradle.kts")) // plugin build
//
// What it adds to every Maven publication of the project (ROADMAP 15.1):
//   * the POM fields Central requires: name, description, url, license, developers, scm;
//   * an empty javadoc jar and, where the publication has none, an empty sources jar (Central
//     refuses a jar publication without both; pom-only plugin markers need neither);
//   * a local `centralStaging` repository, a plain Maven layout under <repo>/build/central-staging,
//     which `.github/scripts/release/central-bundle.sh` zips for the Central Portal upload API.
//     Every component writes the same directory, so one deployment carries all of them;
//   * in-memory PGP signing of every publication, only when a key is given
//     (MAVEN_GPG_PRIVATE_KEY / MAVEN_GPG_PASSPHRASE in the environment, or the Gradle properties
//     signingInMemoryKey / signingInMemoryKeyPassword). Without a key nothing is signed, so
//     `publishToMavenLocal` keeps working on a machine that has none.
// Nothing here uploads anything; the upload is .github/scripts/release/central-upload.sh.

import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningExtension

val repoRoot: File = if (rootDir.resolve("gradle.properties").isFile && rootDir.resolve("gradle").isDirectory) {
    rootDir
} else {
    rootDir.parentFile
}
val stagingDir: File = (findProperty("centralStagingDir") as String?)?.let(::File)
    ?: repoRoot.resolve("build/central-staging")

val signingKey: String? = (findProperty("signingInMemoryKey") as String?)
    ?: System.getenv("MAVEN_GPG_PRIVATE_KEY")?.takeIf { it.isNotBlank() }
val signingPassword: String = (findProperty("signingInMemoryKeyPassword") as String?)
    ?: System.getenv("MAVEN_GPG_PASSPHRASE")
    ?: ""

val projectUrl = "https://github.com/thisisthepy/python-multiplatform"

val emptyJar = { classifier: String ->
    tasks.register<Jar>("central${classifier.replaceFirstChar { it.uppercase() }}Jar") {
        archiveClassifier.set(classifier)
        destinationDirectory.set(layout.buildDirectory.dir("central-empty-jars"))
        // Central only checks that the jar exists; the KDoc lives in the sources jar.
        from(rootProject.file("LICENSE")) { into("META-INF") }
    }
}

fun publicationIsBuilt(publication: org.gradle.api.publish.Publication, logger: org.gradle.api.logging.Logger): Boolean {
    publication as MavenPublication
    val missing = publication.artifacts.filter { !it.file.exists() }
    if (missing.isNotEmpty()) {
        logger.warn("CENTRAL-SKIPPED ${publication.groupId}:${publication.artifactId}: not built here: " +
            missing.joinToString { it.file.name })
    }
    return missing.isEmpty()
}

pluginManager.withPlugin("maven-publish") {
    apply(plugin = "signing")

    val publishing = extensions.getByType<PublishingExtension>()
    publishing.repositories.maven {
        name = "centralStaging"
        url = stagingDir.toURI()
    }

    val javadocJar = emptyJar("javadoc")
    val sourcesJar = emptyJar("sources")

    publishing.publications.withType<MavenPublication>().configureEach {
        val publication = this
        // A plugin marker is a pom-only publication: nothing to attach.
        val markerOnly = publication.name.endsWith("PluginMarkerMaven")
        pom {
            name.set(provider { publication.artifactId })
            description.set(
                "Embeds CPython in Kotlin Multiplatform and provides Kotlin <-> Python interoperability " +
                    "(${publication.artifactId})."
            )
            url.set(projectUrl)
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thisisthepy")
                    name.set("thisisthepy")
                    email.set("thisisthepy@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/thisisthepy/python-multiplatform.git")
                developerConnection.set("scm:git:ssh://git@github.com/thisisthepy/python-multiplatform.git")
                url.set(projectUrl)
            }
        }
        if (!markerOnly) {
            // Attached once every plugin has added its own artifacts (KGP adds the sources jars of
            // its targets late); a classifier already present is left alone.
            gradle.projectsEvaluated {
                val classifiers = publication.artifacts.mapNotNull { it.classifier }.toSet()
                if ("javadoc" !in classifiers) publication.artifact(javadocJar)
                if ("sources" !in classifiers) publication.artifact(sourcesJar)
            }
        }
    }

    if (signingKey != null) {
        extensions.configure<SigningExtension> {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications)
        }
        // KMP publications are created late and read the .asc files without declaring the tasks
        // that write them; make every publish task wait for every signing task.
        tasks.withType<AbstractPublishToMaven>().configureEach {
            dependsOn(tasks.withType<Sign>())
        }
    }

    // A publication whose file is not built on this machine is left out of the staging, loudly,
    // rather than failing the whole deployment. Today that is only `wasmRuntime`: its zip needs a
    // local Emscripten CPython build (-PwasmPythonDir) that CI does not make (ROADMAP 10.x), so
    // `central-bundle.sh` lists what was left out and a release owner decides whether that is fine.
    tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToCentralStagingRepository") }
        .configureEach {
            val task = this
            onlyIf { publicationIsBuilt(task.publication, task.logger) }
        }
    // The same for mavenLocal, so `publishAllToMavenLocal` works in a checkout without the wasm build.
    tasks.withType<PublishToMavenLocal>().configureEach {
        val task = this
        onlyIf { publicationIsBuilt(task.publication, task.logger) }
    }

    // One task per component that fills the staging directory with every publication.
    tasks.register("publishAllToCentralStaging") {
        group = "publishing"
        description = "Publishes every publication of this project to $stagingDir (signed when a key is given)."
        dependsOn(tasks.withType<AbstractPublishToMaven>().matching { it.name.endsWith("ToCentralStagingRepository") })
    }
}
