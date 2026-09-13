import java.io.File

/** A library module with no tests of its own: everything that exercises it lives in multi-app. */
tasks.test { enabled = false }

val writeFixtureDescriptor = tasks.register("writeFixtureDescriptor") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    val descriptor = layout.buildDirectory.file("fixture.properties")

    dependsOn(tasks.named("classes"))
    inputs.files(mainClasses)
    outputs.file(descriptor)

    doLast {
        val separator = File.pathSeparator
        descriptor.get().asFile.writeText(buildString {
            appendLine("mainClasses=" + mainClasses.joinToString(separator) { it.absolutePath })
            appendLine("sourceRoot=" + sourceRoot.asFile.absolutePath)
        })
    }
}
