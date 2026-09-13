import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * A Kotlin fixture whose expected verdicts are derived by hand, and whose job is to expose the
 * compiler-generated constructs that bytecode mutation turns into junk.
 */
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// No jvmToolchain: this machine has only the JDK Gradle is running on, and provisioning another
// would make the fixture depend on network access to build. The Kotlin and Java compilers are
// pinned to the same target instead, which is what the toolchain would have achieved.
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
            appendLine("kotlinVersion=" + libs.versions.kotlin.get())
        })
    }
}
