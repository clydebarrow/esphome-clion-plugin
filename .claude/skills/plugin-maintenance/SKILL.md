---
name: plugin-maintenance
description: Ongoing maintenance guide for the esphome-clion-plugin repo — architecture, build/test/release commands, local test-build versioning, and known gotchas. Use for any work in this repo (bug fixes, features, catalog/schema updates, releases) so decisions stay consistent with prior ones.
---

# ESPHome plugin maintenance

JetBrains IDE plugin (CLion/IDEA/PyCharm, 2024.2+) for ESPHome YAML: completion,
hover docs, validation, navigation, run/flash, and a live device window.
Repo: `clydebarrow/esphome-clion-plugin`, GitHub CLI is authed as `clydebarrow`.

Read this before non-trivial work in the repo. For deep historical context on a
specific past decision, this session's persistent memory
(`~/.claude/projects/-Users-clyde-dev-opensourceprojects-esphome-clion-plugin/memory/`)
has longer write-ups — but treat it as point-in-time; verify against current
code before asserting a stale claim as fact.

## Architecture (verify against `docs/esphome-data-sources.md` before relying on this)

Two-module Gradle build:

- **`:catalog`** — pure-Kotlin model + loader (kotlinx.serialization), no
  IntelliJ SDK dependency, builds on plain Maven Central. Package
  `io.esphome.clion.catalog`.
- **`:plugin`** — the actual IDE plugin; depends on `:catalog`, needs the
  IntelliJ Platform SDK. Packages under `io.esphome.clion`: `api` (native API
  client, device window), `completion`, `documentation`, `index`
  (`FileBasedIndex`es for includes/ids), `inspections`, `lambda`, `psi`,
  `references` (go-to-def/find-usages/rename), `run` (compile/upload/logs
  configs), `secrets`, `services`, `settings`, `validation`.

**Data-source split — ESPHome publishes no JSON Schema, don't go looking for
one:**

| Concern | Source | Mechanism |
|---|---|---|
| Completion / hover / docs (most components) | Device Builder's *normalized* catalog (`components.index.json` + `components/<id>.json`), vendored via the `vendorCatalog` Gradle task, pinned by `esphomeDeviceBuilderRef` in `gradle.properties` | Fully flattened/resolved JSON, refreshed per pin bump |
| Completion for `lvgl:` specifically | A plugin-side **language-schema converter** (`catalog/.../lang/LangSchemaRepository`, `EsphomeLangSchemaService`) reading bundled `plugin/src/main/resources/esphome/langschema/{esphome,lvgl}.json`, with lazily-resolved `extends` + a cycle guard | Device Builder's flattener explodes lvgl's recursive/shared schemas to ~15MB; this hybrid path keeps it ~1.6MB and handles recursion. Everything *except* lvgl still goes through the normal catalog (it has real field docs lvgl's raw schema lacks). |
| Validation / diagnostics | Shell out to the user's `esphome config` (`ExternalAnnotator`, `EsphomeConfigOutputParser`) | Ground truth — resolves `!secret`/`!include`/`!lambda`, substitutions, packages; nothing static can replace this |

**Known temporary debt:** `plugin/catalog-overlay/esphome/definitions/` (an
`overlayCatalog` Gradle task, chained after `vendorCatalog`) drops a
locally-regenerated `lvgl.json` + a relabeled automations index over the
vendored catalog. This exists because the upstream `esphome`/`device-builder`
fix for lvgl's schema-dump bloat hadn't shipped in a pinned `device-builder`
release yet. **Before touching lvgl catalog code, check whether
`esphomeDeviceBuilderRef` has since moved past that fix** — if so, delete the
overlay task and `plugin/catalog-overlay/` rather than maintaining it further.

## Build & test

```bash
./gradlew :catalog:test                     # fast, no SDK download
./gradlew :plugin:test                      # completion/validation/doc-provider/etc.
./gradlew vendorCatalog                     # refresh the full catalog from the pinned device-builder ref
./gradlew :plugin:buildPlugin               # assemble the distributable zip (downloads the SDK first time)
./gradlew :plugin:runIde                    # sandbox IDE with the plugin loaded, for manual testing
```

CI (`.github/workflows/ci.yml`) runs `:catalog:test :plugin:test` on every push/PR
to `main`. Always run the relevant test module locally before considering a
change done; `:plugin:test` is the one that matters for completion/validation/
doc-provider/annotator changes.

Manual smoke test: open `examples/living_room.yaml` in the sandbox IDE launched
by `runIde` — completion, hover/Ctrl-Q, and inline validation errors should all
work (set the esphome path under Settings → Tools → ESPHome if not on PATH).

## Giving the user a build to test on real hardware

After a change the user wants to try before release, build a local zip:

```bash
./gradlew :plugin:buildPlugin -PpluginVersion=<test-version>
```

Output: `plugin/build/distributions/esphome-clion-plugin-<version>.zip`. They
install it via Settings → Plugins → ⚙ → Install Plugin from Disk (then restart —
reload always needs a restart here, see Gotchas).

**Versioning rule — do not use `X.Y.99`:** install-from-disk works regardless of
version ordering, so the test build does not need to sort above the published
release. Using a version *above* latest published (the old `.99` trick) makes
the IDE think it's already newer than the Marketplace, so real updates stop
showing (hit in practice with `0.16.99` blocking the `0.16.3` release). Instead
use a pre-release of the next intended version, e.g. `0.17.0-test1` (matches the
project's existing `-rcN` convention for betas) — it sorts *below* the eventual
final release, so once that publishes the IDE offers it normally. If a user is
already stuck on a too-high test build, the fix is building the actual released
version from that tag and having them install *that* from disk.

## Releasing

Keep `CHANGELOG.md`'s `[Unreleased]` section current as you land changes (Keep a
Changelog format; it's rendered into both the plugin's in-IDE change notes and
the GitHub release body). To ship:

1. Promote `[Unreleased]` to a version heading in `CHANGELOG.md`.
2. `git tag vX.Y.Z && git push origin vX.Y.Z` — GitHub Actions
   (`release.yml`) runs tests, builds the zip, and publishes a GitHub release.
   A tag with a pre-release suffix (`vX.Y.Z-rc1`) publishes as a GitHub
   pre-release.
3. Marketplace publish is automatic on that tag *if* `PUBLISH_TOKEN` +
   `PRIVATE_KEY`/`CERTIFICATE_CHAIN`/`PRIVATE_KEY_PASSWORD` repo secrets are
   set; otherwise only the GitHub release is made. Clean semver → **stable**
   channel, pre-release suffix → **beta** channel.
4. Plugin version comes from the tag (leading `v` stripped) — never hand-edit a
   version into `plugin/build.gradle.kts` for a release.

`since-build=242` (no `until-build` — deliberately removed so the plugin stays
available on future IDE releases without a republish each cycle; every release
still runs through `verifyPlugin` for binary compatibility, so don't reintroduce
an upper bound without a reason).

## Gotchas worth knowing before you spend time rediscovering them

- **Plugin reload/install-from-disk always forces a full IDE restart** in dev
  (2026.1). Investigated at length — all EPs are `dynamic="true"`, file-based
  indexes and the API connection thread are both ruled out as sole causes, cause
  is unknown and this was deliberately deferred (dev-workflow annoyance only;
  real users restart on update anyway). Don't re-litigate this from scratch —
  check the `plugin-reload-needs-restart` memory file first if you want the
  ruled-out list before investigating further.
- **Run-console `\r`-only chunks render as a blank line**, not stale text —
  `ConsoleViewImpl` keeps only the text after the last `\r` in a chunk, so a
  chunk that *ends* on a carriage return (common with esptool's progress bar,
  which draws `\r`+clear+bar with no trailing newline) collapses to empty. Fixed
  once in `EsphomeProcessHandler`/`CarriageReturnCoalescer` (carries a trailing
  `\r` into the next chunk) — if a similar "progress bar looks blank in the IDE
  but fine in a terminal" bug shows up elsewhere in `run/`, this is almost
  certainly the same class of bug, not a new one.
- **Background validation must not resave-and-reformat the file it's
  validating** — an earlier bug flushed unsaved edits via `saveDocument`, which
  runs the IDE's on-save processors (trim trailing whitespace/blank lines) and
  could silently mutate what the user was typing. Use the as-is save path
  (`saveDocumentAsIs`), not a plain save, anywhere validation needs to flush
  before shelling out to `esphome config`.
- **ESPHome publishes no JSON Schema.** If a task description assumes one
  exists (e.g. "just point `JsonSchemaFileProvider` at it"), that premise is
  wrong — see the data-source split above.

## Where things live

- `docs/esphome-data-sources.md` — the authoritative, longer version of the
  architecture section above; re-read it if the catalog/validation split ever
  seems to need revisiting.
- `docs/roadmap-includes-and-navigation.md` — completed roadmap for
  include-graph/id navigation/rename/inspections (all 5 phases done; useful as
  a map of where that functionality lives, not as a to-do list).
- `CHANGELOG.md` — release history and the source of in-IDE change notes.
- `examples/` — sample configs used for manual sandbox-IDE testing.
- `site/` — the GitHub Pages showcase/user-guide site.
