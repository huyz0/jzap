// Dependency-free for the same reason as jzap-agent: this jar goes on the classpath of the
// JVM running the user's tests.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
