# ESPHome CLion plugin

JetBrains CLion plugin for ESPHome YAML device configs: completion, hover
documentation, and validation. See
[`docs/esphome-data-sources.md`](docs/esphome-data-sources.md) for the data
architecture and why this does **not** use a JSON Schema.

## Modules

| Module | What it is | Needs the IntelliJ SDK? |
|---|---|---|
| `:catalog` | Pure-Kotlin model + loader for the ESPHome component catalog (kotlinx.serialization). Mirrors Device Builder's `ConfigEntry` / `ComponentCatalogEntry`. | No — builds/tests on plain Maven Central. |
| `:plugin` | The plugin: catalog service, YAML PSI helpers, completion contributor, documentation provider, and the `esphome config` validation annotator. Depends on `:catalog`. | Yes. |

## Build & test

```bash
./gradlew :catalog:test         # fast: model + loader + derived-view tests
./gradlew :plugin:test          # completion, validation parser, doc renderer/provider
./gradlew vendorCatalog         # fetch the FULL catalog (pinned device-builder ref)
./gradlew :plugin:buildPlugin   # assemble the plugin zip (downloads the SDK)
./gradlew :plugin:runIde        # launch a sandbox IDE with the plugin loaded
```

Open `examples/living_room.yaml` in the sandbox IDE: completion via
**Code → Code Completion → Basic**, hover/Ctrl-Q for docs, and config errors are
underlined (set the esphome path under **Settings → Tools → ESPHome** if it
isn't on PATH).

## Features

- **Completion** — top-level keys, nested component keys, platform list items
  (`sensor: - platform: …`), `platform:` values, and `options` enums.
- **Hover documentation** — field type/requiredness/default/range/units/values
  and a docs link, from the catalog (`DocumentationProvider`).
- **Validation** — runs the real `esphome config` in the background
  (`ExternalAnnotator`) and maps reported errors to the offending lines. The
  executable is auto-detected from PATH or set in Settings → Tools → ESPHome.
- **Full catalog** — `vendorCatalog` downloads all component bodies for a pinned
  ESPHome release into generated resources; a small committed subset is the
  offline fallback.

## Targeting CLion

The build is property-driven (`gradle.properties`). The default targets IntelliJ
IDEA Community, which resolves on this repo's pinned toolchain (Gradle 8.13 +
IntelliJ Platform Gradle Plugin 2.5.0 + JDK 17). The plugin uses only IntelliJ
Platform + bundled-YAML APIs, so an IDEA build loads unchanged in CLion.

To build against CLion:

```bash
./gradlew :plugin:buildPlugin -PideType=CL -PideVersion=2024.3
```

This also requires bumping the toolchain (CLion + plugin 2.5.0 hits the
"No IntelliJ Platform dependency found" resolution bug,
[intellij-platform-gradle-plugin#1931](https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1931)):

1. IntelliJ Platform Gradle Plugin **>= 2.6** (in `plugin/build.gradle.kts`).
2. **Gradle 9.x** (`./gradlew wrapper --gradle-version 9.x`) and **Kotlin >= 2.1**
   (root `build.gradle.kts`).
3. **JDK 21** for CLion 2024.2+ (CLion 2024.1 still builds on JDK 17).

## Catalog data & licensing

`plugin/src/main/resources/esphome/definitions/` (committed subset) and the
`vendorCatalog` output come from
[esphome/device-builder](https://github.com/esphome/device-builder) (Apache-2.0;
see the bundled `LICENSE` and `ATTRIBUTION.md`). The pin is recorded in
`gradle.properties` (`esphomeDeviceBuilderRef`).
