# Roadmap: includes, project model, and ID navigation

Status: Phases 1–5 done (2026-06-09).

The objective: open a top-level ESPHome device YAML and treat its whole
`!include` graph as a navigable unit — resolve included files, and navigate via
entity/component **IDs** across files. This doc records the design and the
phased delivery so the work can be picked up incrementally.

## Reframing "import as a project"

CLion already opens a directory as a project, so there is no literal import
wizard to build. "Project" here means three model concepts the editor currently
lacks:

1. **Entry points** — which YAMLs are *device roots* (top-level `esphome:` key)
   vs. *fragments* (meant to be `!include`d; often partial/invalid alone).
2. **Include graph** — the transitive `!include` / `packages:` edges, in **both**
   directions.
3. **Global ID space** — ESPHome merges the whole include graph before
   compiling, so `id:`s are global across the connected graph. Resolution scope
   is "the include graph this file belongs to," not "this file."

Navigation, cross-file completion, and graph-aware validation all fall out of
these three.

## What the catalog already gives us (verified)

- `ConfigEntryType.ID` marks id-valued fields.
- `ConfigEntry.references_component` names the target component class for an id
  **reference** (`cv.use_id`). Verified populated in **760 / 904** component
  bodies (values like `i2c`, `power_supply`, `binary_sensor`, `web_server`).
- A bare `id:` **declaration** (e.g. `sensor.dht`) has `references_component:
  null`. So declaration vs. typed reference is cleanly distinguishable.
- `ComponentCatalogEntry.provides` lists the classes a component satisfies — the
  basis for type-matching a declaration against a reference's
  `references_component` (must also honour ESPHome class inheritance — see
  Phase 3 open question).

## Foundation: the model layer

**Include graph index** — a `FileBasedIndex<String, List<IncludeEdge>>` mapping
each YAML file to the relative paths it `!include`s (plus `packages:` entries).
An index so it is *reverse*-queryable: given a fragment, find the device roots
that include it (needed for validation re-targeting and resolution scope). The
resolved per-root graph is cached in a project `@Service` via
`CachedValuesManager` keyed on `PsiModificationTracker`.

**ID index** — a `FileBasedIndex<String /* id name */, IdDeclaration>` scanning
every YAML for `id:` keys, storing the name, file/offset, and the **owning
component class** (resolved from the enclosing component path through the
catalog: an `id:` under `sensor: - platform: dht` is a `sensor`/`dht`, which
`provides: [sensor, …]`). `provides` + domain is what lets us type-match a
declaration against a reference's `references_component`.

These two indexes are the hard part; the features below are comparatively thin
once they exist.

## Phased delivery

### Phase 1 — `!include` navigation (in progress)
A `PsiReferenceContributor` that, for scalars tagged `!include` (and the `file:`
key of the block form `!include { file:, vars: }`), contributes a
`FileReferenceSet`. Gives Ctrl-Click-to-file, path completion, and
rename-on-move for free. Also covers `packages:` local includes. No index
needed — ships first, self-contained.

### Phase 2 — include graph + ID index (done)
Implemented as the two `FileBasedIndex`es above plus two project services. No UI
yet (the optional gutter marker was deferred). Concretely:
- `index.EsphomeIncludeIndex` — `!include` targets keyed by basename
  (reverse-queryable), value = raw relative path.
- `index.EsphomeIdIndex` — `id:` declarations keyed by name, value =
  `IdDeclaration(offset, domain, platform)` (component class kept out of the
  index so a catalog bump needs no reindex).
- `services.EsphomeIncludeGraph` — `directIncludes` / `directIncluders` (precise,
  re-resolved per candidate) / `rootsOf` / `connectedFiles` (resolution scope).
- `services.EsphomeIds` — `declarationsIn(scope)` / `resolve(name, scope)`.
All read PSI/index under a read action. Covered by `EsphomeIncludeGraphTest` and
`EsphomeIdsTest`.

### Phase 3 — ID navigation (done, except rename)
Implemented in `references` + the completion contributor:
- `EsphomeIdReferences.referencedComponentOf` recognises an id-reference position
  (value whose field is `type: id` with a `references_component`);
  `.satisfies` type-matches a declaration via `domain == C` or `C ∈ provides`.
- `EsphomeIdReferenceContributor` → `EsphomeIdReference`
  (`PsiPolyVariantReferenceBase`, **soft**) resolves across
  `EsphomeIncludeGraph.connectedFiles`. Go-to-definition and find-usages work
  off this; completion offers in-scope, type-matching ids.
- **Find Usages + Rename (done)**: a YAML scalar isn't a `PsiNamedElement`, so
  the default caches-based search can't walk reference→declaration backwards.
  Solved without disturbing the YAML plugin via composable EPs:
  `EsphomeIdReferenceSearcher` (`referencesSearch`) finds references to a
  declaration over `connectedFiles`; `EsphomeIdFindUsagesHandlerFactory`
  enables the action; `EsphomeIdRenameProcessor` (`renamePsiElementProcessor`,
  order=first) rewrites the declaration (via the scalar manipulator) and all
  usages; `EsphomeIdRenameHandler` (`renameHandler`) makes Rename invokable on
  the declaration itself (`TargetElementUtil` won't target a non-named scalar).
  Rename from a reference uses the platform's standard handler + our processor.
- Tests: `EsphomeIdReferenceTest`, `EsphomeIdCompletionTest`,
  `EsphomeIdFindUsagesAndRenameTest`.

Original design notes:
- **Go-to-definition**: a `PsiReferenceContributor` on scalars in id-*reference*
  positions (catalog field `type == ID && references_component != null`),
  resolving via the ID index filtered to (a) the file's include-graph scope and
  (b) type compatibility (`declaration.provides ∪ {domain}` contains
  `references_component`, honouring inheritance). Resolve falls out of the
  reference.
- **Find usages**: declaration as a navigable target; `ReferencesSearch` finds
  references by the id's literal text (works cross-file — the word appears
  verbatim).
- **Completion**: extend the existing `CompletionContributor` to offer in-scope,
  type-matching ids in a reference position.
- **Rename**: `handleElementRename` on the reference + a
  `RenamePsiElementProcessor` for the `id:` declaration, updating all references
  across the graph.

### Phase 4 — graph-aware validation (done)
`EsphomeValidationAnnotator.collectInformation` now drops the "must have
`esphome:`" gate. A device root still validates itself; a fragment is validated
through the device root that includes it (`includingRoot` = topmost includer
that is a real ESPHome config, via `EsphomeIncludeGraph.rootsOf`); an orphan
fragment is skipped. `Info` separates the `configPath` (what `esphome config`
runs on) from the `targetPath` (the open file we annotate). The parser filters by
the open file's name, and `parse(…, includeTopLevelErrors = false)` for fragments
suppresses the root's headerless top-level errors (shown when the root is open).
Tests: `EsphomeValidationTargetTest` + a parser case.

### Phase 5 — inspections (done)
- `EsphomeUnresolvedIdInspection` (enabled, WARNING): flags a *typed* id
  reference whose value names no compatible declaration in scope, with
  "Change to '<near-match>'" quick-fixes (Levenshtein ≤ 2). High precision:
  typed references only, target class must be in `repo.referenceableClasses`,
  and suppressed entirely when the scope builds ids from `${substitutions}`
  (can't index those, so a literal ref may legitimately match). Complements the
  `esphome config` validation (instant + quick-fix; no save needed).
- `EsphomeUnusedIdInspection` (**disabled by default**, WEAK WARNING): flags an
  `id:` declared but whose name appears nowhere else in the connected scope —
  by whole-word *text* scan, so lambda uses (`id(x)`) count. Off by default
  because ESPHome ids are often referenced only externally (HA/API/web).
- `CatalogRepository.referenceableClasses` = top-level keys ∪ all `provides`.
- Tests: `EsphomeIdInspectionTest`.

### Phase 6 — substitutions (done)
`${name}` / `$name` resolution over the include graph:
- `services.EsphomeSubstitutions` — `definitionsInScope`, `resolve`,
  `valueOf` (recursive expansion), `knownNamesInScope` (incl. `!include … vars:`).
- `references.EsphomeSubstitutionReferenceContributor` → soft references from
  each usage to its `substitutions:` definition (navigation); `…Syntax` is the
  shared `${name}`/`$name` scanner.
- Completion of substitution names inside `${…}` (in the completion contributor).
- `inspections.EsphomeUnresolvedSubstitutionInspection` (enabled): flags an
  undefined braced `${name}` with near-match quick-fixes; conservative —
  braced-only, include `vars:` count as defined, silent when nothing is defined
  (config may use command-line `-s`).
- Tests: `EsphomeSubstitutionTest`, `EsphomeSubstitutionInspectionTest`,
  `EsphomeSubstitutionCompletionTest`.

### Phase 6b — substitution value resolution (done)
Templated ids now participate in resolution. The id index stores the raw name
(`${prefix}_relay`); `EsphomeIds.declarationsIn` expands it at query time
(`Declaration.effectiveName`) using `EsphomeSubstitutions.expand(text, defs)`.
References expand their own text before matching, so a templated reference and a
templated declaration both resolve by effective name. Completion offers expanded
names. The unresolved-id inspection matches on `effectiveName` and stays quiet
only when the reference or some declaration can't be fully expanded (replacing
the old blanket "any templated id in scope" suppression).

## Status: phases 1–6 complete

Possible future work: find-usages/rename of substitution variables and of
templated ids; id completion inside schema-less components (lvgl); remote
(`github://`) packages.

## Known hard parts / risks

- **Substitutions & vars.** `${var}` (from `substitutions:` and `!include`
  `vars:`) can appear inside `id:` values, breaking literal matching. Initial
  stance: treat any `${…}`-containing scalar as opaque — don't resolve, don't
  flag — to avoid false errors. Full substitution resolution is a later
  sub-project.
- **Remote packages.** `packages:` can reference `github://…`. Out of scope
  initially; resolve local file packages only, ignore remote.
- **`!extend` / `!remove`.** These modify packaged ids by reference; they are id
  *references* too and should resolve, but mutation semantics can wait.
- **Scope ambiguity.** A fragment included by multiple roots (or none, while
  being authored): resolution scope = union of all graphs containing it, with a
  same-directory fallback for orphans, so editing a not-yet-included fragment is
  not a wall of red.
- **YAML PSI for rename.** YAML scalars are not `PsiNamedElement`; rename needs a
  custom processor. Manageable but fiddly.

## Open questions

- Does ESPHome's `references_component` type-match honour class inheritance
  (e.g. does a reference to `sensor` accept a `dht` sub-id)? Governs how strict
  Phase 3 type-matching should be. Verify against ESPHome before Phase 3.

## Recommended order

Phases 1 → 2 → 3 deliver the headline experience (open a device file, jump
through includes, navigate ids). Phases 4 and 5 are independent follow-ons.
