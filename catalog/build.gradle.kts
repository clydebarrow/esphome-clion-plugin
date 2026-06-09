// Pure-Kotlin ESPHome catalog model + loader. No IntelliJ Platform dependency,
// so it compiles and tests fast and is reused by the :plugin module.
//
// Serialization uses kotlinx.serialization. The :plugin module bundles this jar
// and the kotlinx runtime into its distribution. Plugin versions and
// repositories are managed centrally (root build / settings).
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
