dependencies {
    implementation(project(":jzap-wire"))
    implementation(project(":jzap-agent"))

    // The launcher is supplied by the project under test at runtime, exactly as it is when
    // that project runs its own tests. Compiling against it without shipping it avoids
    // forcing a JUnit version on the analysed project.
    compileOnly(libs.junit.platform.launcher)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
