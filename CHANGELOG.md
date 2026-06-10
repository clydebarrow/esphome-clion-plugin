# Changelog

All notable changes to the ESPHome plugin are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the section
for each version is rendered into the plugin's change-notes and the GitHub
release.

## [Unreleased]

### Added

- Run configurations to **compile / run / upload / logs / clean** an ESPHome
  config, with output in the Run console. Right-click a config to create one.
- **Local** and **Docker** (`ghcr.io/esphome/esphome`) execution backends.
- Docker backend mounts the config directory at `/config` and a persistent host
  cache at `/cache`, so PlatformIO toolchains aren't re-downloaded each run.
- **Settings** for the default backend and Docker image new run configs use.
- **State reporting** (`--states` / `--no-states`) for `run`/`logs`, and a
  free-form **extra arguments** field.

## [0.8.0]

### Added

- LVGL completion driven by ESPHome's language schema: top-level `lvgl:` fields,
  the 29 widget types and their properties, **nested widgets**, triggers, and
  enum/boolean value completion.

### Changed

- LVGL ships as ~1.6 MB of `extends`-preserving schema (was a 17 MB flattened
  body), resolved lazily so recursive widget trees work.

## [0.7.1]

### Added

- Completion in package-based configs (top-level `packages:`, no `esphome:`),
  and value/boolean/trigger/action completion.

### Fixed

- Bundled YAML word-completion noise (in-use ids, sibling keys) under component
  blocks.

## [0.7.0]

### Added

- Complete LVGL catalog via a build-time overlay, ahead of device-builder.

## [0.6.0]

### Added

- Substitution resolution, find-usages and rename for ids and substitutions.

### Changed

- Build natively against CLion 2026.1 on a JDK 21 toolchain.
