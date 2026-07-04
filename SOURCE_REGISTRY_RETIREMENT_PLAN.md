# SourceRegistry Endpoint Retirement Plan — JSON Config as the Single Source Authority

Date: 2026-07-03 · Status: **COMPLETE — all 6 phases implemented and pushed (2026-07-04).**
The remote SourceRegistry endpoint is DELETED; the bundled JSON config document is the single
authority for every source (12 generic + 33 metadata-only legacy stanzas). §6 decided: **Option A**.
Phase commits: P1 `67e0b718` · P2 `3e6d3733` · P3 `130216cc` · P4 `9e1c9a82` · P5 `da8c894e` · P6 `818912b4` (endpoint deletion).
Goal: everything `SourceRegistryRefreshRepositoryImpl` does today becomes owned by the JSON config
system; the remote `/source/35` + `/dev/source` endpoint becomes unnecessary and is then removed
cleanly. Direction pre-approved by the owner: **Phase 1 = endpoint skips config-backed APIs.**

Every claim below was verified against the current code (2026-07-03 trace); citations inline.

---

## 1. Full responsibility audit — what the endpoint flow owns TODAY

`SourceRegistryRefreshRepositoryImpl` (`data/.../repository/SourceRegistryRefreshRepositoryImpl.kt`),
bound `single` in `SourcesReworkModule.kt:138`, called once per launch from `App.kt:387` (parallel
with the config sync at `App.kt:391-394`), fetching `/dev/source` (debug) or `/source/35` (release)
per `Admin.isAdmin` (`:81`).

| # | Behavior | Where | Who consumes it downstream |
|---|---|---|---|
| R1 | **Row seeding/upsert** of any api the feed lists (insert happens only implicitly — feed rows for unknown apis are version-compared against `-1` and update nothing unless present; in practice it updates existing rows) | `upsert()` `:113-157` | the `sources` Room table |
| R2 | **baseUrl updates, version-gated**: `src.baseVersion > row.baseVersion` → migrate stored URLs, then `updateBaseUrlAndVersionByName` | `:128-135` | (a) `DbSourceBaseUrlProvider` → `GenericSourceClient.effectiveBaseUrl` (live row wins over config at request time — `SourcesGenericModule.kt:68`, `GenericSourceClient.kt:70-79`); (b) host-trust fallback (R7); (c) repo-settings surfaces |
| R3 | **Stored-URL migration on host move** (`SourceUrlMigrator.migratePageUrls`): rewrites `saved_manga.url`, `saved_chapters.url`, `history_items.{mangaUrl,chapterUrl}`, `notifications.{mangaUrl,chapterUrl}` BEFORE the registry bump | `:133` | saved-library fetches (details/chapters/reader resolve stored absolute URLs) |
| R4 | **imageBaseUrl updates + image-URL migration** (`migrateImageUrls`: `saved_manga.imageUrl`, `history_items.mangaImageUrl`, `notifications.mangaImageUrl`), version-gated like R2 | `:137-144` | cover rendering for saved rows |
| R5 | **siteState** (`WORKING` / `STOPPED` / `UNDER_MAINTENANCE`, with the native `isWorking` fallback parse) | `:146-149`, `:255-270` | Home tab UI states (`HomeScreen.kt:336, 687` via `SourceTab.siteState` from `HomeFeedRepositoryImpl`) |
| R6 | **Source removal**: `shouldDelete` (the native `delate` key) → `deleteSourceByName` | `:123-126` | removes the row entirely (library entries untouched — they live in their own tables) |
| R7 | **Deep-link host trust (indirect)**: `findRepoByHost`'s DB fallback matches a host against `sources.baseUrl` rows (`sources/legacy/.../SourcesRepository.kt:224-238`), feeding `ownsHostForApi` → the push deep-link trust gate (`App.kt` `isHostTrustedFor`) | consumer of R2 | push-payload URL validation |
| R8 | **Legacy-api support**: R2-R6 are the ONLY mechanism that reacts to a host move / status change / removal for sources that are NOT config-backed — including the legacy apis that old saved-library entries still route through (`MangaDetailsRepositoryImpl.kt:116` legacy branch) and the permanently-legacy sources (Dilar, MangaPark, Comick, …) | whole file | old library entries + the legacy fallback floor |
| R9 | **Dev/prod feed split** via `Admin.isAdmin` (debug-only since C1) | `:81` | dev-vs-prod catalog testing |
| R10 | **DB version semantics**: `baseVersion`/`imageUrlVersion` monotonic counters; endpoint updates only on strictly-greater; config sync writes `row.version + 1` when it asserts config values (`SourceCatalogSyncRepositoryImpl` update branch) — the two writers share these counters, which is the root of the launch-order race | `:128, :137` + sync impl | both writers |

What the endpoint does **NOT** own (already config-owned): which sources exist in the UI at all
(Sources list filters config-backed — `SourcesRepositoryImpl.kt:159-162`), Home tabs/active source
(`HomeFeedRepositoryImpl.kt:107, 255-257`), Search/Details routing, enable/disable of non-config
rows (config sync force-disables every launch), engine behavior (configs), request headers.

**Related-but-separate authority (unchanged by this plan):** the repo-settings screen's
user-edited mirror URL (DataStore, per `SourcesRepository.kt:199-205` KDoc) — a user override
layered above whatever the authority says. Keep it out of scope; note it in Phase 3 tests only to
ensure we don't regress it.

## 2. JSON config schema design — exact additions

Current shapes (`sources/contracts/.../model/SourceConfig.kt`): the document already has
`schemaVersion` + monotonic `revision` (merge precedence bundled<cache<remote), and `SourceConfig`
already has `api, language, displayName, baseUrl, imageBase, enabled, priority, engine,
minAppVersion, headers, …`. Notably **`enabled` already exists but the catalog sync ignores it**
(rows are always seeded `isEnabled = false` for user opt-in).

Proposed additions (all with defaults → older documents parse unchanged; parser uses
`ignoreUnknownKeys`, so newer documents also parse on older apps):

```jsonc
// per SourceConfig
{
  "api": "Azora",
  "baseUrl": "https://azoramoon.com",
  "imageBase": "",
  // NEW — R5. Mirrors the SourceState enum; default "WORKING".
  "siteState": "WORKING" | "STOPPED" | "UNDER_MAINTENANCE",
  // NEW — R6. Lifecycle replaces the endpoint's shouldDelete. Default "active".
  //  "active"   → normal
  //  "disabled" → force-disable the row + hide from pickers (kept for saved-entry reads)
  //  "removed"  → delete the sources row (same effect as endpoint shouldDelete)
  "lifecycle": "active" | "disabled" | "removed",
  // NEW — R3/R4 robustness + R7. Previous hosts this source has lived on. Drives stored-URL
  // migration (any stored URL whose host ∈ previousHosts is rewritten to baseUrl) and joins the
  // deep-link trust set. Append-only history; hosts, not URLs.
  "previousHosts": ["azoramoon.co", "azoraworld.com"],
  // NEW — R4 sibling for image hosts.
  "previousImageHosts": [],
  // NEW — R7. Extra hosts trusted for this api beyond baseUrl/imageBase/previousHosts
  // (image CDNs on unrelated domains). Default empty.
  "trustedHosts": ["cdn.example-azora.net"]
}
```

Deliberate non-additions:
- **No per-source `version` field.** The endpoint needed `baseVersion` because it was a dumb feed;
  config sync is *state-based* (it compares row vs config and migrates on difference —
  `SourceCatalogSyncRepositoryImpl` update branch already does this for baseUrl/imageBase). The
  document-level `revision` already orders documents. Keeping the DB's `baseVersion` counters as a
  sync implementation detail (bump-on-write) avoids a second version algebra.
- **No migration-rule DSL.** `previousHosts` + the existing host-swap migrator (`replaceBaseUrl`
  preserves path/query/fragment) covers the real case (host move). Anything fancier is YAGNI.
- **`enabled` gains defined semantics** instead of a new field: it becomes "default-enabled on
  first seed" (today's seed hardcodes `false`); user toggles still win afterwards. `lifecycle`
  (not `enabled`) is the kill switch.

Validator additions (`DefaultSourceConfigValidator`): reject unknown `siteState`/`lifecycle`
values; reject malformed hosts in the three host lists (must parse as bare hosts, no scheme/path);
fail-closed as today.

## 3. Runtime ownership model — the new rule

1. **The JSON config document is the ONLY authority for config-backed sources**: existence,
   metadata, baseUrl/imageBase, siteState, lifecycle, migration history, trust hosts.
2. **Room's `sources` table is a local cache/projection** of the config (plus two user-owned
   fields: `isEnabled` user toggle, and priority-order if user-sorted) — never an independent
   authority. `DbSourceBaseUrlProvider` stays (the engine reads the projection), but the
   projection's writer for config apis is the config sync alone.
3. **The endpoint must never write a config-backed row** (Phase 1 makes this structural).
4. **Legacy/non-config rows** remain endpoint-owned until Phase 4's decision retires them, then
   config-owned via legacy-source config stanzas (`engine: "legacy"` entries carrying only the
   lifecycle/host metadata — the parser already defaults `engine` to `"legacy"`).
5. **Delivery**: authority updates ship with app releases (bundled doc) today; the already-designed
   Stage-1 signed remote channel (`remote = null` + `DenyRemoteSignatureVerifier` today) is the
   future fast path. Retiring the endpoint does NOT depend on enabling remote config — a bundled
   doc + app release moves hosts with the same latency as shipping a new scraper build, which is
   what legacy host moves effectively require anyway. State this tradeoff honestly: **until
   Stage-1 remote lands, host moves reach users at app-update speed, not server speed.** (Today's
   endpoint is faster for URL changes — that is the one real capability lost at Phase 5, and the
   reason Phase 5 is flag-gated rather than a hard delete.)

## 4. Safe migration phases

**Phase 1 — endpoint skips config-backed APIs** *(direction pre-approved; small, immediate)*
- `SourceRegistryRefreshRepositoryImpl` gains a `SourceRegistry` (contracts) dependency; the
  `upsert()` loop and `shouldDelete` branch skip any `src.api` where `isConfigBacked(api)`.
- Kills the config-vs-endpoint write race (R10) structurally; startup order stops mattering.
- Files: `SourceRegistryRefreshRepositoryImpl.kt`, `SourcesReworkModule.kt:138-143` (inject
  registry), new `SourceRegistryRefreshSkipsConfigApisTest` in `:data` commonTest.
- Exit: tests in §5(a,g) green; behavior for legacy apis byte-identical.
- **Status: IMPLEMENTED 2026-07-03.** Ctor gained `sourceRegistry: SourceRegistry`; one guard at
  the top of the per-source loop covers both the `shouldDelete` branch and the version-gated
  updates; binding updated. `SourceRegistryRefreshSkipsConfigApisTest` (5 tests, `:data`
  commonTest, real `refreshSources()` over Ktor MockEngine) covers §5(a) in both the overwrite and
  delete directions, §5(g) in both launch orders, §5(h) full legacy behavior (update + migrate +
  siteState + `delate`), and pins the non-admin PROD-feed URL (R9). `StatefulSourcesDao` upgraded
  to stateful/recording siteState + delete for these assertions.

**Phase 2 — schema + parser + validator extensions** *(additive, inert)*
- Add §2 fields to `SourceConfig` (contracts), validator rules, and golden parse tests. No
  consumer changes yet — documents without the fields behave exactly as today.
- Files: `sources/contracts/.../model/SourceConfig.kt`, `SourceConfigParser.kt`,
  `DefaultSourceConfigValidator.kt` + `:sources:contracts` tests; `PILOT_SOURCES_CONFIG_JSON`
  gains the fields for the 12 pilots (values mirroring current endpoint truth).
- Exit: parser/validator golden tests; old docs parse; bad values rejected.
- **Status: IMPLEMENTED 2026-07-04 (`3e6d3733`).** Five fields added (defaults → old docs parse
  unchanged); validator rules run for EVERY engine (metadata-only legacy stanzas included); 5 new
  golden tests. `DefaultSourceConfigValidator.validateSource` decomposed while at it.

**Phase 3 — config sync takes over the remaining responsibilities**
- `SourceCatalogSyncRepositoryImpl` grows: write `siteState` (R5); honor `lifecycle`
  (disabled → force-disable+hide; removed → `deleteSourceByName`, R6); migrate stored URLs for any
  host ∈ `previousHosts`/`previousImageHosts` (R3/R4 — reusing `SourceUrlMigrator`, made
  idempotent per host); seed `isEnabled` from config `enabled` on FIRST insert only.
- Host trust (R7): extend the trust check to consult config hosts (baseUrl + imageBase +
  previousHosts + trustedHosts) for config apis — either by keeping the row projection accurate
  (already true) plus an engine-side set, or a small `ConfigHostTrust` port consulted by
  `findRepoByHost`'s fallback. Decide shape at implementation; the test in §5(f) pins behavior,
  not shape.
- Files: `SourceCatalogSyncRepositoryImpl.kt` (+ its test file, which already exists),
  `SourceUrlMigrator.kt`, possibly `sources/legacy/.../SourcesRepository.kt` (trust fallback),
  `HomeFeedRepositoryImpl` (no change expected — reads the row projection).
- Exit: §5(b-f) green; a config-only document can express everything the endpoint feed could.
- **Status: IMPLEMENTED 2026-07-04 (`130216cc`).** Sync owns siteState/lifecycle/alias sweep for
  every config entry; `ConfigHostTrust` (composition root) joins the deep-link gate ahead of the
  legacy resolver; §5(b-f) green (+13 tests). Two findings locked in during implementation:
  (a) the repo-settings user mirror is persisted into `sources.baseUrl` itself (NOT a separate
  DataStore layer as §1 assumed) — protection is authoring-opt-in: a source that declares
  `previousHosts` gets mirror-safe assertion (row host outside the declared set survives);
  without declared history the classic assert-any-difference posture keeps plain host moves
  working. (b) `enabled` first-seed semantics landed by flipping the MODEL default true→false
  (zero readers existed), keeping every shipped document's seeding byte-identical.

**Phase 4 — legacy/saved-library decision executed** (see §6 — owner decision required)
- Depending on the §6 choice: add legacy-source config stanzas (metadata-only) so config owns
  their lifecycle too, or document the retirement of endpoint-driven legacy host-moves.
- Exit: §5(h) has a green test OR a written retirement note in this doc + CLAUDE.md.
- **Status: IMPLEMENTED 2026-07-04 (`9e1c9a82`) — Option A executed.** 33 `engine:"legacy"`
  stanzas (revision 2), values captured VERBATIM from the live `/source/35` feed (41 entries,
  fetched 2026-07-04) so the handover is byte-identical; 4 feed-absent apis carry code constants;
  3 live host moves declared in previousHosts (Dilar, Komik Cast, Flowermanga). The 12 generic
  stanzas untouched — their baseUrl is the generic ENGINE's base, legitimately different from the
  legacy scraper hosts (cross-SYSTEM hosts must never drive migration).
  `LegacyStanzaCompletenessTest` pins registry⇄config completeness forever.

**Phase 5 — endpoint off behind a flag**
- `App.kt:387`'s `launch { refreshSources() }` gated on a single compile-time flag
  (`SourceDebugFlags`-style, e.g. `SourceRegistryFlags.ENDPOINT_REFRESH_ENABLED = false`), NOT
  deleted — one-line rollback for a full release cycle. `Admin.isAdmin`'s URL branch (R9) goes
  dormant with it (documented in Admin.kt KDoc).
- Exit: one release cycle in production with the flag off and no source-availability regressions
  (watch: Home tab states, saved-entry reads, complaint volume).
- **Status: IMPLEMENTED 2026-07-04 (`da8c894e`), then SUPERSEDED same-day by Phase 6** (owner
  directive to complete the plan end-to-end). The flag was compile-time, so it offered no cheaper
  production rollback than reverting a commit — both require shipping a new build. Post-deletion
  rollback = `git revert` of the Phase 6 commit (restores the flag AND the endpoint path; Phase
  1's config-backed skip stays in force either way). The watch-list above still applies to the
  first production cycle on config-owned behavior.

**Phase 6 — retire the code**
- Delete `SourceRegistryRefreshRepositoryImpl` + `RefreshSourcesUseCase` +
  `SourceRegistryRefreshRepository` + the `SourcesReworkModule` binding + the `App.kt` call +
  flag; keep `SourceUrlMigrator` (config sync uses it). Update CLAUDE.md + this doc.
- Exit: compile gates + full suites green; grep shows zero `/source/35`·`/dev/source` references.
- **Status: IMPLEMENTED 2026-07-04.** Deleted: `SourceRegistryRefreshRepositoryImpl` (+ its P1
  test), `SourceRegistryRefreshRepository`, `RefreshSourcesUseCase`, the Koin binding + factory,
  the `App.kt` startup call, `SourceRegistryFlags`, and the ktor-client-mock test dep.
  `replaceBaseUrl` moved to `SourceUrlMigrator.kt` (its surviving consumer; `ReplaceBaseUrlTest`
  unchanged). R9 (the `Admin.isAdmin` dev/prod feed split) retired with it — Admin.kt/MyApp/
  AdminDefaultsTest/SourcesDao prose updated. Remaining `/source/35` mentions are docs/history
  (this plan, BundledSourcesConfig provenance KDoc).
- **Test annotation (2026-07-04 audit):** the §5(a)/(g) tests and the ENDPOINT half of §5(h)
  lived in the P1 test file deleted with the endpoint in this phase — they gated Phases 1–5 and
  died with the code they pinned (nothing left to test). The CONFIG half of §5(h) — legacy
  lifecycle/host behavior preserved through the stanzas — remains permanently pinned by
  `SourceCatalogSyncRepositoryTest` + `LegacyStanzaCompletenessTest`; §6's "§5(h) remains a
  permanent test" refers to that config half.

## 5. Tests and gates (each lands with its phase)

a. **Endpoint cannot overwrite config-backed baseUrl** — feed row for a pilot api with higher
   `baseVersion` + different URL → row unchanged, no migration calls (Phase 1).
b. **Config updates baseUrl** — doc with changed `baseUrl` → row updated, `migratePageUrls`
   called once, idempotent on re-sync (exists partially in `SourceCatalogSyncRepositoryTest`;
   extend).
c. **Config updates siteState** — doc `siteState: UNDER_MAINTENANCE` → row + Home-tab projection
   reflect it (Phase 3).
d. **Config disables/removes a source** — `lifecycle: disabled` → row force-disabled + absent
   from pickers; `removed` → row deleted; saved-library rows untouched (Phase 3).
e. **URL migration from config aliases** — stored manga/chapter/history/notification URLs on a
   `previousHosts` host are rewritten to `baseUrl`; unrelated hosts untouched; second sync is a
   no-op (Phase 3).
f. **Deep-link host trust from config hosts** — `ownsHostForApi` accepts baseUrl/previousHosts/
   trustedHosts for the api and rejects foreign hosts (Phase 3).
g. **No startup race** — with Phase 1 in place, a test drives endpoint-upsert and config-sync in
   both orders over the same fake DAO and asserts identical final rows for config apis (Phase 1).
h. **Legacy behavior preserved or retired-by-decision** — per §6: either a test pinning that a
   legacy api's feed row still updates + migrates (Phases 1-4 keep this), or the written
   retirement record (Phase 4).
Gates per phase: the standard 3-target compile, `:data`/`:sources:contracts` desktopTest,
`:app:testDebugUnitTest`, and for Phase 3+ the pilot parity suites in `:composeApp` commonTest.

## 6. Backward-compatibility decision — legacy saved-library entries (OWNER DECISION)

The question: after the endpoint dies, who handles a **host move for a legacy api** that old
saved-library entries still point at?

| Option | What it means | Cost | Risk |
|---|---|---|---|
| **A. Preserve via config stanzas** (recommended) | Every legacy api gets a metadata-only config entry (`engine:"legacy"`, baseUrl, previousHosts, lifecycle). Config sync migrates their stored URLs exactly as it does for pilots. | One-time authoring of ~50 stanzas from `MangaSource` registry constants; each future legacy host move = a doc edit + release | Lowest — saved libraries keep working; legacy sources remain readable offline and refreshable |
| B. Migrate-once, then freeze | Ship one final migration pass, then stop tracking legacy hosts; entries keep working until the NEXT host move, then their remote refresh breaks (downloads still read) | Minimal | Silent decay: users with legacy entries see fetch failures after the next host rotation, no recovery path except re-adding |
| C. Drop legacy support ("start fresh") | Document that legacy-api entries are unsupported; optionally prompt users to re-add from config sources | Zero authoring | User-visible data loss for pre-migration libraries; support burden; contradicts the app's parity heritage |

**Recommendation: A.** The marginal cost is a one-time authoring pass (the values already live in
code constants + the current DB), and it is the only option in which "config is the single
authority" is literally true for every source the app has ever written to disk. B is acceptable
only if analytics show legacy-api library rows are near-zero; C is not recommended while the
legacy fallback floor is still part of the pilot-source safety story. **This plan assumes A
unless you say otherwise; Phase 4 is where the choice becomes code.**

> **DECIDED 2026-07-03 — Option A** (owner): "I want the JSON config to become the literal single
> authority, so please plan and later implement metadata-only config stanzas for the legacy APIs
> too. Even if a legacy source is not fully converted yet, its lifecycle, host moves, previous
> hosts, trusted hosts, maintenance state, and removal/disable behavior should be owned by config,
> not by the remote SourceRegistry endpoint." Phase 4 therefore authors metadata-only stanzas
> (`engine:"legacy"`) for every legacy api, and §5(h) remains a permanent test — legacy behavior
> is preserved through config, never dropped.

## 7. Execution rules

- No code until you approve this plan; then phases land as separate reviewed commits, each with
  its §5 tests, in order — no phase skipping (Phase 5 explicitly waits a production cycle).
- Files/classes that will change, complete list: `SourceRegistryRefreshRepositoryImpl.kt` (P1,
  P6-delete), `SourcesReworkModule.kt` (P1, P6), `SourceConfig.kt`/`SourceConfigParser.kt`/
  `DefaultSourceConfigValidator.kt` (P2), `BundledSourcesConfig.kt` `PILOT_SOURCES_CONFIG_JSON`
  (P2, P4), `SourceCatalogSyncRepositoryImpl.kt` (P3), `SourceUrlMigrator.kt` (P3),
  `sources/legacy/.../SourcesRepository.kt` (P3 trust fallback, shape TBD), `App.kt` (P5 flag,
  P6 removal), `Admin.kt` KDoc (P5), `RefreshSourcesUseCase.kt` + domain interface (P6-delete),
  CLAUDE.md (P5/P6). Tests: new files per §5 in `:data`, `:sources:contracts`, `:composeApp`.
- Risks called out: bundled-only delivery latency for host moves until Stage-1 remote (see §3.5);
  the repo-settings user-override interplay (§1 note — pin with a regression test in P3); the
  `enabled`-semantics change (first-seed only; verify no pilot flips user toggles on upgrade —
  add a P3 test); Home-tab siteState projection must not flicker when both writers existed (dies
  with P1 anyway).
