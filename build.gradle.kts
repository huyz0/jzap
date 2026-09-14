plugins {
    java
    jacoco
}

allprojects {
    group = "io.github.huyz0"
    // Overridden by the release workflow from the tag: -PjzapVersion=0.1.0. A developer build
    // stays a snapshot, so nothing here depends on remembering to set it back.
    version = providers.gradleProperty("jzapVersion").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

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
        extensions.configure<JacocoTaskExtension> {
            // The forked analysis JVMs are separate processes; only this JVM is measured.
            // coverageReport explains what that costs and which classes it excludes.
            isEnabled = true
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

/**
 * Release publishing, kept in its own file because it is a self-contained concern and this one is
 * already about the build itself. See that file's header for what goes where and why.
 */
apply(from = "gradle/publishing.gradle.kts")

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

/**
 * Coverage across every module at once.
 *
 * <p>Per-module coverage would be badly misleading here. The tests that exercise most of
 * jzap-core live in jzap-e2e, because what they assert is a whole analysis of a real fixture:
 * measuring jzap-core against only its own test source set would report a fraction of what is
 * actually covered. So every module's execution data is unioned against every module's classes.
 *
 * <h2>What is measured</h2>
 *
 * jzap's own production code, and nothing else. The fixtures under fixtures/ are deliberately
 * excluded: they are the code jzap mutates, the input to the tool rather than the tool, and they
 * execute only inside the analysis JVMs jzap forks. Counting them put 715 lines of sample code
 * in the denominator, 680 of them generated, and moved the headline figure by fifteen points
 * while saying nothing about whether jzap is tested.
 *
 * <h2>What cannot be measured this way</h2>
 *
 * JaCoCo instruments the JVM it is attached to, and jzap's whole design is to run the user's
 * tests in JVMs it forks. Two things fall outside it, and both are excluded from the ratio
 * rather than counted as zero, which would put a false floor under every number:
 *
 * <ul>
 *   <li><b>io.github.huyz0.jzap.minion</b> exists only to run inside those forks. MinionIntegrationTest
 *       drives a live one over the wire, so it is tested; it cannot be observed from here.
 *       Attaching a second JaCoCo agent to each minion would change the thing under test --
 *       the minion asserts it is dependency-free, and its class-redefinition path is exactly
 *       what another bytecode-rewriting agent interferes with.
 *   <li><b>JzapAgent and OverrideTransformer</b> need a real {@code Instrumentation}, which only
 *       a JVM started with {@code -javaagent} has. The rest of io.github.huyz0.jzap.agent is ordinary static
 *       state and pure functions, and is measured.
 * </ul>
 */
val coverageExcludes = listOf(
    // Runs only inside forked JVMs, or needs a real -javaagent Instrumentation; see above.
    "io/github/huyz0/jzap/minion/**",
    "io/github/huyz0/jzap/agent/JzapAgent*",
    "io/github/huyz0/jzap/agent/OverrideTransformer*",
)

/** jzap's own modules. Fixtures and tools are not the product and are not measured. */
val measuredProjects = listOf(
    "jzap-model", "jzap-core", "jzap-agent", "jzap-wire", "jzap-minion",
    "jzap-git", "jzap-report", "jzap-cli", "jzap-gradle",
).map { project(":$it") }

/** Every module with tests, whose execution data feeds the report. */
val testedProjects = measuredProjects + project(":jzap-e2e")

val coverageReport = tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Unions every module's coverage into one report."

    dependsOn(testedProjects.map { "${it.path}:test" })

    executionData.setFrom(files(testedProjects.map {
        it.layout.buildDirectory.file("jacoco/test.exec")
    }).filter { it.exists() })

    sourceDirectories.setFrom(files(measuredProjects.map { it.file("src/main/java") }))
    classDirectories.setFrom(files(measuredProjects.map {
        it.layout.buildDirectory.dir("classes/java/main")
    }).asFileTree.matching { coverageExcludes.forEach { pattern -> exclude(pattern) } })

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/coverage.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/coverage/html"))
        csv.required.set(false)
    }
}

/**
 * Fails the build if coverage slips.
 *
 * <p>A ratchet rather than the target. The floors are a little under what the suite currently
 * achieves, so ordinary variation does not fail a build, and the point is that a change which
 * drops a swathe of covered code has to say so rather than pass quietly. docs/coverage.md records
 * where the number stands, what the remaining gap is made of, and what closing it would cost.
 */
val coverageFloorLine = 0.93
val coverageFloorBranch = 0.85

val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    group = "verification"
    description = "Fails if aggregate coverage falls below the recorded floor."
    dependsOn(coverageReport)

    executionData.setFrom(coverageReport.get().executionData)
    sourceDirectories.setFrom(coverageReport.get().sourceDirectories)
    classDirectories.setFrom(coverageReport.get().classDirectories)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverageFloorLine.toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = coverageFloorBranch.toBigDecimal()
            }
        }
    }
}

/**
 * Wires the gate into the build, because a gate nothing runs is not a gate.
 *
 * <p>It was registered but unreachable: neither `check` nor `build` depended on it, so the floors
 * above were only enforced for whoever thought to type `./gradlew coverageGate`. The test tasks it
 * needs are already part of `build`, so this adds the report and the comparison, not another run
 * of the suite.
 */
tasks.named("check") {
    dependsOn(coverageGate)
}

/** Prints the aggregate ratios, because a report nobody reads is not a check. */
tasks.register("coverage") {
    group = "verification"
    description = "Prints aggregate line and branch coverage."
    dependsOn(coverageReport)
    val xml = coverageReport.get().reports.xml.outputLocation
    doLast {
        val text = xml.get().asFile.readText()
        // The report-wide totals are the last counter elements in the document.
        val totals = Regex("""<counter type="(\w+)" missed="(\d+)" covered="(\d+)"/>""")
            .findAll(text).toList().takeLast(6)
        totals.forEach { m ->
            val (kind, missed, covered) = m.destructured
            val total = missed.toInt() + covered.toInt()
            val pct = if (total == 0) 0.0 else covered.toInt() * 100.0 / total
            println(String.format("%-12s %6.2f%%  (%d/%d)", kind, pct, covered.toInt(), total))
        }
    }
}
