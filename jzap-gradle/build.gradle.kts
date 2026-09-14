import java.time.Duration

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.plugin.publish)
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

/**
 * The plugin goes to the Gradle Plugin Portal rather than to Maven Central with the rest.
 *
 * The Portal is where `plugins { id ... }` resolves from, so publishing it anywhere else would
 * leave the documented way of applying it not working. com.gradle.plugin-publish also produces
 * the sources and javadoc jars and the plugin marker artefact, which is why this module is not in
 * gradle/publishing.gradle.kts: having both configure publications for one project would mean two
 * definitions of the same thing.
 *
 * The plugin id's namespace is claimed on the Portal through the GitHub account it names, the same
 * verification Maven Central does for io.github.huyz0.
 */
gradlePlugin {
    website.set("https://huyz0.github.io/jzap/")
    vcsUrl.set("https://github.com/huyz0/jzap.git")
    plugins {
        create("jzap") {
            id = "io.github.huyz0.jzap"
            implementationClass = "io.github.huyz0.jzap.gradle.JzapPlugin"
            displayName = "jzap mutation testing"
            description = "Fast, diff-aware mutation testing for Java and Kotlin. Changes your " +
                "compiled code in small ways and reports every change your tests fail to notice, " +
                "scoped to what a pull request changed."
            tags.set(listOf("mutation-testing", "testing", "test-quality", "java", "kotlin"))
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
