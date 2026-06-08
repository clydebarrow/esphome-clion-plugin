import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.URI

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

// IDE target is property-driven so retargeting needs no code edits.
//   Default (dev, verifiable here): IDEA Community, JDK 17, plugin 2.5.0.
//   CLion (production): -PideType=CL -PideVersion=2024.3 — see README for the
//   accompanying toolchain (plugin >= 2.6, Gradle 9, JDK 21). The plugin uses
//   only IntelliJ Platform + bundled-YAML APIs, so a build against IDEA loads
//   unchanged in CLion.
val ideType: Provider<String> = providers.gradleProperty("ideType").orElse("IC")
val ideVersion: Provider<String> = providers.gradleProperty("ideVersion").orElse("2024.1.7")

dependencies {
    intellijPlatform {
        create(ideType.get(), ideVersion.get())
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
    // The pure-Kotlin catalog model + loader. Its kotlinx-serialization runtime
    // is bundled into the plugin distribution transitively.
    implementation(project(":catalog"))

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    // No custom settings UI searchable options to index; skipping avoids
    // launching a headless IDE during the build (which also conflicts with a
    // running runIde sandbox).
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            // Compiled against the 241 API baseline (newest buildable on JDK 17),
            // but the APIs used are stable, so allow installing into newer IDEs
            // (e.g. CLion 2026.1 / build 261). For a build truly compiled against
            // a newer CLion, see README "Targeting CLion".
            sinceBuild = "241"
            untilBuild = "261.*"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

// --- Full catalog vendoring -------------------------------------------------
//
// The committed `src/main/resources/esphome/definitions/` holds the full index
// plus a few component bodies (offline fallback). `vendorCatalog` downloads the
// *complete* set of component bodies from a pinned esphome/device-builder ref
// into a generated resources dir, so the released plugin ships the whole
// catalog without committing ~900 JSON files. Run `./gradlew vendorCatalog`
// (then build); override the pin with `-PesphomeDeviceBuilderRef=<tag-or-sha>`.
val esphomeDeviceBuilderRef: Provider<String> = providers.gradleProperty("esphomeDeviceBuilderRef")
    .orElse("dd411a2593c556c1aa066e4ba0b2fe4a3572684b") // ESPHome schema 2026.5.3

val catalogGenDir: Provider<Directory> = layout.buildDirectory.dir("generated/esphome-catalog")

val vendorCatalog by tasks.registering {
    description = "Download the full ESPHome component catalog from a pinned esphome/device-builder ref."
    group = "build"
    val ref = esphomeDeviceBuilderRef.get()
    val outDir = catalogGenDir.get().asFile
    val tarball = layout.buildDirectory.file("device-builder-$ref.tar.gz").get().asFile
    inputs.property("ref", ref)
    outputs.dir(outDir)
    doLast {
        if (!tarball.exists()) {
            val url = "https://github.com/esphome/device-builder/archive/$ref.tar.gz"
            logger.lifecycle("Downloading ESPHome catalog from $url")
            tarball.parentFile.mkdirs()
            URI(url).toURL().openStream().use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
        }
        outDir.deleteRecursively()
        copy {
            from(tarTree(resources.gzip(tarball)))
            include("**/esphome_device_builder/definitions/components.index.json")
            include("**/esphome_device_builder/definitions/components/*.json")
            includeEmptyDirs = false
            eachFile {
                val marker = path.indexOf("definitions/")
                if (marker >= 0) path = "esphome/" + path.substring(marker) else exclude()
            }
            into(outDir)
        }
        val count = outDir.resolve("esphome/definitions/components").listFiles()?.size ?: 0
        logger.lifecycle("Vendored $count component bodies into $outDir")
    }
}

// Generated catalog is an additional resource root; when populated it overrides
// the committed subset (last-wins). `vendorCatalog` is up-to-date-cached on the
// pinned ref, so it downloads once and is skipped thereafter (offline builds
// work after the first fetch; before it, the committed subset is the fallback).
sourceSets.named("main") {
    resources.srcDir(catalogGenDir)
}
tasks.processResources {
    dependsOn(vendorCatalog)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
