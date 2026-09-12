dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The fixture's tests are intentionally weak. Running them in our own build would prove
// nothing and only confuse the output, so they are compiled but not executed here.
tasks.test { enabled = false }

/**
 * Publishes the paths jzap needs in order to analyse this fixture. Computing them here, in
 * the build that owns them, is exactly what a real Gradle adapter will do; the e2e test then
 * consumes the result as a project model would.
 */
val writeFixtureDescriptor = tasks.register("writeFixtureDescriptor") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val testClasses = sourceSets["test"].output.classesDirs
    // sourceSets.test.runtimeClasspath, not the testRuntimeClasspath configuration: the
    // configuration holds only external dependencies, so using it would leave the project's
    // own main classes off the classpath of the analysis JVM.
    val testRuntime = sourceSets["test"].runtimeClasspath
    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    val projectRoot = rootProject.layout.projectDirectory.asFile.absolutePath
    val descriptor = layout.buildDirectory.file("fixture.properties")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
    inputs.files(mainClasses, testClasses, testRuntime)
    outputs.file(descriptor)

    doLast {
        val separator = File.pathSeparator
        val text = buildString {
            appendLine("mainClasses=" + mainClasses.joinToString(separator) { it.absolutePath })
            appendLine("testClasses=" + testClasses.joinToString(separator) { it.absolutePath })
            appendLine("testRuntimeClasspath=" +
                testRuntime.files.joinToString(separator) { it.absolutePath })
            appendLine("sourceRoot=" + sourceRoot.asFile.absolutePath)
            appendLine("projectRoot=$projectRoot")
        }
        descriptor.get().asFile.writeText(text)
    }
}

/**
 * Writes a jzap project model for this fixture.
 *
 * A stand-in for the Gradle adapter of M18, and a demonstration of how small that adapter is:
 * its whole job is to turn what the build already knows into the one document the engine reads.
 */
tasks.register("writeProjectModel") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val testClasses = sourceSets["test"].output.classesDirs
    val testRuntime = sourceSets["test"].runtimeClasspath
    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    val model = rootProject.layout.buildDirectory.file("fixture-model.json")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
    inputs.files(mainClasses, testClasses, testRuntime)
    outputs.file(model)

    doLast {
        fun json(files: Iterable<File>) = files.joinToString(", ") { "\"${it.absolutePath}\"" }
        model.get().asFile.parentFile.mkdirs()
        model.get().asFile.writeText(
            """
            {
              "schemaVersion": 1,
              "modules": [
                {
                  "id": ":fixtures:sample-java",
                  "mutableCodePaths": [${json(mainClasses)}],
                  "sourceRoots": [${json(listOf(sourceRoot.asFile))}],
                  "testClassPaths": [${json(testClasses)}],
                  "testClasspath": [${json(testRuntime.files)}],
                  "jvmArgs": ["-Xmx512m"]
                }
              ],
              "scope": { "kind": "ALL", "granularity": "line" },
              "reporters": ["console", "json", "elements", "html", "annotations"],
              "threads": 1
            }
            """.trimIndent() + "\n"
        )
        logger.lifecycle("wrote ${model.get().asFile}")
    }
}
