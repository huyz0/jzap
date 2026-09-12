rootProject.name = "jzap"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "jzap-model",
    "jzap-core",
    "jzap-agent",
    "jzap-wire",
    "jzap-minion",
    "jzap-git",
    "jzap-report",
    "jzap-cli",
    "jzap-e2e",
)

include("fixtures:sample-java")
include("fixtures:bench-java")
include("tools:parity")
include("tools:bench")
