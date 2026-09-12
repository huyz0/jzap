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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("io.github.huyz0.jzap.cli.Main")
    applicationName = "jzap"
}
