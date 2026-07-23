# Source configs — the complete developer guide

> Adding, editing, testing, disabling, and removing manga sources in Kira Manga.
> Written for a developer who has never touched the source system. Last verified against the code
> 2026-07-09 (source-lifecycle hardening batch). Companion deep-dive:
> [`../ENGINEERING_NOTES.md`](../ENGINEERING_NOTES.md) §1; working rules: [`../../CLAUDE.md`](../../CLAUDE.md).

---

## 1. Where source configs live (and where they don't)

**Sources have an authoritative signed catalog and a bundled recovery floor.**

- `CONFIG_BACKED_SOURCES_JSON` in
  `composeApp/src/commonMain/kotlin/me/manga/kira/sources/runtime/BundledSourcesConfig.kt` is the
  revision-6 binary fallback. It contains exactly the 12 approved generic sources and no legacy
  stanza.
- The backend publishes a lightweight signed manifest and immutable signed per-source revisions.
  `IncrementalSourceCatalogManager` checks the manifest ETag, reuses verified local revisions, and
  downloads only missing active revisions.
- The accepted signed manifest is the runtime authority. `DefaultSourceRegistry` creates a client
  only for an active `engine:"generic"` entry. There is no compiled roster, legacy adapter,
  inference path, or per-verb fallback.
- Room re-verifies the last-known-good catalog after restart. A new revision becomes visible only
  when every required artifact and the source projection commit atomically; otherwise the complete
  prior cache or bundle stays active.

**When is the catalog loaded?** `IncrementalSourceCatalogManager` validates the bundle during
construction. `App.kt` calls `refresh()` at startup, then projects the resulting active catalog.
Unchanged manifests return 304 and download no source payload. The in-memory document changes only
after complete verification and durable activation.

## 2. How to add a new source

Checklist (details below):

1. Choose a **stable api string** — this is the source's permanent identity (see §3). Not a
   display name; never reused; never renamed.
2. Author and validate an `engine:"generic"` stanza, then publish it through the backend lifecycle.
   The signed manifest entry is the whole runtime registration — no allow-list, enum entry, or
   Kotlin wiring. Add the stanza to `CONFIG_BACKED_SOURCES_JSON` only when deliberately updating
   the next binary's outage floor.
3. Optionally give it an icon (see "Icons" below): `"icon": { "resourceKey": "x" }` for a
   packaged drawable (one generic entry in `SourceIconRegistry` maps the key), or
   `"icon": { "remoteUrl": "https://…" }` for a config-delivered icon (no Kotlin at all), or
   omit the block for the deterministic initials avatar.
4. Set `"enabled": true` if the source should be default-enabled when first seeded, and
   `"lifecycle": "active"` (the default). **Note the current reality: seeding honors
   `isEnabled = enabled && lifecycle == "active"`, and all shipped stanzas leave `enabled` unset
   (= `false`) — sources start disabled and users turn them on in the Sources screen
   (onboarding / Settings → Sources).** A source that never gets enabled never appears in Home.
5. Define `baseUrl` (must start with `http`) — the generic engine's API/site base. `imageBase` if
   covers/pages live on a different host.
6. Define the endpoints. **Required: `home` or `featured`, plus `search`, `details`, `pages`.**
   `chapters` is optional (separate chapter-list request). There is NO legacy fallback — a missing
   endpoint is a user-visible failure on every use of that verb (§4). The
   `ConfigBackedSourceCompletenessTest` build gate enforces all four.
7. Headers: static headers go in `"headers": { ... }`; `"usesCapturedHeaders": true` (the default)
   additionally merges headers captured by the in-app WebView Cloudflare solver (`cf_clearance`,
   cookies, User-Agent), stored per-api in DataStore. Set it `false` for clean JSON APIs (see
   Azora).
8. `previousHosts` / `previousImageHosts`: leave empty for a new source. They exist for **domain
   moves** (§7) — append the OLD host when `baseUrl` changes so stored library/chapter/history
   URLs are rewritten and user-configured mirrors are protected.
9. **No enum or legacy entry:** a generic stanza needs no `MangaSource` entry. Archived scrapers are
   migration references only and must not be added to the runtime graph.
10. Add a `<Source>PilotParityTest.kt` in `:composeApp` commonTest with real captured HTML/JSON
    fixtures asserting each verb's parsed output (see any existing `*PilotParityTest.kt`).
11. If the site needs an engine capability that doesn't exist yet (new transform, new pagination
    type, new date strategy): implement it in `:sources:engine`, whitelist the name in
    `DefaultStrategyRegistry`, and add a golden test in `:sources:engine`. Configs may only
    reference names compiled into that registry — the validator rejects everything else
    (fail-closed; do not weaken).

Derive selectors/paths from the legacy parser in `sources_repositry/` when converting (that tree
is the read-only spec).

## 3. Source api rules — the permanent identity

The `api` string is the **primary key of the entire system**. It is:

- the stanza key in the config document (`engine:"generic"` IS the registration);
- the `sources` table primary key (`SourcesEntity.name`);
- the `saved_manga.api` / `history_items.api` / `chapter_downloads.api` column value on every row
  a user ever created from this source (plain strings — **no foreign key, no cascade**);
- the DataStore key for captured Cloudflare headers;
- the backup format's source reference (`BackupManga.api`).

Rules:

- **Once shipped, NEVER rename an api.** There is no migration mechanism. After a rename, every
  existing library row, history row, and captured-header record still carries the old string —
  they become permanent dead references: Details refresh fails with "Unknown source api=…",
  non-downloaded chapters can't be read online, and nothing ever reconciles them. (Downloaded
  chapters, read state, and covers survive — they're keyed by DB ids — but the source link is
  gone forever.)
- **Don't use a display name as the api.** `displayName` is the UI label and may change freely;
  the api may not. (Historical warts like `"Team X"`/`"Mangamello Plus"` — apis with spaces —
  are grandfathered; don't add new ones.)
- Never reuse a retired api for a different site: old library rows would suddenly point at the
  new site's URLs.

## 4. Required endpoint behavior — there is NO legacy fallback

`DefaultSourceRegistry.get()` returns the **bare** `GenericSourceClient` for an active,
config-backed api. The fallback and legacy client classes are deleted. Consequences:

- A **missing endpoint** returns `AppError.Validation.Required("endpoint:<verb>")` to the user on
  every use of that verb. It does not fall back to the legacy scraper. (Older docs claiming
  "an omitted verb transparently falls back to legacy" are obsolete.)
- A **wrong-but-Success** verb (e.g. empty chapters because a selector went stale) is a visible
  regression — nothing catches it at runtime. This is why every verb needs a parity test with
  real fixtures, and why the owner rule is *a source is 100% generic or fully legacy — no
  per-verb split*.
- Downloads: `RegistryChapterPageProvider` **throws `GenericPagesFailedException`** on a generic
  pages failure (recorded as a failed download); it returns `null` only for non-config-backed
  apis (which then use the legacy download path).

What each endpoint must produce (verb → domain result, via the `fields` mappings):

| Endpoint | Feeds | Minimum output |
|---|---|---|
| `home` | Home feed (paginated) | list of items: `item.title`, `item.url`, `item.cover`; optional rating/genres/recentChapters |
| `featured` | Home "featured" carousel | list of items (cover + title is enough) |
| `search` | Search (this source; also the all-sources fan-out) | same item shape as `home`; use `{queryEncoded}` (raw `{query}` is rejected by the validator) |
| `details` | Manga details screen + library refresh | `detail.title`, `detail.cover`, `detail.description`, `detail.status`, `detail.genres`, and chapters (`detail.chapters` inline, or a separate `chapters` endpoint) with `chapter.name`/`chapter.number`/`chapter.url`/`chapter.date` |
| `pages` | Reader + downloads | ordered image URLs (`page.image`, optional `page.order`) |
| `chapters` (optional) | Two-request details | replaces the inline chapter list; if this second request fails, the whole `details` fails (deliberate — a Success with silently-empty chapters is the forbidden failure mode) |

## 5. Validation rules — and why one bad stanza is catastrophic

`DefaultSourceConfigValidator` (`:sources:engine`) checks, per document:

- `schemaVersion == 1` (anything else rejects the document immediately);
- per stanza: non-blank **unique** `api`; non-blank `language`; `baseUrl` starting with `http`;
  compatibility parsing recognizes `engine` ∈ {`generic`, `legacy`, `kotlin:*`}, while bundled and
  signed v2 catalogs accept only `generic`; `siteState` ∈
  {`WORKING`, `UNDER_MAINTENANCE`, `STOPPED`, `ADULT_18_PLUS`}; source-payload `lifecycle` ∈
  {`active`, `disabled`, `removed`} and manifest lifecycle ∈ {`active`, `disabled`, `retired`};
  `previousHosts`/`previousImageHosts`/`trustedHosts` entries are **bare hosts** (no scheme, no
  path, no port);
- generic stanzas additionally: known `pagination.type` (currently only `page-number`); at least a
  `home` or `featured` endpoint; endpoint `url` non-blank, method/format whitelisted (`json`,
  `html`, `script-json`), no raw `{query}`; every transform `fn`, `dateStrategy`, and
  `imageStrategy` name must be compiled into `DefaultStrategyRegistry` (the image set is
  intentionally EMPTY — any `imageStrategy` reference is rejected).

**Acceptance is all-or-nothing.** The incremental manager rejects the candidate manifest if any
manifest, source, signature, schema, identity, lifecycle, strategy, explicit-tombstone, per-source
anti-rollback, or full-document check fails.
It never exposes a partial candidate; the complete last-known-good catalog or bundle remains active.
Unknown JSON fields are ignored for compatibility, so typo'd field names still require parity tests.
Two safety nets exist:

- **Build time**: `BundledSourceCatalogPolicyTest`, `ConfigBackedSourceCompletenessTest`,
  `IncrementalSourceCatalogManagerTest`, `KtorRemoteSourceCatalogTest`, and
  `RoomSourceCatalogStoreTest` pin the exact-12 bundle, required endpoints, signatures, delta
  behavior, lifecycle removal, immutable revisions, and atomic activation.
- **Runtime**: the manager's `onDocumentRejected` hook logs every rejection with per-stanza
  reasons (tag `SourceConfig`), and `App.kt` logs a startup alarm if the active document contains
  zero generic stanzas.

## 6. How to test a new source

```bash
# 1. The config gates (parse + validate + registry reachability + endpoint completeness):
./gradlew :composeApp:desktopTest \
  --tests "me.manga.kira.sources.runtime.ConfigBackedSourceCompletenessTest" \
  --tests "me.manga.kira.sources.runtime.BundledSourceCatalogPolicyTest" --offline

# 2. Your parity test + everything else in the sources runtime:
./gradlew :composeApp:desktopTest --offline

# 3. If you added an engine capability:
./gradlew :sources:engine:desktopTest --offline

# 4. The standard compile gate before committing:
./gradlew :composeApp:compileAndroidMain :composeApp:compileKotlinDesktop :composeApp:compileKotlinIosSimulatorArm64 --offline
```

Manual, on a device/emulator (`SourcesScreen` → enable the new source):

- **Home**: tab appears, feed loads, pagination (scroll past page 1), featured row.
- **Search**: this source alone + the all-sources fan-out (fan-out only covers config-backed
  sources).
- **Details**: cover, description, status, genres, full chapter list, chapter dates.
- **Reader**: open a chapter online; page order; page count.
- **Downloads**: download a chapter, then read it offline (airplane mode) — verifies the
  `pages` headers are right and the CBZ pipeline works.
- **Fresh install AND upgrade**: run once on a wiped app (first seed path — is the source visible
  after enabling?) and once over an existing install (catalog-sync-asserts-truth path).
- **If you changed a domain** (`baseUrl` + `previousHosts`): verify a previously-saved manga from
  that source still opens and its cover still loads after the upgrade (the alias sweep rewrote
  stored URLs).
- **If the source is Cloudflare-protected**: first load will 403 → the WebView solver should
  open; after solving, retry succeeds (captured headers now in DataStore).

## 7. How to edit an existing source safely

**Safe (routine) changes** — take effect on the next release, no data impact:

- selectors / JSON paths / transforms in `fields`;
- endpoint URL templates, filters, pagination params;
- `displayName`, `siteState` (UI badge), `priority`;
- **`baseUrl` host move — WITH the ritual**: set the new `baseUrl`, and **append the old host to
  `previousHosts`** (and old image host to `previousImageHosts` if `imageBase` moved).
  `previousHosts` is append-only history. On next launch the catalog sync (a) asserts the new
  baseUrl into the `sources` row and bumps `baseVersion`, (b) rewrites the host of every stored
  `saved_manga.url`, chapter url, history url, and notification url from the old host(s) to the
  new one (`SourceUrlMigrator` — host swap only, paths preserved), and (c) because the old host
  is *declared*, a user's hand-configured mirror host (one NOT in the declared set) is left
  alone. Without the `previousHosts` entry you still get (a)+(b) for the current row host, but
  mirror protection is off and older stored hosts are never swept.

**Risky changes — think twice, then follow the rules:**

- **Changing the api**: never (§3).
- **Removing an endpoint**: instant user-visible breakage of that verb (§4); the completeness
  test will fail the build for the four required ones — that's by design.
- **Changing lifecycle**: use the backend state machine (§8). A signed manifest with
  `disabled`, `retired`, or a `removed` tombstone removes the api from the active projection.
- **Deleting a source without a lifecycle publication**: do not. The backend must publish the
  explicit transition/tombstone so clients cannot confuse omission with an incomplete catalog.
- **Editing a generic stanza's selectors without re-running the parity test**: a stale selector
  that still returns 200-with-empty is the forbidden wrong-but-Success mode.

## 8. How to retire a source

The backend policy is explicit and ordered:

1. Publish `active → disabled`.
2. After review/grace, publish `disabled → retired`.
3. Publish `retired → removed` with the required confirmation; v2 retains an identity tombstone.
4. Never rename or reuse the api (§3).

Each accepted state removes a non-active source from the runtime projection. Re-enabling a reviewed
generic source requires an explicit backend transition and a completely verified manifest; absence
never activates a bundled or legacy implementation.

What survives, forever, in either state (verified in code — there is no orphan cleanup, by
design):

| Data | Fate when a source is disabled/removed |
|---|---|
| Library entries (`saved_manga`) | Kept and listed; open from cache (Details renders from Room, zero network); deletable; only a manual refresh errors ("Unknown source api=…") |
| Downloaded chapters | Fully readable offline forever — files are keyed by DB `mangaId`/`chapterId`, the reader's offline path never consults the source |
| Read state / bookmarks / resume page | Untouched (`saved_chapters` flags + settings-store resume keyed by chapter URL) |
| History | Rows kept; downloaded chapters open; non-downloaded chapters error typed |
| Backups | Export/import never touch the sources table; a backup of a dead-api manga imports fine as an orphan row (usable offline, refreshable never) |
| Captured headers (DataStore) | Orphaned but harmless; no GC |
| Library refresh | Skips the manga (strict lookup — dead apis count as failed, and can never blank a stored cover: `LibraryRepositoryCoverGuardTest`) |

## 9. Troubleshooting

| Symptom | Likely cause → what to check |
|---|---|
| **Source doesn't appear in the UI** | (a) not enabled — sources seed disabled by default; enable it in the Sources screen, or ship `"enabled": true` for the first-seed default; (b) stanza engine isn't `generic` (the stanza IS the registration since the 2026-07 decoupling); (c) the WHOLE document got rejected — see the last row |
| **Tab shows but Home/featured fails** | wrong `home`/`featured` endpoint url/format/root; check Kermit + the parity test fixtures; Cloudflare → see below |
| **Search fails or returns nothing** | missing `search` endpoint (`Validation.Required("endpoint:search")` — no fallback!); raw `{query}` instead of `{queryEncoded}`; site expects POST (set `method`) |
| **Details fail** | `details` endpoint or `detail.*` fields wrong; if a separate `chapters` endpoint exists, ITS failure fails all of details (deliberate) |
| **Pages/download fail** | `pages` endpoint / `page.image` path stale; downloads for config-backed sources throw `GenericPagesFailedException` (failed-chapter row) — check the recorded error message; wrong/missing per-page headers → 403 on the CDN |
| **Cloudflare / 403s** | the engine detects the challenge and signals the WebView solver; the solved `cf_clearance` is stored per-api and merged only when `usesCapturedHeaders: true` (check it isn't `false`); the cookie is per-host — a domain move invalidates it until re-solved |
| **Validates locally but returns empty results** | wrong-but-Success: selectors return no nodes / JSON `root` path misses. Validation can't catch this — only a parity test with real captured fixtures can. Re-capture fixtures; the site probably changed markup |
| **"Unknown source api=…" errors** | a library/history row references an api that no longer resolves (renamed api, or a source removed while its enum entry was also dropped). The data is orphaned — this is exactly why apis are immutable (§3) |
| **Blank home / every generic source gone** | the bundled document was rejected wholesale (one bad stanza!). Logcat tag `SourceConfig` has the per-stanza reasons; `ConfigBackedSourceCompletenessTest` reproduces it at build time. Home now shows an error pane instead of silent blank |

## 10. Examples

**Minimal valid generic source** (JSON API site; every required endpoint; default-enabled):

```jsonc
{
  "api": "Example",                    // permanent identity — never rename (§3)
  "language": "(EN)",
  "displayName": "Example Manga",      // UI label — free to change
  "baseUrl": "https://api.example.com",
  "imageBase": "https://cdn.example.com",
  "engine": "generic",
  "enabled": true,                     // default-enabled on first seed (omit = starts disabled)
  "usesCapturedHeaders": false,        // clean API, no Cloudflare captures needed
  "pagination": { "type": "page-number", "param": "page", "start": 1 },
  "endpoints": {
    "home":     { "url": "{baseUrl}/api/latest?page={page}",  "format": "json", "root": "items" },
    "featured": { "url": "{baseUrl}/api/popular?page={page}", "format": "json", "root": "items" },
    "search":   { "url": "{baseUrl}/api/search?q={queryEncoded}", "format": "json", "root": "items" },
    "details":  { "url": "{itemUrl}",    "format": "json" },
    "pages":    { "url": "{chapterUrl}", "format": "json", "root": "pages" }
  },
  "fields": {
    "item.title":   { "path": "title" },
    "item.url":     { "template": "{baseUrl}/api/manga/{id}", "vars": { "id": "id" } },
    "item.cover":   { "path": "cover" },
    "detail.title":       { "path": "title" },
    "detail.cover":       { "path": "cover" },
    "detail.description": { "path": "summary", "transform": [ { "fn": "clean-html" } ] },
    "detail.status":      { "path": "status", "transform": [ { "fn": "default", "args": { "value": "Unknown" } } ] },
    "detail.genres":      { "listPath": "genres[*].name" },
    "detail.chapters":    { "listPath": "chapters" },
    "chapter.name":   { "path": "name" },
    "chapter.number": { "path": "number", "transform": [ { "fn": "format-number" }, { "fn": "prepend", "args": { "value": "Chapter " } } ] },
    "chapter.url":    { "template": "{baseUrl}/api/chapter/{id}", "vars": { "id": "id" } },
    "chapter.date":   { "path": "createdAt", "dateStrategy": "iso" },
    "page.image": { "path": "url" },
    "page.order": { "path": "order" }
  }
}
```

…plus an `ExamplePilotParityTest`. Nothing else: no allow-list entry, no `MangaSource` enum
entry, no Kotlin wiring (the 2026-07 decoupling invariant). Optional icon flows: packaged —
drop the drawable in `composeResources/drawable/`, add one `SourceIconRegistry` entry, reference
its key from `"icon": { "resourceKey": … }`; remote — `"icon": { "remoteUrl": "https://…" }`
(validated https-only) with the deterministic initials avatar as the loading/error fallback; none —
omit the block. The real 12 stanzas in `BundledSourcesConfig.kt` are the best reference — Azora is
the cleanest JSON-API example, the Madara-family sources the HTML ones.

**Source after a domain move** (`previousHosts` carries the history, append-only):

```jsonc
{
  "api": "Example",
  "baseUrl": "https://api.example-new.com",       // ← the move
  "previousHosts": ["api.example.com"],            // ← old host, appended (bare host — no scheme!)
  "imageBase": "https://cdn.example-new.com",
  "previousImageHosts": ["cdn.example.com"],
  // …rest unchanged
}
```

**Retirement:** do not turn a source into a legacy JSON tombstone. Use the authenticated backend
lifecycle endpoints in order: `/disable`, `/retire`, then `/remove` with exact api confirmation.
The signed v2 manifest carries disabled/retired state and the final identity-only tombstone.

**What NOT to do — the api rename.** This is the one change with permanent, invisible damage:

```jsonc
// ❌ NEVER: "renaming" a source
// before:  { "api": "Example",       ... }
// after:   { "api": "Example Manga", ... }   // ← this is a NEW source, not a rename
```

Every library entry, history row, backup, and captured header still says `"Example"`. They now
point at nothing: refresh fails, online reading fails, and no code will ever fix them up. The
old api remains unresolved. If the display name must change, change `displayName`.
If the site truly relaunched as something else, retire the old api through the backend lifecycle and
publish the new site as a genuinely new source — accepting that users re-add their
library entries (or migrate via backup export/import, which keeps working offline either way).

---


## 11. Filters — fully config-driven (2026-07)

A generic source declares its complete advanced-filter surface in its stanza: the UI renders from
it, the request mapping executes from it, and the validator gates it. Adding or changing a
source's filters is a JSON edit + release — never a `MangaSource` entry, a compiled roster, a
source-specific Kotlin filter class, or a `when(api)` branch. Design record:
`docs/sources/CONFIG_DRIVEN_FILTERS_PLAN.md`; pilot: Lekmanga (`LekmangaFilterParityTest`).

### 11.1 Schema reference

`"filters"` is an ORDERED array on the source stanza — declaration order is both the sheet's
render order and the request-composition order. A stanza without `filters` simply has no advanced
filters (plain search unaffected).

Each filter:

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | Stable identity, `[a-z0-9_]{1,64}`. Behavior binds to it; renaming = retire + re-add. |
| `label` | yes | Display label (non-blank). Standard ids get localized section titles; custom ids show this verbatim. |
| `type` | yes | `select` \| `multiselect` \| `toggle` \| `text` \| `number` (`range`/`date` reserved — rejected until implemented). |
| `options` | select/multiselect | `{ "value", "label"? }` — `value` is the stable backend string that reaches the request; `label` (default = value) is display-only. Forbidden on toggle/text/number. |
| `default` | no | Scalar default: a declared option value / toggle `"true"`\|`"false"` / a text or number literal. |
| `defaults` | multiselect only | Default option values. Mutually exclusive with `default`. |
| `required` | no | Must always reach the request → validator demands a usable default. |
| `request` | yes | The mapping block (below). |
| `visibleWhen` | no | `[{ "filter": "<id>", "anyOf": ["v", …] }]` — ALL must hold; a hidden filter is neither rendered nor sent. Values are LOGICAL (`true`/`false` for toggles, option values otherwise). Cycles are validation errors. |
| `excludeOf` | multiselect only | Marks this filter as the exclusion counterpart of filter `<id>` (include/exclude pairs). A value selected on both sides is dropped from the exclude side. |
| `appliesTo` | no | Endpoint verbs, default `["search"]` — the only supported verb today. |

`request` block:

| Field | Meaning |
|---|---|
| `target` | `query` (URL parameter, appended after template expansion, percent-encoded) \| `form` (post-form entry, appended after the static `formBody`) \| `header` \| `path` (fills a `{param}` hole in the endpoint url — needs a guaranteed non-empty default) \| `body-json` (fills a `{param}` hole in `jsonBody`) |
| `param` | query/form/header: the parameter name (`"genre[]"` is fine — it percent-encodes on the wire). path/body-json: the template placeholder name (`[a-zA-Z0-9_]+`, must not shadow a reserved engine var). |
| `encode` | `single` (first value) \| `csv` (join with `delimiter`, default `,`) \| `repeat` (one `param=value` per value; query/form only) \| `json-array` (JSON array literal; body-json only). `csv`/`repeat`/`json-array` require `multiselect`. |
| `omitIfEmpty` | default `true`: an empty effective value drops the parameter entirely (query/form/header). `false` sends an empty-valued parameter. Placeholder targets always expand (`[]`/`""`). |
| `trueValue` / `falseValue` | toggle wire values (defaults `"true"` / `""` — empty + omit = the parameter vanishes when off). |

Deterministic composition rules (all pinned in `FilterRequestComposerTest`): defaults apply when
nothing is selected; unknown selection ids and unknown option values are DROPPED (stale UI state
can never inject parameters); select conflicts resolve to the first value by option order;
multiselect values re-order to option-declaration order; a required filter resolving empty fails
closed (`Validation.Required("filter:<id>")`) without issuing a request.

### 11.2 Standard filter conventions

`genres`, `sort`, `status`, `language`, `type` are CONVENTIONS on `id`, not special code paths:
the validator pins their control types (`sort` = select; the rest = select or multiselect), and
the sheet gives them localized section titles (`strings_pfix_filters.xml` + the existing search
keys) — request behavior is 100% the generic pipeline. One source's `genre=action,drama` vs
another's `genre[]=action&genre[]=drama` vs a JSON body array is purely a different `request`
block on the same standard filter.

### 11.3 Custom filters

Any other id is a custom filter and flows through the exact same parse → validate → render →
state → compose pipeline. Example (a minimum-rating number + an adult toggle gating a demographic
select):

```jsonc
{ "id": "min_rating", "label": "Minimum rating", "type": "number",
  "request": { "target": "query", "param": "min_rating" } },
{ "id": "adult", "label": "Adult content", "type": "toggle", "default": "false",
  "request": { "target": "query", "param": "adult", "trueValue": "1", "falseValue": "0" } },
{ "id": "demographic", "label": "Demographic", "type": "select",
  "options": [ { "value": "seinen" }, { "value": "josei" } ],
  "visibleWhen": [ { "filter": "adult", "anyOf": ["true"] } ],
  "request": { "target": "query", "param": "demo" } }
```

### 11.4 Validation (all-or-nothing, like everything else)

`DefaultSourceConfigValidator.validateSearchFilters` rejects — with a
`source '<api>': filters: filter '<id>': <field>: <message>` path — duplicate ids, blank
ids/labels/option values, duplicate option values, unknown types/targets/encodings,
type-incompatible encodings, invalid defaults (not in options / non-numeric / bad toggle),
`defaults`-vs-`default` misuse, required-without-default, unknown `visibleWhen`/`excludeOf`
references, self-references, impossible `anyOf` values, dependency cycles, chained/overlapping
exclude pairs, filters mapped to missing or unsupported endpoints, `form`/`body-json` targets on
the wrong endpoint method, placeholders missing from templates or shadowing reserved vars, param
collisions with static formBody keys or hardcoded url query keys, standard-id type mismatches, and
`filters` on a non-generic engine. Test spec: `DefaultSourceConfigValidatorFilterTest`.

### 11.5 Evolving a source's filters safely

- **Add an option**: append `{ "value", "label" }` — additive, nothing else changes.
- **Rename a label** (filter `label` or option `label`): always safe — behavior binds only to
  `id`/`option.value`. NEVER rename a shipped `id` or `option.value` in place; that is a retire +
  re-add (in-flight UI state for the old identity is pruned harmlessly on the next filter load).
- **Retire a filter or option**: delete it from the JSON. The reconciliation step drops any held
  selection referencing it; nothing persists filter state today, so there is no migration.
- **Filter state scoping**: selections live in Search MVI state only — cleared when the overlay
  closes, reconciled against the freshly loaded filter list on every open (unknown ids/values
  dropped, defaults seeded). Switching sources can never leak selections
  (`SearchViewModelFilterTest`).
- Legacy (non-generic) sources keep their compiled `sortTypes`/`allGenres`: `:data` adapts them
  into the same `SourceFilter` shape (genres, then sort — `toSourceFilters()`) and translates
  selections back onto the legacy `SearchType` (`legacySearchTypeOf`, sort > genres, CSV). A
  GENERIC source never falls back to legacy filter code — its stanza is the only filter authority.

### 11.6 Complete example

```jsonc
"filters": [
  { "id": "genres", "label": "Genres", "type": "multiselect",
    "options": [ { "value": "action" }, { "value": "drama" }, { "value": "isekai" } ],
    "request": { "target": "query", "param": "genre[]", "encode": "repeat" } },
  { "id": "sort", "label": "Sort", "type": "select", "default": "latest",
    "options": [ { "value": "latest", "label": "Latest" }, { "value": "views", "label": "Most viewed" } ],
    "request": { "target": "query", "param": "orderby" } },
  { "id": "status", "label": "Status", "type": "select",
    "options": [ { "value": "ongoing" }, { "value": "completed" } ],
    "request": { "target": "query", "param": "status" } },
  { "id": "language", "label": "Language", "type": "select",
    "options": [ { "value": "en" }, { "value": "ar" } ],
    "request": { "target": "query", "param": "lang" } },
  { "id": "type", "label": "Type", "type": "multiselect",
    "options": [ { "value": "manga" }, { "value": "manhwa" } ],
    "request": { "target": "query", "param": "type", "encode": "csv" } },
  { "id": "completed_only", "label": "Completed only", "type": "toggle",
    "request": { "target": "query", "param": "completed", "trueValue": "yes" } }
]
```

For a POST-form (madara) source, see the shipped Lekmanga stanza: `form` targets appending after
the static `formBody` (`vars[wp-manga-genre]` CSV + `vars[meta_key]` mapped sort values).

**Versioning**: `filters` is additive-optional — `schemaVersion` stays 1 and pre-filter parsers
ignore the key. Bump `SUPPORTED_SCHEMA_VERSION` only for changes to the MEANING of existing
fields.

> **Invariant**: a generic source's discovery, icon, endpoints, filters, filter UI, and request
> mapping are fully defined by validated configuration. Adding or changing its filters must not
> require editing `MangaSource`, a compiled source roster, a source-specific Kotlin filter class,
> or a `when(api)` branch. (Build gate: `GenericSourcesDecouplingGuardTest`, incl. the
> `when(api)` scan over the generic pipeline modules.)

*Invariants recap: api strings are immutable once shipped · retirement is
`disabled → retired → removed`, never silent deletion · `previousHosts` is append-only · manifest
activation is all-or-nothing · generic sources have no legacy fallback · every required endpoint
must exist and be parity-tested.*
