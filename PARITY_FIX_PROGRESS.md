# Parity Fix Progress — Native Android → KMP

Tracks the remediation of parity issues from **`NATIVE_VS_KMP_PARITY_AUDIT.md`** (681 findings: 🔴13 Critical · 🟠98 High · 🟡202 Medium · ⚪368 Low). Native Android app = source of truth. Work branch: **`parity-fixes`**.

**This file is updated after every fix.** It is the single source of truth for what is done / pending / blocked. You can stop at any time and read this file to know exactly where things stand.

---

## 🔽 Download deep-fix (user-reported, 2026-06-02) — restart-resume, completion-atomicity, size, desktop CBZ

Investigated native vs KMP download flow with a parallel agent workflow, then fixed + adversarially reviewed (5-dimension review workflow) + runtime-verified on desktop.

- **Restart freeze (BUG 1) — interrupted downloads no longer stick.** On process death a download was left `RUNNING`/`COMPRESSING` in Room forever (the worker only pulls `QUEUED`), so it showed stuck "downloading" on reopen. Native has the same bug — fixed everywhere instead of replicated. New `ChapterDownloadDao.reEnqueueInterrupted(excludeChapterId)` resets orphaned rows → `QUEUED`; `DownloadRepository.reconcileInterruptedDownloads()` (Android re-posts the unique WorkManager job with **`APPEND_OR_REPLACE`** — not KEEP, which could drop the re-enqueue and re-freeze; iOS/Desktop wake the in-process worker loop, excluding the row legitimately running in this process); surfaced via `DownloadsActionRepository.reconcileInterrupted()` → `ReconcileDownloadsUseCase`, called once at startup in `App.kt` (in its own coroutine, parallel to source-refresh so it fires immediately). ✅ Runtime-verified: chapters 33/32/35 each auto-resumed on relaunch.
- **Completion freeze (BUG 2) — row flips downloading→downloaded atomically.** `DownloadedChapter` now carries `url`, so `DetailsViewModel` joins downloads to displayed chapters **by url synchronously** (no `ChapterIdResolver` round-trip) and keeps the SUCCESS row in the map — the `RUNNING→SUCCESS` flip lands in one `updateState`, removing the old two-source race (no leave/re-enter needed). +regression test. ✅
- **Chapter size display (native parity).** New `chapter_downloads.sizeBytes` column (Room **v8→v9**, `MIGRATION_8_9`, schema `9.json` regenerated); computed once at SUCCESS via `AppFileSystem.folderSize` in both engines (and back-filled for pre-v9 rows by the startup reconcile); flows through `DownloadedChapter` → `DetailsState`; rendered per-chapter ("MMM d • 12.3 MB", primary tint) + a per-manga total header ("`<total>` • `<N>` downloaded"). Formatter `:core/formatBytes` (round-half-up, native `%.1f` parity). Header count + size derive from the same SUCCESS set so they never desync. ✅ Runtime: 8.2 MB / 7.9 MB captured + persisted.
- **Desktop CBZ now works (bonus, surfaced by the smoke test).** `DesktopCbzWriter` re-encoded pages via ImageIO, which has no WebP decoder → every page `FAILED DECODE` → **0-page archives** (downloads silently unreadable on desktop). Rewrote it to store page bytes **verbatim** as a STORE-method zip (lossless, like iOS; the Skia reader decodes WebP), with fail-fast + partial-`.cbz` cleanup so a mid-write failure can't inflate the captured size. ✅ Runtime: produced a valid **18-page** readable CBZ (`RIFF…WEBP` magic intact).

Verified: 3-target compile green; `:domain`/`:data`/`:presentation` desktopTest pass (10/10 Details, 0 failures); dup-resource + comment-hazard gates clean; desktop launch smoke (3 runs) — DI resolves, 0 exceptions, reconcile-resume + size + readable CBZ all observed. **Needs on-device confirmation:** Android WorkManager `APPEND_OR_REPLACE` reconcile path and iOS CBZ/runtime (compile-verified; `:app` Koin/instrumented tests blocked locally by missing `google-services.json`).

Method: one **Opus 4.8** fix agent per issue-cluster, each followed by an independent **Opus 4.8** verifier agent, then a compile gate, then this log is updated and the cluster is committed. Fix agents mutate the shared working tree, so clusters whose file scopes overlap run **sequentially** (to avoid corrupting the tree / losing progress); provably-disjoint clusters may run in parallel. Every agent uses Opus 4.8.

---

## 🧭 State-reactivity / navigation / snackbar UX — round 3 (user-reported, 2026-06-02)

Three cross-cutting UX bugs, root-caused with parallel Explore agents and fixed.

- **Per-chapter state didn't update on the Details screen until leaving + re-entering** (e.g. a deleted download still showed "Downloaded"). The Details flow IS reactive (the saved-chapters Room `Flow` re-emits on every write), but `MangaDetails.overlaidWith` OR-ed the new saved flags against `current.details` — which is the *previously-overlaid* result — so `isDownloaded`/`isBookmarked`/`isNew` were **sticky**: once true, `true || false` stayed true forever (only `isRead`, a direct assignment, updated live). Fix: take the saved Room flags **directly** (Room is the source of truth; the network DTO always leaves them false), so every re-emission reflects the real state. `DetailsViewModel.overlaidWith`.
- **A visible snackbar blocked navigation** ("can't go back / to another screen until the snackbar's duration ends"). Every screen's effect collector called `snackbarHostState.showSnackbar(...)` **directly inside** `effects.collect { }` — a suspending call that froze the single collector for the snackbar's whole duration, starving the next effect (e.g. `NavigateBack`). Fix: launch every snackbar in a `rememberCoroutineScope()` (`scope.launch { showSnackbar(...) }`) so the collector never blocks — swept across all 12 effect collectors (Reader/Details/Home/Library/Search/Settings/Language/Complaint/AdminComplaint/Sources/Downloads; Updates already did this). Result-handling snackbars (Settings feedback retry, Sources retry) keep their action logic inside the launch.
- **Rapid taps stacked navigations** ("tap Back twice → go back 2 screens"). `safeNavigate`/`safePopBackStack` had no guard and the dormant `NavigationLock` was never used. Fix: a RESUMED-state guard (`isReadyForNavigation()`) — a navigation only proceeds while the source destination's lifecycle is RESUMED; the in-flight transition drops it to STARTED, so the second rapid tap no-ops. Added to both helpers; the chapter→reader and settings-row direct `navigate(...)` calls now route through the guarded `safeNavigate`. Auto-navigations (What's-New redirect) are unaffected — they fire post-RESUMED.

Verified: 3-target compile green; `:presentation` Details regression suite passes; desktop boot smoke clean (app reaches the start destination and loads content — the nav guard does not block startup navigation).

---

## 🔧 Download/reader/settings parity — round 2 (user-reported, 2026-06-02)

Five more user-reported regressions vs native, root-caused with parallel Explore agents (each cross-checked against `native-app/`), then fixed for parity on **all** platforms.

- **Reader re-fetched downloaded chapters over the network (the MAIN bug).** The user-facing reader is the rework reader (`Screen.ChapterImagesFragment` → `ChapterImagesByLegacyArgsReworkScreenRoute` → `ChapterPagesRepositoryImpl`), but `fetchPages` had **no downloaded-chapter branch** — it always hit the source. The downloader already persists `SavedChapterEntity.localImagePaths` (a single `chapter_<id>.cbz` or loose `image_<n>` files) + `isDownloaded`, exactly native's `localImagePaths` mechanism. Added the branch (mirrors native `ReaderViewModel`): resolve the saved chapter via `ChapterDao.getChapterIdByUrl`/`getChapterByIdSuspend`; if downloaded with local files, serve them — extract a `.cbz` via `CbzReader.extractImages`, else use the loose paths in stored order — as `file://` URLs (Coil 3 `FileUriFetcher`, uniform JVM+Native); otherwise fall through to the network. New ctor deps `chapterDao` + `cbzReader` wired in `ReaderReworkModule`. ✅ **Runtime-verified on desktop:** opening downloaded chapter 37 (manga 9) extracted its CBZ to `cache/cbz_extract/9/37/` (19 valid WEBP pages, written this session 36 s after the download) — and `ChapterPagesRepositoryImpl` is the *only* `extractImages` caller in the tree, so this proves the local-read path executed instead of the network.
- **Deleting a download still showed "Downloaded".** `DownloadsActionRepositoryImpl.deleteDownload` only removed the `chapter_downloads` row; the badge OR-s `saved_chapters.isDownloaded`, which was never cleared. Now also calls `ChapterDao.markChaptersNotDownloaded` (clears `isDownloaded` + `localImagePaths`) and best-effort deletes the on-disk `chapterDir`. The reactive saved-details flow re-emits, so the Details badge clears immediately; the reader then falls back to the network. Covers single/selected/all (all funnel through this one method).
- **"What's New" reopened immediately until app restart.** The first-launch redirect is gated on the legacy `WhatsNewViewModel.shouldShowWhatsNew`, set true on init and never flipped within the session; `LibraryScreenRoute`'s `hasNavigated` guard is a `remember` that resets when the route is disposed during navigation, so on pop-back it re-navigated. Added `WhatsNewViewModel.markSeen()` (flips the flag false synchronously + persists the seen version), called from `LibraryScreenRoute` on navigate — mirrors native's `markWhatsNewAsSeen()`.
- **No visible "Compressing" step (iOS especially).** The pipeline already sets `COMPRESSING` and runs the CbzWriter on every platform (iOS stores bytes verbatim — a valid CBZ, by design), but the Details row rendered QUEUED/COMPRESSING with the same spinner. Added a chapter-row status line — "Downloading N%" / "Compressing…" / "Queued" — driven by `download.state` (new `strings_pfix_dlstatus.xml`).
- **Site-header (Cloudflare cookie/UA) saved only on Android.** Android captures cookie+UA synchronously in `onPageFinished`; iOS/Desktop capture asynchronously, and the save was gated on a Save/Close tap → it raced the capture and persisted nothing. Fix: **auto-persist on capture** in `WebViewComposeScreen` (`LaunchedEffect(savedHeaders)` → `onSaveHeaders`), removing the race everywhere. Also fixed iOS to read the **WKWebView** cookie store (`configuration.websiteDataStore.httpCookieStore.getAllCookies`, host-filtered) instead of the usually-empty shared `NSHTTPCookieStorage`.

Verified: **3-target compile green** (Desktop/Android/iOS-sim); `:data` + `:presentation` desktopTest pass; `:app` `KoinGraphRegistrationTest` passes (validates the new reader ctor wiring in the merged graph); desktop launch smoke clean (0 exceptions) with the **reader-local-read path proven by the populated `cbz_extract` cache**. **Needs on-device confirmation:** the iOS site-header capture loop (WKWebView cookie store + auto-persist is compile-verified only — the full Cloudflare challenge→capture→reuse flow can't be exercised on the simulator here).

---

## Execution plan & phases

Order is strict: **all P0/Critical resolved-or-blocked → P1/High → P2/Medium → P3/Low.**

- **Phase P0 — Critical (13 findings).** Triaged below. Some are pure-code + compile-verifiable; some are Android-SDK/device-bound (code can be written but final verification needs a device + `google-services.json` + signed build); a few need a product/compliance decision.
- **Phase P1 — High (98).** Per-screen feature/state/interaction gaps. Will be grouped into ~20 clusters by screen/section.
- **Phase P2 — Medium (202).** UI/behaviour divergences (spacing/typography/color/component/defaults).
- **Phase P3 — Low (368).** Cosmetic/copy/a11y; includes items where KMP already improved on native (these will be marked *Intentionally different*).

### Environment & verification constraints (important)
- **Compile gate:** `./gradlew <module>:compileKotlin{Desktop,DebugKotlinAndroid,…} --offline`. Online/clean dependency resolution can fail (AGP 8.13.0); `--offline` against the warm cache is the reliable gate. *(Probe in progress — result recorded below.)*
- **Cannot be verified in this environment:** full Android `assembleDebug` (no committed `google-services.json`; the `:app` module applies the google-services + crashlytics plugins), iOS device/simulator runs (need a Mac+Xcode), and any **on-device runtime behaviour** (ads rendering, Play Store update/review dialogs, real FCM push, actual network calls). Such items, once coded, are marked **Needs Review (build/device)** rather than Fixed.
- Forbidden to edit: `native-app/` (read-only source of truth) and the `sources_repositry/` scrapers (logic frozen). Adding a *use case* that calls a DAO is allowed; editing scraper bodies is not.

### P0/Critical triage

| ID | Section | Severity (audit→verified) | Tractability | Plan |
|---|---|---|---|---|
| P0-LOC | localization-rtl | Critical (confirmed) | **Code + compile-verifiable** | Port the 9 missing locale catalogs (de/es/fr/in/it/ja/pt/ru/tr) into `:ui` composeResources so the live `:ui` screens resolve them. The catalogs already exist in `:composeApp` but are unreachable from the `:ui` Res package. |
| P0-ADULT | manga-details | Critical (confirmed) | **Code + compile-verifiable** | Restore native's 3-state hard-block adult gate (AdultWarning→MStep1→MStep2, all paths back-navigate; content never shown). Assets `ic_pluss18` + `Plus18memes` already exist in `:composeApp`; copy into `:ui`. Compliance-sensitive → restore native hard block. |
| P0-SRCSEED | di-startup-lifecycle | Critical (confirmed) | **Code + compile-verifiable** (runtime fetch needs device) | Reintroduce startup remote source-list refresh (GET `yamimanga.me/source/35`, upsert `SourcesDao`) via a domain use case invoked from an app-scope startup side-effect. |
| P0-FCM | notifications-background | Critical (confirmed) | **Code only; Android-build/device verify-blocked** | Port `onMessageReceived` into the KMP Android `MyFirebaseMessagingService` → post via `NotificationPresenter` on an IMPORTANCE_HIGH channel. (`:app` androidMain.) |
| P0-PLAY-WIRE | play-update-review-consent (×4) | Critical (confirmed) | **Code only; Android-build/device verify-blocked** | Implement a `ForegroundActivityProvider`/`ActivityHolder` (ActivityLifecycleCallbacks in `MyApp`), bind it to the existing `AppUpdateClient`/`InAppReviewClient`/`ConsentFlowClient`, and add startup invocations (update check, review prompt, UMP consent before ads). The SPI impls already exist & are bound; only the activity wiring + call sites are missing. |
| P0-ADS | ads-monetization (×3: banner, native, rewarded) | Critical (confirmed) | **BLOCKED — decision + heavy Android interop + device** | Requires AdMob SDK Compose interop (AndroidView host for AdView/NativeAd), `play-services-ads` in androidMain, ad-unit IDs, lifecycle, and on-device verification. Also a product/architecture decision: the rework contract intentionally deferred ads as a cross-platform deviation. See "Decisions required". |
| P0-WEBVIEW403 | webview-screen | Critical → **Low (verifier-corrected)** | Code + compile-verifiable | Verifier downgraded to Low (the full-screen WebView route exists; this is a UX degradation, not a missing flow). **Reclassified to P3/Low** and handled in the Low phase. |

> Net P0 to action now: **P0-LOC, P0-ADULT, P0-SRCSEED** (compile-verifiable) and **P0-FCM, P0-PLAY-WIRE** (code, verify-blocked). **P0-ADS blocked** pending decision. P0-WEBVIEW403 reclassified to Low.

### Decisions required (documented; safest-parity default chosen)
1. **Ads/monetization (P0-ADS) & ad-gated consent.** Native runs AdMob banners (5 screens), native-ad list interleaving (Home/Search), and a rewarded-ad download gate, with UMP consent gating ad init. The KMP architecture contract **intentionally deferred ads** as a documented cross-platform deviation, and the platform `AdProvider` is a no-op/stub. Reintroducing ads = significant Android-only Compose-interop work that cannot be runtime-verified here and re-opens an intentional architectural decision.
   - **Options:** (a) Re-implement full AdMob stack on Android only (large; needs device + `google-services.json` + product sign-off); (b) keep ads deferred and mark as *Intentionally different* per the contract; (c) implement the **non-ad-coupled** consent/Play wiring now (P0-PLAY-WIRE) and leave the ad rendering for a device-enabled session.
   - **Chosen (safest, reversible):** (c) — wire the Play/consent SPIs now (code-complete, verify-blocked), and mark the **ad-rendering** placements **Blocked: needs product decision + Android SDK interop + on-device verification**, with the exact native references recorded so they can be implemented in a device session. This avoids a large unverifiable architectural change while restoring everything that is safely restorable.

---

## Progress log (newest first)

_(Each fix appended here after its verifier passes. Format: ID · severity · screen · what was wrong · what changed · files · verification · build · status.)_

- **USER-REPORTED · 🔴-equivalent · Library chapters not cached** — *Reported by user:* adding a manga to the library saved only the manga info, and opening it re-fetched chapters from the network every time. *Confirmed* a real parity regression (native persists manga+chapters on add and reads from Room on open). *Fixed:* (A) `addToLibrary(manga, chapters)` now persists chapters via the existing `LibraryDeo.saveMangaWithChapters` + a faithful `Chapter→SavedChapterEntity` mapper (`.reversed()` to match native id ordering); threaded through `ToggleInLibraryUseCase` + `DetailsViewModel.onToggleInLibrary`. (B) `DetailsViewModel.onEnter` reads the Room saved-details flow first — an in-library manga with cached chapters renders from cache with **no network fetch**; not-in-library or empty-cache fetches; `OnRetry`/refresh always fetches (native parity). No schema/migration. *Files:* `domain` (LibraryRepository, ToggleInLibraryUseCase, +tests/fakes), `data` (LibraryRepositoryImpl, MangaDetailsMappers, +new test), `presentation` (DetailsViewModel, +tests/fakes). *Verification:* independent Opus verifier **PASS**; `:composeApp` compile + `:domain`/`:data`/`:presentation` desktopTest **BUILD SUCCESSFUL** (new tests assert persistence, ordering, idempotency, no-fetch-on-cached-open). *Status:* **FIXED** (caveat: manga added *before* this fix, or via Home quick-toggle, have no cached chapters and still fetch until re-added/refreshed — persist-on-fetch not implemented).
- **P1 batch 2 · 🟠 High · Details / Reader / Library / Complaint** — Details: restored 4-button header action row + centered cover/title header; added Resume FAB, chapter filter/sort sheet, per-chapter bookmark + download buttons. Reader: nested-scroll pinch-zoom, auto-advance on last page, auto-load on scroll-end, two-line title. Library: title-on-cover scrim, native 0..8 items-per-row slider (replacing the 3-value density picker), toggleable search bar. Complaint: admin inline closure-reason. *Verification:* `:composeApp` compile + `:presentation` desktopTest **BUILD SUCCESSFUL** (batch-1 verifier PASS-WITH-NOTES). *Deferred (cross-cutting):* NEW-chapter badge (`Chapter.isNew` domain+data), reader banner ad (blocked-ads), reader multi-chapter feed + OOM compression (structural), items-per-row persistence (:data), device-metadata row + edit-metadata preservation (:data), BackHandler shim.
- **P1 batch 1 · 🟠 High · Search / Statistics / About / What's New** — Search: filter chips apply immediately + blank query/GENRES mode + 3-action error state (+4 VM tests). Statistics: 7 native row icons restored. About: row leading icons + trailing chevrons + scrollable. What's New: faithful video poster (true inline player = platform/media-SPI limitation). *Verification:* independent Opus verifier **PASS-WITH-NOTES**; `:composeApp` compile + `:presentation` desktopTest **BUILD SUCCESSFUL**. *Deferred (cross-cutting):* search error-action route wiring + BackHandler shim; What's New image carousel (`:data` one-liner) + inline video (media SPI).
- **P0-FCM + P0-PLAY-WIRE · 🔴 Critical ×6 · Android host (FCM push display; in-app update/review; UMP consent; foreground-Activity wiring)** — *What was wrong:* (a) the KMP `MyFirebaseMessagingService` was an empty stub, so received FCM pushes were silently dropped; (b) the Play `AppUpdateClient`/`InAppReviewClient`/`ConsentFlowClient` SPIs were implemented & Koin-bound but **never invoked** and had **no foreground Activity** (`activityProvider = { null }`), so update/review/consent never ran. *What changed:* (1) `MyFirebaseMessagingService.onMessageReceived` now displays the push via `NotificationCompat` (`firebase_messages` IMPORTANCE_HIGH channel, `ic_message` icon, native title/body fallbacks byte-for-byte); `onNewToken` bridges to `PushTokenBroadcaster`. (2) New `ActivityHolder` (`:platform` androidMain) tracks the foreground Activity via `registerActivityLifecycleCallbacks` in `MyApp`; the 4 facades are now bound with `activityProvider = { ActivityHolder.current }`. (3) `MainActivity.onCreate` now invokes, mirroring native: flexible in-app update (`checkForUpdate → startFlexibleUpdate`), UMP consent (`requestConsentInfoUpdate → loadAndShowConsentFormIfRequired →` ads-init gated on `canRequestAds()`), and a **20-day-gated** in-app review (`first_open_time` key via `SecureStorage` — native's `SEVEN_DAYS_MILLIS` constant is actually 20 days); all try/catch-guarded, scope cancelled in `onDestroy`. No ad views added. *Files:* `platform/.../activity/ActivityHolder.kt` (new), `app/.../MyApp.kt`, `app/.../MainActivity.kt`, `app/.../firebase_cores/messaging/MyFirebaseMessagingService.kt`, `shared/androidMain/.../di/PlatformModule.android.kt`. *Verification:* independent Opus verifier **PASS-WITH-NOTES** — all referenced symbols resolve (no anticipated compile errors), every flow faithful to native, scope clean. *Build:* `:platform` + `:shared` `compileDebugKotlinAndroid` **BUILD SUCCESSFUL** (ActivityHolder + DI binding verified). The `:app` Kotlin compile is blocked by `:app:processDebugGoogleServices` (no `google-services.json`), so the `:app` files are review-verified only. *Status:* **CODE-COMPLETE / NEEDS REVIEW (device + `:app` build)** — restores native behavior at source level; actual FCM rendering + Play/consent dialogs require `google-services.json` + an on-device run to confirm.
- **P0-SRCSEED · 🔴 Critical · Startup — remote source-list seeding** — *What was wrong:* native refreshes its manga-source registry from a remote endpoint on every launch (`MainActivity → updateSources.initializeSources()`); KMP retired this as an orphan, so source base-URLs go stale and scrapers silently break when a source's domain changes server-side. *What changed:* re-added the refresh in the rework graph — `:domain` `RefreshSourcesUseCase` + `SourceRegistryRefreshRepository` interface; `:data` impl GETs the Admin-gated endpoint (`yamimanga.me/dev/source` when `Admin.isAdmin` else `/source/35`) via the shared `ApiClient`, parses with a manual `JsonObject` DTO that mirrors native's lenient deserializer **byte-for-byte** (incl. the misspelled `delate` key, string-coerced ints, `state`/`isWorking` fallback), and upserts `SourcesDao` with native's exact version-comparison (base/image version strictly-greater wins; state change; delete flag); re-added the 4 `SourcesDao` write methods retired earlier; bound in DI; invoked once per launch from `App()` `LaunchedEffect`, failure-isolated (never blocks launch, `CancellationException` re-thrown). *Files:* `domain/.../repository/SourceRegistryRefreshRepository.kt` (new), `domain/.../usecase/sources/RefreshSourcesUseCase.kt` (new), `data/.../repository/SourceRegistryRefreshRepositoryImpl.kt` (new), `shared/.../data/local/dao/SourcesDao.kt`, `composeApp/.../di/SourcesReworkModule.kt`, `composeApp/.../App.kt`, `data/build.gradle.kts` (added `kotlinx-serialization-json` runtime lib — **NOT** the compiler plugin; see build note below). *Verification:* independent Opus verifier **PASS-WITH-NOTES** — endpoint/Admin-gating/parse/version-upsert all faithful to native; confirmed the registry refresh **does** restore the primary scrape path (home/search/browse read base-URL from the registry via `BaseManga.getBaseUrl()`). *Build:* initial attempt FAILED — the verifier-approved `data/build.gradle.kts` change applied the kotlinx.serialization **compiler plugin**, which altered `:data`'s Gradle variant attributes and broke `:composeApp`'s resolution of all `:data` repository classes; fixed by removing the plugin (manual `JsonObject` parsing needs only the runtime lib). After fix: `:data:compileKotlinDesktop` + `:composeApp:compileKotlinDesktop` both **BUILD SUCCESSFUL**. *Status:* **FIXED** (primary scrape path) — with follow-up below.
  - *Follow-up (P0-SRCSEED-FOLLOWUP, Needs Review):* native also rewrites already-stored absolute URLs in the `manga`/`chapter`/`history`/`notification` tables when a domain moves (`propagateUrlChanges`/`propagateImageUrlChanges`). This was deliberately scoped out (touches 4 DAOs + the rework `:domain` model dropped routing fields). Residual gap: previously-saved library/history items keep pointing at the old domain until propagation lands. Tracked for a follow-up slice.
- **P0-ADULT · 🔴 Critical · Manga Details — adult/+18 content gate** — *What was wrong:* native HARD-BLOCKS adult manga (a "Content unavailable" dialog; any interaction back-navigates; cover+chapters never shown — Google-Play-policy compliance). KMP had inverted this: a single dialog whose "Continue" REVEALED the content. *What changed:* (1) `:presentation` — new `AdultGateStep{AdultWarning,MStep1,MStep2,None}` in `DetailsState` (MVI state, survives config change), 4 gate intents, reducer arms the gate on enter AND re-arms from fetched genres (closes the URL/search bypass), no transition ever reveals content; reuses the existing `IsAdultContentUseCase`. (2) `:ui` — gate branch suppresses `DetailsBody`/chapters above the content branch, cover forced blurred, native-faithful `AdultConfirmationDialog` (ic_pluss18 120dp red, "Content unavailable" header, Play-policy body) + `MConfirmationDialog`; the old `adultConfirmed` reveal path removed. (3) Both warning buttons + outside-tap back-navigate — **exactly matching native** (MStep1/MStep2 kept as unreachable dead code, as in native). Assets (`ic_pluss18` + 12 meme drawables) + 2 strings copied into `:ui`. *Files:* `presentation/.../details/{AdultGateStep(new),DetailsState,DetailsIntent,DetailsViewModel}.kt`, `ui/.../details/DetailsScreen.kt`, `ui/.../values/strings_np_p2_details_reader.xml`, 13 new `ui/.../drawable/` files. *Verification:* independent Opus verifier **PASS-WITH-NOTES** → content provably unreachable on every path; the one flagged divergence (Close→meme chain) was then **re-aligned to native** (both buttons → back). *Build:* `:ui:compileKotlinDesktop` **BUILD SUCCESSFUL**. *Status:* **FIXED** (compliance behavior restored, byte-faithful to native flow). *(Verifier also noted a pre-existing duplicate `close` key across two `values/` files — not caused by this fix; logged for later cleanup.)*
- **P0-LOC · 🔴 Critical · Localization (all 19 live `:ui` screens)** — *What was wrong:* 9 of 11 locales (de/es/fr/in/it/ja/pt/ru/tr) were entirely absent from the `:ui` Compose-Resources package the live screens consume, so users in those languages saw the whole app in English. *What changed:* created 9 `values-XX/strings.xml` catalogs in `:ui`, 166 keys each, copied **verbatim** from the `:composeApp` native-mirror catalogs (no machine translation; untranslated keys omitted → English fallback, mirroring the existing `values-ar/` convention). *Files:* `ui/src/commonMain/composeResources/values-{de,es,fr,in,it,ja,pt,ru,tr}/strings.xml` (9 new). *Verification:* independent Opus verifier **PASS** — 0 orphan keys, 0 placeholder/escape mismatches (all 79 placeholder keys checked), faithful byte-level sourcing, 0 out-of-scope edits. *Build:* `./gradlew :ui:compileKotlinDesktop` → **BUILD SUCCESSFUL**. *Status:* **FIXED** — with known limitation below.
  - *Known limitation (pre-existing, not a new regression):* 280 of 446 `:ui` keys have no translation source anywhere (45 truly rework-only `np_*` keys + 235 keys `:composeApp` never translated either), so they fall back to English in the 9 new locales — the **same coverage the Arabic catalog already had**. Full translation of those 280 keys needs real translation assets (human/professional MT) and is tracked as **P-LOC-FOLLOWUP (Needs Review)**.

---

## Status board

### ✅ Fixed (verified)
- **P0-SRCSEED** — startup remote source-list refresh restored (faithful to native; primary scrape path works again); verifier PASS-WITH-NOTES; `:data` + `:composeApp` compile. (Follow-up: URL propagation to entity tables — P0-SRCSEED-FOLLOWUP.)
- **P0-ADULT** — native hard-block adult gate restored (content unreachable; flow byte-faithful to native); verifier PASS-WITH-NOTES → re-aligned; `:ui` compiles.
- **P0-LOC** — 9 locale catalogs ported into `:ui`; verifier PASS; `:ui` compiles. (Limitation: 280 unsourced keys still English — same as Arabic; tracked as P-LOC-FOLLOWUP.)

### ✅ ALL PHASES COMPLETE (P0, P1, P2, P3)
Every one of the 681 audit findings is now in a terminal state: **Fixed & compile-verified**, **Verified already-correct**, **Intentionally-different (documented)**, or **Deferred-cross-cutting / Blocked (with a precise recorded plan)**. The full rework graph compiles on desktop + Android + iOS and `:domain`/`:data`/`:presentation` unit tests pass. See the FINAL summary at the bottom.

### 🔥 Post-run fixes (found by actually running the Desktop app)
- **RESOURCE-DUP CRASH (Critical, FIXED):** Compose Resources throws at **runtime** (not compile) when one `<string name>` exists in two files within a `values*` qualifier (`Resource ID … has more than one file`). The parity work's `strings_pfix_*.xml` files introduced 7 such collisions (+2 pre-existing: `completed`/`close`), crashing the desktop app in `LibraryOptionsSheet`/`DownloadsScreen`. Deduped all 9 (identical values). **Lesson: the `compileKotlin*` gate does NOT catch this** — added a duplicate-id scan (node, per qualifier) as a required gate; verified 0 dups across `:ui` + `:composeApp`.
- **WebView on macOS (attempted, then reverted — app must open):** I enabled a macOS KCEF init, but the user's run showed it regressed the app to "window never opens": under a JetBrains Runtime (Android Studio's bundled JBR = java.home), JCEF's `libjcef.dylib` loads from the JBR and hard-codes the CEF framework path to `<JBR>/Contents/Frameworks/…` which the plain JBR doesn't ship → native `cef_load_library: dlopen … no such file` (uncatchable), and `-XstartOnFirstThread` then blocks the Compose window. **Reverted** to skipping KCEF on macOS (degrade to placeholder) + removed `-XstartOnFirstThread`. Verified the app opens (ran `:desktopApp:run`: 0 `cef_load_library`, 0 resource-dup, no exceptions, home feed loads). Windows/Linux KCEF, proguard, bundling-prep, and the `KcefState`/late-init recovery scaffolding are kept. **macOS embedded WebView requires running under a plain JDK 17 (not the JBR), and even then KCEF 2025.03.23 has a macOS icudtl.dat bug — realistically needs a manual CEF bundle or a KCEF upgrade.**
- **Desktop downloads:** investigated — already functional end-to-end (engine bound for desktop, files to `~/.yami-manga`, screen observes the same Room rows). No change needed; CBZ-on-download intentionally not wired (shared path is also iOS where the CBZ writer throws) — CBZ available via Settings "convert existing".

### 📦 macOS Desktop package (standalone `.app` + `.dmg`)
- **Status:** ✅ Built from `parity-fixes` and **verified to launch standalone** (outside Android Studio/JBR). KCEF/embedded-WebView on macOS remains **skipped** (graceful placeholder), as required.
- **Prereq:** one-time flag in `gradle.properties` — `compose.desktop.packaging.checkJdkVendor=false` (the only JDK 17 available here is Homebrew, which Compose blocks by default; the produced app was verified to launch — for CI/release prefer a non-Homebrew JDK 17 such as Temurin/Corretto).
- **Build commands** (from repo root, with a JDK 17 toolchain):
  - `.app` (app image): `./gradlew :desktopApp:createDistributable`
  - `.dmg` (installer): `./gradlew :desktopApp:packageDmg`
- **Output paths:**
  - `.app` → `desktopApp/build/compose/binaries/main/app/Yami Manga.app` (~202 MB; bundles its own JDK 17 runtime under `Contents/runtime`)
  - `.dmg` → `desktopApp/build/compose/binaries/main/dmg/Yami Manga-1.0.35.dmg` (~138 MB)
- **Launch verification** (ran the bundled `Contents/MacOS/Yami Manga` launcher directly): process stays alive and reaches the home feed; **0** `cef_load_library`, **0** resource-duplicate crashes, **0** exceptions, **0** Android-Studio/JBR references (uses the bundled runtime). Prints the expected `Skipping KCEF init on macOS …` line.
- **Known limitations:**
  - **Unsigned / not notarized** — macOS Gatekeeper blocks the first open ("cannot be opened because Apple cannot check it"). Bypass: right-click → **Open**, or after copying from the DMG run `xattr -dr com.apple.quarantine "/Applications/Yami Manga.app"`. Proper fix = Apple Developer ID signing + notarization (not configured).
  - Built with the **Homebrew JDK 17** toolchain via the override above; clean-vendor JDK recommended for release.
  - **macOS WebView** still shows the graceful placeholder (KCEF intentionally skipped — separate follow-up).
  - Build artifacts live under `desktopApp/build/` (git-ignored) — not committed; only the `gradle.properties` flag + this doc are.

### Phase P3 (Low, 369) — summary
- All triaged across 3 batches (P3-1 Home/Settings/Search/Library/Updates/Complaint; P3-2 Details/Statistics/WhatsNew/Reader/Downloads/History; P3-3 Language/About/Sources/Onboarding/Theme/Components/Accessibility/LibraryDetails/WebView/Room/Networking/Android-infra/App-shell/Navigation/Image/Validation + misc reconcile).
- **Fixed (compile-verified):** dozens of cosmetic/copy/typography/spacing/icon/a11y items per screen + a few seams (`ScrollBarFadeDuration` expect/actual, cover-scrim 0.8, loading-spinner `inversePrimary`, `StringListConverter` lenient parse, kermit-crashlytics writer, App.kt scaffold inset/Surface, validation trim-gating + localized counter, +per-screen Arabic/locale strings).
- **Intentionally-different (KMP matches or improves on native):** the large majority of Low items — empty/loading states the rework added, RTL-aware/AutoMirrored icons, labeled-clickable a11y improvements, trimmed/guarded inputs, M3 redesigns. Each documented in code.
- **Deferred-cross-cutting / Blocked:** items needing `:app` (google-services build), `:platform` SPIs, `:data`/`:shared` signature/schema changes, frozen scrapers, or a product decision (ads) — all in the backlog with plans.

### Phase P2 (Medium, 202) — summary
- All 202 triaged across 4 batches. **Fixed (compile-verified):** Details (parallax, chip styling, dialogs, multi-select, pull-to-refresh), Library (native empty states, card styling, sort/filter/display sheets, tab order), Search (collapsible genres, mode chips, close-clears-query, SORT+genre), Reader (per-mode chip icons, 3-pill seekbar, translucent bars, bottom action bar, tap-through-zoom), Complaint (native states, dialog icons, status badges, admin footer/stats), Settings (chromeless bar, nav chevrons, reading-mode chips, feedback subject), Updates (flat rows, error rendering, delete-persists-nav, icon actions), Home (error interpolation, carousel scale), What's New (typography, header, fullscreen viewer, placeholders), About (row order, container styling, ArrowBack, 24sp title, +10-locale source-code), Statistics (ArrowBack, read-duration, localized h/m ×11), History (icon affordances, title), Sources (native subtitle, complaint subject, ItemsGroup), Downloads (compressing tab/label, ar error), Theme (TabRow indicator + card, perm section), Components (press-scale, gestureExclusion seam, adaptive badge, native cover placeholder), Language (request dialog footer+counter, retry snackbar), Validation (social footer, hasChanges gating), Room (FK PRAGMA on), Networking (timeout, debug-gated logging), Android-infra (notification channel/icon, localized worker strings ×11).
- **Intentionally-different (KMP improvement / documented):** library long-press multi-select, downloads blank-error handling, several "KMP redesign" items.
- **Deferred-cross-cutting / blocked:** ads (blocked); items needing `:data`/`:shared` signature changes, `:platform` SPIs, `composeApp/App.kt` + navigation, Android `:app` build, or frozen scrapers — each recorded with a precise plan (see backlog).
- **Build:** all rework modules compile desktop+Android+iOS; `:domain`/`:data`/`:presentation` desktopTest green. Fixed 3 build breaks found by the gate (nested-comment in SettingsScreen, cross-module smart-cast in HomeScreen, stale Room-KSP cache). 4 commits.

### ✅ Cross-cutting backlog — DONE (compile/test-verified)
- **Admin complaint Type selector** — `editComplaint(original, type, subject, body)` wired domain→data→usecase→presentation→ui. ✅
- **Reply metadata preservation** — `replyToComplaint` merges parent device metadata + `replyto`. ✅
- **Download cancel-all "mark failed"** — `ChapterDownloadDao.markAllRunningOrQueuedAsFailed` + `DownloadRepository.cancelAllDownloads()` (Android + nonAndroid impls) + receiver wired (`:app` review-only). ✅
- **Settings CBZ conversion progress dialog** — `observeCbzConversion(): Flow<CbzConversionProgress>` + `stopConversion()` (domain→data→presentation→ui), counts/current-item/Stop/terminal states. ✅
- **NEW-chapter badge** — refresh-insert already set `isNew=true`; fixed the read-side `overlaidWith` so the badge shows after a network fetch too. Now lights up end-to-end. ✅
- **iOS CBZ writer + CBZ-on-download (jvm+ios)** — implemented `IosCbzWriter` (was throwing) via a new pure-Kotlin STORE-method `StoreZipWriter` (okio + table CRC32; Kotlin/Native has no `java.util.zip`); wired `CoroutineDownloadRepositoryImpl` (nonAndroid) to archive to `.cbz` when the CBZ pref is on. Reader-compatible (okio `openZip`). Compiles for iOS; runtime archive needs on-device verify. ✅

### 🩹 User-reported polish (this session, verified by compile + tests + desktop smoke-run)
- **Details live download progress** — chapter rows now show a determinate progress ring while downloading and flip to "downloaded" on completion **without leaving the screen** (VM combines the chapter list with `observeAllDownloads()`; +regression test). ✅
- **Library cards** — removed the non-native card **elevation/shadow** (flat like native) and the non-native below-card **"Done"/downloaded** check icon. ✅

### 🧷 Cross-cutting backlog — STILL OPEN (each with a recorded plan)
- **Needs a Room migration:** `lastReadDate` chapter-sort column (sort option exists; domain `Chapter`/entity lack the field — adding it requires a new column + `MIGRATION_n_n+1`).
- **Needs a `:platform` SPI:** What's-New per-version gating wants `AppVersionProvider.versionCode` (currently only `versionName`) — add to the SPI + 3 actuals; inline **video** needs a `:platform` media-player SPI (per-platform dep).
- **Downloads paging:** Room-KMP can't generate `PagingSource` (`LimitOffsetPagingSource` is Android-only) — true paging needs an expect/actual `Pager` across `:data`/`:platform`. **Effectively blocked cross-platform**; current in-memory list works (perf only).
- **Shared HTTP/Coil unification:** expose the shared engine `OkHttpClient` so Coil derives from it (shared 200 MB disk cache) + lift `forceCacheForDados`/offline interceptor to shared — needs `createHttpClient(...)` signature + DI change.
- **`:ui`/presentation:** per-request **source headers** on covers; reader **>99 MB bitmap compression** + **multi-chapter continuous feed** (structural); offline-read path (`ChapterPagesRepositoryImpl` branch on `isDownloaded`); Home **403/Cloudflare inline recovery** (route + WebView dialog).
- **Android-only (verify needs device + `google-services.json`):** analytics `app_open`/`manga_open`; notification permanent-denial → App-Settings redirect; `AppCompatActivity` base for packaging chrome. _(CBZ archiving on iOS/Desktop — now DONE, see above.)_
- **Italian MangaPark source:** un-stub a config-only class under the frozen `sources_repositry/`.
- **Complaint submit-form labels/error strings:** repoint `:ui/settings` to `ComplaintType.displayName()` + base-strings tweaks.
- **P-LOC-FOLLOWUP:** ~280 `:ui` keys lack non-English translations (no in-repo source) — needs real translation assets.

### ⛔ Blocked
- **P0-ADS** — AdMob banner/native/rewarded ad **rendering** (3 Critical findings + the ad-rendering part of the consent finding). Reason: needs a product/architecture **decision** (the rework contract intentionally deferred ads as a documented cross-platform deviation) + Android AdMob Compose interop (AndroidView host for AdView/NativeAd) + `google-services.json` + on-device verification. The **consent flow + ads-init wiring** is already done (P0-PLAY-WIRE); only the ad **views** remain. Native refs: `ad_mob/bannars/BannerAdView.kt`, `ad_mob/util/ads_lists/interleaveAds7.kt`, `ad_mob/native_ads/NativeAdListItem.kt`, `ad_mob/AdViewModel.kt` (download gate, interval=6). **Awaiting user decision: (a) implement Android-only ad stack later in a device session, or (b) keep ads deferred per contract and mark *Intentionally different*.**

### 👀 Needs review (manual / device / build)
- **P0-FCM + P0-PLAY-WIRE** — code-complete & review-verified; `:platform`/`:shared` parts compile; `:app` Kotlin build + on-device runtime (FCM render, Play update/review dialogs, UMP form) owed (needs `google-services.json` + device).
- **P-LOC-FOLLOWUP** — 280 `:ui` keys (45 `np_*` + 235 others) have no translation source in-repo; remain English in all non-en locales. Needs real translation assets.
- **P0-SRCSEED-FOLLOWUP** — native's `propagateUrlChanges`/`propagateImageUrlChanges` (rewrite stored manga/chapter/history/notification URLs on domain change) not yet ported; previously-saved items keep old-domain URLs until done.

### ⏭️ Pending (not started)
- _(none — all four phases triaged.)_

### 🟢 Recommended next step
The audit is fully worked through. Remaining real work is the **cross-cutting/Blocked backlog** (below) — best done in a device-enabled session with `google-services.json`: (1) **decide ads** (P0-ADS); (2) verify the Android Play/FCM/consent flows on a device; (3) the `:data`/`:shared` signature/schema items (admin complaint-type, downloads paging, CBZ progress, downloaded-size, `lastReadDate`/`isNew`-on-refresh); (4) translation assets for the ~280 untranslated `:ui` keys; (5) a `:platform` media-player SPI for inline video.

---

## Phase P0 (Critical) — summary
- **Findings:** 13 Critical.
- **Fixed & compile-verified (3):** P0-LOC (9 locales), P0-ADULT (hard-block adult gate), P0-SRCSEED (startup source refresh).
- **Code-complete, needs device/`:app`-build review (6):** P0-FCM (push display) + P0-PLAY-WIRE (in-app update invocation, update Activity wiring, in-app review invocation, UMP consent invocation). `:platform`/`:shared` parts compile; `:app` parts review-verified (google-services.json absent).
- **Blocked, awaiting decision (3 + ad-render of 1):** P0-ADS — AdMob banner/native/rewarded **rendering** (consent + ads-init wiring already done).
- **Reclassified (1):** P0-WEBVIEW403 → Low by the audit's own verifier (full-screen WebView route exists); handled in the Low phase.
- **Critical blockers:** none for code work; the only hard external dependency is `google-services.json` + a device/emulator to *verify* the Android Play/FCM/consent flows, and a product decision on ads.
- **Files changed this phase:** 9 locale catalogs + Details (presentation+ui) + adult assets + source-seed (domain/data/shared/composeApp/build) + Android host (platform/app/shared). 5 commits on `parity-fixes`.
- **Build/test status:** `:ui`, `:data`, `:composeApp` (desktop) and `:platform`, `:shared` (androidMain) all **compile green**. `:app` Android build blocked by missing `google-services.json` (pre-existing infra gap, not a code defect).

---

## Phase P1 (High) — summary
- **Findings:** 98 High across 37 sections — all triaged; in-scope code items fixed, the rest in the documented cross-cutting backlog above.
- **Fixed & compile-verified (screens):** Search (filter immediate-apply, query-blank, 3-action error), Statistics (7 row icons), About (icons+chevrons+scroll), What's New (faithful video poster + image carousel data), Details (4-button header, centered header, Resume FAB, chapter filter/sort sheet, per-chapter bookmark+download, NEW badge), Reader (nested-scroll zoom, auto-advance, auto-load, two-line title), Library (cover-scrim title, **0..8 items-per-row slider + persistence**, toggleable+back-closable search, fast-scroller), Complaint (admin closure-reason, device-metadata row, edit metadata-preservation), Home (default=list), Updates (Arabic headers, mark-read-on-tap, chapter template), Settings (14 row icons), Sources (back arrow), Language (system-language default), Downloads (localized error). **Cross-cutting:** BackHandler shim, system-back wiring, per-screen HTTP error mapping, search-error/sources-back/onboarding-3-step route wiring, networking dados cache, Coil OkHttp config, splash/theme day-night + edge-to-edge.
- **Resolved via P0 work (code-complete, needs device):** Play update/review/UMP-consent invocation + ActivityHolder (P0-PLAY-WIRE); startup source-seeding (P0-SRCSEED); the 9 missing locales (P0-LOC).
- **Blocked:** ads-monetization (3) — decision + device (P0-ADS). **Deferred (backlog above):** the `:data`-signature / `:ui`-structural / Android-analytics / inline-video / Italian-source / translation-completeness items.
- **Build/test:** `:composeApp`/`:ui`/`:data`/`:domain`/`:presentation` desktop compile + tests green; `:ui`/`:shared`/`:platform` android & iosSimulatorArm64 compile green. `:app` Android build blocked (no google-services.json) → `:app`-resident edits review-verified.
- **Commits:** 18 on `parity-fixes` (P1 batches 1–5 + wiring + app-shell + chapter-cache + tracking).

---

## Tallies
- **Critical (P0):** ✅ all 13 resolved-or-marked — 3 fixed+verified, 6 code-complete/needs-device, 3–4 ads blocked.
- **High (P1):** ✅ all 98 triaged — in-scope code fixed & compile-verified; remainder in the documented backlog or blocked (ads).
- **Medium (P2):** ✅ all 202 triaged — in-scope code fixed & compile-verified across every screen + cross-cutting; remainder deferred-with-plan or blocked.
- **User-reported:** 1 fixed+verified (library chapter caching).
- **Low (P3):** ✅ all 369 triaged — in-scope cosmetic/copy/a11y fixed & compile-verified across every screen; the large majority *intentionally-different* (KMP matches/improves native); rest deferred/blocked with plans.
- **Commits on `parity-fixes`:** 40+.

---

## ✅ FINAL SUMMARY — all 681 audit findings resolved-or-marked

**Disposition (every finding is in one of these terminal states):**
- **Fixed & compile-verified** — the bulk of P0–P3 in-scope code: localization (9 locales), adult hard-block gate, startup source seeding, **library chapter caching (user-reported)**, and per-screen feature/state/interaction + cosmetic/typography/spacing/icon/a11y fixes across all ~23 screens, plus cross-cutting seams (BackHandler, GestureExclusion, ScrollBarFadeDuration, per-screen HTTP error mapping, navigation route wiring, Room FK PRAGMA, networking timeouts/logging, Coil config, splash/theme).
- **Code-complete, needs on-device verification** — FCM push display + Play in-app update/review/UMP-consent wiring (+ ActivityHolder); compile-verified on `:platform`/`:shared`, `:app` build needs `google-services.json`.
- **Verified already-correct** — many items the audit flagged from stale data.
- **Intentionally-different (documented in code)** — items where KMP matches or deliberately improves on native (added empty/loading states, RTL-aware icons, labeled-clickable a11y, M3 redesigns, trimmed inputs, multiplatform substitutions).
- **Deferred-cross-cutting (with precise plans)** — the backlog above (`:data`/`:shared` signature/schema, `:platform` SPIs, `:app`-build/device, frozen scrapers).
- **Blocked (decision)** — AdMob ad **rendering** (consent+init wiring already done); awaiting product decision; rework contract intentionally deferred ads.

**Build/test:** full rework graph compiles on **desktop + Android + iOS**; `:domain`/`:data`/`:presentation` desktop unit tests pass. Three build breaks introduced mid-run were caught by the compile gate and fixed (nested-comment in SettingsScreen, cross-module smart-cast in HomeScreen, stale Room-KSP cache). `:app` Android *assemble* is the only thing not runnable here (missing `google-services.json` — pre-existing infra gap), so `:app`-resident edits are independent-review-verified.

**Process:** ~150 Opus 4.8 fix-agents + per-batch independent Opus 4.8 verifiers; every batch compile-gated and committed (40+ logical commits on `parity-fixes`); native app treated as source of truth throughout.
- **Build status:** all KMP modules compile on desktop (+android/iOS where relevant); desktop unit tests green. `:app` Android assemble blocked by missing `google-services.json` (pre-existing infra gap).

---

## Post-launch audit round (2026-06-03) — multi-agent review + top fixes

**Audit:** 18-reviewer multi-agent sweep (9 feature verticals + 9 cross-cutting), every finding
adversarially verified against code + `native-app/`. 35 confirmed (0 critical / 3 high / 8 medium /
24 low), 3 refuted. Full report: `APP_AUDIT_REVIEW.md`. The dedicated Compose UI-correctness
reviewer was re-run separately (it had crashed in-workflow): no high-severity defects, 2 benign lows.

**Fixed & verified this round (the 3 High + 2 of the Medium):**
- **HIGH Resume FAB** — `DetailsState.firstUnreadChapter` now scans reading order
  (`asReversed()` under descending) → resumes at oldest-unread, matching native.
- **HIGH Reader auto-advance** — both `ReaderScreen` pagers rewritten to `snapshotFlow` with a
  `previous < lastIndex` guard + single-page skip; 1-page chapters / resume-at-last-page no longer
  auto-skip. (Independently verified sound by the UI-correctness reviewer.)
- **HIGH i18n backfill** — every `:ui` locale (de/es/fr/in/it/ja/pt/ru/tr/ar) now at full 586-key
  parity: 1,820 entries ported verbatim from native-app's shipped translations (English-value match)
  + 2,072 net-new strings LLM-translated (0 placeholder mismatches, 0 missing, all XML well-formed).
- **MED Home pagination deadlock** — `HomeViewModel` clears `isLoadingNextPage` on every reset
  fetch + tab switch, so a refresh/tab-switch mid-page-load can't strand the bottom spinner.
- **MED CancellationException** — new `:core` `runCatchingCancellable` helper; applied to 8 repos /
  24 suspend `runCatching` sites. (Audit's 9th repo, LibraryPrefs, was a false positive — its
  `runCatching` wraps a pure `LocalDateTime.parse`, no cancellation point — correctly left as-is.)

**Build/test:** 3-target compile (Desktop + Android + iOS sim) GREEN; `:presentation`/`:data`/
`:domain` desktop tests GREEN (101 tests, 0 failures); desktop boot smoke clean (Koin resolves,
resources load, home feed fetches live, 0 exceptions). The remaining audit items (6 medium + low)
are catalogued in `APP_AUDIT_REVIEW.md` for follow-up.

### Medium-severity follow-ups (2026-06-03, same audit round)

Fixed the 4 remaining audit Mediums (the other 2 — Home pagination, Arabic-incomplete — were closed above):
- **MED #5 Multi-repo search loading** — `searchAllRepos` now seeds every enabled repo as `null`
  (loading) on the first emission and skips the legacy Loading state (`SearchRepository`/`UseCase`
  signature → `Map<String, AppResult?>`; `SearchRepositoryImpl`); `SearchViewModel` maps `null →
  UiState.Loading`, so each still-fetching source shows a per-section spinner instead of "No results".
- **MED #6 Library "Downloaded only"** — `LibraryViewModel` now observes `ObserveSettingsUseCase`,
  projects `downloadedOnly` into state, and `applyView` overrides the filter chip with a
  `hasDownloads` narrowing when on (native parity). New `FakeSettingsRepository` + a test proving the
  override.
- **MED #7 History/Updates details route** — widened `HistoryEffect`/`UpdatesEffect.NavigateToDetails`
  to the full identity tuple (api/language/title/url/cover) and routed History & Updates (rework +
  legacy adapters) to `Screen.MangaDetailsRework` instead of the URL-only `Screen.MangaDetails`, so
  saved manga bind membership/title/cover up-front (no empty-flash). Mirrors the Search/Home pattern.
- **MED #9 Arabic RTL on iOS/Desktop** — `App.kt` now provides `LocalLayoutDirection` from the chosen
  language (RTL subtags → `Rtl`), keyed on `appLanguage`, only on an explicit selection (system
  default keeps the platform direction so Android device-RTL is preserved).

**Build/test:** 3-target compile GREEN; `:presentation`/`:data`/`:domain` desktop tests GREEN (102
tests incl. the new Library override test, 0 failures); desktop boot smoke clean.
