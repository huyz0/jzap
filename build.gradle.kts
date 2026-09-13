plugins {
    java
}

allprojects {
    group = "io.github.huyz0"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.compilerArgs.add("-Xlint:-options")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

/**
 * The Maven plugin is built by Maven, not by this build.
 *
 * A Maven plugin needs a generated plugin descriptor and maven-plugin-plugin is what generates
 * it; hand-writing that descriptor would work right up until it silently did not. These tasks are
 * deliberately not wired into `build`, so the ordinary build does not require Maven or a network.
 */
val buildMavenPlugin = tasks.register<Exec>("buildMavenPlugin") {
    group = "build"
    description = "Builds and installs the jzap Maven plugin into the local Maven repository."
    workingDir = file("jzap-maven")
    commandLine("mvn", "-q", "-B", "install", "-DskipTests")
}

tasks.register<Exec>("mavenSmokeTest") {
    group = "verification"
    description = "Runs the Maven plugin against a generated sample project and checks the result."
    dependsOn(buildMavenPlugin, ":jzap-cli:installDist")
    workingDir = projectDir
    commandLine("bash", file("jzap-maven/smoke-test.sh").absolutePath,
        project(":jzap-cli").layout.buildDirectory.dir("install/jzap/lib").get().asFile.absolutePath)
}
