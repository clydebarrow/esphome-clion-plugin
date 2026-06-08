# Roadmap: includes, project model, and ID navigation

Status: Phases 1–3 done (go-to-def/find-usages/completion; rename deferred);
Phases 4–5 next (2026-06-08).

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
- **Rename deferred**: renaming from a reference works via the manipulator, but
  initiating rename on the `id:` declaration needs a `RenamePsiElementProcessor`
  (YAML scalars aren't `PsiNamedElement`). Tracked under hard parts.
- Tests: `EsphomeIdReferenceTest` (same-file, provides-inheritance, cross-include,
  undeclared, type-mismatch) + `EsphomeIdCompletionTest`.

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

### Phase 4 — graph-aware validation
Today the annotator runs `esphome config <thisfile>`, which fails on fragments.
Change `collectInformation`: if the file is a fragment, find its including
root(s) via the reverse include index and run `esphome config <root>` instead.
The parser already attributes each error to its *source* file+line (ESPHome
reports the real file), so a fragment error maps back correctly — minimal parser
change.

### Phase 5 — inspections
A `LocalInspectionTool` flagging id references that don't resolve in scope (with
a quick-fix to create the declaration or pick a near-match), and unused
declarations. Where the model becomes authoring help, not just navigation.

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
