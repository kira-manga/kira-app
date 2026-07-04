# OLD Native Android App — LIBRARY Feature Cluster Audit

Scope: `presentation/features/library/` (grid) + `presentation/features/library_details/` (per-manga chapter list) + `navigation/routes/LibraryRoute.kt` + `navigation/routes/LibraryMangaRoute.kt`. Package root in source is `me.manga.kira.presentation.features.library...` (the directory path uses `me/manga/yami` but the package declaration is `me.manga.kira`).

This cluster has TWO user-visible screens:
1. **LibraryScreen** — the saved-manga grid (a bottom-nav tab).
2. **LibraryMangaScreen** — per-manga chapter list / details for a saved manga (navigated to on card click).

---

### LibraryScreen
- **Entry/route:** `LibraryRoute` (`navigation/routes/LibraryRoute.kt:49`). A bottom-nav destination. Reads no nav-args. Obtains `LibraryViewModel` via `hiltViewModel(backStackEntry)` (scoped to the back-stack entry, `LibraryRoute.kt:60`), plus `RefreshViewModel` (`:61`), and is passed `DownloadViewModelv2` + `WhatsNewViewModel`. On click of a card it calls `onLibraryMangaClick(Long)` (the manga id) which navigates to `Screen.LibraryMangaDetails`. The screen body is `LibraryScreen(...)` (`library/ui/screens/LibraryScreen.kt:51`).
- **Layout & components:** `Scaffold` (`LibraryScreen.kt:113`) with:
  - **Top bar (default):** `TopAppBarCom` titled `R.string.title_library` ("Library", `LibraryScreen.kt:138`). Action icons in order: Search (`Icons.Default.Search`, `:140`), an **animated download indicator** (`AnimatedPreloader` Lottie, only shown when `isDownloading`, tappable → navigates to `Screen.DownloadsScreen`, `:143-150`), Filter (`Icons.Default.FilterList`, opens bottom sheet, `:151-156`), and overflow (`Icons.Default.MoreVert`, `:157-159`). Overflow `DropdownMenu` has two items: "Refresh" (`R.string.dropdown_button_refresh`, `:164`) and "Open Random Manga" (`R.string.dropdown_button_open_random_manga`, `:171`).
  - **Top bar (search mode):** when Search tapped, top bar swaps to `SearchAppBar` (`:119`) bound to `searchQuery`/`viewModel::onSearchChanged`. `BackHandler` exits search mode and clears the query (`:106-112`).
  - **Body:** `LibraryItems(...)` (`LibraryScreen.kt:195`, defined `LibraryItems.kt:60`). Composed of: optional category `TabRow`, a "last updated / item count" header `Row`, and the manga grid.
  - **Bottom sheet:** `CustomFilterBottomSheet` (`LibraryScreen.kt:227`) — 3 tabs (Filter / Sort / Display).
- **Visual:** 
  - Grid: `LazyVerticalGrid` wrapped in `VerticalGridFastScroller` (`LibraryItems.kt:188-205`); `contentPadding = 8.dp`, columns = `GridCells.Fixed(itemsPerRow)` if `itemsPerRow>0` else `GridCells.Adaptive(minSizeDp)` where `minSizeDp = (screenWidthDp/itemsPerRow).dp` or fallback `140.dp` (`:184-186`). Fast-scroller `thumbColor = colorScheme.primary`.
  - Header row: `padding(horizontal=16.dp, vertical=8.dp)`, `SpaceBetween`. "Last updated" text = `labelSmall` 12sp Medium **Italic** `onSurfaceVariant` (`LibraryItems.kt:147-152`). Count text = `labelSmall` 12sp Medium `onSurfaceVariant` (`:161-165`).
  - Category `TabRow`: `containerColor = Transparent`, `contentColor = primary`; tab labels via `AutoSubtitleText` 14sp `primary` (`LibraryItems.kt:107-130`).
  - See **LibraryMangaCard** sub-section below for card visuals.
- **States:** Driven by `uiState.items: State<List<MangaDisplayItem>>` (`LibraryItems.kt:169`):
  - **Loading** → `LoadingScreen()` (`:170`).
  - **Error** → centered `Snackbar` with the error message (`:171-173`).
  - **Success + empty** → `EmptyLibraryPlaceholder(libraryName)` (`:178`), where `libraryName` reflects the selected tab ("Watching Now" / "Likes" / "Library").
  - **Success + non-empty** → grid.
  - Separately, `uiState.errorMessage` (nullable) renders a bottom-center `Snackbar` overlay regardless of `items` state (`LibraryItems.kt:242-244`).
- **Interactions:**
  - **Pull-to-refresh:** `rememberPullRefreshState` + `pullRefresh` modifier + `PullRefreshIndicator` top-center (`LibraryScreen.kt:185`, `LibraryItems.kt:101,236`). On refresh → `onRefreshClick(uiState.items)` → `onRefreshLibrary` → `refreshViewModel.refreshLibrary()` (only if library non-empty, else Toast "No manga in your library yet!", `LibraryRoute.kt:183-197`). `isRefreshing` comes from `refreshViewModel.isWorkRunning` (`LibraryRoute.kt:64`).
  - **Card tap** → `onMangaClick(manga.id)` → navigate to details.
  - **Card per-item action buttons** (watch-later / like / delete) — see card section.
  - **Category tabs** → `tabIndex` local state + `onTabChanged(tab)` (`LibraryItems.kt:115-117`).
  - **Search** → live filter as you type (`onSearchChanged`); `onSearch` (IME action) clears query if non-blank (`LibraryScreen.kt:126-132` — note: appears to clear rather than commit).
  - **WhatsNew auto-navigation:** `LaunchedEffect` in route navigates to `Screen.WhatsNewScreen(true)` when `shouldShowWhatsNew && !isLoading` (guarded by `hasNavigatedToWhatsNew`, `LibraryRoute.kt:77-91`). (This is the app's "show what's new on launch" trigger, hosted on the Library tab.)
  - No card animations beyond Coil image load; download indicator is a Lottie loop.
- **Dialogs/sheets/snackbars:**
  - **Delete-confirmation `AlertDialog`** (hosted in `LibraryRoute.kt:94-154`): shown when a card's delete button sets `mangaToDelete`/`showDeleteDialog`. Rounded 16.dp, side padding 18.dp; Delete icon tinted `error`; title `R.string.delete_manga` ("Delete Manga?") `headlineSmall` Bold; body `R.string.are_you_sure_you_want_to_remove_this_manga...` (long warning about permanent deletion of progress/read-status/bookmarks/downloads); confirm "Delete" (`error`, Bold) → `vm.removeManga(it)`; dismiss "Cancel" (Medium).
  - **CustomFilterBottomSheet** (Filter/Sort/Display) — see Forms below.
  - Error/empty Snackbars as in States.
- **Forms & validation:** `CustomFilterBottomSheet` (`LibraryScreen.kt:227`, component `CustomFilterBottomSheet.kt:207`): `ModalBottomSheet`, rounded top 24.dp, `tonalElevation=8.dp`, `containerColor = surfaceContainerHigh`, **no drag handle**, with a `TabRow` (3 tabs) over dynamic `pageContents`:
  - **Filter tab:** `FilterChipsRow` over `LibraryViewModel.FilterType.entries` = ALL, DOWNLOADED, UNREAD, STARTED, BOOKMARKED, COMPLETED (single-select `FilterChip` with Done leading icon when selected; `FilterChipsRow.kt`). → `viewModel.onFilterChanged` (persisted).
  - **Sort tab:** `SortOptionsSection` over `SortType.entries` = ALPHABETIC, TOTAL_CHAPTERS, LAST_READ, UNREAD_COUNT, DATE_ADDED, RANDOM. Has a header ("Sort Options"), a direction toggle row (`SwapVert` icon + ascending/descending label + `Switch`), and a `FlowRow` of single-select chips (`SortOptionsSection.kt:30-105`). → `onSortChanged` / `onSortDirectionChanged` (both persisted).
  - **Display tab:** `DisplayOptionsSection` (`DisplayOptionsSection.kt:23`): a `Slider` for items-per-row (range 0..8, 7 steps; 0 = "Auto") with label "Items per row:" and a value caption ("N item(s)" or italic "Auto"); then 5 `SwitchItem` toggles separated by `Divider`s: **Show Items Details**, **Show Items Source**, **Show Items Count**, **Show Buttons**, **Show Tabs (All,likes,Etc.)** (`LibraryScreen.kt:266-312`). Each → the matching `onToggle*` persisted setter.
  - No text validation; all inputs are selection/toggle/slider.
- **Data/behavior:**
  - `LibraryViewModel` (`library/ui/viewmodel/LibraryViewModel.kt:41`, `@HiltViewModel`). Deps: `LibraryRepository`, `MangaRepository`, `SharedPrefsHelper` (prefs), `SettingsRepository`, `SourcesRepository`.
  - **UiState** (`:50-67`) holds: isRefreshing, items, errorMessage, lastUpdated, filter, sort, tabs, ascending, showDetails, showButtons, showTabs, showSource, showCount, randomSeed, itemsPerRow(default 2), searchQuery.
  - **Persistence (SharedPrefs):** last-updated, items-per-row, show-details/buttons/tabs/source/count, sort-asc, filter, sort, random-seed — all loaded in `init` (`:143-163`) and written on each change.
  - **Reactive list pipeline** (`init`, `:169-241`): `combine` of `libraryRepository.getDisplayItemsFlow()`, filter, sort, ascending, `settingsRepository.downloadedOnlyFlow`, searchQuery, randomSeed → `mapLatest` does filter → tab-filter + title `contains(searchQuery, ignoreCase=true)` → sort → reverse if descending (RANDOM ignores ascending). Runs on `Dispatchers.Default`, emits `State.Success`, catches errors into `errorMessage`.
  - **Filter semantics** (`:195-202`): DOWNLOADED `downloadedCount>0`; UNREAD `total-read>0`; STARTED `read>0`; BOOKMARKED `bookmarkedCount>0`; COMPLETED `total>0 && read==total`. A global `downloadedOnly` setting overrides to `downloadedCount>0`.
  - **Sort semantics** (`:214-229`): ALPHABETIC (title lowercase), TOTAL_CHAPTERS, LAST_READ (`manga.lastOpenTimestamp`), UNREAD_COUNT (`total-read`), DATE_ADDED (`savedTimestamp`), RANDOM (`shuffled(Random(seed))`, seed persisted, regenerated each time RANDOM is re-selected `:284-291`).
  - **Tabs** (`FilterTabs`, `:88-96`): NAN (=All), WATCHING_NOW (`manga.isWatchingNow`), LIKED (`manga.isLiked`).
  - **Mutations:** `toggleLiked` / `toggleWatchingNow` → `libraryRepository.updateManga(copy(...))`; `removeManga(id)` → `mangaRepository.removeMangaById(id)`.
  - **Image requests:** `buildItemsImageRequest(ctx,url,api)` → `sourcesRepository.getRepoByName(api).buildItemsImageRequest(ctx,url,0)` (per-source request builder; the grid uses this "items" variant).
  - `lastUpdated` exposed as a separate `StateFlow` from the `KEY_LAST_UPDATED` pref flow (`:258-270`).
  - Also exposes `darkMode`/`followSystem` StateFlows (theme, not used by the grid directly).
- **Feature inventory (every affordance):**
  1. Search toggle + live search-by-title.
  2. Animated download-progress indicator → opens Downloads screen (only while downloading).
  3. Filter bottom sheet (Filter/Sort/Display tabs).
  4. Overflow → Refresh library.
  5. Overflow → Open Random Manga.
  6. Pull-to-refresh.
  7. Category tabs: All / Watching Now / Likes (only when Show Tabs on).
  8. "Last updated: X ago" caption.
  9. "N items" count caption (only when Show Count on).
  10. Manga card tap → details.
  11. Per-card Watch-Later toggle (only when Show Buttons on).
  12. Per-card Like toggle (only when Show Buttons on).
  13. Per-card Delete → confirmation dialog (only when Show Buttons on).
  14. Filter selection (6 filters).
  15. Sort selection (6 sorts) + ascending/descending switch.
  16. Items-per-row slider (0=Auto..8).
  17. Display toggles: Show Details / Show Source / Show Count / Show Buttons / Show Tabs.
  18. Empty placeholder per tab.
  19. WhatsNew auto-launch (hosted here).
- **Citations:** `LibraryScreen.kt:49-321`, `LibraryItems.kt:60-246`, `LibraryViewModel.kt:41-455`, `LibraryRoute.kt:49-197`, `CustomFilterBottomSheet.kt:205-251`, `DisplayOptionsSection.kt:23-70`, `FilterChipsRow.kt:26-91`, `SortOptionsSection.kt:30-105`, `EmptyLibraryPlaceholder.kt:26-51`, `AnimatedPreloader.kt:20-57`.

---

### LibraryMangaCard (grid cell)
- **Entry/route:** Rendered per item in the `LazyVerticalGrid` (`LibraryItems.kt:213`), one per `MangaDisplayItem`. Defined in `MangaCard.kt:71`.
- **Layout & components:** `Card` (rounded 8.dp, elevation 2.dp, `aspectRatio 1f/1.5f`, `widthIn 120.dp`, `padding 8.dp`, `MangaCard.kt:118-127`) containing a `BoxWithConstraints`:
  - **Cover image:** `AsyncImage` (`matchParentSize`, `ContentScale.Crop`) using a per-source `ImageRequest` from `buildImageRequest` and `getImageLoader()`; placeholder = `onSurface 12%` ColorPainter, error = `error 24%` ColorPainter (`MangaCard.kt:87-93, 146-158`).
  - **Source badge** (top-start, when `showSource`): a small `Card` with rounded 4.dp, container = source brand color (`api.COLORS`) at 80% alpha; text via `AutoSubtitleText` 8sp Bold; color black/white based on `bgColor.isDark()`; text = `R.string.source_badge_format` = `"<api> - <language>"` (`MangaCard.kt:94, 110-113, 172-193`).
  - **Action button column** (top-end, when `showButtons`, `MangaCard.kt:199-282`): three circular (rounded 50%) icon buttons stacked, sizes computed adaptively from card width (`buttonSize = 22% of width clamped 4..40.dp`, `iconSize = 55% of button`):
    - **Watch-Later:** `surface 80%` bg; icon `WatchLater` (filled) if `isWatchingNow` else `Schedule`; tint `primary`.
    - **Like:** `surface 80%` bg; icon `Favorite` if `isLiked` else `FavoriteBorder`; tint **Red**.
    - **Delete:** `errorContainer 80%` bg; icon `Delete`; tint **White**.
  - **Bottom overlay** (bottom-start, full width): vertical gradient Transparent→Black 80%; contains title `AutoSubtitleText` (White, Bold, maxLines 2, 14sp, ellipsis) and — when `showDetails` — a `Row` of four `IconWithCount` (`MangaCard.kt:286-330`).
  - **IconWithCount badges (when showDetails):** four equally-weighted columns, each `Icon + count` (White), adaptive sizing (`IconWithCount.kt`): 
    - `Icons.Outlined.List` → `totalChapters`
    - `Icons.Outlined.RemoveRedEye` → `readCount`
    - `Icons.Outlined.Download` → `downloadedCount`
    - `Icons.Outlined.BookmarkAdd` → `bookmarkedCount`
- **Visual:** Card 8.dp rounded, 2.dp elevation; 1:1.5 portrait aspect ratio. Title 14sp Bold white over dark gradient. Badge 8sp. All icon/text sizing is adaptive to measured card width (`BoxWithConstraints`).
- **States:** Image: placeholder (loading) / error painters as above. No per-card loading/empty/error text states — purely image fallback colors.
- **Interactions:** Card `clickable` → `onMangaClick(id)` (`:124`). Each action button `clickable` launches a coroutine → `onToggleWatchLater(manga)` / `onToggleLike(manga)` / `onToggleDelete(manga.id)` (`:218-269`). Delete routes up to the confirmation dialog in `LibraryRoute`.
- **Dialogs/sheets/snackbars:** Delete button triggers the route-level delete dialog.
- **Forms & validation:** none.
- **Data/behavior:** `imageRequest` built in `produceState` keyed on `imageUrl`+`api` (`:96-106`). Brand color via `api.COLORS` extension (from `sources_repositry.data`).
- **Feature inventory:** cover image; source+language badge; watch-later/like/delete buttons; title; 4 count badges (chapters/read/downloaded/bookmarked).
- **Citations:** `MangaCard.kt:71-334` (+ preview composables `:336-420`); `IconWithCount.kt:23-71`. Brand color: `MangaCard.kt:63-64,110`.

---

### LibraryMangaScreen (per-manga chapter list / details)
- **Entry/route:** `LibraryMangaRoute` (`navigation/routes/LibraryMangaRoute.kt:40`). Reads `Screen.LibraryMangaDetails.mangaId` from nav args (`:54-59`), calls `viewModel.loadMangaDetails(id)` and `adViewModel.preloadAds()`. Uses `LibraryDetailsViewModel` (`hiltViewModel()`), `HomeViewModel`, `DownloadViewModelv2`, `AdViewModel`. While `manga.id == 0L` it shows `LoadingScreen()` and returns (`:105-108`). Body = `LibraryMangaScreen(...)` (`library_details/ui/screens/LibraryMangaScreen.kt:67`).
- **Layout & components:** `Scaffold` (`LibraryMangaScreen.kt:210`):
  - **Top bar:** `MangaTopAppBar` (`MangaTopAppBar.kt:30`) — back arrow; title = manga.title (20sp Normal); transparent background; actions: a **Stop** icon (tint `error`) shown only while `isDownloadingAll` → `cancelAllDownloads`; **Filter** (`FilterList`) → open sheet; **overflow** with: "Delete all downloaded chapters", "Refresh", "Share" (Share is inert — empty onClick, `MangaTopAppBar.kt:91-96`).
  - **FAB:** `AnimatedCircleExtendedFab` (`LibraryMangaScreen.kt:225`) — `PlayArrow` icon; text "Resume {chapter#}" if there's a first-unread chapter, else "You finished this manga"; expands/collapses on scroll direction (`:153-175`); click → `onChapterClick(firstUnread,...)`. (FAB text strings are **hardcoded English literals**, not stringResource.)
  - **Background:** `ImageWithGradientOverlay` (blurred parallax cover header, height 250.dp, blur 14.dp, `:253-259`).
  - **Body:** `VerticalFastScroller` → `LazyColumn` (`:261-352`):
    - item 0: `LibraryHeaderSection` (cover + title + info + size + banner ad + genres/description + actions row + download menu).
    - item 1: a `Row` with "N Chapters" (`chapters_count_format`) + a sort-direction `IconButton` (`KeyboardDoubleArrowDown`/`Up`).
    - items: `LibraryChapterItem` per chapter.
  - **Selection action bar:** `ChapterSelectionActionsRow` overlaid when in multi-select mode (`:353-383`).
  - **Bottom sheet:** `CustomFilterBottomSheet` with **2 tabs** here (Filter + Sort; Display tab commented out, `:386-460`).
  - **Snackbar host** + **PullRefreshIndicator** (custom colors `inverseSurface`/`background`).
- **Visual:** Blurred parallax cover backdrop. Chapter rows = `Card` with custom `shadow` (4.dp, ambient/spot `onSurface 90%`), rounded 8.dp, container `background` (`LibraryChapterItem.kt:353-378`). "N Chapters" label Bold. NEW badge = red `Card` rounded 4.dp, white "NEW" `labelSmall` Bold, top-end (`LibraryChapterItem.kt:621-640`).
- **States:**
  - **Loading:** route shows `LoadingScreen()` until manga loaded (`LibraryMangaRoute.kt:105-108`); pull-refresh spinner during chapter refresh (`isRefreshing`).
  - **Empty:** no dedicated empty-chapters placeholder (just "0 Chapters" + empty list). (INFERRED — no empty state composable present.)
  - **Error:** site-status 403 → opens a WebView dialog (`showWebViewDalog`, `LibraryMangaRoute.kt:86-98`); generic `_error` is collected in VM but not surfaced as a UI element here beyond logs. No-internet on download attempts → snackbar (`:128-138`).
  - **Success:** header + chapter list.
- **Interactions:**
  - **Chapter tap** → `onChapterClick` → marks chapter not-new, updates last-open, opens reader (`LibraryMangaRoute.kt:118-125`).
  - **Chapter long-press** → enters multi-select (`showChaptersCheckBox`, `LibraryMangaScreen.kt:344-346`); `BackHandler` exits multi-select first (`:178-184`).
  - **Per-chapter:** read toggle (`RemoveRedEye`), download button (`Download`/`DownloadDone`, disabled if downloaded) OR a **circular progress + cancel dropdown** while downloading/queued/compressing (`LibraryChapterItem.kt:465-619`), bookmark toggle (`BookmarkBorder`/`BookmarkRemove`). Compressing state shows `AnimatedCompressing` Lottie.
  - **Sort direction** icon button (`:311-318`) → `toggleSort()`.
  - **Pull-to-refresh** → `onRefreshClick` → `viewModel.refreshChapters()` (fetches new chapters from source, inserts new ones flagged `isNew`, `LibraryDetailsViewModel.kt:246-302`).
  - **FAB Resume** → first unread.
  - **Download-all** / **custom download** / **bookmark-all** / **mark-all-read** / **delete-all-downloaded** via header actions, download menu, top-bar overflow, and the selection action bar.
- **Dialogs/sheets/snackbars:**
  - `ConfirmDialogClean` for remove-bookmark (`R.string.remove_bookmark_title/message`) and add-to-library (`R.string.add_library_title/message`) (`LibraryMangaScreen.kt:185-208`).
  - WebView dialog on 403.
  - No-internet snackbars (`R.string.no_internet_connection...`).
  - `CustomFilterBottomSheet` (Filter/Sort).
  - `DownloadMenu` dropdown (Download all / Custom download|cancel selection|download N selected, `DownloadMenu.kt:14-54`).
- **Forms & validation:**
  - **Filter chips** over `LibraryDetailsViewModel.FilterType` = ALL, DOWNLOADED, UNREAD, READED, BOOKMARKED (`LibraryDetailsViewModel.kt:132-144`).
  - **Sort chips** over `SortType` = ID, NUMBER, DATE, LAST_READ_DATE (`:145-156`); **note: SortType display names are hardcoded English literals** "Id"/"Number"/"Date"/"Last Read Date" (`:150-154`), not localized.
  - Direction switch toggles ascending. No text inputs.
- **Data/behavior:** `LibraryDetailsViewModel` (`library_details/ui/viewmodel/LibraryDetailsViewModel.kt:40`). Deps: `LibraryRepository`, `SourcesRepository`.
  - `chapters` = combine(mangaId, asc, filter, sort) → `flatMapLatest` over `libraryRepository.getChaptersByMangaId` → filter/sort/reverse (`:91-129`).
  - `imageStatus` = per-source site reachability check via raw `OkHttpClient` GET with source default headers (`:196-239`); 403 → triggers WebView dialog.
  - `refreshChapters()` fetches via `sourcesRepository.getRepoByName(api).fetchMangaChaptersF(url)`, updates manga metadata + cover everywhere, inserts new chapters as `isNew` (`:246-302`).
  - Chapter mutations: toggle/mark read (single + bulk), toggle bookmark (single + bulk), delete downloaded chapters (file deletion via `deleteDownloadedChapters`), markChapterIsNew, updateLastOpen.
  - Downloads delegated to `DownloadViewModelv2` (downloadChapter / downloadChapters / cancel / cancelRunning), gated on connectivity; rewarded-ad shown on bulk/custom download.
  - **Permissions/side effects:** Firebase `manga_open` analytics event logged on open (`LibraryMangaScreen.kt:104-113`); ad preload + rewarded-ad gating; file I/O for sizes (`FileSizeUtils`, on `Dispatchers.IO`).
- **Feature inventory:** back; share (inert); refresh; filter; sort + direction; delete-all-downloaded; resume FAB; per-chapter read/download/bookmark/cancel; NEW badge; chapter date + file-size captions; multi-select (download/bookmark/mark-read/delete/mark-down-read/cancel); add/remove from library (bookmark) with confirm; download-all; custom (selected) download; open-in-browser; total download size display; cover long-press copy title; genres/description section; banner ad.
- **Citations:** `LibraryMangaScreen.kt:67-472`, `LibraryMangaRoute.kt:40-276`, `LibraryDetailsViewModel.kt:40-381`, `LibraryHeaderSection.kt:31-100`, `LibraryChapterItem.kt:322-643`, `ChapterSelectionActionsRow.kt:33-101`, `ActionsRow.kt:24-79`, `DownloadMenu.kt:15-54`, `MangaTopAppBar.kt:30-101`, `InfoCompose.kt:34-87` (CoverImage/TitleSection/InfoSection), `TotalSizeDisplay.kt:35-79`.

---

### Cluster notes

**Asset inventory:**
- `R.drawable.ic_done_down_arrow` — PRESENT (`res/drawable/ic_done_down_arrow.xml`), used in `ChapterSelectionActionsRow.kt:79` (mark-all-down-read for single selection).
- Lottie raws referenced by `AnimatedPreloader`/`AnimatedNew`/`AnimatedCompressing`: code references `R.raw.download_anim`, `R.raw.new_ani`, `R.raw.filemoving`. The `raw/` dir contains `download_anim.lottie`, `new_ani.lottie`, `filemoving.lottie` (+ `download_animation.json`). The `.lottie`/`.json` extension is stripped by `R.raw`, so these resolve. **Note `download_animation.json` is a separate file from `download_anim.lottie`** — confirm which is wired when porting.
- All strings used resolve in `res/values/strings.xml` (verified: title_library, filter_*, sort_*, watching_now, likes, last_updated, not_updated_yet, items_count plural, empty_library_*, source_badge_format, show_* toggles, items_per_row_label, auto_text, items_count_format, delete_manga, chapters_count_format, new_chapter, downloaded_header).

**Hardcoded / non-localized strings (parity hazards):**
- MangaCard action button `contentDescription`s "Watch Later" / "Favorite" / "Delete" are literals (`MangaCard.kt:227,251,275`).
- FAB text "Resume {n}" / "You finished this manga" are literals (`LibraryMangaScreen.kt:234`).
- FAB `contentDescription` "Resume" literal (`:232`).
- `LibraryDetailsViewModel.SortType.getDisplayName` returns "Id"/"Number"/"Date"/"Last Read Date" literals (`:150-154`).
- Chapter "Cancel chapter download" appears both as a literal (`LibraryChapterItem.kt:515`, the RUNNING branch) AND as `R.string.cancel_chapter_download` (`:546,577`) — inconsistent.
- `onRefreshLibrary` Toast "No manga in your library yet!" literal (`LibraryRoute.kt:194`).

**Two FilterType/SortType enums exist** — `LibraryViewModel` (grid: 6 filters / 6 sorts incl. RANDOM) vs `LibraryDetailsViewModel` (chapter list: 5 filters incl. READED / 4 sorts). They are NOT shared; the KMP port must keep them distinct.

**Behavioral subtleties to preserve in KMP:**
- `itemsPerRow == 0` means Auto/adaptive grid (`GridCells.Adaptive`); >0 means fixed columns. Default is 2.
- Grid uses the per-source `buildItemsImageRequest` (the "items" variant), while the details cover uses a plain Coil `ImageRequest` with `crossfade(true)` (`InfoCompose.kt:36-41`) — different request paths.
- RANDOM sort persists a seed so order is stable across recompositions; re-selecting RANDOM regenerates it.
- The global `settingsRepository.downloadedOnlyFlow` overrides the chosen filter to downloaded-only.
- Search filters by title `contains` (case-insensitive), live.
- `MangaDisplayItem` carries `totalChapters/readCount/downloadedCount/bookmarkedCount` precomputed by the DAO (`SavedMangaWithMetrics` projection); `lastReadTs` exists on the projection but the grid sorts LAST_READ by `manga.lastOpenTimestamp`, not chapter last-read.
- WhatsNew auto-launch is wired into the Library tab route, not a standalone trigger.
- Two `TextButton(onClick=onContinue){ TextButton(...) }` nesting bug in `DownloadProgressDialog.kt:60-63` (double-nested button) — present in old code; `DownloadProgressDialog` is in the library package but is **not referenced by LibraryScreen/LibraryItems** (appears unused in this cluster — INFERRED).
- `MangaTopAppBar` Share action is inert (empty onClick) in the old app.

**SavedMangaEntity fields** (`data/local/entity/SavedMangaEntity.kt`): id, api, language, url, imageUrl, title, description, status, rating?, genres, savedTimestamp, lastOpenTimestamp, isLiked, isWatchingNow.
