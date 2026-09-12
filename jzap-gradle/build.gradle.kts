import java.time.Duration

plugins {
    `java-gradle-plugin`
}

/**
 * The Gradle adapter. It contains no analysis logic: its whole job is to turn what the build
 * already knows -- source sets, configurations, toolchains -- into the project model the engine
 * reads, and then run the engine in a JVM of its own.
 *
 * Forking rather than running in-process is deliberate, and matches what gradle-pitest-plugin
 * does. It keeps ASM and the engine off the buildscript classpath, and it lets the engine
 * version move independently of the plugin version.
 */
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

gradlePlugin {
    plugins {
        create("jzap") {
            id = "io.github.huyz0.jzap"
            implementationClass = "io.github.huyz0.jzap.gradle.JzapPlugin"
            displayName = "jzap mutation testing"
            description = "Fast, diff-aware mutation testing for Java and Kotlin."
        }
    }
}

// TestKit runs a real Gradle build, which needs the engine distribution to exist and to be
// told where it is: there is no published artefact to resolve from in this repository.
val cliInstall = project(":jzap-cli").layout.buildDirectory.dir("install/jzap/lib")

tasks.test {
    dependsOn(":jzap-cli:installDist")
    systemProperty("jzap.engine.lib", cliInstall.get().asFile.absolutePath)
    timeout.set(Duration.ofMinutes(15))
}
