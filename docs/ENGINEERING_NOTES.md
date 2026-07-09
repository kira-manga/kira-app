# Engineering Notes — durable subsystem deep-dives

> Consolidated 2026-07-04 from the retired working docs (`IOS_BACKGROUND_DOWNLOADS*.md`,
> `IOS_NATIVE_READER.md`, `IOS_NATIVE_COMPRESSION_PLAN.md`, `LOCAL_MANGA_SHARING_PLAN.md`,
> `DEVICE_QA_CHECKLIST_2026-07.md`, the sources campaign docs, `migration/ARCHITECTURE_BASELINE.md`
> §7/§8). Project-state overview and open work: [`HANDOFF.md`](HANDOFF.md). Working rules:
> [`../CLAUDE.md`](../CLAUDE.md).

## 1. Sources subsystem

### Ownership model (since the SourceRegistry endpoint retirement, 2026-07-04)

The bundled JSON config document (`CONFIG_BACKED_SOURCES_JSON` + `CONFIG_BACKED_APIS` in
`composeApp/.../sources/runtime/BundledSourcesConfig.kt`) is the **only authority** for every
source — existence, metadata, baseUrl/imageBase, `siteState`, `lifecycle`, host-migration history,
deep-link trust hosts. Room's `sources` table is a local cache/projection (plus the user-owned
`isEnabled` toggle and priority order); its writer for config apis is the config sync alone
(`SourceCatalogSyncRepositoryImpl`). The legacy remote source-list endpoint (`/source/35` ·
`/dev/source`) is deleted. A source host move now ships as a config edit + app release (accepted
tradeoff until Stage-1 signed remote config: update-speed, not server-speed).

Schema facts: `siteState` (`WORKING|STOPPED|UNDER_MAINTENANCE`), `lifecycle`
(`active|disabled|removed` — the kill switch; `enabled` only means "default-enabled on first
seed"), `previousHosts` (**append-only**; drives the stored-URL alias sweep via `SourceUrlMigrator`
and the push deep-link trust join via `ConfigHostTrust`), `previousImageHosts`, `trustedHosts`.
Mirror protection is authoring-opt-in: a source that declares `previousHosts` gets mirror-safe
baseUrl assertion (a user's repo-settings mirror host outside the declared set survives sync);
without declared history, plain assert-any-difference applies. The 12 generic stanzas' baseUrl is
the generic **engine's** base — legitimately different from the legacy scraper host; cross-system
hosts must never drive migration. `LegacyStanzaCompletenessTest` +
`SourceCatalogSyncRepositoryTest` pin registry⇄config completeness and sync behavior.

### Converted sources (campaign complete)

**12 generic sources** (all verbs through `GenericSourceClient`): Azora, Mangamello, Mangamello
Plus, SwatManga (full 5-verb JSON), Lekmanga, Team X, DilarV2, 3asq (AR), Demonicscans,
Mangabuddy, Zazamanga, Tapas (EN).

**Registry hardening (2026-06-25, Phase 5/6 — verified in code):** `DefaultSourceRegistry.get()`
returns the **bare generic client for config-backed apis — no `FallbackSourceClient`**; a generic
failure surfaces as a failure. `FallbackSourceClient` + `SourceDebugFlags` are retained-but-unwired
rollback material. The owner rule stands: a source is 100% generic or fully legacy — the per-verb
fallback described in older campaign docs no longer exists at runtime. Non-config legacy sources
still route to `LegacyKotlinSourceClient`, are hidden from Home tabs (hidden-not-dropped), and
their DB rows are force-disabled by the config sync. Config cache lives in Room
(`source_config_cache`, added in `Migration_10_11`).

**Permanently legacy-only** (pure-config impossible under the no-source-specific-Kotlin rule; the
legacy `:sources:legacy` scrapers serve them): Dilar (AES-encrypted), MangaPark AR+EN (GraphQL),
Promanga/Prochan (canvas de-scramble + per-item CDN host), Mangatuk (rebuilt Next.js/RSC SPA —
its legacy adapter is itself broken vs the new site), Lavatoons (CF + inline-JS `ts_reader`),
Comick (API hosts dead/CF), Manhwatop, Batcave (CF), Batoto (connection-fail).

Reusable engine features built during the campaign (each has a golden test in `:sources:engine`):
separated `chapters` endpoint (two-request details), comma-fallback template vars, POST_JSON body,
JSON list-filter, `{root:path}`/`{root:__dir}` vars, conditional list-root coalesce, detail
chapter-pagination (HTML max-page + JSON has_next), regex-extract, `script-json`
(`__NEXT_DATA__`) format, per-verb field overrides, drop-un-navigable-chapters, home-feed dedup,
`blacklistGenres`, `{pageOffset}`.

Deferred engine features that would extend coverage if built: Arabic/relative date strategy,
per-item image host (Promanga/Prochan covers), response-root page var refinements.

**Next safe conversion set** (from the 2026-06-25 migration plan — the AR/EN campaign never
covered these locales): the Madara/WP family — Taurusfansub (ES), Raijinscan + Manga Origine (FR),
Flowermanga (PT), Timenaight + Webtoontr + Webtoonhatti (TR).

### How to convert a source

> **The full lifecycle guide is [`sources/ADDING_SOURCES.md`](sources/ADDING_SOURCES.md)** —
> authoring, api-immutability rules, endpoint requirements, validation, testing, domain moves,
> retirement policy (`disabled` → `removed`, never silent deletion), and troubleshooting. The
> steps below are the short form.

1. Author its `SourceConfig` (`engine="generic"`) in `CONFIG_BACKED_SOURCES_JSON`, deriving fields
   from the legacy parser in `sources_repositry/` (read-only spec); add the api to
   `CONFIG_BACKED_APIS`.
2. Add a `*PilotParityTest.kt` in `:composeApp` commonTest with real captured HTML/JSON fixtures.
3. Any new engine capability needs a golden test in `:sources:engine` plus a
   `DefaultStrategyRegistry` whitelist entry.
4. **Safety rule**: every verb's generic output must be verified correct before the source ships —
   since the Phase 5/6 hardening there is **no runtime fallback** for a config-backed source, so a
   wrong-but-`Success` verb (empty chapters/pages are the classic trap) is a visible regression.
   Convert a source only when ALL its verbs are provable (100% generic or fully legacy).
5. Kermit diagnostics run under tag `GenericSourceTest`.

Fail-closed posture (do not weaken): configs are data-only; only strategy names compiled into
`DefaultStrategyRegistry` may be referenced (image-strategy set intentionally EMPTY);
`DefaultSourceConfigValidator` rejects unknown references; remote config fetch disabled
(`remote = null`) and `DenyRemoteSignatureVerifier` rejects all signatures. Signed remote delivery,
image strategies, `minAppVersion` gating = Stage-1/2 roadmap.

Known open question (never adjudicated): `GenericSourceClient.isBlacklistedByGenre` uses
case-insensitive **substring** contains vs the native exact-set membership — could over-filter
generic feeds; needs live per-source genre data to decide.

## 2. iOS background downloads

Engine: `BackgroundUrlSessionDownloadRepository` (`:data:download` iosMain) — the iOS default.
Rollback flag: `DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED` (OFF path binds the legacy
in-process `CoroutineDownloadRepositoryImpl`, kept in nonAndroidMain — also the Desktop engine).
Android is untouched by all of this: WorkManager `DownloadWorkerV2` + `ChapterDownloadService`.

Architecture: background `NSURLSession` moves bytes while the app is suspended but cannot run CPU
work; OS-granted task windows provide the CPU (iOS 26+: `BGContinuedProcessingTask`, id
`me.manga.kira.download.continued`, with system Live Activity — **must be submitted while the app
is still foreground** the moment work becomes pending; submitting at `didEnterBackground` races
suspension. Pre-26: opportunistic `BGProcessingTask`, id `me.manga.kira.download.processing` — a
large queue may not fully drain in background, honest limitation). Components:
`ChapterPageResolver` (page-URL/header resolution), `ChapterFinalizer` (CBZ + bookkeeping,
idempotent), `DownloadManifest`/`Store` (`chapter_<id>/manifest.json`), `BackgroundReconciler`
(pure, unit-tested), `:platform` `BackgroundTransport`/`BackgroundScheduler`/`BackgroundWorkSignal`,
Swift `AppDelegate` + `IosBackgroundBridge.kt`.

Guarantees: transfers handed to URLSession complete/resume across suspension and termination;
queue/page/attempt state persists across Room + manifest + the URLSession task list (three-way
reconcile at launch); bounded retry (3 attempts, exp backoff 2 s/4 s cap 30 s); atomic
`.cbz.part` → `.cbz` rename (interrupted encodes re-run, never corrupt). Force-quit cancels
in-flight transfers and does not relaunch — **OS rule, not a bug**; next manual launch reconciles
(pages on disk kept, missing re-enqueued). Best-effort only: background scraping of further
chapters, BG task grant timing.

**Notification rule (load-bearing UX)**: user-facing "complete" means the CBZ exists. Silent
per-page progress → silent "Finalizing chapter…" at `RUNNING→DOWNLOADED` → banner+sound only at
`finalize.success`. Foreground presentation via `willPresent`: `DOWNLOAD_PROGRESS` silent,
`DOWNLOAD_DONE` banner+sound.

Cancel semantics (device-verified 2026-07-04): the chapter is marked readable at transfer-complete
(before "Finalizing…"), so every cancel entry point — including `cancelAllDownloads`, which is what
the Downloads UI routes through (log key `cancel.all`) — must revert that bookkeeping
**synchronously** (`ChapterFinalizer.revertReadable`); file/manifest deletion defers only to an
in-flight encode and completes post-encode (`finalize.cancelledCleanup`). Decision table:
`FinalizeRules.cancelCleanup(state, encodeInFlight)` → REVERT_AND_DELETE_FILES / REVERT_ONLY /
DELETE_PARTIAL_PAGES (QUEUED/RUNNING cancel drops this cycle's partial pages surgically, keeping a
previous download's `.cbz`) / NONE. `FinalizeRulesTest` pins the matrix — extend it for any change.

Debugging vocabulary: all engine logs carry tag `KiraBgDownload` with structured keys
(`enqueue.*`, `state.transition`, `manifest.*`, `reconcile.plan|enqueue`, `task.*`, `file.move.*`,
`notif.*`, `finalize.*`, `cbz.*`, `retry.*`, `cancel.*`, `scheduler.*`, `bgtask.*`, `lifecycle.*`).
Device tests MUST run on a real iPhone (Simulator doesn't exercise suspension/BGTaskScheduler/
relaunch); stream via Console.app filtered on `KiraBgDownload` — Xcode detaches on suspend.
Perf instrumentation `BgDownloadLog.DLPERF` is default-off (zero cost off).

Cloudflare limitation: 429/403 during background prep can't show the WebView solver → defers to
foreground; prefetch pauses 10 min on ANY prefetch failure while real resolves continue.

## 3. Native iOS reader

The shipping iOS reader is Swift UIKit (`iosApp/iosApp/NativeReader/`) driven by the **shared**
KMP `ReaderViewModel` — a pure renderer; page resolution, resume, history, mark-read, bookmark,
reading-mode persistence, cross-chapter append are byte-identical to the Compose reader (kept as
rollback behind `IosReaderFlags.NATIVE_READER_ENABLED`, `composeApp/iosMain/.../reader/`).
Why it exists: Compose-MP iOS strains on ~9k-px Skia strip textures (slow drag barely moves,
fling coasts); Android is unaffected.

Bridge: `ReaderNativeBridge.kt` (`:composeApp` iosMain) streams a flat stdlib-only
`IosReaderSnapshot` DTO; Swift registers the VC factory from `iOSApp.swift`;
`ReaderHostSwitch` expect/actual routes iOS → native when flag + factory present.

Load-bearing gotchas:
- **Chrome is UIKit bars, not a SwiftUI overlay** — a full-screen `UIHostingController` overlay
  eats scroll/tap in transparent regions. Hidden bars set `isUserInteractionEnabled = false`.
- **Zoom**: paged = per-page `UIScrollView` (Photos-style, decodes at 2.5× screen width).
  Continuous = collection view nested in an outer horizontal scroll view — zoom makes the strip
  genuinely wider (no transform; virtualization preserved); **both scroll views are disabled
  during an active pinch** (avoids the classic webtoon pinch-scroll bug). Max zoom 2.5×;
  visible pages re-decode at zoomed width on pinch-end.
- Scroll-driven page tracking fires only for user scrolls, so programmatic resume/seek never
  clobbers the page index.
- Memory bounds (2026-07-04): decode concurrency gated by `DispatchSemaphore(3)` in
  `ReaderImageLoading.swift`; decoded-image `NSCache` is RAM-tiered 64/128/256 MB
  (<2 GB / ≤4 GB / >4 GB physical); long-edge decode cap `maxPixelDimension = 12000 px`
  (do NOT lower it — quality; true tiling/segmentation is the documented follow-up if OOM
  appears). CATiledLayer deliberately not used (ImageIO can't region-decode JPEG/WebP).
- Strings resolve from compose-resources in `ReaderHostSwitch.ios` and are handed to Swift;
  Swift-side `ReaderStrings` + notification strings resolve via NSBundle → they catch up on next
  launch after a live language switch (by design).

## 4. iOS CBZ compression (libwebp)

Format decision (LOCKED 2026-06-29): **WebP on both Android and iOS** (HEIC rejected — poor
decode off-Apple breaks planned sharing/export; JPEG rejected — generation loss). Apple ImageIO
cannot *encode* WebP, so `platform/libs/libwebp/` vendors libwebp 1.5.0 (static, ios-arm64 +
ios-arm64-simulator) via cinterop (`platform/src/nativeInterop/cinterop/libwebp.def`).
`IosLibWebpEncoder.kt`: ImageIO decode → CoreGraphics-native RGBA buffer (never a K/N ByteArray)
→ vertical banding by pointer → `WebPEncodeRGBA` per band → `WebPFree`. Root cause it fixed:
K/N GC stop-the-world during Skia encode = 274–566 ms main-thread stalls; libwebp path = **zero**
stalls on device. Banding stays regardless of encoder (WebP 16383-px format cap + reader
crispness under the 12 kpx decode cap). A/B flag `IosWebpEncoderFlags.USE_LIBWEBP` (default true);
linkage pinned by `IosLibWebpLinkageTest` (iosTest). Desktop keeps skiko. Standing risks: RGBA
stride mismatch = color corruption; ~190 MiB native transient per page → encode strictly
sequential, release CGImage/context promptly; manual `WebPFree` discipline (leaks grow native
memory across chapters).

## 5. Image pipeline — load-bearing fixes (DO NOT BREAK)

(From the retired `migration/ARCHITECTURE_BASELINE.md` §7, updated 2026-07-04.)

- The singleton `ImageLoader` (App.kt) sets `maxBitmapSize(Size.ORIGINAL)`; loader-level settings
  do **not** propagate to per-request Options, so tall-webtoon requests must handle their own cap.
- Reader page requests: decode **width** capped at window-width × 2.5 zoom headroom
  (`readerDecodeMaxWidthPx`, `ui/.../reader/internal/ReaderDecodeCap.kt`); **height must stay
  `Dimension.Undefined`** — a Pixels height reintroduces Coil's aspect-driven width collapse
  (~234 px blur on tall strips). Coil applies the two axes independently (verified against
  Coil 3.5.0 sources).
- Android: `allowHardware(false)` + `RGB_565` hints stop cache-eviction blur; explicit OkHttp
  `NetworkFetcherFactory` because the ktor+okhttp ServiceLoader pick was nondeterministic;
  AVIF decoder Android-only.
- iOS/Desktop decode via `HighQualitySkiaImageDecoder` (CATMULL_ROM). Per-source auth headers via
  `CoilSourceHeaderInterceptor`.
- Landmine: `shouldConstrainImageSizeToScreen()` must stay `true` on all platforms.

## 6. Persistence warnings

- `MangaDatabase` is at **v11**; never change existing column names/types/indices — user installs
  depend on the bit-identical wire format. New state prefers new tables/columns with defaults, plus
  a migration.
- The 4→5 migration is deliberately spelled `Migration_4_5` (native-source parity) — don't "fix" it.
- Room KSP is registered **per target** in `:data:local`; a single common `ksp(...)` generates
  nothing.
- Some legacy DAO names are intentionally spelled `...Deo` (native parity, not typos).

## 7. Device QA checklist (pre-store-submission; consolidated 2026-07-03)

All open except Q6. Run on real hardware.

- **Q1 — iOS background downloads resolve-ahead**: real iPhone, Release/TestFlight. Queue 5+
  chapters, background during ch1 → ch2–4 complete in background (`prefetch.manifest.written`).
  Forced CF/403 mid-run → prefetch pauses 10 min while real resolves continue. Kill mid-batch →
  relaunch reconciles RUNNING/COMPRESSING orphans to QUEUED.
- **Q2 — Android R8 production-key smoke**: `:app:assembleRelease` with the real keystore (online
  resolve); on-device tap-through; watch Logcat for R8-only crashes.
- **Q3 — Feel pass**: fast-scroller thumb (Library + Details; light+dark, RTL); native reader
  paged zoom / 3 s chrome auto-hide / scrubber across appended chapters / swipe-past-last-page
  advance / share captures centered page / exact-boundary resume; Arabic reader error strings.
- **Q4 — Push E2E** (after APNs `.p8` + real plists): cold-start tap → Details; warm tap →
  single instance; deferred during onboarding; wrong-host url → rejected, app opens normally.
- **Q5 — Logging distribution**: TestFlight has `BgDownloadLog.VERBOSE`, App Store must not.
- **Q6 — RESOLVED in code** (QUEUED spinner visibility; `DownloadAffordanceVisibilityTest`).
- **Q7 — iOS live language switch**: Arabic → immediate re-resolve + RTL mirror + Arabic-Indic
  digits; if `AppleLanguagesLiveSwitchContractTest` ever fails, flip
  `LocalAppLocale.ios isLiveLocaleSwitchSupported` back to false.

## 8. Local manga sharing (deferred feature — plan only, no code)

Owner-approved direction (2026-07-03): LAN sharing between Kira instances over **P2pKit**
(`dev.p2pkit:*` 0.6.0, Bonjour `_p2pkit._tcp`), consumed via Maven (never `includeBuild` — AGP
composite conflict). New leaf module `:data:share` (the only P2pKit dependent) + `:domain` ports +
MVI `share/` feature + `Screen.Share` route. Versioned JSON control protocol
(`share.hello/offer/offer.response/chapter.result/complete/imported/cancel`), chapter-granular
resume, per-chapter SHA-256 + byte-count verify, staging under `share_staging/<shareId>/` with
atomic rename, import through the existing `LibraryRepositoryImpl.addToLibrary` +
4-write downloaded-marking chain — **receiver-local ids; no received path is ever transplanted;
Room stays v11**. v1: downloaded chapters only, always CBZ, foreground-only, plaintext
(consent-gated). Android needs `ACCESS_WIFI_STATE`+`CHANGE_WIFI_MULTICAST_STATE`; iOS needs
`NSLocalNetworkUsageDescription` + `NSBonjourServices`. Open owner decisions: P2pKit distribution
(mavenLocal vs private repo), upstream Kotlin/AGP alignment, plaintext v1 acceptance, entry-point
set, metadata-only sharing. Phase P0 go/no-go = publish 0.6.0 + empty module + two-instance JVM
loopback smoke.
