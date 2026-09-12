dependencies {
    api(project(":jzap-model"))
    api(project(":jzap-wire"))
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)

    // Test-only: the probe sink lives in the agent, and instrumentation is verified by
    // running instrumented code in this JVM rather than only inspecting its bytecode.
    testImplementation(project(":jzap-agent"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
