dependencies {
    api(project(":jzap-model"))
    implementation(libs.jgit)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
