// Lets Gradle locate (or download) the JDK 21 toolchain the build requires on
// any machine/CI, so no JDK path is hardcoded.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "esphome-clion-plugin"

// Repositories are declared per-module (catalog needs only Maven Central; the
// plugin additionally needs the IntelliJ Platform repositories). This keeps the
// fast :catalog module decoupled from the IntelliJ SDK tooling.
include(":catalog", ":plugin")
