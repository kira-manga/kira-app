# OLD Native Android Audit — Downloads + Sources/Repo-Settings Cluster

Audit of the original native Android app (`yami-manga-apk-main`). READ-ONLY.
Package root: `me.manga.kira` (note: directory path is `me/manga/yami/...` but the package declaration is `me.manga.kira`).

Surfaces covered:
1. DownloadsScreen (tabbed: Active / Failed / Completed)
2. RepoSettingsScreen (Sources Settings — reachable post-onboarding)
3. SourcesScreen (onboarding variant of Sources, animated background + Finish)
4. CbzConversionDialog (referenced from settings; download-adjacent)

Shared building blocks: `LanguageToggle`, `LanguageToggleWithAnimation` + `RepoToggleItem`, `SwitchItem`, `ItemsGroup`, `SettingsNavigationItem`, `FeedbackDialog`, `TopAppBarCom`, `AutoSubtitleText`.

---

### DownloadsScreen
- **Entry/route:** `DownloadsScreenRoute(navController, backStackEntry, downloadViewModeltestv2: DownloadViewModelv2 = hiltViewModel())`. Builds three paged flows via `getDownloadsByState(...)` and passes them to `DownloadsScreen`. Back = `navController.safePopBackStack()`. (`navigation/routes/DownloadsScreenRoute.kt:14-65`)
- **Layout & components:** `Scaffold` with `TopAppBarCom` (title `R.string.downloads` = "Downloads", back ArrowBack icon tinted `onBackground`). Body = `Column` → `TabRow` (3 tabs) + per-tab `LazyColumn`. (`DownloadsScreen.kt:83-144`)
  - `TabRow`: `containerColor = background`; tabs = Active(0)/Failed(1)/Completed(2) from `R.string.active|failed|completed`. **Default selected tab = index 2 (Completed)** via `var selectedTab by remember { mutableStateOf(2) }`. (`DownloadsScreen.kt:76-115`)
  - Tab 0 (Active) → `ActiveDownloadsTabPaged`; tabs 1 & 2 both → `CompletedDownloadsTabPaged` (same composable; Failed reuses it with `showRetry` gated per-item). (`DownloadsScreen.kt:117-142`)
  - `ActiveDownloadsTabPaged`: `LazyColumn`, contentPadding `vertical=8.dp, horizontal=16.dp`. Per item: if `state == RUNNING` → `RunningDownloadItemCard` (progress bar + cancel), else → `DownloadItemCard(showCancel=true)`. 8.dp `Spacer` between items. (`DownloadsScreen.kt:147-204`)
  - `CompletedDownloadsTabPaged`: `LazyColumn`, same padding. Per item: `DownloadItemCard(showRetry = state==FAILED, showDelete=true)`. (`DownloadsScreen.kt:206-260`)
- **Visual:** spacing/typography/colors/shapes:
  - `DownloadItemCard`: `Card` `fillMaxWidth`, background `colorScheme.background`, outer padding `horizontal=16.dp, vertical=8.dp`, `shadow(elevation=4.dp, RoundedCornerShape(8.dp), clip=false, ambient/spot = onSurface@0.9f alpha)`, shape `RoundedCornerShape(8.dp)`, cardColors container=`background` content=`onBackground`. Inner `Row` padding `16.dp`, `CenterVertically`. (`DownloadsScreen.kt:262-289`)
  - Title: `AutoSubtitleText` `"Ch ${item.number} - ${item.mangaTitle} "`, fontSize/maxSize 16.sp, maxLines 1, `FontWeight.Bold`, color `onBackground`. (`DownloadsScreen.kt:297-304`)
  - Status line under title (4.dp spacer): per `DownloadingState` — RUNNING→`R.string.running` "Running"; QUEUED→`R.string.queued` "Queued"; SUCCESS→`R.string.downloaded` "Downloaded"; FAILED→`R.string.download_failed` ("Failed: %1$s" with `item.errorMsg ?: R.string.unknown`), color `error`, maxLines 2; COMPRESSING→shows "Downloaded" string (maps to downloaded label). (`DownloadsScreen.kt:306-337`)
  - `RunningDownloadItemCard`: `Card` outer padding `horizontal=6.dp, vertical=12.dp` (note: differs from DownloadItemCard's 16/8), same shadow/shape/colors. Inner `Column` padding 12.dp. Header `Row` SpaceBetween: title (weight 1, fontSize 16.sp, minSize 8.sp, maxLines 1, Bold) + `IconButton` `Icons.Default.Cancel` tint `error`. Then 8.dp spacer, then `Box` height 24.dp containing `LinearProgressIndicator` (height 8.dp, CenterStart) + centered `Text` "${progress}%" fontSize 12.sp color `onSurface`. (`DownloadsScreen.kt:379-459`)
  - Progress is **animated**: `animateFloatAsState(targetValue=fraction, tween(300ms))`; fraction = `progress.coerceIn(0,100)/100f`. (`DownloadsScreen.kt:384-388`)
- **States:**
  - loading: paging `loadState.append == Loading` → centered `CircularProgressIndicator` in `Box` fillMaxWidth padding 16.dp (both tab composables). (`DownloadsScreen.kt:177-189, 233-245`)
  - error: `loadState.append == Error` → `Text(R.string.error_loading_more)` "Error loading more items" color `error`, padding 16.dp. (`DownloadsScreen.kt:190-200, 246-256`)
  - empty: **NO explicit empty state** — empty paging list simply renders an empty `LazyColumn`. (INFERRED gap; no empty-state composable present)
  - success: list of cards as above.
- **Interactions:**
  - Tab switch: `onClick { selectedTab = index }`. No swipe between tabs (TabRow only, no HorizontalPager). (`DownloadsScreen.kt:109-114`)
  - Active tab, RUNNING item: cancel icon → `runningChapterCancel(item)` → `cancelRunningDownload(chapterId, mangaId)`. (`DownloadsScreen.kt:163-164, 428`; route `:53-55`)
  - Active tab, non-RUNNING (QUEUED) item: `DownloadItemCard(showCancel=true)`; when `state != RUNNING` shows `TextButton` "Cancel" (text color `error`) → `onCancel(item)` → `onCancelChapterTapped(chapterId)`. When `state == RUNNING` here shows an inert `TextButton` with `"${progress}%"` text color `secondary` (no-op onClick). (`DownloadsScreen.kt:339-348`; route `:56-58`)
  - Failed/Completed: `showRetry` when FAILED → `TextButton` "Retry" → `onRetry(item)` → `downloadChapter(it.toChapterEntity(), api, title)`. (`DownloadsScreen.kt:350-354`; route `:46-52`)
  - SUCCESS item: inert `IconButton` `Icons.Outlined.Done` tint `primary` (no onClick action). (`DownloadsScreen.kt:356-364`)
  - `showDelete` → `IconButton` `Icons.Default.Delete` tint `error` → `onDelete(item)` → `deleteDownload(chapterId)`. (`DownloadsScreen.kt:366-374`; route `:59-61`)
  - `BackHandler { onBack() }` registered. (`DownloadsScreen.kt:74`)
  - Animations: running-card progress tween (300ms). No item enter/exit animations.
  - No long-press, no pull-to-refresh, no run-all button on this screen.
- **Dialogs/sheets/snackbars:** None on DownloadsScreen itself.
- **Forms & validation:** None.
- **Data/behavior:**
  - `DownloadViewModelv2` (Hilt) injects `DownloadRepository` (clean) twice (`repository`, `downloadRepo`). (`DownloadViewModelv2.kt:27-30`)
  - Paged flows: `getDownloadsByState(states)` → `repository.observeDownloadsByStatePaged(states).cachedIn(viewModelScope)`. Active = `[RUNNING, QUEUED]`, Failed = `[FAILED]`, Completed = `[SUCCESS]`. (`DownloadViewModelv2.kt:58-60`; route `:23-39`)
  - LazyColumn item key = `it.chapterId`. (`DownloadsScreen.kt:159, 218`)
  - VM also exposes (not used by this screen but part of the subsystem): `runningChapter`, `isDownloading`, `downloads` (deprecated), `downloadsPaged`, `queuedCount`, `queuedChapterIds`, `networkAvailable` (`Status`, init `Lost`), and actions `downloadChapterNotification`, `downloadChapters`, `cancelDownloads` (cancelAll), `clearDownloads` (clearFailedAndQueued), `cancelRunningDownload`, `onCancelChapterTapped`. (`DownloadViewModelv2.kt:32-119`)
  - `onCancelChapterTapped` → `downloadRepo.onCancel(chapterId)` (updates DB state immediately for instant UI feedback). (`DownloadViewModelv2.kt:101-111`)
  - Repository contract (clean): `observeRunningChapter`, `observeAllDownloads`, `isDownloading`, `queuedCount`, `queuedChapterIds`, `networkStatus`, `enqueueChapterDownload`, `enqueueChaptersDownload`, `deleteDownload`, `onCancel`, `observeAllDownloadsPaged`, `observeDownloadsByStatePaged`, `cancelAllDownloads`, `cancelARunningChapter`, `clearFailedAndQueued`. (`download/domain/clean/DownloadRepository.kt:10-32`)
  - Entity `ChapterDownloadEntity`: id, number(String), chapterId, mangaId, api, mangaTitle?, url, state(`DownloadingState`), progress(0–100 Int), errorMsg?. Room table `chapter_downloads`, unique index on `chapterId`. (`data/local/entity/ChapterDownloadEntity.kt:8-23`)
  - `DownloadingState` enum: QUEUED, RUNNING, SUCCESS, FAILED, COMPRESSING. (`download/data/DownloadingState.kt:3-9`)
- **Feature inventory (EVERY affordance):**
  - 3 tabs (Active/Failed/Completed); default opens on Completed.
  - Running item: animated linear progress bar + % text + Cancel (X) icon.
  - Queued item (Active tab): "Cancel" TextButton.
  - Failed item: status text "Failed: <msg>" (red) + "Retry" TextButton + Delete icon.
  - Completed item: "Downloaded" status + inert green Done check icon + Delete icon.
  - Per-tab paging loading spinner + paging error text.
  - Back button (top bar) + hardware BackHandler.
- **Citations:** `presentation/features/download/ui/screens/DownloadsScreen.kt:1-460`; `navigation/routes/DownloadsScreenRoute.kt:1-65`; `presentation/features/download/ui/test2/DownloadViewModelv2.kt:1-119`; `presentation/features/download/domain/clean/DownloadRepository.kt:1-32`; `presentation/features/download/data/DownloadingState.kt`; `data/local/entity/ChapterDownloadEntity.kt`.

---

### RepoSettingsScreen (Sources Settings)
- **Entry/route:** `RepoSettingsScreenRoute(navController, backStackEntry, repoSettingsViewModel)`. Reads `args.isFirstOpen` from `Screen.RepoSettings`. `onFinish`: sets `PrefsDelegate("first_launch")=false`, navigates to `Screen.Library` popping to graph start (inclusive) + `launchSingleTop`. `onBackPress` = `safePopBackStack`. (`navigation/routes/RepoSettingsScreenRoute.kt:15-43`)
- **Layout & components:** `Scaffold` (Material **m2** `Scaffold` from `androidx.compose.material` — note the screen mixes m2 `Scaffold`/`Icon`/`IconButton` with m3 content) with:
  - `topBar` = `TopAppBarCom(title=R.string.title_sources_settings "Sources Settings", back ArrowBack icon, contentDescription R.string.desc_back, tint onBackground)`. (`RepoSettingsScreen.kt:70-84`)
  - `snackbarHost` = `SnackbarHost(snackbarHostState)`. (`RepoSettingsScreen.kt:85`)
  - `bottomBar` = present **only if `isFirstOpen`**: `Box` padding `horizontal=24.dp, vertical=12.dp` containing full-width `Button` height 50.dp clipped `RoundedCornerShape(26.dp)`, shape `shapes.medium`, container `primary`, text **hardcoded "Finish"** (NOT a string resource here), labelLarge 16.sp color `onPrimary`. (`RepoSettingsScreen.kt:86-112`)
  - `backgroundColor = colorScheme.background`.
  - Body = `LazyColumn` contentPadding=innerPadding, fillMaxSize, padding 16.dp. (`RepoSettingsScreen.kt:114-120`)
  - Item 1: `ItemsGroup { SettingsNavigationItem(title=R.string.request_adding_source "Request Adding Source/Site", desc=R.string.enter_the_url_for_site_you_want_us_to_add, icon=Icons.Outlined.AddCircleOutline) { showSourceDialog=true } }` + 16.dp spacer. (`RepoSettingsScreen.kt:121-132`)
  - Item 2 (info card): `ItemsGroup { SettingsNavigationItem(title=R.string.languages_coming_soon_title "Upcoming Languages", desc=R.string.languages_coming_soon_description, icon=Icons.Outlined.Info, iconColor=error, endIcon=null, maxLines=3) }` + 16.dp spacer. (`RepoSettingsScreen.kt:134-146`)
  - Then per `grouped` language: `ItemsGroup { LanguageToggle(...) ; 8.dp spacer ; LanguageToggleWithAnimation(...) }` + 16.dp spacer. (`RepoSettingsScreen.kt:148-172`)
- **Visual:** `ItemsGroup` = `Column` fillMaxWidth, background `surfaceContainerHigh` (default), `RoundedCornerShape(16.dp)`, padding `horizontal=16.dp, vertical=8.dp`. (`ItemsGroup.kt:17-28`)
  - `SettingsNavigationItem`: `Row` fillMaxWidth, clickable when onClick non-null, padding `vertical=16.dp`. Leading icon 24.dp + 16.dp spacer; title Text 14.sp `onBackground`; desc via `AutoSubtitleText` (12.sp, maxSize 12.sp, minSize 6.sp, `onBackground@0.8f`, ellipsis); trailing `endIcon` default `KeyboardArrowRight` (null for info card). (`SettingsNavigationItem.kt:30-89`)
  - `LanguageToggle` = `SwitchItem(title=language, description, icon, checked, onCheckedChange)`. `SwitchItem`: `Row` padding `vertical=8.dp`; optional icon 24.dp; title 14.sp; desc 12.sp `onBackground@0.5f`; m2 `Switch` (checkedThumb=primary, uncheckedThumb=surfaceVariant, uncheckedTrack=onBackground@0.4f). (`LanguageToggle.kt:10-32`; `SwitchItem.kt:28-73`)
  - `RepoToggleItem` (per-source): `Row` fillMaxWidth padding `vertical=12.dp`. Optional icon 24.dp (tint `Color.Unspecified` when checked to show source's own colored icon, else `onBackground`) + 16.dp spacer; title 14.sp; desc 12.sp `onBackground@0.8f`; m2 `Checkbox` (checked=primary, unchecked=onBackground, checkmark=onPrimary). (`RepoToggleItem.kt:24-73`)
- **States:** No dedicated loading/empty/error UI. `enabledStates` starts as `emptyMap()` and updates as DB emits (sources are seeded at app start). `grouped` is computed synchronously from the in-memory repo set. If no repos → no language groups render (silent empty). (`RepoSettingsViewModel.kt:37-43, 114-115`)
- **Interactions:**
  - Tap "Request Adding Source/Site" row → opens `FeedbackDialog` (`showSourceDialog=true`). (`RepoSettingsScreen.kt:127-129`)
  - Language master toggle (`LanguageToggle`): `onToggleLanguage` → `viewModel.setLanguageEnabled(language, it)` → for every source whose `entity.language == language`, `enableDisAbleSource(name, enabled)`. "all on" indicator = `repos.any { enabledStates[it.API]==true }` (so a single enabled source shows the master switch as ON). (`RepoSettingsScreen.kt:151-160`; `LanguageToggle.kt:19`; `RepoSettingsViewModel.kt:85-95`)
  - `LanguageToggleWithAnimation`: shows the per-source `RepoToggleItem` list **only when `allEnabled`** (`repos.any{enabled}`), wrapped in `AnimatedVisibility(enter=fadeIn()+expandVertically(), exit=fadeOut()+shrinkVertically())`. Each per-source checkbox → `onToggleLanguage(repo.API, bol)` → `setRepoEnabled(api, bol)` → `enableDisAbleSource`. Per-source description = `R.string.enabled`/`R.string.disabled` ("Enabled"/"Disabled"). (`LanguageToggleWithAnimation.kt:18-47`; `RepoSettingsScreen.kt:162-168`; `RepoSettingsViewModel.kt:75-79`)
  - Finish button (first-open only): `onFinish` → navigate to Library. (`RepoSettingsScreen.kt:93-109`)
  - Back button + (m2 Scaffold; no explicit BackHandler here).
  - `language.removeAllParens()` strips "(" and ")" for display (raw LANGUAGE codes are stored like "(EN)"). (`RepoSettingsScreen.kt:154, 214-216`)
- **Dialogs/sheets/snackbars:**
  - `FeedbackDialog(visible=showSourceDialog, selectedType=ComplaintType.SITES_ADD, headerText=R.string.we_will_add_it_as_soon_it_possible, textFieldText=R.string.enter_the_site_url)`. (`RepoSettingsScreen.kt:175-210`)
  - On submit → `complaintViewModel.submit(type, displayName, body, onSuccess, onError)`. onSuccess → snackbar `R.string.request_submitted_successfully` (Short). onError → snackbar `R.string.request_failed` with actionLabel `R.string.retry` (Long). Dialog closes on submit/dismiss. (`RepoSettingsScreen.kt:178-209`)
- **Forms & validation (FeedbackDialog):** `AlertDialog` (m3) RoundedCornerShape(20.dp). Header (headerText + "We'd love to hear from you"). Category `ExposedDropdownMenuBox` (readOnly OutlinedTextField + dropdown of `ComplaintType.entries`); pre-selected `SITES_ADD`. Feedback `OutlinedTextField` minLines 4 maxLines 6, isError when 0<len<5, supportingText shows "Minimum 5 characters required" (error) + "len/500" counter. Social media row + connect text. Submit `Button` enabled only when `selectedType != null && body.length >= 5`. Dismiss `TextButton` "Cancel". (`FeedbackDialog.kt:33-231`)
- **Data/behavior:**
  - `RepoSettingsViewModel` (Hilt): injects `Context`, `SourcesRepository`, `DataStoreHelper`. `repoList = sourcesRepository.repoTaps.sortedBy{PRIORITY}`. `enabledStates: StateFlow<Map<String,Boolean>>` = `sourcesRepository.enabledStates` stateIn(Lazily, emptyMap). `newSources` from `ds.newSourcesFlow`. `enabledRepositoriesFlow` (enabled→repo). (`RepoSettingsViewModel.kt:28-64`)
  - `groupedByLanguage(): Map<String, List<BaseMangaRepository>>` = `repoList.groupBy { it.LANGUAGE }`. (`RepoSettingsViewModel.kt:114-115`)
  - `SourcesRepository` seeds all sources into Room (`SourcesEntity`) at construction via `saveSources()` on `applicationScope`, each inserted with `isEnabled=false`. (`SourcesRepository.kt:46-52, 189-217`)
  - Source enabled-state is persisted in Room `SourcesEntity` (name, priority, isEnabled, language, baseUrl, baseVersion, imageBaseUrl, imageUrlVersion). `enableDisAbleSource(name, enabled)` → `sourcesDao.setEnabledByName`. (`SourcesRepository.kt:166-175`)
  - `enabledStates` flow = `allSources.map { associate { name to isEnabled } }`. Active repo selection via `activeIndex` pref ("active_tab"). `SourceState` enum (WORKING, UNDER_MAINTENANCE, STOPPED, ADULT_18_PLUS) tracked per source; not surfaced in this screen's UI. (`SourcesRepository.kt:54-164`; `SourceState.kt`)
  - `BaseMangaRepository` abstract fields used: `API`, `LANGUAGE`, `ICON`(@DrawableRes Int → `ImageVector.vectorResource`), `PRIORITY`, `BASE_URL`, `URL_VERSION`, `imgBaseUrl`, `imgUrlVersion`. (`sources_repositry/BaseMangaRepository.kt:15-25`)
  - **No source-tab UI on this screen.** Source "tabs" (active-repo selection) live elsewhere (Home/Search via `SourcesTabs.kt`); `activeIndexFlow`/`updateActiveIndex` belong to `SourcesRepository` but are not used in RepoSettings/Sources screens. (INFERRED — task mentions "source tabs"; they are not part of this cluster's UI.)
- **Feature inventory:**
  - Request-adding-source row (opens feedback dialog).
  - Upcoming-Languages info card (inert; Info icon red, 3-line desc with flags/emoji).
  - Per-language master toggle switch.
  - Animated per-source checkbox list (expand/collapse with master toggle).
  - Per-source colored icons + "Enabled"/"Disabled" captions.
  - Finish button (only when `isFirstOpen`).
  - Snackbar feedback on request submit/fail (with Retry action on fail).
- **Citations:** `presentation/features/repo_settings/ui/screens/RepoSettingsScreen.kt:1-216`; `navigation/routes/RepoSettingsScreenRoute.kt:1-43`; `presentation/features/repo_settings/ui/viewmodel/RepoSettingsViewModel.kt:1-127`; `presentation/features/repo_settings/domain/SourcesRepository.kt:1-223`; `presentation/features/repo_settings/ui/components/LanguageToggle.kt`; `presentation/common/componants/sources/LanguageToggleWithAnimation.kt`; `presentation/features/repo_settings/ui/components/RepoToggleItem.kt`.

---

### SourcesScreen (Onboarding Sources)
- **Entry/route:** `SourcesScreenRoute(navController, backStackEntry, repoSettingsViewModel)`. `onFinish`: `PrefsDelegate("first_launch")=false`, navigate `Screen.Library` popping graph start (inclusive) + `launchSingleTop`. (`navigation/routes/SourcesScreenRoute.kt:14-37`)
- **Layout & components:** `Scaffold` (m3) fillMaxSize, `snackbarHost`, `containerColor=background`. Body = `Box` fillMaxSize stacking:
  1. `AnimatedBackground()` (onboarding welcome animated bg). (`SourcesScreen.kt:88`)
  2. Gradient overlay `Brush.verticalGradient(background@0.1f → @0.3f → solid background)`. (`SourcesScreen.kt:91-103`)
  3. `Column` fillMaxSize padding 24.dp, `Arrangement.SpaceBetween`, `CenterHorizontally`:
     - Title `Text(R.string.select_your_manga_sources "Select Your Manga Sources", headlineMedium 24.sp color primary)`. (`SourcesScreen.kt:112-118`)
     - 16.dp spacer.
     - `LazyColumn` weight(1f) with the **same** content blocks as RepoSettings: Request-source row, Upcoming-languages info card, and per-language toggle groups — BUT `ItemsGroup(color = surfaceContainerHigh@0.4f)` (translucent over the animated bg). Language label here uses `getLanguageName(language.removeAllParens().lowercase())` → localized display name (e.g. "English") instead of the raw "(EN)". (`SourcesScreen.kt:122-182`)
     - `FeedbackDialog` (same config as RepoSettings). (`SourcesScreen.kt:184-219`)
     - Bottom full-width `Button` "Finish" (`R.string.finish` — string resource here, unlike RepoSettings' hardcoded "Finish"), height 50.dp, RoundedCornerShape(26.dp), shape `shapes.medium`, container primary, labelLarge 16.sp onPrimary → `onFinish`. (`SourcesScreen.kt:221-237`)
- **Visual:** identical toggle/info components to RepoSettings but with translucent `ItemsGroup` background, animated background + gradient, centered headline title.
- **States:** Same as RepoSettings — no explicit loading/empty/error. Auto-seed effect runs on enter (below).
- **Interactions:**
  - `LaunchedEffect(Unit)` → `repoSettingsViewModel.setLanguageEnabledDefault("(${languageCode.uppercase(ROOT)})", true)` — **auto-enables sources for the device's current language on first display**, falling back to "(EN)" if no sources exist for that language. (`SourcesScreen.kt:77-79`; `RepoSettingsViewModel.kt:97-111`)
  - Same language master toggle + animated per-source checkbox + request-source dialog interactions as RepoSettings.
  - Finish button → onFinish (navigate to Library, clears first-launch).
  - No back button (onboarding terminal step).
- **Dialogs/sheets/snackbars:** Same `FeedbackDialog` + request submit/fail snackbars as RepoSettings. (`SourcesScreen.kt:184-219`)
- **Forms & validation:** Same FeedbackDialog (≥5 char body, category required).
- **Data/behavior:**
  - `@SuppressLint("LocalContextConfigurationRead")`; reads `configuration.locales[0].language` for the auto-seed default. (`SourcesScreen.kt:59, 66-73`)
  - `getLanguageName(code, inLocale)`: `Locale(code).getDisplayLanguage(...)` capitalized; falls back to raw code on exception. (`SourcesScreen.kt:243-254`)
  - `setLanguageEnabledDefault`: reads `allSources.first()`, filters by language; **if empty falls back to language=="(EN)"**, enables each. (`RepoSettingsViewModel.kt:97-111`)
  - Same `RepoSettingsViewModel` / `SourcesRepository` backing as RepoSettings (shared VM instance passed in).
- **Feature inventory:**
  - Animated onboarding background + gradient overlay.
  - Centered "Select Your Manga Sources" headline.
  - Request-adding-source row + feedback dialog.
  - Upcoming-Languages info card.
  - Per-language master toggle + animated per-source checkboxes (localized language names).
  - Auto-seed default language sources on entry.
  - Finish button → Library.
- **Citations:** `presentation/features/onboarding/sources/SourcesScreen.kt:1-254`; `navigation/routes/SourcesScreenRoute.kt:1-37`; `RepoSettingsViewModel.kt:97-111`.

---

### CbzConversionDialog (download-adjacent)
- **Entry/route:** Not a screen; a `Dialog` composable invoked from Settings (`CbzConversionViewModel`, see commented-out `DownloadSettingsSection.kt`). Driven by `ConversionProgress` (`core.cbz.ConversionProgress`). (`download/ui/components/CbzConversionDialog.kt:23-27`)
- **Layout & components:** `Dialog` → `Card` fillMaxWidth padding 16.dp RoundedCornerShape(16.dp) container `surface`. Inner `Column` padding 24.dp centered. Returns early (renders nothing) unless converting OR error OR successMessage present. (`CbzConversionDialog.kt:28-60`)
- **Visual:** Three mutually-exclusive states inside `when`:
  - Error: `Icons.Default.Error` 48.dp tint error; title `R.string.conversion_failed` bold error; error text bodyMedium center; "Close" Button (`R.string.close`). (`CbzConversionDialog.kt:62-91`)
  - Success: `Icons.Default.CheckCircle` (or `Warning` if `wasStopped`) 48.dp; title `R.string.conversion_complete_` / `R.string.conversion_stopped`; successMessage text; "Done" Button (`R.string.closure_reason_done`). (`CbzConversionDialog.kt:93-133`)
  - Converting: `Icons.Default.Warning` primary; title `R.string.converting_to_cbz`; warning "please don't close the app..." (error color); `LinearProgressIndicator(progress = converted/total)` height 8.dp trackColor surfaceVariant; rows "Completed: x/total" + "Remaining: n"; current manga title + chapter; `CircularProgressIndicator` 32.dp; `OutlinedButton` "Stop conversion" (`R.string.stop_conversion`, error content). (`CbzConversionDialog.kt:135-266`)
- **States:** converting / success / success-stopped / error (explicit four-way render). Dismiss blocked while `isConverting` (`dismissOnBackPress`/`dismissOnClickOutside` = `!isConverting`). (`CbzConversionDialog.kt:34-44`)
- **Interactions:** Close/Done → `onDismiss`; Stop → `onDismiss` (VM stops conversion).
- **Dialogs/sheets/snackbars:** is itself a dialog.
- **Forms & validation:** None.
- **Data/behavior:** `ConversionProgress` fields used: `isConverting`, `error`, `successMessage`, `wasStopped`, `totalChapters`, `convertedChapters`, `currentMangaTitle`, `currentChapterNumber`. (`CbzConversionDialog.kt`)
- **Feature inventory:** progress dialog for bulk CBZ conversion of existing downloads (3-state). The standalone `DownloadSettingsSection.kt` that would have hosted the toggles + "Start Conversion" button is **fully commented out** (dead). (`download/ui/components/DownloadSettingsSection.kt:1-131`)
- **Citations:** `presentation/features/settings/ui/components/CbzConversionDialog.kt:1-271`; `presentation/features/download/ui/components/DownloadSettingsSection.kt:1-131`.

---

### Cluster notes

**Run-all / bulk download:** No "Run all" / "Download all queued" / "Pause all" affordance exists on `DownloadsScreen`. The VM exposes `cancelDownloads()` (cancel-all → `cancelAllDownloads`) and `clearDownloads()` (`clearFailedAndQueued`), but **neither is wired to any button** on the Downloads screen. (`DownloadViewModelv2.kt:88-94`; absent in `DownloadsScreen.kt`) — record as a gap if KMP added a run-all/clear button.

**Two DownloadRepository interfaces exist** in the OLD tree: the active clean one at `download/domain/clean/DownloadRepository.kt` (used by `DownloadViewModelv2`) and a legacy `download/domain/DownloadRepository.kt` (`DownloadRepositoryImpl.kt` alongside). The screen uses the `clean` package. `DownloadException.kt`, `DownloadState.kt`, `DownloadRequest.kt` are data/domain types (sealed `DownloadState` with InProgress/Compressing/Complete/Error; sealed `DownloadRequest` Chapter/Notification) not directly referenced by the UI cluster.

**Default tab quirk:** Downloads opens on **Completed (index 2)**, not Active. Easy to miss in a 1:1 port. (`DownloadsScreen.kt:76`)

**Inert affordances (visual-only, no action):** SUCCESS Done check icon (`onClick={}`), and the RUNNING-state branch inside `DownloadItemCard.showCancel` rendering "%"-text TextButton with empty onClick. (`DownloadsScreen.kt:344-348, 356-364`)

**RUNNING handled in two places:** Active tab routes RUNNING items to `RunningDownloadItemCard`; the RUNNING branch inside `DownloadItemCard` is therefore effectively unreachable from the Active tab list (`DownloadItemCard` only gets non-RUNNING items there). (`DownloadsScreen.kt:163-171`)

**Hardcoded string:** RepoSettings "Finish" button text is a literal `"Finish"` (`RepoSettingsScreen.kt:103`), whereas SourcesScreen uses `R.string.finish` (`SourcesScreen.kt:231`). Both resolve to "Finish" but the RepoSettings one is not localized.

**Material version mixing:** RepoSettingsScreen uses `androidx.compose.material` (m2) `Scaffold`/`Icon`/`IconButton`/`Switch`/`Checkbox` while body uses m3 components. SourcesScreen uses m3 `Scaffold`. `RepoToggleItem`/`SwitchItem` use m2 `Checkbox`/`Switch`. Port should reconcile to m3.

**Missing/absent assets & states:**
- No empty-state composable for any Downloads tab (empty paging list renders blank).
- No empty/loading/error state for the Sources lists.
- Source icons are `@DrawableRes` vector resources resolved via `repo.ICON` → `ImageVector.vectorResource(repo.ICON)`; these live in `res/drawable` per-source and must exist for parity (RepoIconResolver handles this in KMP). (`LanguageToggleWithAnimation.kt:40`)
- `languages_coming_soon_description` contains emoji/flags and multi-line whitespace — verbatim match matters for parity. (`values/strings.xml:608-612`)

**Source-state (SourceState) not surfaced here:** WORKING/UNDER_MAINTENANCE/STOPPED/ADULT_18_PLUS is read by Home/Search/maintenance surfaces, not by the Sources Settings cluster. Out of scope for these screens but part of the broader Sources subsystem. (`SourceState.kt`; `SourcesRepository.kt:54-72`)

**"all enabled" semantics (INFERRED intent vs. code):** `LanguageToggle`/`LanguageToggleWithAnimation` compute master-on as `repos.any { enabled }` (comment says "all on if every repo on" but the code uses `any`, not `all`). A single enabled source flips the master switch ON and reveals the per-source list. Match the `any` behavior, not the comment. (`LanguageToggle.kt:19`; `LanguageToggleWithAnimation.kt:23`)

**String resources confirmed present (values/strings.xml):** downloads(257), active(314), failed(315), completed(316), running(317), queued(318), downloaded(319), download_failed(322), unknown(324), cancel(397), retry(221), error_loading_more(660), title_sources_settings(238), request_adding_source(271), enter_the_url_for_site_you_want_us_to_add(272), languages_coming_soon_title(607), languages_coming_soon_description(608), enable_disable_all_sources(239), enabled(240), disabled(241), finish(284), select_your_manga_sources(285), we_will_add_it_as_soon_it_possible(273), enter_the_site_url(274), request_submitted_successfully(647), request_failed(648).
