import java.time.Duration
import java.util.Properties

/**
 * Runs PIT and jzap over the same fixture, then compares them.
 *
 * PIT is the oracle: if the two disagree about a mutant, the burden of proof is on jzap. See
 * docs/parity-and-benchmarks.md for the triage discipline this harness enforces.
 *
 * Registered once per fixture, because a baseline validated only on a hand-written toy proves
 * very little. The bench fixture has two orders of magnitude more mutants and is where a
 * triaged difference either holds up or does not.
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

/** The same ten mutators on both sides, so the inventories are comparable at all. */
val sharedMutators = "CONDITIONALS_BOUNDARY,INCREMENTS,INVERT_NEGS,MATH,NEGATE_CONDITIONALS," +
    "VOID_METHOD_CALLS,EMPTY_RETURNS,FALSE_RETURNS,TRUE_RETURNS,PRIMITIVE_RETURNS"

fun registerParity(name: String, fixturePath: String, classGlob: String) {
    val fixture = project(fixturePath)
    val descriptor = fixture.layout.buildDirectory.file("fixture.properties")
    val pitReportDir = layout.buildDirectory.dir("$name/pit-report")
    val jzapReportDir = layout.buildDirectory.dir("$name/jzap-report")
    val jzapModel = layout.buildDirectory.file("$name/jzap-model.json")
    val pitClasspath = pit

    val runPit = tasks.register<JavaExec>("runPit${name.capitalize()}") {
        dependsOn("$fixturePath:writeFixtureDescriptor")
        mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
        timeout.set(Duration.ofMinutes(30))

        doFirst {
            val props = Properties()
            descriptor.get().asFile.reader().use { r -> props.load(r) }
            classpath = pitClasspath + files(
                props.getProperty("testRuntimeClasspath").split(File.pathSeparator))
            args = listOf(
                "--reportDir", pitReportDir.get().asFile.absolutePath,
                // jzap structurally cannot mutate test classes: it only mutates the module's
                // mutableCodePaths. PIT scopes by class-name glob over the whole classpath, so
                // tests must be excluded explicitly or the comparison is not like for like.
                "--targetClasses", classGlob,
                "--excludedClasses", "$classGlob*Test",
                "--targetTests", classGlob,
                "--sourceDirs", props.getProperty("sourceRoot"),
                "--outputFormats", "XML",
                "--timestampedReports", "false",
                "--threads", "1",
                "--mutators", sharedMutators,
                "--verbose", "false",
            )
            pitReportDir.get().asFile.mkdirs()
        }
    }

    val runJzap = tasks.register<Exec>("runJzap${name.capitalize()}") {
        dependsOn(":jzap-cli:installDist", "$fixturePath:writeFixtureDescriptor")
        val cli = project(":jzap-cli").layout.buildDirectory.file("install/jzap/bin/jzap")

        doFirst {
            val props = Properties()
            descriptor.get().asFile.reader().use { r -> props.load(r) }
            fun list(key: String) = props.getProperty(key).split(File.pathSeparator)
                .joinToString(", ") { "\"$it\"" }
            jzapModel.get().asFile.parentFile.mkdirs()
            jzapModel.get().asFile.writeText(
                """
                {
                  "schemaVersion": 1,
                  "modules": [
                    {
                      "id": "$fixturePath",
                      "mutableCodePaths": [${list("mainClasses")}],
                      "sourceRoots": [${list("sourceRoot")}],
                      "testClassPaths": [${list("testClasses")}],
                      "testClasspath": [${list("testRuntimeClasspath")}]
                    }
                  ],
                  "scope": { "kind": "ALL", "granularity": "line" },
                  "reporters": ["json"],
                  "threads": 1
                }
                """.trimIndent()
            )
            commandLine(
                cli.get().asFile.absolutePath, "run",
                "-m", jzapModel.get().asFile.absolutePath,
                "-o", jzapReportDir.get().asFile.absolutePath,
                "-q",
            )
        }
    }

    tasks.register<Exec>("parity${name.capitalize()}") {
        dependsOn(runPit, runJzap)
        description = "Compare jzap and PIT over $fixturePath"
        commandLine(
            "python3", file("compare.py").absolutePath,
            "--pit", pitReportDir.get().asFile.resolve("mutations.xml").absolutePath,
            "--jzap", jzapReportDir.get().asFile.resolve("jzap-result.json").absolutePath,
            "--mapping", file("mutator-mapping.yaml").absolutePath,
            "--baseline", file("parity-baseline.yaml").absolutePath,
            "--fixture", fixturePath,
        )
    }
}

registerParity("sample", ":fixtures:sample-java", "sample.*")
registerParity("bench", ":fixtures:bench-java", "bench.*")

tasks.register("parity") {
    description = "Run every parity comparison"
    dependsOn("paritySample", "parityBench")
}

/** Prints the resolved PIT classpath, so one-off PIT experiments can be run by hand. */
tasks.register("printPitClasspath") {
    val cp = pit
    doLast { println(cp.files.joinToString(File.pathSeparator) { it.absolutePath }) }
}
