import java.time.Duration

dependencies {
    testImplementation(project(":jzap-core"))
    testImplementation(testFixtures(project(":jzap-core")))
    testImplementation(project(":jzap-model"))
    testImplementation(project(":jzap-git"))
    testImplementation(project(":jzap-wire"))
    testImplementation(project(":jzap-report"))

    // On the runtime classpath so RuntimeJars can find them, exactly as they will be found
    // when jzap runs from its distribution.
    testRuntimeOnly(project(":jzap-minion"))
    testImplementation(project(":jzap-agent"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val fixtureDescriptor = project(":fixtures:sample-java")
    .layout.buildDirectory.file("fixture.properties")
val hangDescriptor = project(":fixtures:hang-java")
    .layout.buildDirectory.file("fixture.properties")
val kotlinDescriptor = project(":fixtures:kotlin-sample")
    .layout.buildDirectory.file("fixture.properties")
val kotestDescriptor = project(":fixtures:kotest-sample")
    .layout.buildDirectory.file("fixture.properties")
val multiCoreDescriptor = project(":fixtures:multi-core")
    .layout.buildDirectory.file("fixture.properties")
val multiAppDescriptor = project(":fixtures:multi-app")
    .layout.buildDirectory.file("fixture.properties")
val redDescriptor = project(":fixtures:red-java")
    .layout.buildDirectory.file("fixture.properties")
val statefulDescriptor = project(":fixtures:stateful-java")
    .layout.buildDirectory.file("fixture.properties")
val parallelDescriptor = project(":fixtures:parallel-java")
    .layout.buildDirectory.file("fixture.properties")
val blockingDescriptor = project(":fixtures:blocking-java")
    .layout.buildDirectory.file("fixture.properties")

tasks.test {
    dependsOn(":fixtures:sample-java:writeFixtureDescriptor",
        ":fixtures:hang-java:writeFixtureDescriptor",
        ":fixtures:kotlin-sample:writeFixtureDescriptor",
        ":fixtures:kotest-sample:writeFixtureDescriptor",
        ":fixtures:multi-core:writeFixtureDescriptor",
        ":fixtures:multi-app:writeFixtureDescriptor",
        ":fixtures:red-java:writeFixtureDescriptor",
        ":fixtures:stateful-java:writeFixtureDescriptor",
        ":fixtures:parallel-java:writeFixtureDescriptor",
        ":fixtures:blocking-java:writeFixtureDescriptor")
    systemProperty("jzap.fixture.descriptor", fixtureDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.hang.descriptor", hangDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.kotlin.descriptor", kotlinDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.kotest.descriptor", kotestDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.multi.core.descriptor", multiCoreDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.multi.app.descriptor", multiAppDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.red.descriptor", redDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.stateful.descriptor", statefulDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.parallel.descriptor", parallelDescriptor.get().asFile.absolutePath)
    systemProperty("jzap.blocking.descriptor", blockingDescriptor.get().asFile.absolutePath)
    // The daemon is tested through the installed binary, because a resident process started from
    // a classpath that only exists inside a Gradle test worker is not the thing users run.
    dependsOn(":jzap-cli:installDist")
    systemProperty("jzap.cli",
        project(":jzap-cli").layout.buildDirectory.file("install/jzap/bin/jzap").get().asFile.absolutePath)
    // Analysis forks JVMs and runs a full test suite per mutant, so it needs room.
    timeout.set(Duration.ofMinutes(10))
}
