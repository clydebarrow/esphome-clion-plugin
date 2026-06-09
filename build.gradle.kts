// Root build: share the Kotlin plugin versions across modules.
//
// :catalog — pure Kotlin model + loader (kotlinx.serialization), no IntelliJ SDK.
// :plugin  — the CLion plugin (IntelliJ Platform); it declares the IntelliJ
//            Platform Gradle Plugin itself so the SDK tooling stays isolated to
//            that module.
plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
}
