plugins {
    application
}

dependencies {
    implementation(project(":jzap-core"))
    // Declared rather than inherited through jzap-core. Both are used directly here -- the model
    // types throughout, and the wire framing for the daemon's own socket -- and a module that
    // compiles against something should say so.
    implementation(project(":jzap-model"))
    implementation(project(":jzap-wire"))
    implementation(project(":jzap-git"))
    implementation(project(":jzap-report"))
    implementation(libs.picocli)

    // On the runtime classpath so the analysis JVM can be assembled; see RuntimeJars.
    runtimeOnly(project(":jzap-minion"))
    runtimeOnly(project(":jzap-agent"))

    // JGit logs through SLF4J and prints a three-line complaint when no provider is present.
    // A command-line tool has no business emitting that, and jzap logs nothing through SLF4J
    // itself, so the no-op provider is the right binding rather than a real one.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The CLI's tests drive the commands in this JVM, against the smallest real fixture.
 *
 * <p>Exit codes, option forwarding and the diagnostics for a missing or malformed model are what
 * a user actually meets first, and until this existed they were only covered by the end-to-end
 * tests that invoke the installed binary as a subprocess -- which proves the binary works but
 * cannot see inside it, and leaves every option-handling branch unexercised.
 */
val sampleDescriptor = project(":fixtures:sample-java")
    .layout.buildDirectory.file("fixture.properties")

tasks.test {
    dependsOn(":fixtures:sample-java:writeFixtureDescriptor")
    systemProperty("jzap.fixture.descriptor", sampleDescriptor.get().asFile.absolutePath)
}

application {
    mainClass.set("io.github.huyz0.jzap.cli.Main")
    applicationName = "jzap"
}
