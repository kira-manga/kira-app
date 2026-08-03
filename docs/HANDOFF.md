# Kira Manga — Project Handoff

> Written 2026-07-04 and release-prep updated 2026-07-18 for an incoming agent/developer with zero prior context. This is the
> project-state document: what the app is, where every subsystem stands, the rules that bite, and
> what's still open. Companion docs: [`ENGINEERING_NOTES.md`](ENGINEERING_NOTES.md) (subsystem
> deep-dives), [`../CLAUDE.md`](../CLAUDE.md) (working rules, build commands, gotchas — read it
> before touching code), [`ARCHITECTURE_REWORK_CONTRACT.md`](ARCHITECTURE_REWORK_CONTRACT.md)
> (the owner's verbatim contract; it wins over habit). Update THIS file when project state changes
> materially — it replaced the ~140 historical campaign docs on 2026-07-04 (originals live in the
> predecessor repo `Apdelrahman1911/yami-kmp`, which also holds the full pre-2026-07 git history;
> this repo starts fresh at a single initial commit).

## 1. What the app is

A manga reader built with **Kotlin Multiplatform + Compose Multiplatform**, born as a 100%
behavior-parity port of the native Android app "Yami Manga" (vendored read-only at `native-app/`
as the parity spec) and since rebranded:

- Display name: **Kira Manga** · package root `me.manga.kira.*` · Android `applicationId` and iOS
  bundle id `me.manga.kira` · version **1.0.0**.
- Feature set: multi-source manga browsing (Home tabs per source), search, details, a
  webtoon/paged reader, library with categories, chapter downloads (CBZ), reading history,
  updates feed, statistics, complaints (Firestore-backed; internal-test/public-release blocker), what's-new, theming (incl. AMOLED),
  11-locale i18n (RTL-ready), push notifications with deep links, in-app messaging.
- **Android and iOS are the shipping targets.** Desktop (JVM) compiles, runs, and is CI-gated,
  but is explicitly out of the current release scope — do not spend effort on Desktop-only gaps
  unless asked.

## 2. Architecture

Clean architecture + strict MVI, 16 Gradle modules. Dependency direction (never violate; full
table with per-module roles in `CLAUDE.md`):

- `:core` (leaf: `AppError`/`AppResult`, dispatchers, logging SPI) ← `:domain` (entities,
  repository interfaces, one-verb use cases; pure Kotlin) ← `:data` (repository impls, mappers)
  ← `:presentation` (MVI ViewModels, no Compose types) ← `:ui` (Compose screens/theme) ←
  `:composeApp` (aggregator: navigation, DI bootstrap, sources composition root, Coil singleton).
- Leaf infra modules `:data` depends down onto: `:data:local` (Room v11), `:data:remote` (Ktor),
  `:data:download` (chapter-download engines), `:sources:legacy` (~50 hand-written scrapers +
  legacy models), `:platform` (platform facades as plain interfaces + per-target impls).
- Config-driven sources subsystem: `:sources:contracts` / `:sources:engine` / `:sources:config`
  (engine and config must never depend on each other; they meet at contracts).
- Hosts: `:app` (Android application host), `:desktopApp` (Desktop entry), `iosApp/` (Xcode host
  project, NOT a Gradle module — generated via `xcodegen generate`, `.xcodeproj` is gitignored).

MVI pattern (`MviViewModel<State, Intent, Effect>` in `:presentation`): immutable State data
class, sealed Intent with exhaustive reducer, one-shot Effects via `Channel(UNLIMITED)`; a fresh
coroutine per intent (use the cancel-before-relaunch `Job?` pattern when ordering matters);
fire-and-forget work inside handlers must use `launchSafely {}` (a bare `viewModelScope.launch`
escapes the crash net). ViewModels consume use cases, never repositories. Errors are typed
`AppError`, translated to text only in `:ui`. Navigation lives only in `:composeApp`.

The legacy `:shared` module was fully strangler-fig-retired and **deleted** (2026-07); relocated
files deliberately kept their original package names (cosmetic-only debt, do not "fix" without
owner sign-off — high import churn, zero behavior value).

## 3. Platform status

| Area | Android | iOS | Desktop |
|---|---|---|---|
| App shell / all screens | ✅ | ✅ | ✅ |
| Reader | Compose (decode-capped) | **Native UIKit reader** (Compose fallback) | Compose |
| Downloads | WorkManager engine | Background URLSession engine | In-process coroutine engine |
| Background library refresh | ✅ periodic WorkManager | ✅ BGAppRefreshTask | ✗ unwired |
| Firebase Analytics + Crashlytics | ✅ | ✅ (Release-only collection) | ✗ |
| Push (FCM) + deep links + FIAM | ✅ | ✅ (owner console steps pending) | ✗ |
| Ads / consent / in-app update / review | No ads/AD_ID; Play update + review | No ads/IDFA; StoreKit review + App Store listing | No product work |
| Cloudflare WebView solver | ✅ | ✅ | Windows/Linux only (macOS KCEF upstream-broken) |
| AVIF decode | ✅ native | ✅ ImageIO | ✗ unsupported |

## 4. Sources (the content backbone)

- **12 config-driven generic sources** in the revision-6 bundled floor: Azora, Mangamello,
  Mangamello Plus, SwatManga, Lekmanga, Team X, DilarV2, 3asq, Demonicscans, Mangabuddy,
  Zazamanga, Tapas. These run **generic-only**. The bundle contains no legacy stanzas.
- The 33 unconverted sources are unavailable. The runtime scraper set is empty, and there is no
  fallback, inference, or union that can reactivate an api missing from the authoritative catalog.
- The legacy SourceRegistry endpoint (`/source/35`) remains deleted. The app now consumes the
  backend's `/api/v2/source-config/manifest` and immutable per-source endpoints through a bounded
  HTTPS client. It authenticates exact checksums, revision-chain metadata, manifest and source
  signatures with an app-pinned X.509 public key; unchanged manifests return 304, and only missing
  active revisions are downloaded. A candidate activates atomically only after full verification.
  Network, HTTP, signature, persistence, or validation failure preserves the complete last verified
  cache or bundled floor. Release builds set the origin with `KIRA_SOURCE_CONFIG_BASE_URL` or
  `-Pkira.sourceConfigBaseUrl` and pins with `KIRA_SOURCE_CONFIG_PINNED_KEYS` or
  `-Pkira.sourceConfigPinnedKeys`; Android release assembly fails when either is absent.
- A new source becomes available only after generic conversion, parity validation, review, and
  explicit backend publication. Do not expose a legacy implementation during migration.
- Settings → Source catalog exposes read-only provenance and version diagnostics for the active
  tier. It reports bundled, reverified-cache, or backend origin; catalog/signature metadata; and
  the immutable revision and checksum of every active source without exposing payloads or keys.

Conversion guide, ownership invariants, and the next-safe conversion set (ES/FR/PT/TR Madara
family): `ENGINEERING_NOTES.md` §1.

## 5. Reader

- **Android/common (Compose)**: webtoon + paged modes. Decode width is capped at window-width ×
  2.5 zoom headroom (`ReaderDecodeCap.kt`); height must stay `Dimension.Undefined` or tall strips
  collapse to ~234 px blur. `RGB_565`/`allowHardware(false)` hints and the singleton loader's
  `Size.ORIGINAL` are load-bearing (see `ENGINEERING_NOTES.md` §5).
- **iOS (shipping)**: native UIKit reader driven by the *shared* `ReaderViewModel` — built because
  Compose-MP iOS stutters on ~9k-px strip textures. Decode concurrency semaphore(3), RAM-tiered
  cache, 12 kpx long-edge cap, pinch-zoom design that avoids the classic webtoon pinch-scroll
  bug. Compose reader remains as rollback (`IosReaderFlags.NATIVE_READER_ENABLED`). Details +
  UIKit gotchas: `ENGINEERING_NOTES.md` §3.
- Recent hardening (2026-07-04, device-relevant): decode caps above; reader behaves identically
  in webtoon and paged modes (unit-pinned).

## 6. Downloads

Per-platform engines behind one `DownloadRepository` interface (`:data:download`):

- **Android**: WorkManager `DownloadWorkerV2` + foreground `ChapterDownloadService`; rotation-safe
  (device-verified); enqueue dedup; cancel sentinel `__cancelled_by_user__` prevents spurious
  fail alerts.
- **iOS**: background `NSURLSession` engine + BG task CPU windows; CBZ finalize with atomic
  rename; three-way crash reconcile; **cancel semantics were the 2026-07-04 device-verified fix
  family** — every cancel path synchronously reverts readable-bookkeeping, defers file deletion
  only to an in-flight encode, and drops mid-transfer partial pages surgically
  (`FinalizeRules.cancelCleanup` is the pinned decision table). "Complete" notification =
  CBZ-on-disk, never earlier. iOS CBZ encode uses vendored **libwebp** (zero main-thread stalls
  vs Skia's 274–566 ms). Full architecture + log vocabulary + test plan:
  `ENGINEERING_NOTES.md` §2/§4.
- **Desktop**: in-process coroutine engine (also the iOS rollback path).

## 7. Firebase / push / crash reporting

Same Firebase project across Android + iOS (BOM 34.15.0). Config files are BYO/gitignored with
committed `*.example` templates — `app/google-services.json`, `iosApp/iosApp/GoogleService-Info.plist`.

- **Crashlytics**: Android uses the SDK default handler (a custom handler is FORBIDDEN — a past
  one recorded fatals as non-fatals and lost them); Kermit → breadcrumbs, release log floor Warn
  (legacy scrapers log cookies/HTML at Info). iOS is Release-only; Kotlin fatals via CrashKiOS
  with a **hand-rolled** exception hook + `exitProcess(0)` (the stock CrashKiOS hook collapses
  every Kotlin crash into one generic issue — do not switch back). iOS Release builds hard-gate
  on the dSYM upload.
- **Push (FCM)**: shipped on both platforms with data-payload deep links (manga/reader/updates)
  through the shared `me.manga.kira.navigation.push` seam (`PushPayloadParser` →
  `NotificationRouter`); deep-link host trust is fed by the sources config (`ConfigHostTrust`) +
  `PushDeepLinkTrust` (coverUrl included). **Real delivery still needs owner console steps**:
  enable Push on the App ID, upload an APNs `.p8` to Firebase, real (non-placeholder) config
  files, then run device E2E (QA Q4).
- **FIAM**: SDK wired on both platforms (owner allows it everywhere incl. the reader); no
  campaigns authored yet — content only appears once the owner creates campaigns in the console.
- iOS signing: Debug and Release use `iosApp.entitlements` under the Developer Program team;
  Debug expands `aps-environment=development`, Release uses `production`. The no-push file remains
  only as an explicit Personal-team contributor fallback.

## 8. CI / branches / release

- **Branch policy (owner rule, encoded in `.github/workflows/ci.yml`): Actions NEVER run on
  `main`.** Triggers are pushes to **`testing`** and **`release`** plus manual
  `workflow_dispatch` — no `pull_request`, no tag triggers. Don't add them back.
- CI jobs: `jvm-android` (compile matrix + 12 module desktopTest suites + both locale-parity
  gates + `:app:testDebugUnitTest` + installable debug APK), `ios` (both iOS klib compiles plus
  arm64 Release framework, macOS runner),
  `static-analysis` (ktlint 1.5.0 + detekt 1.23.7 standalone CLIs, **blocking** against committed
  baselines under `config/`), `release-verify` (always verifies unit/lint/R8/unsigned APK+AAB and
  optionally emits a signed AAB), and `ios-archive` (optionally creates a signed archive+dSYMs).
- Android signing is environment-only. The Internal testing workflow requires
  `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`, real `GOOGLE_SERVICES_JSON`, and `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`;
  iOS archive output requires its certificate/profile/plist secret set. Missing Android release
  inputs fail that publishing workflow before any artifact is uploaded; the general CI build
  verification remains separate.
- AdMob, mediation, UMP, Android `AD_ID`, and Privacy Sandbox advertising-ID/attribution
  permissions were removed because the app has no advertising UI; CI guards the merged manifest.
- Store submission is gated on `docs/release/INTERNAL_RELEASE_QA.md`; no final signed-device suite
  has been completed yet. Configuration/manual actions: `docs/release/RELEASE_CONFIGURATION.md`.

## 9. Toolchain (2026-07)

Kotlin **2.4.0** · Compose Multiplatform **1.11.1** · AGP **9.2.1** (all KMP modules on the
new-DSL `com.android.kotlin.multiplatform.library`; `:app` on AGP built-in Kotlin; AGP-10-ready,
the version bump itself pending AGP 10's release) · Gradle **9.6.1** · compileSdk **37** / minSdk
26 · targetSdk 36 · JVM 11 (Android) / 17 (Desktop; non-JBR JDK required) · Room KMP (DB v13) · Ktor
(OkHttp/Darwin/CIO) · Koin · Coil 3.5 · Kermit · Firebase BOM 34.15.0 · iOS targets `iosArm64` +
`iosSimulatorArm64` only (no x64) · Xcode project via **xcodegen**. Machine gotcha: SDK 37
installs as `platforms/android-37.0` but AGP wants `android-37` — symlink needed on fresh
machines (see `CLAUDE.md`).

## 10. Things an incoming agent MUST understand

1. **Read `CLAUDE.md` first** — build commands (prefer `--offline`), test task names
   (`desktopTest`, single-variant `compileAndroidMain`), i18n rules (new keys → own
   `strings_pfix_*.xml` file, ALL 11 locales or the parity gate fails), string-accessor imports,
   spacing/icon conventions.
2. **Read-only paths**: `native-app/` (always), `sources_repositry/` (spec; edit only on explicit
   instruction). Owner-WIP untouchables even when dirty: `ui/.../home/HomeScreen.kt`,
   `sources_repositry/ar/mangalek/MangaLekRepositoryv2.kt`, `sources_repositry/data/MangaSource.kt`.
3. **Lint discipline**: ktlint + detekt run blocking against baselines; editing a baselined file
   resurfaces its old findings in both tools — fix them for real, never re-baseline to silence a
   new finding.
4. **KDoc postscripts are noise**: huge machine-generated audit-trail blocks, often citing
   deleted files. Assert against source, not KDoc — several KDocs still describe retired postures
   (e.g. "Stage-0 dark" sources comments).
5. **Fail-closed sources posture must not be weakened** (§4); Room wire format is frozen
   (`ENGINEERING_NOTES.md` §6); `AppResult`/`AppError` at boundaries, never `kotlin.Result`
   (except deliberate legacy-parity slices); `CancellationException` is always re-thrown.
6. Every commit to `main` in the predecessor repo ran the compile gate + module desktopTests +
   `:app:testDebugUnitTest` + lint before push — keep that cadence here (CI only fires on
   `testing`/`release`).

## 11. Known deferred areas (owner-decided; do not "fix" unprompted)

- **Complaint authorization (CRITICAL public-release blocker)**: the clients have no authenticated
  user/admin identity, deployed Firestore rules are absent from this repository, and debug-gating
  `Admin.isAdmin` is not server authorization. Keep internal test data disposable; secure the
  service or disable the public feature per `docs/release/COMPLAINT_PRODUCTION_DECISION.md`.
- **Manga sharing (P2pKit)**: plan-only (`ENGINEERING_NOTES.md` §8); five open owner decisions.
- **Desktop**: DB directory migration, background refresh, KCEF bundling for macOS, AVIF — all
  parked (Desktop unshipped).
- **Package-rename pass** for relocated `:shared` files — deliberately not done.
- Sources follow-ups: image strategies and `minAppVersion` gating. Signed remote delivery is
  implemented; production activation still needs the deployed backend HTTPS base URL.
- C2 accepted cross-platform gaps (from the retired inventory): new-chapter **system**
  notifications are Android-only (`NotificationPresenter` has zero call sites); inline What's-New
  video is poster-only everywhere; Material You dynamic color is a no-op everywhere; in-app update
  is Android-only while review is implemented on Android and iOS; Desktop app-version string is a
  hardcoded stub.
- Downloads paging: Room-KMP can't generate `PagingSource`; the in-memory list stands
  (perf-only concern).
- ~2,072 LLM-translated strings never human-reviewed (worst risk: ja/ar/ru).
- Smaller parked items (each needs an owner call or the owner-WIP `HomeScreen.kt`):
  Home-tab-reselect scroll-to-top (blocked on `HomeScreen.kt`); the new-sources badge is fully
  plumbed but its final wiring line (`showNewBadge = state.hasNewSources`) sits in the owner-WIP
  `HomeScreen.kt`; manhastro `/dados` offline cache (needs a disk-backed Ktor `HttpCache`; owner
  already decided disk-backed bounded caches for iOS/Desktop — unimplemented); COMPRESSING rows
  are invisible in the Downloads tabs (owner call); WhatsNew fallback copy (owner copy); WebView
  sandbox allows unparseable main-frame URLs (owner call — changing it alters legacy behavior);
  iOS download progress-stall watchdog (design after device QA; deliberately no
  `timeoutIntervalForResource`); an Italian MangaPark stub in `sources_repositry/` was never
  un-stubbed (moot while the MangaPark family is legacy-only).
- Standing owner policies worth knowing: never wipe user settings on an upgrade;
  delete-downloaded must physically delete files on ALL platforms; strict native parity is not
  required where a better approach exists (recorded owner ruling).

## 12. Remaining risks / follow-ups

1. **Complaint rules/identity hole** — see §11; this blocks public release.
2. **Device QA checklist not yet run** on final builds; use the authoritative
   `docs/release/INTERNAL_RELEASE_QA.md` for Android/iOS, website/deep-link, backup, and compliance evidence.
3. **Owner console steps for push**: APNs key upload + real config files (then Q4).
4. **Release secrets** in GitHub (§8) so `release-verify` actually exercises the signed path.
5. **Never-audited subsystem**: the WebView/Cloudflare solver stack has never had a dedicated
   review despite being load-bearing for CF-protected sources.
6. **Un-adjudicated**: generic-engine genre blacklist uses substring matching vs native's exact
   set — needs live genre data; could over-filter feeds.
7. **Never device-proven**: navigation process-death restoration of `List<String>` route args
   (Reader `paths`, Details `genres`) through the JetBrains nav port.
8. **Release-process rule**: the adult-content gate must be smoke-tested on every Details entry
   point (incl. cache-first opens) before any store release.
9. iOS reader loader changes (decode gate + cache tiers, 2026-07-04) are simulator-verified;
   recommend one device pass on fast webtoon fling on a low-RAM iPhone.
10. iOS ATS uses a scoped `raijinscan.fr` exception rather than global arbitrary loads; confirm the
    source still requires it and document/remove it when possible.
11. Android Auto Backup/device transfer excludes all app persistence domains so DB/settings/manga
    files cannot be restored inconsistently; Kira ZIP import is the supported restore mechanism.
12. FIAM has no campaigns; push has no server sender yet — both silently inert until owner acts.
13. The signed source-config client is fail-closed and compiled for Android/iOS, but the production
    backend HTTPS origin and signing ceremony are not configured; set `KIRA_SOURCE_CONFIG_BASE_URL`
    and `KIRA_SOURCE_CONFIG_PINNED_KEYS` in the release environment only after the backend's protected
    private key and matching public key exist. No orphan placeholder pin is accepted.
