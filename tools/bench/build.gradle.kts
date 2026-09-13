/**
 * Times jzap and PIT over the same fixture, with the same mutator set and one thread each.
 *
 * Reports the median of several runs with its range, never a best-of figure, per
 * docs/parity-and-benchmarks.md section 5. A single number with no range is not a result.
 */
val pit: Configuration = configurations.create("pit")

dependencies {
    pit(libs.pitest)
    pit(libs.pitest.entry)
    pit(libs.pitest.command.line)
    pit(libs.pitest.junit5.plugin)
    pit(libs.junit.jupiter)
    pit(libs.junit.platform.launcher)
}

val benchFixture = project(":fixtures:bench-java")

tasks.register<Exec>("bench") {
    dependsOn(
        ":jzap-cli:installDist",
        ":fixtures:bench-java:writeFixtureDescriptor",
        ":fixtures:sample-java:writeFixtureDescriptor",
    )

    val script = file("bench.py")
    val descriptor = benchFixture.layout.buildDirectory.file("fixture.properties")
    val cli = project(":jzap-cli").layout.buildDirectory.file("install/jzap/bin/jzap")
    val out = layout.buildDirectory.dir("bench")
    val pitClasspath = pit
    val runs = (findProperty("benchRuns") as String? ?: "3")

    doFirst {
        out.get().asFile.mkdirs()
        commandLine(
            "python3", script.absolutePath,
            "--descriptor", descriptor.get().asFile.absolutePath,
            "--jzap", cli.get().asFile.absolutePath,
            "--pit-classpath", pitClasspath.files.joinToString(File.pathSeparator) { it.absolutePath },
            "--out", out.get().asFile.absolutePath,
            "--runs", runs,
            "--java", "${System.getProperty("java.home")}/bin/java",
        )
    }
}

/**
 * Measures what each mutant-reduction technique removes and what it costs in detection.
 *
 * Separate from the speed benchmark because a reduction figure without its loss figure is not a
 * result, it is an advertisement.
 */
tasks.register<Exec>("reduction") {
    dependsOn(":jzap-cli:installDist", ":fixtures:bench-java:writeFixtureDescriptor")

    val script = file("reduction.py")
    val descriptor = benchFixture.layout.buildDirectory.file("fixture.properties")
    val cli = project(":jzap-cli").layout.buildDirectory.file("install/jzap/bin/jzap")
    val out = layout.buildDirectory.dir("reduction")
    val runs = (findProperty("benchRuns") as String? ?: "2")

    doFirst {
        out.get().asFile.mkdirs()
        commandLine(
            "python3", script.absolutePath,
            "--descriptor", descriptor.get().asFile.absolutePath,
            "--jzap", cli.get().asFile.absolutePath,
            "--out", out.get().asFile.absolutePath,
            "--runs", runs,
        )
    }
}
