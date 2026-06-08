import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// The ESPHome CLion plugin. Uses the IntelliJ Platform Gradle Plugin (2.x).
//
// Version notes for this spike:
// - IntelliJ Platform Gradle Plugin 2.5.0 is the last line that runs on
//   Gradle 8.x (2.6+ requires Gradle 9). Keeping the repo on Gradle 8.13 lets
//   the fast :catalog module stay on the toolchain we already have.
// - Targets CLion 2024.1 (build 241), which runs on JDK 17 — matching the JDK
//   available here. Bumping to CLion 2024.2+ would require JDK 21 to build.
plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Production target is CLion, but the plugin only uses IntelliJ Platform
        // + bundled-YAML APIs (no CLion-specific code), so it loads in CLion all
        // the same. Building against IDEA Community avoids the CLion + plugin
        // 2.5.0 dependency-resolution bug (JetBrains/intellij-platform-gradle-plugin#1931)
        // and resolves reliably. Switch to `clion("…")` with a newer plugin
        // version when finalizing the CLion build.
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
    // The pure-Kotlin catalog model + loader. Its kotlinx-serialization runtime
    // is bundled into the plugin distribution transitively.
    implementation(project(":catalog"))

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "243.*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}
