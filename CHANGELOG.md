# Changelog

All notable changes to the ESPHome plugin are documented here. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the section
for each version is rendered into the plugin's change-notes and the GitHub
release.

## [Unreleased]

## [0.14.0]

### Added

- An **editor floating toolbar** (top-right of the editor, on a standalone
  ESPHome config) puts the common actions in one place: **Run** (`esphome run`),
  **Logs** (`esphome logs`), and **Open Device Window**. It stays visible while
  you're in the config. The Run/Logs buttons reuse a matching run configuration
  if you have one, otherwise create a temporary one from your default backend.

### Fixed

- A typed id reference (e.g. `rtttl:`'s `output:`) is no longer falsely flagged
  "Cannot resolve id reference" when the same id is also passed to an **action**
  elsewhere (e.g. `output.set_level: { id: x }`). Those action arguments are
  `use_id` *references*, but the id index was recording them as *declarations*;
  sharing the name, the action's entry (with the action's own domain) shadowed
  the real declaration. The index now only records genuine declarations — an
  `id:` owned by a plain component/platform key, never a dotted action key.

## [0.13.2]

### Fixed

- Id navigation and completion now work in a config opened **outside the
  project's content roots** (e.g. a file from another directory). The id lookup
  falls back to a direct PSI scan when the file-based index doesn't cover the
  file, so even a same-file reference like an lvgl `text_font: <font_id>` resolves.

## [0.13.1]

### Fixed

- Removed a `DynamicPluginListener` whose Kotlin default-method bridges
  (`beforePluginsLoaded`/`pluginsLoaded`) were binary-incompatible with IntelliJ
  IDEA 2025.1. It only tore down device connections before a plugin unload, which
  already happens on tool-window/project dispose — so nothing is lost.

## [0.13.0]

### Added

- **Lambda highlighting & checks**: embedded lambda (C++) bodies now get
  lexer-based syntax highlighting (keywords, strings, numbers, comments) plus
  cheap structural checks — unbalanced brackets and unterminated literals are
  errors, and a likely-missing trailing `;` is a warning. Lexical only (no type
  or identifier analysis); works in every IDE without a C++ plugin.
- **Lambda `id()` support**: inside a lambda (C++) body, typing `id(` now
  completes in-scope ids, and `id(<name>)` is a navigable reference to its
  `id:` declaration (Ctrl-/Cmd-Click and find-usages).
- Hovering a **platform-domain key** (`sensor:`, `switch:`, …) now shows quick-doc
  — the available platforms and a link to the ESPHome domain page — instead of
  nothing (the catalog has no umbrella entry for a domain, so it's synthesized).
- **`secrets.yaml` masking**: values in an open `secrets.yaml` are now hidden
  behind `••••••`, revealing only the value on the caret's line — so secrets
  aren't exposed on a screen-share. Masking is visual only.
- **`!secret` navigation**: Ctrl-/Cmd-Click (or go-to-definition) on a
  `!secret <name>` value jumps to its `name:` declaration in the nearest
  `secrets.yaml`.
- The **ESPHome Device** window now lists **event** entities and shows when each
  last fired — `<event_type> · 10s ago` — with the relative time ticking up live.
- Id completion for **automation action** shorthand arguments — e.g.
  `on_value: - component.update: <caret>` now suggests in-scope ids (any
  component for `component.*`, or only ids of the matching type for
  `switch.turn_on`, `script.execute`, …).

### Fixed

- A pin warning (e.g. a strapping-pin advisory for `GPIO3`) now anchors on the
  whole-word pin, so it no longer highlights a different pin that merely contains
  it (`GPIO38`).
- Lambda bracket diagnostics now blame the **unclosed opener** at its own line
  (e.g. the line with the missing `)`) instead of the next mismatching `}`/`{`.
- The **ESPHome Device** window now disconnects when hidden (and reconnects when
  shown), and fully tears down the connection — socket, reader thread, liveness
  watchdog and timers — when the window is closed, the project closes, or the
  plugin is unloaded. A hidden-but-connected window no longer keeps that work
  running in the background.
- Config validation runs the `esphome config` subprocess under the editor's
  progress indicator, so a superseded validation is cancelled promptly instead
  of running to completion.
- Opening the **ESPHome Device** window (context menu) for a *different* device
  while connected now switches over — disconnecting and reconnecting to the new
  host — instead of only refilling the fields and staying on the old device.
- While the device window is counting down to an auto-reconnect, the button now
  reads **Connect** (not Disconnect) and clicking it reconnects immediately,
  skipping the wait.
- The **ESPHome Device** window now detects a device that drops off the network
  silently (powered off, Wi-Fi lost) instead of freezing on stale values: a
  liveness watchdog pings the device and, after ~25s without a reply, closes the
  link so the existing auto-reconnect takes over.
- A validation error inside an automation action (e.g. `component.update:`
  referencing an unknown id) is now highlighted on the offending line instead of
  an unrelated earlier `id:` in the same component. ESPHome expands the
  shorthand and reports `id: <name>`; the annotator now matches the **value** to
  the source line rather than the first same-named key.
- The **ESPHome Device** window now advertises native-API version **1.14**, so
  the device no longer logs an `'…' using outdated API 1.12` warning on connect.

## [0.12.0]

### Changed

- **ESPHome Device** window: it now **auto-reconnects** on a backing-off schedule
  when the connection drops (e.g. the device reboots), and **auto-connects** when
  you open it from the config's context menu and a host is known. The Connect
  button moved to the left of the Host field, and the host/key fields are no
  longer stretched across the whole tool window.
- The **default backend** setting (Settings | Tools | ESPHome) now offers
  **Managed venv** alongside Local and Docker, so a provisioned venv can be the
  default for new run configurations and config validation — it was previously
  selectable only per run configuration.

## [0.11.0]

### Added

- Config **warnings** from `esphome config` (e.g. a strapping-pin advisory) are
  now shown in the editor as warnings, anchored at the relevant token (the pin,
  or a quoted name) — not just errors. Warnings appear even when the config is
  valid.

## [0.10.1]

### Fixed

- Device-window icons no longer use `IconUtil.colorize`, which is binary
  incompatible on some IDE builds (2025.1) and could fail to load the plugin.
  Icons are now bundled as monochrome SVGs with `_dark` variants the IDE picks
  by theme.

## [0.10.0]

### Added

- **ESPHome Device** tool window: connect to a running device over the native
  API and watch its entities (sensors, switches, lights, …) with live-updating
  state. Host/port are pre-filled from the open config's `api:`/address, with a
  manual override. Works with both plaintext and **encrypted** (`encryption:`)
  APIs — the Noise handshake uses only the bundled JDK crypto, no extra
  dependency.
- The device window now shows an **icon per entity** (by type / device class)
  and lets you **control** the common ones — on-off toggles for switches,
  lights and fans, and a Press button for buttons — grouped by type like Home
  Assistant / the ESPHome webserver.

### Changed

- New run configurations default to the **`run`** command (compile + upload +
  logs) instead of `compile`.
- **Host / SDL builds** now work from a GUI-launched IDE: when `sdl2-config` is
  found (e.g. Homebrew's `/opt/homebrew/bin`), its directory is added to `PATH`
  and the SDL library dirs it reports (`sdl2-config --libs`) are added to
  `DYLD_LIBRARY_PATH` / `LD_LIBRARY_PATH` for local/venv runs (and to the
  background `esphome config` validation).
- `run` / `upload` / `logs` over the **network** (an OTA host/IP/`.local`
  device) now run under a pseudo-terminal automatically, so ESPHome emits
  ANSI-colored logs and in-place progress. Serial operations are unchanged
  (a PTY there buffers output); the per-config "Emulate a terminal" still forces
  it.

### Fixed

- A shared package (a file with an `esphome:` block that is `!include`d by
  devices) is no longer treated as a standalone config: it's validated through
  the device that includes it — disambiguated by the selected run configuration —
  so its `${substitutions}` resolve instead of erroring. Connection-target
  derivation now resolves names in the chosen device's own include scope, so a
  device sharing a package no longer picks up a sibling's name/host.
- `id: !extend X` / `id: !remove X` in a `packages:`-based config now link to the
  `id: X` declared in the package — go-to-definition and find-usages connect the
  two, and the override is no longer mistaken for a second declaration of `X`.

## [0.9.1]

### Changed

- Config validation now runs `esphome config` on the **same backend** as a run
  (the default backend: Local / managed venv / Docker), so the editor's errors
  come from the same ESPHome you build with — no separate executable to wire up.

### Fixed

- Validating an `!include`d fragment now also saves the device root it runs
  against, so changes there aren't checked against stale on-disk content.
- Backend-specific messages when validation can't start (no esphome, venv not
  set up, or Docker missing) instead of a silent no-op.

## [0.9.0]

### Added

- Run configurations to **compile / run / upload / logs / clean** an ESPHome
  config, with output in the Run console. Right-click a config to create one.
- **Local**, **managed venv**, and **Docker** (`ghcr.io/esphome/esphome`)
  execution backends. The managed venv is created with one click in Settings and
  pip-installs a chosen esphome version — no global install, full serial support.
- Docker backend mounts the config directory at `/config`, with an optional
  shared host cache at `/cache` (off by default; needs the directory shared with
  Docker Desktop).
- **Settings** for the default backend and Docker image new run configs use.
- **State reporting** (`--states` / `--no-states`) and a **"Reset device before
  logs"** option (`--reset`) for `run`/`logs`, and a free-form **extra
  arguments** field.
- Per-run **"Emulate a terminal"** option (off by default) for in-place
  compile/OTA progress; leave it off for serial upload/logs.

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
