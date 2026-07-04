# Sources Migration Plan — make the JSON/config-driven engine the source of truth

**Goal:** configs come from a swappable provider (bundled JSON now, signed API later), DB-cached, app agnostic to origin; UI shows only config-backed sources; `config.baseUrl` is the trusted base URL (compare-and-migrate on refresh); all reads **and** downloads route through `SourceRegistry`; phase the legacy Kotlin scrapers out of the user-facing flow. No app release needed when a source changes its HTML/baseUrl/selectors.

---

## A. Where we are today (verified against the code)

**Already built (reusable):**
- **Origin-agnostic config abstraction (~90% done).** `ConfigStore` (storage origin), `RemoteConfigSource` (remote fetch — interface only, no impl), `SourceUpdateManager`/`RemoteSourceConfigManager` (bundled < cache < remote precedence, parse→validate→accept→merge, anti-rollback by `revision`), `SourceConfigParser`, `SourceConfigValidator`, `ConfigMerger`. The whole app already reads `SourceUpdateManager.activeDocument()` / `SourceRegistry` — it does **not** know where the config came from.
- **Reads already route through the registry** for the 12 piloted sources: Home/Search/Details/ChapterPages `*RepositoryImpl` branch on `sourceRegistry.isPiloted(api)`.
- **baseUrl already converges on ONE DB column** (`sources.baseUrl`), read by both engines (legacy `BaseManga.getBaseUrl()` and the generic engine's `DbSourceBaseUrlProvider`). The engine already prefers the DB value over the frozen config value.
- **A host-move detect-and-migrate engine already exists**: `SourceRegistryRefreshRepositoryImpl` + `replaceBaseUrl()` already rewrite saved_manga / saved_chapters / history / notifications URLs (page + image) when a source's base URL changes — today driven by a legacy server list, keyed on version numbers.

**Gaps vs target:**
1. Config cache is **in-memory only** (`BundledSourceConfigStore.cache: var String?`) — never persisted, wiped on process death.
2. `SourceUpdateManager.refresh()` has **no production caller** — the active document is the bundled JSON parsed once at startup.
3. Nothing filters the catalog/UI to config-backed sources — the Sources screen shows **all ~50 legacy rows**, seeded from the legacy `BaseMangaRepository` set, not the config.
4. **Downloads bypass the registry entirely** — `DownloadWorkerV2` / `CoroutineDownloadRepositoryImpl` always call legacy `repo.fetchChapterDataF` and type-check concrete repos (`MangamelloPlusRepository`/`Prochan`) for per-image headers. (This is why the Azora download hit the legacy scraper.)
5. Remote delivery is intentionally disabled (`remote = null` + `DenyRemoteSignatureVerifier`).

**Hard limit (important):** the generic engine is **text-only** (URLs/strings) and **has no escape hatch for arbitrary compiled Kotlin** (fail-closed by design). A few sources cannot be pure JSON:
- **Promanga / Prochan** — reader pages are **canvas byte de-scramble** (needs image-byte processing the engine doesn't model).
- **Mangatuk** — Next.js **RSC React-Flight streams** (no clean JSON island).
- **Dilar** — AES search payload (OK only if the key is static/config-supplied; impossible-as-generic if the key is computed in JS).
- (MangaPark GraphQL is **already supported** via `POST_JSON`; Comick/Manhwatop/Batcave/Batoto are **Cloudflare/reachability**-blocked, not engine-blocked.)

So "remove legacy completely" is achievable for the *catalog, routing, baseUrl, visibility, and ~80% of parsing*, but a handful of sources need compiled logic that ships in the app — meaning their **parsing** can't be made update-free even though their **routing/visibility/baseUrl** become config-driven.

---

## B. Phase 0 — Decisions (LOCKED 2026-06-25)

1. **Non-config-able sources → hidden, not dropped-in-code.** The UI catalog shows **only converted/config-backed sources**. Legacy/non-converted sources are **hidden from the user-facing UI** (not selectable). Legacy code stays in the binary during migration (as the `FallbackSourceClient` floor / internal use) but is never exposed. Priority is to test the new config-driven flow end-to-end first, then convert the rest gradually. *(No `CompiledClientRegistry` escape hatch for now — the hard sources simply don't appear until/unless converted.)*
2. **Config cache → Room** (`source_config_cache` single-row table + `Migration_10_11`). Chosen for long-term fit, not speed: the config drives the `sources` table, so co-locating them lets the Phase-2 reseed + baseURL migration run in one atomic transaction; gives queryable revision/timestamp metadata; stays consistent with the rest of the persistence layer. Stores the **raw JSON** (forward-compatible with future remote schemas). The v11 migration is acceptable (bundled-JSON changes ship with an app update anyway).
3. **Treat as a new architecture / clean setup.** No need to preserve the legacy source-list behavior for existing installs. Config-backed sources are the only visible sources; config is the catalog source of truth; config/baseUrl drives DB updates + URL migration. Convert more sources gradually after the new system is fully tested.

---

## C. Phased plan (each phase independently shippable + green-gated)

### Phase 1 — Persistent config cache + startup refresh *(foundation, behavior-neutral)*
- Replace the in-memory `ConfigStore` with a persistent one at the composition root (DataStore-backed; reads/writes the raw JSON string the port already speaks). `:sources:*` layering unchanged — the Room/DataStore impl lives in `:composeApp`, like `DbSourceBaseUrlProvider`.
- Call `SourceUpdateManager.refresh()` at app start (mirror the existing `RefreshSourcesUseCase` fire-and-forget at `App.kt:371`). With `remote = null` this is a safe no-op, but the cache + refresh plumbing is now real.
- **Result:** config is loaded → validated → cached in DB; foundation for "API later". No user-visible change.

### Phase 2 — Config becomes the catalog source of truth *(seed + UI filter + baseUrl truth + migrate)*
- **Config-driven seed:** on startup, upsert one `sources` row per `activeDocument().sources` (api/language/baseUrl/imageBase/priority). Additive (`INSERT … IGNORE`) so it coexists during rollout.
- **baseUrl as truth + migrate:** re-point `SourceRegistryRefreshRepositoryImpl` to read the **config** instead of the legacy server list, and detect change by **string compare** (`config.baseUrl != DB.baseUrl`) instead of version numbers. Reuse — unchanged — `replaceBaseUrl()` and all the per-table migrators (saved_manga/chapters/history/notifications, page + image). This is the compare-and-migrate flow you described, built on code that already exists.
- **UI filter:** show only config-backed sources. Inject a `:domain` "is this api config-backed?" predicate (impl delegates to `SourceRegistry.availableApis()` via `:sources:contracts`) and filter in `SourcesRepositoryImpl.observeSources()`. Per Decision 3, optionally sweep non-config rows.
- **Result:** the catalog, visibility, enable/disable, priority, and baseUrl are all config-driven; legacy sources disappear from the UI; host moves auto-migrate stored URLs.

### Phase 3 — Route downloads through the registry *(close the read/write asymmetry)* — **DONE**
- Added the `:shared` `ChapterPageProvider` port (`DownloadPage(url, headers)`) + composition-root `RegistryChapterPageProvider` (consumes `SourceRegistry`); `:shared` can't see `:domain`/`:sources:contracts`, so the seam is DIP across the module boundary.
- `DownloadWorkerV2` (Android) and `CoroutineDownloadRepositoryImpl` (iOS/Desktop) consult the provider; for config-backed sources they download via `sourceRegistry.get(api).pages()` using **`Page.headers`** for per-image auth (threaded as `overrideHeaders`; the legacy repo-header path incl. `MangamelloPlusRepository.imgsHeader` is kept for non-config sources). `FallbackSourceClient` gives the per-verb legacy fallback; the provider returns null for non-config sources and on a routed failure so legacy stays the ultimate floor.
- **Result:** config-backed sources download via the generic engine end-to-end (same path as reads).

### Phase 4 — Download parity + generic download details *(no new conversions)* — **DONE**
- **Owner decision (2026-06-25): parity only, defer conversions** (new sources can't be live-verified from here; a blind conversion risks a stale-selector empty-`Success` on a list verb). Phase 4 proves the new download path is correct **per converted source** and does not touch `PILOT_APIS`.
- Per-source **download-header parity** added to all 12 `*PilotParityTest`s (only Zazamanga asserted `Page.headers` before): static-header sources assert their Referer/UA reach every page; header-free sources (Azora, SwatManga) assert empty headers; captured-only CF-gated sources (Lekmanga, Team X, 3asq, Demonicscans) assert seeded captured headers flow into `Page.headers` (the capture→download path). Cold-start (empty store) yields config-only headers — matches the legacy download, not a regression.
- New `DownloadPageSeamParityTest`: the full path with the REAL config (`RegistryChapterPageProvider` → real `DefaultSourceRegistry` from `PILOT_SOURCES_CONFIG_JSON` → engine → `DownloadPage`), asserting page ORDER (Azora scrambled→sorted), absolutization, and headers (Zazamanga Referer).
- **Deferred (next safe expansion, on-device-verify-gated):** the untouched Madara/WordPress family in other languages — Taurusfansub (ES), Raijinscan + Manga Origine (FR), Flowermanga (PT), Timenaight + Webtoontr + Webtoonhatti (TR) — all carry the `wp-manga` signature the engine already handles. The conversion campaign (`AR_SOURCES_CONVERSION_PLAN.md`) only covered AR + EN.
- **Result:** downloads of all 12 config-backed sources are proven to carry correct page URLs, order, and headers.

### Phase 5/6 — Isolate legacy from the active/user-facing flow — **DONE**
- Owner direction: remove legacy from the active/user-facing architecture, keep the legacy CODE in the repo (archived/internal reference); do not delete or rewrite scrapers.
- Catalog/UI: config sync force-disables any enabled non-config row; Home tabs / search-all / active-source resolution are gated to config-backed (`:data`). Legacy sources can't appear as tabs, be searched, or be the active source. (Earlier slice.)
- **Generic-only, no legacy fallback (this hardening slice):** `DefaultSourceRegistry.get()` returns the **bare generic client** for a config-backed source (the `FallbackSourceClient` wrap was removed), and `RegistryChapterPageProvider` **throws** a clear error on a config-backed generic failure (never `null`→legacy). So a config-backed source uses the generic/config-driven engine **only** — a generic failure is a clear error and the legacy scraper is **never executed** across Home / Search / Details / ChapterPages / Downloads. `FallbackSourceClient` + `SourceDebugFlags` are retained-but-unwired (kept for a possible future opt-in per-source fallback); no legacy scraper file touched.

### Deferred (owner decision — not started)
- **Remote API config delivery (the "API later" step):** the port layer is ready (`RemoteConfigSource` interface, `RemoteSourceConfigManager` precedence, durable DB cache, origin-agnostic consumers). To enable: implement `RemoteConfigSource` (Ktor GET of the signed doc) + a real `ConfigSignatureVerifier` (pinned key) replacing `DenyRemoteSignatureVerifier`, flip `remote = null` → the live source, and stand up the signed endpoint. **Deferred.**
- **Remaining legacy→config conversions:** convert the remaining legacy sites to JSON/config one-by-one, on-device-verified (next safe set: the Madara/WP family — Taurusfansub ES, Raijinscan + Manga Origine FR, Flowermanga PT, Timenaight + Webtoontr + Webtoonhatti TR). Hard sources (Dilar AES, MangaPark GraphQL, Promanga/Prochan de-scramble, Mangatuk RSC, Lavatoons inline-JS, Comick/Manhwatop/Batcave CF, Batoto) stay legacy-only. **Deferred.**

---

## D. Honest limits / expectations
- **Fully dynamic (no app update):** source list, enable/disable, priority, baseUrl (+ URL migration), home/search/details/pages/downloads routing, and parsing for every config-backed source. ✅
- **Config-backed = generic-only:** a config-backed source never executes the legacy scraper; a generic failure surfaces as a clear error (no silent legacy fallback). Legacy code/rows are retained but isolated.
- **Still needs an app update / conversion:** the legacy-only hard sources (de-scramble, RSC, AES, GraphQL, CF-unverifiable) until converted or given a compiled escape hatch; and **remote config delivery** until the API path above is built.
- **Risk controls:** the bundled config is the trusted floor + durable DB cache; every change passes the compile gate + tests + locale parity; source conversions require parity tests.
