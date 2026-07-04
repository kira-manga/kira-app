# OLD Native Android App — Full Feature & UI Audit

_Assembled 2026-05-31T01:28Z from audit_workspace/old/ — one section per feature cluster. Read-only audit; cites file:line. Companion: the other side's audit + UI_AND_FEATURE_GAPS.md._

## Clusters: Home/Search · Library · Details/Reader · Downloads/Sources · Settings/Theme/Language · History/Updates/Statistics · Complaint/WhatsNew/Welcome/About · WebView/Nav/Shell/Theming


---

# CLUSTER: home_search

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


---

# CLUSTER: library

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


---

# CLUSTER: details_reader

# OLD Native Android Audit — Manga Details + Reader cluster

Source root: `D:/yami manga/yami-manga-apk-main/app/src/main/java/me/manga/yamiapk/`
Package note: directory is `me/manga/yami/...` on disk but the Kotlin package is `me.manga.kira.*`.
Scope: Manga Details feature, Reader feature, the two nav routes, and supporting reader components. All citations are `file:line`.

---

## DETAILS CLUSTER

### Manga Details (route + adult-gate dispatcher)
- **Entry/route:** `navigation/routes/MangaDetailsRoute.kt:34` `MangaDetailsRoute(navController, backStackEntry, onChapterClick, onDownloadClick)`. Reads `Screen.MangaDetails` args via `toRoute()` (`MangaDetailsRoute.kt:45`): `mangaUrl`, `api`. Two Hilt VMs scoped here: `HomeViewModel` + `MangaDerailsViewModel` (`MangaDetailsRoute.kt:42-43`). Dispatcher composable is `MangaDetailsScreen` (`details/ui/screens/MangaDetailsScreen.kt:31`).
- **Layout & components:** `MangaDetailsScreen` is a state switch (`MangaDetailsScreen.kt:48`): `State.Loading → LoadingScreen()`; `State.Error → ErrorScreen(...)`; `State.Success →` adult-gate dialog state machine → `DetailsContent`. On Success it computes initial `DialogState` based on `isPlus18(genres, api)` (`MangaDetailsScreen.kt:63`) and renders one of `AdultConfirmationDialog`, `MConfirmationDialog`(step1, imgs1), `MConfirmationDialog`(step2, imgs2), or `DetailsContent` when `DialogState.None` (`MangaDetailsScreen.kt:74-128`).
- **Visual:** `ErrorScreen` padded 16.dp fillMaxSize (`MangaDetailsScreen.kt:51-61`). All visual content delegated to children. Adult flow uses red imagery.
- **States:** loading (`LoadingScreen()`), error (`ErrorScreen` with retry/open-in-browser/help/back), success (content or adult dialogs). No explicit empty state — empty chapter list still renders header.
- **Interactions:** adult dialog confirm advances the state machine (`AdultWarning → MStep1 → MStep2`); any dismiss calls `onBackClick()` (pops back stack). `MStep2` confirm/dismiss both call `onBackClick()` (`MangaDetailsScreen.kt:100-112`).
- **Dialogs/sheets/snackbars:** `AdultConfirmationDialog`, two `MConfirmationDialog` steps (meme images). Help video dialog (`HelpVideoDialog`) toggled by `onHelp` (`MangaDetailsRoute.kt:113-117`). `WebViewDialog` via `Handle403Error`.
- **Forms & validation:** none.
- **Data/behavior:** `MangaDerailsViewModel` (`details/ui/viewmodel/MangaDerailsViewModel.kt:23`) fetches on init via `sourcesRepository.getRepoByName(api).fetchMangaChaptersF(mangaUrl).collect { _mangaDetails.value = state }` (`MangaDerailsViewModel.kt:45-54`). `onRetry` re-fetches (`MangaDerailsViewModel.kt:57`). Firebase analytics logs `manga_open` event in `LaunchedEffect(state.data.title)` (`MangaDetailsScreen.kt:66-73`). 403 handling: `Handle403Error(state, api, url, onDismiss = delay(1000) + onRetry)` (`MangaDetailsRoute.kt:57-67`). Bookmark via `homeViewModel.toggleManga(it)` (`MangaDetailsRoute.kt:75`). WebView nav: `navController.navigate(Screen.WebView(url, api))` (`MangaDetailsRoute.kt:99`). `onOpenInWebViewError` navigates to WebView using `mangaDerailsViewModel.currentUrl` (skips if empty) (`MangaDetailsRoute.kt:87-96`). `hasShownRemoveBookMark` flow from HomeViewModel gates first-time add dialog.
- **Feature inventory:** adult-content gating (18+ confirmation + 2 meme steps), retry, open-in-webview (error pane + header button), help video, bookmark toggle, download-all, chapter open, firebase logging, 403/Cloudflare recovery, title copy (in header).
- **Citations:** `MangaDetailsRoute.kt:34-121`, `MangaDetailsScreen.kt:31-131`, `MangaDerailsViewModel.kt:23-74`.

### Details Content (scroll body)
- **Entry/route:** `details/ui/screens/DetailsContent.kt:43` `DetailsContent(manga, savedTitles, ...)`. Rendered only when `DialogState.None`.
- **Layout & components:** `Scaffold` with transparent `TopAppBarCom` showing `manga.title` (titleSize 20.sp, FontWeight.Normal, transparent bg) + back `IconButton` with `Icons.AutoMirrored.Filled.ArrowBack` (`DetailsContent.kt:100-111`). Body is a `Box` containing: (1) `ImageWithGradientOverlay` blurred cover backdrop (headerHeight 250.dp, blur 14.dp, parallaxOffset) (`DetailsContent.kt:118-123`); (2) `VerticalFastScroller` wrapping a `LazyColumn` (`DetailsContent.kt:125-176`). LazyColumn item 0 = `HeaderSection`; subsequent items = `ChapterItem` per `manga.chapters` (`DetailsContent.kt:140-174`).
- **Visual:** background `MaterialTheme.colorScheme.background` (`DetailsContent.kt:114`). Parallax: `scrollOffset` derived from `listState.firstVisibleItemScrollOffset` coerced to headerHeightPx (250.dp), `parallaxOffset = scrollOffset/2f` (`DetailsContent.kt:62-73`). Blurred header at 14.dp blur. `ImageWithGradientOverlay`: vertical gradient `background.copy(0.4f)` → `background`, `graphicsLayer { translationY = -parallaxOffset }` (`ImageWithGradientOverlay.kt:19-59`).
- **States:** content only (parent handles loading/error). Empty chapters → `manga.chapters ?: mutableListOf()` (no chapters shows just header) (`DetailsContent.kt:168`).
- **Interactions:** scroll with fast-scroller thumb; chapter click → `onChapterClick`; bookmark confirm dialogs.
- **Dialogs/sheets/snackbars:** `ConfirmDialogClean` remove-bookmark (`R.string.remove_bookmark_title/_message`) (`DetailsContent.kt:74-83`); `ConfirmDialogClean` add-to-library (`R.string.add_library_title/_message`, confirm `R.string.confirm_add_to_library`) (`DetailsContent.kt:86-98`). Add-dialog shown only first time (gated by `hasShownRemoveBookMark`; after first time `onMangaBookmark` is called directly, `DetailsContent.kt:146-155`).
- **Forms & validation:** none.
- **Data/behavior:** `isSaved = ApiTitle(api, title) in savedTitles` (`DetailsContent.kt:61`). Bookmark request branches: if `hasShownRemoveBookMark` → immediate `onMangaBookmark`; else show add dialog + `onShownRemoveBookMark()`.
- **Feature inventory:** parallax blurred header, fast-scroller, back, transparent top bar with title, chapter list.
- **Citations:** `DetailsContent.kt:43-180`, `ImageWithGradientOverlay.kt:19-59`.

### Details Header Section
- **Entry/route:** `details/ui/components/HeaderSection.kt:50` `HeaderSection(manga, isSaved, onMangaBookmark, onRequestAddBookmark, onDownloadClick, onOpenInWebView, buildImageRequest)`.
- **Layout & components:** centered `Column` padded 16.dp (`HeaderSection.kt:62-67`). Order: cover `AsyncImage` (200×250.dp, RoundedCornerShape 8.dp, `ContentScale.Crop`, built via `buildImageRequest(context,url,api)` and `getImageLoader()`) (`HeaderSection.kt:70-78`); 16.dp spacer; title `Text` (Bold 20.sp center, long-press → copy to clipboard + toast `R.string.title_copied`) (`HeaderSection.kt:80-95`); 8.dp spacer; subtitle `"${api} ${language} - ${status}"` (bodyMedium, onSurface alpha .7) (`HeaderSection.kt:97-102`); 8.dp spacer; `BannerAdView()` (`HeaderSection.kt:104`); `GenresAndDescriptionSection`; 8.dp spacer; action `Row` SpaceEvenly (`HeaderSection.kt:109`).
- **Visual:** cover 200×250 8dp-rounded; typography Bold 20.sp title; subtitle bodyMedium alpha .7. Action buttons each `weight(1f)`, color `onSurface.copy(alpha=0.7f)`.
- **States:** n/a (data already loaded).
- **Interactions:** title long-press copies title (combinedClickable, `HeaderSection.kt:86-94`). Action buttons: Bookmark (`Icons.Default.BookmarkRemove`/`BookmarkBorder`, toggles `R.string.action_remove`/`action_bookmark`; if saved → `onMangaBookmark`, else `onRequestAddBookmark(true)`) (`HeaderSection.kt:110-116`); Schedule chip showing days-since-latest-chapter (`R.string.action_no_chapter_yet`/`action_today`/`action_yesterday`/`day_since_format`, inert onClick) (`HeaderSection.kt:118-131`); Download-all (`Icons.Default.Download`, `R.string.action_download_all`; if saved → `onDownloadClick`, else `onRequestAddBookmark(true)`) (`HeaderSection.kt:132-138`); Open-in-browser (`Icons.Default.Language`, `R.string.action_open_in_browser`, → `onOpenInWebView(manga.url, manga.api)`) (`HeaderSection.kt:139-145`).
- **Dialogs/sheets/snackbars:** Toast on title copy.
- **Forms & validation:** none.
- **Data/behavior:** `days = manga.chapters.firstOrNull()?.date?.daysSince()` (`HeaderSection.kt:118`). Cover request built per-source via `buildImageRequest`. Has a leftover `Log.i(...)` debug line (`HeaderSection.kt:69`).
- **Feature inventory:** cover, title (long-press copy), source/lang/status subtitle, banner ad, genres+description, 4 action buttons (bookmark, schedule/last-update, download-all, open-in-browser).
- **Citations:** `HeaderSection.kt:50-147`.

### Genres + Description Section
- **Entry/route:** `details/ui/components/GenresAndDescriptionSection.kt:41` `GenresAndDescriptionSection(genres, description, collapsedMaxGenres=4, collapsedMaxLines=4)`.
- **Layout & components:** `Column`; collapsed vs expanded description; `FlowRow` of `GenreChip`s with a `MoreGenresChip("+N more")` when collapsed and >4 genres (`GenresAndDescriptionSection.kt:50-65`).
- **Visual:** `CollapsedDescription`: centered bodyMedium, maxLines=4, ellipsis, with a vertical gradient fade (`Color.Transparent → background`) and an `ExpandMore` IconButton at bottom-center (24.dp) (`GenresAndDescriptionSection.kt:124-149`). `ExpandedDescription`: full text centered + `ExpandLess` IconButton, `animateContentSize()` (`GenresAndDescriptionSection.kt:68-77`). `GenreChip`: bordered (1.dp, onBackground alpha .5), RoundedCornerShape 6.dp, padding v8/h12, bodySmall onSurface alpha .7, maxLines 1 (`GenresAndDescriptionSection.kt:101-120`). `MoreGenresChip`: RoundedCornerShape 4.dp, border alpha .7 (`GenresAndDescriptionSection.kt:80-97`). FlowRow horizontal/vertical spacing 4/8.dp centered.
- **States:** expanded/collapsed (`expanded` bool, default false) (`GenresAndDescriptionSection.kt:47`).
- **Interactions:** tap description fade/ExpandMore icon → expand; tap MoreGenresChip → expand; ExpandLess → collapse.
- **Data/behavior:** visibleGenres = all if expanded or ≤4, else first 4 (`GenresAndDescriptionSection.kt:48`).
- **Feature inventory:** expandable description with gradient fade, genre chips, "+N more" overflow chip.
- **Citations:** `GenresAndDescriptionSection.kt:41-149`.

### Chapter list item
- **Entry/route:** `details/ui/screens/ChapterItem.kt:32` `ChapterItem(manga, chapter, chapters, isSaved, onRequestAddBookmark, onDownloadClick, onChapterClick)`. One per chapter inside DetailsContent LazyColumn.
- **Layout & components:** `Card` fillMaxWidth, padding h16/v8, clickable → `onChapterClick(chapter, manga, chapters)`, `shadow(elevation=4.dp, RoundedCornerShape 8.dp, ambient/spot = onSurface alpha .9)`, RoundedCornerShape 8.dp, card colors = background/onBackground (`ChapterItem.kt:46-72`). Inner `Row` padding 16.dp center-aligned: `Column(weight 1f)` with chapter number (`chapter.number.ifBlank { chapter.name }`, Bold) + relative date (`chapter.date?.toRelativeString(context)`, bodySmall onSurface alpha .7) (`ChapterItem.kt:73-84`); trailing `IconButton` download (`ChapterItem.kt:87-94`).
- **Visual:** elevated rounded card, bold number, dimmed subtitle. Download icon tint = primary if downloaded else onSurface alpha .7.
- **States:** downloaded vs not (`chapter.isDownloaded` → `Icons.Default.DownloadDone` else `Icons.Default.Download`) (`ChapterItem.kt:90-92`).
- **Interactions:** whole card click opens reader; download IconButton → if saved `onDownloadClick()` else `onRequestAddBookmark()` (`ChapterItem.kt:87`).
- **Data/behavior:** no per-chapter read/unread visual state; no progress; relative date string.
- **Feature inventory:** open chapter, per-chapter download, downloaded indicator. NOTE: no read/unread dimming, no multi-select, no bookmark-per-chapter here.
- **Citations:** `ChapterItem.kt:32-98`.

### Details dialogs
- **AdultConfirmationDialog** (`details/ui/components/dialogs/AdultConfirmationDialog.kt:28`): `AlertDialog`, header `R.string.adult_filter_removal_header` (20.sp Bold, `Color.Red.copy(0.8f)`); body = `ic_pluss18` icon (120.dp, red tint .65) + `R.string.adult_filter_removal_title` centered; confirm `R.string.close` (red), dismiss `R.string.cancel` (onBackground alpha .7); RoundedCornerShape 16.dp, surfaceContainerHigh. Both confirm and dismiss call `onDismiss()` (`AdultConfirmationDialog.kt:74-99`). NOTE: in `MangaDetailsScreen` the dialog's `onConfirm` advances to MStep1 but the dialog itself wires both buttons to `onDismiss` — the actual "confirm" path is driven from the parent (`MangaDetailsScreen.kt:76-84`).
- **MConfirmationDialog** (`details/ui/components/dialogs/MConfirmationDialog.kt:25`): `AlertDialog` showing one random meme image from `imgs1`/`imgs2` (240.dp, RoundedCornerShape 8.dp, FillBounds, via `rememberAsyncImagePainter`); confirm "Continue" shown only if `showContinue` (hardcoded English text); dismiss "Close" (gray); surfaceContainerHigh 16.dp.
- **ConfirmDialogClean** (`details/ui/components/dialogs/ConfirmDialog.kt:11`): generic `AlertDialog(title, text, confirmText="OK", onConfirm, onDismiss)`; dismiss label = `R.string.cancel`.
- **DialogState** enum (`details/domain/DialogState.kt:3`): `AdultWarning, MStep1, MStep2, None`.
- **Citations:** `AdultConfirmationDialog.kt:28-103`, `MConfirmationDialog.kt:25-92`, `ConfirmDialog.kt:11-25`, `DialogState.kt:3`.

---

## READER CLUSTER

### Reader Route (chapter data loader)
- **Entry/route:** `navigation/routes/ReadingScreenRoute.kt:43` `ReadingScreenRoute(navController, backStackEntry, sharedChaptersVm, historyViewModel)`. Reads `Screen.ChapterImagesFragment` args (`ReadingScreenRoute.kt:50`): `chapterUrl, mangaUrl, mangatitle, mangaImgUrl, chapterNumber, api, language, isDownload, paths, mangaId, chapterId, isHome`.
- **Layout & components:** state switch on `State<List<ReaderChapters>>` (`ReadingScreenRoute.kt:73`): Loading → centered `CircularProgressIndicator`; Error → optional 403 WebView dialog + centered error text (`R.string.failed_to_load` with message, GellixFontFamily 14.sp Bold, color error, maxLines 2 ellipsis) (`ReadingScreenRoute.kt:85-130`); Success → `ReaderScreen(...)` (`ReadingScreenRoute.kt:132-157`).
- **States:** loading / error (with 403 path) / success. If empty chapters list, `ReaderScreen` immediately calls `onBackPressed()` and returns (`ReaderScreen.kt:116-122`).
- **Interactions:** computes `startIndex` from matching `startingChapter.url` else 0 (`ReadingScreenRoute.kt:137-141`).
- **Data/behavior:** inserts history on entry: `historyViewModel.insertHistory(historyItem)` in `LaunchedEffect(args.chapterUrl)` (`ReadingScreenRoute.kt:57-59`). Chapter flow source: `getChaptersList(mangaUrl)` if `args.isHome` else `getChaptersByHistoryItemFlow(historyItem)` (`ReadingScreenRoute.kt:61-67`). 403 → `Handle403Error` (WebViewDialog) when `code == 403` (`ReadingScreenRoute.kt:88-105`). `initHistoryItem`/`initStartingChapter` build models from args (`ReadingScreenRoute.kt:160-191`).
- **Feature inventory:** history recording, chapter list resolution (home vs history), 403 recovery, start-index resume.
- **Citations:** `ReadingScreenRoute.kt:43-191`.

### Reader Screen (host, mode dispatcher, chrome)
- **Entry/route:** `reader/ui/screens/ReaderScreen.kt:103` `ReaderScreen(startIndex, mangaApi, chaptersList, readerViewModel, historyViewModel, sharedChaptersVm, openChapterInWebView, mangaUrl, onBackPressed)`.
- **Layout & components:** `Scaffold` → `Column` { weighted reader `Box` (tap toggles controls) + `BannerAdView()` at bottom } (`ReaderScreen.kt:352-619`). Reader `Box` branches on `readingMode` (`ReaderScreen.kt:372-525`): DEFAULT/VERTICAL → `VerticalReadingMode` (VerticalPager); LEFT_TO_RIGHT/RIGHT_TO_LEFT → `HorizontalReadingMode` (HorizontalPager, `reverseLayout = (mode==RIGHT_TO_LEFT)`); WEBTOON → `WebToonReadingMode` (gapless LazyColumn); CONTINUOUS_VERTICAL → `ContinuousVerticalReadingMode` (LazyColumn). `ControlOverlay` over the reader; first-chapter loading spinner overlay; `ReadingModeDialog`; `Handle403Error` WebView dialog.
- **Visual:** background `MaterialTheme.colorScheme.background`. System bars hidden via `HideSystemBars()` (hides navigationBars on enter, shows on dispose) (`ReaderScreen.kt:138`, `767-786`). Controls auto-hide after 3 s (`LaunchedEffect(showControls){ delay(3000); showControls=false }`) (`ReaderScreen.kt:345-350`). First-chapter loading shows centered primary `CircularProgressIndicator` over whole screen (`ReaderScreen.kt:623-632`).
- **States:** per-chapter loading via `loadingChapters: Set<Int>`; `isCurrentChapLoading`/`isFirstChapterLoading` derived (`ReaderScreen.kt:303-311`). Per-page Loading/Error/Success handled inside each reading-mode item composable. 403 overlay: scans current chapter items for `ErrorOverlay.errorCode == 403` and triggers `showWebViewDialog` (`ReaderScreen.kt:188-205`). Empty list → back.
- **Interactions:**
  - Tap anywhere (no-indication clickable) toggles `showControls` (`ReaderScreen.kt:364-367`); each reading-mode also forwards `onTap`.
  - Pinch-zoom: all modes wrap content in `zoomableWithScroll(rememberZoomState(), onTap=...)` (engawapg zoomable lib) — see each mode.
  - Swipe: HorizontalPager (LTR/RTL with reverseLayout) / VerticalPager (DEFAULT/VERTICAL) / vertical scroll (WEBTOON, CONTINUOUS_VERTICAL).
  - Auto-load next chapter on reaching last page (paged: `LaunchedEffect(pagerState.currentPage)` when `== allReaderItems.lastIndex`, `ReaderScreen.kt:257-266`); continuous/webtoon load on scroll-to-end.
  - Chapter index tracking: `snapshotFlow` on pager.currentPage / listState.firstVisibleItemIndex → `setCurrentChapterIndex` (`ReaderScreen.kt:268-300`).
  - Scroll-to-chapter: `shouldScrollToChapter` triggers pager/list scroll to first page of `currentChapterIndex`, retrying every 50 ms until found (`ReaderScreen.kt:231-255`).
- **Dialogs/sheets/snackbars:** `ReadingModeDialog` (mode picker) on settings tap (`ReaderScreen.kt:635-648`); `Handle403Error` WebViewDialog with reload-after-dismiss (delay 500 + goToChapter) (`ReaderScreen.kt:649-668`); Toast "You should add the manga to Library first" when bookmarking a chapter with `chapterId==0L` (`ReaderScreen.kt:597`).
- **Forms & validation:** none.
- **Data/behavior:**
  - VM init in `LaunchedEffect(Unit)`: `readerViewModel.initialize(startIndex, chaptersList, mangaApi, screenWidthPx, context)` (`ReaderScreen.kt:172-180`).
  - Reading-session timer: `ReadingTimeRecorder` observes lifecycle ON_RESUME→`onScreenResume()` / ON_STOP→`onScreenPause()` (`ReaderScreen.kt:162`, `672-697`); VM delegates to `statisticsRepo.startReadingSession()`/`endReadingSession()` (`ReaderViewModel.kt:562-570`).
  - History: on first compose fetch `historyId` via `getLatestHistoryIdByManga(mangaUrl).first()` (`ReaderScreen.kt:166-168`); on chapter change `historyViewModel.updateHistoryItem(...)` (`ReaderScreen.kt:217-225`); `markChapterAsRead(...)` on next/prev/load-next (`ReaderScreen.kt:699-704`).
  - Bookmark: `observeBookmark(chapterId)` on chapter change (`ReaderScreen.kt:226-228`); `toggleChapterBookmark(chapterId)` on bookmark tap (`ReaderScreen.kt:592-599`).
  - Share: `ScreenshotUtils.captureAndShare(activity, rootView, hideControls, showControls)` on Dispatchers.Default (`ReaderScreen.kt:601-610`).
  - Reading-mode persistence: VM `init` collects `settingsRepo.readingModeFlow` → `ReadingMode.valueOf` (`ReaderViewModel.kt:87-95`); `setReadingMode` persists via `settingsRepo.setReadingMode(mode.name)` (`ReaderViewModel.kt:555-560`).
  - CBZ cleanup on `onCleared` (`ReaderViewModel.kt:651-667`); `clearSavedStateHandle()` on dispose (`ReaderScreen.kt:132-137`).
  - Navigation: next/prev chapter via `goToChapter(index,...)` (clears list, reloads) (`ReaderScreen.kt:549-585`); open-in-webview navigates to `Screen.WebView`.
- **Feature inventory (EVERY affordance):** tap-to-toggle chrome; auto-hide chrome (3 s); top bar (manga title + chapter number + back + menu(dots, inert)); seekbar/scrubber with prev/next-page; bottom action bar (settings/reading-mode, bookmark, share); reading-mode picker dialog; pinch-zoom; swipe paging (LTR/RTL/vertical); continuous & webtoon scroll; next-chapter overlay card; per-page retry + open-in-webview; OOM fallback tap-to-open-webview; large-image compression; download-% progress indicator; reading-session statistics; history recording + resume start-index; chapter bookmark; share screenshot; 403 recovery; banner ad; next/prev chapter buttons; ProManga streaming loader.
- **Citations:** `ReaderScreen.kt:103-795`, `ReaderViewModel.kt:1-699`.

### Reading mode — DEFAULT / VERTICAL (VerticalPager)
- **Component:** `reader/ui/reading_modes/VerticalReadingMode.kt:22`.
- **Layout/behavior:** `VerticalPager` over `allReaderItems`, `zoomableWithScroll` + onTap (`VerticalReadingMode.kt:36-44`). Per page: `ImagePage → PagerImageItem`; `NextChapterOverlay → NextChapterCard` (onGoToNext → `loadingNextChapter`); `ErrorOverlay → errorCard`; null → `LoadingScreen()` (`VerticalReadingMode.kt:45-85`).
- **NOTE:** DEFAULT is mapped to a *vertical paged* layout here (not continuous), despite `DEFAULT` icon = continuous-vertical in `ReadingMode.kt:12`. CONTINUOUS_VERTICAL is the actual continuous mode.
- **Citations:** `VerticalReadingMode.kt:22-87`, `ReaderScreen.kt:373-415`.

### Reading mode — LEFT_TO_RIGHT / RIGHT_TO_LEFT (HorizontalPager)
- **Component:** `reader/ui/reading_modes/HorizontalReadingMode.kt:24`.
- **Layout/behavior:** `HorizontalPager(reverseLayout=reverseLayout)`, `zoomableWithScroll`+onTap (`HorizontalReadingMode.kt:43-50`). Same per-item dispatch as vertical; `NextChapterCard` onGoToNext launches `loadingNextChapter` in scope; else→`LoadingScreen()` (`HorizontalReadingMode.kt:54-94`). `reverseLayout` set by parent for RIGHT_TO_LEFT (`ReaderScreen.kt:435`).
- **States:** if `isCurrentChapLoading` also overlays `LoadingScreen()` (`HorizontalReadingMode.kt:92-94`).
- **Citations:** `HorizontalReadingMode.kt:24-96`, `ReaderScreen.kt:417-455`.

### Reading mode — WEBTOON (gapless LazyColumn)
- **Component:** `reader/ui/reading_modes/WebToonReadingMode.kt:65` → `WebToonReader` (`:111`).
- **Layout/behavior:** `LazyColumn` with `zoomableWithScroll`+onTap, background = colorScheme.background, keyed by `"${chapterIndex}-${request.data}-${index}"` (`WebToonReadingMode.kt:130-147`). `WebToonImageItem` uses `ContentScale.FillWidth`, `fillMaxWidth().wrapContentHeight()`, `drawFallbackOnOOM(ic_image_broken)` (`WebToonReadingMode.kt:200-265`). Loads next chapter when `listState.isScrolledToTheEnd()` (guarded by `alreadyLoading`) (`WebToonReadingMode.kt:94-106`).
- **States (per page):** Loading → `LoadingImagePlaceholder(progressState)` reserving `minHeight=screenHeightDb`; Error → `ImageLoadError(onRetry=painter.restart, onOpenInWebView)`; Success → direct `Image` if `img.size <= 99 MB` else `CompressedImageHandler`; else fallback placeholder (minHeight 600.dp) (`WebToonReadingMode.kt:227-293`).
- **Download progress:** `LoadingImagePlaceholder` renders Coil download % via `ProgressManager.getProgressFlow(request.data)` — Idle=spinner; Loading=ring with `formattedPercent()` + `formattedSize()`; Completed=spinner; Failed=`R.string.failed_to_load` (`WebToonReadingMode.kt:357-414`).
- **Compression:** images >99 MB → `CompressedImageHandler` reserves aspect-ratio height, calls `onStartImageCompression`; shows `CompressionLoadingPlaceholder` (`R.string.notification_compressing_images`) or `ImageLoadError` on failure (`WebToonReadingMode.kt:296-355`).
- **Citations:** `WebToonReadingMode.kt:65-435`, `ReaderScreen.kt:457-485`.

### Reading mode — CONTINUOUS_VERTICAL (LazyColumn)
- **Component:** `reader/ui/reading_modes/ContinuousVerticalReadingMode.kt:54` → `ContinuousVerticalReader` (`:98`).
- **Layout/behavior:** `LazyColumn` with `zoomableWithScroll`+onTap, keyed by `"${chapterIndex}-${request.data}-${index}"`; loads next chapter on scroll-to-end (`ContinuousVerticalReadingMode.kt:82-93`, `121-193`). `isCurrentChapLoading` appends a full-screen spinner item (`ContinuousVerticalReadingMode.kt:177-191`).
- **States (per page):** Loading → spinner (minHeight screenHeightDb); Error → `ImageLoadError(retry/openWebView)`; Success → `Image` (FillWidth/wrapContentHeight + OOM fallback) if ≤99 MB else `ContinuousVerticalCompressedImageHandler`; else spinner 600.dp (`ContinuousVerticalReadingMode.kt:230-301`). `NextChapterCard` onGoToNext is a no-op (scroll-end effect loads next) (`ContinuousVerticalReadingMode.kt:155-165`). NOTE: hardcoded English "Compressing image..." here (`ContinuousVerticalReadingMode.kt:380`).
- **Citations:** `ContinuousVerticalReadingMode.kt:54-386`, `ReaderScreen.kt:486-523`.

### Reader page item (paged) + Zoomable
- **PagerImageItem** (`reader/ui/reading_modes/PagerImageItem.kt:38`): compressed-painter fast path; else `rememberAsyncImagePainter`; Loading→spinner; Error→`ImageLoadError`; Success→`SubcomposeAsyncImage` (`ContentScale` default fillMaxSize + `drawFallbackOnOOM`) if ≤99 MB else `PagerCompressedImageHandler` (`PagerImageItem.kt:49-141`). Compression handler reserves aspect-ratio size, shows placeholder/error; hardcoded English "Compressing image..." (`PagerImageItem.kt:214`).
- **ZoomableImage** (`reader/ui/reading_modes/ZoomableImage.kt:21`): wraps `me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage`; Loading/Error/else reserve `heightIn(min=600.dp)` with spinner; Success → `ZoomableAsyncImage(onClick → onTap)` (`ZoomableImage.kt:20-87`). NOTE: this is a telephoto-based variant; the live reading modes use engawapg `zoomableWithScroll` instead — ZoomableImage appears unused by the four modes (INFERRED, not referenced in the mode files read).
- **Citations:** `PagerImageItem.kt:38-220`, `ZoomableImage.kt:21-87`.

### Reader chrome — ControlOverlay (HUD)
- **Entry/route:** `reader/ui/components/ControlOverlay.kt:37` `ControlOverlay(currentChapter, show, currentPage, pageCount, isBookmarked, hasNext, hasPrevious, onPrevious, onNext, onPageChange, onBackPressed, onSettings, onBookmark, onShare)`.
- **Layout & components:** `Box` fillMaxSize; top `TopAppBar` (AnimatedVisibility slide+fade from top) + bottom `Column` (slide+fade from bottom) containing `SeekBarContainer` + 8.dp spacer + `BottomActionBar` (`ControlOverlay.kt:53-101`).
- **Visual:** top/bottom bars 56.dp height, `background.copy(alpha=0.8f)`. Top bar: back icon (`ic_back_`), title `titleLarge` (1 line ellipsis) + chapter number `bodySmall` alpha .75, menu icon (`dots`). Animations: enter `fadeIn(250)+slideInVertically(250)`, exit `fadeOut(200)+slideOutVertically(200)` (`ControlOverlay.kt:54-72`).
- **BottomActionBar** (`ControlOverlay.kt:158-196`): 3 weighted IconButtons — Settings (`ic_reader_setting`), Bookmark (`Icons.Filled.Bookmark`/`Outlined.BookmarkBorder`), Share (`ic_panal_shera`).
- **Interactions:** back, menu(dots — inert `/* ... */`), seekbar prev/next + seek, settings (opens mode dialog), bookmark, share.
- **Feature inventory:** top bar (title+number+back+menu), page seekbar, settings/bookmark/share action bar. NOTE: menu (dots) button is inert.
- **Citations:** `ControlOverlay.kt:37-197`.

### Reader chrome — SeekBarContainer (page scrubber)
- **Entry/route:** `reader/ui/components/SeekBarContainer.kt:26` `SeekBarContainer(progress, total, hasNext, hasPrevious, onPrevious, onNext, onSeekChange)`.
- **Layout & components:** `Row` of three rounded (RoundedCornerShape 50) `Card`s (`background.copy(0.8f)`): left = "next" IconButton (`ic_previous`, enabled by `hasNext`); middle = current-page text (`(progress+1).toInt()`) + `Slider(value=progress, range 0..total-1, steps=total-1)` + total text; right = "previous" IconButton (`ic_next`, enabled by `hasPrevious`) (`SeekBarContainer.kt:38-131`).
- **Visual:** disabled tint via `ContentAlpha.disabled`. Text bodySmall onBackground.
- **Interactions:** slider drag → `onSeekChange(round)`; prev/next page buttons.
- **NOTE:** icon/label semantics are swapped — left card labeled "Next" uses `ic_previous` and fires `onNext`; right card labeled "Previous" uses `ic_next` and fires `onPrevious` (accommodates a visual layout; carry intent, not labels). Page number shows `progress+1` (1-based).
- **Citations:** `SeekBarContainer.kt:26-132`.

### Reader chrome — Reading-mode picker
- **ReadingModeDialog** (`reader/ui/components/reading_mode_dialog/ReadingModeDialog.kt:42`): `Dialog` → `Surface` (surfaceContainerHigh, RoundedCornerShape 16.dp, tonalElevation 8.dp). Header "Reading mode" (hardcoded English, headlineSmall Bold). Scrollable `ReadingModeChips` + `Divider` + footer Row: OutlinedButton "Revert" (`R.string.but_revert`, resets selection + dismiss) + `Button` "Apply" with check icon (`R.string.but_apply`, calls `onModeSelected(selected)` + `onApply`) (`ReadingModeDialog.kt:42-155`). Local `selected` state defaults to `currentMode`. Apply colors: container/content = onBackground (NOTE: text color = background for contrast).
- **ReadingModeChips** (`reader/ui/components/reading_mode_dialog/ReadingModeChips.kt:29`): `FlowColumn` of `FilterChip`s (one per `ReadingMode`), each with `mode.iconRes` + `stringResource(mode.titleRes)`; selected → primary container/onPrimary, RoundedCornerShape 18.dp, height 40.dp (`ReadingModeChips.kt:29-101`).
- **ReadingModeSelector** (`ReadingModeDialog.kt:158`): alternate OutlinedButton list variant (uses `mode.name`, not localized) — appears unused by the dialog (INFERRED).
- **ReadingMode enum** (`reader/data/ReadingMode.kt:7`): 6 modes DEFAULT, RIGHT_TO_LEFT, LEFT_TO_RIGHT, VERTICAL, WEBTOON, CONTINUOUS_VERTICAL, each with `iconRes` + `titleRes` (`R.string.reading_mode_*`). `ReadingMode.isPaged` = LTR/RTL/VERTICAL/DEFAULT (`reader/data/isPaged.kt:6-12`).
- **Citations:** `ReadingModeDialog.kt:42-197`, `ReadingModeChips.kt:29-101`, `ReadingMode.kt:7-35`, `isPaged.kt:6-12`.

### Reader — Next-chapter overlay
- **NextChapterCard** (`reader/ui/components/NextChapterCard.kt:24`): full-screen Box `surface.copy(0.95f)`, clickable→`onGoToNext`. If `isCurrentChapLoading` → centered primary `CircularProgressIndicator`; else shows `R.string.you_are_in` + `"current chapter"` (titleLarge primary) + `R.string.going_to` + `"next chapter"` (titleLarge secondary) (`NextChapterCard.kt:32-78`).
- **Citations:** `NextChapterCard.kt:24-78`.

### Reader — per-page error & OOM handling
- **ImageLoadError** (`reader/ui/components/ImageLoadError.kt:21`): centered message (`R.string.failed_to_load_image` default) + Row of two `BorderedPrimaryButton`s: Retry (`R.string.retry`) and Open-in-browser (`R.string.action_open_in_browser`) (`ImageLoadError.kt:21-57`).
- **errorCard** (`reader/ui/components/errorCard.kt:21`): full-screen `surface.copy(0.95f)`; shows "You are in" (hardcoded English) + chapter number (primary) + `errorMassage` (error color, centered) (`errorCard.kt:21-60`). Used for terminal "No Next Chapter" + fetch errors.
- **drawFallbackOnOOM** (`reader/ui/components/drawFallbackOnOOM.kt:34`): `Modifier` extension that catches `drawContent()` failure (OOM); draws DarkGray bg + `ic_image_broken` icon (62.dp) + tappable text `R.string.image_too_large_tap_here_to_open_the_chapter_in_webview` (yami_manga_primary color, 14.sp bold); tap inside text bounds → `onOpenInWebView()` (`drawFallbackOnOOM.kt:34-144`).
- **Citations:** `ImageLoadError.kt:21-57`, `errorCard.kt:21-60`, `drawFallbackOnOOM.kt:34-144`.

### Reader — 403 / Cloudflare handling
- Route-level: `ReadingScreenRoute` shows `Handle403Error` WebViewDialog when `State.Error.code == 403` (`ReadingScreenRoute.kt:88-105`).
- Screen-level: per-page `ErrorOverlay` with `errorCode==403` triggers in-reader `Handle403Error` WebViewDialog; on dismiss waits 500 ms then `goToChapter(currentChapterIndex)` to reload (`ReaderScreen.kt:188-205`, `649-668`).
- VM: `loadChapter`/`loadChapterStreaming` translate `State.Error` (with `.code`) into `ReaderItem.ErrorOverlay(errorCode=state.code)` (`ReaderViewModel.kt:232-263`, `427-460`).
- `Handle403Error` util: `<T>` overload monitors a State (maxDismissals=1) → `WebViewDialog`; second overload directly shows `WebViewDialog(api, chapterUrl, onDismiss)` (`core/util/Handle403Error.kt:22-63`).
- **Citations:** `Handle403Error.kt:22-63`, `ReaderScreen.kt:188-205,649-668`, `ReadingScreenRoute.kt:85-130`.

### Reader ViewModel — data/behavior
- **Loading:** `loadChapter` routes ProManga (`MangaSource.PROCHAN.API`) to `loadChapterStreaming` (incremental URL adds), else normal collect-first (`ReaderViewModel.kt:130-271`, `277-468`). Downloaded chapters read from `localImagePaths`; single `.cbz` → `cbzManager.extractImagesFromCbz(...)` (`ReaderViewModel.kt:169-186`). Online: `sourcesRepository.getRepoByName(mangaApi).fetchChapterDataF(url)`; image requests via per-source `buildImageRequest(context, url, screenWidthPx)` (`ReaderViewModel.kt:188-201`).
- **Item model** (`reader/data/ReaderItem.kt`): sealed `ImagePage(request, chapterIndex, isCompressed, compressedPainter)`, `NextChapterOverlay(current, next)`, `ErrorOverlay(current, errorCode, errorMassage)`.
- **Multi-chapter:** `goToNextChapter` / `goToChapter` (clears list, reloads) / `goToPreviousChapter` (rewinds loaded list) (`ReaderViewModel.kt:473-536`).
- **Bookmark:** `observeBookmark` collects `libraryRepository.isChapterBookmarkedFlow`; `toggleChapterBookmark` (`ReaderViewModel.kt:538-549`).
- **Compression:** `startImageCompression` scales bitmaps over 99 MB threshold via `compressImageToSizeOptimized` (target width = screenWidthPx) (`ReaderViewModel.kt:580-698`).
- **Statistics:** `onScreenResume`/`onScreenPause` → `statisticsRepo.start/endReadingSession()` (`ReaderViewModel.kt:562-570`).
- **CompressionState** model: `isCompressing/error/isCompleted` (`reader/data/CompressionState.kt:3`).
- **Citations:** `ReaderViewModel.kt:42-699`, `ReaderItem.kt:7-26`, `CompressionState.kt:3`.

---

### Cluster notes
- **Reading-mode mapping quirk:** `DEFAULT` is rendered by `VerticalReadingMode` (a *paged* VerticalPager), while continuous scroll is `CONTINUOUS_VERTICAL`. `isPaged` includes DEFAULT/VERTICAL/LTR/RTL (`isPaged.kt`). KMP parity must preserve this split.
- **Two zoom libraries present:** live modes use engawapg `net.engawapg.lib.zoomable.zoomableWithScroll`; `ZoomableImage.kt` uses saket telephoto `ZoomableAsyncImage`. ZoomableImage appears orphaned (not called by the 4 mode composables) — (INFERRED).
- **Hardcoded English strings (not localized):** ReadingModeDialog header "Reading mode" (`ReadingModeDialog.kt:68`); errorCard "You are in" (`errorCard.kt:39`); "Compressing image..." in `PagerImageItem.kt:214` and `ContinuousVerticalReadingMode.kt:380` (webtoon uses `R.string.notification_compressing_images`); MConfirmationDialog "Continue"/"Close" (`MConfirmationDialog.kt:71,83`); ConfirmDialogClean default `confirmText="OK"`; ReaderScreen Toast "You should add the manga to Library first" (`ReaderScreen.kt:597`); ControlOverlay contentDescriptions ("Back"/"Menu"/etc).
- **Leftover debug logs:** `Log.i("asdasklsfsakdfsadfsad", ...)` in HeaderSection (`HeaderSection.kt:69`); various `Log` imports/calls across reader files.
- **SeekBar label/icon inversion:** "Next" card uses `ic_previous`+`onNext`; "Previous" card uses `ic_next`+`onPrevious` (`SeekBarContainer.kt:50-130`) — preserve callback wiring, not literal labels.
- **Inert affordances:** ControlOverlay top-bar menu (dots) is a no-op (`ControlOverlay.kt:64`); HeaderSection Schedule/last-update button is inert (`HeaderSection.kt:128`).
- **Banner ads** appear in HeaderSection (`HeaderSection.kt:104`) and below the reader (`ReaderScreen.kt:617`) — KMP rework typically drops ads.
- **First-time-bookmark UX:** add-to-library confirm dialog shown once, gated by `hasShownRemoveBookMarkFlow` from HomeViewModel; download/bookmark on an unsaved manga forces add-to-library first.
- **Required drawable assets (must exist in KMP):** `ic_reader_continuous_vertical_24dp`, `ic_reader_ltr`, `ic_reader_rtl_24dp`, `ic_reader_vertical_24dp`, `ic_reader_webtoon_24dp` (mode icons — all present in old res `drawable/`), `ic_back_`, `dots`, `ic_reader_setting`, `ic_panal_shera`, `ic_previous`, `ic_next`, `ic_image_broken`, `ic_pluss18`; color `yami_manga_primary`. NOTE: `ReadingMode.DEFAULT` reuses `ic_reader_continuous_vertical_24dp`; there is also an unused `ic_reader_horizontal_`/`ic_reader_ltr_24dp`/`ic_reader_rtl` set in res.
- **403 recovery differs by surface:** Details retries after 1000 ms; Reader reloads chapter after 500 ms. Both via `WebViewDialog`.
- **Resume position:** route resolves `startIndex` by chapter URL match; VM scrolls to first page of current chapter. No per-page resume within a chapter beyond pager initial page 0 (INFERRED — pager `initialPage=0`, scroll driven by `shouldScrollToChapter`).
- **No read/unread chapter state** in `ChapterItem` (only downloaded indicator); no chapter sort/filter UI exists in the OLD details screen — chapters render in `manga.chapters` order. (Scope mentioned "sort/filter sheets" but none exist in this details feature; the filter/sort sheets in the codebase belong to Home/Search, not Details.)


---

# CLUSTER: downloads_sources

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


---

# CLUSTER: settings_theme_language

# OLD Native Android Audit — Settings + Theme-Selection + Language Cluster

Source root: `D:/yami manga/yami-manga-apk-main/app/src/main/java/me/manga/yamiapk/`
(package is `me.manga.kira`; folder is `me/manga/yami/`). READ-ONLY audit. Cite `file:line`.

This cluster covers: **Settings hub** (every row + action), **Theme selection** (onboarding picker + permission), **Language selection** (list + request dialog). Shared design-system components used by all three are documented inline and summarized in **Cluster notes**.

---

### SettingsScreen
- **Entry/route:** `Screen.Setting` (object) → `composable<Screen.Setting>` in `NavGraphV2.kt:514` → `SettingsRoute(navController, backStackEntry)` (`NavGraphV2.kt:516`). `SideEffect { onBottomBarVisibleChange(true) }` (`NavGraphV2.kt:515`) — Settings is a **bottom-bar tab** (bottom nav stays visible). The old `SettingsRoute` adapter file was not found under `app/.../navigation/routes/SettingsRoute.kt` (only imported at `NavGraphV2.kt:42`); the screen composable is `SettingsScreen.kt`. (INFERRED: `SettingsRoute` is a thin wrapper that calls `SettingsScreen(navController)`.)
- **Layout & components:** `Scaffold` (Material **M2** `androidx.compose.material.Scaffold`) with `backgroundColor = colorScheme.background` and a `SnackbarHost(snackbarHostState)` (M3) (`SettingsScreen.kt:101-104`). Body is a single `LazyColumn`, `fillMaxSize().background(colorScheme.background).padding(paddingValues).padding(horizontal=16.dp, vertical=8.dp)`, `horizontalAlignment = CenterHorizontally` (`SettingsScreen.kt:105-112`). Content order:
  1. **Header image** — `Image(painterResource(R.drawable.ic_launcher_foreground))`, `size(250.dp).padding(vertical=24.dp)`, contentDescription `"Header Icon"` (`SettingsScreen.kt:113-121`).
  2. **General settings group** — preceded by a `Divider(Color.Gray.copy(alpha=0.3f))` + `Spacer(24.dp)`, wrapped in `ItemsGroup{}` card (`SettingsScreen.kt:124-186`).
  3. **Download/CBZ group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:189-240`).
  4. **Navigation group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:244-279`).
  5. **Other group** — `Spacer(24.dp)` + `ItemsGroup{}` (`SettingsScreen.kt:282-311`).
  - `ItemsGroup` (`ItemsGroup.kt:16-29`) = `Column.fillMaxWidth().background(color=surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal=16.dp, vertical=8.dp)`. Default container color `surfaceContainerHigh`.
  - Inner rows separated by `Divider(color = colorScheme.background.copy(alpha=0.8f))`.
- **Visual:** group cards are 16.dp-rounded `surfaceContainerHigh` panels; outer 16.dp horizontal padding; 24.dp spacers between groups. Header icon 250.dp. Switch rows (`SwitchItem`) use 14.sp title + 12.sp description (alpha 0.5). Nav rows (`SettingsNavigationItem`) use 14.sp title + auto-sized 12→6.sp description (alpha 0.8), 24.dp leading icon + 16.dp gap, trailing `KeyboardArrowRight` chevron. Section dividers are nearly-invisible (background-colored, alpha 0.8).
- **States:** No loading/empty/error skeleton for the screen — it is a static config list. **Cache size** has a transient state: starts as `R.string.calculating` ("Calculating…", `strings.xml:141`), then recomputed off `Dispatchers.IO` on init and after clear (`SettingsViewModel.kt:39,42-45,72-74`). **CBZ conversion** has its own loading/success/error states surfaced via `CbzConversionDialog` (see Dialogs). All toggles are reactive via `collectAsStateWithLifecycle`.
- **Interactions:**
  - Switches: `downloadedOnly`, `incognito`, `followSystem`, `darkMode` (conditional), `pureBlack`, CBZ `useCbz`, plus an admin-only `Testing Mode` switch.
  - Nav rows clickable → navigate; `clearCache` row triggers IO deletion; `feedback` row opens `FeedbackDialog`; `reading mode` row opens `ReadingModeDialog`; `Help` row is **inert** (no `onClick`, `SettingsScreen.kt:307-311`).
  - "Start conversion" `Button` (CBZ) → `cbzViewModel.startConversion()`; disabled while `conversionProgress.isConverting`, shows inline spinner + "Converting".
  - No explicit animations beyond default Compose/ripple and the `AutoSubtitleText` auto-size.
- **Dialogs/sheets/snackbars:**
  - **FeedbackDialog** (`SettingsScreen.kt:316-350`): header `R.string.request_feature_bug`, field label `R.string.enter_your_feedback`; on submit → `complaintViewModel.submit(type, displayName, body, onSuccess, onError)`; success → snackbar `R.string.request_submitted_successfully` (Short); error → snackbar `R.string.request_failed` w/ actionLabel `R.string.retry` (Long).
  - **ReadingModeDialog** (`SettingsScreen.kt:352-365`): driven by `showReadingModeDialog`; `currentMode` from `chaptersViewModel.readingMode`; `onModeSelected → chaptersViewModel.setReadingMode`; apply/dismiss both close.
  - **Adult/M dialog chain** (`dialogState`: `AdultWarning → MStep1 → MStep2 → None`, `SettingsScreen.kt:367-406`) using `AdultConfirmationDialog` + `MConfirmationDialog(images = imgs1/imgs2)`. NOTE: `dialogState` is initialized to `None` and there is **no UI affordance in this file that sets it to `AdultWarning`** (only the admin `Testing Mode` and `Admin.isAdmin` complaint-route branch reference admin). (INFERRED: this dialog chain is effectively dead/unreachable from Settings as written, or triggered elsewhere; flagged.)
  - **CbzConversionDialog** (`SettingsScreen.kt:408-417`): always composed; self-gates on `isConverting || error != null || successMessage != null`; dismiss either stops conversion (if running) or clears error.
  - **Snackbar** host is M3 `SnackbarHostState`.
- **Forms & validation:** Only the FeedbackDialog has a form (category dropdown + min-5-char body, ≤500). See FeedbackDialog section.
- **Data/behavior:**
  - VM: `SettingsViewModel` (`@HiltViewModel`, `AndroidViewModel`) injects `SettingsRepository` + `@ApplicationContext`.
  - **Theme prefs** persist via `SharedPrefsHelper` (NOT DataStore): `KEY_THEME_MODE="ThemeMode"`, `KEY_THEME_SYSTEM="ThemeSystem"`, `KEY_PURE_BLACK="PureBlack"` (`StorageKeys.kt:15,16,18`; `SettingsRepository.kt:36-78`).
  - **General prefs** persist via DataStore (`DataStoreHelper`): `downloadedOnlyFlow`, `incognitoFlow`, `readingModeFlow`, `languageFlow` (`SettingsRepository.kt:23-34`).
  - Defaults: `darkMode` falls back to **system uiMode night-mask** when key absent (`SettingsRepository.kt:46-54`); `pureBlack` default **true** (`:57`); `followSystem` default **true** (`:61`). `downloadedOnly`/`incognito` UI `initial = true` (`SettingsScreen.kt:87,88`).
  - **Cache:** `clearLargeCache()` deletes all files >1 MB (`ONE_MB = 1024*1024L`, `ONE_MB.kt:3`) in `cacheDir` + `externalCacheDir`, prunes empty dirs, then recomputes size (`SettingsViewModel.kt:63-69`; `SettingsRepository.kt:83-94`). Size formatting via `formatSize` → `R.string.gigabytes`/`megabytes`/`kilobytes`/`bytes` (`SettingsRepository.kt:127-137`). Displayed as `R.string.cache_used` ("Used: ") + size (`SettingsScreen.kt:286`).
  - Navigation targets: Complaint (`Screen.ComplaintAdmin` if `Admin.isAdmin` else `Screen.Complaint`), `Screen.Statistics`, `Screen.LanguageScreen`, `Screen.DownloadsScreen`, `Screen.AboutScreen`.
  - Permissions: none directly (notification permission lives in Theme onboarding).
- **Feature inventory (EVERY row, top→bottom):**
  - *General group:*
    1. **Downloaded only** — `SwitchItem`, title `downloaded_only_title`, desc `downloaded_only_desc`, icon `Icons.Default.CloudOff`, bound `downloadedOnly` ↔ `setDownloadedOnly` (`:129-135`).
    2. **Incognito mode** — title `incognito_mode_title`, desc `incognito_mode_desc`, icon `R.drawable.incognito_svgrepo_com`, bound `incognito` ↔ `setIncognito` (`:138-144`).
    3. **Follow system theme** — title `system_theme`, desc `follow_system_theme`, icon `R.drawable.switchthemes`, bound `isFollowSystem` ↔ `toggleFollowSystem` (`:146-152`).
    4. **Dark mode** — *gated on `!isFollowSystem`* (`:154`); title `theme_title`, desc dynamic `theme_dark`/`theme_light`, icon `R.drawable.ic_day_night`, bound `themeMode` ↔ `toggleDarkMode` (`:156-162`).
    5. **Pure black mode** — title `pure_black_mode_title` (no description), icon `Icons.Outlined.DarkMode`, bound `pureBlack` ↔ `togglePureBlack` (`:166-171`).
    6. **Testing Mode** — *admin-only* (`Admin.isAdmin`), hardcoded title "Testing Mode", red tint `Color.Red.copy(0.5f)`, icon `R.drawable.ic_plus_18`, bound `Admin.testingMode` (`:173-184`).
  - *Download/CBZ group:*
    7. **Use Yami compressor** — `SwitchItem` (no icon), title `use_yami_compressor`, desc `use_yami_compressor_to_reduce_chapter_size...`, bound `useCbz` ↔ `cbzViewModel.setUseCbzFormat` (`:195-200`).
    8. **Compress existing downloads** — *visible only if `useCbz`* (`:202`): `Divider` + title `compress_existing_downloads` (titleMedium) + body `compress_all_previously_downloaded_chapters...` (bodySmall, onSurfaceVariant) + full-width `Button` → `startConversion()` (disabled while converting; spinner + "Converting" / else "Start conversion") (`:202-237`).
  - *Navigation group:*
    9. **Feedbacks & complaints** — `SettingsNavigationItem`, title `feedbacks_and_complaints`, icon `R.drawable.ic_complaint` → `Screen.ComplaintAdmin`/`Screen.Complaint` (`:247-256`).
    10. **Default reading mode** — title `default_reading_mode`, icon `R.drawable.ic_reader_setting` → opens `ReadingModeDialog` (`:258-263`).
    11. **Statistics** — title `statistics`, icon `Icons.Outlined.QueryStats` → `Screen.Statistics` (`:265-270`).
    12. **App language** — title `app_language`, icon `Icons.Outlined.Language` → `Screen.LanguageScreen` (`:272-274`).
    13. **Downloads** — title `downloads`, icon `Icons.Outlined.Download` → `Screen.DownloadsScreen` (`:276-278`).
  - *Other group:*
    14. **Clear cache** — title `clear_cache`, desc `cache_used` + " $cacheSize", icon `R.drawable.cache_cleaner` → `clearLargeCache()` (`:284-290`).
    15. **Request feature / report bug** — title `request_feature_bug_title`, desc `request_feature_bug_desc`, icon `Icons.AutoMirrored.Outlined.Message` → opens FeedbackDialog (`:293-297`).
    16. **About** — title `about`, desc `app_information_and_updates...`, icon `Icons.Outlined.Info` → `Screen.AboutScreen` (`:299-303`).
    17. **Help** — title `help`, icon `Icons.AutoMirrored.Outlined.Help`, **no onClick (inert row)** (`:307-311`).
- **Citations:** `SettingsScreen.kt:70-419`; `SettingsViewModel.kt:1-83`; `SettingsRepository.kt:1-138`; `SettingsNavigationItem.kt:29-89`; `SwitchItem.kt:28-73`; `ItemsGroup.kt:16-29`; `ONE_MB.kt:3`; `NavGraphV2.kt:42,514-518`; `StorageKeys.kt:15,16,18`; `strings.xml:134,141,144,145,753`.

---

### SettingsNavigationItem (shared row component)
- **Entry/route:** Reusable row used throughout Settings nav/other groups (`SettingsNavigationItem.kt`).
- **Layout & components:** `Row.fillMaxWidth()`, clickable only if `onClick != null`, `padding(vertical=16.dp)`, `verticalAlignment=CenterVertically`. Optional leading `Icon` 24.dp + 16.dp spacer; `Column.weight(1f)` with title `Text` (14.sp, `onBackground`) + optional `AutoSubtitleText` description (start-aligned, 12→6.sp auto, alpha 0.8, maxLines default 1, ellipsis); trailing `endIcon` default `Icons.AutoMirrored.Filled.KeyboardArrowRight` (`SettingsNavigationItem.kt:40-88`).
- **Visual:** title 14.sp; description auto-sized 12.sp start, min 6.sp; `iconColor` default `onBackground`; chevron `onBackground`.
- **States:** n/a (stateless row).
- **Interactions:** single click → `onClick`.
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** purely presentational; caller wires nav/dialog.
- **Feature inventory:** leading icon (optional), title, subtitle (optional, auto-shrinking), trailing chevron (overridable/removable via `endIcon=null`).
- **Citations:** `SettingsNavigationItem.kt:29-89`; `AutoSizedText.kt:17-46`.

---

### ThemeSelectionScreen (onboarding theme picker)
- **Entry/route:** `Screen.Theme` → `composable<Screen.Theme>` `NavGraphV2.kt:175` → `ThemeSelectionScreenRoute(navController, backStackEntry)` (`:177`), `onBottomBarVisibleChange(false)` (`:176`) — full-screen, no bottom bar. Route adapter `ThemeSelectionScreenRoute.kt:24-65` injects `OnboardingViewModel`, maps follow-system/dark to `AppTheme.System/Dark/Light` (`:33-37`), wires `onThemeSelected` → toggles, and `onContinue` → `navController.navigate(Screen.Sources)` (`:59`). Adapter also unconditionally invokes a second `NotificationPermissionRequester{}` (`:63-64`, `:66-93`) that auto-requests POST_NOTIFICATIONS once via `LaunchedEffect(Unit)`.
- **Layout & components:** `Surface.fillMaxSize()` containing:
  - `AnimatedBackground(fillMaxSize)` (animated onboarding backdrop; see Cluster notes — asset under `onboarding/welcome`).
  - `Box.fillMaxSize` with a vertical gradient overlay: `Brush.verticalGradient([background.copy(0.1f), background.copy(0.3f), background])` (`ThemeSelectionScreen.kt:103-115`).
  - `Column.fillMaxSize.padding(24.dp)`, `verticalArrangement=SpaceBetween`, `horizontalAlignment=CenterHorizontally` (`:116-167`):
    - Title `Text(R.string.choose_your_theme)` styled `headlineMedium.copy(fontSize=24.sp, color=primary)` (`:123-130`).
    - `Spacer(16.dp)`.
    - `ThemeSelector(...)` (the tab + permission card; `ThemeSelector.kt`).
    - `Button(onContinue)` full-width 50.dp, `clip(RoundedCornerShape(26.dp))`, `shape=shapes.medium`, `containerColor=primary`, label `R.string.continue_string` (labelLarge, 16.sp, onPrimary) (`:149-166`).
- **Visual:** 24.dp screen padding; title 24.sp primary; Continue button 50.dp tall, 26.dp clip + medium shape, primary fill / onPrimary text. ThemeSelector card uses `surfaceContainerHigh.copy(alpha=0.4f)` (`ThemeSelector.kt:41`).
- **States:** No loading/empty/error. Two reactive `remember` states: `hasNotificationPermission` (seeded by `hasPostNotificationPermission`) and `autoRequested` guard (`ThemeSelectionScreen.kt:60-64`). **Continue button enabled only when `hasNotificationPermission.value == true`** (`:151`) — gating onboarding on notification grant.
- **Interactions:**
  - **Theme tabs** (`ThemeSelector` `TabRow`): three `Tab`s Light/Dark/System with icons `LightMode`/`DarkMode`/`SettingsBrightness`; selecting → `onThemeSelected(theme)` (`ThemeSelector.kt:48-83`). Indicator color = `primary`, `containerColor` transparent.
  - **Grant permission** button → `onRequestNotificationPermission` → on Tiramisu+ launches `RequestPermission(POST_NOTIFICATIONS)`, else sets `hasNotificationPermission=true` (`ThemeSelectionScreen.kt:138-145`).
  - **Auto-request once** on first composition (`LaunchedEffect(autoRequested)`, Tiramisu+ & not granted) (`:88-96`).
  - **Continue** → `onContinue()` (navigate to Sources).
- **Dialogs/sheets/snackbars:** No dialogs. On permission denial shows a **Toast** `R.string.you_need_to_enable_notifications` (`:70-71`); if denied + "don't ask again", **redirects to app settings** via `openAppSettings()` (ACTION_APPLICATION_DETAILS_SETTINGS intent) (`:76-84,209-214`).
- **Forms & validation:** none (tab selection only).
- **Data/behavior:**
  - `AppTheme` enum: `Light(theme_light)`, `Dark(theme_dark)`, `System(theme_system)` (`ThemeSelectionScreen.kt:172-176`).
  - Theme persistence via `OnboardingViewModel.toggleFollowSystem/toggleDarkMode` (route adapter) which delegate to the same SharedPrefs keys as Settings. (INFERRED: `OnboardingViewModel` mirrors `SettingsViewModel` theme setters.)
  - **Permission:** `POST_NOTIFICATIONS` (Tiramisu+ only; pre-Tiramisu implicitly granted, `hasPostNotificationPermission` returns true `:189-198`).
  - Side effect: `onContinue` navigates to `Screen.Sources`.
  - **Pure-black/OLED is NOT exposed on this onboarding screen** — only Light/Dark/System tabs. Pure-black lives only on the Settings hub. (Flag for parity: KMP rework added a pure-black toggle to the Theme picker per task #243 — OLD app has none here.)
- **Feature inventory:** Light tab, Dark tab, System tab (TabRow); "Enable notifications" title + body copy; Grant permission button; Continue button (gated). Animated background + gradient overlay. Auto + manual notification permission request; toast + app-settings redirect on denial.
- **Citations:** `ThemeSelectionScreen.kt:51-214`; `ThemeSelector.kt:32-133`; `ThemeSelectionScreenRoute.kt:24-93`; `NavGraphV2.kt:45,175-179`.

---

### ThemeSelector (theme tabs + notification permission card)
- **Entry/route:** Child of `ThemeSelectionScreen` (`ThemeSelector.kt`).
- **Layout & components:** `ItemsGroup(color = surfaceContainerHigh.copy(alpha=0.4f))` → inner `Column.fillMaxWidth().padding(16.dp)` (`:40-47`):
  - `TabRow(selectedTabIndex = themes.indexOf(selected))`, transparent container, custom `Indicator` at selected tab offset, color `primary` (`:48-57`). Each `Tab`: icon (Light/Dark/System) + `AutoSubtitleText` label (bodyMedium 14.sp, primary, maxLines 1) (`:58-83`).
  - `Spacer(24.dp)`.
  - Notification section `Column.fillMaxWidth` (`:88-130`): `AutoSubtitleText(R.string.enable_notifications)` (bodyMedium 14.sp onBackground, maxLines 1), `AutoSubtitleText(R.string.notification_permission)` (bodySmall 12.sp onBackground, maxLines 3), then a `Row(horizontalArrangement=End)` with a `Button(onRequestNotificationPermission)` containing `AutoSubtitleText(R.string.grant_permission)` (bodySmall, onPrimary, minSize 2.sp) (`:111-129`).
- **Visual:** semi-transparent card; tab labels & indicator in `primary`; permission copy in `onBackground`; grant button default M3 primary.
- **States:** stateless (selection passed in).
- **Interactions:** tab click → `onThemeSelected`; grant button → `onRequestNotificationPermission`.
- **Dialogs/sheets/snackbars:** none (parent handles toast/redirect).
- **Forms & validation:** none.
- **Data/behavior:** purely presentational.
- **Feature inventory:** 3-tab theme picker, enable-notifications headline + body, grant-permission button.
- **Citations:** `ThemeSelector.kt:32-133`.

---

### LanguageSelectionScreen
- **Entry/route:** `Screen.LanguageScreen` (object, `NavGraphV2.kt:88`) → `composable<Screen.LanguageScreen>` `NavGraphV2.kt:543` → `LanguageScreenRoute(navController, backStackEntry)` (`:545`), `onBottomBarVisibleChange(false)` (`:544`). Route adapter `LanguageScreenRoute.kt:18-40` reads `R.array.supported_languages` from resources, maps each tag → `LanguageOption(tag, Locale.forLanguageTag(tag).getDisplayLanguage(locale))` (localized endonym), passes list + `onBack = navController.safePopBackStack()`.
- **Layout & components:** M2 `Scaffold` with `topBar = TopAppBarCom(title=R.string.select_language, navigationIcon=back IconButton(ArrowBack))`, `snackbarHost = SnackbarHost`, `contentColor = onBackground` (`LanguageSelectionScreen.kt:74-91`). Body `Column.fillMaxSize().padding(paddingValues).background(background)` → `LazyColumn.fillMaxWidth().padding(horizontal=24.dp)` (`:92-102`):
  - `items(availableLanguages)` → each renders `StatsItem(title=displayName, description=code, icon = Done if selected else null, onClick = selectLanguage(code))` + `Divider(padding vertical=12.dp)` (`:103-111`).
  - Trailing item: `StatsItem(title=R.string.request_language, icon=Icons.Default.Add, onClick → showFeedbackDialog=true)` + `Divider` (`:113-122`).
- **Visual:** 24.dp horizontal list padding; `StatsItem` row = optional 24.dp leading icon + 16.dp gap, title 14.sp + desc 12.sp (alpha 0.8), trailing bold count text (here `value=0` → renders "0" via `R.string.value_count="%,d"`, `:76-81`, `strings.xml:753`). Selected language shows a leading `Icons.Default.Done` check. Dividers 12.dp vertical padding. Top bar 24.sp bold title (`TopAppBarCom.kt:18-44`).
- **States:** No loading/empty/error — synchronous resource-backed list. Selected row reflects `selectedLanguageFlow` (`initial = Locale.getDefault().language`, `:64-66`).
- **Interactions:**
  - Tap language row → `viewModel.selectLanguage(code)` → persists + applies locale immediately (no restart prompt) (`LanguageViewModel.kt:25-37`).
  - Tap "Request language" row → opens FeedbackDialog (pre-selected category `ComplaintType.LANGUAGES`).
  - Back IconButton → `onBack` (pop).
- **Dialogs/sheets/snackbars:**
  - **FeedbackDialog** (`:125-160`): `selectedType = ComplaintType.LANGUAGES`, header `R.string.request_add_language`, field label `R.string.enter_your_language`. Submit → `complaintViewModel.submit(...)`; success snackbar `request_submitted_successfully` (Short); error snackbar `request_failed` + actionLabel `retry` (Long).
  - Snackbar host M3.
- **Forms & validation:** delegated to FeedbackDialog (category required + body ≥5 chars).
- **Data/behavior:**
  - VM `LanguageViewModel` (`@HiltViewModel`) injects `@ApplicationContext` + `SettingsRepository`. `selectedLanguageFlow = settingsRepo.languageFlow` (DataStore) (`LanguageViewModel.kt:23`).
  - `selectLanguage(code)`: `settingsRepo.setLanguage(code)` (DataStore persist) then `updateLocale(code)` → `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))` (`:25-37`). Comment notes activity may need recreate to fully apply (`:36`).
  - Supported tags (`arrays.xml:5-17`): `en, ar, de, es, fr, in, it, ja, pt, ru, tr` (11 listed; array may continue past line 17 — verify full list).
- **Feature inventory:** scrollable language list (display name + code, check on selected), "Request language" row (opens dialog), back nav, request snackbars. Note an **unused** `LanguageOptionItem` (radio-button row) composable exists (`:170-197`) but is NOT used by the screen (the screen uses `StatsItem`).
- **Citations:** `LanguageSelectionScreen.kt:57-197`; `LanguageScreenRoute.kt:18-40`; `LanguageViewModel.kt:15-38`; `LanguageOption.kt:3`; `StatsItem.kt:31-83`; `TopAppBarCom.kt:18-44`; `arrays.xml:5-17`; `NavGraphV2.kt:88,543-547`.

---

### LanguageSwitcher (file)
- **Entry/route:** `LanguageSwitcher.kt` — file contains **only imports, no composable body** (`LanguageSwitcher.kt:1-11`). Effectively dead/stub. Locale switching is implemented inline in `LanguageViewModel.updateLocale` instead.
- **Citations:** `LanguageSwitcher.kt:1-11`.

---

### FeedbackDialog (shared dialog — used by Settings + Language)
- **Entry/route:** Shared `AlertDialog`-based dialog (`FeedbackDialog.kt`).
- **Layout & components:** M3 `AlertDialog`, `RoundedCornerShape(20.dp)`, `containerColor=surface`, `tonalElevation=3.dp` (`:53-57`). Title block = header (`headlineSmall` bold) + subtitle `R.string.we_d_love_to_hear_from_you` (bodyMedium, onSurfaceVariant) (`:58-73`). Body `Column(verticalScroll, spacedBy 20.dp)`:
  - **Category dropdown** — label `R.string.category` (labelLarge SemiBold) + `ExposedDropdownMenuBox` with read-only `OutlinedTextField` (12.dp rounded, ArrowDropDown trailing) listing `ComplaintType.entries` (`:80-135`).
  - **Feedback input** — label `R.string.your_feedback` + multi-line `OutlinedTextField` (minLines 4, maxLines 6, heightIn min 120.dp, 12.dp rounded), error state when 0<len<5, supporting row with `R.string.minimum_5_characters_required` + char counter `"${len}/500"` (`:137-181`).
  - **Social media** — divider + `R.string.connect_with_us_in_social_media` + `R.string.you_ll_receive_a_prompt_response...` + `SocialMediaRow()` (`:183-201`).
  - confirmButton = `Button` enabled when `submitEnabled` (category != null AND body.length ≥ 5), label `R.string.submit` (`:204-218`). dismissButton = `TextButton` `R.string.cancel` (`:219-229`).
- **Visual:** 20.dp dialog corners, 12.dp field corners, 20.dp section spacing.
- **States:** local `selectedTypeState`, `feedbackBody`, `expanded`; `submitEnabled` derived. Early-returns when `!visible`.
- **Interactions:** dropdown select, text input, submit (gated), cancel, social-media taps.
- **Forms & validation:** category required + body ≥5 chars (max 500 shown). Error styling + counter.
- **Data/behavior:** stateless re: persistence — emits `onSubmit(type, body)` to caller; caller invokes `ComplaintViewModel.submit`. Caller passes `headerText`, `textFieldText`, optional pre-`selectedType`.
- **Feature inventory:** category dropdown, validated feedback field with counter, social row, submit/cancel.
- **Citations:** `FeedbackDialog.kt:32-231`.

---

### ReadingModeDialog (reached from Settings "Default reading mode")
- **Entry/route:** Opened by Settings row #10 (`SettingsScreen.kt:352-365`); defined `ReadingModeDialog.kt`.
- **Layout & components:** `Dialog` → `Surface(fillMaxWidth, tonalElevation=8.dp, RoundedCornerShape(16.dp), color=surfaceContainerHigh)` (`:56-64`). Header `Text("Reading mode")` (**hardcoded string**, headlineSmall bold, `:67-74`). Scrollable `Column` with `ReadingModeChips(modes, selectedMode, onModeSelected)` (`:84-92`), `Divider`, footer `Row(End)` with `OutlinedButton(R.string.but_revert)` (resets to currentMode + dismiss) and `Button(R.string.but_apply)` with `Icons.Default.Check` (applies selected + onApply) (`:98-148`).
- **Visual:** 16.dp corners, `surfaceContainerHigh`, 8.dp tonal elevation. Apply button uses `onBackground` container / `background` content (inverted).
- **States:** local `selected` (init `currentMode`).
- **Interactions:** chip select (updates local), Revert (reset + dismiss), Apply (`onModeSelected(selected)` + `onApply`).
- **Dialogs/sheets/snackbars:** is itself a dialog.
- **Forms & validation:** none.
- **Data/behavior:** `ReadingMode` enum (`ReadingMode.kt:7-35`): `DEFAULT`, `RIGHT_TO_LEFT`, `LEFT_TO_RIGHT`, `VERTICAL`, `WEBTOON`, `CONTINUOUS_VERTICAL` — each `@DrawableRes iconRes` + `@StringRes titleRes` (`reading_mode_*`). Persisted via `chaptersViewModel.setReadingMode` → DataStore `readingModeFlow`.
- **Feature inventory:** 6 reading-mode chips, Revert, Apply (with check icon). Note: header text "Reading mode" is hardcoded (not a string resource).
- **Citations:** `ReadingModeDialog.kt:41-155`; `ReadingMode.kt:7-35`; `SettingsScreen.kt:352-365`.

---

### CbzConversionDialog (reached from Settings "Start conversion")
- **Entry/route:** Always composed in Settings (`SettingsScreen.kt:408-417`); self-gating (`CbzConversionDialog.kt:28-32`).
- **Layout & components:** `Dialog` → `Card(fillMaxWidth.padding(16.dp), RoundedCornerShape(16.dp), containerColor=surface)` → centered `Column.padding(24.dp)` with three mutually-exclusive states (`:46-269`).
- **Visual:** 16.dp card corners; 48.dp state icon; progress bar 8.dp tall.
- **States (this dialog IS the loading/success/error surface):**
  - **Error** — `Icons.Default.Error` (error tint), title `R.string.conversion_failed`, error message, full-width `Button(R.string.close)` (`:62-91`).
  - **Success** — `CheckCircle` (or `Warning` if `wasStopped`), title `conversion_complete_`/`conversion_stopped`, message, `Button(R.string.closure_reason_done)` (`:93-133`).
  - **Converting** — `Warning` icon, title `converting_to_cbz`, warning `please_don_t_close_the_app...`, `LinearProgressIndicator(progress)`, completed/remaining counts, current manga title + chapter, `CircularProgressIndicator`, `OutlinedButton(R.string.stop_conversion)` (`:135-266`). Dismiss disabled while converting (`dismissOnBackPress/ClickOutside = !isConverting`, `:42-43`).
- **Interactions:** dismiss/close/stop per state.
- **Data/behavior:** reads `ConversionProgress` (isConverting/error/successMessage/wasStopped/totalChapters/convertedChapters/currentMangaTitle/currentChapterNumber); dismiss stops (if running) or clears error (`SettingsScreen.kt:410-416`).
- **Feature inventory:** progress %, completed/remaining counts, current item, spinner, stop, close/done.
- **Citations:** `CbzConversionDialog.kt:23-271`; `SettingsScreen.kt:408-417`.

---

### Cluster notes

**Theme tokens (`Theme.kt`, `Color.kt`, `Type.kt`) — these govern the WHOLE app:**

- **`YamiMangaTheme(darkTheme, dynamicColor=false, pureBlack=false, content)`** (`Theme.kt:97-134`). `dynamicColor` default **false** (dynamic color exists but is off). Base scheme: dynamic (S+) if enabled, else `DarkColorScheme`/`LightColorScheme`. **Pure-black override:** when `darkTheme && pureBlack`, copies base scheme with `background=Color.Black`, `surfaceContainer=Color.Black` (only those two) (`:117-125`). Applies `Typography` + `Shapes`.

- **DarkColorScheme** (`Theme.kt:17-51`) — KEY values:
  - primary `#FFB0C6FF`, onPrimary `#FF002D6E`, primaryContainer `#FF00429B`, onPrimaryContainer `#FFD7E2FF`
  - secondary `#FFB0C6FF`, onSecondary `#FF002D6E`, secondaryContainer `#FF00429B`, onSecondaryContainer `#FFD7E2FF`
  - tertiary `#FFB8D0FF`, onTertiary `#FF003063`, tertiaryContainer `#FF2C2C2F`, onTertiaryContainer `#FFD6E3FF`
  - **background `#FF15202B`** (Twitter-dim navy), onBackground `#FFE3E2E6`, **surface `#FF15202B`**, onSurface `#FFE3E2E6`, surfaceVariant `#FF44464F`, onSurfaceVariant `#FFC4C6D0`
  - outline `#FF8E9099`, inverseOnSurface `#FF1B1B1F`, inverseSurface `#FFE3E2E6`, inversePrimary `#FF0058CA`
  - error `#FFFFB4AB`, onError `#FF690005`, errorContainer `#FF93000A`, onErrorContainer `#FFFFDAD6`
  - (commented-out alt background `#FF1B1B1F` at `:33`.)

- **LightColorScheme** (`Theme.kt:53-85`) — KEY values:
  - primary `#FF0058CA`, onPrimary `#FFFFFFFF`, primaryContainer `#FFD7E2FF`, onPrimaryContainer `#FF001945`
  - secondary `#FF0058CA`, onSecondary `#FFFFFFFF`, secondaryContainer `#FFD7E2FF`, onSecondaryContainer `#FF001945`
  - tertiary `#FF0061A3`, onTertiary `#FFFFFFFF`, tertiaryContainer `#FF2C2C2F` (note: dark-ish), onTertiaryContainer `#FF001D36`
  - background `#FFFEFBFF`, onBackground `#FF1B1B1F`, surface `#FFFEFBFF`, onSurface `#FF1B1B1F`, surfaceVariant `#FFE3E2EC`, onSurfaceVariant `#FF44464F`
  - outline `#FF757780`, inverseOnSurface `#FFF2F0F4`, inverseSurface `#FF303034`, inversePrimary `#FFB0C6FF`
  - error `#FFBA1A1A`, onError `#FF93000A` (note: not white), errorContainer `#FFFFDAD6`, onErrorContainer `#FF410002`

- **`Color.kt`** (`Color.kt:5-10`) defines Material-template purples (`Purple80/40`, `PurpleGrey80/40`, `Pink80/40`) — **these are NOT used** by either color scheme (dead default tokens). The real palette is the blue scheme inline in `Theme.kt`.

- **Shapes** (`Theme.kt:89-95`): extraSmall 4.dp, small 8.dp, medium 12.dp, large 16.dp, **extraLarge 0.dp**. All `RoundedCornerShape`.

- **Typography** (`Type.kt`): font family **Gellix** — `gellix_regular`(Normal), `gellix_semibold`(Medium), `gellix_bold`(Bold) (`Type.kt:12-16`). Only 3 styles overridden: `bodyLarge` (Bold 16.sp), `titleMedium` (Medium 14.sp), `titleSmall` (Normal 12.sp) — rest are M3 defaults (`Type.kt:18-36`). NOTE: `bodyLarge` is **Bold**, which affects body text app-wide.

- **Component idioms reused across this cluster:**
  - `ItemsGroup` = rounded `surfaceContainerHigh` card, 16.dp radius (`ItemsGroup.kt:16-29`).
  - Section dividers = `colorScheme.background.copy(alpha=0.8f)` (near-invisible).
  - `SettingsNavigationItem` (nav rows, chevron) vs `SwitchItem` (M2 Switch, primary thumb) vs `StatsItem` (used by Language, trailing count) — all 24.dp icon + 16.dp gap, 14.sp title, 12.sp subtitle.
  - `AutoSubtitleText` = `BasicText` with `TextAutoSize.StepBased` (auto-shrinking) (`AutoSizedText.kt:17-46`).
  - `TopAppBarCom` = M3 TopAppBar, 24.sp bold title, background container (`TopAppBarCom.kt:18-44`).

**Persistence split (important parity detail):**
- Theme prefs (`ThemeMode`/`ThemeSystem`/`PureBlack`) → **SharedPreferences** (synchronous, `SharedPrefsHelper`).
- General/language/reading-mode → **DataStore** (`DataStoreHelper`).
- Defaults: pureBlack=true, followSystem=true; darkMode falls back to system night-mode when key absent.

**Notification permission flow** lives in onboarding Theme screen only: auto-request once + manual Grant button + Toast on denial + app-settings redirect when permanently denied. **Continue is gated on grant** — a hard onboarding gate.

**Inferences / anomalies flagged:**
- `SettingsRoute.kt` adapter file not located (only the import); assumed thin wrapper. (INFERRED)
- The `dialogState` Adult/M-confirmation chain in `SettingsScreen.kt:367-406` has no visible trigger in this file → likely dead/unreachable. (INFERRED)
- `LanguageOptionItem` (radio row) and `LanguageSwitcher.kt` are dead/unused.
- `StatsItem` trailing count shows "0" for language rows (value defaults to 0) — a cosmetic artifact of reusing the stats component.
- `OnboardingViewModel` theme setters assumed to mirror `SettingsViewModel`. (INFERRED — not read.)

**Missing assets to verify (referenced drawables/strings):**
- Drawables: `ic_launcher_foreground`, `incognito_svgrepo_com`, `switchthemes`, `ic_day_night`, `ic_plus_18`, `ic_complaint`, `ic_reader_setting`, `cache_cleaner`, reader-mode icons (`ic_reader_continuous_vertical_24dp`, `ic_reader_rtl_24dp`, `ic_reader_ltr`, `ic_reader_vertical_24dp`, `ic_reader_webtoon_24dp`).
- Fonts: `gellix_regular`, `gellix_semibold`, `gellix_bold`.
- String arrays: `R.array.supported_languages` (en/ar/de/es/fr/in/it/ja/pt/ru/tr — confirm full list past `arrays.xml:17`).
- Strings: `choose_your_theme`, `continue_string`, `enable_notifications`, `notification_permission`, `grant_permission`, `you_need_to_enable_notifications`, `select_language`, `request_language`, `request_add_language`, `enter_your_language`, all `reading_mode_*`, `but_revert`/`but_apply`, CBZ strings, `value_count`, `cache_used`, `calculating`, size unit strings.


---

# CLUSTER: history_updates_statistics

# OLD Native Android Audit — History · Updates(Notifications) · Statistics cluster

Read-only audit of the original native Android app (`yami-manga-apk-main`). Package root on disk is `me.manga.kira` (the path segment is `me/manga/yami/`). All citations are absolute paths into `D:/yami manga/yami-manga-apk-main/`.

This cluster covers three user-reachable screens plus one background-refresh helper:
- **History** — chronological reading log, grouped by date, per-item + clear-all delete.
- **Updates / Notifications** — new-chapter feed grouped by recency, swipe-to-mark-read, swipe-to-delete-with-undo, per-item download button, mark-all-read, delete-all.
- **Statistics** — read-only metrics dashboard (overview card + Entries section + Chapters section).
- **RefreshViewModel** — WorkManager-backed library-refresh trigger (no screen of its own; feeds the Updates feed).

---

### HistoryScreen
- **Entry/route:** `HistoryRoute` wrapper at `app/src/main/java/me/manga/yami/navigation/routes/HistoryRoute.kt:16-37`. Reads no nav-args; obtains `HistoryViewModel` via `hiltViewModel(backStackEntry)` (scoped to destination); passes `onMangaClick` (cover tap → manga), `onChapterClick` (row tap → reader/chapter), and `buildImageRequest = viewModel::buildImageRequest`. Screen composable: `HistoryScreen(viewModel, onMangaClick, onChapterClick, buildImageRequest)` at `presentation/features/history/ui/screens/HistoryScreen.kt:37-43`.
- **Layout & components:** `Scaffold` (`HistoryScreen.kt:48`) with `TopAppBarCom` (`HistoryScreen.kt:52`, title `R.string.title_history` = "History") whose single `actions` slot is a `DeleteForever` `IconButton` tinted `colorScheme.error` (`HistoryScreen.kt:55-61`) → `viewModel.deleteAllHistory()`. Body is a single `LazyColumn` (`HistoryScreen.kt:67`) filling size with `background(colorScheme.background)`, `contentPadding = top=innerPadding.calculateTopPadding(), bottom=8.dp` (`HistoryScreen.kt:71-74`). Items are grouped by `LocalDate` (`groupItemsByDate`, `HistoryScreen.kt:107-111`) and each group renders a `stickyHeader` label (`HistoryScreen.kt:78-88`) followed by `items(items, key = { it.id })` of `HistoryItem` (`HistoryScreen.kt:90-98`).
- Each row `HistoryItem` (`presentation/features/history/ui/screens/HistoryItem.kt:40-123`): outer `Card` `fillMaxWidth` + `padding(horizontal=16.dp, vertical=4.dp)`, clickable→`onChapterClick`, container color `surface.copy(alpha=0.12f)` (`HistoryItem.kt:52-60`). Inside, a `Row` `padding(12.dp)` center-aligned (`HistoryItem.kt:61-66`) containing: (1) a cover `Card` 80×120 dp, `shapes.small`, clickable→`onMangaClick`, holding `AsyncImage` (`ContentScale.Crop`, `fillMaxSize`, `imageLoader = getImageLoader()`, model from `buildImageRequest(context, item.mangaImageUrl, item.api)`) (`HistoryItem.kt:67-85`); (2) a weight-1 `Column` `padding(start=12.dp)` with manga title (`titleMedium`, `maxLines=2`), 4.dp spacer, chapter title (`bodyMedium`, `maxLines=1`), 4.dp spacer, relative date (`bodySmall`) (`HistoryItem.kt:87-112`); (3) a `Delete` (outlined) `IconButton` tinted `onBackground`, contentDescription `R.string.content_description_delete` = "Delete" → `onDeleteClick` (`HistoryItem.kt:114-120`).
- **Visual:** spacing 16/4 dp outer card padding, 12 dp inner row padding, 12 dp column start padding, 4 dp inter-text spacers. Cover 80×120 dp with `shapes.small`. Sticky header padding 16h/8v dp. Typography: header `titleMedium` + `FontWeight.SemiBold`; manga title `titleMedium`; chapter title `bodyMedium`; date `bodySmall`. Colors: scaffold/list bg `colorScheme.background`; card container `surface @ 0.12 alpha`; sticky-header background `colorScheme.background`; clear-all icon `colorScheme.error`; delete icon `colorScheme.onBackground`. Shapes: card default M3 corner; cover `shapes.small`. No explicit elevation set (M3 Card default tonal elevation).
- **States:**
  - *loading:* `HistoryUiState.isLoading` exists (`history/data/HistoryUiState.kt:5-9`) and is toggled in `HistoryViewModel.loadHistory()` (`history/ui/viewmodel/HistoryViewModel.kt:52,57`), but **the screen never reads `isLoading`** — no spinner/placeholder is rendered. (INFERRED) During first load the list is simply empty.
  - *empty:* No dedicated empty-state composable. When `historyItems` is empty, `groupedItems` is empty and the `LazyColumn` renders nothing (blank background). (INFERRED — no "No history" text/illustration exists.)
  - *error:* `HistoryUiState.error` is set on flow `.catch` / try-catch (`HistoryViewModel.kt:54-60,71-78`) but **the screen never displays `error`** — failures are silent on this screen.
  - *success:* grouped list rendered as above; items sorted newest-first.
- **Interactions:** row click → `onChapterClick(historyItem)` (open chapter). Cover-card click → `onMangaClick(historyItem)` (open manga). Per-row delete icon → `viewModel.deleteHistory(historyItem)` (`HistoryScreen.kt:95`). Top-bar `DeleteForever` → `viewModel.deleteAllHistory()` (`HistoryScreen.kt:55`). No long-press, no swipe, no undo, no animations (no `animateItem`/`animateContentSize`). Delete is **immediate and irreversible** (contrast with Updates which has undo).
- **Dialogs/sheets/snackbars:** **None.** Clear-all has no confirmation dialog; deletion is instant.
- **Forms & validation:** None.
- **Data/behavior:** `HistoryViewModel.init{}` calls `loadHistory()` (`HistoryViewModel.kt:45-47`) which collects `historyRepository.getAllHistory()` (Room `Flow`) and re-sorts `sortedByDescending { lastReadDate }` into state (`HistoryViewModel.kt:62-70`). Repo delegates to `HistoryDao` (`history/domain/HistoryRepository.kt:14,31,37`). `deleteHistory` / `deleteAllHistory` run on `viewModelScope.launch` (default dispatcher) wrapping repo calls in try-catch that only sets `error` (`HistoryViewModel.kt:143-165`). The VM also exposes `incognitoMode` and write paths (`insertHistory`, `updateHistoryItem`, `markChapterAsRead`, `getLatestHistoryIdByManga`, `chapterDownloadedState`) used by the **reader**, not by this screen — incognito guards skip inserts/updates when on (`HistoryViewModel.kt:84-87,108-111`). No permissions. Date grouping: `groupItemsByDate` groups by `lastReadDate.toLocalDate()` descending; `formatGroupLabel` returns "Today"/"Yesterday"/"N days ago" for ≤6 days else `"MMM d, yyyy"` pattern (`HistoryScreen.kt:107-128`). `HistoryItem.formatDate` (`HistoryItem.kt:126-137`) computes relative label from `ChronoUnit.DAYS`: 0→`today`, 1→`yesterday`, <7→`days_ago`, <30→`weeks_ago`(days/7), <365→`months_ago`(days/30), else `years_ago`(days/365).
- **Feature inventory:** (1) grouped-by-date list with sticky headers; (2) per-row cover thumbnail (80×120) opening manga; (3) manga title + chapter title + relative date; (4) row tap opens chapter; (5) per-row delete; (6) top-bar clear-all-history. Absent: loading spinner, empty state, error surface, undo, search/filter.
- **Citations:** `navigation/routes/HistoryRoute.kt:16-37`; `presentation/features/history/ui/screens/HistoryScreen.kt:37-128`; `.../history/ui/screens/HistoryItem.kt:40-138`; `.../history/ui/viewmodel/HistoryViewModel.kt:30-190`; `.../history/data/HistoryUiState.kt:5-9`; `.../history/domain/HistoryRepository.kt:10-61`; `data/local/entity/HistoryItemD.kt:7-24`. Strings: `title_history`, `content_description_delete`, `content_description_clear_history`, `today`, `yesterday`, `days_ago`, `weeks_ago`, `months_ago`, `years_ago` (`res/values/strings.xml:5,106-113`).

---

### UpdatesScreen (Notifications feed)
- **Entry/route:** `NotificationsRoute` at `navigation/routes/NotificationsRoute.kt:21-53`. Obtains `NotificationsViewModel` via `hiltViewModel(backStackEntry)`; **also receives `DownloadViewModelv2` and `AdViewModel` from the caller** (download/ad infrastructure). Collects `downloadViewModel.queuedChapterIds` → `downloadingChapters` and `downloadViewModel.runningChapter` → `runningChapter` (`NotificationsRoute.kt:31-32`). Composable: `NotificationScreen(...)` at `presentation/features/notifications/ui/screens/UpdatesScreen.kt:64-71` (note: file named `UpdatesScreen.kt`, function named `NotificationScreen`). Top-level `@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")` (`UpdatesScreen.kt:62`).
- **Layout & components:** `Scaffold` (`UpdatesScreen.kt:76`) with `snackbarHost = SnackbarHost(snackbarHostState)` (`UpdatesScreen.kt:77`) and `TopAppBarCom` (title `R.string.title_notifications` = "Notification", `UpdatesScreen.kt:79-80`). Top-bar `actions` (two icons, both `enabled` only when `groupedNotifications.isNotEmpty()`): `DeleteSweep` → `viewModel.deleteAll()` (cd `contentDescription_delete_all` = "Delete all", `UpdatesScreen.kt:82-87`) and `DoneAll` → `viewModel.markAllAsRead()` (cd `contentDescription_mark_all_as_read` = "Mark all as read", `UpdatesScreen.kt:88-93`). Body wrapped in a `Box` with `padding(top = paddingValues.calculateTopPadding())` (`UpdatesScreen.kt:97`). Inside: an error `Text` (when present), then either `LoadingScreen()` or a `LazyColumn(Modifier.animateContentSize())` (`UpdatesScreen.kt:108-111`). The list iterates `groupedNotifications` (`List<Pair<Int,List<ChapterNotification>>>`): one non-keyed `item` group-header `Text` (`titleMedium`, `padding(16.dp)`) per bucket using `stringResource(labelRes)` (`UpdatesScreen.kt:112-119`), then `items(notifications, key = { it.id })` of swipe-wrapped rows (`UpdatesScreen.kt:120-123`).
- Each row is wrapped in a `SwipeToDismissBox` (`UpdatesScreen.kt:163-214`), `fillMaxWidth().animateItem().animateContentSize()`, both directions enabled (`enableDismissFromStartToEnd`/`EndToStart = true`, `UpdatesScreen.kt:212-213`). `backgroundContent` is a `Row` `SpaceBetween` whose bg color `animateColorAsState` (300 ms `FastOutSlowInEasing`): StartToEnd → `primary @ 0.2`, EndToStart → `error @ 0.2`, else `background` (`UpdatesScreen.kt:170-188`); a `Done` outlined icon (tint primary) shows on the start edge during StartToEnd, a `Delete` outlined icon (tint error) on the end edge during EndToStart (`UpdatesScreen.kt:189-209`). Foreground content is `NotificationItems(...)`.
- Row content `NotificationItems` (`presentation/features/notifications/ui/screens/UpdateItem.kt:45-139`): `Row` `fillMaxWidth`, clickable→`onNotificationClick`, `padding(16h/8v dp)`, center-aligned. (1) 50×50 dp `AsyncImage` (model = raw `notification.mangaImageUrl`, NOT via buildImageRequest), `clip(RoundedCornerShape(8.dp))`, separate `clickable`→`onNotificationImgClick`, bg `surfaceVariant`, `ContentScale.Crop` (`UpdateItem.kt:61-71`). (2) weight-1 `Column` `padding(start=16.dp)`: manga title (`titleMedium`, 16.sp, `maxLines=1`, `alpha = if(isRead) 0.4f else 1f`); 4.dp spacer; a `Row` that shows an 8.dp `primary` `CircleShape` unread dot + 6.dp spacer when `!isRead`, then chapter-number `Text` (`stringResource(R.string.chapter_number, chapterNumber)`, `bodyMedium`, 12.sp, `maxLines=1`, same read-alpha) (`UpdateItem.kt:73-112`). (3) Trailing: if `downloadingChapters.contains(chapterId) || chapterId == runningChapter?.chapterId` → 24.dp `CircularProgressIndicator` (strokeWidth 2.dp; color `primary` when it is the *running* chapter else `onPrimary`) flanked by 8.dp spacers; otherwise a 48.dp download `IconButton` enabled only when `!isDownloaded`, showing `DownloadDone` (tint primary) when downloaded else `Download` (tint `onSurface @ 0.7`) → `onNotificationDownloadClick` (`UpdateItem.kt:113-137`).
- **Visual:** row padding 16h/8v dp; thumbnail 50 dp with 8.dp rounded corners; title 16.sp `titleMedium`; chapter number 12.sp `bodyMedium`; unread dot 8.dp `primary` circle; progress indicator 24.dp/2.dp stroke; download button 48.dp tap target. Read items dimmed to 0.4 alpha. Group headers `titleMedium` padded 16 dp. Swipe backgrounds animate over 300 ms; swipe-action icons 24.dp. List has `animateContentSize`.
- **States:**
  - *loading:* `uiState.isLoading` → `LoadingScreen()` (centered `CircularProgressIndicator`, color `inversePrimary`) (`UpdatesScreen.kt:108-109`, `presentation/common/screens/LoadingScreen.kt:18-33`). VM seeds `isLoading=true` via `.onStart` and `initialValue` (`notifications/ui/viewmodel/NotificationsViewModel.kt:36-39,53`).
  - *empty:* No dedicated empty composable. When `groupedNotifications` is empty the `LazyColumn` is blank; the two top-bar actions are disabled (`enabled = ...isNotEmpty()`). (INFERRED — no "No notifications" text.)
  - *error:* `uiState.errorMessage` rendered as a plain `Text` (color `error`, `fillMaxWidth`, `padding(16.dp)`) at the top of the `Box`, **above** the list (so it can coexist with content) (`UpdatesScreen.kt:98-106`). VM sets it from upstream `.catch { e -> ... errorMessage = e.localizedMessage }` (`NotificationsViewModel.kt:40-49`).
  - *success:* recency-grouped list as above.
- **Interactions:**
  - Row tap → `viewModel.markAsRead(it.chapterId)` then `onNotificationClick(it)` (`UpdatesScreen.kt:217-220`) — opens the chapter AND marks read.
  - Thumbnail tap → `onNotificationImgClick(notification)` (open manga details) (`UpdateItem.kt:68`).
  - Download button tap → `onNotificationDownloadClick(notification)` which in the route calls `downloadViewModel.downloadChapterNotification(it, it.api, it.mangaTitle)` then `adViewModel.onDownloadStarted(context, onProceed={}){}` (interstitial-ad gate) (`NotificationsRoute.kt:42-50`).
  - **Swipe StartToEnd** (→) → `viewModel.markAsRead(notification.chapterId)` (`UpdatesScreen.kt:128-130`).
  - **Swipe EndToStart** (←) → `viewModel.deleteWithUndo(notification)` then launches a snackbar; on `ActionPerformed` → `viewModel.undoDelete()`, on `Dismissed` → `viewModel.confirmDelete()` (`UpdatesScreen.kt:131-156`).
  - `confirmValueChange` always returns `false` (`UpdatesScreen.kt:159`), so the box never settles into a dismissed state — the row stays put and the model performs the action (delete is done via DB removal + list re-emission, not via the swipe component). Background-color crossfade + `animateItem`/`animateContentSize` animations present.
  - Top-bar: `DeleteSweep`→delete-all; `DoneAll`→mark-all-read.
- **Dialogs/sheets/snackbars:** **Snackbar** on swipe-delete (`UpdatesScreen.kt:134-153`): message `"Notification deleted"`, actionLabel `"Undo"`, `withDismissAction=true`, `SnackbarDuration.Short` — **all three strings are HARD-CODED English literals, not stringResource** (parity gap). Uses `async{ showSnackbar }.await()` to branch on `SnackbarResult`. A stray debug `Log.i("asfadfasdgasfgfdgdgdfgdfsgdf", result.toString())` remains (`UpdatesScreen.kt:146`). No confirmation dialog for delete-all or mark-all.
- **Forms & validation:** None.
- **Data/behavior:** `NotificationsViewModel.uiState` is `repo.getGroupedNotifications().map{…}.onStart{loading}.catch{error}.stateIn(WhileSubscribed(5_000), initial=loading)` (`NotificationsViewModel.kt:26-54`). Repo `getGroupedNotifications()` maps `dao.getAllNotifications()` through `groupByDate` (`notifications/domain/NotificationRepository.kt:22-24`). `groupByDate` (`NotificationRepository.kt:45-72`) buckets by `notificationDate` into Today / Yesterday / Last Week (after `today-7` and before yesterday) / Older; each bucket sorted **descending by `chapterNumber.toDoubleOrNull() ?: 0.0`**; emits `Pair<labelRes, list>` only for non-empty buckets (header string-res ids: `notifications_group_today/yesterday/last_week/older`). Mutations run on `Dispatchers.IO`: `markAsRead(id)` → `libraryDeo.markChapterAndNotificationRead(id)` (`NotificationRepository.kt:26-27`); `markAllAsRead()` → `dao.markAllAsRead()`; `deleteAll()` → `dao.deleteAllNotifications()`; `delete(n)` → `dao.deleteNotification(n)`; `restore(n)` → `dao.insertNotification(n)` (undo). `deleteWithUndo` stashes `pendingDeleteNotification` and immediately deletes from DB; `undoDelete` re-inserts the stashed entity; `confirmDelete` just clears the pending ref (`NotificationsViewModel.kt:56-101`). Download flows route through `DownloadViewModelv2` (external). No permissions on-screen (notification permission handled elsewhere).
- **Feature inventory:** (1) recency-grouped feed (Today/Yesterday/Last Week/Older); (2) per-row 50 dp thumbnail opening manga; (3) manga title + chapter number; (4) unread dot + read-state dimming (0.4 alpha); (5) row tap opens chapter + marks read; (6) swipe-right mark-read; (7) swipe-left delete with Undo snackbar; (8) per-row download button (idle/downloading-spinner/done states) gated by ad; (9) top-bar mark-all-read; (10) top-bar delete-all; (11) actions disabled when empty; (12) error banner. Absent: empty-state UI, delete-all confirmation, localized snackbar strings, swipe-confirm settling.
- **Citations:** `navigation/routes/NotificationsRoute.kt:21-53`; `presentation/features/notifications/ui/screens/UpdatesScreen.kt:61-233`; `.../notifications/ui/screens/UpdateItem.kt:45-185`; `.../notifications/ui/viewmodel/NotificationsViewModel.kt:19-101`; `.../notifications/data/NotificationsUiState.kt:5-9`; `.../notifications/domain/NotificationRepository.kt:12-72`; `data/local/entity/ChapterNotification.kt:11-27`; `presentation/common/screens/LoadingScreen.kt:18-33`. Strings: `title_notifications`, `contentDescription_delete_all`, `contentDescription_mark_all_as_read`, `chapter_number`, `notifications_group_today/yesterday/last_week/older` (`res/values/strings.xml:6,93-101`). Hard-coded literals: `"Notification deleted"`, `"Undo"`, `"Mark as read"`, `"Delete"` (`UpdatesScreen.kt:137-138,192,203`).

---

### Statistics (StatisticsScreen)
- **Entry/route:** `StatisticsRoute` at `navigation/routes/StatisticsRoute.kt:13-45`. `StatisticsViewModel = hiltViewModel()`. Collects 8 `StateFlow`s (`inLibrary`, `readDuration`, `completedEntries`, `entriesStarted`, `chaptersTotal`, `chaptersRead`, `chaptersDownloaded`, `chaptersBookmarked`) with `collectAsStateWithLifecycle()` and passes them as plain values plus `onBack = { navController.safePopBackStack() }` into `StatisticsScreen` (`StatisticsRoute.kt:21-44`). The screen itself takes primitive params, not a VM (stateless/preview-friendly) (`presentation/features/statistics/ui/screens/StatisticsScreen.kt:39-49`).
- **Layout & components:** `Scaffold` (note: uses **`androidx.compose.material.Scaffold`** — M2 — not M3; imports `material.Divider/Icon/IconButton/Scaffold`, `StatisticsScreen.kt:10-13`). `topBar = TopAppBarCom(title R.string.title_statistics = "Statistics", navigationIcon = IconButton{ onBack } with AutoMirrored `ArrowBack`, tint `onBackground`, cd `R.string.desc_back` = "Back")` (`StatisticsScreen.kt:52-57`). `contentColor = colorScheme.background` (`StatisticsScreen.kt:58`). Body is a `Column` `fillMaxSize().padding(scaffoldPadding).background(colorScheme.background).padding(16.dp)` (`StatisticsScreen.kt:60-66`) containing:
  1. **Overview card** `StatsOverview(inLibrary, readDuration, completedEntries)` (`StatisticsScreen.kt:67-71`).
  2. 16.dp spacer; `SectionTitle(R.string.section_entries = "Entries")` (`StatisticsScreen.kt:73-75`); `ItemsGroup{}` rounded container with 3 `StatsItem`s separated by `Divider`s: In library (`AutoMirrored.Outlined.LibraryBooks`, value `inLibrary`), Started (`Outlined.NotStarted`, value `entriesStarted`), Completed (`Outlined.DoneAll`, value `completedEntries`) (`StatisticsScreen.kt:76-94`).
  3. 16.dp spacer; `SectionTitle(R.string.section_chapters = "Chapters")` (`StatisticsScreen.kt:96-97`); `ItemsGroup{}` with 4 `StatsItem`s + `Divider`s: Total (`Outlined.SelectAll`, `chaptersTotal`), Read (`Outlined.RemoveRedEye`, `chaptersRead`), Downloaded (`Outlined.FileDownloadDone`, `chaptersDownloaded`), Bookmarked (`Outlined.BookmarkAdd`, `chaptersBookmarked`) (`StatisticsScreen.kt:98-122`).
  - `StatsOverview` (`presentation/features/statistics/ui/components/StatsOverview.kt:27-58`): `Card` (`surfaceContainerHigh`, `RoundedCornerShape(12.dp)`, `fillMaxWidth`) holding a `Row` (`spacedBy(8.dp)`, center, `padding(vertical=16.dp)`) of three equal-weight `OverviewItem`s: In library / Read duration / Completed entries (labels `label_in_library`/`label_read_duration`/`label_completed_entries`). `OverviewItem` (`StatsOverview.kt:61-83`): `Column` `padding(horizontal=18.dp)`, value `Text` 20.sp Bold centered (passed through `formatStatValue`), 4.dp spacer, label `Text` 12.sp centered. `formatStatValue` (`StatsOverview.kt:85-90`) renders integers via `R.string.value_count` (`"%,d"` grouped-thousands) else raw string.
  - `StatsItem` (`presentation/common/componants/list_items/StatsItem.kt:31-83`): `Row` `fillMaxWidth().padding(vertical=12.dp)`, optional 24.dp leading icon (tint `onBackground`) + 16.dp spacer, weight-1 title `Text` 14.sp (+ optional description 12.sp @0.8 alpha), trailing value `Text` (`value_count` `"%,d"`, 12.sp, Bold, centered). `ItemsGroup` (`presentation/common/componants/ItemsGroup.kt:17-29`): `Column` `fillMaxWidth`, `background(surfaceContainerHigh, RoundedCornerShape(16.dp))`, `padding(16h/8v dp)`. `SectionTitle` (`presentation/common/componants/titles/SectionTitle.kt:14-22`): `Text` 14.sp Bold, color `onBackground`, `padding(bottom=8.dp)`.
- **Visual:** outer body padding 16 dp; overview card `RoundedCornerShape(12.dp)` `surfaceContainerHigh`, internal row vertical padding 16 dp, item horizontal padding 18 dp; overview value 20.sp Bold, label 12.sp; section titles 14.sp Bold; `ItemsGroup` 16.dp rounded `surfaceContainerHigh` with 16h/8v padding; stat rows 12.dp vertical padding, 24.dp icons, 16 dp icon-text gap, value 12.sp Bold; dividers tinted `background @ 0.8 alpha`; 16.dp spacers between sections. Numbers formatted with thousands separators (`"%,d"`). Mixed M2/M3 usage (Scaffold/Divider/Icon/IconButton/Text from material, color/Card from material3).
- **States:**
  - *loading:* **No loading state.** Each metric `StateFlow` is `stateIn(..., Eagerly, <default>)` with defaults 0 / "0h 0m" (`statistics/ui/viewmodel/StatisticsViewModel.kt:17-43`); the screen always renders, showing defaults until DB flows emit.
  - *empty:* Not distinct from default — zeros display (e.g. "0", "0h 0m"). No empty illustration.
  - *error:* **No error state** — Room count flows are assumed to succeed; no try-catch or error surface.
  - *success:* live metrics rendered.
- **Interactions:** Back navigation only (`onBack` → `safePopBackStack`, `StatisticsRoute.kt:42`). `StatsItem` supports an optional `onClick` but **none is wired here** (all stat rows are non-interactive). No refresh, no animations.
- **Dialogs/sheets/snackbars:** None.
- **Forms & validation:** None.
- **Data/behavior:** `StatisticsViewModel` (`statistics/ui/viewmodel/StatisticsViewModel.kt:13-47`) exposes 8 flows from `StatisticsRepository`, all `Eagerly`-started. `StatisticsRepository` (`statistics/domain/StatisticsRepository.kt:20-80`): count flows from `StatisticsDeo` (`getTotalMangaCount`, `getTotalChaptersCount`, `getDownloadedChaptersCount`, `getReadChaptersCount`, `getBookmarkedChaptersCount`, `getCompletedMangaCount`, `getStartedMangaCount`) each `.flowOn(Dispatchers.IO)` (`StatisticsRepository.kt:28-36`). Read-duration is **not** from Room: `readMinutesFlow` reads DataStore pref `intPreferencesKey("read_minutes")` via `dataStoreHelper.dataStore` (`StatisticsRepository.kt:38-42`); `readDurationFlow` formats it as `R.string.h_m` (`"%1$dh %2$dm"`) (`StatisticsRepository.kt:44-48`). The repo also owns the **reading-session timer**: `startReadingSession()` stamps `sessionStartMillis`; `endReadingSession()` computes elapsed minutes (floor) and adds to the `read_minutes` pref via `dataStore.edit` (`StatisticsRepository.kt:49-73`) — these are called from the reader, accumulating the "Read duration" metric. No permissions.
- **Feature inventory:** (1) overview card (In library / Read duration / Completed entries); (2) Entries section: In library, Started, Completed; (3) Chapters section: Total, Read, Downloaded, Bookmarked; (4) thousands-grouped number formatting; (5) hours/minutes read-duration from accumulated reading sessions; (6) back button. Eight distinct metrics total. Absent: charts/graphs (the heading says "every chart" but the native app has **no graphical charts** — it is a numeric metrics list only), per-metric drill-down, refresh, loading/error states.
- **Citations:** `navigation/routes/StatisticsRoute.kt:13-45`; `presentation/features/statistics/ui/screens/StatisticsScreen.kt:39-157`; `.../statistics/ui/components/StatsOverview.kt:27-90`; `.../statistics/ui/viewmodel/StatisticsViewModel.kt:13-47`; `.../statistics/domain/StatisticsRepository.kt:20-80`; `presentation/common/componants/ItemsGroup.kt:17-29`; `presentation/common/componants/list_items/StatsItem.kt:31-83`; `presentation/common/componants/titles/SectionTitle.kt:14-22`. Strings: `title_statistics`, `desc_back`, `section_entries`, `section_chapters`, `label_in_library`, `label_read_duration`, `label_completed_entries`, `label_started`, `label_completed`, `label_total`, `label_read`, `label_downloaded`, `label_bookmarked`, `value_count`(`"%,d"`), `h_m`(`"%1$dh %2$dm"`) (`res/values/strings.xml:9,152,155-171,753,768`).

---

### RefreshViewModel (library-refresh helper — no screen)
- **Entry/route:** No dedicated screen; injected wherever a "refresh library" trigger is needed (e.g. Library/Updates surfaces). `@HiltViewModel` at `presentation/features/refresh/ui/viewmodel/RefreshViewModel.kt:25-30`.
- **Layout & components:** N/A (logic only).
- **States:** Exposes `isScheduled: Flow<Boolean>` (any WorkInfo `ENQUEUED`) and `isWorkRunning: Flow<Boolean>` (any `RUNNING`) derived from `workManager.getWorkInfosForUniqueWorkLiveData("LibraryRefresh").asFlow()` (`RefreshViewModel.kt:38-52`).
- **Interactions:** `refreshLibrary()` enqueues a unique `OneTimeWorkRequest<LibraryRefreshWorker>` with `ExistingWorkPolicy.REPLACE` under name `"LibraryRefresh"` (`RefreshViewModel.kt:88-102`).
- **Data/behavior:** `init{}` observes the unique work's first `WorkInfo`, `filterNotNull`, switches on state (`SUCCEEDED`/`FAILED`/else) but the branches are **empty no-ops** (placeholders) and reads `info.progress` without using it (`RefreshViewModel.kt:55-85`). The refresh worker (`me.manga.kira.work.LibraryRefreshWorker`) is what actually fetches new chapters and writes `ChapterNotification` rows that feed the Updates screen. Permission: WorkManager (no runtime permission).
- **Feature inventory:** trigger a background library refresh; observe enqueued/running state. The progress/result handling is stubbed.
- **Citations:** `presentation/features/refresh/ui/viewmodel/RefreshViewModel.kt:25-102`.

---

### Cluster notes
- **Package path vs id:** on-disk path is `me/manga/yami/` but the Kotlin package is `me.manga.kira` (and `R` is `me.manga.kira.R`). Citations above use the on-disk paths.
- **History has the weakest state coverage:** `isLoading` and `error` exist in `HistoryUiState` and are populated by the VM but **never rendered** by `HistoryScreen` — no spinner, no empty state, no error banner. Clear-all and per-item delete are **immediate with no confirmation/undo**. This contrasts sharply with Updates (which has loading + error + undo). A KMP parity port should decide whether to (a) mirror exactly (no empty/loading/error UI) or (b) intentionally improve — the rework tasks #239/#288/#357 indicate History was reworked and the legacy screen retired.
- **Updates snackbar strings are hard-coded English** (`"Notification deleted"`, `"Undo"`) and the swipe icons use hard-coded contentDescriptions (`"Mark as read"`, `"Delete"`) — these are NOT in strings.xml. A debug `Log.i` with a garbage tag remains in `UpdatesScreen.kt:146`. The rework undo-snackbar task is #298.
- **Updates download path is cross-feature:** the download button depends on `DownloadViewModelv2` + `AdViewModel` injected via the route, not the `NotificationsViewModel`. Downloading state is derived from `queuedChapterIds` / `runningChapter`, and the running chapter gets a `primary`-tinted spinner vs `onPrimary` for merely-queued. The download fires an interstitial-ad gate (`adViewModel.onDownloadStarted`). The rework download-button slice is #299/#300.
- **Updates row image** uses the raw `mangaImageUrl` directly in `AsyncImage` (no `buildImageRequest`/headers), whereas History uses `buildImageRequest(context, url, api)`. This is a real divergence — Updates thumbnails may fail to load for sources requiring referer/headers. (INFERRED quality risk; relevant to the MEMORY note on `buildImageRequest` parity.)
- **Updates swipe never settles:** `confirmValueChange` always returns `false`, so `SwipeToDismissBox` resets and the model performs delete/mark via DB mutation + Room flow re-emission. This is intentional (lets the row animate back while the list updates). A KMP port using a different swipe API must replicate the "perform side-effect, don't dismiss" behavior.
- **Statistics has NO charts/graphs** despite the audit prompt's "every chart/metric/card" wording — the native screen is purely a numeric overview card + two grouped metric lists (8 metrics). It uses **M2 `Scaffold`/`Divider`/`Icon`/`IconButton`** mixed with M3 components. No loading/error/empty states; defaults (0 / "0h 0m") show until flows emit. "Read duration" is uniquely DataStore-backed (`read_minutes` pref accumulated by the reader's session timer in `StatisticsRepository`), not Room. Rework swap is #286/#349; session-timer port is #232.
- **Date grouping divergence:** History groups by `lastReadDate.toLocalDate()` with `formatGroupLabel` (Today/Yesterday/N-days-ago ≤6, else `"MMM d, yyyy"`), while the per-row date uses a *different* helper (`formatDate`, weeks/months/years buckets). Updates groups by fixed Today/Yesterday/Last-Week(>today-7 & <yesterday)/Older buckets sorted by chapter number descending. Two different grouping schemes — port each verbatim.
- **No emulator/device in this audit env** (per project memory) — this is a static source audit; runtime appearance (e.g. exact M2/M3 color resolution, thumbnail load success) is inferred from code.
- **Missing assets:** none referenced beyond Material icons and string resources, all of which are present in `res/values/strings.xml`. No drawables/Lottie/raw assets in this cluster.


---

# CLUSTER: complaint_whatsnew_welcome_about

# OLD Native Android Audit — Complaint (User + Admin) / WhatsNew / Welcome / About Cluster

Source root: `D:/yami manga/yami-manga-apk-main/app/src/main/java/me/manga/yamiapk/`
(Package is `me.manga.kira`, dir is `me/manga/yami/`.)
This document audits the OLD native surfaces 1:1 so the KMP rework can align. All citations are `file:line`.

---

### ComplaintScreen (User-side feedback list)
- **Entry/route:** `navigation/routes/ComplaintScreenRoute.kt:17` → `ComplaintScreen(...)`. VM = `ComplaintViewModel` via `hiltViewModel()` (`ComplaintScreenRoute.kt:21`). `LaunchedEffect(Unit)` calls `complaintViewModel.loadForUser()` (`:26-28`). Collects `userComplaints` as `State<List<Complaint>>` (`:30`). Callbacks: `onRetry`=loadForUser, `onHelp`=no-op stub (commented nav to help, `:36-39`), `onBackClick`=`navController.safePopBackStack()`, `onReplyComplaint`=sendComplaint(reply)+reload, `onEditComplaint`=updateComplaint(copy(body=..))+reload, `onDeleteComplaint`=deleteComplaint(id)+reload (`:44-63`).
- **Layout & components:** Single `LazyColumn` fills screen, `background = colorScheme.background`, `contentPadding = PaddingValues(vertical = 16.dp)` (`ComplaintScreen.kt:124-129`). Item order: (1) `CenterAlignedTopAppBar` with bold title `feedback_manager_title`, nav back arrow `Icons.AutoMirrored.Filled.ArrowBack` (cd "Back"), container=background color (`:131-145`); (2) state branch (loading/error/empty); (3) `SearchAndFilterSection`; (4) no-results fallback OR (5) `items(filtered){ ComplaintCard }`.
- **Visual:** Top bar bold. Search section padding 16.dp. Search field `RoundedCornerShape(12.dp)`, single line. Filter chips `LazyRow` spacedBy 8.dp. Cards 16.dp horizontal/4.dp vertical padding, `RoundedCornerShape(16.dp)`, elevation 2.dp. Typography: title `titleMedium`+SemiBold, subject `bodySmall`/onSurfaceVariant, body `bodyMedium` maxLines 10 ellipsis, footer `bodySmall` + Monospace id.
- **States:** Loading → `LoadingState(message=loading_feedback)` padded top 64.dp then `return@LazyColumn` (`:150-160`). Error → `ErrorState(error, onRetry, onHelp = if code==403 onHelp else null)` (`:161-173`). Empty (Success + `allComplaints.isEmpty()`) → `EmptyState(fillParentMaxSize)` (`:174-181`). No-results (filtered empty + active query/status) → `EmptyState(title=no_results_found, message=try_different_search, icon=SearchOff)` (`:196-206`). Success → cards.
- **Interactions:** Card click → opens `ComplaintActionDialog` (sets selectedComplaint + showActionDialog, `:212-215`). Search text live-filters body/subject/id case-insensitive (`:116-122`). Status `FilterChip` toggle (re-tap clears) (`:296-304`). Clear-search trailing icon when query non-empty (`:272-278`). NOTE: user-side list itself has NO long-press copy in OLD (copy exists only on admin card body); the KMP task #274 "user-side long-press body-copy" is a KMP addition (INFERRED net-new).
- **Dialogs/sheets/snackbars:** `ComplaintActionDialog` (see below). On reply/edit/delete the screen calls `onShowMessage("... successfully ...")` (`:231,237,243`) — but the route wires `onShowMessage` to default `{}`, so NO visible snackbar/toast in user flow (INFERRED: messages are dropped).
- **Forms & validation:** Inside dialog only (search field is not a form).
- **Data/behavior:** `allComplaints = getCustomTopComplaints(context) + Success.data` (`:111-113`) — TWO hardcoded PINNED FAQ complaints are prepended (see PinnedFAQ section). Filtering done client-side in composable. Reply builds a new `Complaint` with `metadata += ("replyto" to complaint.id)` and `status=OPEN`.
- **Feature inventory:** back button; bold title; search field w/ leading search icon + clearable trailing; "All" + per-status filter chips; results-count text (`feedbacks_found_count`); pinned FAQ cards; complaint cards (type title, subject, OS-version InfoItem, manufacturer InfoItem, body, closure-reason card for CLOSED/PINNED, timestamp, short id); status chip; action dialog (reply/edit/delete).
- **Citations:** `ComplaintScreen.kt:17-316`; route `ComplaintScreenRoute.kt:17-66`.

### ComplaintCard (user list row)
- **Layout & components:** `Card` fillMaxWidth/wrapContentHeight, padding h16/v4, clickable, elevation 2.dp, `RoundedCornerShape(16.dp)` (`ComplaintCard.kt:30-37`). Inner `Column` padding 16. Header `Row` SpaceBetween: left `Column` weight 1 with type display-name (`titleMedium`/SemiBold, maxLines 2 ellipsis) + subject (`bodySmall`/onSurfaceVariant); right `StatusChip` (`:44-65`). Device/source `Row` SpaceBetween: `InfoItem(Android icon, apiLevelToAndroidVersion(osVersion))` + `InfoItem(PhoneAndroid icon, manufacturer)` (`:70-96`). Body `bodyMedium` maxLines 10 ellipsis (`:102-108`). If status CLOSED or PINNED and metadata "reason" present → `ClosureReasonCard` (`:111-117`). Footer `Row` SpaceBetween: `formatTimestamp(createdAt.time)` + `feedback_id_format`(id.take(8)) Monospace (`:122-139`).
- **Visual/Data:** osVersion read from `metadata["osVersion"].toString().toIntOrNull()`; manufacturer from `metadata["manufacturer"]` non-blank. Timestamp formatted "MMM dd, yyyy HH:mm" default locale (`formatTimestamp.kt:8`).
- **Citations:** `ComplaintCard.kt:24-143`.

### ComplaintActionDialog (user reply/edit/delete)
- **Entry/route:** Shown from `ComplaintScreen.kt:222-248` when `showActionDialog && selectedComplaint != null`.
- **Layout & components:** `Dialog` → `Card` fillMaxWidth padding 16, elevation 8.dp, `RoundedCornerShape(16.dp)` (`ComplaintActionDialog.kt:38-45`). State machine `currentAction: DialogAction` {NONE,REPLY,EDIT,DELETE} (`:36`, enum `DialogAction.kt`). NONE → `ActionSelectionContent`; REPLY → `ReplyContent`; EDIT → `EditContent`; DELETE → `DeleteConfirmationContent`.
- **ActionSelectionContent:** Header row: title `complaint_actions` (`headlineSmall`/Bold) + close IconButton (`Icons.Default.Close`). `ComplaintPreviewCard`. Buttons column spacedBy 12: `ElevatedButton` Reply (`AutoMirrored.Filled.Reply` icon) always shown; if `status != PINNED` → `OutlinedButton` Edit (`Icons.Default.Edit`) + `OutlinedButton` Delete (error contentColor, `Icons.Default.Delete`) (`:130-183`). So PINNED FAQ entries can only be Replied to.
- **ReplyContent:** Header back arrow + `AutoSubtitleText(reply_to_complaint, 22.sp Bold)`. Original-complaint reference card (surfaceVariant, label `reply_to_complaint_id`, subject titleSmall 1-line, body bodySmall 2-line) (`:230-260`). `OutlinedTextField` reply, label `your_reply`, placeholder `reply_placeholder`, minLines 3 maxLines 6, `RoundedCornerShape(12.dp)`, maxChars=500, isError when over (`:263-273`). Char count `character_count(len,max)` end-aligned, error color over limit (`:276-286`). Buttons: Cancel `OutlinedButton` weight 1; Send `Button` weight 1 enabled when non-blank & ≤max & !loading; shows `CircularProgressIndicator` 16.dp while loading else `AutoSubtitleText(send_reply)`. On send builds `Complaint(userId,type,subject, body=replyText, status=OPEN, metadata += "replyto"->id)` (`:301-328`).
- **EditContent:** Header `edit_complaint`. Info card (primaryContainer, `Icons.Default.Info`, `edit_complaint_id`). Subject `OutlinedTextField` single line (label `subject`). Body field minLines 4 maxLines 8, maxChars=1000, isError over. Char count. Cancel/`save_changes` buttons; Save enabled when body & subject non-blank & ≤max. Calls `onEdit(editedText)` (only body is passed up — subject edit is local-only and discarded, INFERRED bug parity) (`:333-473`).
- **DeleteConfirmationContent:** Header `delete_complaint`. Warning card (errorContainer, `Icons.Default.Warning`, `delete_warning_title`/`delete_warning_message`). `ComplaintPreviewCard`. Cancel + `delete_forever` Button (error container) with loading spinner (`:476-591`).
- **ComplaintPreviewCard:** surfaceVariant card, subject titleSmall, body bodySmall 3-line, status badge via `getColorWithContrast()` Surface `RoundedCornerShape(8.dp)`, "ID: ${id}" labelSmall (`:593-654`).
- **Forms & validation:** Reply max 500; Edit body max 1000, subject+body required; both block over-limit and blank. `isLoading` local flag disables buttons.
- **Citations:** `ComplaintActionDialog.kt:28-654`.

### AdminComplaintScreen (admin management list)
- **Entry/route:** `navigation/routes/AdminComplaintScreenRoute.kt:21` → `AdminComplaintScreen(...)`. VM `AdminComplaintViewModel` `hiltViewModel()`. `LaunchedEffect(Unit)` loadAllComplaints (`:29-31`). Collects `complaints` + `isLoading`. `showMessage` lambda just `println("Admin Message: ...")` (`:37-41`) — NO snackbar/toast in admin route either. Callbacks wire VM `updateComplaintStatus / deleteComplaint / updateComplaint / addClosureReason` each with onSuccess/onError → showMessage (`:51-105`).
- **Layout & components:** `LazyColumn` fill, background, contentPadding v16 (`AdminComplaintScreen.kt:155-159`). Items: (1) `CenterAlignedTopAppBar` title `admin_complaint_management` Bold, back arrow, **actions: toggle-stats IconButton** (`VisibilityOff`/`Visibility`) (`:162-188`); (2) state branch; (3) `AdminStatisticsCard` (if showStats); (4) `AdminSearchAndFilterSection`; (5) no-results OR `items{AdminComplaintCard}`.
- **Visual:** Same card geometry as user (h16/v4, r16, elev2). Stats card elevation 4.dp. Filter labels `labelMedium`/SemiBold. App-version chips use `FontFamily.Monospace` "v$version".
- **States:** Loading → `LoadingState(message=loading_complaints)` (`:192-202`). Error → `ErrorState(onRetry)` (no help) (`:204-215`). Empty Success → `EmptyState(no_complaints_found / no_complaints_message)` (`:217-228`). No-results (filtered empty, complaints non-empty) → `EmptyState(no_results_found / try_different_filters, icon=FilterAlt)` (`:260-270`).
- **Interactions:** Card status surface click → StatusChangeDialog; Edit IconButton → EditComplaintDialog; closure-note IconButton → ClosureReasonDialog; Delete IconButton → DeleteConfirmationDialog (`:276-291`). **Long-press body → copy to clipboard** + Toast `title_copied` (`AdminComplaintScreen.kt:800-811` via `combinedClickable`). Stats toggle button shows/hides stats card. Sort `DropdownMenu`.
- **Dialogs/sheets/snackbars:** Four dialogs (Status / Edit / Closure / Delete) in `StatusChangeDialog.kt`. Toast on body copy.
- **Forms & validation:** In dialogs (below).
- **Data/behavior — filtering/sort:** `complaints = Success.data` (`:85`). `availableAppVersions` = distinct `metadata["appVersion"]` sorted with numeric semver-descending comparator (`:88-110`). `filteredComplaints` remembered over (complaints, query, status, type, appVersion, sortBy): search matches body/subject/id/userId; status/type/appVersion equality filters (`:113-153`). Sort via `SortOption` enum: DATE_DESC(default)/DATE_ASC/STATUS(ordinal)/TYPE(ordinal)/USER_ID/APP_VERSION/APP_VERSION_DESC (`:139-151`, enum `:900-911`).
- **Feature inventory:** back; stats-toggle action; statistics card (total, per-status chips+counts, top-5 app-version counts); search (4-field); status filter chips (All + all statuses); type filter chips (All + all types); app-version filter chips (only if versions exist, "All Versions" + "v$ver"); sort dropdown (7 options); results count; active-filters summary line; admin cards.
- **Citations:** `AdminComplaintScreen.kt:58-912`; route `AdminComplaintScreenRoute.kt:21-107`.

### AdminComplaintCard (admin row)
- **Layout & components:** Card h16/v4 r16 elev2 (`:702-708`). Header `Row`: left column weight1 → type display-name (`titleMedium`/SemiBold), subject (`bodyMedium`), optional `replyToId` text (from metadata "replyto"), Row with `user_id_format`(userId.take(8)) Monospace + optional app-version Surface chip (tertiaryContainer, "v$ver" Monospace, r4) (`:713-768`). Right: status `Surface` (primaryContainer, r8, clickable→onStatusClick) containing `StatusChip` + `Icons.Default.Edit` (cd "Change Status") (`:770-794`).
- **Body:** `combinedClickable` long-press copy + Toast (`:799-816`), `bodyMedium` maxLines 3 ellipsis. CLOSED/PINNED + reason → `ClosureReasonCard` (`:818-825`).
- **Footer:** `Row` SpaceBetween: left column timestamp `formatTimestamp` + `feedback_id_format`(id.take(8)) Monospace; right action `Row` spacedBy 8: closure-note IconButton (secondaryContainer, `AutoMirrored.Filled.Note`), edit IconButton (tertiaryContainer, `Icons.Default.Edit`), delete IconButton (errorContainer, `Icons.Default.Delete`) (`:829-895`).
- **Citations:** `AdminComplaintScreen.kt:691-898`.

### AdminStatisticsCard
- **Layout:** Card elev4. Title `complaints_statistics` (`titleMedium`/Bold/primary). Total row (`total_complaints` + count Bold). "By status" label; for each status with count>0 → Row(StatusChip + count). If >1 app version: "By app version" label + top-5 by count (sorted desc) → Row("v$ver" Monospace + count).
- **Data:** Aggregates from `complaints` list in-composable (`:586-591`). (Note VM also has `getComplaintsStatistics()` returning `ComplaintsStatistics` with recentCount/avgResponseTime but this is NOT used by the card — INFERRED dead-ish; avgResponseTime reads metadata "resolvedAt".)
- **Citations:** `AdminComplaintScreen.kt:581-688`; VM stats `AdminComplaintViewModel.kt:316-364`.

### Admin Dialogs (StatusChange / Edit / Closure / Delete)
- **StatusChangeDialog:** `AlertDialog`, title `change_status` Bold. Body: `complaint_id_format`(id.take(12)), `select_new_status`, radio list of all `ComplaintStatus.entries` each Row(RadioButton + StatusChip) (`StatusChangeDialog.kt:45-92`). Confirm `update_status` enabled only when selection ≠ current; dismiss `cancel`.
- **EditComplaintDialog:** `Dialog`+Card r16, verticalScroll. Title `edit_complaint` + id. Type selector: read-only `OutlinedTextField` w/ ArrowDropDown/Up trailing → `DropdownMenu` of `ComplaintType.entries` (check leadingIcon on selected). Subject field (single line, Sentences caps, ImeNext). Body field height 120, maxLines 5. Cancel/`save_changes`; Save enabled when `hasChanges && subject & body non-empty`. Builds `complaint.copy(type,subject,body)` (`:112-286`).
- **ClosureReasonDialog:** `Dialog`+Card r16, scroll. Title `add_closure_reason` + id. `LaunchedEffect` pre-fills existing reason + type via `ClosureReasonType.fromString` (`:299-305`). Current-status Row (label + StatusChip). Reason-type read-only dropdown of `ClosureReasonType.entries` (display via `getDisplayText()`). Reason `OutlinedTextField` height 120 maxLines 5 (`closure_reason_details`). Cancel/`add_reason` enabled when reason non-empty. Builds `"${type.key}: ${reason}"` unless type==OTHER (`:288-456`).
- **DeleteConfirmationDialog:** `AlertDialog` w/ `Icons.Default.Warning` (error tint) icon, title `delete_complaint_title` Bold, message `delete_complaint_message`, details card (errorContainer .3α) with id Monospace + subject + status row + StatusChip. Confirm `delete` (error container, onError text); dismiss `cancel` (`:458-549`).
- **Citations:** `StatusChangeDialog.kt:36-549`.

### Complaint shared components / models / utils
- **StatusChip:** `Surface` r16, per-status M3 container/content pairs; OPEN→primaryContainer, IN_PROGRESS→tertiaryContainer, RESOLVED→secondaryContainer, CLOSED→errorContainer, PLANNED→surfaceVariant, PINNED/UNKNOWN→Black/White, NOT_PLANNED→Gray/White. Text `labelMedium`/Medium via `status.getDisplayName(context)` (`ComplaintComponents.kt:26-51`).
- **ClosureReasonCard:** Card r8, color by `ClosureReasonType.getColorScheme()`; icon DONE→CheckCircle, DONE_WAIT_UPDATE→Update, PINNED→PushPin, OTHER→Info. Label `closure_reason_label`, optional type chip (non-OTHER), reason text maxLines 10 (`ComplaintComponents.kt:53-124`). `InfoItem`/`DetailCard`/`DetailRow` also defined here (`:126-193`) — InfoItem used by cards; DetailCard/DetailRow appear dead.
- **EmptyState:** Centered icon (default `Icons.Default.Inbox`, 80.dp, onSurfaceVariant .6α) + title `titleLarge`/Medium + message `bodyMedium`. Defaults `no_feedback_found`/`no_feedback_message` (`EmptyState.kt:26-65`).
- **ErrorState:** Centered Card (errorContainer .1α, r16). Icon by code: 0→WifiOff, 400-499→Lock, 500-599→CloudOff, else→Error (all error tint). Title `error_occurred` headlineSmall Bold; error-code `error_code_format` Monospace; Retry `Button` (Refresh icon, `retry`) shown if `onRetry != null`. (`onHelp` param exists but the Help button is NOT rendered — only retry — INFERRED help is non-functional in OLD ErrorState despite 403 wiring.) (`ErrorState.kt:27-122`).
- **LoadingState:** Transparent card, infinite-rotating `Icons.Default.Refresh` 64.dp (1000ms linear), message `titleMedium`, `LinearProgressIndicator` (`LoadingState.kt:27-91`).
- **Complaint model:** `data class Complaint(id, userId, type:ComplaintType, subject, body, createdAt:Date?, status=OPEN, metadata:Map<String,Any>?)` (`Complaint.kt:6-15`). Uses `java.util.Date`.
- **ComplaintStatus:** 8 entries OPEN/IN_PROGRESS/RESOLVED/CLOSED/PLANNED/PINNED/UNKNOWN/NOT_PLANNED, each `@StringRes labelRes`, `getDisplayName(context)` (`ComplaintStatus.kt`).
- **ComplaintType:** 6 entries TECHNICAL/LANGUAGES/SITES_ADD/SITE_ERROR/FEATURES/CUSTOM → string keys error_in_the_app/add_languages/add_manga_site/error_in_manga_site/ask_to_add_features/custom_feedback (`ComplaintType.kt`).
- **ClosureReasonType:** DONE/DONE_WAIT_UPDATE/PINNED/OTHER + `fromString` heuristic (`ClosureReasonType.kt`). `getDisplayText()`@Composable + `getColorScheme()` (green/blue/error/white) + `ComplaintStatus.getColor()` / `getColorWithContrast()` (raw hex colors) in `utils/ClosureReasonExtensions.kt:13-94`.
- **apiLevelToAndroidVersion:** maps API 14-34 to version strings, 0→`filter_all`, else→`unknown` (`apiLevelToAndroidVersion.kt`).
- **toComplaintStatus / formatTimestamp:** string→enum (UNKNOWN fallback); "MMM dd, yyyy HH:mm" (empty for 0L).
- **ComplaintViewModel (user):** Hilt; deps UserIdProvider, DeviceInfoProvider, 5 use-cases (Send/GetUser/GetAll/Update/Delete). `submit()` builds complaint w/ `deviceInfoProvider.getDeviceMetadata()` + `Date()`. `loadForUser()` sets Loading→Success/Error.fromException. update/send/delete swallow exceptions silently (`ComplaintViewModel.kt:26-142`).
- **AdminComplaintViewModel:** Hilt; deps UserIdProvider + GetAll/Update/Delete. `init{loadAllComplaints()}`. Optimistic local-state updates after each mutation. `addClosureReason` writes metadata reason/reasonAddedBy/reasonAddedAt + auto-sets CLOSED if OPEN/IN_PROGRESS. `bulkUpdateStatus`/`bulkDeleteComplaints` exist (NOT wired to UI — INFERRED unused; KMP added bulk multi-select task #265 as net surface). `getComplaintsStatistics()` + `ComplaintsStatistics` (`AdminComplaintViewModel.kt:1-364`).
- **Repository / DataSource:** `ComplaintRepository` interface (send/getAll/getByUser/update/delete) (`ComplaintRepository.kt`). Firestore-backed `ComplaintFirestoreDataSource.kt` (Android-only).
- **Citations:** as inline above.

### Pinned FAQ (customTopComplaints)
- Prepended to user list ALWAYS via `getCustomTopComplaints(context)` (`getCustomTopComplaints.kt:41-74`). Two localized PINNED entries (id=`R.string.admin`, userId="0", type CUSTOM, status PINNED, metadata osVersion="0"/pinned=true/reason): (1) "Content removed (18+/hentai)" w/ reason `removed_18_hentai_reference_...`; (2) "New manga site requirements" (≥200 titles, no bot checks) w/ reason `new_site_requires_200_mangas_...`. A separate hardcoded `customTopComplaints` list (`:11-40`) holds two non-localized dummy PINNED entries (NOT referenced by the screen — INFERRED dead sample). Pinned entries render with PINNED StatusChip (Black/White) + ClosureReasonCard (PINNED→PushPin icon, white scheme); in the action dialog only Reply is offered (Edit/Delete hidden for PINNED).
- **Citations:** `getCustomTopComplaints.kt:11-74`; PINNED edit-gate `ComplaintActionDialog.kt:146`.

---

### WhatsNewScreen (feature pager)
- **Entry/route:** `navigation/routes/WhatsNewRoute.kt:20`. Args `Screen.WhatsNewScreen(isFirstOpen)` via `toRoute` (`:25`). VM `WhatsNewViewModel` (passed in, not hiltViewModel here). Collects `features`, `isLoading`, `loadError`. Branch: isLoading→`LoadingState` (local, spinner 48.dp + "Loading What's New..."); loadError!=null & empty→`ErrorState` (Card r? elev4, "Failed to Load" headlineSmall/error + msg + Close/Retry buttons); features empty→`EmptyState` ("No Updates Available" + Close); else→`WhatsNewScreen` (`WhatsNewRoute.kt:30-58`, local states `:60-168`). onDismiss: if isFirstOpen → `markWhatsNewAsSeen()` then `safePopBackStack()`.
- **Layout & components:** Full-screen `Box` with vertical gradient (primaryContainer .3α → surface) (`WhatsNewScreen.kt:53-64`). `Column`: `WhatsNewHeader` → `HorizontalPager` (weight 1, beyondViewportPageCount 1, pageSpacing 0) of `FeatureCard` → `PageIndicators` → `NavigationButtons`. Fullscreen media overlay (`FullscreenMediaViewer`) when `fullscreenMediaState != null`.
- **Visual:** Responsive: isTablet (`screenWidthDp>=600`), isLandscape. horizontalPadding 32/16; cardPadding 32/16/24. Media size 300/280/180/220. Card `fillMaxHeight(0.85f|0.75f)`, r20, elev4.
- **Header:** Surface (transparent, tonalElev 2). Row SpaceBetween: title `whats_new_title` headlineSmall Bold + close IconButton 40.dp circle surfaceVariant .5α (`Icons.Default.Close`) (`WhatsNewComponents.kt:21-63`).
- **PageIndicators:** Centered row of pills; selected = 24×8 primary, else 8×8 outline .3α, r4 (`WhatsNewComponents.kt:65-96`).
- **NavigationButtons:** Row SpaceBetween. If page>0 → `OutlinedButton` `previous`, else Spacer 80.dp. If page<last → `Button` `next`, else `Button` `get_started`→onDismiss (`WhatsNewComponents.kt:98-141`).
- **FeatureCard:** `AnimatedVisibility` slide+fade 250ms keyed on isActive. Card → scrollable Column spacedBy 16: `FeatureMedia` + title `AutoSubtitleText` (Bold 26/32sp, maxLines 2) + description `AutoSubtitleText` (15/18sp, maxLines 15) (`FeatureCard.kt:28-110`). NOTE: OLD FeatureCard renders NO "NewChip" / isNew badge nor version label — `feature.isNew`/`feature.version` are unused in UI (KMP task #248 "NewChip" is a KMP net-new, INFERRED).
- **FeatureMedia:** Branches mediaType IMAGE (imageResList→ImageCarousel / imageUrlList→ImageUrlsCarousel / imageRes→SingleImage / imageUrl→SingleUrlImage / else→ImagePlaceholder) vs VIDEO (`SafeVideoPlayer` if url & showSmallVideo, else VideoPlaceholder) (`FeatureCard.kt:112-183`). Components in `ImageComponents.kt`/`VideoComponents.kt` (not all read; carousels + Coil `SubcomposeAsyncImage`).
- **States:** loading/error/empty/success all in route (above). Pager success only.
- **Interactions:** Horizontal swipe paging; prev/next animate-scroll buttons; tap media → fullscreen viewer (image res/url or video); fullscreen tap-outside / close button dismiss; `get_started` on last page dismisses.
- **Dialogs/sheets:** Fullscreen media overlay (Box black .95α, image via painter or Coil `SubcomposeAsyncImage` w/ spinner+error, or `SafeVideoPlayer`; close IconButton top-end; `tap_outside_to_close` caption bottom) (`FullscreenMediaViewer.kt:26-141`).
- **Data/behavior:** VM `WhatsNewViewModel` (Hilt): deps Context, DataStoreHelper, `WhatsNewRemoteDataSource`. Prefs via `PrefsDelegate` (`whats_new_last_shown_version`/`..._timestamp`). `init{checkIfShouldShowWhatsNew()}`: if currentVersionCode>lastShown → `ds.setNewSources(true)`, shouldShow=true, loadFeatures. `loadFeatures()` fetches remote by user-language code, maps `RemoteData`→`WhatsNewFeature` via `getLocalizedFeature`, falls back to `getDefaultFeatures` on empty/error. `getDefaultFeatures` currently returns EMPTY list (all entries commented out — `getDefaultFeatures.kt:8-45`). `ensureFeaturesLoaded`, `markWhatsNewAsSeen` (persists version+timestamp), `forceShowWhatsNew`, `retryLoadFeatures`, `shouldShowBasedOnTime` (30d), `resetWhatsNew` (`WhatsNewViewModel.kt:24-263`).
- **Models:** `WhatsNewFeature(title, description, mediaType, imageRes?, imageResList, imageUrl?, imageUrlList, videoUrl?, isNew=false, version?)`; `MediaType{IMAGE,VIDEO}`.
- **Feature inventory:** gradient bg; header (title + close); horizontal pager; per-card media (single img/res, image carousels for res-list & url-list, video player) + auto-sized title + description; page-pill indicators; prev/next/get-started nav; tappable fullscreen media viewer w/ video; remote-first w/ default fallback; version-gated auto-show + seen persistence.
- **Citations:** `WhatsNewScreen.kt:31-135`; `WhatsNewRoute.kt:19-168`; `WhatsNewComponents.kt`; `FeatureCard.kt`; `FullscreenMediaViewer.kt`; `WhatsNewViewModel.kt`; `getDefaultFeatures.kt`.

---

### WelcomeScreen (onboarding intro)
- **Entry/route:** `navigation/routes/WelcomeScreenRoute.kt:10` → `WelcomeScreen { navController.navigate(Screen.Theme) }` (`:17-19`). Single `onGetStarted` callback navigates to Theme.
- **Layout & components:** `Surface` fill → `Box` fill: `AnimatedBackground` (Lottie) + gradient overlay `Box` (background .1α/.3α/solid vertical) + bottom-aligned `Column` (padding 24, animateContentSize 600ms): title `welcome_title` (headlineLarge 36sp ExtraBold primary), subtitle `welcome_suptitle` (bodyMedium 18sp onBackground .9α), Spacer 24, `Button` fillMaxWidth height 52 r26 primary → `get_started` label (18sp onPrimary) (`WelcomeScreen.kt:42-116`).
- **Visual:** Lottie raw resource `R.raw.background` looping forever speed 1.0 `ContentScale.FillBounds` (`:118-132`). Dark gradient bottom-to-top to make text legible.
- **States:** No loading/error/empty (static onboarding).
- **Interactions:** Single Get-Started button → navigate to Theme screen; background animation loops.
- **Dialogs/sheets:** None.
- **Data/behavior:** Pure UI; no VM. Navigation only.
- **Feature inventory:** looping Lottie background; gradient legibility overlay; title; subtitle; full-width rounded Get-Started button.
- **Asset dependency:** `R.raw.background` (Lottie JSON), `R.string.welcome_title`, `welcome_suptitle`, `get_started`. (Per MEMORY task #743 these assets exist; KMP wired lottie-compose.)
- **Citations:** `WelcomeScreen.kt:41-140`; `WelcomeScreenRoute.kt:10-21`.

---

### AboutScreen
- **Entry/route:** `presentation/features/about/screen/AboutScreen.kt:60`. Params navController, backStackEntry, `whatsNewViewModel`, `onBack`. Uses `LocalContext`.
- **Layout & components:** `Scaffold` (Material2 `Scaffold`/`TopAppBarCom`) topBar `TopAppBarCom(title=about, navigationIcon=back IconButton `ArrowBack`)` (`:70-84`). Body `LazyColumn` fill, background, padding start/end16/top=scaffold, `horizontalAlignment CenterHorizontally` (`:89-97`).
- **Items:** (1) Header `Image(R.drawable.ic_launcher_foreground)` 250.dp, vertical padding 24 (`:99-107`). (2) `Divider` (Gray .3α) + Spacer 24 + `ItemsGroup` of `SettingsNavigationItem` rows separated by Dividers: **Version** (`version`, value = packageInfo.versionName, `Icons.Outlined.AppRegistration`); **Check for update** (`Icons.Outlined.Update` → `openAppInPlayStore`); **Rate our app** (`Icons.Outlined.StarRate` → `openAppInPlayStore`); **What's new** (`AutoMirrored.Outlined.ManageSearch` → `whatsNewViewModel.ensureFeaturesLoaded()` + `navController.navigate(Screen.WhatsNewScreen(false))`); **Source code** (`soon` subtitle, `Icons.Outlined.Code`, inert); **Privacy policy** (→ `openBrowser(https://yamimanga.me/privacy)`) (`:108-149`). (3) Spacer 24 + `SocialMediaRow()` + Spacer 24 (`:151-155`).
- **Visual:** Big centered launcher logo. Dividers use background-color .8α between rows. Title via TopAppBarCom.
- **States:** Static.
- **Interactions:** Back; row clicks (PlayStore intents, WhatsNew nav, privacy browser); social icons.
- **Dialogs/sheets/snackbars:** None directly (WhatsNew is full screen nav). openLink falls back to chooser/CustomTabs/Toast "No app available".
- **Data/behavior:** Reads versionName from PackageManager. Navigates to WhatsNew (isFirstOpen=false). External intents.
- **SocialMediaRow:** `BoxWithConstraints` adaptive 6 buttons (button 36-56dp, icon 18-28dp), SpaceEvenly, each `SocialMediaButton` (circle primaryContainer .2-.3α, scale-press spring animation 0.85f). Order: X/Twitter (`CustomIcons.X` → twitter `yami_manga_me`), Facebook (`Icons.Default.Facebook` → page `61577403584218`), Instagram (`R.drawable.ic_instagram` → `yami_manga_me`), WhatsApp (`R.drawable.ic_whatsapp` → `01558657735` w/ prefilled msg), Discord (`CustomIcons.Discord` → no-op default), Website (`R.drawable.earth_svgrepo_com` → `https://yamimanga.me/`) (`SocialMediaRow.kt:33-164`). Each accepts optional override callback.
- **Intent helpers:** `openLink` (app-uri → web chooser → CustomTabs → Toast fallback), `openTwitter/openFacebook/openInstagram/openGitHub/openDiscordInvite/openBrowser/sendWhatsAppMessage` (Egypt 0→20 normalization) in `openLink.kt`; `openAppInPlayStore` (market:// → web fallback) in `OpenAppInPlayStore.kt`.
- **Feature inventory:** back; logo header; version row; check-update; rate; what's-new (loads features + navigates); source-code (inert/soon); privacy-policy; 6-button social row.
- **Asset dependency:** `R.drawable.ic_launcher_foreground`, `ic_instagram`, `ic_whatsapp`, `earth_svgrepo_com`; custom vectors `CustomIcons.X`, `CustomIcons.Discord` (`about/common/icons/`); `Icons.Default.Facebook` (material-icons-extended).
- **Citations:** `AboutScreen.kt:59-176`; `SocialMediaRow.kt:31-164`; `openLink.kt:12-172`; `OpenAppInPlayStore.kt:8-27`.

---

### Cluster notes
- **No visible toast/snackbar on user OR admin mutation:** user route passes default `onShowMessage = {}` and admin route uses `println(...)`. Admin body-copy IS the only Toast (`title_copied`). KMP should decide whether to ADD real snackbars (the OLD app effectively shows nothing). (INFERRED behavior parity = silent.)
- **Help action is dead in OLD:** `onHelp` is threaded for 403 errors but `ErrorState` never renders a Help button, and the route's onHelp is a commented no-op. Do not re-implement a working Help unless intentionally improving.
- **Edit subject discarded (user dialog):** `EditContent` lets the user edit subject locally but `onEdit(complaint, body)` only propagates body; subject change is lost. (INFERRED bug.)
- **Bulk admin ops + VM statistics are unwired in OLD:** `bulkUpdateStatus`/`bulkDeleteComplaints`/`getComplaintsStatistics()`/`ComplaintsStatistics` exist in `AdminComplaintViewModel` but no OLD UI calls them. The stats CARD computes its own aggregates inline. KMP tasks #263 (stats card) and #265 (bulk multi-select) therefore exceed OLD parity.
- **WhatsNew defaults are empty:** `getDefaultFeatures` returns `emptyList()` (all commented). With no remote data the screen shows the EmptyState ("No Updates Available"). isNew/version fields are not rendered in OLD; any KMP NewChip/version label is net-new.
- **Pinned FAQ has a dead sample list:** `customTopComplaints` (top-level val) is unused; only `getCustomTopComplaints(context)` feeds the UI (2 localized PINNED entries). Both pinned ids use `R.string.admin` and userId "0".
- **Mixed Material versions in About:** AboutScreen uses Material2 `Scaffold`/`Icon`/`Divider`/`IconButton` while the rest of the cluster uses Material3. KMP rework should standardize on M3.
- **Date type:** `Complaint.createdAt` is `java.util.Date?` (Android/JVM); KMP must map to `Instant`/epoch millis (MEMORY notes Instant.fromEpochMilliseconds).
- **Status color duality:** `StatusChip` uses M3 theme container colors; `ComplaintPreviewCard` (user dialog) uses raw-hex `getColorWithContrast()`. The two color systems for the same status differ — confirm which KMP adopted.
- **Missing-asset check:** Lottie `R.raw.background`, drawables `ic_instagram`/`ic_whatsapp`/`earth_svgrepo_com`/`ic_launcher_foreground`, custom vectors X/Discord — all required for Welcome/About parity. Per MEMORY they were ported.
- **Files NOT deeply read (low risk):** `whatsnew/ui/components/ImageComponents.kt`, `VideoComponents.kt`, `about/common/icons/{CustomIcons,Discord,Facebook,Github,Reddit,X}.kt`, `complaint/data/sample.kt`, `ComplaintFirestoreDataSource.kt`, the 5 complaint use-cases, `RemoteData.kt`/`LocalizedFeature.kt`/`WhatsNewRemoteDataSource.kt` — referenced but their UI contribution is captured via call sites above.


---

# CLUSTER: webview_nav_shell

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

