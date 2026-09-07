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
| Completion for `lvgl:` specifically | A plugin-side **language-schema converter** (`catalog/.../lang/LangSchemaRepository`, `EsphomeLangSchemaService`) reading bundled `plugin/src/main/resources/esphome/langschema/{esphome,lvgl}.json`, with lazily-resolved `extends` + a cycle guard | Device Builder's flattener explodes lvgl's recursive/shared schemas to ~15MB (which is exactly why device-builder itself refuses to ship a flattened lvgl body — see the overlay note below); this hybrid path keeps it ~1.7MB and handles recursion. Everything *except* lvgl still goes through the normal catalog (it has real field docs lvgl's raw schema lacks). |
| Validation / diagnostics | Shell out to the user's `esphome config` (`ExternalAnnotator`, `EsphomeConfigOutputParser`) | Ground truth — resolves `!secret`/`!include`/`!lambda`, substitutions, packages; nothing static can replace this |

**The lvgl catalog overlay is permanent, not temporary debt (corrected
2026-09-04):** `plugin/catalog-overlay/esphome/definitions/components/lvgl.json`
(an `overlayCatalog` Gradle task, chained after `vendorCatalog`) overrides the
vendored `lvgl` component body. This used to be attributed to the fix for
lvgl's schema-dump bloat not having reached a pinned `device-builder` release
yet — that turned out to be wrong. The fix *did* land and *is* in every
current `device-builder` sync, but device-builder responded to the
still-huge flattened output (lvgl's recursive widget tree has no `extends`
mechanism in their `ConfigEntry` model, so it fully inlines to ~14 MB) by
**deliberately shipping zero `config_entries` for `lvgl`** — see
`_YAML_ONLY_COMPONENT_IDS` in their `script/sync_components.py`
(esphome/device-builder#1510, merged 2026-06-16). No future ref bump will
ever restore it; **don't delete the overlay**. What it's for and how to
regenerate it is documented in the `overlayCatalog` task's comment in
`plugin/build.gradle.kts` — short version: check out device-builder from just
before #1510, run its `sync_components.py --limit-component lvgl` against the
current schema version, drop the `widgets` entry (the recursive tree — handled
separately by the langschema converter, and the reason for the 14 MB), and
hand-patch the `displays` field's `references_component` and the component's
mislabeled name/description/docs_url. The automations index needs no such
overlay — device-builder's vendored `automations.index.json` already carries
lvgl's triggers correctly on its own (verified 2026-09-04); don't reintroduce
a static copy of it, it will just go stale and silently regress every other
component's automation data.

## Checking whether the catalog pin needs bumping

`esphomeDeviceBuilderRef` (`gradle.properties`) is a fixed commit, not a
moving target — it silently drifts behind every new ESPHome release until
someone bumps it. Last refreshed 2026-09-04 to `292ed4f2` (schema 2026.8.2);
before that it had sat at a 2026-06-07 commit for three months, three ESPHome
minor releases stale. Check drift with:

```bash
gh api repos/esphome/device-builder/compare/<current-ref>...main \
  --jq '{ahead_by,behind_by}'
gh api repos/esphome/esphome/releases/latest --jq '.tag_name'
```

To bump: update the ref in both `gradle.properties` and the fallback default
in `plugin/build.gradle.kts` (keep them in sync — the properties file is what
actually takes effect; the Kotlin default is just what a fresh checkout with
no override falls back to), refresh
`plugin/src/main/resources/esphome/langschema/{esphome,lvgl}.json` from the
matching `schema.esphome.io/<version>/schema.zip`, run
`./gradlew vendorCatalog :catalog:test :plugin:test`, and re-check the lvgl
overlay (previous section) if the schema version moved far enough that lvgl's
own config options likely changed.

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
- **A secrets file's `---`/`---` front-matter block (the `is_secrets_file`
  marker convention) makes the file a *multi-document* YAML stream.** Any code
  reading a secrets file's real content via PSI must use
  `yaml.documents.lastOrNull()`, not `.firstOrNull()` — the first document is
  the front matter itself. `EsphomeSecret.topLevelMapping` (used by
  `secretEntries`/`isIncludedBySecretsFile`) got this wrong once already (bit a
  real secrets file that used front matter — `!secret` silently failed to
  resolve past it, with no error). The line-based masking code in
  `EsphomeSecretLines`/`EsphomeSecretMasking` is unaffected (it never parses
  YAML documents, just scans text lines), but any *new* PSI-based secrets code
  needs the same `lastOrNull()` treatment.
- **A YAML mapping *key* (`name:`) is not a `YAMLScalar` — a *value* is, but
  the "declaration element" for a key is the whole `YAMLKeyValue`, not its
  key's bare leaf token.** A scalar value gets wrapped in a composite PSI
  element (`YAMLScalar`, e.g. `YAMLPlainTextImpl`); a mapping key is a bare
  leaf token (`LeafPsiElement`, elementType `scalar key`) parented *directly*
  by the `YAMLKeyValue` — no wrapper at all. This bit secrets find-usages
  twice in the same feature: first, code that walks up from a caret position
  with `PsiTreeUtil.getParentOfType(element, YAMLScalar::class.java, false)`
  (the id find-usages pattern, correct there since an `id:` declaration lives
  on the *value* side) silently finds nothing for a key-side declaration —
  `getParentOfType` returns null, not a wrong answer, so it fails quietly.
  Second, and much less obvious: once code was written to accept the bare key
  leaf directly instead, it still didn't work in a real IDE, though it passed
  every test — because the *bundled YAML plugin already treats the whole
  `YAMLKeyValue` as the `PsiNamedElement` for a YAML key*, and the platform
  resolves a real Cmd-click/Find-Usages invocation to that `YAMLKeyValue`
  *before* ever consulting a custom `TargetElementEvaluator`/
  `UsageTargetProvider` — confirmed by temporarily logging what
  `FindUsagesHandlerFactory.canFindUsages`/`ReferencesSearch` actually receive
  (`element=YAML key value`, a `YAMLKeyValue`, never the leaf). Tests missed
  this because they built the target element by hand
  (`file.findElementAt(offset)`) and fed it straight to `myFixture.findUsages`,
  never exercising the platform's own target-resolution
  (`TargetElementUtil.findTargetElement`) the real gesture goes through — see
  `EsphomeSecretFindUsagesTest`'s "works end-to-end from the caret via
  platform target resolution" test, added specifically to close this gap.
  `EsphomeSecret.declaredSecretName` now takes the `YAMLKeyValue` itself, and
  `EsphomeSecretReference.resolve()` returns it directly (not `.key`). Lesson
  for any *new* declaration-like feature: don't just find "the right PSI
  node" from a caret offset — verify what the platform's own default handling
  for that language already treats as the named element, ideally by logging
  the actual target a real invocation produces, not by only testing a
  hand-built element.
- **`GlobalSearchScope.allScope(project)` / `FileTypeIndex` only cover files
  under a *registered module content root* — a `secrets.yaml` (or any config)
  doesn't have to sit inside one.** Hit this building secrets Find Usages: it
  worked in every light-fixture test (which registers a proper content root)
  but found zero usages in a real report, because that user's IDE window had
  a *different, unrelated* project as the active `Project` (confirmed by
  logging the "files in scope" — they were from that other project entirely,
  none from the device-config directory actually being edited). A device
  config directory opened standalone, or attached alongside another project
  in the same window, is a completely normal setup — don't assume the file
  you're resolving from is under `project`'s own content roots.
  `EsphomeSecretReferenceSearcher` no longer uses the index at all: it walks
  the filesystem (`VfsUtilCore.iterateChildrenRecursively`) down from the
  secrets file's own directory instead, matching how
  `EsphomeSecret.findSecretsFile` already resolves `!secret` — by filesystem
  proximity, same as ESPHome itself, not by IDE project structure. Prefer this
  pattern over index/scope-based search for anything rooted in "near this
  file on disk," not "somewhere in this project."
- **The IDE persists fold-region state per file, independently of any plugin
  — a session-restored tab can already have fold regions at the exact ranges
  a previous session's plugin code created, recreated by the platform
  *before* the plugin's own startup code runs.** Bit `EsphomeSecretMasker`:
  on a restart, its `rebuild()` tried `FoldingModelEx.addFoldRegion()` at
  those same ranges and got `null` back for *every* one (confirmed by
  logging: `linesWithValue=18 regionsCreatedNull=18`), since a region already
  existed there — so `ourRegions` ended up empty, nothing was left to
  re-collapse on caret move, and the file stayed unmasked once revealed until
  closed and reopened. Fixed by checking
  `com.intellij.codeInsight.folding.impl.FoldingUtil.findFoldRegion(editor,
  start, end)` first and adopting that region if one already exists, instead
  of only trying to create a new one. Relatedly: `ProjectActivity`
  (`postStartupActivity`) can run *before* session-restored tabs' editors
  exist, so a one-shot `EditorFactory.allEditors` sweep at startup isn't
  reliable for them either (no later `editorCreated` fires since those
  editors already existed) — subscribe to
  `FileEditorManagerListener.FILE_EDITOR_MANAGER`'s `fileOpened` too, which
  reliably fires for every file that becomes visible, restored tabs included.

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
