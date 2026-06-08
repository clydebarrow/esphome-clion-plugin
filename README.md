# ESPHome CLion plugin

JetBrains CLion plugin for ESPHome YAML device configs: completion and
documentation now, validation and navigation later. See
[`docs/esphome-data-sources.md`](docs/esphome-data-sources.md) for the data
architecture and why this does **not** use a JSON Schema.

## Modules

| Module | What it is | Needs the IntelliJ SDK? |
|---|---|---|
| `:catalog` | Pure-Kotlin model + loader for the ESPHome component catalog (kotlinx.serialization). Mirrors Device Builder's `ConfigEntry` / `ComponentCatalogEntry`. | No — builds/tests on plain Maven Central. |
| `:plugin` | The CLion plugin: catalog service, YAML PSI helpers, completion contributor. Depends on `:catalog`. | Yes. |

## Build & test

```bash
./gradlew :catalog:test     # fast: model + loader + derived-view tests
./gradlew :plugin:buildPlugin   # assemble the plugin zip (downloads the SDK)
./gradlew :plugin:runIde        # launch a sandbox IDE with the plugin loaded
```

Open `examples/living_room.yaml` in the sandbox IDE and press Ctrl/Cmd+Space to
exercise completion.

## Status (spike)

Implemented:
- Catalog model + lazy repository, with forward-compatible decoding.
- File detection (top-level `esphome:`), YAML path resolution, platform
  discriminator handling.
- `CompletionContributor`: top-level keys, nested component keys, platform list
  items, `platform:` values, and `options` enums.

Deferred: validation via `esphome config` (ExternalAnnotator), documentation on
hover, registry lists / pin pickers / lambdas, vendoring the **full** catalog
pinned to an ESPHome release, and finalizing the CLion build target (see the
note in `plugin/build.gradle.kts`).

## Catalog data & licensing

`plugin/src/main/resources/esphome/definitions/` vendors a small subset of the
ESPHome component catalog from
[esphome/device-builder](https://github.com/esphome/device-builder) (Apache-2.0;
see the bundled `LICENSE` and `ATTRIBUTION.md`).
