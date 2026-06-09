import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.URI

// The ESPHome CLion plugin. Uses the IntelliJ Platform Gradle Plugin (2.x).
//
// Toolchain: IntelliJ Platform Gradle Plugin 2.16 + Gradle 9 + Kotlin 2.2 +
// JDK 21, building natively against CLion 2026.1 (the user's IDE). JDK 21 is
// required for IDEs from 2024.2 onward (they run on JBR 21). The IDE target is
// property-driven (gradle.properties): default `CL` / 2026.1.1.
plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

// Plugin version: patched into plugin.xml's <version> and used in the
// distribution file name. Bump for each shared build / GitHub release.
version = providers.gradleProperty("pluginVersion").orElse("0.1.0").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// IDE target is property-driven so retargeting needs no code edits.
//   Default: CLion 2026.1.1 (the user's IDE) — see gradle.properties.
//   IDEA Community instead: -PideType=IC -PideVersion=2026.1.1.
// The plugin uses only IntelliJ Platform + bundled-YAML APIs, so it also loads in
// IDEA/PyCharm/etc. of a compatible build.
val ideType: Provider<String> = providers.gradleProperty("ideType").orElse("CL")
val ideVersion: Provider<String> = providers.gradleProperty("ideVersion").orElse("2026.1.1")

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
            // Compiled against CLion 2026.1 (build 261) on JDK 21. Bytecode 21
            // needs JBR 21, i.e. IDEs from 2024.2 (build 242) onward; the APIs
            // used are stable across that range.
            sinceBuild = "242"
            untilBuild = "261.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Name the distribution after the product, not the Gradle module (":plugin"),
// so the released artifact reads `esphome-clion-plugin-<version>.zip`.
tasks.named<Zip>("buildPlugin") {
    archiveBaseName.set("esphome-clion-plugin")
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
