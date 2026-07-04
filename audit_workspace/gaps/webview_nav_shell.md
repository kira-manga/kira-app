## WebView, Navigation, Shell & Theming — gaps

Gap count: 22 (WV: 11, NAV: 4, SHELL: 4, THEME: 3). Priority spread: P0 ×3, P1 ×8, P2 ×6, P3 ×5.

---

# ① WebView Screen

### GAP-WV-01 — Same-host URL sandbox (`shouldOverrideUrlLoading` / `isAllowed`) not ported
- **Screen/surface:** WebView
- **Type:** MISSING_FEATURE
- **OLD (target):** `WebViewComposeScreen.kt:188-211` (`shouldOverrideUrlLoading` blocks main-frame nav unless `isAllowed()` — same-host as `initialUrl`, http/https only, `urlValidationCache` at `:165-186`; sub-frame `javascript`/`file` schemes blocked; `about:`/`data:` always allowed).
- **KMP (current):** `WebViewComposeScreen.kt:142-280` + actuals — no override gate; Android/iOS/Desktop actuals just `loadUrl(url)` (kmp audit `:33`, `:35`).
- **Priority:** P1
- **Acceptance criteria:** Main-frame navigations to a host other than the initial host are blocked (no load occurs); same-host http/https loads proceed; `about:`/`data:` allowed; sub-frame `javascript:`/`file:` blocked. Verifiable by attempting an off-host link and asserting the URL bar / `onPageFinished` host is unchanged.
- **Notes:** Load-bearing for the Cloudflare flow — keeping the WebView pinned to the source host prevents redirect drift that loses the `cf_clearance` context. Implement in `WebViewController`/expect layer (a `shouldOverrideUrlLoading`-equivalent on Android `WebViewClient`, `decidePolicyForNavigationAction` on iOS WKWebView, `CefRequestHandler.onBeforeBrowse` on Desktop). `urlValidationCache` is an optimization, not required for parity.

### GAP-WV-02 — `shouldInterceptRequest` background cookie-merge channel dropped
- **Screen/surface:** WebView
- **Type:** BEHAVIOR
- **OLD (target):** `WebViewComposeScreen.kt:214-245` — `shouldInterceptRequest` fires per-request; when request host contains the initial host, a `Dispatchers.IO` coroutine reads `CookieManager.getCookie(initialUrl)`, merges into request headers as `Cookie`, posts to `savedHeaders` on Main.
- **KMP (current):** kmp audit `:30-33` — capture is now `onCookiesAvailable(cookieHeader)` fired from `onPageFinished` (Android `WebViewHost.android.kt:53`; iOS `:170-181`; Desktop `:275-307`). No per-request interception.
- **Priority:** P2
- **Acceptance criteria:** Cookies (specifically `cf_clearance` / `__cf_bm`) captured after a Cloudflare challenge are still surfaced into `savedHeaders` and persisted on Save. Confirm the captured Cookie header on a known Cloudflare-gated source matches what the OLD app saved.
- **Notes:** The KMP `onPageFinished`-driven capture is a deliberate substitute and likely sufficient (memory: Bug-4-layer-2 keeps Cookie+UA). Risk: if the challenge resolves via a sub-resource request without a full page-finish, the page-finished hook may miss it where `shouldInterceptRequest` would have caught it. Assess against a live CF source before downgrading to P3. Keep as a behavior-parity watch item, not a re-port.

### GAP-WV-03 — Render-process-gone crash recovery / auto-recreation removed
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:277-293` — `onRenderProcessGone` cleans up, sets `webViewError=true`, bumps `recreationKey` to force recreation, clears history/headers; body shows `Text("WebView crashed. Recreating...")` (`:518`).
- **KMP (current):** kmp audit `:19` — no commonMain crash UI, no render-process-gone recovery/recreation; Android actual only `stopLoading()+destroy()` on dispose (`WebViewHost.android.kt:84-87`).
- **Priority:** P2
- **Acceptance criteria:** Document the deviation. On Android, a render-process crash should not hard-crash the app; minimum bar is graceful teardown. Full auto-recreation + "Recreating..." UI is optional. iOS/Desktop have no equivalent crash signal.
- **Notes:** Android WebView internal (`onRenderProcessGone` is `android.webkit`-only). Substitute: catch the platform crash callback in `WebViewHost.android.kt` and rebuild the controller, or accept teardown-only. Rationale: OOM render kills are an Android-specific failure mode; iOS/Desktop don't expose it. Recommend at least re-adding the Android crash hook so a CF page that OOMs doesn't take the activity down.

### GAP-WV-04 — Custom in-memory back/forward history (50-entry) + BackHandler not ported
- **Screen/surface:** WebView
- **Type:** BEHAVIOR
- **OLD (target):** `WebViewComposeScreen.kt:258-266` (capped 50-entry `mutableStateListOf<String>`, forward branch truncated on new nav, `historyIndex`) + `BackHandler` `:364-383` (steps back through in-memory history, else cleanup+close).
- **KMP (current):** kmp audit `:22,:24` — no `BackHandler`; uses the WebView's native back/forward list via the controller (`canGoBack()/canGoForward()`); system back pops the nav back stack normally.
- **Priority:** P2
- **Acceptance criteria:** Hardware/gesture back inside the WebView navigates back through page history before leaving the screen; only when no in-page history remains does back close the WebView. Forward button reachable after going back.
- **Notes:** Native back/forward list is the more correct model and is cross-platform — the divergence is acceptable. The user-visible gap is the back-button BEHAVIOR: in OLD, system back walked WebView history first; in KMP it pops the nav stack and leaves the screen. Add a `BackHandler` (Android) / equivalent that calls `controller.goBack()` while `canGoBack`, else closes. iOS/Desktop: wire the platform back affordance similarly. The 50-entry cap and forward-truncation are implementation detail, not parity targets.

### GAP-WV-05 — Pinch/zoom controls not configured
- **Screen/surface:** WebView (Android, iOS)
- **Type:** MISSING_FEATURE
- **OLD (target):** `WebViewComposeScreen.kt:126-128` — `setSupportZoom(true)`, `builtInZoomControls=true`, `displayZoomControls=false` (pinch zoom on, no on-screen buttons).
- **KMP (current):** kmp audit `:25` — not configured in commonMain; Android actual does not set `setSupportZoom`/`builtInZoomControls`.
- **Priority:** P2
- **Acceptance criteria:** Pinch-to-zoom works inside the WebView on Android (and iOS WKWebView, which is on by default); no visible zoom buttons. Verify by pinching a source page.
- **Notes:** Trivial Android fix in `WebViewHost.android.kt` settings block. iOS WKWebView allows zoom by default (likely already parity). Desktop KCEF has native zoom. Low effort, restores expected browser feel for image-heavy source pages.

### GAP-WV-06 — ANR-hardening WebSettings (cacheMode, hardware layer, focus guards) dropped
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:116-151` — `cacheMode=LOAD_CACHE_ELSE_NETWORK` (ANR mitigation), `RenderPriority.HIGH`, `LAYER_TYPE_HARDWARE`, `allowContentAccess=false`, `allowFileAccess=false`, `mediaPlaybackRequiresUserGesture=true`, `mixedContentMode=COMPATIBILITY_MODE`, geolocation off, `isFocusable=false`/`isFocusableInTouchMode=false` (focus-crash dodge).
- **KMP (current):** kmp audit `:35` — Android actual sets JS+DOM+DB enabled, `loadWithOverviewMode`, `useWideViewPort`, optional UA override only; `cacheMode`, hardware-layer, focus-crash guards all ABSENT.
- **Priority:** P1
- **Acceptance criteria:** Restore the security-relevant settings on Android: `allowFileAccess=false`, `allowContentAccess=false`, file-URL access off, `mixedContentMode=COMPATIBILITY`, geolocation off, `mediaPlaybackRequiresUserGesture=true`. ANR cacheMode + hardware layer + focus guards are best-effort.
- **Notes:** Split priority — the `allowFileAccess`/`allowContentAccess`/mixed-content/geolocation flags are a SECURITY posture (P1, easy, must restore in `WebViewHost.android.kt`). The `cacheMode`/`LAYER_TYPE_HARDWARE`/focus-crash guards are Android-specific ANR/stability tuning (DEVIATION, optional). iOS/Desktop have no equivalents.

### GAP-WV-07 — Lifecycle pause/resume (timers) not wired on Android
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:326-361` — `DisposableEffect` wires `onResume→resumeTimers`, `onPause→pauseTimers`, `onDestroy→cleanup`.
- **KMP (current):** kmp audit `:35` — lifecycle pause/resume from OLD absent; Android actual only `stopLoading()+destroy()` on dispose.
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Optional: pause WebView JS timers when the app backgrounds to save battery/CPU.
- **Notes:** `pauseTimers`/`resumeTimers` are `android.webkit`-only and tied to Android lifecycle. iOS/Desktop manage this differently. Low user impact for a short-lived modal browser; safe to defer.

### GAP-WV-08 — `cleanupWebView` (clearCache/clearHistory/removeView/destroy) reduced
- **Screen/surface:** WebView (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `WebViewComposeScreen.kt:86-99` — `clearFocus, stopLoading, clearCache(true), clearHistory, removeView from parent, destroy`, all try/catch-guarded; invoked on close/back/crash.
- **KMP (current):** kmp audit `:35` — Android actual does `stopLoading()+destroy()` on dispose only (`WebViewHost.android.kt:84-87`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Ensure no WebView instance leak on repeated open/close (destroy is called). Full cache/history clear optional.
- **Notes:** `destroy()` covers the leak risk. `clearCache(true)`/`clearHistory` were partly tied to the crash-recovery path (GAP-WV-03). Bundle any revisit with WV-03/WV-04.

### GAP-WV-09 — WebView chrome strings hardcoded (un-localized)
- **Screen/surface:** WebView
- **Type:** VISUAL
- **OLD (target):** OLD uses `pageTitle.ifEmpty{"Loading..."}` literal too, but action icons carry no visible text; OLD title literal at `WebViewComposeScreen.kt:398`.
- **KMP (current):** `WebViewComposeScreen.kt:183,189,208,217,224,230` — hardcoded English literals "Loading...", "Close", "Back", "Forward", "Reload", "Save Headers" (kmp audit `:15,:248`). Every other surface in the app goes through compose-resources.
- **Priority:** P2
- **Acceptance criteria:** WebView chrome strings resolved via `stringResource(Res.string.…)` with Arabic parity entries; "Loading...", "Close", "Back", "Forward", "Reload", "Save Headers" all localized.
- **Notes:** Only un-localized surface in the cluster. Most are `contentDescription`s (a11y) rather than visible labels, so user-visible impact is small, but it breaks the app-wide localization invariant. Reuse existing keys where they exist (Close/Back are common).

### GAP-WV-10 — Desktop ignores per-browser User-Agent override
- **Screen/surface:** WebView (Desktop)
- **Type:** DEVIATION(platform)
- **OLD (target):** N/A (OLD is Android-only; UA override is implicit via Android WebView settings).
- **KMP (current):** `WebViewHost.desktop.kt:190-195` — `userAgent` input param IGNORED; JCEF has no per-browser UA override (kmp audit `:37`).
- **Priority:** P2
- **Acceptance criteria:** Document deviation. If a global JCEF UA can be set at client init, set it to match the captured/source UA so `cf_clearance` (UA-bound) stays valid on Desktop.
- **Notes:** JCEF limitation. Bug-4-layer-2 (memory) says `cf_clearance` is bound to the UA that earned it — on Desktop, if the browsing UA ≠ the UA later sent with image requests, the clearance cookie is rejected. Investigate `CefSettings`/command-line `--user-agent` at `KCEF` init as a workaround. Desktop-only; lower user base.

### GAP-WV-11 — `WebViewController` / nav-controls layer is a KMP addition
- **Screen/surface:** WebView
- **Type:** KMP-EXTRA
- **OLD (target):** OLD drove the WebView imperatively inside the composable; no controller abstraction (`WebViewComposeScreen.kt:102` `LaunchedEffect` building the instance).
- **KMP (current):** `core/webview/WebViewController.kt:20-57` + `WebViewHost.kt:22-31` expect/actual — clean controller exposing goBack/goForward/reload/progress (kmp audit `:13,:24`; restored by task #742).
- **Priority:** P3
- **Acceptance criteria:** N/A (improvement). No action needed; nav controls already at parity (Back/Forward/Reload/Save/Close all present, kmp audit `:23`).
- **Notes:** Healthy refactor enabling 3-platform support. Recorded for completeness; the OLD KDoc that forecast this as a TODO is now FULFILLED (kmp audit `:256`).

---

# ② Navigation graph

### GAP-NAV-01 — `LibraryMangaDetails(mangaId: Long)` route fully retired
- **Screen/surface:** Navigation graph
- **Type:** MISSING_FEATURE
- **OLD (target):** `NavGraphV2.kt:469-497` — `LibraryMangaDetails` (data, `mangaId:Long`) → `LibraryMangaRoute`, the by-ID local/offline details path. Reached from Library item click and `onOpenRandomClick` (`:284-300`).
- **KMP (current):** kmp audit `:92` — `LibraryMangaDetails` key fully retired (App.kt:104-109 comment); all details now route through `Screen.MangaDetails(mangaUrl, api)` → `MangaDetailsByUrlReworkScreenRoute`.
- **Priority:** P1
- **Acceptance criteria:** Tapping a library item opens its details from the LOCAL DB (offline-capable), not a fresh network-by-URL fetch; "Open random" from Library opens a random library manga's details offline. Behavior matches OLD's by-ID path.
- **Notes:** Cross-check with memory "Details offline-DB fix (REAL)" (`dae40c0`) — the offline local-DB Details path was added to `MangaDetailsByUrlRework`, which may already subsume the by-ID route's offline behavior. CONFIRM the rework url-based route resolves library items from DB (no network round-trip) before deciding effort. If it does, downgrade to P3/KMP-EXTRA (route consolidation). If library details still hit the network, this is a real P0 regression. Open question flagged.

### GAP-NAV-02 — Parallel `*Rework` debug routes are KMP-only additions
- **Screen/surface:** Navigation graph
- **Type:** KMP-EXTRA
- **OLD (target):** OLD has a single route per screen (route table `NavGraphV2.kt:56-132`).
- **KMP (current):** App.kt — 14 extra `*Rework` route keys (MangaDetailsRework, ChapterImagesRework, StatisticsRework, HistoryRework, UpdatesRework, SourcesRework, ThemeRework, AboutRework, WhatsNewRework, LanguageRework, ComplaintRework, ComplaintAdminRework, SettingsRework) marked "debug-only, not user-reachable" except `ComplaintAdminRework` (admin-reachable) and `HistoryRework`/`UpdatesRework` (show bottom bar) (kmp audit table rows, `:80-89`).
- **Priority:** P2
- **Acceptance criteria:** Confirm the debug-only `*Rework` routes are not user-reachable in release builds (no nav edge from user-facing UI). `HistoryRework`/`UpdatesRework` showing the bottom bar must not be reachable in release, or they'd present a duplicate tab experience.
- **Notes:** Intentional dev scaffolding. Cleanup item: gate the `composable<>` blocks behind a debug flag (or remove pre-release) so the route table is 1:1 with OLD in production. `ComplaintAdminRework` is the one with a real reachable edge — verify it's the intended admin path.

### GAP-NAV-03 — `safeNavigate` signature drift + debug `println` logging
- **Screen/surface:** Navigation utilities
- **Type:** REFACTOR
- **OLD (target):** `safePopBackStack.kt:138-158` — `safeNavigate(route, clearFocus=true, builder)` (focus-clearing wrapper).
- **KMP (current):** `navigation/safePopBackStack.kt:65-79` — `safeNavigate(route: Any, builder)`: NO `clearFocus` param; adds `[ReaderNav]` `println` invoke/success/failure debug prints (kmp audit `:107`).
- **Priority:** P3
- **Acceptance criteria:** `[ReaderNav]` `println` debug logging removed (or behind a debug flag) before release. The dropped `clearFocus` param is acceptable (commonMain has no decor-view focus model) — document, don't restore.
- **Notes:** `clearFocus`/`clearAllFocus(context)` was an Android FocusFinder-crash guard (`safePopBackStack.kt:103-108` OLD) tied to `decorView` — correctly dropped in commonMain. Only the stray `println`s are the cleanup target. If the focus-crash recurs on Android, re-add as an Android-actual hook, not commonMain.

### GAP-NAV-04 — `safePopBackStack` drops Android focus-clear guard (commonMain)
- **Screen/surface:** Navigation utilities (Android)
- **Type:** DEVIATION(platform)
- **OLD (target):** `safePopBackStack.kt:24-72` — clears decor-view focus before pop (FocusFinder crash guard), default `clearFocus=true`.
- **KMP (current):** `navigation/safePopBackStack.kt:8-34` — `runCatching` start-dest + pop/fallback-to-library; OLD's `clearFocus`/`clearAllFocus(context)` NOT present (kmp audit `:104`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Watch for FocusFinder-related crashes on Android when popping from text-input screens; if observed, re-introduce focus clearing as an Android-platform hook.
- **Notes:** Android-specific (`decorView`/`rootView` focus). The 3-catch fallback-to-library safety is preserved, which is the load-bearing part. Pairs with GAP-NAV-03.

---

# ③ App Shell / Scaffold

### GAP-SHELL-01 — `MainActivity.onCreate` side-effect stack not present in common shell
- **Screen/surface:** App shell / startup
- **Type:** DEVIATION(platform)
- **OLD (target):** `MainActivity.kt:274-313,428-544` — Firebase `APP_OPEN` analytics; `updateSources.initializeSources()`; in-app update check (flexible); UMP ads consent (`requestConsent`→`loadAndShowForm`→`initializeAds`); in-app review; update progress listener; `onResume` resume-update; `onActivityResult` update result.
- **KMP (current):** kmp audit `:126` — NONE of these live in the common shell (`App()`/`MainScreen()`); deferred to platform hosts / `:platform` SPIs (INFERRED — outside scoped files).
- **Priority:** P1
- **Acceptance criteria:** Each OLD onCreate effect has a defined home and runs on Android with parity: (a) `updateSources.initializeSources()` runs at startup; (b) FCM, analytics APP_OPEN, in-app update, UMP consent, in-app review either run via `:platform` SPI on Android or are explicitly logged as descoped. Produce a checklist of which are wired vs descoped.
- **Notes:** Most are Android/Google-only (FCM, UMP, Play in-app-update/review, Firebase) → legitimately DEVIATION via `:platform` SPI on Android, no-op elsewhere. BUT `updateSources.initializeSources()` is core app behavior (sources won't refresh) — verify it runs somewhere in the Android host or a startup SPI. This audit cluster can't see platform hosts; flag as an open verification item with a real functional risk if `initializeSources` was lost. Split: source-init = functional P1; ads/update/review/FCM/analytics = platform deviations to enumerate.

### GAP-SHELL-02 — Shared graph-scoped ViewModels eliminated (esp. SharedChaptersViewModel)
- **Screen/surface:** App shell / Navigation
- **Type:** REFACTOR
- **OLD (target):** `NavGraphV2.kt:140-144` — 4–5 NavGraph-scoped VMs hoisted: `WhatsNewViewModel`, `RepoSettingsViewModel`, `SharedChaptersViewModel` (chapter list shared into reader), `DownloadViewModelv2`, `AdViewModel`. Used to pass chapter lists Home/Details/Library/History/Updates → reader (`:240-266`, `:438-465`, etc.).
- **KMP (current):** kmp audit `:49` — NONE; NavHost is a pure dispatcher; VMs resolved per-route-host via Koin.
- **Priority:** P1
- **Acceptance criteria:** The reader receives the correct chapter LIST (for prev/next navigation within the manga) when opened from Home, Details, Library, History, and Updates — not just the single tapped chapter. Verify in-reader chapter swiping/next-chapter works from each of the 5 entry points.
- **Notes:** `SharedChaptersViewModel` was the mechanism OLD used to hand the full chapter list to the reader. KMP's reader takes a 12-field legacy args tuple (route `ChapterImagesFragment`, kmp audit table) which may carry `paths`/chapter context, but the FULL list for in-reader prev/next needs a source. Memory ("Reader-convergence R4", parity campaign complete) suggests the reader was reworked — CONFIRM list-passing parity. If the reworked reader fetches its own chapter list, this is a clean refactor (downgrade to KMP-EXTRA). If next/prev-chapter is broken from some entry points, it's a P0. Open question.

### GAP-SHELL-03 — Navigation cross-link guards/toasts (deleted-manga, random-empty) parity
- **Screen/surface:** Navigation cross-links (History/Updates/Library)
- **Type:** BEHAVIOR
- **OLD (target):** `NavGraphV2.kt:325-336,356-384` — History/Updates guard `sharedChaptersVm.isMangaExists(mangaId)` else Toast "THis Manga Is Deleted from the Libarary" (sic); `:284-300` Library random → Toast "No manga in your library yet!" when empty.
- **KMP (current):** kmp audit — cross-links described (`:94`) route Details→reader/downloads/webview and onboarding chain, but the `isMangaExists` guard + the two specific Toasts are not enumerated (and `SharedChaptersViewModel.isMangaExists` is gone per GAP-SHELL-02).
- **Priority:** P2
- **Acceptance criteria:** Tapping a History/Updates entry whose manga was removed from the library shows a "deleted from library" message instead of opening a broken screen; "Open random" on an empty library shows the empty message. Both messages localized.
- **Notes:** Tie to GAP-SHELL-02 (the guard used `SharedChaptersViewModel`). Re-home the existence check on whatever repo the rework History/Updates routes use. The OLD Toast strings have typos ("THis", "Libarary") — fix in the localized rework copy, don't reproduce verbatim.

### GAP-SHELL-04 — Singleton ImageLoader / `CoilSourceHeaderInterceptor` is a KMP addition
- **Screen/surface:** App shell
- **Type:** KMP-EXTRA
- **OLD (target):** OLD registers AVIF decoder on Coil per memory but had no common ImageLoader-factory wiring at shell scope in this audit.
- **KMP (current):** `App.kt:266-291` (`CoilSourceHeaderInterceptor`, Bug-4-layer-3 per-source header injection) + `:306-324` (singleton ImageLoader: OkHttp forced Android, AVIF decoder Android, `maxBitmapSize(Size.ORIGINAL)`).
- **Priority:** P3
- **Acceptance criteria:** N/A (improvement). Ensure parity invariants from memory hold: AVIF decoder registered on Android, OkHttp fetcher forced on Android, `maxBitmapSize` lifts the 4096 cap. No regression.
- **Notes:** Directly encodes several memory items (AVIF decoder, OkHttp fetcher, image-quality buildRequest, Skia size cap). Recorded so a future sweep doesn't "simplify" these away — they are load-bearing for image quality. Remove the `[CoilDbg]` `println`s before release.

---

# ④ Theming

### GAP-THEME-01 — App-root still binds legacy `YamiMangaTheme`, not rework `YamiTheme`
- **Screen/surface:** App shell / Theming
- **Type:** REFACTOR
- **OLD (target):** N/A for the rewire itself; OLD wraps content in `YamiMangaTheme(darkTheme, pureBlack)` (`MainActivity.kt:339-342`) — and the rework's own design system defines `YamiTheme` (`ui/theme/YamiTheme.kt:88-114`) as the intended app-root.
- **KMP (current):** `App.kt:17,332` — app root imports/calls LEGACY `me.manga.kira.theme.YamiMangaTheme` (in `composeApp`), NOT the rework `:ui` `YamiTheme`. `YamiTheme` is used only at leaf-screen scope; the root rewire is FORECAST-NOT-YET-FULFILLED (kmp audit `:132`, `YamiTheme.kt:55-66` postscript).
- **Priority:** P1
- **Acceptance criteria:** App root (`App()`/`MainScreen`) wraps content in the rework `:ui` `YamiTheme(darkTheme, pureBlack, dynamicColor, content)`; the legacy `composeApp` `YamiMangaTheme` is removed (or reduced to a thin alias). Verify identical visuals (colors/typography/shapes are byte-for-byte parity per kmp audit `:206,:214,:228`) so the swap is non-visual. Leaf screens that already call `YamiTheme` get the same theme from root (no double-wrap).
- **Notes:** This is the explicitly-called-out app-root theme rewire. Because tokens are byte-for-byte identical (YamiColors/YamiTypography/YamiShapes mirror legacy Theme.kt), the swap should be visually inert — that's the test: screenshot diff before/after must be empty. Watch for double-theming: leaf screens currently re-wrap in `YamiTheme`; after the root rewire, remove redundant leaf wraps or confirm they're idempotent. Also fold `LocalSpacing`/`Spacing()` provision (currently in `YamiTheme`) into the now-root path so spacing is available app-wide.

### GAP-THEME-02 — Dynamic color is a documented no-op (DynamicColorProvider SPI missing)
- **Screen/surface:** Theming
- **Type:** DEVIATION(platform)
- **OLD (target):** `theme/Theme.kt:101,108-111` — dynamic color supported (Android 31+ Material You) but default OFF (`dynamicColor=false`).
- **KMP (current):** `ui/theme/YamiTheme.kt:67-81,92` — `dynamicColor` param present but `@Suppress("UNUSED_PARAMETER")` NO-OP, awaiting a `:platform DynamicColorProvider` SPI (FORECAST-NOT-YET-FULFILLED). Same OFF-by-default posture as OLD (kmp audit `:225`).
- **Priority:** P3
- **Acceptance criteria:** Document deviation. Since OLD defaults dynamic color OFF, no user-visible parity gap today. If/when a user toggle for dynamic color is exposed, implement the `:platform DynamicColorProvider` SPI (Android `dynamicDarkColorScheme`/`dynamicLightColorScheme`; no-op iOS/Desktop).
- **Notes:** Android-only Material You feature → platform SPI is the right home. No parity gap while both default OFF. Pure forecast item.

### GAP-THEME-03 — Unreferenced fonts (Gilroy/Poppins/alba/extra-Gellix) shipped in `:composeApp`
- **Screen/surface:** Theming / assets
- **Type:** REFACTOR
- **OLD (target):** `theme/Type.kt:12-16` wires only 3 Gellix weights; Gilroy/Poppins/alba ship in `res/font/` but are unreferenced by the Compose theme (OLD audit `:228,:293`).
- **KMP (current):** kmp audit `:199` — `:ui` ships only the lean 3-Gellix set (good); but `:composeApp/.../composeResources/font/` still carries `alba.TTF`, extra Gellix weights, Gilroy ×3, Poppins ×7 — unreferenced (INFERRED dead assets, same situation as OLD).
- **Priority:** P3
- **Acceptance criteria:** Confirm no rework code references Gilroy/Poppins/alba/extra-Gellix; if confirmed dead, remove from `:composeApp/composeResources/font/` to shrink the bundle. No visual change.
- **Notes:** Parity-neutral (OLD also shipped them dead). Pure bundle-size cleanup. Verify no XML layout / legacy `composeApp` screen still references them before deleting. Low priority.
