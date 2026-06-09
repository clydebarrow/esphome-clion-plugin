# ESPHome CLion plugin

JetBrains CLion plugin for ESPHome YAML device configs: completion, hover
documentation, and validation. See
[`docs/esphome-data-sources.md`](docs/esphome-data-sources.md) for the data
architecture and why this does **not** use a JSON Schema.

## Install

Download the latest plugin zip from the
[**Releases**](https://github.com/clydebarrow/esphome-clion-plugin/releases/latest)
page (don't unzip it), then in CLion go to **Settings → Plugins → ⚙ → Install
Plugin from Disk…**, select the zip, and restart. If validation is quiet, set
your esphome binary under **Settings → Tools → ESPHome** (it's auto-detected from
`PATH` otherwise). Open any config with a top-level `esphome:` key to activate it.

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

## Toolchain & build target

The build runs on **Gradle 9 + IntelliJ Platform Gradle Plugin 2.16 + Kotlin 2.2
+ a JDK 21 toolchain**, and targets **CLion 2026.1** by default. JDK 21 is
required for IDEs from 2024.2 onward (they run on JBR 21). The JDK 21 toolchain
is located/downloaded automatically via the
[foojay resolver](https://github.com/gradle/foojay-toolchains) (configured in
`settings.gradle.kts`), so no JDK path is hardcoded and CI works out of the box.

The IDE target is property-driven (`gradle.properties`). To build against IntelliJ
IDEA Community instead (the plugin uses only IntelliJ Platform + bundled-YAML
APIs, so it loads in either):

```bash
./gradlew :plugin:buildPlugin -PideType=IC -PideVersion=2026.1.1
```

Produced artifact: `plugin/build/distributions/esphome-clion-plugin-<version>.zip`
(`since-build=242`, i.e. installable in any 2024.2+ IDE through CLion 2026.1.x).

## Releasing

Releases are built by GitHub Actions (`.github/workflows/release.yml`). Push a
semver tag and the workflow runs the tests, builds the distribution, and
publishes a GitHub release with the zip attached:

```bash
git tag v0.6.1 && git push origin v0.6.1
```

The plugin version is taken from the tag (the leading `v` is stripped), so no
manual version edit is needed. A tag with a pre-release suffix (e.g.
`v0.7.0-rc1`) is published as a GitHub pre-release. Every push and pull request
to `main` also runs the test suites via `.github/workflows/ci.yml`.

## License

This project is licensed under the Apache License 2.0 — see [`LICENSE`](LICENSE).

The bundled ESPHome catalog data is also Apache-2.0, from a separate upstream:
`plugin/src/main/resources/esphome/definitions/` (committed subset) and the
`vendorCatalog` output come from
[esphome/device-builder](https://github.com/esphome/device-builder) (see the
bundled `esphome/definitions/LICENSE` and `ATTRIBUTION.md`). The pin is recorded
in `gradle.properties` (`esphomeDeviceBuilderRef`).
