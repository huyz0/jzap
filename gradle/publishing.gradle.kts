/**
 * Publishing to Maven Central, via the Central Portal's bundle upload.
 *
 * <h2>Why a bundle and a curl rather than a publishing plugin</h2>
 *
 * The Portal accepts a zip in Maven repository layout. Gradle's own `maven-publish` writes
 * exactly that layout to a `file://` repository, checksums included, and the `signing` plugin adds
 * the detached signatures beside each file -- so the bundle is the staging directory, zipped. That
 * leaves nothing between the artefacts and the upload except one HTTP request, which is worth more
 * here than the convenience of a plugin: this is a path that cannot be rehearsed without real
 * credentials, so the fewer moving parts between `./gradlew centralBundle` and what Sonatype
 * receives, the better. `./gradlew centralBundle` produces the exact bytes CI uploads, and
 * `unzip -l` on it is the whole verification.
 *
 * <h2>What is published where</h2>
 *
 * The eight library modules go to Central. jzap-gradle goes to the Gradle Plugin Portal instead,
 * which is where `plugins { id ... }` resolves from, and is configured in its own build script.
 * Fixtures, tools and jzap-e2e are not published: they are how jzap is tested, not what it is.
 *
 * <h2>Signing</h2>
 *
 * Central requires a detached signature for every artefact. The key arrives in the environment
 * rather than from a keyring on disk, because the only machine that signs a release is a CI
 * runner. Signing is skipped when no key is present, so an ordinary `./gradlew build` and a
 * `publishToMavenLocal` both work on a developer machine without one -- but `centralBundle`
 * refuses to build an unsigned bundle, because the Portal would reject it after the fact and the
 * failure is much cheaper here.
 */

val centralModules = listOf(
    "jzap-model", "jzap-core", "jzap-agent", "jzap-wire", "jzap-minion",
    "jzap-git", "jzap-report", "jzap-cli",
)

/** Where every module publishes before being zipped. One directory, so the zip is the layout. */
val stagingDir = layout.buildDirectory.dir("staging-deploy")

val signingKey: String? = providers.environmentVariable("GPG_SIGNING_KEY").orNull
val signingPassphrase: String? = providers.environmentVariable("GPG_SIGNING_KEY_PASSWORD").orNull

configure(centralModules.map { project(":$it") }) {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // Central rejects a deployment with no sources or javadoc, so these are not optional extras.
    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    // A missing @param on a package-private helper must not fail a release. Javadoc is a required
    // artefact here rather than a checked one; the compiler and the tests are what gate a change.
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    extensions.configure<PublishingExtension> {
        publications.create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set(descriptionOf(project.name))
                url.set("https://github.com/huyz0/jzap")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("huyz0")
                        name.set("Huy Nguyen")
                        url.set("https://github.com/huyz0")
                    }
                }
                scm {
                    url.set("https://github.com/huyz0/jzap")
                    connection.set("scm:git:https://github.com/huyz0/jzap.git")
                    developerConnection.set("scm:git:ssh://git@github.com/huyz0/jzap.git")
                }
            }
        }
        repositories {
            maven {
                name = "staging"
                url = uri(stagingDir)
            }
        }
    }

    extensions.configure<SigningExtension> {
        isRequired = signingKey != null
        if (signingKey != null) {
            useInMemoryPgpKeys(signingKey, signingPassphrase ?: "")
            sign(extensions.getByType<PublishingExtension>().publications["maven"])
        }
    }
}

/** One line per module, because a Central listing with no description is close to useless. */
fun descriptionOf(module: String): String = when (module) {
    "jzap-model" -> "jzap's project model and result schemas: the seam between build tools and the engine."
    "jzap-core" -> "The jzap mutation testing engine: discovery, mutators, schemata, coverage and scheduling."
    "jzap-agent" -> "jzap's dependency-free Java agent: class overrides, coverage probes and the loop guard."
    "jzap-wire" -> "jzap's dependency-free controller/minion wire protocol."
    "jzap-minion" -> "The forked JVM that runs the tests under jzap analysis."
    "jzap-git" -> "Resolves git ranges and unified diffs to the changed line ranges jzap scopes to."
    "jzap-report" -> "jzap reporters: console, JSON, mutation-testing-elements, HTML, PR annotations and agent."
    "jzap-cli" -> "The jzap command line and resident daemon."
    else -> "Fast, diff-aware mutation testing for Java and Kotlin."
}

/**
 * Everything Central needs, in one zip.
 *
 * <p>Depends on a clean staging directory rather than an incremental one: a stale artefact from a
 * previous version left in the layout would be uploaded and published along with the rest.
 */
val clearStaging = tasks.register<Delete>("clearStaging") {
    delete(stagingDir)
}

val stageForCentral = tasks.register("stageForCentral") {
    group = "publishing"
    description = "Publishes every Central-bound module into one staging directory."
    dependsOn(clearStaging)
    dependsOn(centralModules.map { ":$it:publishMavenPublicationToStagingRepository" })
}

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Builds the deployment bundle to upload to the Central Portal."
    dependsOn(stageForCentral)
    from(stagingDir)
    // Gradle writes this for the version listing of a repository; a single deployment is not one,
    // and the Portal reads the coordinates from the POMs.
    exclude("**/maven-metadata*")
    archiveFileName.set("jzap-${project.version}-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("central"))

    doFirst {
        check(!project.version.toString().endsWith("-SNAPSHOT")) {
            "refusing to bundle ${project.version}: the Central Portal's release endpoint does " +
                "not take snapshots, and a snapshot's filenames carry a build timestamp, so the " +
                "rejection would arrive after the upload rather than here. Pass " +
                "-PjzapVersion=<release version>; see docs/releasing.md."
        }
        check(signingKey != null) {
            "GPG_SIGNING_KEY is not set, so the bundle would be unsigned and the Central Portal " +
                "would reject it after upload. Export GPG_SIGNING_KEY (an ASCII-armoured private " +
                "key) and GPG_SIGNING_KEY_PASSWORD; see docs/releasing.md."
        }
    }
}
