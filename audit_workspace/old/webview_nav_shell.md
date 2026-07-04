# OLD Native Android Audit — WebView · Navigation · App-Shell · Theming

> Source root: `D:/yami manga/yami-manga-apk-main/app/src/main/java/me/manga/yami/`
> Package in source files is `me.manga.kira.*` (directory is `yami`).
> Read-only audit. All citations are `file:line`. Inferences are marked **(INFERRED)**.

---

## ① WebView Screen

### WebViewComposeScreen
- **Entry/route:** Reached via `Screen.WebView(url: String, api: String)` route → `WebViewRoute` (`navigation/routes/WebViewRoute.kt:14-32`) which calls `WebViewComposeScreen(args.api, args.url, onSaveHeaders = vm::saveHeaders){ header, api -> vm.saveHeaders(header,api); navController.safePopBackStack() }`. Also a thin wrapper `WebViewScreen(...)` exists (`features/webview/ui/components/webView.kt:10-27`) taking `onBackPressed`, but the live path is `WebViewRoute`. Core composable: `features/webview/ui/screens/WebViewComposeScreen.kt:60-524`. (Lines 538-1043 are a fully commented-out earlier copy — ignore.)
- **Layout & components:** `Scaffold` (`:385`) → `TopAppBar` (`:387`, Material3, `ExperimentalMaterial3Api`) + body `Column(fillMaxSize + padding)` (`:476`). Body = optional `LinearProgressIndicator` (`:482`, 4.dp tall, fillMaxWidth) then `AndroidView` wrapping an Android `WebView` (`:492`) OR a fallback `Text` (`:517`). The WebView is created imperatively inside a `LaunchedEffect` (`:102`) and held in `webViewInstance` state; `AndroidView.factory` returns the pre-built instance (`:493-495`).
- **Visual:** TopAppBar title is a 2-line `Column`: line 1 = page title (`pageTitle.ifEmpty{"Loading..."}`, maxLines 1, ellipsis, `:398-402`), line 2 = current URL (`bodySmall`, maxLines 1, ellipsis, `:403-408`). Progress bar height 4.dp (`:486`). No custom colors — inherits default `TopAppBar` colors. Fallback text padding 16.dp (`:519`).
- **States:**
  - *loading* — `isLoading` true while `onProgressChanged < 100` (`:158-161`) and on `onPageStarted` (`:249`); shows `LinearProgressIndicator(progress = progress/100f)` (`:481-488`).
  - *empty* — none distinct; pre-instance shows `Text("Loading WebView...")` (`:518`).
  - *error/crash* — `webViewError` flag. On `onRenderProcessGone` (`:277-293`) cleans up, sets `webViewError=true`, bumps `recreationKey` to force recreation, clears history/headers. While erroring, body shows `Text("WebView crashed. Recreating...")` (`:518`). `onReceivedError` (`:295-309`) silently ignores `ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_TIMEOUT` (no error UI) — only flips `isLoading=false`.
  - *success* — WebView visible (`webViewInstance != null && !webViewError`, `:491`).
- **Interactions:**
  - *Android back* — `BackHandler` (`:364-383`): if `historyIndex > 0` step back through in-memory `history` list and `loadUrl`; else cleanup + `onClose(savedHeaders, api)`. Wrapped in try/catch → cleanup+close on failure.
  - *Nav controls* (TopAppBar actions, `:411-471`): Back arrow (`Icons.AutoMirrored.Filled.ArrowBack`, enabled `canGoBack && !isLoading`), Forward (`ArrowForward`, enabled `canGoForward && !isLoading`), Refresh (`Icons.Default.Refresh`, enabled `!isLoading`, calls `webView.reload()` with crash-recovery), Save (`Icons.Default.Save`, enabled `savedHeaders != null && !isLoading`, calls `onSaveHeaders`). Nav icon = Close (`Icons.Default.Close`, `:393`) → cleanup + `onClose`.
  - *History model* — custom in-memory `mutableStateListOf<String>` capped at 50 entries (`:258-261`); forward branch truncated on new navigation (`:263-266`); `historyIndex` tracks position. NOT the WebView's native back/forward list.
  - *Zoom* — pinch zoom enabled (`setSupportZoom(true)`, `builtInZoomControls=true`, `displayZoomControls=false`, `:126-128`).
  - *animations/transitions* — none custom (default Compose nav).
- **Dialogs/sheets/snackbars:** None.
- **Forms & validation:** None (it's a browser).
- **Data/behavior:**
  - *WebSettings* (`:116-151`): JS enabled, DOM storage + DB enabled, `cacheMode=LOAD_CACHE_ELSE_NETWORK` (ANR mitigation), `loadWithOverviewMode`, `useWideViewPort`, `RenderPriority.HIGH`, `allowContentAccess=false`, `allowFileAccess=false`, file-URL access off, `mediaPlaybackRequiresUserGesture=true`, `mixedContentMode=COMPATIBILITY_MODE`, geolocation off, multiple-windows off, `setNetworkAvailable(true)`, `textZoom=100`, `utf-8`, `LayoutAlgorithm.NORMAL`, `LAYER_TYPE_HARDWARE`. `isFocusable=false`/`isFocusableInTouchMode=false` to dodge focus crashes.
  - *URL gating* — `shouldOverrideUrlLoading` (`:188-211`): main-frame requests blocked unless `isAllowed()` (same-host as `initialUrl`, http/https only, with `urlValidationCache`, `:165-186`); sub-frame `javascript`/`file` schemes blocked. `about:`/`data:` always allowed.
  - *Cookie capture (the point of this screen)* — `shouldInterceptRequest` (`:214-245`): when request host contains the initial host, launches a background `Dispatchers.IO` coroutine to read `CookieManager.getInstance().getCookie(initialUrl)`, merges into request headers as `Cookie`, posts back to `savedHeaders` on Main. The Save action persists `savedHeaders` for the source `api` (used to bypass Cloudflare).
  - *Persistence* — `WebViewViewModel.saveHeaders(headers, api)` (`features/webview/ui/viewmodel/WebViewViewModel.kt:24-38`): on `Dispatchers.IO`, `sourcesRepository.getRepoByName(api).refreshHeaders(headers)`; no-op if headers null/empty; swallows exceptions. VM (`@HiltViewModel`) deps: `DataStoreHelper`, `ActiveRepoProvider`, `SourcesRepository`.
  - *Lifecycle* — `DisposableEffect` (`:326-361`) wires `onResume/resumeTimers`, `onPause/pauseTimers`, `onDestroy→cleanup`; onDispose removes observer + cleans up.
  - *Cleanup* — `cleanupWebView` (`:86-99`): clearFocus, stopLoading, clearCache(true), clearHistory, removeView from parent, destroy — all try/catch-guarded.
- **Feature inventory:** Close button; 2-line title (page title + URL); linear progress; back/forward (custom history); reload (with crash-recovery); save-headers; pinch zoom; same-host URL sandboxing; cookie sniffing → header persistence; render-process-crash auto-recreation; lifecycle pause/resume; hardware-layer rendering; ANR-hardened cache mode + background cookie reads.
- **Citations:** `features/webview/ui/screens/WebViewComposeScreen.kt:60,86,102,116-151,153-162,164-310,326-361,364-383,385-524`; `navigation/routes/WebViewRoute.kt:14-32`; `features/webview/ui/components/webView.kt:10-27`; `features/webview/ui/viewmodel/WebViewViewModel.kt:16-39`.

---

## ② Navigation graph (full route table)

### NavGraph (NavGraphV2)
- **Entry/route:** `NavGraphV2(navController, onBottomBarVisibleChange)` (`navigation/NavGraphV2.kt:134-595`), hosted by `MainActivity.MainScreen()` (`MainActivity.kt:547-581`). Type-safe routes via `kotlinx.serialization` `@Serializable sealed class Screen(val route: String)` (`NavGraphV2.kt:56-132`) + `composable<Screen.X>` blocks.
- **Start destination:** dynamic (`NavGraphV2.kt:160`): `val rootStart = if (firstLaunch) Screen.Welcome else Screen.Library`. `firstLaunch` is a `PrefsDelegate(key="first_launch", default=true)` (`:151-155`). So first ever launch → onboarding `Welcome`; thereafter → `Library`.
- **Shared graph-scoped VMs** (hoisted at `NavGraphV2` top, `:140-144`): `WhatsNewViewModel`, `RepoSettingsViewModel`, `SharedChaptersViewModel` (chapter list shared into reader), `DownloadViewModelv2`, `AdViewModel`.
- **Bottom-bar visibility:** every destination calls `SideEffect { onBottomBarVisibleChange(true/false) }` (true only for the 5 tab roots: Library, Updates, Home, History, Setting).

#### Full route table

| Route (Screen) | Args | Renders | BottomBar | Notes |
|---|---|---|---|---|
| `Welcome` (object) | — | `WelcomeScreenRoute` | hidden | onboarding (`:168-173`) |
| `Theme` (object) | — | `ThemeSelectionScreenRoute` | hidden | onboarding (`:175-180`) |
| `Sources` (object) | — | `SourcesScreenRoute(repoSettingsViewModel)` | hidden | onboarding (`:182-187`) |
| `Home` (object) | — | `HomeRoute` | **shown** | `:189-272` — BackHandler→safePopBackStack; reselect handler; listState+gridState saveable |
| `Library` (object) | — | `LibraryRoute` | **shown** | `:274-314` — default start dest; `BackHandler(enabled=false)`; onOpenRandomClick + onLibraryMangaClick |
| `History` (object) | — | `HistoryRoute` | **shown** | `:316-390` — onHistoryImgClick + onHistoryItemClick |
| `Updates` (object) | — | `NotificationsRoute` | **shown** | `:392-431` — onNotificationClick + onNotificationImgClick |
| `Setting` (object) | — | `SettingsRoute` | **shown** | `:514-519` |
| `Statistics` (object) | — | `StatisticsRoute` | hidden | `:520-525` |
| `MangaDetails` (data) | `mangaUrl:String, api:String` | `MangaDetailsRoute` | hidden | `:433-466` — onChapterClick + onDownloadClick(→Library) |
| `LibraryMangaDetails` (data) | `mangaId:Long` | `LibraryMangaRoute` | hidden | `:469-497` |
| `ChapterImagesFragment` (data) | `isHome:Boolean=false, api:String, language:String, mangaId:Long=0, chapterId:Long=0, mangatitle:String, mangaUrl:String, mangaImgUrl:String, chapterNumber:String, chapterUrl:String, paths:List<String>?, isDownload:Boolean` | `ReadingScreenRoute(sharedChaptersVm)` | hidden | `:499-512` — the reader |
| `WebView` (data) | `url:String, api:String` | `WebViewRoute` | hidden | `:527-532` |
| `RepoSettings` (data) | `isFirstOpen:Boolean=false` | `RepoSettingsScreenRoute(repoSettingsViewModel)` | hidden | `:535-542` |
| `LanguageScreen` (object) | — | `LanguageScreenRoute` | hidden | `:543-548` |
| `DownloadsScreen` (object) | — | `DownloadsScreenRoute` | hidden | `:550-555` |
| `AboutScreen` (object) | — | `AboutScreen(whatsNewViewModel){safePopBackStack}` | hidden | `:557-565` |
| `Complaint` (object) | — | `ComplaintScreenRoute` | hidden | `:567-572` — route string `...ComplaintScreen` |
| `WhatsNewScreen` (data) | `isFirstOpen:Boolean=false` | `WhatsNewRoute(whatsNewViewModel)` | hidden | `:574-581` |
| `ComplaintAdmin` (object) | — | `AdminComplaintScreenRoute` | hidden | `:584-589` |

Declared in `Screen` sealed class but **not given a `composable<>` block** in this graph: `Updates` HAS a block; all 20 declared screens have blocks **except none missing** — note `Screen.Updates` route string is `...Screen.Updates`. (No deep links, no nav animations declared anywhere.)

- **Deep links:** **None.** No `deepLinks=` arg on any `composable<>`; `AndroidManifest.xml` MAIN/LAUNCHER only — no `<data android:scheme>` `VIEW` filters (manifest:38-42). External entry points: `me.manga.kira.ACTION_CANCEL_DOWNLOAD` / `ACTION_CANCEL_CHAPTER_DOWNLOAD` (download-service receiver, manifest:80-83) and `com.google.firebase.MESSAGING_EVENT` (FCM, manifest:88-90) — neither routes into the NavGraph.
- **Navigation cross-links of note (side effects in route lambdas):**
  - Home → MangaDetails OR (if saved) resolves `sharedChaptersVm.getIdByApiTitle` → `LibraryMangaDetails(id)` else `MangaDetails` (`:218-238`).
  - Home/Details/Library/History/Updates → `ChapterImagesFragment` (reader) after `sharedChaptersVm.set*ChaptersList(...)` (`:240-266`, `:438-465`, `:477-494`, `:353-387`, `:399-421`).
  - Library `onOpenRandomClick` → random `LibraryMangaDetails` else Toast "No manga in your library yet!" (`:284-300`).
  - History/Updates guard `sharedChaptersVm.isMangaExists(mangaId)` else Toast "THis Manga Is Deleted from the Libarary" (sic) (`:325-336`, `:356-384`).
  - Details `onDownloadClick` → `navigate(Screen.Library.route){ popUpTo(graph.id){inclusive=false}; launchSingleTop=true }` (`:459-464`).
- **Citations:** `navigation/NavGraphV2.kt:56-132` (Screen defs), `:134-595` (graph); `MainActivity.kt:547-581`; `AndroidManifest.xml:38-90`.

### NavigationLock
- **Entry/route:** `navigation/NavigationLock.kt:7-39`. Utility class (not obviously wired into the live graph — **(INFERRED)** likely legacy/unused given route lambdas don't reference it; the live anti-double-nav is `safePopBackStack` + `launchSingleTop`).
- **Behavior:** `Mutex`-guarded `withLock { … }` (suspend, delays 100ms if already navigating) and `tryLock { … }: Boolean` (returns false if mid-nav). Single `isNavigating` flag.
- **Citations:** `navigation/NavigationLock.kt:7-39`.

### safePopBackStack (navigation utilities)
- **Entry/route:** `navigation/safePopBackStack.kt`. Used by `Home` BackHandler (`NavGraphV2.kt:192`), `WebViewRoute` (`:29`), `AboutScreen` (`:563`), etc.
- **Behavior:**
  - `NavController.safePopBackStack(libraryRoute=Screen.Library.route, clearFocus=true): Boolean` (`:24-72`): clears decor-view focus (FocusFinder crash guard), if no previous entry → `navigateToLibrary`, else `popBackStack()`; catches `IllegalStateException`/`IllegalArgumentException`/`Throwable` → fallback to library.
  - `navigateToLibrary` (`:77-97`): `navigate(libraryRoute){ launchSingleTop=true; restoreState=true; popUpTo(startDestId){inclusive=false; saveState=true} }`.
  - `clearAllFocus(context)` (`:103-108`): clears focus on decorView + rootView.
  - `safePopBackStackAsync(delayMs=50)` (`:119-128`): suspend, delays a frame then pops.
  - `safeNavigate(route, clearFocus=true, builder)` (`:138-158`): focus-clearing navigate wrapper, swallows exceptions.
- **Citations:** `navigation/safePopBackStack.kt:24-158`.

### double_click (Home tab reselect)
- **Entry/route:** `navigation/double_click/HomeTabReselectedHandler.kt:3-5` (interface `onHomeTabReselected()`), `NavigationHandlerHolder.kt:4-6` (singleton object holding `var homeReselectHandler`).
- **Behavior:** In `NavGraphV2` Home block (`:202-211`) an anonymous `HomeTabReselectedHandler` is created that `listState.scrollToItem(0)` + `gridState.scrollToItem(0)` on `Dispatchers.Main`, and registers itself into `NavigationHandlerHolder.homeReselectHandler`. The bottom bar invokes it when the Home tab is reselected while already selected (see BottomNavigationBar).
- **Citations:** `navigation/double_click/HomeTabReselectedHandler.kt:3-5`, `NavigationHandlerHolder.kt:4-6`, `NavGraphV2.kt:202-211`.

---

## ③ App Shell / Scaffold

### MainActivity / App entry
- **Entry/route:** `MainActivity.kt:94-974`, `@AndroidEntryPoint class MainActivity : AppCompatActivity()`. LAUNCHER activity (manifest MAIN/LAUNCHER).
- **Layout & components:** `onCreate` (`:199-424`): `installSplashScreen()` (splash via `Theme.App.Starting`), `super.onCreate`, `WindowCompat.setDecorFitsSystemWindows(window,false)` (edge-to-edge), then `setContent { … }`. Inside content: pulls `SettingsViewModel` flows (`followSystem`, `darkMode`, `pureBlack`, `:318-329`), wraps everything in `YamiMangaTheme(darkTheme = if(system) isSystemInDarkTheme() else dark, pureBlack=pureBlack)` (`:339-342`). If `Admin.testingMode` → `ApiTestScreen` (`:344-417`, dev-only) else `MainScreen()` (`:419`).
- **`MainScreen()`** (`:547-581`): `rememberNavController()`; `var showBottomBar by remember{false}`; `Scaffold(bottomBar = { if(showBottomBar) Column { Divider(1.dp, onSurface@25%) ; BottomNavigationBar(navController) } })` (`:553-566`); body `Box(padding(bottom = paddingValues.calculateBottomPadding()))` (top stays edge-to-edge) wrapping `NavGraphV2(navController, onBottomBarVisibleChange = { showBottomBar = it })` (`:573-577`).
- **Visual:** bottom bar gated by a hairline Divider (1.dp, `onSurface.copy(alpha=0.25f)`, fillMaxWidth, `:558-561`). Only bottom inset is consumed; top is edge-to-edge. Status/nav bars transparent (theme + `setupTransparentNavigationBar` helper at `:582-605`, though edge-to-edge is set in onCreate).
- **States:** theme state from `SettingsViewModel` (followSystem / darkMode / pureBlack). Bottom-bar show/hide reactive to active destination.
- **Interactions / side effects (onCreate, heavy):** Firebase `APP_OPEN` analytics event (`:274`); `updateSources.initializeSources()` (`:276`); in-app update check `AppUpdateHelper.checkForUpdate(immediate=false)` (`:278-288`); UMP consent flow on `Dispatchers.IO` (`requestConsent`→`loadAndShowForm`→`initializeAds`, `:289-298`, `:428-532`); in-app review `reviewHelper.launchInAppReview` (`:299-301`, `:534-544`); update progress listener (`:304-313`). `onResume` resumes flexible update (`:883-897`); `onActivityResult` handles update flow result code 100 (`:899-922`); `onDestroy` cancels reviewScope + unregisters update listener (`:924-933`).
- **Dialogs/sheets/snackbars:** UMP consent form (Google), in-app-update prompts, in-app-review prompt — all platform/Google UIs, not Compose.
- **Forms & validation:** none in shell.
- **Data/behavior:** DEX plugin loader helpers `runPluginHtml/runPluginList/runPluginGetConfig` (`:116-195`, dev/experimental); device-id via `Settings.Secure.ANDROID_ID` (`:607-609`); several `fetch*` debug HTTP helpers (`:616-790`, testing-only); `shareTextFile` via FileProvider (`:791-814`); `saveToDownloadsFile` (`:965-972`). Hilt-injected: `ReviewManagerHelper`, `FirebaseAnalytics`, `IMangaDataApiServices`, `UpdateSourcesRepository`.
- **Feature inventory:** splash screen; edge-to-edge window; theme application (system/dark/pureBlack); conditional bottom bar with hairline divider; nav host; ads consent + init; in-app update (flexible); in-app review; FCM; analytics; admin/testing mode bypass screen; DEX plugin experiments.
- **Citations:** `MainActivity.kt:94-95,199-424,428-532,534-544,547-581,582-605,883-933`; `theme/Theme.kt:97-134`; `AndroidManifest.xml:38-42`.

### BottomNavigationBar
- **Entry/route:** `presentation/common/componants/BottomNavigationBar.kt:31-81`. Rendered by `MainScreen` when `showBottomBar`.
- **Layout & components:** Material3 `NavigationBar(containerColor = colorScheme.background)` (`:44-45`) with 5 `NavigationBarItem`s built from a `listOf(Triple(...))` (`:35-42`).
- **Tabs (order as displayed, left→right):**
  1. `Screen.Library` — `Icons.AutoMirrored.Filled.LibraryBooks` — `R.string.title_library` ("Library")
  2. `Screen.Updates` — `Icons.Default.Notifications` — `R.string.title_notifications` ("Notification")
  3. `Screen.Home` — `Icons.Default.Home` — `R.string.title_home` ("Home") **(center)**
  4. `Screen.History` — `Icons.Default.History` — `R.string.title_history` ("History")
  5. `Screen.Setting` — `Icons.Default.Settings` — `R.string.title_settings` ("Settings")
- **Visual:** label uses `NavigationBarAutoText` (auto-shrinking BasicText, 8→14sp, `onBackground`, `auto_sized_text/AutoSizedText.kt:54-73`); selected indicator color = `colorScheme.primaryContainer` (`:74-76`). Icon contentDescription null.
- **States:** selected = `currentDestination?.hierarchy?.any { it.route == screen.route }` (`:49-52`).
- **Interactions:**
  - *reselect* — if already selected AND `screen is Screen.Home`: invoke `NavigationHandlerHolder.homeReselectHandler?.onHomeTabReselected()` (scroll Home to top) (`:61-63`). Reselect on other tabs does nothing special.
  - *navigate* — else `navController.navigate(screen){ popUpTo(graph.findStartDestination().id){saveState=true}; launchSingleTop=true; restoreState=true }` (`:64-72`) — standard multi-backstack state save/restore.
- **Dialogs/sheets/snackbars:** none. **Forms:** none.
- **Feature inventory:** 5 tabs; Home center; auto-sizing labels; primaryContainer indicator; Home double-tap-to-top; state-saving tab switching; hairline divider above bar (provided by MainScreen, not this composable).
- **Citations:** `presentation/common/componants/BottomNavigationBar.kt:31-81`; labels `res/values/strings.xml:4-8`.

---

## ④ Design-System / Shared Components Inventory

### TopAppBarCom (app_bars)
- **Layout & components:** Reusable Material3 `TopAppBar` (`app_bars/TopAppBarCom.kt:18-44`). Params: `title`, `backgroundColor=colorScheme.background`, `textColor=onBackground`, `titleSize=24.sp`, `fontWeight=Bold`, `navigationIcon={}`, `actions={}`.
- **Visual:** title `titleLarge.copy(fontSize=titleSize)`, Bold, maxLines 1, ellipsis. containerColor=background.
- **Citations:** `app_bars/TopAppBarCom.kt:16-44`.

### SearchAppBar (app_bars)
- **Layout & components:** Material3 `TopAppBar` with Close nav icon, an `OutlinedTextField` as title, and slot `actions` (`app_bars/SearchAppBar.kt:34-117`). Params: `query`, `onQueryChange`, `onToggleSearch`, `onSearch`, `actions`.
- **Visual:** transparent borders + transparent container on the text field (looks like inline search); leading `Search` icon, trailing clear `Close` (only when query non-blank); placeholder `R.string.searching_placeholder` ("Search your manga…"); `labelLarge` style; `RoundedCornerShape(12.dp)`; height 56.dp; cursor=onBackground; containerColor=background.
- **Interactions:** IME action Search → `onSearch(query)` + hide keyboard (`:90-98`); Close nav icon → `onToggleSearch()` + clears query (`:51-60`); trailing clear → `onQueryChange("")`.
- **Forms:** single-line search field, no validation.
- **Citations:** `app_bars/SearchAppBar.kt:34-117`; strings.xml:77.

### ActionButton (buttons)
- **Layout:** vertical icon-over-label button on a transparent `Surface` with `combinedClickable` (onClick + onLongClick), `ExperimentalFoundationApi` (`buttons/ActionButton.kt:32-81`). Params: `text, icon, color, onClick, onLongClick={}, isLoading=false`.
- **Visual:** 24.dp icon (tinted `color`) OR 24.dp `CircularProgressIndicator(strokeWidth=2.dp, primary)` when loading; 4.dp spacer; `AutoSubtitleText` 10sp (min 4sp), maxLines 1, centered. Padding h4/v8.
- **Citations:** `buttons/ActionButton.kt:30-81`.

### IconAboveTextButton (buttons)
- **Layout:** **Material2** `Button` (note: `androidx.compose.material.Button`) transparent bg, icon over auto-text (`buttons/IconAboveTextButton.kt:23-54`). Params: `title, icon, onClick, iconSize=24.dp, spacing=4.dp`.
- **Visual:** primary-tinted icon + `AutoSubtitleText` primary, centered; elevation 0.
- **Citations:** `buttons/IconAboveTextButton.kt:22-54`.

### FeedbackDialog (dialogs)
- **Layout:** Material3 `AlertDialog`, `RoundedCornerShape(20.dp)`, surface container, tonalElevation 3.dp (`dialogs/FeedbackDialog.kt:34-231`). Sections: header (`headlineSmall` Bold + subtitle), category `ExposedDropdownMenuBox` over `ComplaintType.entries`, feedback `OutlinedTextField` (min 120.dp, minLines 4 / maxLines 6, char counter `len/500`, error if `<5`), social-media divider + `SocialMediaRow()`.
- **States:** `submitEnabled = selectedType != null && feedbackBody.length >= 5` (`:49-51`).
- **Forms & validation:** category required + body ≥5 chars; error text `R.string.minimum_5_characters_required`; live `${len}/500` counter.
- **Interactions:** confirm `Button` (`R.string.submit`, RoundedCornerShape 12.dp), dismiss `TextButton` (`R.string.cancel`).
- **Extras:** also defines two `Modifier.simpleVerticalScrollbar` extensions (LazyListState + ScrollState variants, red thumb, fade animation) at `:246-316`.
- **Citations:** `dialogs/FeedbackDialog.kt:34-316`.

### FloatingActionButton (floating_button)
- **Components:** `BaseFloatingActionButton` (Material3 FAB, `:45-68`), `BaseExtendedFloatingActionButton` (animated expand/collapse text via AnimatedVisibility, `:82-129`), `AnimatedCircleExtendedFab` (AnimatedContent switching circle↔extended, `:134-188`). Defaults: containerColor=primary, contentColor=onPrimary, tonalElevation 4.dp.
- **Note:** Task memory (#362) marks `BaseFloatingActionButton`/`BaseExtendedFloatingActionButton` as orphaned in the rework — present in OLD, dead in KMP.
- **Citations:** `floating_button/FloatingActionButton.kt:45-188`.

### Chips (flow_chips)
- **ChipsRow** (`flow_chips/ChipsRow.kt:24-83`): `FlowRow` of `FilterChip`s, single-select by `selectedItem` equality; Done leading icon when selected (onPrimary tint); `RoundedCornerShape(16.dp)`; selectedContainer=primary, selectedLabel=onPrimary; min height 32.dp.
- **ChipsColumn** (`flow_chips/ChipsColumn.kt:24-83`): `FlowColumn` variant, chips fillMaxWidth, centered labels; same color/shape scheme.
- **Citations:** `flow_chips/ChipsRow.kt:24-83`, `flow_chips/ChipsColumn.kt:24-83`.

### Images (images)
- **BlurredImageCoil** (`images/BlurredImageCoil.kt:13-25`): Coil3 `AsyncImage` with `BlurTransformation(radius=25f, sampling=8f)` from `core.blur`; `contentScale` param (default Fit).
- **ImageWithGradientOverlay** (`images/ImageWithGradientOverlay.kt:20-60`): blurred header image (FillBounds) with parallax (`graphicsLayer{translationY=-parallaxOffset}`) + vertical gradient overlay (background@0.4 → background); params `imageUrl, headerHeightDp, startColor, endColor, blur=24.dp, parallaxOffset`. Used as manga-details hero backdrop **(INFERRED)**.
- **Citations:** `images/BlurredImageCoil.kt:13-25`, `images/ImageWithGradientOverlay.kt:20-60`.

### List items (list_items)
- **StatsItem** (`list_items/StatsItem.kt:32-83`): Row(optional 24.dp icon + 16.dp spacer, title `14sp` + optional description `12sp`@0.8α, trailing value via `R.string.value_count` `%,d` bold 12sp centered); optional clickable. **Mixes Material2 `Text`/`Icon`/`Surface` with Material3 `MaterialTheme`.**
- **SwitchItem** (`list_items/SwitchItem.kt:29-73`): Row(optional icon + title/description + Material2 `Switch`); checkedThumb=primary, uncheckedThumb=surfaceVariant, uncheckedTrack=onBackground@0.4α; title 14sp, desc 12sp@0.5α.
- **Citations:** `list_items/StatsItem.kt:32-83`, `list_items/SwitchItem.kt:29-73`.

### Scroll (scroll)
- **VerticalFastScroller / VerticalGridFastScroller** (`scroll/LazyVerticalScrollerWithScrollBar.kt:61-195`, `:229-387`): Tachiyomi-style draggable fast-scroll thumb over a `SubcomposeLayout`; thumb 48.dp×12.dp, `RoundedCornerShape(thickness/2)`, `thumbColor=primary`; fades after scroll (fade-out delay 2000ms via `ViewConfiguration.getScrollBarFadeDuration()`); `systemGestureExclusion`; sticky-header aware (`STICKY_HEADER_KEY_PREFIX="sticky:"`).
- **Note:** Task #364 marks `VerticalGridFastScroller` orphaned in rework; #744 recreated `VerticalFastScroller` in `:ui`.
- **Citations:** `scroll/LazyVerticalScrollerWithScrollBar.kt:61-461`.

### Titles (titles)
- **SectionTitle** (`titles/SectionTitle.kt:14-22`): `Text` 14sp Bold `onBackground`, bottom padding 8.dp. (Material2 `Text`.)
- **Citations:** `titles/SectionTitle.kt:14-22`.

### Toast (toast)
- **ToastHostState / ToastHost** (`toast/ToastHostState.kt:29-88`): in-house snackbar. `ToastHostState.show(message, durationMillis=2000)` sets a state; `ToastHost` renders an `AnimatedVisibility` (slideInVertically+fadeIn / out) Box (fillMaxWidth, shadow 4.dp, `RoundedCornerShape(8.dp)`, bg=`onPrimaryContainer`, text=`onPrimary` bodyMedium), auto-dismiss after duration.
- **Note:** Task #361 marks this orphaned/retired in rework.
- **Citations:** `toast/ToastHostState.kt:29-88`.

### auto_sized_text
- **AutoSubtitleText** (`auto_sized_text/AutoSizedText.kt:18-46`): `BasicText` + `TextAutoSize.StepBased(min 6 / max 14sp, step 0.1sp)`, maxLines 2 default; used by ActionButton/IconAboveTextButton.
- **NavigationBarAutoText** (`:54-73`): `BasicText` + StepBased(min 8 / max 14sp, step 1sp), maxLines 1, `onBackground`; used by BottomNavigationBar labels.
- **Citations:** `auto_sized_text/AutoSizedText.kt:18-73`.

### ItemsGroup / isScrolledToTheEnd (root componants)
- **ItemsGroup** (`componants/ItemsGroup.kt:17-29`): rounded card Column (`surfaceContainerHigh`, `RoundedCornerShape(16.dp)`, padding h16/v8) — settings-style grouping container.
- **isScrolledToTheEnd** (`componants/isScrolledToTheEnd.kt:6-16`): `LazyListState`/`LazyGridState` extensions returning whether last visible index == last item — pagination trigger.
- **Citations:** `componants/ItemsGroup.kt:17-29`, `componants/isScrolledToTheEnd.kt:6-16`.

### sources/LanguageToggleWithAnimation
- **Layout:** `AnimatedVisibility(visible = repos.any{enabled}, enter=fadeIn+expandVertically, exit=fadeOut+shrinkVertically)` wrapping a Column of `RepoToggleItem`s built from `BaseMangaRepository` list with vector icons; enabled/disabled strings (`R.string.enabled`/`disabled`) (`sources/LanguageToggleWithAnimation.kt:18-48`).
- **Note:** Task #356 marks orphaned in rework.
- **Citations:** `sources/LanguageToggleWithAnimation.kt:18-48`.

---

## ⑤ Cluster notes — Full design-token inventory

### Fonts
- **Compose family:** `GellixFontFamily` (`theme/Type.kt:12-16`): `R.font.gellix_regular`@Normal, `R.font.gellix_semibold`@Medium, `R.font.gellix_bold`@Bold. (Only 3 of the shipped weights are wired into Compose.)
- **Font files present** (`res/font/`, raw .ttf — **no `<font-family>` XML wrappers**): `alba.TTF`, gellix_{black,bold,extrabold,regular,semibold,thin}.ttf, gilroy_{bold,light,regular}.ttf, poppins_{black,bold,light,medium,regular,semibold,thin}.ttf. **Gilroy + Poppins + alba ship but are NOT referenced by the Compose theme** (likely XML-layout/legacy use, e.g. `gellix_bold` in `TabedText`/`ToolbarTitleText` styles). **(INFERRED)** Gellix is the live app font.
- **XML font usages** (`res/values/themes.xml`): `TabedText`→gellix_bold (`:31`), `unTabedText`→gellix_regular (`:37`), `ToolbarTitleText`→gellix_bold (`:76`).

### Typography scale (Compose `Typography`, `theme/Type.kt:18-36`)
Only 3 styles overridden; everything else = Material3 defaults:
- `bodyLarge` = Gellix **Bold** 16sp
- `titleMedium` = Gellix Medium 14sp
- `titleSmall` = Gellix Normal 12sp
(Components frequently override inline: 24sp app-bar titles, 14sp/12sp list rows, 10sp action labels.)

### Color palette
**Compose dark scheme** (`theme/Theme.kt:17-51`, `darkColorScheme`):
- primary `#B0C6FF`, onPrimary `#002D6E`, primaryContainer `#00429B`, onPrimaryContainer `#D7E2FF`
- secondary = same blues as primary; tertiary `#B8D0FF`/onTertiary `#003063`/tertiaryContainer `#2C2C2F`
- **background `#15202B`** (Twitter-night blue, not pure dark — note commented-out `#1B1B1F`), onBackground `#E3E2E6`, surface `#15202B`, onSurface `#E3E2E6`, surfaceVariant `#44464F`, onSurfaceVariant `#C4C6D0`
- outline `#8E9099`, inversePrimary `#0058CA`
- error `#FFB4AB`, onError `#690005`, errorContainer `#93000A`, onErrorContainer `#FFDAD6`

**Compose light scheme** (`theme/Theme.kt:53-85`, `lightColorScheme`):
- primary `#0058CA`, onPrimary `#FFFFFF`, primaryContainer `#D7E2FF`, onPrimaryContainer `#001945`
- tertiaryContainer `#2C2C2F` (note: dark gray even in light)
- background `#FEFBFF`, onBackground `#1B1B1F`, surface `#FEFBFF`, surfaceVariant `#E3E2EC`
- outline `#757780`, inversePrimary `#B0C6FF`
- error `#BA1A1A`, errorContainer `#FFDAD6`

**PureBlack/OLED override** (`theme/Theme.kt:117-125`): when `darkTheme && pureBlack`, copies base scheme with `background = Color.Black`, `surfaceContainer = Color.Black`.

**Dynamic color:** supported but **default OFF** (`dynamicColor=false`, `theme/Theme.kt:101,108-111`) — uses `dynamicDark/LightColorScheme` only if explicitly enabled on API 31+.

**XML palette** (`res/values/colors.xml`) — used by themes.xml / splash / legacy XML:
- accent `#5899FF`, accent_background `#A35899FF`, light_blue `#99CCFF`, blue `#0066CC`
- `yami_manga_*` full M3 token set (primary `#0058CA`, onPrimary white, primaryContainer `#D9E2FF`, tertiary `#006E1B`/tertiaryContainer `#95F990`, background `#FEFBFF`, error `#BA1A1A`, full surfaceContainer{Lowest..Highest} ramp `#F5F1F8`→`#FCF7FF`) — mirrors Compose light scheme.
- misc: orange `#FF6905`, new_chapter `#63F2F2F2`, white `#F2F0FF`, black `#FF000000`, Dark_background `#1C1C1E`, dark_dim_* ramp `#121212`/`#1D1D1D`/`#0D0D0D`.

**XML themes** (`res/values/themes.xml`):
- `Theme.YamiManga` (postSplash theme, `:88-124`): full `yami_manga_*` M3 mapping, `windowBackground=@color/black`, fullscreen, transparent nav bar, translucent navigation, no title.
- `Theme.MangaX` (`:3-25`): older theme, accent primary, translucent status, fullscreen.
- `Theme.App.Starting` (`:133-137`): `Theme.SplashScreen`, splash bg=colorBackground, animated icon `ic_launcher_foreground`, postSplashTheme=`Theme.YamiManga`.
- Bottom-nav active-indicator styles use `accent_background` / `colorPrimaryContainer` (`:80-86`, `:125-127`).

### Shapes (Compose `Shapes`, `theme/Theme.kt:89-95`)
- extraSmall `RoundedCornerShape(4.dp)`, small `8.dp`, medium `12.dp`, large `16.dp`, **extraLarge `0.dp`** (square).
- Component-local shapes: search field 12.dp, chips 16.dp, feedback dialog 20.dp, dialog buttons 12.dp, toast 8.dp, items group 16.dp, fast-scroll thumb pill (thickness/2). Button shape style `ShapeAppearanceOverlay.Button.50` = 50% rounded (XML).

### Spacing (recurring values, no central tokens object)
- Standard insets: 4 / 8 / 12 / 16 / 24.dp throughout.
- Bottom-nav divider 1.dp; progress bar 4.dp; icon size 24.dp; search field height 56.dp; fast-scroll thumb 48×12.dp; feedback field min 120.dp; chip min-height 32.dp.
- **No dedicated `Spacing`/`Dimens` tokens file in OLD** — spacing is inline `.dp` literals (the rework introduced `Spacing.kt`, task #552). **(INFERRED)**

### Elevation
- FAB tonalElevation 4.dp; FeedbackDialog tonalElevation 3.dp; toast shadow 4.dp; most surfaces flat (tonalElevation 0).

### Cross-cutting observations
- **Material2/Material3 mixing:** `IconAboveTextButton`, `StatsItem`, `SwitchItem`, `SectionTitle`, and `MainActivity`'s `Divider` use legacy `androidx.compose.material.*` alongside Material3 — a parity hazard the KMP port must normalize.
- **No deep links / no nav transitions** in the OLD graph — KMP must not invent any to stay 1:1.
- **Edge-to-edge** is core to the shell (top edge-to-edge, only bottom inset consumed).
- **Several components already retired in the rework** per task memory: ToastHost (#361), Base*FloatingActionButton (#362), VerticalGridFastScroller (#364), LanguageToggleWithAnimation (#356) — list them as "OLD-only, intentionally not ported" when aligning.
- **Hilt** is the OLD DI (`@HiltViewModel`, `hiltViewModel()`); KMP uses Koin.
- **NavigationLock** appears unused by the live graph (anti-double-nav is handled by `safePopBackStack` + `launchSingleTop`). **(INFERRED)**

### Missing / absent assets (recorded explicitly)
- No `res/font/*.xml` font-family definitions (only raw .ttf).
- No deep-link intent filters in the manifest.
- No nav animation specs anywhere in the graph.
- No central spacing/dimens token object in OLD.
- Gilroy, Poppins, alba fonts shipped but unreferenced by the Compose theme.
