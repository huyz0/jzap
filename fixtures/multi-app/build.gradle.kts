import java.io.File

dependencies {
    implementation(project(":fixtures:multi-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { enabled = false }

val writeFixtureDescriptor = tasks.register("writeFixtureDescriptor") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val testClasses = sourceSets["test"].output.classesDirs
    val testRuntime = sourceSets["test"].runtimeClasspath
    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    val descriptor = layout.buildDirectory.file("fixture.properties")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
    inputs.files(mainClasses, testClasses, testRuntime)
    outputs.file(descriptor)

    doLast {
        val separator = File.pathSeparator
        descriptor.get().asFile.writeText(buildString {
            appendLine("mainClasses=" + mainClasses.joinToString(separator) { it.absolutePath })
            appendLine("testClasses=" + testClasses.joinToString(separator) { it.absolutePath })
            appendLine("testRuntimeClasspath=" + testRuntime.files.joinToString(separator) { it.absolutePath })
            appendLine("sourceRoot=" + sourceRoot.asFile.absolutePath)
        })
    }
}
