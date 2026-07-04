# OLD Native Android Audit — HOME + SEARCH cluster

> READ-ONLY audit of `yami-manga-apk-main`. Cited as `file:line`. All paths under
> `app/src/main/java/me/manga/yamiapk/` unless noted. Package in source is `me.manga.kira`.
> Inferences marked **(INFERRED)**.

This cluster is a single navigation destination (`HomeRoute`) that flips between two
full-screen surfaces driven by `isSearchVisible`: the **Home feed** (`HomeScreen`) and the
**in-app Search** (`SearchScreen`). Both are described below as separate screens, plus the
component-level surfaces they host (featured carousel, source tabs, manga rows/cards, search
items, filter sheet, maintenance screens, help video dialog).

---

### Home feed (HomeScreen)
- **Entry/route:** `HomeRoute` is the composable bound to the Home bottom-nav tab. `HomeRoute` collects all ViewModel state and delegates to `HomeScreenContainer`, which branches: `if (isSearchVisible) SearchScreen else HomeScreen` (`navigation/routes/HomeRoute.kt:288-347`). Home is the `else` branch. ViewModels: `MangaViewModel` (home/search data, owns most state — note: lives in `presentation/common/viewmodel/`), `HomeViewModel` (saved-titles + multi-search + Lekmanga site-status), `ChaptersViewModel`, plus `RepoSettingsViewModel` passed in for tabs/site-state (`HomeRoute.kt:54-117`). `listState`/`gridState` are hoisted from the caller (preserved across nav).
- **Layout & components:** (top→bottom)
  - `Scaffold` with `scaffoldState` (snackbar host) and `contentColor = colorScheme.background` (`HomeScreen.kt:226-228`).
  - `topBar = TopAppBarCom(title = stringResource(title_home="Home"))` with 3 action `IconButton`s shown only when `siteState == WORKING` (`HomeScreen.kt:229-249`):
    1. Grid/List toggle — icon `Icons.AutoMirrored.Filled.ViewList` (when in grid) or `Icons.Default.GridView` (when in list); contentDescription "List View"/"Grid View".
    2. Search — `Icons.Default.Search`, cd "Search" → `onToggleSearch()`.
    3. WebView — `Icons.Default.Web`, cd "Options" → `onOpenInWebView()`.
  - Body `Box`(fillMaxSize, top padding = scaffold top inset, bg = background) → inner `Box` with `.pullRefresh(pullRefreshState)` → `Column(fillMaxSize)`:
    - `SourcesTabs(...)` (always, at top, outside the list) (`HomeScreen.kt:270-276`).
    - `Spacer(height 8.dp)`.
    - Then a `when (isGridView)` branch, each containing a `when (siteState)`:
      - **List view (default):** `LazyColumn(state=listState, contentPadding=8.dp)`:
        - item: `MangaCarousel` IF `popularManga is State.Success` and non-null (`HomeScreen.kt:385-399`).
        - item: `Spacer(height 16.dp)`.
        - `itemsIndexed(mangaItemsState.data)` → each entry is `ListEntryWithAd.Item` → `MangaHomeItem(...)`, or `ListEntryWithAd.Ad` → `NativeAdListItem(position=index, padding 8.dp)` (`HomeScreen.kt:405-431`).
        - if `isLoadingNextPage`: trailing item `LoadingScreen()` (`HomeScreen.kt:433-437`).
      - **Grid view:** `LazyVerticalGrid(state=gridState, columns=GridCells.Adaptive(minSize=160.dp), contentPadding=8.dp)` → `SearchItems(...)` per item (NOT MangaHomeItem; carousel is NOT shown in grid mode), or `NativeAdListItem`, plus trailing `LoadingScreen` when loading next page (`HomeScreen.kt:307-339`).
  - `PullRefreshIndicator` aligned `TopCenter`, `backgroundColor = inverseSurface`, `contentColor = background` (`HomeScreen.kt:455-461`).
  - `HelpVideoDialog` overlaid when `showHelpDialog` (set true from the error screen's Help button) (`HomeScreen.kt:465-469`).
  - **Ad interleaving:** `mangaItemsState` is wrapped via `interleaveAdsCustom(interval = 5, ...)` into `List<ListEntryWithAd<MangaItem>>` before rendering — every 5th slot is a native ad (`HomeScreen.kt:147-158`).
- **Visual:** Top bar title 24.sp Bold (`TopAppBarCom.kt:22-23,38-39`), titleLarge style, color `onBackground`, bg `background`. List/grid contentPadding 8.dp. Carousel block height 220.dp. Spacer 8.dp under tabs, 16.dp above first row. Adaptive grid min 160.dp. Pull-refresh indicator uses inverseSurface/background. Cards/rows carry their own styling (see component sections).
- **States:**
  - **loading:** `mangaItemsState is State.Loading` → `LoadingScreen(fillMaxSize, padding 16.dp)` — a centered `CircularProgressIndicator(color = inversePrimary)` (`LoadingScreen.kt:18-32`).
  - **error:** `State.Error` → `ErrorScreen(message = stringResource(failed_to_load="Failed to load : %1$s", err.message), onRetry=onRefresh, onOpenInBrowser=onOpenInWebView, onHelp={showHelpDialog=true})` (`HomeScreen.kt:292-305, 365-377`).
  - **empty:** No dedicated empty state — `State.Success(emptyList())` renders the carousel (if present) + spacer with no rows. **(INFERRED gap)** there is no "no results" placeholder for an empty successful home feed.
  - **success:** carousel + interleaved rows/grid.
  - **site-state overlays:** independent of data state, `siteState` (from `RepoSettingsViewModel.getSiteStateFlow`) can replace the whole body with `SiteMaintenanceScreen` (`UNDER_MAINTENANCE`), `SiteStoppedScreen` (`STOPPED`), or `SiteAdultContentBlockedScreen` (`ADULT_18_PLUS`) (`HomeScreen.kt:343-351, 442-450`).
- **Interactions:**
  - Pull-to-refresh (`rememberPullRefreshState(isRefreshing, onRefresh)` → `mangaViewModel.getMangaHome()`) (`HomeScreen.kt:252-255`, `HomeRoute.kt:210`).
  - Infinite scroll: `snapshotFlow { listState/gridState.isScrolledToTheEnd() }.filter{it}.distinctUntilChanged()` → `onEndReached()` = `mangaViewModel.onLastItemVisible()`, guarded by `hasRequestedNextPage` which resets on tab change / data-size change (`HomeScreen.kt:122-145`, `HomeRoute.kt:211`).
  - Tab change resets both scroll positions to item 0 via `LaunchedEffect(activeTabIndex)` (`HomeScreen.kt:114-120`).
  - Grid/List toggle (`isGridView` local state, top-bar button) (`HomeScreen.kt:110, 235-240`).
  - Manga click → `onMangaClick(url, api, title, isSaved)` → `onMangaDetailsClick` (navigate to details) (`HomeRoute.kt:182-184`).
  - Chapter chip click → `onChapterClick(chapter, item, chapters)` (`HomeRoute.kt:200`).
  - Save/bookmark toggle → `onSaveToggle(mangaItem)`: if already saved, `homeViewModel.toggleManga(...)` immediately; else fetches chapters via `chaptersViewModel.getChaptersDataR(url)` then toggles (`HomeRoute.kt:185-199`).
  - 403 handling: `Handle403Error(state=mangaItemsState, api, baseUrl, onDismiss=getMangaHome after 1s delay)` runs when `siteState==WORKING` (`HomeRoute.kt:135-147`). Separately, a Lekmanga-only `siteStatusFlow` 403 triggers a snackbar with action "Open" → navigate to `Screen.WebView("https://lekmanga.net/manga/","Lekmanga")` (`HomeRoute.kt:118-128`, `HomeViewModel.kt:83-126`).
  - WebView action → navigate `Screen.WebView(currentBaseUrl, currentApi)` (`HomeRoute.kt:164-178`).
- **Dialogs/sheets/snackbars:** `HelpVideoDialog` (from error→Help). Snackbar for Lekmanga 403 token-refresh (`HomeRoute.kt:118-128`). No bottom sheet on Home itself (filter sheet lives in Search).
- **Forms & validation:** None on Home (search field lives in Search surface).
- **Data/behavior:** `MangaViewModel` exposes `mangaItems`, `popularManga`, `activeTabIndex`, `LoadingNextPage`, `isRefreshing`, `currentBaseUrlFlow`, `currentApiFlow`, etc. (collected `HomeRoute.kt:71-114`). Tabs from `RepoSettingsViewModel.enabledRepositoriesFlow`. `onEditTabs` navigates to `Screen.RepoSettings(false)` + clears new-source flag (`HomeRoute.kt:213-217`). `buildImageRequest` is per-source via `mangaViewModel.buildImageRequest(ctx,url,api)` (`HomeRoute.kt:227-229`). Note debug `Log.i` calls left in (`HomeRoute.kt:84, 170`).
- **Feature inventory:**
  - Grid/List view toggle (top bar).
  - Open in-app Search (top bar).
  - Open current source in WebView (top bar).
  - Featured/popular carousel (list mode only).
  - Source tabs strip + edit-tabs button + "NEW" badge.
  - Infinite-scroll pagination + bottom loading spinner.
  - Pull-to-refresh.
  - Per-row save/unsave (favorite) with inline spinner.
  - Per-row up-to-3 chapter quick-jump chips.
  - Manga card → details navigation.
  - Native ad interleaving (every 5th item).
  - Retry / Open-in-browser / Help error actions.
  - Help video dialog.
  - Maintenance / Stopped / Adult-blocked full-screen states.
  - Lekmanga 403 → token-refresh snackbar.
- **Citations:** `HomeScreen.kt:79-471`; `HomeRoute.kt:54-241`; `HomeViewModel.kt`; `LoadingScreen.kt`; `ErrorScreen.kt`.

---

### Featured carousel (MangaCarousel)
- **Entry/route:** Rendered as the first `item` of the Home `LazyColumn` when `popularManga is State.Success` (list mode only) (`HomeScreen.kt:385-396`).
- **Layout & components:** `HorizontalUncontainedCarousel` (Material3, experimental) — `contentPadding horizontal 12.dp`, `fillMaxWidth().height(220.dp)`, `itemWidth=150.dp`, `itemSpacing=16.dp`, `flingBehavior = CarouselDefaults.singleAdvanceFlingBehavior(..., snapAnimationSpec=spring())` (`MangaCarousel.kt:46-57`). Each page is a `Card(RoundedCornerShape 12.dp, elevation 6.dp)` containing a full-bleed `AsyncImage(contentScale=Crop)` built from `buildImageRequest`, using the singleton `getImageLoader()` (`MangaCarousel.kt:74-94`).
- **Visual:** 220.dp tall, 150.dp wide cards, 16.dp spacing, 12.dp horizontal content pad, rounded 12.dp, elevation 6.dp. Cover image cropped to fill.
- **States:** Empty list → early `return` (renders nothing) (`MangaCarousel.kt:41`). No per-item loading/error placeholder shown (AsyncImage default).
- **Interactions:** Per-card click → `onItemClick(url, api, title, isSaved)`. Scale animation: each item scales via `animateFloatAsState` driven by `carouselItemDrawInfo.size/maxSize` with `spring(DampingRatioMediumBouncy)` applied through `graphicsLayer` (parallax/zoom effect) (`MangaCarousel.kt:65-83`). Single-advance fling snapping.
- **Dialogs/sheets/snackbars:** None.
- **Forms & validation:** None.
- **Data/behavior:** Items are `List<PopularManga>` from `mangaViewModel.popularManga`. No save toggle on carousel.
- **Feature inventory:** Horizontal swipe; tap → details; animated scale/snap.
- **Citations:** `MangaCarousel.kt:32-96`.

---

### Source tabs strip (SourcesTabs)
- **Entry/route:** Top of Home body, always shown (`HomeScreen.kt:270-276`).
- **Layout & components:** `Row(fillMaxWidth, height 48.dp)` → `ScrollableTabRow(weight 1f, backgroundColor=Transparent, edgePadding=16.dp, indicator={}, divider={})` with a `Tab` per `BaseMangaRepository` (`SourcesTabs.kt:58-120`). Each tab: rounded 12.dp pill background (`primary @ alpha .1` when selected else transparent), `Row` of source icon (`painterResource(repo.ICON)`, fallback `R.drawable.team_x` when ICON==0, size 18.dp) + `Text(repo.API)` in `labelMedium` Medium weight. Trailing: edit `IconButton(size 36.dp, painter R.drawable.ic_edit_sur, tint onBackground)` with an `AnimatedNew` "NEW" badge overlay when `isNewSource` (`SourcesTabs.kt:123-149`).
- **Visual:** Selected tab text = `primary`; unselected = `onSurface @ alpha .6`. Icon tint `Color.Unspecified` (full color) when selected, dimmed onSurface otherwise. Pill padding 8/4 outer + 12/6 inner. Default edit label `R.string.new_badge` = "NEW".
- **States:** N/A (driven by tabs list + activeTabIndex). Empty tabs list → empty row **(INFERRED)**.
- **Interactions:** Tab tap → `onTabSelected(index)` (`MangaViewModel.onTabSelected`). Edit tap → `onEditTabs()` → navigate to RepoSettings. `AnimatedNew` badge animation when new source available.
- **Dialogs/sheets/snackbars:** None.
- **Data/behavior:** Tabs = enabled repositories. `isNewSource` from `repoSettingsViewModel.newSources`. Debug `Log.i` left in loop (`SourcesTabs.kt:76`). Note `contentDescription = "repo.API"` is a literal string bug (`SourcesTabs.kt:108`).
- **Feature inventory:** Scroll/select source; edit sources; new-source badge.
- **Citations:** `SourcesTabs.kt:49-151`.

---

### Manga home row (MangaHomeItem) — list-mode row
- **Entry/route:** Rendered per `ListEntryWithAd.Item` in Home list mode (`HomeScreen.kt:408-420`).
- **Layout & components:** `Card(fillMaxWidth, padding 8.dp, shadow elev 8.dp rounded 8.dp, containerColor=background)` → `Row(padding 8.dp)`: left `Card(100×130.dp, elevation 12.dp)` with full-bleed `AsyncImage(Crop)` cover; `Spacer(width 12.dp)`; right `Column(weight 1f)`: title `Text` (Gellix font family, 16.sp, Bold, onBackground, maxLines 2, ellipsis, start-aligned), `Spacer(12.dp)`, then `Row`: chapters column (`weight 4f`) of up to 3 chapter `Card`s (rounded 6.dp, primaryContainer bg, elevation 8.dp, label = `"${chapter.number}"` Gellix 10.sp) + `Spacer(weight 1f)` + save `IconButton` (`MangaHomeItem.kt:69-200`).
- **Visual:** Gellix typeface for title and chapter labels. Card shadow ambient/spot = onSurface@.9. Cover 100×130. Chapter chips primaryContainer.
- **States:** Save button shows `CircularProgressIndicator(inversePrimary)` while `isLoading`; `LaunchedEffect(isSaved)` clears loading once saved (`MangaHomeItem.kt:62-68, 184-198`).
- **Interactions:** Card click → `onMangaClick`. Chapter card click → `onChapterClick(chapter, item, chapters)`. Save click → sets isLoading (when not yet saved) + `onSaveClick(item)`.
- **Dialogs/sheets/snackbars:** None.
- **Data/behavior:** Save icon = `R.drawable.ic_bookmark_bold` (saved) / `R.drawable.ic_bookmark` (unsaved) via `painterResource` (`MangaHomeItem.kt:192`).
- **Feature inventory:** Open details; jump to one of first 3 chapters; save/unsave with spinner.
- **Citations:** `MangaHomeItem.kt:50-204`.

---

### Manga home card (MangaHomeCard) — alternate richer row **(unused?)**
- **Entry/route:** Defined but NOT referenced by `HomeScreen` (which uses `MangaHomeItem`). **(INFERRED)** legacy/alternate variant; may be dead or used elsewhere. No call site found in this cluster.
- **Layout & components:** Same Row-with-cover layout as `MangaHomeItem` but adds: a **source badge** (`AutoSubtitleText` "${api} - ${language}", colored by `api.COLORS`, dark/light text via `bgColor.isDark()`) overlaid top-start on the cover; `AutoSubtitleText` auto-sizing title (16/18/12 sp); chapter chips labelled `"Chapter ${number}"`; round 48.dp save button using `Icons.Filled.Favorite`/`FavoriteBorder` (red when saved) instead of bookmark drawables (`MangaHomeCard.kt:58-289`).
- **Visual:** Cover 90×130 inside rounded-8 card; source badge colored per-source; placeholder/error `ColorPainter` tints; rounded-12 outer card.
- **Feature inventory:** Source/language badge, auto-sized text, favorite-heart save, chapter quick-jump. (Differs from the live `MangaHomeItem` — relevant for parity decisions.)
- **Citations:** `MangaHomeCard.kt:58-290`.

---

### Search-result grid item (MangaSearchItems / SearchItems)
- **Entry/route:** `MangaSearchItems` hosts the Search tab's results grid; `SearchItems` is the single grid cell, ALSO reused directly in Home **grid mode** (`HomeScreen.kt:316-321`, `SearchScreen.kt:159-167`).
- **Layout & components:** `MangaSearchItems`: `Column` → fixed `BannerAdView()` then `LazyVerticalGrid(columns=Adaptive(minSize=160.dp), contentPadding h8/v4)` of `SearchItems` (`MangaSearchItems.kt:42-75`). `SearchItems`: `Card(padding 8.dp, fillMaxWidth, rounded 8.dp, elevation 2.dp)` → `Box(height 250.dp)` with full-bleed `AsyncImage(contentScale=FillBounds, imageLoader=getImageLoader())` and a bottom 50.dp overlay (`Black@.3` scrim) with centered white `Text(title, 10.sp, Bold, maxLines 2, ellipsis)` (`MangaSearchItems.kt:77-142`).
- **Visual:** Poster-style cell, 250.dp tall, title overlaid on a translucent black band at bottom, white 10.sp bold centered. `FillBounds` (stretches) — note distinct from carousel's `Crop`.
- **States:** No per-cell loading/error placeholder (default AsyncImage).
- **Interactions:** Card click → `onMangaClick(url, api, title, isSaved)`.
- **Dialogs/sheets/snackbars:** None. (Banner ad fixed at top of grid.)
- **Feature inventory:** Tap → details; banner ad.
- **Citations:** `MangaSearchItems.kt:42-142`.

---

### In-app Search (SearchScreen)
- **Entry/route:** `HomeScreenContainer` shows `SearchScreen` when `isSearchVisible` (toggled by the Home top-bar Search button → `mangaViewModel.onSearchToggle`) (`HomeRoute.kt:288-316`). `BackHandler(enabled=isSearchVisible)` → `closeSearch()` (`HomeRoute.kt:289-294`).
- **Layout & components:** `Scaffold(topBar = SearchAppBar(...))`. Body `Box(fillMaxSize, top inset pad, bg background)` → `Column`:
  - `ChipsRow(items = [Search, Multi Search], selectedItem=selectedTab)` — two-segment tab selector as filter chips (`SearchScreen.kt:128-132`).
  - `HorizontalPager(pageCount=2, pageSpacing=16.dp)` synced bidirectionally with `selectedTab` (`SearchScreen.kt:72-82, 133-183`):
    - **Page 0 (Search):** `when (searchResultsState)` → `LoadingScreen` / `ErrorScreen` / `MangaSearchItems(...)`.
    - **Page 1 (Multi Search):** `MultiRepoResults(...)` in a fillMaxSize Box.
- **SearchAppBar** (`presentation/common/componants/app_bars/SearchAppBar.kt`): Material3 `TopAppBar(containerColor=background)`; navigationIcon = close `IconButton` (`Icons.Default.Close`) → `onToggleSearch()` + clear query; title = `OutlinedTextField` (placeholder `searching_placeholder`="Search your manga…", leading Search icon, trailing clear-X when non-blank, transparent borders/containers, rounded 12.dp, `labelLarge` text, single line, `ImeAction.Search`, on-search hides keyboard) (`SearchAppBar.kt:34-117`); actions = a Settings `IconButton` (`Icons.Default.Settings`) → opens filter bottom sheet (`SearchScreen.kt:104-111`).
- **Visual:** Transparent-bordered search field, rounded 12.dp, 56.dp tall. Tab chips with Done leading-icon when selected (`ChipsRow`/`ChipItemRow` `flow_chips/ChipsRow.kt`). Pager spacing 16.dp.
- **States (Page 0):** loading → `LoadingScreen()`; error → `ErrorScreen(message=failed_to_load, onRetry=onSearchChange(localQuery), onOpenInBrowser, onHelp)`; success → `MangaSearchItems` grid (`SearchScreen.kt:139-168`). Initial `searchState` default `State.Success(emptyList())` (`HomeRoute.kt:73`) — empty grid with banner, no explicit "type to search" hint **(INFERRED gap)**.
- **Interactions:** Type in field (`localQuery` local state); submit (`onSearch`): page 1 → `onMultiSearch(localQuery)`, page 0 → `onSearchChange(localQuery)` then hide keyboard (`SearchScreen.kt:95-103`). Tab switch via chips or swipe (pager↔selectedTab two-way sync via two `LaunchedEffect`s). Close → back/X. Settings → filter sheet. `onSearchChange` triggers `mangaViewModel.startSearch(SearchType.Normal(q))` (`HomeRoute.kt:158-162`).
- **Dialogs/sheets/snackbars:** `SearchBottomSheet` (filter/sort) shown when `ShowSetting` (`SearchScreen.kt:189-213`).
- **Forms & validation:** Free-text query, no validation; empty query allowed (submits empty). Sort default = first sortType or `no_sort_types` label.
- **Data/behavior:** `searchResultsState` from `mangaViewModel.mangaSearchItems`; `multiSearchState` from `homeViewModel.allSearchResults`; sortTypes/genres/activeGenres from MangaViewModel flows. `onSortClick(sortType, query, genre)`, `onGenreClicked(genre)`. Has a `@Preview`. Debug `Log` import present.
- **Feature inventory:** Search field + clear; submit search; Search vs Multi-Search tabs (chips + swipe); filter/sort sheet (settings icon); retry/help/open-browser on error; close search (X / back).
- **Citations:** `SearchScreen.kt:50-257`; `SearchAppBar.kt:34-117`; `ChipsRow.kt`.

---

### Multi-source search results (MultiRepoResults)
- **Entry/route:** Page 1 of `SearchScreen`'s pager (`SearchScreen.kt:174-180`). Fed by `homeViewModel.fetchAllSearchResults(query)` which merges per-repo flows into a `Map<api, State<List<MangaItem>>>` emitting incrementally (`HomeViewModel.kt:55-80`).
- **Layout & components:** `Column(fillMaxSize)` → fixed `BannerAdView()` + `LazyColumn(padding 16.dp, vertical spacing 24.dp)` of `RepoSection` per (api, state) (`MultiRepoResults.kt:53-80`). Each `RepoSection`: `Text(apiName, titleLarge, primary)` header, then `when(state)`: Loading → centered `CircularProgressIndicator` in 100.dp box; Error → `Text("Error: ${message}", error color)`; Success → `LazyRow(spacing 12.dp)` of `MultiSearchItem` (`MultiRepoResults.kt:83-133`).
- **MultiSearchItem:** `Card(140×200.dp, rounded 8.dp, elevation 4.dp)` clickable → `onMangaClick`; full-bleed `AsyncImage(Crop)` with a top-start translucent (`Black@.6`, rounded bottomEnd 4.dp) title label (`bodySmall` white, maxLines 1, ellipsis) (`MultiRepoResults.kt:135-192`).
- **Visual:** Per-source vertical sections (24.dp gap), horizontal poster rows, 140×200 cards.
- **States:** Per-section loading spinner / error text / success row (handled independently per source). Empty map → empty list **(INFERRED)**.
- **Interactions:** Card click → `onMangaClick(url, api, title, isSaved)`. `onRepoChange` is wired but the call inside is commented out (`MultiRepoResults.kt:150-156`).
- **Dialogs/sheets/snackbars:** Banner ad fixed at top.
- **Feature inventory:** Search all enabled sources at once; per-source progressive results; tap → details.
- **Citations:** `MultiRepoResults.kt:44-192`; `HomeViewModel.kt:50-80`.

---

### Filter / sort sheet (SearchBottomSheet)
- **Entry/route:** Opened from `SearchScreen` Settings icon (`ShowSetting`) (`SearchScreen.kt:189-213`).
- **Layout & components:** `ModalBottomSheet(containerColor=surfaceContainerHigh, tonalElevation 8.dp, rounded top 16.dp)` → `Column(padding 16.dp)`:
  - If both `allSortOptions` and `genres` empty → centered `Text(search_filters_not_ready)` in 200.dp box and early return (`SearchBottomSheet.kt:89-103`).
  - Title `Text(filter_sort_title="Filter & Sort", titleLarge)`.
  - Genres section: collapsible `SectionHeader(genres="Genres", expanded, chevron up/down)` → `AnimatedVisibility` → scrollable `FlowRow(maxHeight 300.dp)` of `FilterChip` per sorted genre (single-select, Check leading-icon when selected, rounded 16.dp, primaryContainer when selected) (`SearchBottomSheet.kt:112-147`).
  - `HorizontalDivider`.
  - Sort section: `Text(order_by="Order By", titleMedium)` + `ExposedDropdownMenuBox` with read-only `OutlinedTextField` + `ExposedDropdownMenu` of `allSortOptions` (`SearchBottomSheet.kt:154-194`).
  - `Button(fillMaxWidth, height 48.dp, rounded 24.dp)` → `Text(apply_filters="Apply Filters")` → `onDismiss` (`SearchBottomSheet.kt:197-205`).
- **Visual:** surfaceContainerHigh bg, rounded-top sheet, 16.dp pad, chips rounded 16, apply button rounded 24.
- **States:** "filters not ready" placeholder when no options.
- **Interactions:** Genre chip → `onGenreClicked(genre)` (single-select). Sort item → `onSortSelected(option, selectedGenre)` → `onSortClick(sortType, localQuery, genre)` (`SearchScreen.kt:202-205`). Apply → dismiss. Section header collapse/expand. Note: signature here is `onSortSelected: (String,String)->Unit` and `selectedGenre`/`onGenreClicked` — the SearchScreen call passes `selectedGenre = selectedGenres` (`SearchScreen.kt:201-210`).
- **Forms & validation:** Single sort + single genre selection; no multi-select.
- **Data/behavior:** Genres/sortTypes from current source. Selecting a sort fires a re-search with sort+genre via `SearchType.SORT`/`GENRES` **(INFERRED from SearchType)**.
- **Feature inventory:** Choose genre (single); choose sort order; apply; collapse genres; empty-state guard.
- **Citations:** `SearchBottomSheet.kt:50-235`; `SearchScreen.kt:189-213`.

---

### Site status screens (Maintenance / Stopped / Adult-blocked)
- **Entry/route:** Replace Home body based on `siteState` (`HomeScreen.kt:343-351, 442-450`).
- **Layout & components:** All three use shared private `SimpleStatusScreen`: centered `Column` in `Box(fillMaxSize, padding 32.dp)` → circular 120.dp `Card` (rounded 60, colored container, elevation 8) holding a 48.dp `Icon`; `Spacer 32`; title `headlineMedium` Bold; `Spacer 12`; site-name subtitle `titleLarge` primary Medium; `Spacer 16`; message `bodyLarge` onSurface@.7 (`SiteMaintenanceScreen.kt:54-129`).
  - Maintenance: `Icons.Default.Build`, primary/primaryContainer, title `under_maintenance`="Under Maintenance" (`:22-36`).
  - Stopped: `Icons.Default.Error`, error/errorContainer, title `site_stopped`="Site Stopped" (`:38-52`).
  - Adult-blocked: `Icons.Default.Error`, tertiary/tertiaryContainer, title `adult_content_blocked_title`="Blocked (18+)" (`:132-146`).
- **Visual:** Big circular colored icon badge; centered text stack.
- **States:** N/A (terminal states themselves).
- **Interactions:** None (informational). Has `@Preview`s.
- **Feature inventory:** Informational source-status messaging.
- **Citations:** `SiteMaintenanceScreen.kt:22-174`.

---

### Help video dialog (HelpVideoDialog)
- **Entry/route:** Shown from Home `ErrorScreen`'s Help button (`showHelpDialog`) (`HomeScreen.kt:303, 376, 465-469`).
- **Layout & components:** Full-screen `Dialog(usePlatformDefaultWidth=false)` → `Box(80% w × 80% h)` → black `Card(rounded 16)` containing an `AndroidView { VideoView }` streaming `https://yamimanga.me/video/help_video.mp4` with a `MediaController`, a centered `CircularProgressIndicator` overlay while loading, and a top-end close `IconButton(Icons.Default.Close, white)` (`HelpVideoDialog.kt:50-209`).
- **Visual:** Black card, 80% screen, loading scrim `Black@.7`.
- **States:** `isVideoLoading` spinner; 15s timeout force-stops loading; error/info listeners; lifecycle pause/resume; async safe release to avoid ANR (`HelpVideoDialog.kt:61-95, 145-154, 215-254`).
- **Interactions:** Play/pause via MediaController; close (X or dismiss) triggers async cleanup.
- **Dialogs/sheets/snackbars:** Is itself the dialog.
- **Data/behavior:** **Android-only** `VideoView` (`android.widget.VideoView`/`MediaController`) — NOT KMP-portable; hardcoded remote MP4 URL. Lifecycle-aware cleanup. **(Asset note: remote URL, not a bundled asset.)**
- **Feature inventory:** Watch help video; close.
- **Citations:** `HelpVideoDialog.kt:50-289`.

---

### Cluster notes (shared components, assets/fonts/icons, theming)

**Shared composables:**
- `TopAppBarCom` (`presentation/common/componants/app_bars/TopAppBarCom.kt`) — generic top bar; title 24.sp Bold titleLarge, bg/text from `background`/`onBackground`.
- `SearchAppBar` (`.../app_bars/SearchAppBar.kt`) — reused search field bar.
- `ChipsRow`/`ChipItemRow` (`.../flow_chips/ChipsRow.kt`) — segmented FilterChip selector (used for Search/Multi-Search tabs).
- `LoadingScreen` (`presentation/common/screens/LoadingScreen.kt`) — centered `CircularProgressIndicator(inversePrimary)`.
- `ErrorScreen` (`presentation/common/screens/ErrorScreen.kt`) — message + 3 `IconAboveTextButton`s (Retry/Refresh, Open in WebView/WebAsset, Help/HelpOutline), optional back arrow. Strings: `retry`="Retry", `action_open_in_browser`="Open in WebView", `help`="Help".
- `isScrolledToTheEnd()` extension (`presentation/common/componants/isScrolledToTheEnd.kt`) — drives infinite scroll for both LazyListState and LazyGridState.
- `interleaveAdsCustom` / `NativeAdListItem` / `BannerAdView` (`ad_mob/...`) — ad system woven into Home list/grid and Search grids. **(Ads are likely deferred/absent in KMP — gap-analysis flag.)**

**Data classes:** `ApiTitle(api, title)` (save-key) (`home/data/ApiTitle.kt`); `SearchType` sealed (`Normal`/`SORT`/`GENRES`) (`home/data/SearchType.kt`).

**ViewModels of record:** `MangaViewModel` (in `presentation/common/viewmodel/`, NOT in home/) owns home feed + search + tabs + image-request building + sort/genre; `HomeViewModel` (in home/ui/viewmodel/) owns saved-titles, multi-search merge, and a Lekmanga-specific OkHttp site-status probe.

**Fonts/typography:** `GellixFontFamily` (`me.manga.kira.theme.GellixFontFamily`) used directly for `MangaHomeItem` title + chapter labels (`MangaHomeItem.kt:47,121,164`). Other surfaces rely on `MaterialTheme.typography`. Theming via `YamiMangaTheme` (`me.manga.kira.theme`), colors via `MaterialTheme.colorScheme` (primary/primaryContainer/surface/inverseSurface/inversePrimary/tertiary etc.).

**Icons / drawables (assets to verify in KMP):**
- Material icons: `GridView`, `AutoMirrored.ViewList`, `Search`, `Web`, `Settings`, `Close`, `Build`, `Error`, `Check`, `KeyboardArrowUp/Down`, `Favorite`/`FavoriteBorder`, `Done`, `Refresh`, `WebAsset`, `HelpOutline`.
- Drawable resources: `R.drawable.ic_bookmark` / `ic_bookmark_bold` (MangaHomeItem save), `R.drawable.ic_edit_sur` (edit tabs), `R.drawable.team_x` (source-icon fallback), plus per-source `repo.ICON` drawables. **Record: these must exist as KMP `:ui` resources or the rows/tabs lose their bookmark/edit/fallback icons.**

**Per-source color/coloring:** `MangaHomeCard` uses `api.COLORS` + `isDark()` (`sources_repositry.data`) for source badge color — only used by the (apparently unused) `MangaHomeCard`, not the live `MangaHomeItem`.

**Image loading:** All cover images go through `buildImageRequest(context, url, api)` (per-source request builder in `MangaViewModel`/repos) + `getImageLoader()` singleton (`me.manga.kira.di.coli.getImageLoader`). ContentScale differs by surface: carousel/multi-search = `Crop`, search grid cell = `FillBounds`, home rows = `Crop`.

**Platform-specific / portability risks for KMP:**
- `HelpVideoDialog` uses Android `VideoView`/`MediaController` via `AndroidView` — needs a KMP video-player abstraction or deferral.
- `HomeViewModel.getSiteStatus` uses raw `OkHttp3` (`OkHttpClient`/`Request`/`Headers`) — Android/JVM only; Lekmanga-hardcoded.
- Ad components (`NativeAdListItem`, `BannerAdView`, `interleaveAdsCustom`) are AdMob.
- `Handle403Error` + Cloudflare/token-refresh WebView flow is woven into Home.

**Left-in debug/quirks (not parity features, but observed):** several `Log.i` with garbage tags (`HomeRoute.kt:84,170`, `SourcesTabs.kt:76`), literal `contentDescription="repo.API"` (`SourcesTabs.kt:108`), commented-out `onRepoChange` in MultiSearchItem.

**No dedicated empty states** for: empty successful Home feed, empty Search results (page 0), empty Multi-Search map. These show banner/carousel/blank rather than a "no results" message — flag for gap analysis.
