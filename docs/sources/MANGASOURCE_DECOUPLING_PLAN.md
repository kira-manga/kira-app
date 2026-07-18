# MangaSource decoupling — design & implementation plan (2026-07-10)

**Goal:** config-backed generic sources are defined entirely by validated `SourceConfig` data.
Adding one must require **only a JSON stanza** (plus, optionally, one generic drawable-registry
entry for a packaged icon) — no `MangaSource` enum entry, no hardcoded list edit, no
source-specific `when`, no source-specific Kotlin wiring. `MangaSource` remains, temporarily, the
registry of **legacy** scrapers only.

This document is the audit record and the binding plan. Read together with
`docs/sources/ADDING_SOURCES.md` (authoring guide — updated at the end of this campaign).

> Historical note (2026-07-18): references below to remote delivery being disabled describe the
> state during this completed decoupling campaign. Signed remote delivery is now implemented with a
> pinned-key Ed25519 verifier; see `docs/release/BUNDLED_SOURCES_RELEASE.md` for the live posture.

---

## 1. Complete `MangaSource` dependency map

Direct code dependencies on the enum outside `sources/legacy/**/sources_repositry/` (grep-verified,
KDoc-only mentions excluded):

| # | Site | Usage | Classification |
|---|------|-------|----------------|
| D1 | `composeApp/.../presentation/common/componants/sources/RepoIconResolver.kt:44,122-170` | 46-arm `when (api) { MangaSource.X.API -> Res.drawable.* }` icon map | **Must remove** (bucket 4) |
| D2 | `composeApp/src/commonTest/.../LegacyStanzaCompletenessTest.kt:27,30,51,68` | ghost gate: every stanza api ∈ `MangaSource.entries` — **blocks config-only sources** | **Must rework** (bucket 12) |
| D3 | `data/download/src/androidMain/.../ChapterDownloadService.kt:32,75` | `api == MangaSource.PROCHAN.API` → streaming download fork | **Keep** — Prochan is permanently legacy; documented exception |
| D4 | `sources/legacy/**` (~50 scraper files + `MangaSource.kt` itself) | each legacy repo declares its enum constants | **Keep** — this *is* the legacy registry |

Indirect dependencies — production paths where a generic source requires **source-specific Kotlin**
(a `BaseMangaRepository` instance wired in `LegacySourcesModule.kt:141-196`) or a **hardcoded list**,
classified by the twelve responsibility buckets:

### B1 — Source discovery & registration
- `CONFIG_BACKED_APIS` (`composeApp/.../sources/runtime/BundledSourcesConfig.kt:852`) — hardcoded
  12-api set, double-gates `DefaultSourceRegistry.isConfigBacked` (`DefaultSourceRegistry.kt:53-54`).
  *Why:* Stage-0 safety allow-list. *Legacy-only?* No — it gates generic sources. *Replacement:*
  derive the generic set from the **validated active document** (`engine == "generic"` stanzas);
  delete the constant. *Adapter:* none. *Risk:* low — remote config is double-locked off
  (`remote = null` + `DenyRemoteSignatureVerifier`), so the document is exactly as trusted as the
  in-binary set it replaces; if Stage-2 remote delivery ever lands, reinstate an in-binary
  allow-list or rely on the signature gate (decision recorded below).
- `DefaultSourceRegistry(legacyRepos: Set<BaseMangaRepository>)` — the legacy half stays (it is the
  legacy registry); the generic half becomes purely document-driven.

### B2 — Source selection & onboarding
- Sources screen list = Room rows filtered `isConfigBacked` (`SourcesRepositoryImpl.kt:173-179`) —
  already api-string-driven; a config-only source **appears** once seeded. No enum dep. *Change:*
  row model gains displayName/icon (B4).
- Onboarding language seed (`EnableDefaultLanguageSourcesUseCase`) — api-string/Room-driven. OK.

### B3 — Enabled-source persistence
- Room `sources` table, PK `name` = api string; seeded by `SourceCatalogSyncRepositoryImpl.seedIfGeneric`
  (config path, works for config-only sources) and by legacy `saveSources()` (repoMap path, legacy rows
  only). No enum dep. **No change needed.**
- **ACTIVE source = persisted `Int` index** (`StorageKeys.ACTIVE_TAB`, `SourcesRepository.kt:138,256-275`)
  into `getEnabledRepos()` = enabled rows `.mapNotNull { repoMap[name] }` — **a config-only source can
  never be active** (dropped from the index space). *Replacement:* persist the active source as an
  **api string** (new key), one-time migration from the int index. *Risk:* index/api migration —
  mitigated below (§6).

### B4 — Display name, icon, language, metadata
- Display name: UI renders raw `api` (`SourcesScreen.kt:931`, `SourceTabsRow.kt:109`);
  `SourceConfig.displayName` exists with **zero consumers**. *Replacement:* descriptor-driven.
- Icon: D1 above; seam is `LocalSourceIconResolver: (api) -> DrawableResource?` (`SourcesScreen.kt:903`,
  provided at `App.kt:523`, consumed at `SourceTabsRow.kt:97` + `SourcesScreen.kt:968`). *Replacement:* §4.
- Brand color: `ui/.../library/LibrarySourceBrand.kt:18-61` — api-**literal** map (no enum import),
  sole caller `LibraryScreen.kt:1490`. Orphan-tolerant (`else -> black`). *Change:* `else` becomes the
  deterministic fallback color so new sources get a stable non-black badge; the named-brand map stays
  (it is :ui cosmetics for shipped brands, including retired-legacy library rows).
- Language: parenthesised tag on the Room row / config stanza; string-driven. OK.

### B5 — Home/Search/Details/Pages routing
- **Details / Pages / Downloads: already clean.** `MangaDetailsRepositoryImpl.kt:117-125`,
  `ChapterPagesRepositoryImpl.kt:132-147`, `RegistryChapterPageProvider` + `ChapterPageResolver` +
  all three download engines resolve by api string with typed failures; config-only apis work
  end-to-end (verified in the audit).
- **Home tabs BREAK:** `HomeFeedRepositoryImpl.observeSourceTabs` (:125-128) requires
  `getOrRepoByName(entity.name)` and builds the tab **from the legacy repo object**
  (`BaseMangaRepository.toSourceTab`, `HomeMappers.kt:104-113`) — config-only source silently dropped.
- **Active repo BREAKS:** `HomeFeedRepositoryImpl.activeRepo()` (:320-328) substitutes via
  `getOrRepoByName`; active machinery runs entirely in legacy-object space.
- **Search BREAKS:** `SearchRepositoryImpl` fan-out + active resolution over `getEnabledRepos()`
  (:109-121) — config-only source never searched.
- `loadFilters` (:299-310) reads `repo.sortTypes`/`repo.allGenres` off the legacy object; sort/genre
  search always drops to the legacy scraper (`SearchRepositoryImpl.kt:87-91`) even for pilots.
  *Decision:* filters remain a **legacy capability** — config schema has no sort/genre spec (Stage-2).
  Config-only sources get empty filters + NORMAL search (documented capability gap, not a blocker).
- Image auth headers: `rememberSourceImageRequest` (`SourceImageRequest.kt:88`) and
  `CoilSourceHeaderInterceptor` → `findRepoByHost` (`App.kt:311`, `SourcesRepository.kt:215-244`) —
  legacy-repo mediated; config-only source ⇒ header-less loads (403 on CF-gated sources). *Replacement:* §7 Phase 6.
- WebView Cloudflare solve: `WebViewViewModel.kt:58` `getRepoByName(api).refreshHeaders(headers)` —
  **no-op for config-only apis** (`EmptyMangaRepository.refreshHeaders`), so a solved challenge is
  never persisted. *Replacement:* write to the api-keyed header store directly.
- Adult gate: `AdultContentClassifierImpl.kt:40` `getOrRepoByName(api)?.blackListGenres` — silently
  disappears for config-only apis. *Replacement:* descriptor `blacklistGenres` first, legacy fallback.

### B6 — Library refresh & background workers
- `app/.../work/LibraryRefreshWorker.kt:227-254` — **100% legacy-scraper** (`fetchMangaChaptersF`).
  The 12 pilots refresh through their rotting legacy parsers; a config-only source never refreshes.
  *Replacement:* registry-first routing (generic `details()` for config-backed apis, legacy repo
  otherwise). *Risk:* behavior change for pilots (refresh switches parser) — aligns with the owner
  rule "a piloted source is 100% generic".

### B7 — Database models & stored identifiers
- All persistence keys on plain api strings (`sources.name` PK, `saved_manga.api`, `history.api`,
  `chapter_downloads.api`, DataStore header keys). **Enum names (`MANGA_LEK`-style) are never
  persisted.** No schema change, no data migration (except the active-tab pref, B3).

### B8 — Navigation & UI state
- Every route arg and MVI state field carries `api: String` (`Screen.kt:43-205`, Home/Search/Details/
  Reader/Updates/History effects). Zero typed enum args. **No change**, except `SourceTab` gains
  descriptor fields (additive — owner-WIP `HomeScreen.kt:754-756` constructs `SourceTab` in preview
  fixtures, so existing fields incl. vestigial `iconKey` must keep their signature/defaults).

### B9 — Backup/export/import
- `BackupManga.api` / `BackupHistoryItem.api` are opaque strings; import resolves by url then
  `(api,title)` (`BackupDao.kt:166`); nothing consults registry/enum/config. **No change.**

### B10 — Analytics, logging, diagnostics
- `AnalyticsRepositoryImpl` sends `manga_api` as a plain string; Kermit tags `SourceConfig` /
  `GenericSourceTest` interpolate api strings. **No enum dep, no change.**

### B11 — Legacy-source fallback
- `LegacyKotlinSourceClient` (built per legacy repo in `DefaultSourceRegistry.kt:36`),
  `FallbackSourceClient` + `SourceDebugFlags` (retained-but-unwired), `EmptyMangaRepository`
  null-object. *Change:* none to the legacy path itself; the rework removes the remaining
  **generic-path** trips into `EmptyMangaRepository` (B3/B5/B6 call sites get typed/config-first
  resolution). Legacy `repo is MangamelloPlusRepository` / `is ProchanRepository` download branches
  are unreachable on the generic path (repo == null) — kept, documented.

### B12 — Tests & fixtures
- `LegacyStanzaCompletenessTest` (D2) — rework: legacy-stanza⇄enum completeness stays; generic
  stanzas exempted from the enum requirement.
- `ConfigBackedSourceCompletenessTest` — derives its api set from the parsed document instead of
  `CONFIG_BACKED_APIS`.
- Fake registries (`data/.../SourceMigrationTestFakes.kt`, registry tests in composeApp) — updated
  for the new interface surface. Tests may keep importing `MangaSource` (they pin legacy invariants).

---

## 2. Target architecture

```
CONFIG_BACKED_SOURCES_JSON  ──parse+validate (all-or-nothing)──▶  RemoteSourceConfigManager.activeDocument()
                                                                        │ (single validated collection)
                              ┌─────────────────────────────────────────┤
                              ▼                                         ▼
              DefaultSourceRegistry (composition root)      SourceCatalogSyncRepositoryImpl
              • get(api)      → generic client (stanza)     (seeds/asserts Room `sources` rows —
                              → legacy adapter (repoMap)     user isEnabled state lives in Room)
              • isConfigBacked(api) = generic stanza exists
              • descriptor(api) / genericDescriptors()
                = RuntimeSourceDescriptor projection
                              │
                              ▼
      :data repos (Home/Search/Details/Pages/Sources/Adult) — join Room row (user state)
      with descriptor (catalog metadata); NEVER require a legacy repo for a generic api
                              │
                              ▼
      :domain models (Source, SourceTab) carry displayName + SourceIcon (pure data)
                              │
                              ▼
      :ui renders descriptor-driven metadata; icons via SourceIconRegistry / Coil / fallback
```

- **`RuntimeSourceDescriptor`** (new, `:sources:contracts`) — the ISP projection of `SourceConfig`
  minus the executable spec:

```kotlin
data class RuntimeSourceDescriptor(
    val api: String,
    val displayName: String,
    val language: String,        // "(AR)"-style tag (the persisted grouping key)
    val engine: String,          // "generic" | "legacy"
    val baseUrl: String,
    val priority: Int,
    val enabledByDefault: Boolean,
    val siteState: String,       // config vocabulary (WORKING/…)
    val lifecycle: String,       // active | disabled | removed
    val iconResourceKey: String?,
    val iconRemoteUrl: String?,
    val blacklistGenres: List<String>,
)
```

- **One canonical collection:** the registry is the ONLY reader of `activeDocument()` for lookup and
  metadata; `SourcesRepositoryImpl`'s direct `SourceUpdateManager` read (lifecycle filter) moves onto
  `descriptor(api)?.lifecycle` so no second derivation exists. The catalog sync keeps its own
  `activeDocument()` read (it is the Room writer, a different responsibility, same document).
- **`CONFIG_BACKED_APIS` is REMOVED** (decision): with remote delivery double-locked off, the set
  duplicates the stanza `engine` field with zero added trust and is the drift hazard the refactor
  exists to kill. Recorded for Stage-2: enabling remote config requires EITHER a real signature
  verifier (replacing `DenyRemoteSignatureVerifier`) OR reinstating an in-binary generic allow-list.
- `availableApis()` (zero callers) is dropped from the `SourceRegistry` contract in favor of
  `genericDescriptors()`.

## 3. Canonical ownership & runtime lifecycle

| Concern | Owner |
|---|---|
| Validated source collection | `RemoteSourceConfigManager.activeDocument()` (bundled tier; all-or-nothing validation, `onDocumentRejected` alarm) |
| api → client / descriptor | `DefaultSourceRegistry` (single public lookup) |
| User enable/disable + siteState/baseUrl projection | Room `sources` rows, written by catalog sync + user toggles |
| Active source | api-string pref (new), resolved against enabled ∧ config-backed rows |
| Captured auth headers | DataStore header store, keyed by api (already true) |

Lifecycle walk-through (post-refactor):
- **Fresh install:** bundled doc validates → registry serves generic stanzas; catalog sync seeds rows
  (`isEnabled = enabled && lifecycle=="active"`); onboarding enables the user's language group.
- **Upgrade adding a source:** new stanza validates → seed on next launch → appears in Sources screen
  (+ "new sources" badge path unchanged); Home tab once enabled. No enum, no Kotlin.
- **Upgrade modifying a source:** sync re-asserts baseUrl/imageBase (+`previousHosts` URL sweep),
  siteState, lifecycle. Registry picks the new stanza immediately (in-memory document).
- **`lifecycle:"disabled"`:** row force-disabled every launch; descriptor still resolvable (Library
  reads fine); hidden from Sources screen (existing behavior).
- **`lifecycle:"removed"`:** row deleted, never re-seeded; saved-library data untouched; descriptor
  reports `removed` (Details/read/downloads of saved entries keep working from cache — verified).
- **Invalid bundled JSON / one bad stanza:** all-or-nothing rejection stays (decision: per-stanza
  acceptance would let a typo silently drop one source; whole-document rejection is loud —
  `onDocumentRejected` + startup zero-generic alarm + Home typed failure + the build gates make it
  effectively unshippable). `ConfigBackedSourceCompletenessTest` remains the compile-time gate.
- **Zero valid generic sources:** typed `AppError` on Home (shipped 2026-07-09) + Kermit alarm. Unchanged.

## 4. Icon architecture

**Config schema** (`:sources:contracts`): `SourceConfig.icon: IconSpec? = null`
```kotlin
@Serializable data class IconSpec(val resourceKey: String = "", val remoteUrl: String = "")
```
Validator (`DefaultSourceConfigValidator`): `resourceKey` must match `[a-z0-9_]{1,64}` when set;
`remoteUrl` must be an absolute **https** URL when set (http rejected at config time — no cleartext
exemptions for icons). Both optional; both may be set (packaged wins).

**Packaged-drawable registry** (`:composeApp`, replaces `RepoIconResolver`):
```kotlin
object SourceIconRegistry {
    private val byKey: Map<String, DrawableResource> = mapOf("swatmanga" to Res.drawable.ic_swatmanga, /* … */)
    fun resolve(resourceKey: String): DrawableResource? = byKey[resourceKey]
}
```
- Keys are stable lowercase strings referenced from JSON; JSON never sees generated resource IDs.
- One generic entry per **drawable** (sources may share keys, e.g. the Dilar pair / Mangapark family).
- Not a source list: it maps assets only; membership in it implies nothing about discovery/identity.
- Companion `val entries: List<Pair<String, DrawableResource>>` backs a duplicate-key unit test.
- Missing key ⇒ `null` ⇒ next tier. Never crashes.

**Runtime icon model** (`:domain`, pure data — UI can only see `:presentation`→`:domain`):
```kotlin
data class SourceIcon(
    val resourceKey: String? = null,
    val remoteUrl: String? = null,
    val fallbackLabel: String,   // 1–2 chars from displayName (existing sourceInitials rule)
    val stableKey: String,       // the api string — drives the deterministic fallback color
)
```
No `DrawableResource`/`Painter`/platform image types in `:domain`/`:presentation`/persistence.

**Rendering** (`:ui` shared composable, used by `SourceMedallion` + `SourceTabsRow`):
```
registry.resolve(resourceKey) → painterResource        (packaged wins)
else remoteUrl → AsyncImage via the existing Coil singleton (memory+disk cache, placeholder =
                 deterministic fallback, error → fallback; bounded size = the medallion box)
else deterministic fallback: fallbackLabel initials on a color from stableKey
```
- Deterministic color: FNV-1a over `stableKey` → index into a fixed palette (NOT `hashCode()` —
  process-stable across launches/platforms by construction).
- Icon failures never affect discovery/enabling/routing — icons are render-only.
- Repeated downloads bounded by Coil's disk cache (same loader as covers; no second image stack).
- `LibrarySourceBrand.libraryBrandColor` keeps its named-brand map; `else` branch switches from
  opaque black to the same deterministic palette.

**Authoring flows** (goes into ADDING_SOURCES.md):
```
Packaged icon: add drawable → one SourceIconRegistry entry → "icon": {"resourceKey": "x"} in JSON
Remote icon:   "icon": {"remoteUrl": "https://…/icon.png"} in JSON — no Kotlin at all
No icon:       omit the field — deterministic initials avatar
```

## 5. Exact files expected to change

**New files**
- `sources/contracts/.../model/SourceConfig.kt` → `IconSpec` (same file) + `sources/contracts/.../RuntimeSourceDescriptor.kt`
- `composeApp/.../sources/runtime/SourceIconRegistry.kt` (replaces `RepoIconResolver.kt` — deleted)
- `domain/.../model/sources/SourceIcon.kt`
- `ui/.../common/SourceIconImage.kt` (shared icon composable + deterministic palette util)
- `composeApp/src/desktopTest/.../GenericSourcesDecouplingGuardTest.kt` (architectural guard)
- Tests: icon registry/model tests, descriptor tests, config-only-source routing tests

**Modified**
- `sources/engine/.../DefaultSourceConfigValidator.kt` (icon rules) + its tests
- `composeApp/.../sources/runtime/DefaultSourceRegistry.kt` (drop set gate; descriptors)
- `sources/contracts/.../SourceRegistry.kt` (descriptor API; drop `availableApis`)
- `composeApp/.../sources/runtime/BundledSourcesConfig.kt` (delete `CONFIG_BACKED_APIS`; add `icon`
  to the 12 generic stanzas; bump `revision`)
- `composeApp/.../di/SourcesGenericModule.kt` (registry ctor)
- `data/.../repository/SourcesRepositoryImpl.kt`, `HomeFeedRepositoryImpl.kt`,
  `SearchRepositoryImpl.kt`, `AdultContentClassifierImpl.kt`, `data/.../mapper/HomeMappers.kt`,
  `data/.../mapper/SourcesMappers.kt`
- `domain/.../model/sources/Source.kt`, `domain/.../model/home/SourceTab.kt` (additive fields;
  `iconKey` kept for owner-WIP HomeScreen preview compatibility)
- `sources/legacy/.../repo_settings/domain/SourcesRepository.kt` (active-api persistence + migration;
  repoMap surface untouched)
- `platform/.../core/storage/StorageKeys.kt` (`ACTIVE_SOURCE_API`)
- `composeApp/App.kt` (icon local provider; Coil interceptor config-first host match)
- `composeApp/.../images/SourceImageRequest.kt`, `composeApp/.../webview/ui/viewmodel/WebViewViewModel.kt`
- `composeApp/.../sources/runtime/ConfigHostTrust.kt` (reverse host→api lookup)
- `app/.../work/LibraryRefreshWorker.kt` (+ `app/build.gradle.kts` if `:sources:contracts` isn't on
  the compile classpath)
- `ui/.../sources/SourcesScreen.kt` (displayName + icon model), `ui/.../home/components/SourceTabsRow.kt`
  (displayName + icon model), `ui/.../library/LibrarySourceBrand.kt` (deterministic else)
- Tests: `LegacyStanzaCompletenessTest`, `ConfigBackedSourceCompletenessTest`,
  `DefaultSourceRegistryTest`, `SourceMigrationTestFakes` + routing tests, sync tests
- Docs: `docs/sources/ADDING_SOURCES.md`, `CLAUDE.md`, `docs/ENGINEERING_NOTES.md`

**Explicitly untouched:** `ui/.../home/HomeScreen.kt` (owner-WIP), `sources_repositry/**` (read-only;
incl. `MangaSource.kt`, `MangaLekRepositoryv2.kt`), `native-app/`, `p1/`.

## 6. Persistence & migration risks

| Store | Format today | Change | Risk / mitigation |
|---|---|---|---|
| Room `sources` | api-string PK | none | — |
| saved_manga / history / chapter_downloads | api strings, no FK | none | orphan-tolerance already tested |
| DataStore headers | api-keyed | none | — |
| Backup archive | api strings | none | — |
| **Active tab** | `Int` index (`active_tab`) into enabled∧repoMap list | **new `active_source_api` string** | One-time migration on first read: resolve old index through the same enabled-list ordering (all currently-enabled sources have legacy repos, so resolution is faithful); unresolvable → empty → existing first-config-backed defensive floor. Old key left in place (rollback-safe). Idempotent: migration only runs when the string key is absent. |
| Enum names | **never persisted** (verified) | — | no compatibility mapping needed anywhere |

Regression risks carried into implementation: Home tab `baseUrl` now comes from row/descriptor
instead of the legacy object (row.baseUrl is already sync-asserted to config truth — WebView-open
parity test); pilots' library refresh switches from legacy parser to generic `details()` (wire-shape
mapping tested); `SourceTab`/`Source` constructors change additively only.

## 7. Legacy compatibility strategy

Legacy sources keep the enum, the repoMap, `LegacyKotlinSourceClient`, and all existing behavior —
they are force-disabled/hidden in the shipped posture. Callers keep one public lookup
(`SourceRegistry`); the legacy path remains the `else` branch of `isConfigBacked`. Unknown apis get
typed failures (Details/Pages already do; Home hardened 2026-07-09; refresh worker skips+logs). The
Prochan/MangamelloPlus download special-cases stay (unreachable for generic apis, `repo == null`).
`MangaSource` is NOT deleted — the audit proves ~50 legacy scrapers still need it.

## 8. Phased implementation (each phase compiles + tests green; one commit per phase)

1. **P1 — contracts + validator:** `IconSpec`, `RuntimeSourceDescriptor`, `SourceRegistry.descriptor/
   genericDescriptors` (drop `availableApis`), `DefaultSourceRegistry` derivation, validator icon
   rules; update registry fakes/tests.
2. **P2 — single authority:** remove `CONFIG_BACKED_APIS` + the registry's set gate; rework
   `LegacyStanzaCompletenessTest` (legacy⇄enum only) + `ConfigBackedSourceCompletenessTest`
   (document-derived); SourcesGenericModule ctor.
3. **P3 — icons:** `SourceIconRegistry` + delete `RepoIconResolver`; `icon` stanzas for the 12
   generics; domain `SourceIcon`; `SourceIconImage` composable (packaged→remote→deterministic);
   `SourceMedallion`/`SourceTabsRow` + key-based `LocalSourceIconResolver`; `LibrarySourceBrand`
   deterministic else; icon tests.
4. **P4 — Sources screen metadata:** domain `Source` + displayName/icon; `SourcesRepositoryImpl`
   descriptor join (lifecycle filter via descriptor); `SourcesScreen` renders displayName.
5. **P5 — Home tabs + active source + search:** `SourceTab` built from row+descriptor (drop
   `getOrRepoByName` requirement); api-string active-source persistence + int migration;
   `HomeFeedRepositoryImpl` active-api resolution; `SearchRepositoryImpl` row-driven fan-out.
   Routing tests prove a config-only api is visible/selectable/searchable.
6. **P6 — header & gating surfaces:** WebView solve → api-keyed header-store write; cover requests
   config-first (`HeaderStore` + config static headers); Coil interceptor config host→api matching
   (`ConfigHostTrust` reverse lookup); `AdultContentClassifierImpl` descriptor-first.
7. **P7 — LibraryRefreshWorker:** registry-first refresh (generic `details()`), legacy fallback for
   non-config apis; regression test.
8. **P8 — guardrails + docs:** `GenericSourcesDecouplingGuardTest`; end-to-end synthetic-stanza test
   ("JSON-only source" invariant); ADDING_SOURCES.md/CLAUDE.md/engineering-notes updates.

## 9. Tests to add (beyond per-phase unit tests)

- Synthetic generic stanza (never in `MangaSource`) through the REAL manager+registry assembly:
  parses, validates, `isConfigBacked`, `get()` returns generic client, descriptor complete.
- Config-only api appears in Home tabs / is searchable / becomes active (fake Room + fake registry,
  `SourceMigrationTestFakes` precedent).
- Active-tab int→api migration: seeded old pref → resolves to same source; idempotent; missing → floor.
- Cover-guard regression (existing, 2026-07-09) still green — removal never blanks covers.
- Icon suite: key resolves; unknown key → remote; packaged beats remote; invalid remote URL rejected
  by validator; remote failure → deterministic fallback; no-icon source fully functional; duplicate
  registry keys detected; icon failure never affects routing/persistence.
- Refresh worker: config-backed api routes to generic client; unknown api skips (no cover blanking).
- Legacy pins: legacy stanza⇄enum completeness; legacy source still resolvable through `get()`.
- Backup round-trip unaffected (existing suite).

## 10. Architectural guardrails

- **`GenericSourcesDecouplingGuardTest`** (`:composeApp` desktopTest, JVM): walks the production
  source trees of `composeApp/data/data:download/data:local/data:remote/domain/presentation/ui/
  platform/sources:contracts/sources:engine/sources:config/app` (`src/*Main/kotlin`) and fails on
  `sources_repositry.data.MangaSource` imports, `MangaSource.entries`, `MangaSource.valueOf`
  outside the explicit allow-list (`ChapterDownloadService.kt` Prochan branch only). Also fails if
  `CONFIG_BACKED_APIS` reappears in production code. Runs in CI via `:composeApp:desktopTest`.
- Existing gates keep holding the rest: `ConfigBackedSourceCompletenessTest` (document validity +
  endpoint completeness), reworked `LegacyStanzaCompletenessTest` (legacy⇄enum), locale-parity gate,
  Koin graph tests.

## 11. Blockers / owner-WIP conflicts

- `ui/.../home/HomeScreen.kt` builds `SourceTab(...)` preview fixtures (754-756) and reads
  `activeTab?.api` (335) — **all `SourceTab` changes must be additive with defaults**; the banner
  label keeps showing `api` until the owner file unlocks (displayName == api for all current pilots,
  so no visible regression).
- `sources_repositry/data/MangaSource.kt` is owner-WIP/read-only — never edited (goal is removing
  dependents, not the enum).
- Filters/sort-genre search for config-only sources: capability gap by design (config schema has no
  filter spec yet — Stage-2 candidate), NOT a parity break for the 12 pilots (they keep legacy repos).
- `:app` may need `:sources:contracts` added to its dependencies for the worker's registry injection
  (layering-legal: contracts is the stable API module).
