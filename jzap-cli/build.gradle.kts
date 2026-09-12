plugins {
    application
}

dependencies {
    implementation(project(":jzap-core"))
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

application {
    mainClass.set("io.github.huyz0.jzap.cli.Main")
    applicationName = "jzap"
}
