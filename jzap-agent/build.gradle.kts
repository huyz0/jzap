// Deliberately dependency-free: this jar is loaded into the same JVM as the code under
// test, so anything it drags in could clash with the user's own dependencies. ADR 0001
// and docs/architecture.md both turn on this property, and the test below enforces it.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.github.huyz0.jzap.agent.JzapAgent",
            "Agent-Class" to "io.github.huyz0.jzap.agent.JzapAgent",
            "Can-Retransform-Classes" to "true",
            "Can-Redefine-Classes" to "true",
        )
    }
}
