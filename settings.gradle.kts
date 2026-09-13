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
    "jzap-gradle",
    "jzap-e2e",
)

include("fixtures:sample-java")
include("fixtures:bench-java")
include("fixtures:hang-java")
include("fixtures:kotlin-sample")
include("fixtures:kotest-sample")
include("fixtures:multi-core")
include("fixtures:multi-app")
include("tools:parity")
include("tools:bench")
