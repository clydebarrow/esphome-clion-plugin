import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
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
    // Renders CHANGELOG.md into the plugin's change-notes and the release body.
    id("org.jetbrains.changelog") version "2.2.1"
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
        // Bundle this version's CHANGELOG.md section as the plugin's change-notes
        // (shown in the IDE's plugin manager and on the Marketplace).
        changeNotes = provider {
            with(changelog) {
                renderItem(
                    (getOrNull(version.get()) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            // Compiled against CLion 2026.1 (build 261) on JDK 21. Bytecode 21
            // needs JBR 21, i.e. IDEs from 2024.2 (build 242) onward; the APIs
            // used are stable across that range.
            sinceBuild = "242"
            untilBuild = "261.*"
        }
    }

    // Marketplace requires signed plugins. Keys come from CI secrets; when they
    // are absent (local builds) signing/publishing simply isn't run.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Pre-release versions (e.g. 0.9.0-rc1) go to the `beta` Marketplace
        // channel; clean semver goes to the default (stable) channel.
        channels = providers.gradleProperty("pluginVersion").orElse("0.0.0")
            .map { listOf(if ("-" in it) "beta" else "default") }
    }

    // `verifyPlugin` checks binary compatibility across the supported IDE range
    // (so a method missing on, say, 2025.1 is caught before publishing). Only
    // real binary/structural problems fail the build; deprecated/experimental/
    // internal API usages are reported but tolerated.
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            recommended()
        }
    }
}

changelog {
    // CHANGELOG.md lives at the repo root, not in this (the plugin) module.
    path = rootProject.file("CHANGELOG.md").absolutePath
    version = providers.gradleProperty("pluginVersion").orElse("0.1.0")
    repositoryUrl = "https://github.com/clydebarrow/esphome-clion-plugin"
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
            // The automations index lists triggers (on_*), keyed by the
            // component they apply to — completion offers them as keys.
            include("**/esphome_device_builder/definitions/automations.index.json")
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

// --- Temporary lvgl catalog overlay -----------------------------------------
//
// The pinned device-builder catalog (esphomeDeviceBuilderRef) predates the
// esphome lvgl-schema fix, so it ships a near-empty `lvgl` body (zero
// config_entries) and no lvgl triggers in its automations index — the `lvgl:`
// block gets little completion. Until the ref can be bumped, overlay files
// regenerated by running device-builder's sync against the patched schema:
// `components/lvgl.json` (193 config_entries) and the full `automations.index.json`
// (adds lvgl's 81 obj-level triggers), and relabel the mislabeled lvgl index
// entry. Remove this task and `plugin/catalog-overlay/` once
// esphomeDeviceBuilderRef points at a release regenerated from the fixed schema.
val catalogOverlayDir = layout.projectDirectory.dir("catalog-overlay")
val overlayCatalog by tasks.registering {
    description = "Overlay locally-regenerated catalog bodies (lvgl) onto the vendored catalog."
    group = "build"
    dependsOn(vendorCatalog)
    val overlay = catalogOverlayDir.asFile
    val outDir = catalogGenDir.get().asFile
    inputs.dir(catalogOverlayDir)
    doLast {
        copy {
            from(overlay)
            into(outDir)
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        // Relabel the lvgl entry in the vendored index (device-builder mislabels
        // it from the id collision with its platform entries). Target id=="lvgl"
        // specifically — "LVGL Light" is also a legit name for the lvgl.light row.
        // Safe casts (the index shape could change across refs) and only rewrite
        // the file when the lvgl entry actually differs.
        val indexFile = outDir.resolve("esphome/definitions/components.index.json")
        if (indexFile.exists()) {
            val root = groovy.json.JsonSlurper().parse(indexFile)
            val components = (root as? Map<*, *>)?.get("components") as? List<*>
            @Suppress("UNCHECKED_CAST")
            val lvgl = components
                ?.firstOrNull { (it as? Map<*, *>)?.get("id") == "lvgl" } as? MutableMap<String, Any?>
            val desired = mapOf(
                "name" to "LVGL",
                "description" to "LVGL graphics: displays, pages, widgets, styles and triggers under lvgl:.",
                "docs_url" to "https://esphome.io/components/lvgl/",
            )
            if (lvgl != null && desired.any { (k, v) -> lvgl[k] != v }) {
                lvgl.putAll(desired)
                indexFile.writeText(groovy.json.JsonOutput.toJson(root))
            }
        }
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
    dependsOn(vendorCatalog, overlayCatalog)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
