# KMP (rework) Audit — WebView · Navigation · App-Shell · Theming

> Roots audited: `composeApp/src/{commonMain,androidMain,iosMain,desktopMain}/kotlin/me/manga/yamiapk/` and `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/`.
> Read-only audit. Citations are `file:line` (paths relative to `D:/yami manga/yami-kmp/`). Inferences marked **(INFERRED)**.
> Cross-reference: OLD native audit at `audit_workspace/old/webview_nav_shell.md`. Heading scheme matches it.
> NOTE: most files carry large §253 "audit-trail postscript" KDoc blocks; these are historical docs, not behavior — citations below point at live code lines.

---

## ① WebView Screen

### WebViewComposeScreen
- **Entry/route:** `Screen.WebView(url: String, api: String)` → `composable<Screen.WebView>` in `App.kt:526-532` → `WebViewScreenRoute` (`navigation/routes/WebViewScreenRoute.kt:57-76`), which decodes args via `backStackEntry.toRoute<Screen.WebView>()` (`:62`), resolves `WebViewViewModel` via `koinViewModel()` (`:63`), and renders `WebViewComposeScreen(api, initialUrl, onSaveHeaders, onClose)`. Core composable: `presentation/features/webview/ui/screens/WebViewComposeScreen.kt:142-280`. Underlying platform web stack is the `WebViewHost` expect/actual (`core/webview/WebViewHost.kt:22-31`) driven by a `WebViewController` (`core/webview/WebViewController.kt:36-49`).
- **Layout & components:** `Scaffold` (`:177`) → `TopAppBar` (`:180`, Material3, `@file:OptIn(ExperimentalMaterial3Api)`) + body `Column(fillMaxSize+padding)` (`:236`). Body = optional `LinearProgressIndicator` (determinate when `nav.progress != null`, else indeterminate, `:241-257`, 4.dp tall) then `WebViewHost(...)` (`:259-277`). NO fallback Text/placeholder in commonMain (per-platform actuals own the empty/unavailable case — Desktop shows its own placeholder).
- **Visual:** TopAppBar title is a 2-line `Column` (`:186-199`): line 1 = `if (isLoading) "Loading..." else currentUrl` (maxLines 1, ellipsis); line 2 = `currentUrl` (`bodySmall`, maxLines 1, ellipsis). Progress bar `height(4.dp).fillMaxWidth()`. Default `TopAppBar` colors (no custom). All strings here are HARDCODED English literals ("Loading...", "Close", "Back", "Forward", "Reload", "Save Headers") — NOT localized (`:183,189,208,217,224,230`).
- **States:**
  - *loading* — `isLoading` starts `true` (`:159`); driven by `nav.isLoading` from controller; progress bar shown while `nav.isLoading` (`:241`). `onPageFinished` flips `isLoading=false` (`:261-264`).
  - *empty* — none in commonMain; Desktop actual renders "Embedded WebView unavailable on this Desktop runtime." when KCEF client is null (`WebViewHost.desktop.kt:89-92`).
  - *error/crash* — NO commonMain crash UI. There is NO render-process-gone recovery / recreation loop (the OLD Android `onRenderProcessGone` + `recreationKey` logic was dropped). Android actual just `stopLoading()+destroy()` on dispose (`WebViewHost.android.kt:84-87`).
  - *success* — WebView visible; `currentUrl` updated on `onPageFinished`.
- **Interactions:**
  - *Android back* — NO `BackHandler` (commonMain has no shim; system back pops the nav back stack normally). OLD's in-memory 50-entry history stack + custom BackHandler was NOT ported.
  - *Nav controls* (TopAppBar actions, `:201-232`) — present (restored by task #742, W-NAV): Back (`Icons.AutoMirrored.Filled.ArrowBack`, enabled `nav.canGoBack && !nav.isLoading`, calls `controller.goBack()`); Forward (`ArrowForward`, `nav.canGoForward && !nav.isLoading`, `controller.goForward()`); Reload (`Icons.Default.Refresh`, `!nav.isLoading`, `controller.reload()`); Save (`Icons.Default.Save`, enabled `savedHeaders != null && !isLoading`, calls `onSaveHeaders(savedHeaders, api)`). Nav icon = Close (`Icons.Default.Close`, `:182-184`) → `onClose(savedHeaders, api)`.
  - *History model* — uses the WebView's native back/forward list via the controller (`canGoBack()/canGoForward()` per platform), NOT the OLD custom 50-entry list.
  - *Zoom* — NOT configured in commonMain; Android actual does NOT set `setSupportZoom`/`builtInZoomControls` (OLD did). **(INFERRED gap vs OLD)**
  - *animations/transitions* — none custom.
- **Dialogs/sheets/snackbars:** None.
- **Forms & validation:** None (browser).
- **Data/behavior — header capture (the point of the screen):**
  - Two capture channels: `capturedCookie` + `capturedUserAgent` (`:156-157`). `savedHeaders` is derived state `remember(capturedCookie, capturedUserAgent)` (`:167-175`): returns null until a non-blank Cookie is in hand, then `buildMap { put("Cookie", cookie); UA?.let { put("User-Agent", it) } }`. **Bug-4-layer-2:** both Cookie AND User-Agent are saved because Cloudflare's `cf_clearance` is bound to the UA that earned it (`WebViewHost.kt:10-14`).
  - `WebViewHost` callbacks (`:259-277`): `onPageFinished(finishedUrl)` → updates `currentUrl` + `isLoading=false`; `onCookiesAvailable(cookieHeader)` → `capturedCookie`; `onUserAgentResolved(ua)` → `capturedUserAgent`.
  - *Persistence* — `WebViewViewModel.saveHeaders(headers, api)` (route bridges both `onSaveHeaders` and `onClose`), then `onClose` calls `navController.safePopBackStack()` (`WebViewScreenRoute.kt:71-74`).
  - URL gating / `shouldOverrideUrlLoading` same-host sandbox + `shouldInterceptRequest` background cookie reads from OLD are NOT ported — actuals just `loadUrl(url)`.
- **Platform actuals (3):**
  - *Android* (`WebViewHost.android.kt:21-154`): `android.webkit.WebView` via `AndroidView`; settings JS+DOM+DB enabled, `loadWithOverviewMode`, `useWideViewPort`, optional UA override (`:34-40`); `WebViewClient.onPageFinished` → resolves url, reads `CookieManager.getInstance().getCookie(resolved)` (`:53`) + `view.settings.userAgentString` (`:60-62`); `WebChromeClient.onProgressChanged` feeds determinate progress (`:67-70`); `AndroidWebViewController` (`:96-150`) tracks canGoBack/canGoForward/progress and exposes goBack/goForward/reload. `loadUrl` on attach, `stopLoading()+destroy()` on dispose. NOTE: `cacheMode`, hardware-layer, zoom, focus-crash guards, lifecycle pause/resume, render-crash recovery from OLD are all ABSENT.
  - *iOS* (`WebViewHost.ios.kt:29-153`): `WKWebView` via `UIKitView`; `WebViewDelegate : WKNavigationDelegateProtocol.didFinishNavigation` (`:164-189`) → `onPageFinished`, walks `NSHTTPCookieStorage.sharedHTTPCookieStorage.cookiesForURL` building `name=value; …` (`:170-181`), and `evaluateJavaScript("navigator.userAgent")` for UA (`:186-188`). Progress is POLLED every 100ms via `view.estimatedProgress`/`view.loading` (KVO is brittle on K/N) (`:71-78`, `:120-130`). `customUserAgent` override (`:48`).
  - *Desktop* (`WebViewHost.desktop.kt:67-210`): KCEF/JCEF Chromium via `SwingPanel`; `KCEF.newClientOrNullBlocking` with null→placeholder text (`:82-92`); `CefMessageRouter` "yamiUaQuery" JS↔Java bridge for UA (`:100-126`); `CefLoadHandlerAdapter.onLoadEnd` (main-frame only) → `onPageFinished` + `captureCookies` + pushes UA query JS (`:146-160`). `captureCookies` (`:275-307`) uses `visitAllCookies` + manual host-domain filtering (apex/subdomain) to keep HttpOnly `cf_clearance`/`__cf_bm` (Bug-4-layer-2 follow-up). **`userAgent` input param is IGNORED on Desktop** (`:190-195`, JCEF has no per-browser UA override). No determinate progress channel (`progress=null`, indeterminate bar) (`:226-236`).
- **Feature inventory:** Close button; 2-line title (Loading/URL + URL); progress bar (determinate Android/iOS, indeterminate Desktop); back/forward/reload (native history via controller); save-headers (Cookie+UA); cookie sniffing → header persistence per-api; 3-platform WebView (WebView/WKWebView/KCEF); Desktop null-client placeholder.
- **Gaps vs OLD (recorded):** no custom BackHandler/in-memory history; no same-host URL sandbox (`isAllowed`/`shouldOverrideUrlLoading`); no `shouldInterceptRequest` background cookie merge; no render-process-gone recovery/recreation; no zoom controls; no lifecycle pause/resume; no ANR `cacheMode`/hardware-layer hardening; Desktop ignores UA override; hardcoded (un-localized) chrome strings.
- **Citations:** `WebViewComposeScreen.kt:142-280`; `WebViewScreenRoute.kt:57-76`; `core/webview/WebViewHost.kt:22-31`; `core/webview/WebViewController.kt:20-57`; `WebViewHost.android.kt:21-154`; `WebViewHost.ios.kt:29-189`; `WebViewHost.desktop.kt:67-307`; `App.kt:526-532`.

---

## ② Navigation graph (full route table)

### AppNavHost
- **Entry/route:** `AppNavHost(navController, onBottomBarVisibleChange)` (`App.kt:373-882`), hosted by `MainScreen()` (`App.kt:339-371`), itself wrapped by `App()` (`App.kt:293-337`). Type-safe routes via `kotlinx.serialization` `@Serializable sealed class Screen(val route: String)` (`navigation/Screen.kt:5-…`) + `composable<Screen.X>` blocks. DI is **Koin** (`koinViewModel()` / `koinInject()`), not Hilt.
- **Start destination:** dynamic (`App.kt:392-393`): `firstLaunch = remember { prefs.getBoolean("first_launch", true) }` (via `SharedPrefsHelper`, key string verbatim from upstream); `rootStart = if (firstLaunch) Screen.Welcome else Screen.Library`.
- **Shared graph-scoped VMs:** NONE. The OLD's 4 NavGraph-scoped VMs (`WhatsNewViewModel`, `RepoSettingsViewModel`, `SharedChaptersViewModel`, `DownloadViewModelv2`, `AdViewModel`) are all retired or resolved per-route-host via Koin (`App.kt:381-386` comment). The NavHost is a pure dispatcher.
- **Bottom-bar visibility:** every `composable<>` block calls `SideEffect { onBottomBarVisibleChange(true/false) }`. `true` for the 5 tab roots (Library, Updates, Home, History, Setting) AND for the debug-only `HistoryRework`/`UpdatesRework`; `false` everywhere else.
- **NavHost-level animations / deep links:** NONE declared (no `enterTransition`/`deepLinks` on any block) — matches OLD 1:1.

#### Full route table

| Route (Screen) | Args | Composable rendered | BottomBar | REWORK / LEGACY | Cite (App.kt) |
|---|---|---|---|---|---|
| `Welcome` (object) | — | `WelcomeScreenRoute` | hidden | REWORK | `:400-406` |
| `Theme` (object) | — | `ThemeSelectionScreenRoute` | hidden | REWORK (legacy route key, swap §291 hosts rework ThemeScreen) | `:408-414` |
| `Sources` (object) | — | `SourcesScreenRoute` | hidden | REWORK (swap §305) | `:416-422` |
| `Home` (object) | — | `HomeReworkScreenRoute` | **shown** | REWORK (Epic H5a) | `:431-437` |
| `Library` (object) | — | `LibraryScreenRoute` | **shown** | REWORK (swap §346) | `:445-451` |
| `History` (object) | — | `HistoryScreenRoute` | **shown** | REWORK (swap §288 hosts rework UI) | `:453-459` |
| `Updates` (object) | — | `UpdatesScreenRoute` | **shown** | REWORK (swap §289) | `:461-467` |
| `MangaDetails` (data) | `mangaUrl, api` | `MangaDetailsByUrlReworkScreenRoute` | hidden | REWORK (swap §429; legacy key retained per ADR-8) | `:469-493` |
| `ChapterImagesFragment` (data) | 12-field legacy reader tuple | `ChapterImagesByLegacyArgsReworkScreenRoute` | hidden | REWORK (Reader-convergence R4) | `:495-508` |
| `Setting` (object) | — | `SettingsRoute` | **shown** | REWORK (swap §301) | `:510-516` |
| `Statistics` (object) | — | `StatisticsScreenRoute` | hidden | REWORK (swap §286) | `:518-524` |
| `WebView` (data) | `url, api` | `WebViewScreenRoute` | hidden | REWORK | `:526-532` |
| `RepoSettings` (data) | `isFirstOpen=false` | `RepoSettingsScreenRoute` | hidden | REWORK (swap §285 → SourcesRework) | `:534-540` |
| `LanguageScreen` (object) | — | `LanguageScreenRoute` | hidden | REWORK (swap §292) | `:542-548` |
| `DownloadsScreen` (object) | — | `DownloadsScreenRoute` | hidden | REWORK (swap §295) | `:550-556` |
| `DownloadsRework` (object) | — | `DownloadsReworkScreenRoute` | hidden | REWORK (user-reachable via Settings Downloads row) | `:572-578` |
| `AboutScreen` (object) | — | `AboutScreenRoute` | hidden | REWORK (swap §287) | `:580-586` |
| `Complaint` (object) | — | `ComplaintScreenRoute` | hidden | REWORK (swap §293) | `:588-594` |
| `WhatsNewScreen` (data) | `isFirstOpen=false` | `WhatsNewScreenRoute` | hidden | REWORK (swap §290) | `:596-602` |
| `ComplaintAdmin` (object) | — | `AdminComplaintScreenRoute` | hidden | REWORK (swap §365; admin-gated) | `:604-610` |
| `MangaDetailsRework` (data) | api,language,title,url,coverUrl,rating,genres | `MangaDetailsReworkScreenRoute` | hidden | REWORK (debug-only, not user-reachable) | `:620-626` |
| `ChapterImagesRework` (data) | api,language,title,mangaUrl,coverUrl,chapterNumber,chapterName,chapterUrl | `ChapterImagesReworkScreenRoute` | hidden | REWORK (debug-only) | `:636-642` |
| `StatisticsRework` (object) | — | `StatisticsReworkScreenRoute` | hidden | REWORK (debug-only) | `:654-660` |
| `HistoryRework` (object) | — | `HistoryReworkScreenRoute` | **shown** | REWORK (debug-only) | `:672-678` |
| `UpdatesRework` (object) | — | `UpdatesReworkScreenRoute` | **shown** | REWORK (debug-only) | `:690-696` |
| `SourcesRework` (object) | — | `SourcesReworkScreenRoute` | hidden | REWORK (debug-only) | `:712-718` |
| `ThemeRework` (object) | — | `ThemeReworkScreenRoute` | hidden | REWORK (debug-only) | `:736-742` |
| `AboutRework` (object) | — | `AboutReworkScreenRoute` | hidden | REWORK (debug-only) | `:758-764` |
| `WhatsNewRework` (object) | — | `WhatsNewReworkScreenRoute` | hidden | REWORK (debug-only) | `:779-785` |
| `LanguageRework` (object) | — | `LanguageReworkScreenRoute` | hidden | REWORK (debug-only) | `:803-809` |
| `ComplaintRework` (object) | — | `ComplaintReworkScreenRoute` | hidden | REWORK (debug-only) | `:826-832` |
| `ComplaintAdminRework` (object) | — | `AdminComplaintReworkScreenRoute` | hidden | REWORK (reachable via Settings hub `OnNavigate(COMPLAINT)` when `Admin.isAdmin`) | `:850-856` |
| `SettingsRework` (object) | — | `SettingsReworkScreenRoute` | hidden | REWORK (debug-only) | `:874-880` |

- **EVERY route renders a REWORK screen.** There is NO LEGACY (`:shared`/old `:composeApp` features) screen wired into the NavHost. The "legacy" route KEYS (`Screen.Theme`, `Screen.History`, `Screen.MangaDetails`, etc.) were retained per the route-adapter-swap pattern (callers untouched) but every adapter now hosts the rework `:ui` screen. The parallel `*Rework` route keys exist as debug-only entry points; most render the SAME rework screen as their non-Rework sibling (each scoped to its own `NavBackStackEntry`-owned Koin VM).
- **Routes declared in Screen.kt but with NO `composable<>` block:** none observed for user-reachable keys; the OLD `LibraryMangaDetails` key was fully retired (App.kt:104-109 comment). The legacy `Screen.MangaDetails` two-arg key and `Screen.ChapterImagesFragment` 12-field tuple are retained ONLY as adapter targets.
- **Deep links:** NONE (no `deepLinks=` on any block) — matches OLD.
- **Navigation cross-links of note:** chapter-row tap (Details) routes via `onNavigateToReader` to `Screen.ChapterImagesFragment` (rework reader); Details Downloads click → `Screen.DownloadsRework`; Details WebView click → `Screen.WebView`; onboarding chain Welcome → Theme → Sources → RepoSettings(isFirstOpen=true) → Library, with `first_launch` flipped to false in `RepoSettingsScreenRoute.onFinish` (App.kt:115-118 comment).
- **Citations:** `App.kt:373-882` (graph), `:339-371` (MainScreen), `:293-337` (App), `navigation/Screen.kt` (Screen defs).

### NavigationLock
- **Entry/route:** `navigation/NavigationLock.kt:7-39`. `Mutex`-guarded `withLock { … }` (suspend, 100ms patience delay) + `tryLock { … }: Boolean` (fast-fail) with single `isNavigating` flag. **(INFERRED: not referenced by the live NavHost or BottomNavigationBar — anti-double-nav is handled by `safePopBackStack` + `launchSingleTop`. Same status as OLD.)**
- **Citations:** `navigation/NavigationLock.kt:7-39`.

### safePopBackStack (navigation utilities)
- **Entry/route:** `navigation/safePopBackStack.kt`. Used by `WebViewScreenRoute` (`:73`) and other route adapters.
- **Behavior:**
  - `NavController.safePopBackStack(libraryRoute=Screen.Library.route): Boolean` (`:8-34`): `runCatching` for start-dest id; if no `previousBackStackEntry` → `navigateToLibrary`; else `popBackStack()` (fallback to library if it returns false); 3-catch chain (`IllegalStateException`/`IllegalArgumentException`/`Throwable`) all → `navigateToLibrary`. NOTE: OLD's `clearFocus`/`clearAllFocus(context)` FocusFinder-crash guard is NOT present (Android-only, dropped in commonMain).
  - `navigateToLibrary(libraryRoute, startDestinationId)` (`:36-55`): `navigate(libraryRoute){ launchSingleTop=true; restoreState=true; popUpTo(startDestId){inclusive=false; saveState=true} }`.
  - `safePopBackStackAsync(delayMs=50)` (`:57-63`): suspend; delays then pops.
  - `safeNavigate(route: Any, builder)` (`:65-79`): try/catch navigate wrapper with `[ReaderNav]` `println` debug logging (invoke/success/failure) — note this DIVERGES from OLD's `safeNavigate(route, clearFocus, builder)` signature: no `clearFocus` param, adds debug prints.
- **Citations:** `navigation/safePopBackStack.kt:8-79`.

### double_click (Home tab reselect)
- **Entry/route:** `navigation/double_click/HomeTabReselectedHandler.kt:3-5` (interface `onHomeTabReselected()`); `NavigationHandlerHolder.kt:4-6` (singleton object `var homeReselectHandler: HomeTabReselectedHandler?`). Non-DI holder pattern (deliberate).
- **Behavior:** `BottomNavigationBar` invokes `NavigationHandlerHolder.homeReselectHandler?.onHomeTabReselected()` when Home is reselected while already selected (`BottomNavigationBar.kt:134-136`). The handler is registered by the Home route host (`HomeReworkScreenRoute`/`HomeScreen` scroll-to-top) **(INFERRED — registration lives in the Home surface, not in this cluster's scope)**.
- **Citations:** `HomeTabReselectedHandler.kt:3-5`, `NavigationHandlerHolder.kt:4-6`, `BottomNavigationBar.kt:134-136`.

---

## ③ App Shell / Scaffold

### App() / MainScreen() (cross-platform root)
- **Entry/route:** `App.kt:293-337` (`App()`), `:339-371` (`MainScreen()`). Each platform host (Android `MainActivity`, iOS `MainViewController`, Desktop `Main.kt`) calls `App()`. **(INFERRED — host wiring is outside this cluster's scoped files.)**
- **Layout & components:**
  - `App()`: wires the singleton Coil `ImageLoader` via `setSingletonImageLoaderFactory` (`:306-324`): platform NetworkFetcher first (OkHttp forced on Android, `:312`), then decoder factories (AVIF on Android, `:303-304,313`), then `CoilSourceHeaderInterceptor` (per-source header injection, `:314`); `.maxBitmapSize(Size.ORIGINAL)` to lift the 4096 cap for tall pages (`:316-322`). Resolves `SettingsViewModel` for theme flows (`:326-329`), computes `effectiveDark = if (followSystem) systemDark else darkMode` (`:331`), wraps in `YamiMangaTheme(darkTheme, pureBlack)` (`:332`) → `Surface(fillMaxSize)` → `MainScreen()`.
  - `MainScreen()`: `rememberNavController()`; `var showBottomBar by remember { false }`; `Scaffold(contentWindowInsets = WindowInsets.systemBars.only(Bottom), bottomBar = { if(showBottomBar) Column { HorizontalDivider(1.dp, onSurface@25%) ; BottomNavigationBar(navController) } })` (`:344-362`); body `Box(padding(paddingValues))` wrapping `AppNavHost(navController, onBottomBarVisibleChange = { showBottomBar = it })` (`:363-369`).
- **Visual:** bottom bar gated by a 1.dp `HorizontalDivider` colored `onSurface.copy(alpha=0.25f)`, fillMaxWidth (`:354-358`). Only the BOTTOM system-bar inset is consumed by the outer Scaffold (top status-bar inset flows to each screen's TopAppBar) (`:350`) — same edge-to-edge posture as OLD.
- **States:** theme from `SettingsViewModel.{followSystem, darkMode, pureBlack}` (collectAsState); bottom-bar show/hide reactive per-route via `SideEffect`.
- **Interactions / side effects:** NONE of the OLD's heavy `MainActivity.onCreate` side effects live in this common shell — no analytics APP_OPEN, no in-app update, no UMP consent, no in-app review, no FCM, no DEX plugins. Those are deferred to platform hosts / `:platform` SPIs **(INFERRED — not in scoped files)**. The theme application (`YamiMangaTheme`, the still-legacy app-root theme name) is the only shell-level effect.
- **Dialogs/sheets/snackbars:** none in shell.
- **Forms & validation:** none in shell.
- **Data/behavior:** `CoilSourceHeaderInterceptor` (`App.kt:266-291`) — Bug-4-layer-3 per-source header injection: extracts image host, `sourcesRepository.findRepoByHost(host)`, pulls `match.defaultHeaders`, converts to Coil `NetworkHeaders`, forwards via `chain.withRequest(newReq).proceed()`; `println("[CoilDbg] …")` debug logging.
- **Feature inventory:** singleton ImageLoader (OkHttp Android / ktor3 elsewhere; AVIF decoder Android; per-source header interceptor; maxBitmapSize ORIGINAL); theme application (system/dark/pureBlack); edge-to-edge (bottom inset only); conditional bottom bar with hairline divider; nav host dispatcher; dynamic start destination from `first_launch`.
- **Citations:** `App.kt:266-291` (interceptor), `:293-337` (App), `:339-371` (MainScreen), `:306-324` (ImageLoader factory).
- **Note — app-root theme:** `App.kt:17,332` still imports/calls the LEGACY `me.manga.kira.theme.YamiMangaTheme` (in `composeApp`), NOT the rework `:ui` `YamiTheme`. The rework `YamiTheme` is used at leaf-screen scope (ThemeScreen etc.) but the app-root rewire is a FORECAST-NOT-YET-FULFILLED item (`YamiTheme.kt:55-66` postscript).

### BottomNavigationBar
- **Entry/route:** `presentation/common/componants/BottomNavigationBar.kt:108-153`. Rendered by `MainScreen` when `showBottomBar`.
- **Layout & components:** Material3 `NavigationBar(containerColor = colorScheme.background)` (`:121-122`) with 5 `NavigationBarItem`s from a `listOf(Triple(Screen, ImageVector, StringResource))` (`:113-119`).
- **Tabs (order left→right):**
  1. `Screen.Library` — `Icons.AutoMirrored.Filled.LibraryBooks` — `Res.string.title_library`
  2. `Screen.Updates` — `Icons.Default.Notifications` — `Res.string.title_notifications`
  3. `Screen.Home` — `Icons.Default.Home` — `Res.string.title_home` **(center)**
  4. `Screen.History` — `Icons.Default.History` — `Res.string.title_history`
  5. `Screen.Setting` — `Icons.Default.Settings` — `Res.string.title_settings`
  - Matches OLD order/center exactly. Labels now via compose-resources `stringResource` (localized), not `R.string`.
- **Visual:** label uses `NavigationBarAutoText(stringResource(labelRes))` (`:131`); selected indicator `NavigationBarItemDefaults.colors(indicatorColor = colorScheme.primaryContainer)` (`:147-149`); icon contentDescription null.
- **States:** selected = `currentDestination?.hierarchy?.any { it.route == screen.route } == true` (`:125-127`).
- **Interactions:**
  - *reselect* — if `selected && screen is Screen.Home` → `NavigationHandlerHolder.homeReselectHandler?.onHomeTabReselected()` (`:134-136`). Other tabs: no special reselect.
  - *navigate* — else `navController.navigate(screen){ popUpTo(graph.findStartDestination().id){saveState=true}; launchSingleTop=true; restoreState=true }` (`:138-144`) — multi-backstack save/restore, identical to OLD.
- **Dialogs/sheets/snackbars / Forms:** none.
- **Feature inventory:** 5 tabs; Home center; auto-sizing labels; primaryContainer indicator; Home double-tap-to-top; state-saving tab switching; hairline divider provided by MainScreen (not this composable).
- **Citations:** `BottomNavigationBar.kt:108-153`.

---

## ④ Design-System / Shared Components Inventory (`:ui` module)

The rework consolidated shared UI into `ui/src/commonMain/.../ui/components/` + `ui/.../ui/theme/`. The OLD `:composeApp` `presentation/common/componants/*` design-system family (TopAppBarCom, SearchAppBar, ActionButton, IconAboveTextButton, FeedbackDialog, FAB family, Chips, StatsItem/SwitchItem, ToastHost, AutoSizedText, ItemsGroup, LanguageToggleWithAnimation) was either retired (#356/#361/#362/#364) or lives feature-local — they are NOT the rework design-system tier. The rework shared components are:

### YamiIcons (icon catalog)
- **Layout:** `object YamiIcons` (`components/YamiIcons.kt:45-126`) — a curated map of named `ImageVector`s onto `material-icons-extended` (UP-2). Entries: Back, PrevChapter, NextChapter (all AutoMirrored), Overflow, Refresh, Download, OpenInWebView (AutoMirrored), FavoriteFilled, FavoriteOutline, Check, Bookmark, BookmarkOutline, Delete, WatchingNowOn(WatchLater)/WatchingNowOff(Schedule), SortAscending/SortDescending, Empty(Inbox), Error(ErrorOutline), BrokenImage, Tune, Search, Close, GridView, ViewList(AutoMirrored), Edit, Share.
- **Citations:** `components/YamiIcons.kt:45-126`.

### YamiStateViews (loading / empty / error)
- **Components:** `YamiLoadingState(modifier)` — centered `CircularProgressIndicator` (`YamiStateViews.kt:47-56`); `YamiEmptyState(title, modifier, icon=YamiIcons.Empty, message?, action?)` — centered big muted icon + titleMedium title + bodyMedium message + optional action slot, padded `spacing.xl` (`:68-112`); `YamiErrorState(message, modifier, retryLabel?, onRetry?)` — error glyph + message + Retry button (only when both retryLabel & onRetry non-null) (`:123-…`).
- **Visual:** icon tint `onSurfaceVariant`; title `onSurface` titleMedium; spacing via `LocalSpacing`.
- **Citations:** `components/YamiStateViews.kt:47-…`.

### YamiCoverImage (manga cover)
- **Layout:** `YamiCoverImage(coverUrl, contentDescription, modifier, aspectRatio=0.7f, shape=RoundedCornerShape(6.dp), scrim=false, placeholderTint=colorScheme.outline)` (`components/YamiCoverImage.kt:54-…`). Coil `AsyncImage` in a `Box(fillMaxWidth.aspectRatio.clip(shape).background(placeholderTint@15%))`; `isError` state keyed on `coverUrl` (recycled-cell reset); broken-image placeholder via `YamiIcons.BrokenImage` on failure.
- **Citations:** `components/YamiCoverImage.kt:54-…`.

### YamiCountBadge
- **Layout:** `YamiCountBadge(...)` (`components/YamiCountBadge.kt:29-…`) — small count/label badge (used on library cards for bookmark/download counts). **(INFERRED usage from naming + memory.)**
- **Citations:** `components/YamiCountBadge.kt:29-30`.

### YamiIconButton
- **Layout:** `YamiIconButton(...)` (`components/YamiIconButton.kt:25-…`) — shared icon-button wrapper (top-bar actions etc.). **(INFERRED usage.)**
- **Citations:** `components/YamiIconButton.kt:25-26`.

### VerticalFastScroller
- **Layout:** `VerticalFastScroller(...)` (`components/VerticalFastScroller.kt:78-…`) — Tachiyomi-style draggable fast-scroll thumb over a LazyListState (recreated for the rework in task #744; the OLD grid variant was retired #364). Helpers `firstNonStickyVisibleItem`, `computeScrollOffset`, `computeScrollRange` (`:218-240`).
- **Citations:** `components/VerticalFastScroller.kt:78-240`.

### LibraryOptionsSheet (feature-local, but reused per memory)
- **Location:** `ui/.../ui/library/LibraryOptionsSheet.kt` (compiled class present; sort/filter/display bottom sheet). Lives under `library/` not `components/`. **(INFERRED — only compiled artifacts visible; referenced in auto-memory as a reusable design-system component.)**
- **Citations:** `ui/build/.../ui/library/LibraryOptionsSheetKt.class` (source under `ui/src/commonMain/.../ui/library/`).

### Home components (feature-local)
- `home/components/`: `FeaturedCarousel.kt`, `HomeFeedGridCard.kt`, `HomeFeedRow.kt`, `SourceTabsRow.kt` — Home-tab building blocks, not general design-system.
- **Citations:** `ui/.../ui/home/components/*.kt`.

---

## ⑤ Cluster notes — Full KMP design-token inventory

### Fonts
- **Live Compose family:** `gellixFontFamily()` (`ui/theme/YamiTypography.kt:30-34`): `gellix_regular`@Normal, `gellix_semibold`@Medium, `gellix_bold`@Bold — composable `Font(Res.font.…)` (compose-resources). Only 3 weights wired (same as OLD).
- **Font files shipped in `:ui`** (`ui/.../composeResources/font/`): ONLY `gellix_{bold,regular,semibold}.ttf` — the lean set the theme actually uses.
- **Font files shipped in `:composeApp`** (`composeApp/.../composeResources/font/`): `alba.TTF`, `gellix_{black,extrabold,thin}.ttf`, `gilroy_{bold,light,regular}.ttf`, `poppins_{black,bold,light,medium,regular,semibold,thin}.ttf` — Gilroy/Poppins/alba/extra Gellix weights ship but are NOT referenced by the rework theme (legacy carry-over, same unreferenced-font situation as OLD). **(INFERRED dead assets.)**
- **Parity note (deliberate):** Gellix overrides only `bodyLarge`/`titleMedium`/`titleSmall`; all other slots stay Material 3 default to preserve Arabic/non-Latin glyph fallback (`YamiTypography.kt:23-27`).

### Typography scale (`ui/theme/YamiTypography.kt:41-48`)
- `bodyLarge` = Gellix **Bold** 16sp
- `titleMedium` = Gellix Medium 14sp
- `titleSmall` = Gellix Normal 12sp
- Everything else = Material 3 defaults. Byte-for-byte parity with OLD `Type.kt`.

### Color palette (`ui/theme/YamiColors.kt`)
**Dark scheme** (`darkColorScheme`, `:54-86`) — verbatim from legacy `composeApp/.../theme/Theme.kt`:
- primary `#B0C6FF`, onPrimary `#002D6E`, primaryContainer `#00429B`, onPrimaryContainer `#D7E2FF`
- secondary = same blues; tertiary `#B8D0FF` / onTertiary `#003063` / tertiaryContainer `#2C2C2F` / onTertiaryContainer `#D6E3FF`
- **background `#15202B`** (Twitter-night blue, deliberate vs M3 `#1B1B1F`), onBackground `#E3E2E6`, surface `#15202B`, onSurface `#E3E2E6`, surfaceVariant `#44464F`, onSurfaceVariant `#C4C6D0`
- outline `#8E9099`, inverseOnSurface `#1B1B1F`, inverseSurface `#E3E2E6`, inversePrimary `#0058CA`
- error `#FFB4AB`, onError `#690005`, errorContainer `#93000A`, onErrorContainer `#FFDAD6`

**Light scheme** (`lightColorScheme`, `:88-120`):
- primary `#0058CA`, onPrimary `#FFFFFF`, primaryContainer `#D7E2FF`, onPrimaryContainer `#001945`
- tertiary `#0061A3`, tertiaryContainer `#2C2C2F` (dark gray even in light)
- background `#FEFBFF`, onBackground `#1B1B1F`, surface `#FEFBFF`, surfaceVariant `#E3E2EC`, onSurfaceVariant `#44464F`
- outline `#757780`, inversePrimary `#B0C6FF`
- error `#BA1A1A`, onError `#93000A`, errorContainer `#FFDAD6`, onErrorContainer `#410002`

**PureBlack/OLED override** (`ui/theme/YamiTheme.kt:96-100`): when `darkTheme && pureBlack`, `baseScheme.copy(background = Color.Black, surfaceContainer = Color.Black)`.

**Dynamic color:** signature param `dynamicColor=false` present but `@Suppress("UNUSED_PARAMETER")` — NO-OP, awaiting a `:platform DynamicColorProvider` SPI (FORECAST-NOT-YET-FULFILLED, `YamiTheme.kt:67-81`,`:92`). Same OFF-by-default posture as OLD.

### Shapes (`ui/theme/YamiShapes.kt:36-42`)
- extraSmall `4.dp`, small `8.dp`, medium `12.dp`, large `16.dp`, **extraLarge `0.dp`** (square / full-bleed for reader). Byte-for-byte parity with OLD.

### Spacing (NEW central token object — `ui/theme/Spacing.kt:50-64`)
- `data class Spacing(xxs=2.dp, xs=4.dp, sm=8.dp, md=12.dp, lg=16.dp, xl=24.dp, xxl=32.dp)` provided via `val LocalSpacing = compositionLocalOf { Spacing() }`. Installed by `YamiTheme`. Consumed as `LocalSpacing.current.md` across ~19 `:ui` screens + 2 `:composeApp` route adapters. This is a REWORK addition (OLD had no central Dimens/Spacing object — task #552).

### Root theme composable
- `YamiTheme(darkTheme, pureBlack=false, dynamicColor=false, content)` (`ui/theme/YamiTheme.kt:88-114`): picks scheme, applies pureBlack override, wraps `MaterialTheme(colorScheme, typography=yamiTypography(), shapes=YamiShapes)` + `CompositionLocalProvider(LocalSpacing provides Spacing())`. Used at leaf-screen scope; app-root still uses legacy `YamiMangaTheme` (see App-Shell note).

### Resources
- **`:ui` composeResources:** `values/strings.xml` (319 strings) + `values-ar/strings.xml` (319 strings — Arabic parity complete per task #741); `font/` (3 Gellix); `drawable/` (only `ic_launcher_foreground.xml`).
- **`:composeApp` composeResources:** `values/` + 10 locale variants (`values-ar/de/es/fr/in/it/ja/pt/ru/tr`); large `drawable/` set (source icons `ic_*.xml`, reader-mode icons, anti-horny adult-confirm images, social icons); `font/` (legacy unreferenced fonts); `files/`.

### Cross-cutting observations
- **DI:** Koin (`koinViewModel()`/`koinInject()`), not Hilt.
- **Material2/Material3 mixing:** the rework `:ui` components use Material3 throughout (the OLD Material2 hazards in IconAboveTextButton/StatsItem/SwitchItem/SectionTitle were not carried into the rework design-system tier).
- **No deep links / no nav transitions** in the rework graph — 1:1 with OLD.
- **Edge-to-edge:** preserved (top edge-to-edge, only bottom inset consumed) (`App.kt:350`).
- **Components retired vs OLD (intentionally not in rework DS):** ToastHost (#361), Base*FloatingActionButton (#362), VerticalGridFastScroller (#364), LanguageToggleWithAnimation (#356). The rework re-created only `VerticalFastScroller` (#744) in `:ui`.
- **No legacy screen is user-reachable** — every NavHost route renders a rework screen; the parallel `*Rework` keys are debug-only entries.
- **WebView is the most divergent surface vs OLD** — the rework delegates to a `WebViewHost` expect/actual that intentionally dropped OLD's URL sandboxing, in-memory history, render-crash recovery, zoom, lifecycle hardening, and same-host cookie interception; it keeps Cookie+UA capture (Bug-4-layer-2) and basic back/forward/reload/progress (task #742). Desktop ignores UA override.
- **Hardcoded WebView chrome strings** ("Loading...", "Close", "Back", etc.) are un-localized — only un-localized surface noted in this cluster (the rest go through compose-resources).

### Missing / absent assets (recorded explicitly)
- No deep-link intent filters anywhere in the graph.
- No nav animation specs.
- `DynamicColorProvider` SPI not yet declared (dynamic color is a documented no-op).
- App-root still binds legacy `YamiMangaTheme`, not rework `YamiTheme`.
- Gilroy / Poppins / alba / extra-Gellix fonts shipped in `:composeApp` but unreferenced.
- `WebViewController` exists (unlike the OLD KDoc that forecast it as a TODO) — that forecast is now FULFILLED (nav controls live).
