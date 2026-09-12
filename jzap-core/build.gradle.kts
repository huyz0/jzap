dependencies {
    api(project(":jzap-model"))
    api(project(":jzap-wire"))
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    implementation(libs.asm.util)

    // Test-only: the probe sink lives in the agent, and instrumentation is verified by
    // running instrumented code in this JVM rather than only inspecting its bytecode.
    testImplementation(project(":jzap-agent"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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

sourceSets.named("main") { resources.srcDir(versionResource) }
tasks.named("processResources") { dependsOn(generateVersionResource) }
