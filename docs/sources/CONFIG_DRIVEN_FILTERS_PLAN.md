# Config-Driven Filters — Audit & Implementation Plan (2026-07)

Successor stage to `MANGASOURCE_DECOUPLING_PLAN.md`. Closes the documented Stage-2 gap:
a config-only source can plain-search but the JSON schema cannot describe its advanced
filters, so sort/genre search still drops to the legacy scraper path
(`SearchRepositoryImpl` `isPlainTextSearch` gate) and config-only sources get an empty
filter sheet (`HomeFeedRepositoryImpl.loadFilters()` KDoc).

**Target invariant** (extends the decoupling invariant):

> A generic source's discovery, icon, endpoints, filters, filter UI, and request mapping
> are fully defined by validated configuration. Adding or changing its filters must not
> require editing `MangaSource`, a compiled source roster, a source-specific Kotlin
> filter class, or a `when(api)` branch.

---

## 1. Audit — what exists today (verified in code, 2026-07-10)

### 1.1 Legacy filter surface

- `BaseMangaRepository` (`sources/legacy/.../sources_repositry/BaseMangaRepository.kt:44-48`)
  declares `abstract val sortTypes/allGenres/blackListGenres: Set<String>` and
  `fetchSearchDataF(searchType: SearchType)`.
- `SearchType` (`domain/.../presentation/features/home/data/SearchType.kt:6-12`):
  `Normal(query)` | `SORT(query, sortType, genres)` | `GENRES(query, genres)` — genres
  travel as ONE comma-joined display string (`searchTypeOf`, `data/.../mapper/HomeMappers.kt:138-154`).
- Of the 12 generic pilots, only **three** have live server-side filters:

| Pilot | Axes | Transport (verbatim from the legacy repo) |
|---|---|---|
| **Lekmanga** (`ar/mangalek/MangaLekRepositoryv2.kt`) | sort (4, Arabic) + genres (~180, Arabic, sent verbatim) | POST madara form to `{base}wp-admin/admin-ajax.php`: `vars[s]`=query, `vars[wp-manga-genre]`=genre CSV, `vars[meta_key]`=sort id via `genreToMetaKeyMap` (`"الاحدث"→"_latest_update"`, others → `"_wp_manga_views"`), static `action=madara_load_more`/`vars[orderby]=meta_value_num`/… (lines 374-407, 595-611) |
| **SwatManga** (`ar/swatmanga/SwatMangaRepository.kt`) | sort only (6, Arabic) | GET `{api}series/?search={q}&order_by={sortMap[sort]}&page=1&page_size=20`; `sortMap` display→slug (`"الأعلى تقييماً"→"-rating"`, …) (lines 127-205) |
| **Zazamanga** (`en/zazamanga/ZazamangaRepository.kt`) | genres (~65 slugs; sort list commented out) | GET `{base}?s={q}&post_type=wp-manga&genre%5B%5D={slug}&op=&author=&artist=&release=&adult=0`; `allGenres` already exposes slugs (`genreMap.values`) so value==sent string (lines 84-89, 110-293) |

- **Demonicscans** declares 3 sorts + 7 genres but never transmits them — its search URL
  uses `toNormalQuery()` which blanks the query in SORT/GENRES modes (lines 48-102): a
  broken axis, not a spec to preserve. The other 8 pilots have empty `sortTypes`/`allGenres`.

### 1.2 Search pipeline

- Domain: `SearchFilters(sortTypes: List<String>, genres: List<String>)` +
  `SearchMode {NORMAL, SORT, GENRES}` (`domain/.../model/home/SearchFilters.kt`);
  `SearchRepository.searchSource(query, mode, sort, genres)`;
  `searchAllRepos(query)` is plain-text only.
- `HomeFeedRepositoryImpl.loadFilters()` (lines 314-330) reads the LEGACY repo's
  `sortTypes`/`allGenres`; config-only sources → empty filters.
- `SearchRepositoryImpl.searchSource` (lines 73-108) routes to the generic client only
  when `mode == NORMAL && sort == null && genres.isEmpty()`; any filtered search drops to
  legacy `fetchSearchDataF` — **the gate this plan deletes for config-backed sources**.
- Presentation: `SearchState.selectedSort: String?` + single-select `selectedGenres`;
  intents `OnLoadFilters`/`OnGenreClick`/`OnSortSelect` (+dormant `OnApplyFilters`);
  immediate-apply; selections cleared on overlay close (`onClose()`), options reloaded per
  active source on every open. Nothing persisted; nothing in nav args; the source cannot
  change while the Search overlay is open (it replaces Home, where the tabs live).
- UI: one composable, `ui/.../search/SearchFilterSheet.kt` — M3 `ModalBottomSheet`, genre
  `FilterChip` flow-row (single-select) + sort `ExposedDropdownMenuBox`, immediate-apply,
  bottom button = close only.

### 1.3 Generic engine request layer

- `EndpointSpec` (`sources/contracts/.../model/SourceConfig.kt:157-190`): url template +
  `method get|post-form|post-json` + `formBody: Map<String,String>` (values templated,
  keys static) + `jsonBody` template + response-side `listFilters` (post-parse predicates
  — NOT request-building).
- `GenericSourceClient.search(query, page)` (engine, lines 89-90) carries **no filter
  axis**; all request variables come from the closed `vars()` map (lines 608-625:
  `baseUrl/imageBase/page/pageOffset/query/queryEncoded/queryJson/itemUrl/chapterUrl/id`),
  expanded by `Templates.expand` (placeholder charset `[a-zA-Z0-9_]+`, unknown left intact).
- `SourceRequest` (`sources/contracts/.../Ports.kt`): prebuilt URL string, single-value
  `formBody: Map<String,String>?`, `jsonBody: String?` — **no structured query params, no
  multi-value form**. Ktor's `Parameters.build` (KtorHttpExecutor) supports repeated keys,
  so the port is the only blocker for `repeat` encoding on forms.
- `DefaultSourceConfigValidator`: all-or-nothing per document; free-text errors prefixed
  `source '<api>':`; `SUPPORTED_*` whitelists mirror engine string handling; endpoint
  checks in `validateEndpoints` (lines 109-144) beside the `listFilters` loop.
- `SourceConfigParser` uses `ignoreUnknownKeys = true` → adding a `filters` field is
  forward-compatible; **schemaVersion stays 1** (see §7 versioning rules).
- `DefaultSourceRegistry.get(api)` returns a **bare** `GenericSourceClient` for
  config-backed apis (SourcesGenericModule ~line 122) — no `FallbackSourceClient` wrap in
  the production search path. `FallbackSourceClient` still implements the contract and is
  used by tests/debug tooling; its search fallback must never engage for a filtered search.
- Signature blast radius of extending `MangaSourceClient.search`: 3 production
  implementors (`GenericSourceClient`, `LegacyKotlinSourceClient`, `FallbackSourceClient`),
  2 production call sites (`SearchRepositoryImpl:91,163`), ~14 test fakes (enumerated in
  the audit transcript; mechanical).
- Request-recording test doubles already exist: `FakeHttpExecutor.requested`
  (engine `GoldenFixtures.kt`), `RecordingHttp.lastJsonBody`
  (`GenericSourceClientPostJsonFilterRootTest.kt`).

---

## 2. Schema design (`:sources:contracts`, additive, schemaVersion 1)

`SourceConfig` gains one ordered list. JSON array order **is** UI order and request
composition order.

```kotlin
val filters: List<FilterDefinition> = emptyList()
```

```kotlin
@Serializable
data class FilterDefinition(
    val id: String,                     // stable identity: [a-z0-9_]{1,64}; persisted nowhere yet, but treated as API
    val label: String,                  // display label (required non-blank). Standard ids may be re-titled by localized UI headers.
    val type: String,                   // "select" | "multiselect" | "toggle" | "text" | "number"
    val options: List<FilterOptionSpec> = emptyList(),  // required for select/multiselect; forbidden for toggle/text/number
    val default: String = "",           // scalar default (select option value / toggle "true"|"false" / text / number literal)
    val defaults: List<String> = emptyList(),           // multiselect default (option values)
    val required: Boolean = false,      // must always reach the request → validator demands a usable default
    val request: FilterRequestSpec,
    val visibleWhen: List<FilterConditionSpec> = emptyList(), // ALL must hold; hidden ⇒ not rendered AND not sent
    val excludeOf: String = "",         // multiselect only: this filter is the exclusion counterpart of filter <id>
    val appliesTo: List<String> = listOf("search"),     // endpoint verbs; v1 whitelist = {"search"}
)

@Serializable
data class FilterOptionSpec(
    val value: String,                  // backend value — the string that reaches the request; stable
    val label: String = "",             // display; defaults to value
)

@Serializable
data class FilterRequestSpec(
    val target: String,                 // "query" | "path" | "form" | "header" | "body-json"
    val param: String,                  // query/form/header: parameter name (may be "genre[]").
                                        // path/body-json: template placeholder name ([a-zA-Z0-9_]+, must appear in url/jsonBody)
    val encode: String = "single",      // "single" | "csv" | "repeat" | "json-array"
    val delimiter: String = ",",        // csv only
    val omitIfEmpty: Boolean = true,    // query/form/header only (placeholders cannot be omitted)
    val trueValue: String = "true",     // toggle only: value sent when on
    val falseValue: String = "",        // toggle only: value when off ("" + omitIfEmpty=true ⇒ absent when off)
)

@Serializable
data class FilterConditionSpec(
    val filter: String,                 // referenced filter id
    val anyOf: List<String>,            // condition holds when the referenced filter's effective value ∩ anyOf ≠ ∅
)
```

Design decisions:

- **Ordered generic list, not hardcoded top-level fields.** `genres`/`sort`/`status`/
  `language`/`type` are **conventions on `id`** enforced by the validator (type
  compatibility, §4 item 17) and used by the UI only for localized section titles —
  request behavior is 100% the generic pipeline. `genre=a,b` vs `genre[]=a&genre[]=b` vs
  JSON body array = same filter with different `request` blocks, zero source branching.
- **Stable ids + backend `option.value`s** drive everything; labels are display-only and
  renameable (§7).
- **Include/exclude pairs** = two multiselect definitions; the exclusion side sets
  `excludeOf: "<includeFilterId>"`. Deterministic conflict rule: a value selected on both
  sides is dropped from the exclude side (include wins). No tri-state UI in v1.
- **No executable expressions**: every field is data validated against whitelists; the
  only "logic" is the fixed composition algorithm in §3.
- **Deferred vocabulary** (documented, validator-rejected until implemented): `range` and
  `date` control types; `appliesTo` verbs other than `search` (home/featured filter bars
  are a UI feature this stage doesn't build). Being whitelist-rejected means a config
  can't silently claim them.

### Runtime UI model (`:domain`, pure Kotlin — `:presentation`/`:ui` never see `:sources:*`)

```kotlin
enum class FilterControlType { SELECT, MULTISELECT, TOGGLE, TEXT, NUMBER }
data class FilterOption(val value: String, val label: String)
data class FilterCondition(val filterId: String, val anyOf: List<String>)
data class SourceFilter(          // ordered projection for rendering — carries NO request mapping
    val id: String, val label: String, val type: FilterControlType,
    val options: List<FilterOption>, val defaultValues: List<String>,
    val required: Boolean, val visibleWhen: List<FilterCondition>,
    val excludeOf: String? = null,
)
data class FilterSelections(val byId: Map<String, List<String>> = emptyMap())
```

- `RuntimeSourceDescriptor` gains `filters: List<SourceFilter>` (derived in
  `toRuntimeDescriptor()`); request mapping stays engine-internal (`FilterDefinition`),
  preserving the descriptor's "display/route, not executable spec" contract.
- `MangaSourceClient.search` becomes
  `search(query: String, page: Int, filters: FilterSelections = FilterSelections())`.
- `SearchFilters` + `SearchMode` are **deleted** (replaced by `List<SourceFilter>` +
  `FilterSelections`); the legacy `SearchType` derivation moves wholly into `:data`.

---

## 3. Request mapping — deterministic composition algorithm

New engine-internal pure function `FilterRequestComposer` (`:sources:engine`), unit-tested
in isolation, consumed by `GenericSourceClient` for verbs listed in `appliesTo`:

Inputs: the source's `filters` (declaration order), the verb, `FilterSelections`.
Outputs: `queryPairs: List<Pair<String,String>>`, `formEntries: List<Pair<String,String>>`,
`headerEntries: Map<String,String>`, `templateVars: Map<String,String>`.

1. **Unknown filter ids** in the selections (not declared in config) → ignored (state-side
   guard; config-side mistakes are validation errors).
2. **Effective value** per definition, in declaration order:
   selection if present, else `default`/`defaults`. For select/multiselect, values not in
   `options` are dropped and survivors are re-ordered to option-declaration order
   (deterministic). For `select`, more than one surviving value → first by option order
   wins (**conflicting values** rule). For `toggle`, `"true"` → `trueValue`, anything else
   → `falseValue`. For `number`, non-numeric values are dropped. For `excludeOf` pairs,
   values also present in the include filter's effective value are dropped from the
   exclude side.
3. **Visibility**: `visibleWhen` conditions evaluate against the referenced filters'
   effective values (step 2, independent of the referenced filter's own visibility —
   simple and cycle-free by validation). Any unsatisfied condition ⇒ the filter
   contributes nothing to the request.
4. **Required**: after steps 2-3 a `required` visible filter with an empty effective value
   fails the request with `AppError.Validation.Required("filter:<id>")` (fail-closed;
   normally unreachable because validation demands a default).
5. **Emptiness**: empty effective value + `omitIfEmpty=true` → nothing. Empty +
   `omitIfEmpty=false` → an empty-valued param (query/form/header). Path/body-json
   placeholders always expand (path is validator-guaranteed non-empty; body-json expands
   to `[]` for `json-array`, `""` for `single`).
6. **Encoding** (`values` = effective value list):
   - `single` → one pair `param=values[0]`
   - `csv` → one pair `param=values.joinToString(delimiter)`
   - `repeat` → one pair per value (`genre[]=a`, `genre[]=b`)
   - `json-array` → JSON array literal of JSON-escaped strings (`["a","b"]`) — body-json only
7. **Targets**:
   - `query` → after `Templates.expand(endpoint.url, vars)`, append
     `?`-or-`&` + pairs percent-encoded with the existing `UrlEncode` (names AND values —
     `genre[]` → `genre%5B%5D`, matching the legacy Zazamanga bytes; space → `%20`).
   - `form` → static `endpoint.formBody` entries first (declaration order), then filter
     entries in filter order. `SourceRequest.formBody` becomes ordered multi-value
     (`List<Pair<String,String>>?`); `KtorHttpExecutor` appends each pair
     (Ktor `Parameters` natively supports repeats).
   - `header` → merged over the computed request headers (filter wins on same name).
   - `path` → added to the template vars (`param` must appear as `{param}` in the url);
     value percent-encoded, csv-joined if multiple.
   - `body-json` → added to the template vars for `jsonBody` expansion; `single` = JSON-escaped
     scalar content (author writes `"{param}"`), `json-array` = complete array literal
     (author writes `{param}` bare).
8. **URL encoding** is applied exactly once per target as above; form/header values go raw
   to Ktor (which encodes forms itself) — mirrors how `{query}` in `formBody` works today.

`home`/`featured`/`details`/`chapters`/`pages` calls pass an empty `FilterSelections`
(v1 `appliesTo` is search-only) — behavior is byte-identical for every existing stanza.

---

## 4. Validation (`DefaultSourceConfigValidator`, generic sources only)

New `validateFilters(source, errors)` after `validateFields`, plus whitelists
`SUPPORTED_FILTER_TYPES`, `SUPPORTED_FILTER_TARGETS`, `SUPPORTED_FILTER_ENCODINGS`,
`SUPPORTED_FILTER_VERBS = setOf("search")`. Error format (satisfies the required
`source api → endpoint → filter id → invalid field` shape):

```
source '<api>': filters: filter '<id>': <field>: <message>
source '<api>': filters: filter '<id>': appliesTo 'search': <message>   (endpoint-scoped checks)
```

Checks (numbering = the requirement list):

1. duplicate filter `id` (within the source)
2. duplicate `option.value` within a filter (ambiguous selection)
3. blank `id` (+ charset `[a-z0-9_]{1,64}`), blank `label`, blank `option.value`
4. `type` not in whitelist (incl. the reserved-but-unimplemented `range`/`date`)
5. invalid `default` for the type: toggle default ∉ {`""`,`true`,`false`}; number default
   non-numeric; select/multiselect handled by 6/7
6. `default` not among declared `option.value`s (select)
7. multi-select default misuse: `defaults` on a non-multiselect / `default` on a
   multiselect / any `defaults` entry not a declared option value
8. `required=true` without a usable default (empty effective default) — a required filter
   the runtime could not satisfy deterministically
9. `request.target` not in whitelist
10. `request.encode` not in whitelist, or incompatible with the target
    (`repeat` → query/form only; `json-array` → body-json only; body-json accepts only
    `single`/`json-array`) or with the type (`csv`/`repeat`/`json-array` require
    multiselect; toggle/text/number/select are `single`)
11. `visibleWhen.filter` referencing an unknown filter id
12. invalid condition refs: self-reference; `anyOf` empty; `anyOf` values not among the
    referenced select/multiselect/toggle filter's possible values
13. dependency cycles across the `visibleWhen` graph (DFS)
14. invalid include/exclude combos: `excludeOf` on a non-multiselect, referencing an
    unknown/non-multiselect filter, self-reference, chained excludes
    (`excludeOf` target itself has `excludeOf`), or overlapping `defaults` between the pair
15. `appliesTo` verb absent from `source.endpoints` (filter mapped to a nonexistent
    endpoint) or outside `SUPPORTED_FILTER_VERBS`
16. unsupported body mapping for the endpoint method: `body-json` target on an endpoint
    whose method isn't `post-json`; `form` target on an endpoint whose method isn't
    `post-form`
17. standard-id type compatibility: `sort` → select; `genres`/`status`/`language`/`type`
    → select or multiselect
18. plus structural safety: `param` blank; path/body-json `param` violating the
    placeholder charset, colliding with reserved template vars
    (`baseUrl/imageBase/page/pageOffset/query/queryEncoded/queryJson/itemUrl/chapterUrl/id`),
    or missing from the endpoint's `url`/`jsonBody` template; `path` target without a
    non-empty guaranteed default; `form`-target `param` colliding with a static
    `formBody` key; `query`-target `param` already hardcoded in the url template
    (`?param=`/`&param=` heuristic); options declared on toggle/text/number

All errors accumulate (no fail-fast) and the document is rejected all-or-nothing —
malformed filters can never be silently ignored. `ConfigBackedSourceCompletenessTest`
remains the build-time gate that the bundled document passes.

---

## 5. Runtime & UI

### `:data` — routing (the invariant enforcement point)

- `HomeFeedRepository.loadFilters(): AppResult<List<SourceFilter>>`:
  config-backed → `descriptor.filters` (ordered, from validated config);
  legacy → adapter: non-empty `sortTypes` → `SourceFilter("sort", SELECT, value==label)`,
  non-empty `allGenres` → `SourceFilter("genres", SELECT, value==label)` (single-select,
  today's UI behavior). One rendering path for both worlds.
- `SearchRepository.searchSource(query: String, selections: FilterSelections)`:
  - config-backed api → `client.search(query, 1, selections)` **unconditionally**. The
    `isPlainTextSearch` drop-to-legacy gate is deleted. A generic source never touches
    legacy filter code — filters its config doesn't declare simply don't exist (composer
    ignores unknown ids).
  - legacy api → translate selections to `SearchType` in `:data` (sort selected →
    `SORT(query, sort, genresCsv)`; genres only → `GENRES(query, genresCsv)`; else
    `Normal(query)`) — exactly today's `searchTypeOf` semantics, keeping every legacy
    source functional.
  - `FallbackSourceClient.search`: when `selections` is non-empty, never falls back to
    the legacy client (a legacy fallback would silently drop filters → the forbidden
    wrong-but-Success mode); transient-failure fallback remains for plain searches.
- `searchAllRepos` stays plain-text (unchanged — documented limitation).

### `:presentation` — SearchViewModel

- `SearchState`: `filters: List<SourceFilter>` (ordered), `selections: Map<String, List<String>>`.
- On filters load: seed `selections` from `defaultValues`, then **prune** any held
  selection whose id or values are unknown to the new filter list (safe drop of
  removed/renamed state — requirement "unknown or removed saved IDs dropped safely").
- Intents: `OnFilterChange(id, values)` (immediate-apply re-search, preserving today's
  UX: fires even with a blank query when a non-default selection exists — the legacy
  genre-browse behavior, generalized), `OnResetFilters` (deterministic: back to defaults +
  re-run), replacing `OnGenreClick`/`OnSortSelect`/`OnApplyFilters`.
- Source scoping is inherited from the overlay lifecycle (selections cleared in
  `onClose()`, options reloaded per active source on open) + the pruning step; a
  source-switch leak test pins it.
- Selections survive recomposition/pagination by living in MVI state (they already do).

### `:ui` — generic renderer

`SearchFilterSheet` re-rendered from `List<SourceFilter>` in declared order:

- `SELECT` ≤ 8 options → `ExposedDropdownMenuBox` (today's sort UX); > 8 → single-select
  `FilterChip` flow-row with collapsible header (today's genres UX)
- `MULTISELECT` → multi-select chip flow-row (collapsible)
- `TOGGLE` → switch row; `TEXT` → `OutlinedTextField`; `NUMBER` → numeric-keyboard field
- Standard ids get localized section titles (`strings_pfix_filters.xml`, all 11 locales);
  custom filters show their JSON `label` verbatim. New "Reset" action in the sheet.
- Empty `filters` → today's "no filters" panel; plain search unaffected.

Heuristics (dropdown vs chips) are UI-side presentation choices keyed on control type and
option count — never on source api — so the JSON stays UI-agnostic.

---

## 6. Pilot migration — Lekmanga (+ parity test first)

Lekmanga is the only pilot where BOTH axes are live, and it exercises the most schema at
once: select-with-value-mapping (sort display → `meta_key`), genre CSV, `form` target,
static-formBody merge, POST madara. Its generic search stanza is already
`post-form` to `wp-admin/admin-ajax.php` with the static `vars[...]` body — filters bolt
on without touching the endpoint:

```json
"filters": [
  { "id": "sort", "label": "ترتيب", "type": "select",
    "options": [
      { "value": "_latest_update",  "label": "الاحدث" },
      { "value": "_wp_manga_views", "label": "شائع" }
    ],
    "request": { "target": "form", "param": "vars[meta_key]" } },
  { "id": "genres", "label": "تصنيفات", "type": "multiselect",
    "options": [ { "value": "fantasy" }, ... ],
    "request": { "target": "form", "param": "vars[wp-manga-genre]", "encode": "csv" } }
]
```

(Option list authored from `MangaLekRepositoryv2.kt` lines 412-611, the read-only spec.
Note the legacy quirk: 3 of 4 sort labels map to the same `_wp_manga_views` meta_key —
the JSON will collapse them to the distinct backend values to satisfy the
duplicate-option-value rule, preserving observable behavior.)

**Order of operations**: (1) `LekmangaFilterParityTest` lands FIRST, pinning the expected
form-body pairs derived from the legacy `sortFormBody`/`genresSearchFormBody` builders
(executed against the real legacy repo with a recording fake if its constructor allows;
otherwise pinned literals cited to file:line) vs the generic engine's recorded
`SourceRequest`; (2) only then the stanza gains `filters` (+ document revision bump).
Rollback = delete the `filters` block (validation-clean; plain search untouched). No other
source is migrated in this campaign. SwatManga/Zazamanga stanzas are follow-up authoring,
deliberately out of scope.

---

## 7. Compatibility, versioning, lifecycle rules

- **schemaVersion stays 1**: `filters` is additive-optional and the parser ignores
  unknown keys. Versioning rule going forward: additive optional fields → no bump;
  changing the meaning/shape of an existing field → bump `SUPPORTED_SCHEMA_VERSION`
  (which all-or-nothing-rejects older documents, the intended fail-closed behavior).
- A stanza **without `filters`** = no advanced filters: empty sheet state, plain search
  works exactly as today (test-pinned).
- A generic source **never** falls back to legacy filter code — incomplete filter JSON is
  a validation failure or an explicitly absent capability, by construction of the routing
  in §5.
- **Renaming labels** (filter `label`, option `label`) is always safe — behavior binds to
  `id`/`option.value` only. **Renaming an `id` or `option.value`** is a retirement + new
  filter (in-flight UI state for the old id is pruned harmlessly on next load).
- **Retiring a filter/option**: delete it from the JSON; the pruning step drops any state
  referencing it; nothing else persists filter state in v1.
- Legacy sources keep `MangaSource` + `sortTypes`/`allGenres` untouched; their filters
  render through the same UI via the `:data` adapter.

---

## 8. Tests (mapped to the 18 required + parity)

Engine (`:sources:engine` commonTest — `FilterRequestComposerTest`, `GenericSourceClientFilterTest` w/ recording fakes; validator — `DefaultSourceConfigValidatorFilterTest`):
(2) exact request from selections (URL + form + body recorded); (3) multi-select
encodings; (4) `repeat` (`genre%5B%5D=a&genre%5B%5D=b`); (5) `csv`; (6) `json-array` body;
(7) toggle true/false values + select value mapping (enum); (8) defaults applied when no
selection; (9) empty optional omitted; (10) required enforced (validator: required needs
default; engine: fail-closed `Validation.Required`); (11) invalid defaults rejected;
(12) duplicate ids rejected; (13) unknown `visibleWhen` refs rejected; (14) dependency
cycles rejected; plus target/encoding whitelists, include/exclude combos, standard-id
type checks, unknown-selection-id ignored, conflicting-values determinism.

`:data` (`ConfigOnlySourceRoutingTest` extension + new `GenericFilterRoutingTest`):
(16) no-filter config source still plain-searches; filtered search on a config-backed api
NEVER reaches the legacy repo (repos=emptySet proves it); legacy translation parity
(selections → `SearchType`); `FallbackSourceClient` no-fallback-with-filters.

`:presentation` (`SearchViewModelFilterTest`): (8) reset behavior; (15) switching sources
does not leak selections (close→open cycle + pruning); selection state through
recomposition (state-driven by construction, asserted via state round-trip); unknown
saved ids dropped.

`:composeApp` commonTest: (1) `JsonOnlySourceAdditionTest` extension — synthetic stanza
with genres/sort/status/language/type filters validates alongside the real document and
its descriptor exposes them ordered, with **no** `MangaSource` entry; (17) a synthetic
custom filter (`min_rating` number + `demographic` select) flows selections → exact
request without any source-specific Kotlin.

Guard (18): extend `GenericSourcesDecouplingGuardTest` — the existing enum/allow-list scan
already covers all filter code locations; add a `when (api`/`when(api` needle scoped to
`sources/contracts`, `sources/engine`, `sources/config` production trees (the generic
pipeline must stay api-agnostic; broader modules legitimately branch on api for legacy
routing).

Parity: `LekmangaFilterParityTest` (§6) — legacy vs JSON-driven request equivalence,
landed BEFORE the stanza migration.

---

## 9. Phased delivery (small reviewable commits, each gate-green)

| # | Commit scope | Key gates |
|---|---|---|
| P0 | this plan document | — |
| P1 | contracts schema (`FilterDefinition` et al.) + domain runtime model (`SourceFilter`/`FilterSelections`) + `RuntimeSourceDescriptor.filters` + full validator rule set + validator tests | 3-target compile, `:sources:engine:desktopTest` |
| P2 | engine `FilterRequestComposer` + `MangaSourceClient.search(query, page, filters)` + multi-value `SourceRequest.formBody` + `KtorHttpExecutor` + all implementor/fake updates + engine filter tests | compile, engine+composeApp desktopTest |
| P3 | `:data`: unified `loadFilters` descriptors (generic + legacy adapter), `searchSource(query, selections)` routing, `SearchFilters`/`SearchMode` removal, use-case updates, fallback guard + data tests | compile, `:data:desktopTest` |
| P4 | `:presentation`: SearchState/Intent/VM generic selections, defaults/reset/pruning + VM tests | `:presentation:desktopTest` |
| P5 | `:ui`: generic `SearchFilterSheet` renderer + `strings_pfix_filters.xml` ×11 locales | compile, `:ui:checkLocaleKeyParity` |
| P6 | synthetic JSON-only filter source test + custom-filter test + guard extension | `:composeApp:desktopTest` |
| P7 | `LekmangaFilterParityTest` (pre-migration) → Lekmanga stanza `filters` + revision bump | composeApp desktopTest |
| P8 | docs (`ADDING_SOURCES.md` filter schema reference §10 topics, CLAUDE.md note) + full final gate run + report | full gate set |

Final gate set: `:composeApp:compileAndroidMain :composeApp:compileKotlinDesktop
:composeApp:compileKotlinIosSimulatorArm64 --offline`; desktopTest for `:composeApp`,
`:data`, `:presentation`, `:ui`, `:domain`, `:sources:engine`, `:sources:config`,
`:sources:legacy`; `:ui:checkLocaleKeyParity`; `:app:testDebugUnitTest` (Koin graph);
`:app:assembleDebug`. Commits authored as `Apdelrahman1911`; nothing pushed.

## 10. Deliberate limitations (v1)

- `range`/`date` control types and non-`search` `appliesTo` verbs: reserved vocabulary,
  validator-rejected until a consumer exists.
- `searchAllRepos` (multi-source fan-out) stays plain-text.
- Filter selections are session-scoped (never persisted) — matches today's behavior; the
  pruning rule already covers a future persistence layer.
- Include/exclude UI is two filter sections (no tri-state chips).
- Only Lekmanga is migrated; SwatManga/Zazamanga filter stanzas are follow-up authoring.
- Legacy-source filters keep single-select genres (today's UI behavior) via the adapter.
