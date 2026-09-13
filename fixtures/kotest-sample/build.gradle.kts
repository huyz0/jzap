import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * A Kotest fixture. Kotest runs on the JUnit Platform, so discovery finds it without any work --
 * but everything jzap does rests on running one test at a time by unique id, and a framework that
 * cannot be driven that way degrades selection silently rather than failing. This fixture exists
 * to find out which it is.
 */
dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.test { enabled = false }

val writeFixtureDescriptor = tasks.register("writeFixtureDescriptor") {
    val mainClasses = sourceSets["main"].output.classesDirs
    val testClasses = sourceSets["test"].output.classesDirs
    val testRuntime = sourceSets["test"].runtimeClasspath
    val sourceRoot = layout.projectDirectory.dir("src/main/kotlin")
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
