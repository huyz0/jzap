import java.time.Duration

plugins {
    `java-test-fixtures`
}

/**
 * Test fixtures: the helper that turns a fixture project's published descriptor into a project
 * model. It lives here rather than in jzap-e2e because analysing a project model is this module's
 * job, and both this module's tests and the end-to-end ones need it.
 */

dependencies {
    // api: AnalysisEngine.analyse returns an AnalysisResult, so the model is part of this
    // module's contract and every caller needs it on their compile classpath.
    api(project(":jzap-model"))
    // implementation, not api: no wire type appears in a public signature here. A module that
    // speaks the protocol -- the CLI's daemon reuses the framing -- declares it itself rather
    // than inheriting it from us, which keeps this module's exported surface to the model alone.
    implementation(project(":jzap-wire"))
    // Compile-only: the engine needs the agent's constants and nothing else, and the agent is
    // always present in the analysis JVM it talks to. Keeping it off the runtime classpath keeps
    // the controller's dependencies honest.
    compileOnly(project(":jzap-agent"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)

    // Test-only: the probe sink lives in the agent, and instrumentation is verified by
    // running instrumented code in this JVM rather than only inspecting its bytecode.
    testImplementation(project(":jzap-agent"))
    // The controller side of the minion protocol is this module's, so testing it against a real
    // minion is this module's job. Runtime only, and no cycle: the minion depends on the wire and
    // the agent, never on the engine.
    testRuntimeOnly(project(":jzap-minion"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The tests that drive a real analysis JVM need a real project to analyse, and the fixture
 * projects publish their own paths; see the Fixture test fixture.
 */
val sampleDescriptor = project(":fixtures:sample-java")
    .layout.buildDirectory.file("fixture.properties")
val hangDescriptor = project(":fixtures:hang-java")
    .layout.buildDirectory.file("fixture.properties")

tasks.test {
    dependsOn(":fixtures:sample-java:writeFixtureDescriptor",
        ":fixtures:hang-java:writeFixtureDescriptor")
    systemProperty("jzap.fixture.descriptor", sampleDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.hang.descriptor", hangDescriptor.get().asFile.absolutePath)
    // Forking JVMs and running a suite per mutant needs room.
    timeout.set(Duration.ofMinutes(10))
}

/**
 * The engine version, as a resource rather than a constant.
 *
 * It is part of the cache key, so a constant that drifts from the build version would let a
 * cache written by one engine be reused by another.
 */
val versionResource = layout.buildDirectory.dir("generated/version")
val generateVersionResource = tasks.register("generateVersionResource") {
    val out = versionResource
    val engineVersion = project.version.toString()
    inputs.property("version", engineVersion)
    outputs.dir(out)
    doLast {
        val file = out.get().asFile.resolve("io/github/huyz0/jzap/core/version.properties")
        file.parentFile.mkdirs()
        file.writeText("version=$engineVersion\n")
    }
}

// The task rather than its directory, so every consumer of the main resources inherits the
// dependency. Naming the directory made processResources need an explicit dependsOn, and left
// sourcesJar -- added for publishing, and also a consumer -- reading the directory with no
// dependency on what fills it.
sourceSets.named("main") { resources.srcDir(generateVersionResource) }
