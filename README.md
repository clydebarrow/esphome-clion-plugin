# ESPHome plugin for JetBrains IDEs

JetBrains IDE plugin for ESPHome YAML device configs: completion, hover
documentation, validation, and navigation. Works in CLion, IntelliJ IDEA,
PyCharm, and other compatible IDEs (2024.2+).

🌐 **[Showcase &amp; user guide →](https://clydebarrow.github.io/esphome-clion-plugin/)** ·
🧩 **[Install from the JetBrains Marketplace →](https://plugins.jetbrains.com/plugin/32186-esphome)**

See [`docs/esphome-data-sources.md`](docs/esphome-data-sources.md) for the data
architecture and why this does **not** use a JSON Schema.

## Install

From the **JetBrains Marketplace**: **Settings → Plugins → Marketplace**, search
for **ESPHome**, install, and restart.

Or from a release zip: download the latest from the
[**Releases**](https://github.com/clydebarrow/esphome-clion-plugin/releases/latest)
page (don't unzip it), then **Settings → Plugins → ⚙ → Install Plugin from
Disk…**, select the zip, and restart.

Open any config with a top-level `esphome:` (or `packages:`) key to activate it.
Completion, hover docs and navigation work with no external tool. **Validation**
runs the real `esphome config` using the same **backend** as a run (the default
backend under **Settings → Tools → ESPHome**), so the editor's verdict matches
what you'd build: the configured/`PATH` esphome for *Local*, the managed venv's
esphome for *Managed venv*, or the image for *Docker* (see
[Compiling, running & flashing](#compiling-running--flashing)). Set up whichever
backend you prefer and both validation and runs follow it — no separate wiring.

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
  (`sensor: - platform: …`), `platform:` values, enum and boolean values,
  `${substitution}` names, triggers (`on_*`), and actions in automation lists.
  Works in package-based configs (top-level `packages:`, no `esphome:`).
- **LVGL** — full `lvgl:` completion including the `widgets:` tree (widget types,
  their properties, and nested widgets), driven by ESPHome's language schema.
- **Navigation** — go-to / find-usages / rename for component ids and
  substitutions; id-reference completion offers in-scope ids of the right type.
- **Hover documentation** — field type/requiredness/default/range/units/values
  and a docs link, from the catalog (`DocumentationProvider`).
- **Validation** — runs the real `esphome config` in the background
  (`ExternalAnnotator`) and maps reported errors to the offending lines.
- **Compile, run & flash** — run configurations that invoke ESPHome (see below).
- **Live device window** — connect to a running device over the native API and
  watch its entities update live, with per-entity icons and on-off toggles for
  switches/lights/fans and a Press button for buttons (see below). Plaintext and
  **encrypted** (`encryption:`) APIs, no extra dependency.
- **Full catalog** — `vendorCatalog` downloads all component bodies for a pinned
  ESPHome release into generated resources; a small committed subset is the
  offline fallback.

## Compiling, running & flashing

Right-click a config (one with a top-level `esphome:` or `packages:`) and choose
**Run**, or create an *ESPHome* run configuration manually. It runs an ESPHome
command with output in the Run console (stop / re-run included).

- **Command** — `compile`, `run` (compile + upload + logs), `upload`, `logs`,
  or `clean`.
- **Backend** — how ESPHome is run:
  - **Local** — the `esphome` set in **Settings → Tools → ESPHome** (or found on
    PATH). Full capability, including serial flashing.
  - **Managed venv** — a plugin-managed Python venv. Set a version and click
    **Set up / update venv** under **Settings → Tools → ESPHome** (blank = latest, `beta` = latest
    pre-release, `dev` or a branch/tag = from git, `2025.7.0` = that release).
    No global install, full serial support.
  - **Docker** — the official `ghcr.io/esphome/esphome` image; great for compile
    and OTA. **Serial flashing does not work in Docker on macOS** (the VM has no
    USB passthrough) — use Local or the venv for that. An optional shared cache
    (Settings) speeds up repeat compiles but needs the cache dir shared with
    Docker Desktop.
- **Options** — a `--device` (OTA host/IP or serial port), state reporting
  (`--states`/`--no-states`), **Reset device before logs** (`--reset`), free-form
  extra arguments, and **Emulate a terminal** (in-place progress for compile/OTA;
  leave off for serial upload/logs).

The **default backend** (in the same settings page) is what new run
configurations start from — and what background validation uses, so the editor's
errors come from the very same ESPHome you build with.

## Live device window

The **ESPHome Device** tool window (bottom) connects to a running device over
the native API and shows its entities — grouped, with a per-entity icon — and
updates them live. Right-click a config → **Open ESPHome Device Window** (or
open the tool window directly); host/port and the encryption key are pre-filled
from the config (`api:`, `wifi:`/`ethernet:` address, `!secret`-resolved), and a
**Key** field lets you paste/override the key.

- **Transport** — plaintext, or **encrypted** when the device's `api:` has an
  `encryption: key:` (Noise `NNpsk0`, built on the bundled JDK crypto — no extra
  dependency). The connecting status shows which is in use.
- **Control** — on-off toggles for switches, lights and fans, and a **Press**
  button for buttons; sensors and the rest are read-only and update live. A
  binary sensor's icon is a filled circle when on, an outline when off.

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

The release body and the plugin's in-IDE change-notes come from
[`CHANGELOG.md`](CHANGELOG.md) — keep the `[Unreleased]` section up to date and
promote it to a version heading when tagging.

**JetBrains Marketplace publishing** happens automatically on a tag when these
repository secrets are set (otherwise the publish step is skipped and only the
GitHub release is made):

- `PUBLISH_TOKEN` — a Marketplace token (set manually).
- `PRIVATE_KEY`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY_PASSWORD` — the plugin-signing
  key/cert. Generate and store them in one step:

  ```bash
  ./gradlew storeSigningSecrets -PsigningPassword=<password>
  ```

Clean-semver tags publish to the **stable** channel; pre-release tags publish to
**beta**.

## License

This project is licensed under the Apache License 2.0 — see [`LICENSE`](LICENSE).

The bundled ESPHome catalog data is also Apache-2.0, from a separate upstream:
`plugin/src/main/resources/esphome/definitions/` (committed subset) and the
`vendorCatalog` output come from
[esphome/device-builder](https://github.com/esphome/device-builder) (see the
bundled `esphome/definitions/LICENSE` and `ATTRIBUTION.md`). The pin is recorded
in `gradle.properties` (`esphomeDeviceBuilderRef`).
