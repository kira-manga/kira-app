# Prioritized Repair Plan — Yami KMP Full-App Audit

_Generated 2026-06-09. Companion to `AUDIT_FULL_APP_2026-06.md`. Ordered by severity, then unblocking impact / shared root-cause. Effort: S/M/L/XL._

> **Do step 0 first (cheapest, highest leverage):** run the compile gate `./gradlew :composeApp:compileKotlinDesktop :composeApp:compileDebugKotlinAndroid :composeApp:compileKotlinIosSimulatorArm64 --offline` + `:app:testDebugUnitTest`. Every "wires/compiles correctly" verdict in this audit is currently *inferred*, not observed.

| # | Sev | Effort | Area | Item |
|---|---|---|---|---|
| 1 | high | L | sources / refresh | Propagate server-side base/image-URL (domain-move) changes into stored manga/chapter/history/notification rows on source-registry refresh |
| 2 | high | M | home | Fetch and persist the full chapter list when saving a manga from the Home feed (fix zero-chapter library rows + Android notification burst) |
| 3 | high | L | image-loading | Add iOS/Desktop AVIF decode support (and harden the Skia decoder) so AVIF pages/covers render and offline CBZ AVIF reads work |
| 4 | medium | M | offline | Restore the offline connectivity gate on chapter-download actions and re-expose ConnectivityObserver via a domain port |
| 5 | medium | S | settings | Wire the missing callbacks on the live Settings route: enable Feedback social links and the admin Testing-Mode toggle |
| 6 | medium | S | sources | Make the generic-engine pages() fall back to the legacy adapter on an empty-but-success result |
| 7 | medium | M | statistics | Bracket reader reading-session timing on app lifecycle (ON_RESUME/ON_STOP) instead of composition lifetime so backgrounded time is not counted |
| 8 | medium | S | refresh / background-jobs | Give Android library refresh unique-work REPLACE semantics to stop duplicate concurrent refresh workers |
| 9 | medium | M | complaint-feedback | Preserve full complaint metadata on user-side Edit/Reply by re-fetching the legacy document (mirror admin-side pattern) |
| 10 | medium | S | downloads | Decide and revert/document the Downloads-screen per-row delete to native row-only semantics (currently wipes downloaded files+flag) |
| 11 | medium | S | platform-services | Wire native-parity analytics events (app_open, manga_open) into the rework |
| 12 | medium | S | persistence | Harden Desktop per-source headers persistence against the java.util.prefs 8KB value limit |
| 13 | low | M | details / library / refresh | Persist refreshed cover URL on in-library refresh across all paths (Details refresh + iOS/Desktop inline refresh-all) |
| 14 | low | S | settings | Reset the CBZ conversion progress StateFlow to idle on dialog dismissal |
| 15 | low | S | reader | Surface 'not in library' feedback when toggling the reader bookmark on a not-saved chapter |
| 16 | low | S | search | Make Multi-Search a submit-driven action, not auto-fired on tab swipe |
| 17 | low | S | error-empty-states | Add a .catch{} and crash-safety to the History VM load + deletes (restore native defensive parity) |
| 18 | low | M | offline | Add persistent disk-backed HttpCache + an offlineCacheInterceptor for manhastro /dados (offline image cache) |
| 19 | low | M | about-whatsnew / error-empty-states | Surface a real error+retry state on What's-New remote-fetch failure (restore native error pane) |
| 20 | low | S | platform-services / about-whatsnew | Wire a real Desktop version string into DesktopAppVersionProvider (fixes About version + frozen What's-New gate) |
| 21 | low | S | library | Thread the actual deleted-row count back from bulk-remove instead of returning the requested key count |
| 22 | low | S | error-empty-states / complaint-feedback | Localize the Settings feedback-failure snackbar and the admin-complaint action snackbars |
| 23 | low | S | home | Clear the Home featured (popular) carousel on source-tab switch |
| 24 | low | S | background-jobs | Persist library_last_updated from the iOS/Desktop in-process refresh so the 'Last updated' row updates |
| 25 | low | S | navigation-state | Reintroduce the Home-tab-reselect scroll-to-top hook |
| 26 | low | M | theme-lang | Seed first-run dark-mode default from device night mode (SystemThemeProvider) |
| 27 | medium | M | di-graph | Add Desktop/iOS Koin graph registration (and Desktop verify) tests |
| 28 | low | S | cbz-webp | Clean up partial-archive on mid-write failure in IosCbzWriter (mirror DesktopCbzWriter) |
| 29 | low | S | error-empty-states | Adopt launchSafely{} for the genuinely-uncaught History/Updates delete mutations |
| 30 | low | M | notifications-push | Wire iOS/Desktop new-chapter system notifications (or document NotificationPresenter as intentionally unconsumed) |
| 31 | low | M | navigation-state / details | Decide on iOS/Desktop BackHandler: wire to the nav host or formally accept the documented no-op |
| 32 | low | M | image-loading | Consider routing covers through the api-aware image request (rememberSourceImageRequest) instead of raw URL + host-match interceptor |
| 33 | low | L | docs (cross-cutting) | Documentation sweep: correct/strip stale audit-trail postscripts and KDocs that misstate live behavior or DI bindings |
| 34 | low | M | cross-cutting (mostly intentional / documented) | Decide and document the deliberate-divergence / lower-priority parity items (paged auto-advance mark-read, Library Auto cell 140dp, language-switch nav reset, version-name gate, mid-session locale, inline What's-New video, default features, updates toggle, schedulers inert, Android locale mechanism, focus-clear nav guard, iOS live-locale) |
| 35 | high | XL | platform-services | Decide AdMob monetization for the rework: port the rewarded-ad download gate + ad surfaces, or formally record 'no ads' as an intentional divergence |

---

### #1 — [high] Propagate server-side base/image-URL (domain-move) changes into stored manga/chapter/history/notification rows on source-registry refresh

- **Area:** sources / refresh  |  **Effort:** L
- **Why / rationale:** DEDUP of two identical findings (sources/SourceRegistryRefreshRepositoryImpl + refresh/refreshSources). SourceRegistryRefreshRepositoryImpl.upsert() only updates the `sources` registry row (updateBaseUrlAndVersionByName / updateImageBaseUrlAndVersionByName / updateSiteStateByName) and self-flags the omission in its own KDoc as 'Deliberate scope narrowing vs native'. Native (UpdateSourcesRepository.propagateUrlChanges/propagateImageUrlChanges) rewrites stored absolute URLs across manga/chapter/history/notification BEFORE the registry UPDATE. Without it, when a source's baseVersion/imageUrlVersion bumps (real domain/CDN move), every in-library manga/chapter/history/notification keeps a dead URL; listing/home/search recover via getBaseUrl() re-read, but opening a saved manga or reading from history calls fetch on the stale absolute URL and fails with no explanation. Highest severity, silently breaks saved content users rely on.
- **Approach & risk:** Highest-risk fix in the set. Requires re-adding DAO query+update methods removed in the 'Task #388' retire (getMangaByApi/getMangaIdsByApi/getChaptersByMangaId/getHistoryByApi/getNotificationsByApi + per-table URL updates), injecting four extra DAOs, and exactly mirroring native's replaceBaseUrl host-swap semantics (swap scheme+host, keep path/query/fragment). Per-row try/catch required so one bad row can't abort the rest. Must run BEFORE/with the registry UPDATE and key on baseVersion>currentBase (and imageUrlVersion). Touches Room DAO surface — schema-safe (queries only) but broad. Add unit tests for the host-swap.
- **Related findings:** sources: Startup source-registry refresh (base-URL / domain migration); refresh: Source registry refresh (startup remote source-list seed)

### #2 — [high] Fetch and persist the full chapter list when saving a manga from the Home feed (fix zero-chapter library rows + Android notification burst)

- **Area:** home  |  **Effort:** M
- **Why / rationale:** HomeViewModel.onSaveToggle -> toggleInLibrary(item.toManga()) with chapters=emptyList() (confirmed in source: toManga() copies no chapters and the use-case default is empty). Native HomeRoute fetches the full chapter list (getChaptersDataR) before toggleManga persists. Result: Home-saved manga get a library row with 0 saved_chapters; Library card shows 0 total, and an Android LibraryRefreshWorker run diffs the fetched list against an EMPTY saved set -> marks every chapter new and fires one notification per chapter (spam). The VM KDoc already (falsely) claims 'adding can fetch chapters before persisting', and the savingKeys spinner is already gated on isAdd, so it correctly covers the round-trip once the fetch is added. Wrong/incomplete persisted data vs native + concrete Android notification burst.
- **Approach & risk:** Medium. Add the same FetchMangaDetails/chapters fetch Details uses on the ADD branch only, then pass List<Chapter> into toggleInLibrary; keep REMOVE branch instant. Risk: a network failure on add must surface an error (already wired via onFailure -> HomeEffect.ShowError) and must not leave a half-saved row; ensure persistence is gated on a successful fetch. Note the notification-spam path is Android-only (legacy :app LibraryRefreshWorker), not the rework persistNewChaptersAndNotify (which is currently dead code).
- **Related findings:** home: Save/bookmark a manga from the Home feed row

### #3 — [high] Add iOS/Desktop AVIF decode support (and harden the Skia decoder) so AVIF pages/covers render and offline CBZ AVIF reads work

- **Area:** image-loading  |  **Effort:** L
- **Why / rationale:** Confirmed in source (HighQualitySkiaImageDecoder.decode): iOS/Desktop register only HighQualitySkiaImageDecoder, whose Image.makeFromEncoded(bytes) sits OUTSIDE the try block; Skiko has no libavif so AVIF input throws -> Coil error -> broken/blank page on AVIF-serving sources. Android decodes AVIF via AvifDecoderCoil. Also bites offline CBZ: iOS/Desktop writers transcode pages to WebP via SkiaWebpEncoder but fall back to VERBATIM bytes for formats Skia can't decode (AVIF) -> a .avif page is stored and then fails the same decoder on read-back. High user-visible impact for any AVIF source on two of three targets.
- **Approach & risk:** L-to-XL depending on approach. iOS: route AVIF bytes through ImageIO/UIImage (system AVIF since iOS 16) as a Decoder.Factory registered ahead of Skia. Desktop: needs a bundled AVIF decode path (imageio-avif plugin or JNI/libavif) — heavier and needs verification; a server-side/write-time transcode is an alternative. Interim low-risk mitigation regardless: move Image.makeFromEncoded inside the try block so an AVIF failure is a clean decode error, and (for CBZ) avoid the verbatim AVIF fallback by transcoding or rejecting on write. All edits in :platform (allowed). Needs on-device verification.
- **Related findings:** image-loading: AVIF-encoded chapter pages / covers — decode on iOS & Desktop

### #4 — [medium] Restore the offline connectivity gate on chapter-download actions and re-expose ConnectivityObserver via a domain port

- **Area:** offline  |  **Effort:** M
- **Why / rationale:** Confirmed: ConnectivityObserver is bound on all 3 targets but has ZERO live consumers (grep finds only KDoc/comment refs and the download repos documenting that the param was dropped). DetailsViewModel.onDownloadChapter/onDownloadAllClick enqueue unconditionally; offline users get no immediate feedback and the work fails/stalls downstream. The ported string no_internet_connection_please_try_again_when_you_re_online is referenced by zero Kotlin files. This is also the prerequisite connectivity input for the manhastro offline-cache item (rank 18). Re-uses live actuals + an already-ported string.
- **Approach & risk:** Medium. Add a narrow ObserveConnectivityUseCase backed by a thin :data repo delegating to platform ConnectivityObserver.observe(), collect into DetailsState.isOnline, early-return in the two download intents emitting a DetailsEffect mapped to the existing string. Risk is mostly plumbing across :domain/:data/:presentation/:ui; keep the typed-effect convention. Do not touch the owner-WIP HomeScreen or sources_repositry. Note the rework has only single + download-all paths (no custom/multi download), so only two gates to restore.
- **Related findings:** offline: Network-gated download UI (offline UX)

### #5 — [medium] Wire the missing callbacks on the live Settings route: enable Feedback social links and the admin Testing-Mode toggle

- **Area:** settings  |  **Effort:** S
- **Why / rationale:** DEDUP of two findings sharing one root cause, confirmed in source: SettingsRoute.kt (bound to Screen.Setting, the bottom-bar Settings tab) calls SettingsScreen(...) passing only viewModel+onNavigate, so onOpenUrl defaults to {} (every Feedback social button is a dead no-op) and isAdmin/initialTestingMode/onToggleTestingMode default to false/{} (admin Testing-Mode row never renders). The parallel SettingsReworkScreenRoute wires all four correctly but is bound to the never-navigated Screen.SettingsRework. Feedback dead links are a real user-facing medium bug; the admin toggle is low (its sole behavioral consumer, the ApiTestScreen harness, was intentionally dropped, so flipping it has no effect) but is fixed in the same one-line-cluster edit.
- **Approach & risk:** Low. In SettingsRoute.kt mirror SettingsReworkScreenRoute: koinInject an IntentLauncher and pass onOpenUrl = { launcher.openUrl(it) }; pass isAdmin = Admin.isAdmin, initialTestingMode = Admin.testingMode, onToggleTestingMode = { Admin.testingMode = !Admin.testingMode } (Admin already imported). Single-file change, both routes host the identical screen.
- **Related findings:** settings: Feedback dialog — social media links; settings: Admin 'Testing Mode' toggle

### #6 — [medium] Make the generic-engine pages() fall back to the legacy adapter on an empty-but-success result

- **Area:** sources  |  **Effort:** S
- **Why / rationale:** Confirmed in source (FallbackSourceClient.pages): `if (primaryResult is AppResult.Success) emit(primaryResult) else emitAll(fallback...)` — a generic config whose page.image selector silently mismatches returns Success(emptyList()) and short-circuits without falling back, yielding a blank reader instead of falling through to the working legacy parser. Breaches FallbackSourceClient's own documented 'pilot can never regress below legacy' guarantee for pages(). Bounded by golden-fixture parity tests but a live site changing its JSON/HTML shape post-release is the uncaught case.
- **Approach & risk:** Low and surgical. Mirror the existing chapters workaround (GenericSourceClient treats empty chapters as Failure) — either make GenericSourceClient.pages() return Failure on an empty page list, or add a non-empty guard in FallbackSourceClient.pages() before the Success short-circuit. Guard against legacy also being empty (emit the empty success rather than looping). Leave details/featured as-is (empty there can be legitimate). Both files are non-forbidden rework paths.
- **Related findings:** sources: Generic engine pages() fallback (FallbackSourceClient)

### #7 — [medium] Bracket reader reading-session timing on app lifecycle (ON_RESUME/ON_STOP) instead of composition lifetime so backgrounded time is not counted

- **Area:** statistics  |  **Effort:** M
- **Why / rationale:** Confirmed: ReaderScreen uses DisposableEffect(Unit) emitting OnScreenResumed on enter and OnScreenPaused on dispose, with no app-lifecycle observation. Composition is not disposed on backgrounding (Android stop-not-destroy / iOS background / Desktop minimize), so the entire backgrounded wall-clock span is added to persisted read_minutes (e.g. 2h backgrounded on the reader inflates a 35-min session to ~155 min). Downstream persistence is correct; the defect is purely the UI bracket. Silently inflates a non-critical statistic but is a clear regression vs native's lifecycle observer.
- **Approach & risk:** Medium. Replace DisposableEffect(Unit) with a LifecycleEventObserver via LocalLifecycleOwner (Compose-MP org.jetbrains.androidx.lifecycle exposes it), emitting OnScreenResumed on ON_RESUME/ON_START and OnScreenPaused on ON_STOP/ON_PAUSE, plus a final OnScreenPaused on onDispose for screen-leave. Existing repo idempotence (begin overwrite, end no-op when start==0) tolerates repeated cycles. Keep the no-key so intra-manga next/prev doesn't re-bracket. Needs per-platform lifecycle-emission verification.
- **Related findings:** statistics: Reading-session time capture (read-duration statistic)

### #8 — [medium] Give Android library refresh unique-work REPLACE semantics to stop duplicate concurrent refresh workers

- **Area:** refresh / background-jobs  |  **Effort:** S
- **Why / rationale:** DEDUP of two identical findings. AndroidBackgroundJobScheduler.scheduleOneOff uses plain workManager.enqueue(request) with only addTag('LibraryRefresh') — no unique-work name, no ExistingWorkPolicy, and the repo's Android branch overwrites currentJobId with no re-entry guard (only the iOS/Desktop inline branch is guarded). Two rapid pull-to-refreshes (esp. in the ENQUEUED window before isRefreshing flips) enqueue two LibraryRefreshWorkers, both foreground services sharing NOTIF_ID 42, racing on the same Room writes; spinner tracking is orphaned. Native uses enqueueUniqueWork(REFRESH_WORK_NAME, REPLACE, request). KDoc falsely claims 'REPLACE policy via re-schedule'.
- **Approach & risk:** Low. Add a uniqueWorkName to BackgroundJob (or a scheduleUnique) and have AndroidBackgroundJobScheduler call enqueueUniqueWork(job.tag, ExistingWorkPolicy.REPLACE, request) (+ enqueueUniquePeriodicWork for periodic); route library refresh through it. Also switch observation to getWorkInfosForUniqueWorkFlow(tag) so the spinner follows the replaced chain. Alternatively add the inlineRefreshing-style guard to the Android branch. Android-only, no cross-platform risk.
- **Related findings:** refresh: Library refresh dedup/throttle (Android user-initiated pull-to-refresh); background-jobs: Manual library refresh (Android scheduling)

### #9 — [medium] Preserve full complaint metadata on user-side Edit/Reply by re-fetching the legacy document (mirror admin-side pattern)

- **Area:** complaint-feedback  |  **Effort:** M
- **Why / rationale:** ComplaintActionRepositoryImpl.editComplaint/replyToComplaint rebuild Complaint.metadata from only 5 carved-out ComplaintSummary fields (preservedMetadata: appVersion/reason/replyto/osVersion/manufacturer) and then do a FULL .set/PATCH-with-full-body, so any other key is lost. Concretely drops model + osRelease (written by every KMP DeviceInfoProvider) and admin-written reasonAddedBy/reasonAddedAt. The admin-side AdminComplaintActionRepositoryImpl already re-fetches the full legacy Complaint and uses legacy.copy(...) and preserves everything. Silent loss of diagnostic/audit fields on user edit/reply.
- **Approach & risk:** Medium. Inject the legacy GetUserComplaintUseCase (or GetAllComplaintUseCase) into ComplaintActionRepositoryImpl, re-fetch the full legacy Complaint by id before edit/reply, and use legacy.copy(body=..., subject=...) so the original metadata map survives verbatim — exactly fetchLegacyById on the admin side. Costs one extra Firestore read per edit/reply (already accepted on admin side). Keeps Any out of :domain. Also correct the stale ComplaintActionRepositoryImpl KDoc claiming metadata is 'PRESERVED ... matching native parity'.
- **Related findings:** complaint-feedback: User-side complaint Edit and Reply (metadata preservation)

### #10 — [medium] Decide and revert/document the Downloads-screen per-row delete to native row-only semantics (currently wipes downloaded files+flag)

- **Area:** downloads  |  **Effort:** S
- **Why / rationale:** Adjusted to real-bug (behavioral parity divergence), not stale-doc. DownloadsActionRepositoryImpl.deleteDownload deletes the chapter_downloads row, calls markChaptersNotDownloaded (clears isDownloaded + localImagePaths), AND recursively deletes the on-disk chapterDir. Native deliberately splits this: Downloads-screen per-row delete is ROW-ONLY (keeps files + isDownloaded=1), while Library 'delete downloaded' does the full file+flag cleanup (already ported faithfully in KMP at LibraryRepository.deleteDownloadedChapters). The KMP impl merged Library-style cleanup into the Downloads-screen path, so deleting a SUCCESS row from the Downloads screen now wipes the downloaded chapter — a behavior native does not have.
- **Approach & risk:** Low. Product decision needed: native-wins -> revert DownloadsActionRepositoryImpl.deleteDownload to row-only (matches native + the existing KDoc); or keep the merged behavior and document it as an intentional divergence. Either way update the now-misleading DeleteDownloadUseCase/DownloadsActionRepository KDocs. User-initiated 'delete' (not silent loss), so low severity, but a genuine divergence.
- **Related findings:** downloads: Delete download

### #11 — [medium] Wire native-parity analytics events (app_open, manga_open) into the rework

- **Area:** platform-services  |  **Effort:** S
- **Why / rationale:** AnalyticsClient is bound on all platforms (Android -> real FirebaseAnalytics) but logEvent/setUserProperty/setUserId are never called anywhere (grep: zero non-doc hits), so even on Android no event is emitted. Native fired APP_OPEN at launch and manga_open on detail open. Lost product telemetry; medium because it's a non-functional but business-relevant gap and the wiring is cheap.
- **Approach & risk:** Low. Emit AnalyticsClient.logEvent('app_open') once at launch (MainActivity onCreate / App init) and logEvent('manga_open', params) when DetailsViewModel first loads a manga (inject AnalyticsClient or fire from the route adapter). Match native param keys. iOS/Desktop actuals are no-ops, so this is effectively free Android-only behavior until Firebase iOS lands.
- **Related findings:** platform-services: Analytics (Firebase Analytics)

### #12 — [medium] Harden Desktop per-source headers persistence against the java.util.prefs 8KB value limit

- **Area:** persistence  |  **Effort:** S
- **Why / rationale:** DataStoreHelper.saveHeadersForApi reads the whole headers map, inserts the per-API entry, and writes the ENTIRE map back as one JSON string under HEADERS_MAP_JSON via settings.putString (confirmed in source). On Desktop the backend is PreferencesSettings over java.util.prefs.Preferences, whose put() throws IllegalArgumentException above MAX_VALUE_LENGTH (8192 chars). 41 sources write into this single value with no length guard or try/catch; realistic Cloudflare cf_clearance/Cookie headers across several sources exceed 8KB and the putString throws, propagating out of saveHeadersForApi. The sibling ReadProgressRepositoryImpl already documents the analogous 80-char Desktop key limit, so the constraint is known.
- **Approach & risk:** Low-to-medium. Best fix: store one JSON value per API under a hashed/bounded key (the ReadProgressRepositoryImpl pattern), or persist via a dedicated file (DesktopSecureStorage-style). Minimum viable: wrap the putString in try/catch and log+degrade so one oversized write can't crash the source's header refresh. :platform code (allowed); sources_repositry callers unchanged. Per-API keying is the durable fix but touches the read path (headersMapFlow) too.
- **Related findings:** persistence: Per-source auth headers persistence (HEADERS_MAP_JSON) on Desktop

### #13 — [low] Persist refreshed cover URL on in-library refresh across all paths (Details refresh + iOS/Desktop inline refresh-all)

- **Area:** details / library / refresh  |  **Effort:** M
- **Why / rationale:** DEDUP of three related findings (details refresh cover; library iOS/Desktop inline cover; refresh iOS/Desktop inline cover). On a successful fetch for an in-library manga the rework only persists new chapters; the refreshed coverUrl is merged into in-memory state only and never written to the saved_manga Room row. Native's updateMangaImageUrlEverywhere fans a changed cover URL out to saved_manga + notifications + history (only when changed). So a server-changed cover stays stale on the next cache-first Details open and in Notifications/Updates + History. iOS/Desktop inline refresh-all has the same gap (Android worker does update covers). Low severity (covers rarely change; entry still works) but a shared fix.
- **Approach & risk:** Medium (touches multiple call sites). Expose a narrow LibraryRepository method (updateMangaImageUrlEverywhere-equivalent: saved_manga + notifications + history, only when URL differs) and call it fire-and-forget from (a) DetailsViewModel.runFetch.onSuccess when isInLibrary, and (b) the inline persistNewChaptersAndNotify / RefreshAllLibraryChaptersUseCase path. Keep to cover URL only (native persists no other metadata to Room). Mirror native's changed-only guard.
- **Related findings:** details: Refresh (pull-to-refresh / OnRetry) of an in-library manga; library: Library refresh — cover image update (iOS/Desktop inline path); refresh: Library refresh-all (Desktop/iOS in-process path)

### #14 — [low] Reset the CBZ conversion progress StateFlow to idle on dialog dismissal

- **Area:** settings  |  **Effort:** S
- **Why / rationale:** SettingsRepositoryImpl keeps conversionProgress at its terminal snapshot indefinitely (only reset at the START of the next run); OnDismissConversionDialog resets only the VM-local field, not the data-layer StateFlow, which replays its current value to new subscribers. A same-process navigate-away-and-back recreates the koinViewModel-scoped SettingsViewModel while the process-lifetime single repo persists, so the new VM's init collector re-projects the stale terminal snapshot and the Conversion-Complete/Stopped dialog re-appears with no run in progress. (Process-death is NOT a trigger — the in-memory flow resets to idle then.)
- **Approach & risk:** Low. Add a repository clearConversionProgress() that sets conversionProgress.value = CbzConversionProgress() and call it from OnDismissConversionDialog (via a use case), in addition to resetting the VM-local field. Native parity reference: CbzConversionViewModel.clearError() resets the actual StateFlow.
- **Related findings:** settings: CBZ conversion dialog dismissal

### #15 — [low] Surface 'not in library' feedback when toggling the reader bookmark on a not-saved chapter

- **Area:** reader  |  **Effort:** S
- **Why / rationale:** The reader bottom bar always renders the bookmark button with no in-library gate; tapping forwards to ChapterBookmarkRepositoryImpl.toggleBookmark which does getChapterIdByUrl(url) ?: return — a silent no-op when the chapter has no saved row, and the observe flow emits false so the star never changes. Native explicitly checks chapId==0 and shows a Toast 'You should add the manga to Library first'. Zero user feedback today.
- **Approach & risk:** Low. Have ToggleChapterBookmarkUseCase / the VM detect the not-in-library case (return a Boolean from the repo or pre-check via the observe flow) and emit a ReaderEffect.ShowError/ShowMessage so :ui shows a snackbar equivalent to native's toast. Keep the typed-error/effect convention (no String in presentation).
- **Related findings:** reader: Bookmark toggle from the reader for a not-in-library chapter

### #16 — [low] Make Multi-Search a submit-driven action, not auto-fired on tab swipe

- **Area:** search  |  **Effort:** S
- **Why / rationale:** The pager<->mode sync LaunchedEffect dispatches OnModeTabChange on every page swipe and onModeTabChange immediately calls runSearch -> runMultiSearch, so merely swiping Single->Multi with a query present kicks off the all-enabled-sources network fan-out; swiping back cancels and re-runs the single-source search (setting Loading and discarding prior results native preserves). Native decouples: search is submit-driven, tab switching is view-only. Per-gesture repeatable wasted-network/battery; results are still correct, so low (borderline medium).
- **Approach & risk:** Low. Make tab switching view-only (don't re-run in onModeTabChange) and fire the multi-repo fan-out only on explicit submit (add OnSubmit intent or have IME-search dispatch a mode-aware search). Pure presentation logic; verify single<->multi state retention.
- **Related findings:** search: Multi-source search trigger

### #17 — [low] Add a .catch{} and crash-safety to the History VM load + deletes (restore native defensive parity)

- **Area:** error-empty-states  |  **Effort:** S
- **Why / rationale:** HistoryViewModel subscribes to observeHistory() via .onEach{}.launchIn(viewModelScope) with NO .catch{}, and HistoryState defaults isLoading=true with no error field. If the upstream Room flow throws, the exception escapes the launchIn collector (the MviViewModel safety net wraps only submit()/launchSafely()) -> routes to the platform uncaught handler (crash on Android; scope/process termination on iOS/Desktop). Deletes are bare viewModelScope.launch with no handling. Native carries .catch + try/catch on the same DAO. Genuine cross-platform defensive-parity regression; low because the local SQLite flow rarely throws.
- **Approach & risk:** Low. Add .catch { updateState { it.copy(isLoading=false) } } (optionally an error field) before launchIn, and route deletes through the existing launchSafely{} helper. No UI change required.
- **Related findings:** error-empty-states: History screen — load/delete failure handling

### #18 — [low] Add persistent disk-backed HttpCache + an offlineCacheInterceptor for manhastro /dados (offline image cache)

- **Area:** offline  |  **Effort:** M
- **Why / rationale:** Only the cache-WRITE half of native's manhastro caching is ported (forceCacheForDados stamps max-age on /dados, Android-only attached); the cache-READ-when-offline half (native's offlineCacheInterceptor: onlyIfCached + maxStale 7d) is absent on all targets. Compounding: all three createHttpClient() actuals install(HttpCache) with NO storage arg, so the Ktor cache is in-memory only (wiped on process death) — there's no persistent payload to serve offline. Single niche PT source, offline-only UX, hence low. Depends on the connectivity port from rank 4.
- **Approach & risk:** Medium. Two parts: (1) configure a persistent disk-backed HttpCache (FileStorage) on the Ktor data path in each HttpClientFactory actual; (2) add an HttpSend interceptor that, when offline (via the rank-4 connectivity port) and the URL is under the /dados prefix, stamps Cache-Control: only-if-cached, max-stale=604800 before the HttpCache plugin. Scoped to the manhastro source. Porting only the interceptor without (1) would have no payload to read.
- **Related findings:** offline: Offline image cache for manhastro /dados

### #19 — [low] Surface a real error+retry state on What's-New remote-fetch failure (restore native error pane)

- **Area:** about-whatsnew / error-empty-states  |  **Effort:** M
- **Why / rationale:** DEDUP of two findings. WhatsNewRepositoryImpl.getFeatures() folds every failure into fallbackToDefaults() (empty list), the use case returns bare List<WhatsNewFeature> (no AppResult), and WhatsNewViewModel.loadFeatures has no failure branch — so WhatsNewState.errorMessage is never written and the wired ErrorState/Retry composable is dead. Native sets _loadError and (since its defaults are also empty) renders Failed-to-Load+Retry. So a transient network failure presents in KMP as a permanent 'No Updates Available' with no Retry. Non-core informational surface, hence low, but a real parity divergence (NOT stale-doc — the State KDoc intent is correct).
- **Approach & risk:** Medium. Thread failure through: use case returns AppResult (or a sealed outcome distinguishing genuine network failure from successful-but-empty), repo stops total-swallowing, VM writes errorMessage on failure to light the existing ErrorState/Retry path. Validate the always-empty-default case still shows EmptyState (not Error) on a successful empty response.
- **Related findings:** about-whatsnew: What's-New error surfacing; error-empty-states: What's-New dialog — remote fetch error state

### #20 — [low] Wire a real Desktop version string into DesktopAppVersionProvider (fixes About version + frozen What's-New gate)

- **Area:** platform-services / about-whatsnew  |  **Effort:** S
- **Why / rationale:** DEDUP of two findings. DesktopAppVersionProvider returns the compile-time constant versionName='1.0.0-desktop' while the real packaged version is 1.0.35 (desktopApp/build.gradle.kts). Consumed by About's Version row (shows a fake version) and by the What's-New show-once gate (currentVersion != lastVersion) — once '1.0.0-desktop' is marked seen, the gate is frozen forever so What's-New never auto-re-shows on real Desktop updates. New-platform not-yet-wired gap (no native Desktop baseline), the provider's own KDoc admits the deferred work.
- **Approach & risk:** Low. Inject the real version via a Gradle-injected build constant (System property from desktopApp packaging or generated BuildKonfig), or read Package.getImplementationVersion() from the JAR manifest. Desktop-only.
- **Related findings:** platform-services: App version (Desktop); about-whatsnew: What's-New show-once-per-version gate (Desktop)

### #21 — [low] Thread the actual deleted-row count back from bulk-remove instead of returning the requested key count

- **Area:** library  |  **Effort:** S
- **Why / rationale:** BulkRemoveFromLibraryUseCase returns keys.size, but LibraryRepositoryImpl.removeAllFromLibrary skips keys whose manga is not found (getIdByApiAndTitle ?: continue), so the 'Removed N items' snackbar can over-count. KMP-only feature (native has no bulk-remove-with-count), and selected keys are by construction in-library, so the edge case is narrow.
- **Approach & risk:** Low. Have removeAllFromLibrary return AppResult<Int> of rows actually purged and thread it through, or accept the input-size count and document it.
- **Related findings:** library: Bulk / single remove success message count

### #22 — [low] Localize the Settings feedback-failure snackbar and the admin-complaint action snackbars

- **Area:** error-empty-states / complaint-feedback  |  **Effort:** S
- **Why / rationale:** Adjusted/narrowed: most cited strangler-slice snackbars actually match native (user-complaint success literals and the downloads cancel/delete snackbar are native-faithful or KMP-extra). The two GENUINE divergences: (a) Settings feedback FAILURE interpolates the raw untranslated exception cause into the template, while native shows pure localized text (R.string.request_failed) and discards the throwable; (b) admin-side action snackbars hardcode English literals where native localizes (title_copied / status_updated_successfully / complaint_updated_successfully / complaint_deleted_successfully, present in all locale folders). Cosmetic, error/admin-only paths.
- **Approach & risk:** Low. For (a) drop the raw-exception interpolation in the Settings feedback-failure branch and show the localized request_failed string. For (b) replace the hardcoded admin English literals with the existing stringResource keys. No functional change.
- **Related findings:** error-empty-states: Strangler-slice snackbars

### #23 — [low] Clear the Home featured (popular) carousel on source-tab switch

- **Area:** home  |  **Effort:** S
- **Why / rationale:** Adjusted/narrowed to the tab-switch path only (drop the pull-to-refresh claim — native's onRefresh never touches the carousel). onTabSelected does not reset HomeState.featured, so after a tab switch, once the new feed lands but before the new featured lands, HomeList renders the PREVIOUS source's carousel above the new source's feed. Native's getPopularManga posts Success(emptyList()) immediately, blanking the carousel on every switch. Transient cosmetic flash.
- **Approach & risk:** Low. Clear featured = emptyList() in the onTabSelected reset copy (and optionally at the start of fetchFeaturedFeed). Pure presentation. Do not edit the owner-WIP HomeScreen directly; the reset is in the VM.
- **Related findings:** home: Source-tab switch — featured (popular) carousel

### #24 — [low] Persist library_last_updated from the iOS/Desktop in-process refresh so the 'Last updated' row updates

- **Area:** background-jobs  |  **Effort:** S
- **Why / rationale:** The only writer of library_last_updated is the Android-only LibraryRefreshWorker; the cross-platform in-process refresh path writes new chapters + notifications but never the timestamp cell, and LibraryPrefsRepository exposes observeLastUpdated read-only with no setter. So on iOS/Desktop the Library header shows its 'Not updated yet' fallback forever despite successful refreshes. KMP platform-extension gap (no native iOS/Desktop baseline); refresh + notifications still work, only a stale informational relative-time label.
- **Approach & risk:** Low. Add setLastUpdated to LibraryPrefsRepository (write library_last_updated as the same kotlinx LocalDateTime.toString() wire format the Android worker uses) and call it at the end of RefreshAllLibraryChaptersUseCase.invoke() success. Unifies the indicator without touching the Android worker.
- **Related findings:** background-jobs: Library 'Last updated' indicator after refresh (iOS/Desktop)

### #25 — [low] Reintroduce the Home-tab-reselect scroll-to-top hook

- **Area:** navigation-state  |  **Effort:** S
- **Why / rationale:** BottomNavigationBar's onClick for every tab (incl. Home) is a launchSingleTop navigate, so re-tapping the already-selected Home tab is a no-op; native scrolled Home to top on reselect via HomeTabReselectedHandler, which was never ported. Minor UX feature dropped on all platforms.
- **Approach & risk:** Low. Add a one-shot HomeIntent.OnScrollToTop consumed by the screen's LazyList/GridState, and in BottomNavigationBar.onClick branch `if (selected && screen is Screen.Home)` dispatch it instead of re-navigating. Expose the hook via the VM (do not edit the owner-WIP HomeScreen directly).
- **Related findings:** navigation-state: Bottom navigation — Home tab reselect (scroll-to-top)

### #26 — [low] Seed first-run dark-mode default from device night mode (SystemThemeProvider)

- **Area:** theme-lang  |  **Effort:** M
- **Why / rationale:** SettingsRepository.isDarkMode() returns false (light) when KEY_THEME_MODE is unset; native seeds from device UI_MODE_NIGHT_MASK. Masked at the root because followSystem defaults true, so the divergence only surfaces the first time a user on a dark device turns follow-system OFF without ever toggling dark/light (native stays dark, KMP flips to light). One-time, recoverable cosmetic flip. Effectively Android-only impact (native is Android-only; iOS/Desktop first-run theming is driven by isSystemInDarkTheme()).
- **Approach & risk:** Medium relative to payoff. Add an expect/actual SystemThemeProvider (Android Configuration.UI_MODE_NIGHT_MASK; iOS/Desktop dark-mode query) and use it as the isDarkMode() fallback when the key is unset. Edit only :shared/:platform.
- **Related findings:** theme-lang: Theme first-run dark-mode default

### #27 — [medium] Add Desktop/iOS Koin graph registration (and Desktop verify) tests

- **Area:** di-graph  |  **Effort:** M
- **Why / rationale:** Both DI graph tests (KoinGraphRegistrationTest, KoinGraphResolutionTest) live in the Android-only :app module, so expect fun platformModule() resolves to the Android actual ONLY; the iOS and Desktop PlatformModule actuals are never merged into any test container, and KoinGraphResolutionTest's own KDoc wrongly claims the registration test covers them. No commonTest/iosTest/desktopTest has any koinApplication/verify call. Production-readiness: a duplicate/missing/override conflict in the iOS or Desktop graph would not be caught by tests, only at runtime. Rated medium as a test-coverage gap on shippable targets.
- **Approach & risk:** Low (test-only, additive). Add a desktopTest (JVM) building koinApplication { modules(allSharedModules() + platformModule() + allReworkModules()) }.close() so the Desktop actual is registered and conflicts surface; add an iosSimulatorArm64Test doing the same registration smoke test (iOS actuals are no-arg, no androidContext). Optionally run verify() on the Desktop graph. No production code touched.
- **Related findings:** di-graph: DI graph integrity tests (KoinGraphRegistrationTest / KoinGraphResolutionTest)

### #28 — [low] Clean up partial-archive on mid-write failure in IosCbzWriter (mirror DesktopCbzWriter)

- **Area:** cbz-webp  |  **Effort:** S
- **Why / rationale:** IosCbzWriter.archive() wraps only a per-PAGE try/catch; if the okio sink fails (e.g. disk-full during zip.finish() writing the central directory, outside any try), the exception propagates, the sink closes, and a partial/corrupt chapter_<id>.cbz is left on disk. Downstream sizeBytes (folderSize after archiving) then double-counts loose pages + the stray .cbz, and a reader's openZip on the corrupt file fails. DesktopCbzWriter already wraps the whole archive in try { } catch { delete; throw }.
- **Approach & risk:** Low and localized. Wrap the IosCbzWriter sink/zip block in an outer try { system.sink(...).use { ... } } catch (t) { runCatching { system.delete(cbzPath) }; throw t } (re-throw CancellationException as-is), mirroring DesktopCbzWriter. Optionally hoist the partial-cleanup policy into the shared StoreZipWriter caller so iOS+Desktop share one path. :platform code (allowed).
- **Related findings:** cbz-webp: iOS CBZ archiving — partial-archive cleanup on mid-write failure

### #29 — [low] Adopt launchSafely{} for the genuinely-uncaught History/Updates delete mutations

- **Area:** error-empty-states  |  **Effort:** S
- **Why / rationale:** Adjusted/narrowed: the broad 'all mutating handlers crash the process' framing is wrong — Sources toggles swallow exceptions internally and Settings setToggle returns Result, so they can't throw. The only genuinely uncatch'd repo writes are the History (HistoryRepositoryImpl) and Updates (UpdatesRepositoryImpl) DAO deletes via bare viewModelScope.launch{}, which escape the MviViewModel safety net (it wraps only submit()/launchSafely()). These are at parity with native's identical bare launches, so it's latent robustness/convention drift on local-SQLite mutations that effectively never throw — not a real crash risk.
- **Approach & risk:** Low. Route the History/Updates delete mutations through the existing launchSafely{} helper (or .onFailure to log/surface). Overlaps with rank 17 (History). Drop the Sources/Settings citations.
- **Related findings:** error-empty-states: All mutating MVI handlers — bare viewModelScope.launch{}

### #30 — [low] Wire iOS/Desktop new-chapter system notifications (or document NotificationPresenter as intentionally unconsumed)

- **Area:** notifications-push  |  **Effort:** M
- **Why / rationale:** All three NotificationPresenter actuals are fully implemented and bound but never invoked (only MyApp eagerly constructs one). On Android the notification surface is fully covered by direct NotificationManager paths (ChapterNotificationHelper + MyFirebaseMessagingService), so the facade is harmless dead code there. On iOS/Desktop the new-chapter inline refresh path only writes Room notifications rows and never surfaces a system notification — but native is Android-only, so this is dead-code/production-readiness, not a parity regression.
- **Approach & risk:** Low. Lowest-risk: drop the eager get<NotificationPresenter>() in MyApp and document the facade as not-yet-consumed (also correct the stale NotificationPresenter.kt KDoc claiming a legacy :shared facade is 'still LIVE'). Feature-complete alternative: call presenter.show(...) from a use case after persistNewChaptersAndNotify on non-Android targets to give iOS/Desktop users real notifications. Decide per product intent.
- **Related findings:** notifications-push: NotificationPresenter SPI dead code; notifications-push: MyApp NotificationPresenter eager init (stale doc)

### #31 — [low] Decide on iOS/Desktop BackHandler: wire to the nav host or formally accept the documented no-op

- **Area:** navigation-state / details  |  **Effort:** M
- **Why / rationale:** DEDUP of three findings (Details selection-mode back; Home search-overlay + Downloads back; nav-state BackHandler). BackHandler.ios.kt/.desktop.kt are intentional no-ops, so system-back/edge-swipe does not intercept on those targets for the search overlay, chapter-selection mode, or Downloads back. Every call site has a working on-screen affordance (Close/back buttons), and native is Android-only (so no parity baseline on iOS/Desktop). Polish item, documented app-wide decision.
- **Approach & risk:** Medium if implemented (iOS swipe-back is owned by the nav host; would need NavigationEvent integration; Desktop could honor Esc via a key handler). Lowest-risk action: leave the no-ops, formally accept the decision, and ensure every BackHandler call site keeps its on-screen Close/back affordance (they currently do). Update the App.kt KDoc that frames BackHandler as unimplemented (see rank 33).
- **Related findings:** details: Chapter multi-select exit via system back; navigation-state: System back / in-screen back interception; navigation-state: BackHandler no-op iOS/Desktop

### #32 — [low] Consider routing covers through the api-aware image request (rememberSourceImageRequest) instead of raw URL + host-match interceptor

- **Area:** image-loading  |  **Effort:** M
- **Why / rationale:** Adjusted down from high to low/medium-effort, narrowed scope. Covers render via AsyncImage(model = coverUrl) (raw URL); headers are attached only by the singleton CoilSourceHeaderInterceptor, which skips when defaultHeaders is empty and matches by host. The cold-start empty-headers window is NOT a native regression (native also renders empty headers for unvisited sources after cold start), and findRepoByHost already matches baseUrl+BASE_URL+imgBaseUrl so most CDN covers match. The genuine narrow divergence: for a Cloudflare-protected source whose cover CDN host is outside the stored hosts, KMP loads the cover without auth where native (api-identity) would not — failure mode is a graceful placeholder, not a crash.
- **Approach & risk:** Medium plumbing. Set composeApp coverModel to rememberSourceImageRequest(url=item.coverUrl, api=item.api) (does ensureSiteInitialized hydration + direct httpHeaders attach, bypassing the host-match) for Home/Search, and expose the same slot for LibraryScreen's LibraryCardCover (api is in scope). :ui slot plumbing + :composeApp adapters only; do NOT edit sources_repositry. Lower priority given the corrected (medium->low) impact.
- **Related findings:** image-loading: Cover images — per-source Cloudflare auth headers

### #33 — [low] Documentation sweep: correct/strip stale audit-trail postscripts and KDocs that misstate live behavior or DI bindings

- **Area:** docs (cross-cutting)  |  **Effort:** L
- **Why / rationale:** Large DEDUP of all confirmed stale-doc findings. Per CLAUDE.md these machine-generated postscripts are non-authoritative, but several actively mislead future audits into chasing non-existent bugs. Clusters: (a) CBZ WebP — CbzWriter.kt postscript + CoroutineDownloadRepositoryImpl + PlatformModule.ios/desktop + SettingsReworkModule all falsely claim iOS throws NotImplementedError / Desktop encodes PNG (all three actuals are real WebP writers); (b) BackgroundJobScheduler / LibraryRefreshRepository — three scheduler postscripts + interface KDoc claim the rework actuals are unbound and a deleted legacy :shared core.jobs.BackgroundJobScheduler is live; (c) Platform-facade postscripts (ads/analytics/crash/review/update/consent/version/intent/toast/device/push) claim :platform actuals 'not yet bound / legacy core.platform is live' — the legacy classes were deleted and :platform is the only binding (rename the misleading 'as LegacyAppVersionProvider' aliases); (d) DI module postscripts with stale counts/signatures (allReworkModules '15' vs 17; CoroutineDownloadRepositoryImpl '6-param' vs 9); (e) SourcesGenericModule + ReworkModules 'BOUND but NOT consumed by :data' / 'only Azora' (12 PILOT_APIS, consumed by 4 :data repos); (f) MangaDatabase 'v8 + 7 migrations' (actually v10 + 9 migrations) incl. CLAUDE.md; (g) LibrarySort.LAST_READ KDoc (sorts lastOpenedAt not lastReadAt); (h) ChapterPagesRepository offline-branch 'deferred/no caller'; (i) HomeViewModel.onEndReached '/data fetchMore doesn't write back' (it does); (j) HomeReworkScreenRoute/HomeScreen WebView 'blank url' KDoc; (k) SettingsReworkScreenRoute / AboutReworkScreenRoute 'not user-facing yet'; (l) App.kt / safePopBackStack 'no BackHandler shim / ReaderNav printlns survive'; (m) RemoteDocStore 'is the complaint backend' (dead, zero consumers); (n) DownloadsActionRepository/DeleteDownloadUseCase delete-scope KDoc (paired with rank 10); (o) ObserveReadingStatisticsUseCase invented 8-flow combine signature.
- **Approach & risk:** Very low runtime risk (doc-only) but tedious and wide. Best done as one careful pass to avoid re-introducing drift. Recommend doing the code-fix items first, then this sweep so doc reflects the final state (and the rank-10 downloads-delete KDoc tracks whichever behavior is chosen). No code change except the misleading import-alias renames in (c).
- **Related findings:** downloads: CBZ-on-download stale KDoc; cbz-webp: CBZ SPI documentation stale KDoc; settings: CBZ compress-existing stale KDoc; background-jobs: BackgroundJobScheduler KDoc postscripts; refresh: LibraryRefreshRepository / scheduler documentation; library: Scheduler actual KDocs falsely claim unbound; library: LibrarySort.LAST_READ KDoc; persistence: Schema/migration documentation vs actual DB version; offline: Stale documentation — offline reading wiring; home: Open-in-WebView KDoc accuracy; home: Infinite-scroll fetchMore KDoc; sources: Generic-sources subsystem DI / activation stale KDoc; di-graph: Generic-sources subsystem DI (SourcesGenericModule); di-graph: ReworkModules / PlatformModule / SharedModule postscripts; settings: Settings route discoverability docs; about-whatsnew: About / What's-New / Welcome KDoc accuracy; platform-services: All platform-service facade KDoc/postscripts; navigation-state: NavHost root composable documentation (App.kt); complaint-feedback: RemoteDocStore dead infrastructure; statistics: ObserveReadingStatisticsUseCase KDoc

### #34 — [low] Decide and document the deliberate-divergence / lower-priority parity items (paged auto-advance mark-read, Library Auto cell 140dp, language-switch nav reset, version-name gate, mid-session locale, inline What's-New video, default features, updates toggle, schedulers inert, Android locale mechanism, focus-clear nav guard, iOS live-locale)

- **Area:** cross-cutting (mostly intentional / documented)  |  **Effort:** M
- **Why / rationale:** Bucket of confirmed-but-intentional or accept-as-is items, each needing a one-line product decision + a doc note rather than code: paged-mode swipe-past-last-page marks the leaving chapter read (real-bug vs native, but arguably better UX — decide divergence vs route reach-end through a non-mark-read path); Library Auto-mode cell 120dp vs native 140dp (one-constant change at GridDensity.COMFORTABLE or accept); language-switch key(appLanguage) resets nav to start destination (matches native recreate on Android; Desktop is pure KMP choice — accept or hoist NavController above the key); What's-New gate uses versionName-string inequality vs native versionCode> (documented portability tradeoff; downgrades are store-blocked); iOS language change is restart-only (compose-resources limitation, restart hint shown); What's-New videos open externally (no :ui-usable inline player SPI; existing VideoPlayerSlot is :composeApp + Android-only actual); getDefaultFeatures() empty (parity with native); updates per-row mark-read is a NOT-isRead toggle (intentional parity — NOT a defect, NOT stale-doc); iOS/Desktop schedulers inert (refresh runs inline; add an interface-doc warning or debug assert to catch future misuse); Android in-app locale uses Compose LocalConfiguration not AppCompat (works; residual gap is only API33+ system picker registration on ComponentActivity — optionally make MainActivity AppCompatActivity); safeNavigate dropped native focus-clear (add LocalFocusManager.clearFocus() only if Android focus-during-nav crashes are observed); What's-New default-features empty fallback (parity).
- **Approach & risk:** Low. Most need no code; where a fix is chosen it's small and localized (e.g. GridDensity 140dp, a reader reach-end intent split, MainActivity AppCompat). The Updates mark-read toggle should NOT be 'fixed' (it's native parity); record it as an audited decision. The chief risk is silently diverging from native — capture each decision explicitly.
- **Related findings:** reader: Mark-chapter-read on paged-mode auto-advance; library: Library grid Auto cell sizing (120 vs 140dp); theme-lang: Mid-session language change resets navigation; about-whatsnew: What's-New version comparison semantics; theme-lang: In-app language switch (iOS) restart-only; about-whatsnew: What's-New inline video playback; about-whatsnew: What's-New default feature fallback; updates: Updates per-row mark-read toggle; background-jobs: BackgroundJobScheduler abstraction (iOS/Desktop) inert; theme-lang: In-app language switch (Android per-app locale); navigation-state: safeNavigate focus-clear guard; history: History cover refresh mangaId zero

### #35 — [high] Decide AdMob monetization for the rework: port the rewarded-ad download gate + ad surfaces, or formally record 'no ads' as an intentional divergence

- **Area:** platform-services  |  **Effort:** XL
- **Why / rationale:** AdProvider is bound on every platform but has ZERO consumers (confirmed-style: no load/show calls anywhere); MobileAds.initialize runs but nothing ever requests/shows an ad. The rework download path goes straight to enqueue with no rewarded-ad gate, and there's no reader banner or native-ad list interleaving. Native (Android-only) had the rewarded-download gate, reader banner, and native-ad list. Adjusted: platforms are effectively Android-only (iOS Free tier serves no ads; Desktop has no JVM AdMob SDK — those no-op stubs are intentional). Rated high because it currently reads as an unfinished port of a revenue feature, not a decision — but it is the largest single effort and is gated on a product call, so it's ranked last among the high-severity items.
- **Approach & risk:** XL and product-gated. If parity required: port a presentation-layer AdCoordinator mirroring native's AdViewModel (per-download counter -> every Nth show rewarded via AdProvider.showRewarded, proceed only on EarnedReward, block-with-feedback on Dismissed, fall through on NotLoaded/Failed), plus banner + native-ad list Compose slots (Android-only). The thin load/show facade cannot express native's gate semantics or queue-based list model, so AdProvider likely needs extending with preload/ready-state/retry — significant surface change. If the decision is 'no ads in KMP', record it explicitly so it stops reading as unfinished work. Either way, do not let this block the other repairs.
- **Related findings:** platform-services: Ads (AdMob) — rewarded-ad download gate, banners, native-ad interleaving
