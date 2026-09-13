// Deliberately dependency-free: this jar is loaded into the same JVM as the code under
// test, so anything it drags in could clash with the user's own dependencies. ADR 0001
// and docs/architecture.md both turn on this property, and the test below enforces it.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

/**
 * The agent's own tests run with the agent attached.
 *
 * <p>Half of what this module does needs a real {@code Instrumentation}: installing replacement
 * bytecode for a class that is already loaded means asking the JVM to retransform it. Testing
 * that against a stub would test the stub. So the test JVM loads the very jar that ships, which
 * also means the premain path and the manifest attributes are exercised by every run rather than
 * only in production.
 */
tasks.test {
    dependsOn(tasks.jar)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:" + tasks.jar.get().archiveFile.get().asFile.absolutePath)
    })
    extensions.configure<JacocoTaskExtension> {
        // JaCoCo instruments at class load but does not register for retransformation, so a
        // class it has instrumented can no longer be retransformed with bytes from disk: the
        // replacement has no $jacocoInit and the JVM rejects it as deleting a method. The two
        // classes ClassOverridesTest swaps are therefore left uninstrumented. They hold one
        // statement each and are test fixtures rather than product code.
        excludes = listOf("io.github.huyz0.jzap.agent.OverrideVictim*")
    }
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
