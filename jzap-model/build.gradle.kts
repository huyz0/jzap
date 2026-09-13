dependencies {
    // implementation, not api: Jackson appears in no public signature here, and a data module
    // that exported it would pin every consumer of jzap to its version. The records carry
    // @JsonIgnore, which the compiler ignores when the annotation is absent.
    implementation(libs.jackson.databind)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
