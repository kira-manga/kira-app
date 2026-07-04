# KMP Rework App — Full Feature & UI Audit

_Assembled 2026-05-31T01:28Z from audit_workspace/kmp/ — one section per feature cluster. Read-only audit; cites file:line. Companion: the other side's audit + UI_AND_FEATURE_GAPS.md._

## Clusters: Home/Search · Library · Details/Reader · Downloads/Sources · Settings/Theme/Language · History/Updates/Statistics · Complaint/WhatsNew/Welcome/About · WebView/Nav/Shell/Theming


---

# CLUSTER: home_search

# KMP (rework) Audit — HOME + SEARCH cluster

Read-only audit of the architecture-rework Home + Search surface. All paths absolute. Citations are `file:line`. Inferences marked `(INFERRED)`.

## Runtime routing — which screen the Home tab actually renders

**REWORK renders the Home tab.** There is no legacy `:shared` fallback for Home/Search.

- `App.kt` defines the NavHost. The user-facing `Screen.Home` `composable<>` block dispatches to `HomeReworkScreenRoute` (rework), not a legacy `HomeScreenRoute`. See `D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt:431-437`. The KDoc at L424-430 confirms Epic H5a swapped the Home tab to render `:ui/.../home/HomeScreen` + `:ui/.../search/SearchScreen` via the rework `HomeViewModel`/`SearchViewModel`, and the legacy `HomeScreenRoute` + its 4 `:shared` VMs were retired in H5b.
- `Screen.Home` is a no-arg object route: `D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt:18`. Home/Search is NOT its own destination — Search is an overlay swapped on `HomeState.isSearching` within the same backstack entry (`HomeReworkScreenRoute.kt:89-161`).
- Bottom-nav visibility for Home is `true` (`App.kt:432`).

**Notable runtime fact:** the route adapter forwards Home/Search "open details" to `Screen.MangaDetailsRework` (the rework Details screen, full-tuple), NOT to legacy `Screen.MangaDetails`. See `HomeReworkScreenRoute.kt:96-106` (search) and `:122-132` (home). This diverges from the VM/effect KDoc which still describes mapping to `Screen.MangaDetails(mangaUrl, api)` (`HomeReworkScreenRoute.kt:35-36` KDoc is stale vs the actual code). Reader chip taps go to `Screen.ChapterImagesFragment` (rework reader via by-legacy-args adapter, `:134-151`); WebView → `Screen.WebView` (`:152-154`); edit-sources → `Screen.RepoSettings(false)` (`:155-157`).

---

### Home feed
- **Entry/route:** `Screen.Home` (no-arg) → `HomeReworkScreenRoute` (rework) → `:ui` `HomeScreen` when `!isSearching`. `App.kt:431-437`; `HomeReworkScreenRoute.kt:74-162`; screen at `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/HomeScreen.kt:114-233`.
- **Layout & components:** `Scaffold` (`HomeScreen.kt:176`) with `TopAppBar` (title + actions, `:179-205`); body is a `PullToRefreshBox` (`:208`) wrapping a `Column` of `SourceTabsRow` (`:216-224`) + `HomeFeedBody` (`:225-229`). Feed body branches: grid = `LazyVerticalGrid` `GridCells.Adaptive(minSize = 120.dp)` of `HomeFeedGridCard` (`:279-313`); list = `LazyColumn` with `FeaturedCarousel` as first item then `HomeFeedRow`s (`:315-360`). Components live in `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/components/`: `SourceTabsRow.kt`, `HomeFeedGridCard.kt`, `HomeFeedRow.kt`, `FeaturedCarousel.kt`.
- **Visual:** Grid cards `RoundedCornerShape(8.dp)`, elevation 2.dp, cover-fill `YamiCoverImage(scrim = true)` with bottom-centered white bold `labelSmall` title (`HomeFeedGridCard.kt:48-75`). List rows: `Card` elevation 4.dp, `surface`/`onSurface` colors, 100.dp cover (100f/130f aspect), bold `titleSmall` 2-line title, chapter chips in `primaryContainer`, trailing heart (`HomeFeedRow.kt:73-130`). Spacing from `LocalSpacing` tokens (`sm`/`md`/`xs`). Grid contentPadding `spacing.sm` with `spacedBy(spacing.sm)` both axes (`HomeScreen.kt:294-296`). Tabs are rounded pills: selected = `primary.copy(alpha=0.1f)` bg + `primary` text; unselected = `onSurface.copy(alpha=0.6f)`, `RoundedCornerShape(12.dp)`, `labelMedium`/Medium weight (`SourceTabsRow.kt:84-122`).
- **States:** loading = full-screen `CircularProgressIndicator` (`isInitialFeedLoading`, `HomeScreen.kt:259-261`); error (with empty feed) = `YamiErrorState` with retry → `OnRefresh` (`:262-266`); empty = `YamiEmptyState(home_empty)` (`:267`); success = grid or list (`:268-269`). Site-state gate ahead of these: `UNDER_MAINTENANCE`/`STOPPED`/`ADULT_18_PLUS` each render `YamiEmptyState` with the per-source message + `YamiIcons.Error` (`:242-276`); only `WORKING` shows the feed. Next-page spinner appended when `isLoadingNextPage` (`:309-311`, `:356-358`, `:362-371`).
- **Interactions:** card/row tap → `OnMangaClick` → Details (`HomeScreen.kt:305`, `:348`); chapter chip tap → `OnChapterClick` → Reader (`:349`, `HomeFeedRow.kt:110-113`); heart toggle → `OnSaveToggle` (`:350`); pull-to-refresh → `OnRefresh` (`HomeScreen.kt:210`); infinite scroll via `snapshotFlow` over last-visible index → `OnEndReached` (`:286-290`, `:323-327`, `:379-387`); tab tap → `OnTabSelected` (`:219`); grid/list toggle → `OnToggleGridView` (`:190`); search action → `OnToggleSearch` (`:195`); open-in-browser → `OnOpenWebView` (`:200`); edit-sources (tab strip trailing pencil) → `OnEditTabs` (`:220`). No long-press, no swipe-to-dismiss. No explicit per-item animations; the legacy carousel per-item spring scale was intentionally dropped (`FeaturedCarousel.kt:24-35`).
- **Dialogs/sheets/snackbars:** snackbar host on the Home Scaffold (`HomeScreen.kt:206`); `HomeEffect.ShowError` → `snackbarHostState.showSnackbar(...)` with typed-error → string mapping (`:170`, `:411-431`). No dialogs. **HelpVideoDialog is UNWIRED:** `onHelp` is a `TODO` no-op in the route adapter (`HomeReworkScreenRoute.kt:158`), and the top bar exposes no help action (only grid-toggle, search, open-in-webview — `HomeScreen.kt:182-202`), so `HomeIntent.OnHelp`/`HomeEffect.ShowHelp` (`HomeIntent.kt:48-49`, `HomeEffect.kt:63-64`) are never reachable from Home. `(INFERRED)` legacy had a help affordance; rework leaves it dangling.
- **Forms & validation:** none on Home.
- **Data/behavior:** `HomeViewModel` (`D:/yami manga/yami-kmp/presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/home/HomeViewModel.kt`). `OnEnter` is idempotent-guarded (`started`, `:104-126`) and launches collectors: `observeSourceTabs` → tabs + restart siteState (`:110-115`); `observeActiveTabIndex` (`:117-122`); first `fetchHome(reset=true)` + `fetchFeaturedFeed()` (`:124-125`). `siteStateJob` keyed on active source api, restarted on tab/index change (`:235-242`). Single-flight cancel-and-replace `homeFetchJob`/`featuredFetchJob` (`:194`, `:224`). Pagination guard in `onEndReached` (`:134-167`) with cross-page de-dup via `distinctBy(feedKey)` and `hasMorePages` gated on net-new rows. First page also de-duped (`:201`). `membershipJob` projects visible-feed library membership into `savedKeys` via `combine` of `ObserveInLibraryUseCase` flows (heart-sync, `:250-268`). `onSaveToggle` → `ToggleInLibraryUseCase` (`:270-274`). `onOpenWebView` emits blank-url + active api (route resolves landing URL) (`:276-282`). Use cases: `ObserveSourceTabsUseCase`, `ObserveActiveTabIndexUseCase`, `ObserveSiteStateUseCase`, `SelectSourceTabUseCase`, `FetchHomeFeedUseCase`, `FetchMoreHomeFeedUseCase`, `FetchFeaturedUseCase`, `ObserveInLibraryUseCase`, `ToggleInLibraryUseCase` (`:46-58`). No permissions.
- **Feature inventory:** (1) source/language tab strip with horizontal scroll; (2) edit-sources pencil button; (3) grid↔list layout toggle; (4) search action; (5) open-in-browser action; (6) pull-to-refresh; (7) infinite scroll pagination; (8) featured/popular carousel (list mode only); (9) manga card/row tap → Details; (10) recent-chapter chips (up to 3) tap → Reader; (11) heart save/unsave to library with reactive sync; (12) per-source maintenance/stopped/adult gate views; (13) loading/empty/error/retry states; (14) error snackbar. **UNWIRED/absent:** help dialog (intent+effect exist, never triggered); per-tab source icon (`SourceTabsRow.iconForTab` slot not supplied by the route adapter — `HomeReworkScreenRoute.kt` calls `SourceTabsRow` indirectly through `HomeScreen` which never threads an `iconForTab`, so tabs are label-only); "new source" badge (`showNewBadge = false` hardcoded, `HomeScreen.kt:221`); featured carousel `coverModel` slot not threaded from list (`HomeScreen.kt:335-338` calls `FeaturedCarousel` without `coverModel`).
- **Citations:** `App.kt:431-437`; `HomeReworkScreenRoute.kt:74-162`; `HomeScreen.kt:114-522`; `HomeViewModel.kt:46-296`; `HomeState.kt:37-59`; `HomeIntent.kt:13-50`; `HomeEffect.kt:14-65`; components `SourceTabsRow.kt`, `HomeFeedGridCard.kt`, `HomeFeedRow.kt`, `FeaturedCarousel.kt`.

### Source tabs (Home tab strip)
- **Entry/route:** rendered inside Home (`HomeScreen.kt:216-224`); component `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/components/SourceTabsRow.kt`.
- **Layout & components:** `Row(height 48.dp)` holding a weighted `PrimaryScrollableTabRow` (transparent container, `edgePadding=16.dp`, empty `indicator`/`divider`) + a trailing edit `YamiIconButton(Edit)` in a `Box` (`SourceTabsRow.kt:70-149`). Each tab is a pill `Tab` with optional icon slot + `Text(tab.api)`.
- **Visual:** see Home "Visual" above. Edit button 36.dp, tinted `onBackground` (`:130-135`). Optional "new" badge overlaid TopEnd in `primary` rounded box with `onPrimary` `labelSmall` bold text — only when `showNewBadge && newBadgeLabel != null` (`:137-148`).
- **States:** empty `tabs` → renders nothing (`SourceTabsRow.kt:69`). Active index coerced into range (`:78`).
- **Interactions:** tab tap → `onTabSelected(index)` → VM resets feed + page cursor + re-fetches for the new source (`HomeViewModel.onTabSelected:169-188`); edit tap → `onEditSources` → `OnEditTabs` → `NavigateToSources` → `Screen.RepoSettings(false)`.
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** tabs come from `ObserveSourceTabsUseCase`; active index from `ObserveActiveTabIndexUseCase`; selection persists via `SelectSourceTabUseCase` (`HomeViewModel.kt:110-122`, `:171`). `SourceTab` carries `api`, `language`, `iconKey?`, `siteState` (`SourceTab.kt:15-24`).
- **Feature inventory:** horizontal scrollable source tabs; selected-pill highlight; edit-sources jump; (absent at runtime) per-source icon, "new" badge.
- **Citations:** `SourceTabsRow.kt:56-151`; `HomeScreen.kt:216-224`; `HomeViewModel.kt:169-188`; `SourceTab.kt`.

### Featured carousel (Popular)
- **Entry/route:** first item of the Home **list** layout only (`HomeScreen.kt:333-339`); not shown in grid layout. Component `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/home/components/FeaturedCarousel.kt`.
- **Layout & components:** `LazyRow(height 220.dp)`, `horizontal contentPadding 12.dp`, `spacedBy(16.dp)`, each item a 150.dp-wide `Card` `RoundedCornerShape(12.dp)` elevation 6.dp with `YamiCoverImage(aspectRatio 150f/220f, scrim=true)` (`FeaturedCarousel.kt:44-85`).
- **Visual:** rounded cover cards with bottom scrim; no title overlay text in the carousel card itself (cover-only). Deviation from legacy `HorizontalUncontainedCarousel`: plain `LazyRow`, per-item spring scale + fling-snap intentionally dropped for cross-target stability (`FeaturedCarousel.kt:21-42`).
- **States:** empty `items` → renders nothing (`:51`). No per-item loading/error beyond `YamiCoverImage`'s own broken-image glyph.
- **Interactions:** card tap → `onItemClick` → `OnMangaClick(featured.toFeedItem())` → Details (`HomeScreen.kt:337`; `toFeedItem` at `:395-404`).
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** `fetchFeaturedFeed()` → `FetchFeaturedUseCase`, de-duped by `FeaturedManga.feedKey()` (`HomeViewModel.kt:223-233`). `FeaturedManga` is a flat 5-field record (`FeaturedManga.kt:12-23`).
- **Feature inventory:** popular cover carousel; tap → Details. **Absent:** title label on carousel cards (legacy showed title); per-item animation; source-aware cover model (slot exists but not passed).
- **Citations:** `FeaturedCarousel.kt:44-85`; `HomeScreen.kt:333-339`, `:395-404`; `HomeViewModel.kt:223-233`; `FeaturedManga.kt`.

### In-app Search
- **Entry/route:** overlay on the Home backstack entry — `HomeReworkScreenRoute` renders `SearchScreen` when `homeState.isSearching` is true (`HomeReworkScreenRoute.kt:89-112`); toggled by the Home top-bar search action (`HomeScreen.kt:195` → `OnToggleSearch` → `HomeViewModel.kt:73`). Not a NavHost destination. Screen at `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/search/SearchScreen.kt:101-218`.
- **Layout & components:** `Scaffold` (`SearchScreen.kt:159`) with a custom `SearchTopBar` (`TopAppBar` with `Close` nav icon + inline `OutlinedTextField` query + `Tune` filter action, `:220-265`); body = `Column` with a 2-tab `TabRow` (single/multi, `:176-187`) over a `HorizontalPager(pageCount=2, pageSpacing=16.dp)` (`:188-202`). Page 0 = `SingleResults` (`LazyVerticalGrid` `Adaptive(120.dp)` of `HomeFeedGridCard`, `:267-303`); page 1 = `MultiRepoResults` (`LazyColumn` of per-source `RepoSection`, each title + horizontal `LazyRow` of `MultiRepoCard` 140×200.dp, `:310-397`). Filter sheet `SearchFilterSheet` (`:206-217`). Reuses `HomeFeedGridCard` from home/components.
- **Visual:** results grid same spacing as Home grid (`spacing.sm`, `Adaptive(120.dp)`); multi sections spaced `spacing.lg`, padded `spacing.md` (`:321-325`); section title `titleMedium`/Bold/`primary` (`:349-354`). Pager page spacing 16.dp (`:188`). `TabRow` default M3 indicator (unlike the Home pills).
- **States:** single: `UiState.Loading` → centered spinner; `Error` → `YamiErrorState` + retry (re-runs query); `Success` empty → `YamiEmptyState(search_empty)`; `Success` non-empty → grid (`SearchScreen.kt:275-302`). Multi: empty map → `YamiEmptyState(search_empty)` (`:317-320`); per-section `Loading` → 100.dp boxed spinner, `Error` → inline `error`-colored text, `Success` empty → muted "no results" text, `Success` → `LazyRow` (`:356-384`). Blank query resets both to empty success/empty map (`SearchViewModel.kt:91-94`).
- **Interactions:** query typing → `OnQueryChange` (per-keystroke search, `SearchScreen.kt:164`, `SearchViewModel.kt:67-70`); clear (trailing X, shown when non-empty) → sets query "" (`SearchScreen.kt:243-250`); close (nav icon) → `OnClose` → `SearchEffect.Close` → adapter flips Home `OnToggleSearch` (`:165`, `SearchViewModel.kt:58`, `HomeReworkScreenRoute.kt:110`); tab tap → `OnModeTabChange` (`:179`, `:184`); pager swipe ↔ tab kept in sync via two `LaunchedEffect`s (`:149-157`); result tap → `OnMangaClick` → Details (`:192`, `:198`); filter action (`Tune`) → opens sheet (`:166`, `:261`). No long-press/gestures beyond pager swipe.
- **Dialogs/sheets/snackbars:** `SearchFilterSheet` `ModalBottomSheet` (see next section); snackbar host on Search Scaffold (`:169`); `SearchEffect.ShowError` → snackbar (`:144`, error mapping `:407-426`).
- **Forms & validation:** the query field is a single-line `OutlinedTextField` with `search_hint` placeholder (`:238-255`); no validation — blank query simply clears results. Search fires on every change (no debounce). `(INFERRED)` legacy may have debounced/submitted; rework searches per-keystroke.
- **Data/behavior:** `SearchViewModel` (`D:/yami manga/yami-kmp/presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/search/SearchViewModel.kt`). `OnLoadFilters` loads active source's sort+genres (`:62-65`); `runSearch` cancels both in-flight jobs then single-flights single (`runSingleSearch:101-118`) or collects multi fan-out (`runMultiSearch:120-138`). Single mode derives `SearchMode` from selection: genres → GENRES, else sort → SORT, else NORMAL (`:105-109`). Multi maps each repo's `AppResult` to `UiState` per-source (`:124-132`). Use cases: `SearchSourceUseCase`, `SearchAllReposUseCase`, `LoadSearchFiltersUseCase` (`:31-33`). Effects map to `Screen.MangaDetailsRework` in adapter (`HomeReworkScreenRoute.kt:92-107`). No permissions.
- **Feature inventory:** (1) inline query field with clear button; (2) close/dismiss; (3) single vs multi-source tabs + swipeable pager; (4) single-source result grid; (5) multi-repo aggregated per-source sections (horizontal rows); (6) per-section loading/error/empty; (7) filter sheet open; (8) result tap → Details; (9) error snackbar. **Absent vs legacy:** source-aware cover (`coverModel` defaults to plain url); query debounce.
- **Citations:** `SearchScreen.kt:101-481`; `SearchViewModel.kt:30-139`; `SearchState.kt:9-42`; `SearchIntent.kt:12-35`; `SearchEffect.kt:12-38`; `HomeReworkScreenRoute.kt:89-112`.

### Search filters (filter sheet)
- **Entry/route:** opened from the Search top-bar `Tune` action; local `showFilters` state (`SearchScreen.kt:135`, `:166`, `:206-217`). Component `D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/search/SearchFilterSheet.kt`.
- **Layout & components:** `ModalBottomSheet(skipPartiallyExpanded=true)` (`SearchFilterSheet.kt:69`, `:78`) with a vertically scrollable `Column`: title (`search_filter_sheet_title`, `titleLarge`/Bold), genre `FlowRow` of `FilterChip`s, sort `ExposedDropdownMenuBox` (readonly `OutlinedTextField` + `ExposedDropdownMenu`), and a full-width Apply `Button` (`:78-162`).
- **Visual:** padded `spacing.lg`/`md`, sections `spacedBy(spacing.md)`; genre chips `spacedBy(spacing.sm)`; section headers `titleMedium` (`:79-153`).
- **States:** if both `sortTypes` and `genres` empty → `search_no_filters` muted text (`:92-97`); genre section only when genres non-empty (`:99`); sort section only when sortTypes non-empty (`:118`).
- **Interactions:** genre chip toggles draft set (multi-select, `:107-114`); sort dropdown single-select (`:138-151`); Apply → `onApply(draftSort, draftGenres)` → `OnApplyFilters` → re-runs single-source search + closes sheet (`:156-161`, `SearchScreen.kt:211-214`, `SearchViewModel.kt:77-81`); dismiss → `onDismiss` closes (`:215`). Draft state committed only on Apply — keyed on incoming selections (`:72-76`).
- **Dialogs/sheets/snackbars:** is itself the modal sheet.
- **Forms & validation:** genre multi-select + sort single-select; no validation. Default sort display falls back to first sortType when draft null (`:128`).
- **Data/behavior:** `filters` from `LoadSearchFiltersUseCase` (active source's sort+genres, `SearchViewModel.kt:62-65`); `SearchFilters(sortTypes, genres)` (`SearchFilters.kt:11-16`). Applying filters always routes to SINGLE mode search (`SearchViewModel.kt:80`).
- **Feature inventory:** genre filter chips (multi-select); sort dropdown (single); Apply commit; no-filters message; dismiss. **Note:** filters apply only to single-source search, not multi.
- **Citations:** `SearchFilterSheet.kt:61-164`; `SearchScreen.kt:206-217`; `SearchViewModel.kt:62-81`; `SearchFilters.kt`.

---

### Cluster notes

**Shared `:ui` design-system components used:**
- `YamiCoverImage` (`D:/yami manga/yami-kmp/ui/src/commonMain/kotlin/me/manga/yamiapk/ui/components/YamiCoverImage.kt`) — covers on grid card, list row, carousel, multi-repo card; `scrim` gradient + broken-image error glyph, no per-cell spinner; backed by the singleton Coil `ImageLoader`.
- `YamiEmptyState` / `YamiErrorState` / `YamiLoadingState` (`YamiStateViews.kt`) — Home empty/error + site-state messages; Search single/multi empty + error states. (Home uses a raw `CircularProgressIndicator` for initial load and `CenteredSpinner`/`NextPageSpinner` rather than `YamiLoadingState`.)
- `YamiIconButton` + `YamiIcons` (`YamiIconButton.kt`, `YamiIcons.kt`) — top-bar actions (`ViewList`/`GridView`/`Search`/`OpenInWebView`/`Close`/`Tune`/`Edit`), row heart (`FavoriteFilled`/`FavoriteOutline`), site-state `Error` glyph, cover `BrokenImage`.
- `YamiCountBadge` — imported by `SourceTabsRow.kt:26` (KDoc references badge styling) but the actual "new" badge is hand-rolled `Text` + box (`SourceTabsRow.kt:137-148`); badge is disabled at runtime (`showNewBadge=false`).
- `LocalSpacing` design tokens (`ui/.../ui/theme/`) — spacing throughout.
- Theme: `YamiMangaTheme` applied at App root (`App.kt:332`); `MaterialTheme` typography/colorScheme used in all components. `(INFERRED)` Gellix typography + theme consolidation per project memory (UP-1/UP-7).

**Resources (present):** all Home/Search strings exist in both `D:/yami manga/yami-kmp/ui/src/commonMain/composeResources/values/strings.xml` (en, lines 344-371) and `values-ar/strings.xml` (Arabic). Keys: `home_title`, `home_grid_view`, `home_list_view`, `home_search`, `home_open_in_webview`, `home_help`, `home_edit_sources`, `home_save`, `home_saved`, `home_new_source_badge`, `home_featured`, `home_empty`, `home_failed_to_load`, `home_site_maintenance`, `home_site_stopped`, `home_site_adult`; `search_hint`, `search_close`, `search_clear`, `search_filters`, `search_tab_single`, `search_tab_multi`, `search_empty`, `search_filter_sheet_title`, `search_genres`, `search_sort`, `search_no_filters`, `search_apply_filters`. `home_help` and `home_featured` strings exist but are not wired into any visible affordance (help unwired; carousel cards show no title). Error strings (`error_network`/`error_storage`/etc.) reused for snackbars.

**Per-source cover headers:** `:ui` never builds a Coil `ImageRequest`; all covers pass a plain URL. Per-source auth headers attach at the singleton `ImageLoader` level via `CoilSourceHeaderInterceptor` + OkHttp fetcher wired in `App.kt:266-324`. The `coverModel`/`iconForTab` slots in the components are designed to thread source-aware requests but the route adapter passes only `{ it.coverUrl }` (`HomeReworkScreenRoute.kt:87`) and no `iconForTab`, so the leaf components coerce `as? String` and discard anything richer (`HomeFeedRow.kt:72`, `HomeFeedGridCard.kt:47`, `FeaturedCarousel.kt:77`).

**Present vs absent (design-exists-but-unwired):**
- ABSENT/unwired: Help dialog (`HomeIntent.OnHelp` + `HomeEffect.ShowHelp` exist; no top-bar trigger, `onHelp` TODO no-op — `HomeReworkScreenRoute.kt:158`).
- ABSENT at runtime: per-tab source icons (`SourceTabsRow.iconForTab` not supplied); "new source" badge (`showNewBadge=false` hardcoded); featured carousel `coverModel` not threaded; featured carousel card title overlay (cover-only).
- DEVIATION: featured carousel uses plain `LazyRow` (no M3 carousel scale/snap animation); Search runs per-keystroke (no debounce); filters apply to single-source search only.
- STALE DOC (not a runtime bug): `HomeReworkScreenRoute.kt:35-36` KDoc says Details effects map to `Screen.MangaDetails`, but code actually navigates to `Screen.MangaDetailsRework` (`:96-106`, `:122-132`).

**Tests/previews:** `HomeScreen.kt` has list/grid/maintenance `@Preview`s (`:462-520`); `SearchScreen.kt` single/multi previews (`:443-478`); `SearchFilterSheet.kt` preview (`:168-183`). Stateless `*Content` hosts split from VM-bound entry for previewability.


---

# CLUSTER: library

# KMP (rework) — Library cluster audit

Read-only audit of the rework Library cluster as wired at runtime. Scope files read in full:
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/library/LibraryScreen.kt` (1097 lines)
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/library/LibraryOptionsSheet.kt` (256 lines)
- `presentation/.../library/LibraryState.kt`, `LibraryIntent.kt`, `LibraryEffect.kt`, `LibraryViewModel.kt`
- `composeApp/.../navigation/routes/LibraryScreenRoute.kt`
- `composeApp/.../App.kt` (route wiring), `navigation/Screen.kt`
- `domain/.../model/LibraryManga.kt`, `domain/.../model/library/{LibrarySort,LibraryFilter,LibraryCategory,LibraryDisplay,GridDensity,SortDirection}.kt`

**Runtime-binding confirmation.** `App.kt:445` declares `composable<Screen.Library> { backStackEntry -> ... LibraryScreenRoute(navController, backStackEntry) }`. The root start destination is `Screen.Library` for a non-first-launch session (`App.kt:393`: `val rootStart = if (firstLaunch) Screen.Welcome else Screen.Library`). `LibraryScreenRoute` (`LibraryScreenRoute.kt:159-219`) resolves the rework `LibraryViewModel` via `koinViewModel()` (`:163`) and renders the rework `:ui` `LibraryScreen` (`:187`). Therefore the Library tab renders the rework screen at runtime — the legacy library screen was retired (§347). The `library_details` matches found on disk are all `build/` compiled artifacts (legacy `shared/build`, `composeApp/build`), NOT live source — there is **no rework library-details (per-manga chapter list) screen** (see Cluster notes).

---

### LibraryScreen
- **Entry/route:** `Screen.Library` → `LibraryScreenRoute` (`App.kt:445-451`, `LibraryScreenRoute.kt:158-219`). VM is `koinViewModel(): LibraryViewModel`. Bottom nav bar is shown (`App.kt:446 onBottomBarVisibleChange(true)`). Public composable `LibraryScreen(viewModel, onNavigateToDetails, onNavigateToDownloads, modifier)` (`LibraryScreen.kt:184-200`) delegates to stateless `LibraryScreenContent` (`:207-305`). On first composition `LaunchedEffect(Unit) { onIntent(LibraryIntent.OnEnter) }` (`:230`) starts the library Flow observation.
- **Layout & components:** Material 3 `Scaffold` (`:244`) with `topBar = LibraryTopBar` and `snackbarHost = SnackbarHost(snackbarHostState)`. Body is a `PullToRefreshBox` (`:256`) wrapping a `Box(fillMaxSize)` (`:263`) that switches on state: loading spinner / empty message / `LibraryGrid`. Two overlay surfaces conditionally render outside the Scaffold: `DeleteSelectedDialog` (`:283`) and `LibraryOptionsSheet` (`:294`).
- **Visual:** Spacing via `LocalSpacing.current` (tokens `xs/sm/md/lg`). Top bar `Column` background = `colorScheme.surface` (`:384`). Cards: `RoundedCornerShape(8.dp)` (`:729`), `elevation = 3.dp` (`:736`, UP-4 lift), containerColor `primaryContainer` when selected else `surfaceVariant` (`:731-732`). Title `bodyMedium` + `FontWeight.Medium`, maxLines 2, ellipsis (`:797-800`). Captions `labelSmall` + `onSurfaceVariant`. Unread count `labelSmall` + `primary` (`:806-807`). Badges tinted: downloaded-check & downloaded-count `tertiary`, bookmark `secondary`, download top-bar badge `primary`.
- **States:** loading → `CircularProgressIndicator` centered (`:265`); empty → `EmptyLibraryMessage` centered (`:266`, text depends on `isSearching`); success → `LibraryGrid` (`:270`). Error is surfaced two ways: as a one-shot snackbar via `LibraryEffect.ShowError` (`:236`) and stored in `state.error` (set by `startObserving().catch`, `VM:298`) though the `state.error` field is **never rendered** by the UI (snackbar is the only error surface; `state.error` is effectively unwired for display — see Feature inventory). `isLoading` defaults `true` (`LibraryState.kt:65`) so first frame is the spinner.
- **Interactions:** pull-to-refresh dispatches `OnRefresh` (`:258`); card tap/long-press via `detectTapGestures` (`:740-743`) → `OnItemClick` / `OnItemLongClick`. Search text field `onValueChange` → `OnSearchQueryChange`. Top-bar actions: options (Tune icon), download badge tap, Random, Refresh. Category tabs tap → `OnCategoryChange`. No explicit list/grid animations beyond default Compose recomposition + PullToRefresh indicator.
- **Dialogs/sheets/snackbars:** `DeleteSelectedDialog` (AlertDialog, `:337-373`); `LibraryOptionsSheet` (ModalBottomSheet, separate file); snackbars for `ShowError` and `ShowBulkRemoveSuccess` (`:236-239`).
- **Forms & validation:** the inline search `OutlinedTextField` (`:421-429`, singleLine, placeholder `library_search_hint`). No validation — search is permissive substring filtering (VM `applyView`, case-insensitive `contains`, `VM:637`).
- **Data/behavior:** VM observes 9 reactive use cases in `init{}` (refresh-state, sort, sort-direction, filter, grid-density, category, last-updated, downloads, display-bundle) plus the library Flow started lazily on `OnEnter` (`VM:284-302`). `OnItemClick` emits `NavigateToDetails`; in selection mode it instead toggles selection (`VM:304-311`). Navigation maps to `Screen.MangaDetailsRework(api, language, title, url, coverUrl, rating, genres)` carrying the full saved identity (`LibraryScreenRoute.kt:201-211`) — explicit fix for the "opens fresh" regression. Download badge tap → `Screen.DownloadsRework` (`:217`). Route also hosts a `WhatsNewViewModel` first-launch redirect to `Screen.WhatsNewScreen(true)` (`:164-185`).
- **Feature inventory:**
  1. Library grid (adaptive cell size per density).
  2. Inline search field (substring, case-insensitive, local).
  3. Top bar: options (Tune) button, active-downloads badge (count, tap→Downloads, hidden when 0), Random text button, Refresh text button.
  4. Selection-mode top bar variant: "N selected" title + Delete + Cancel actions.
  5. Category tabs (NAN/LIKED/WATCHING_NOW), gated on `display.showTabs`.
  6. "Last updated: <relative>" / "Never updated" status row.
  7. Pull-to-refresh.
  8. Manga cards with cover, action overlay, title, badges, captions (detailed below).
  9. Bulk-delete confirmation dialog.
  10. Tabbed options sheet (Filter / Sort / Display).
  11. Empty + loading + (snackbar) error states.
  - **Present-but-unwired:** `state.error` field is populated but never read by any composable. `LibraryIntent.OnToggleInLibrary` and `OnSelectionToggle` exist and are handled in the VM but are **not dispatched by any `:ui` affordance** (no in-grid library-membership toggle; selection toggling happens via long-press path `OnItemLongClick` and via tap-while-in-selection-mode which routes through `OnItemClick`→`OnSelectionToggle` in the VM, `VM:307`). `gridDensityLabel`/`librarySortLabel`/`libraryFilterLabel` "title-row item count" caption described in `LibraryDisplay.showCount` KDoc (`:17` "N items" under tab row) is **not implemented** — `showCount` instead gates per-card downloaded/bookmark badges.
- **Citations:** `LibraryScreen.kt:184-305`, `:230`, `:244-281`, `App.kt:393,445-451`, `LibraryScreenRoute.kt:158-219`.

---

### LibraryTopBar
- **Entry/route:** rendered as `Scaffold.topBar` (`LibraryScreen.kt:246-253`); defined `:375-448`.
- **Layout & components:** `Column(background surface)` containing either the selection-mode `TopAppBar` or the normal `TopAppBar` + search field + (conditional) `CategoryTabs` + `LastUpdatedRow`.
- **Visual:** `TopAppBar` (M3 default). Search field padded `horizontal lg, vertical sm` (`:428`). Selection title `library_selected_count` (`:387`).
- **States:** branches on `state.isInSelectionMode` (`:385`). Selection mode hides search/tabs/last-updated and shows Delete/Cancel.
- **Interactions:** normal mode actions — `YamiIconButton(Tune, library_options)` → opens options sheet (`:404-408`); `DownloadProgressBadge` tap (`:409-412`); Random `TextButton` → `OnOpenRandom` (`:413-415`); Refresh `TextButton` → `OnRefresh` (`:416-418`). Selection mode — Delete `TextButton` → `OnDeleteSelected` (`:389`); Cancel `TextButton` → `OnSelectionClear` (`:392`).
- **Dialogs/sheets/snackbars:** opens `LibraryOptionsSheet` (via `showOptionsSheet` screen-local boolean, `:223,250`).
- **Forms & validation:** search `OutlinedTextField` (covered above), only in normal mode.
- **Data/behavior:** `CategoryTabs` only when `state.display.showTabs` (`:439`). `LastUpdatedRow(state.lastUpdated)` always present in normal mode (`:445`).
- **Feature inventory:** options/Tune, download badge, Random, Refresh, selection Delete, selection Cancel, search field, category tabs (conditional), last-updated row. NOTE: Random and Refresh are plain `TextButton`s (text labels), not icons — `library_random` and `dropdown_button_refresh` strings.
- **Citations:** `LibraryScreen.kt:375-448`.

---

### LibraryCard (manga card)
- **Entry/route:** item template in `LibraryGrid` (`LibraryScreen.kt:706-712`); defined `:717-970`.
- **Layout & components:** `Card` → `Column(padding sm)` containing: (1) `BoxWithConstraints` holding the cover (`LibraryCardCover`) + optional `LibraryCardActionRow` overlay aligned `CenterEnd`; (2) a title `Row` with inline unread count + downloaded-check + downloaded-count + bookmark badges; (3) source caption; (4) last-read caption; (5) added-at caption; (6) chapter-progress caption.
- **Visual:** see LibraryScreen Visual. Action-row buttons adaptively sized `cardWidth * 0.22f` clamped `4..40 dp` (`:768-769`). Selected card → `primaryContainer` bg + `primary` placeholder tint (`:727,731`).
- **States:** selection visual via `isSelected = item.manga.toKey() in selection` (`:708`). Captions/badges conditionally rendered on data presence AND display flags.
- **Interactions:** `pointerInput(...) detectTapGestures(onTap = OnItemClick, onLongPress = OnItemLongClick)` (`:739-744`). Action-row icons each dispatch their intent. Action row hidden when `isInSelectionMode` (`:771`).
- **Dialogs/sheets/snackbars:** single-delete from card action row dispatches `OnSingleDelete` directly (no per-card confirm dialog — VM routes through bulk-remove with 1-element list, `VM:599-603`).
- **Forms & validation:** none.
- **Data/behavior:** binds `LibraryManga` fields. Cover via shared `YamiCoverImage(scrim=true)`; explicitly NOT blurred for adult content in library (blur is a Details-only concern, `:983-985`).
- **Feature inventory (EVERY caption/badge/affordance):**
  - **Cover thumbnail** — `LibraryCardCover` → `YamiCoverImage(coverUrl, placeholderTint, scrim=true)` (`:770,987-995`). No loading spinner (calm fill-in), shows broken-image glyph on error.
  - **Action row overlay (3 buttons)** — gated `display.showButtons && !isInSelectionMode` (`:771`). Vertically stacked, CenterEnd:
    - Watch-now toggle — `WatchingNowOn`/`WatchingNowOff` icon, tint `primary`, → `OnToggleWatchingNow` (`:1036-1046`).
    - Like toggle — `FavoriteFilled`/`FavoriteOutline`, tint `Color.Red`, → `OnToggleLike` (`:1047-1057`).
    - Delete — `Delete` icon, tint `onErrorContainer`, → `OnSingleDelete` (`:1058-1064`).
  - **Title** — always; `bodyMedium` Medium, 2 lines (`:795-802`).
  - **Unread count** — when `unreadCount > 0`; `labelSmall` primary; raw number, no icon (`:803-810`).
  - **Downloaded-check badge** — `display.showCount && hasDownloads`; `YamiCountBadge(Check, tertiary)`, no count number (`:817-824`).
  - **Downloaded-count badge** — `display.showCount && downloadedCount > 0`; `YamiCountBadge(Download, count, tertiary)` (`:830-838`).
  - **Bookmark-count badge** — `display.showCount && bookmarkedCount > 0`; `YamiCountBadge(Bookmark, count, secondary)` (`:844-852`).
  - **Source caption** — `display.showSource && manga.api.isNotBlank()`; raw `manga.api` text, `labelSmall` (`:863-872`).
  - **Last-read caption** — `display.showDetails && lastReadAt != null`; `library_card_last_read` + relative time (`:892-905`).
  - **Added-at caption** — `display.showDetails` (always, non-null field); `library_card_added` + relative time (`:927-939`).
  - **Chapter-progress caption** — `display.showDetails && totalChapters > 0`; `library_card_chapters_progress(readCount, totalChapters)` where `readCount = (totalChapters - unreadCount).coerceIn(0, totalChapters)` (`:953-967`).
  - Caption order under showDetails umbrella: last-read → added → progress.
- **Citations:** `LibraryScreen.kt:686-1066`.

---

### CategoryTabs
- **Entry/route:** in top bar, gated on `state.display.showTabs` (`LibraryScreen.kt:439-444`); defined `:532-550`.
- **Layout & components:** M3 `TabRow(containerColor = surface)` with one `Tab` per `LibraryCategory.entries` (NAN, LIKED, WATCHING_NOW). Selected index = `entries.indexOf(category)`.
- **Visual:** flush with top-bar surface.
- **States:** selected tab reflects `state.category`.
- **Interactions:** tap → `OnCategoryChange(option)` → VM updates category, re-runs `applyView`, persists via `SetLibraryCategoryUseCase` (`VM:421-429`).
- **Labels:** `libraryCategoryLabel` — NAN→`filter_all` ("All"), LIKED→`library_category_liked`, WATCHING_NOW→`library_category_watching` (`:558-563`).
- **Feature inventory:** 3-way affinity category narrowing. Persisted.
- **Citations:** `LibraryScreen.kt:532-563`, `VM:183-192,421-429`.

---

### LastUpdatedRow
- **Entry/route:** top bar, normal mode, always rendered (`LibraryScreen.kt:445`); defined `:616-633`.
- **Layout & components:** single `Text`, `labelSmall`, `onSurfaceVariant`, full width.
- **Visual/States:** `lastUpdated == null` → `not_updated_yet` ("Never updated"); else `last_updated` + `formatRelativeTime(lastUpdated, now)`.
- **Interactions:** none (read-only status indicator; no intent mutates `state.lastUpdated`).
- **Data/behavior:** VM observes `ObserveLibraryLastUpdatedUseCase` (`VM:199-201`). On iOS/Desktop the Android-only refresh worker never writes the cell so this always shows the fallback.
- **`formatRelativeTime`** buckets: just-now / minutes / hours / yesterday / days / weeks / months / years (`:650-670`), negative deltas → just-now.
- **Citations:** `LibraryScreen.kt:616-670`.

---

### DownloadProgressBadge
- **Entry/route:** top bar action (`LibraryScreen.kt:409-412`); defined `:575-589`.
- **Layout & components:** `YamiCountBadge(Download icon, count, tint=primary, labelMedium)`; composes nothing when `count <= 0` (`:577`).
- **Interactions:** `clickable` → `onNavigateToDownloads` → `Screen.DownloadsRework` (`LibraryScreenRoute.kt:217`).
- **Data/behavior:** VM observes `ObserveDownloadsUseCase`, counts rows where `state.isActive()` (RUNNING ∪ QUEUED ∪ COMPRESSING) (`VM:217-222,245-248`).
- **Citations:** `LibraryScreen.kt:575-589`, `VM:217-248`.

---

### DeleteSelectedDialog (bulk-delete confirmation)
- **Entry/route:** rendered when `state.isDeleteDialogVisible` (`LibraryScreen.kt:283-289`); defined `:337-373`.
- **Layout & components:** M3 `AlertDialog`. Title `library_delete_selected_title(count)` headlineSmall Bold; body `library_delete_selected_message` bodyMedium; confirm "Delete" (`delete`) error-colored Bold; dismiss "Cancel" (`cancel`) Medium.
- **States:** visibility lives in MVI state (`isDeleteDialogVisible`), set true by `OnDeleteSelected` (guarded on non-empty selection, `VM:327-330`), false on confirm/dismiss.
- **Interactions:** confirm → `OnDeleteSelectedConfirm` (`VM:332-348`: clears selection, exits selection mode, hides dialog, calls `BulkRemoveFromLibraryUseCase`, emits `ShowBulkRemoveSuccess(count)` or `ShowError`). dismiss (button / outside tap / system back) → `OnDeleteSelectedDismiss` (hides dialog, selection preserved, `VM:262-264`).
- **Forms & validation:** none beyond the empty-selection guard.
- **Citations:** `LibraryScreen.kt:283-373`, `VM:327-348`.

---

### LibraryOptionsSheet (Filter / Sort / Display tabbed bottom sheet)
- **Entry/route:** rendered when screen-local `showOptionsSheet` true (`LibraryScreen.kt:294-304`); defined in `LibraryOptionsSheet.kt:72-198`.
- **Layout & components:** M3 `ModalBottomSheet(skipPartiallyExpanded = true)` with a `TabRow` (Filter / Sort / Display tabs, `:88-104`) over a `Column(padding lg/md)`. Tab selection is sheet-local `remember { mutableIntStateOf(0) }` (`:85`).
- **Visual:** selected option rows show `primary` text + `FontWeight.Medium` + trailing `Check` icon (`OptionRow`, `:204-235`). Toggle rows use M3 `Switch` (`ToggleRow`, `:238-255`).
- **States:** reflects `filter`, `sort`, `sortDirection`, `density`, `display` from `LibraryState`.
- **Interactions / Feature inventory (EVERY option):**
  - **Filter tab** — one `OptionRow` per `LibraryFilter.entries` → `OnFilterChange`. Values: ALL, DOWNLOADED, UNREAD, STARTED, COMPLETED, BOOKMARKED (`:111-117`). Labels via `libraryFilterLabel` (`LibraryScreen.kt:471-478`).
  - **Sort tab** — one `OptionRow` per `LibrarySort.entries` → `OnSortChange`. Values: ALPHABETIC, DATE_ADDED, UNREAD_COUNT, TOTAL_CHAPTERS, LAST_READ, RANDOM (`:119-125`). Below them, a sort-direction toggle Row (`library_toggle_sort_direction` + ascending/descending icon) → `OnSortDirectionToggle` — **hidden when sort == RANDOM** (`:128-152`).
  - **Display tab** — density section header (`library_density`) then one `OptionRow` per `GridDensity.entries` (COMPACT/COMFORTABLE/SPACIOUS) → `OnGridDensityChange` (`:154-167`); then a divider; then 5 `ToggleRow`s → toggle intents:
    - `show_items_details` → `OnToggleShowDetails` (`:169-173`)
    - `show_items_source` → `OnToggleShowSource` (`:174-178`)
    - `show_items_count` → `OnToggleShowCount` (`:179-183`)
    - `show_buttons` → `OnToggleShowButtons` (`:184-188`)
    - `show_tabs_all_likes_etc` → `OnToggleShowTabs` (`:189-193`)
- **Data/behavior:** every option commits synchronously through the reducer + persists via the matching `Set*UseCase`. Dismiss closes via `onDismiss` (`:81`).
- **Citations:** `LibraryOptionsSheet.kt:72-255`, label helpers `LibraryScreen.kt:457-511`.

---

### Sort / Filter / Display / Density semantics (data/behavior)
- **Sort** (`LibrarySort.kt`): ALPHABETIC (title lowercase), DATE_ADDED (`addedAt`), UNREAD_COUNT, TOTAL_CHAPTERS, LAST_READ (null→`Long.MAX_VALUE` so unread sink in ascending), RANDOM (seeded stable shuffle, ignores direction). Applied in `VM.applyView` (`VM:652-665`). RANDOM regenerates `randomSeed` on (re)selection (`VM:370-384`). Persisted via `SetLibrarySortUseCase` / `SetLibrarySortDirectionUseCase`.
- **SortDirection**: ASCENDING/DESCENDING; descending applied via `asReversed()` except for RANDOM (`VM:666-670`).
- **Filter** (`LibraryFilter.kt`): ALL / DOWNLOADED (`hasDownloads`) / UNREAD (`unreadCount>0`) / STARTED (`unreadCount<totalChapters`) / COMPLETED (`totalChapters>0 && unreadCount==0`) / BOOKMARKED (`bookmarkedCount>0`) (`VM:644-651`). Persisted.
- **Category** (`LibraryCategory.kt`): NAN/LIKED/WATCHING_NOW; applied BEFORE filter (`VM:639-643`). Persisted.
- **Display bundle** (`LibraryDisplay.kt`): 5 booleans showSource/showCount/showDetails/showButtons/showTabs, all default `true`; persisted to legacy `library_show_*` disk cells, observed via `ObserveLibraryDisplayUseCase`.
- **GridDensity** (`GridDensity.kt`): COMPACT→96.dp, COMFORTABLE→120.dp, SPACIOUS→160.dp via `GridDensity.minSize()` feeding `GridCells.Adaptive` (`LibraryScreen.kt:507-511,697`). Persisted.
- **applyView pipeline order:** search → category → filter → sort → optional reverse (`VM:624-671`). Pure/deterministic. Re-run on every relevant flow emission and intent.
- **Citations:** `VM:624-671`, domain enum files.

---

### Cluster notes

- **Runtime wiring is the rework screen** — confirmed at `App.kt:393,445-451` + `LibraryScreenRoute.kt:158-219`. Legacy Library UI retired (§347); only `build/` artifacts remain for the legacy `library_details` package.

- **No per-manga chapter-list (library_details equivalent) in the rework.** There is no rework `:ui`/`:presentation` screen mirroring the legacy `presentation/features/library_details/` (`LibraryMangaScreen` + `LibraryDetailsViewModel` with per-chapter read/bookmark/download/delete, chapter sort/filter, total-size display, mark-read actions). Tapping a library card navigates to **`Screen.MangaDetailsRework`** (the network/details screen), NOT a library-scoped chapter list. The full saved identity tuple (api, language, title, url, cover, rating, genres) is carried to fix the "opens fresh" regression (`LibraryScreenRoute.kt:189-211`). **Parity gap vs native: the dedicated library-details chapter management screen does not exist in the rework** — its chapter-level operations (mark chapters read, bookmark chapters, delete downloaded chapters, chapter sort/filter, total download size) are only reachable, if at all, through the Details/Downloads screens. (INFERRED parity gap — confirm against the OLD audit's `library_details` coverage.)

- **Present-but-unwired / dead surfaces:**
  - `LibraryState.error` is written by `startObserving().catch` (`VM:298`) but **never rendered** — the only error surface is the `ShowError` snackbar. (INFERRED dead field.)
  - `LibraryIntent.OnToggleInLibrary(manga)` is fully handled in the VM (`VM:350-354`) but **no `:ui` affordance dispatches it** — the library card has no add/remove-from-library heart toggle distinct from the action-row Like/Delete. (INFERRED unwired intent.)
  - `LibraryDisplay.showCount` KDoc claims it gates an "N items" count label under the tab row (`LibraryDisplay.kt:17`), but the rework gates per-card downloaded/bookmark badges on it instead — **the documented "items count" label is not implemented**. (INFERRED doc/impl mismatch.)
  - `OnSelectionToggle` is reachable only via the VM's `OnItemClick`-while-in-selection path (`VM:307`); there is no direct `:ui` checkbox/toggle dispatching `OnSelectionToggle`.

- **Selection model.** Long-press a card enters multi-select (`OnItemLongClick`); subsequent taps toggle membership (routed through `OnItemClick`→`OnSelectionToggle`). Selection mode swaps the top bar (N-selected + Delete/Cancel) and hides each card's action-row overlay. Bulk delete is two-step (confirm dialog); per-card delete (action row) is one-step (no confirm). This asymmetry is intentional (`LibraryIntent.kt:326-345`).

- **Status indicators on iOS/Desktop.** `LastUpdatedRow` and (potentially) `DownloadProgressBadge` depend on Android-only writers; the last-updated row always shows "Never updated" off-Android (`VM:194-198`).

- **Relative-time formatting** is duplicated inline in `:ui` (`formatRelativeTime`, `LibraryScreen.kt:650-670`) rather than shared with other screens — intentional to avoid a cross-layer leak into `:composeApp`'s date helper. Used by last-updated row, card last-read, and card added-at.

- **Localization.** All visible strings resolve through `stringResource(Res.string....)`; keys verified present in `ui/src/commonMain/composeResources/values/strings.xml` (and `values-ar/`). Error snackbar strings are pre-resolved in composable scope via `rememberAppErrorMessages()` because `stringResource` can't run in the effect collector (`:1076-1096`).

- **Heavy KDoc audit-trail postscripts** (§253 / cluster sweeps) dominate several files but are historical record only — they do not affect runtime behavior. Card grid uses `key = manga.keyString()` ("api/language/title") to keep LazyGrid keys stable.


---

# CLUSTER: details_reader

# KMP (rework) audit — Manga Details + Reader cluster

Read-only audit of the architecture-rework app's Details + Reader surfaces. All citations are
absolute `file:line`. Inferences are marked `(INFERRED)`.

## Runtime routing map (split-brain analysis)

This is **NOT a split-brain** in the screen sense — every entry path lands on the **same rework
`:ui` `DetailsScreen` and the same rework `:ui` `ReaderScreen`**. The "two routes" are two thin
route-adapter functions over the same VM + same composable. Verified from
`composeApp/.../App.kt`:

| Nav key | App.kt block | Adapter fn | Renders | Reached from |
|---|---|---|---|---|
| `Screen.MangaDetails(mangaUrl, api)` (legacy key) | `App.kt:469-493` | `MangaDetailsByUrlReworkScreenRoute` | rework `DetailsScreenByUrl` → `OnEnterByUrl` | Home / Library / History / Updates manga taps (all 4 legacy caller sites unchanged) |
| `Screen.MangaDetailsRework(full tuple)` | `App.kt:620-626` | `MangaDetailsReworkScreenRoute` | rework `DetailsScreen` → `OnEnter` | debug-only (`navController.navigate(...)`); no user entry |
| `Screen.ChapterImagesFragment(legacy wide tuple)` | `App.kt:495-508` | `ChapterImagesByLegacyArgsReworkScreenRoute` | rework `ReaderScreen` | Home / History / Updates chapter taps (legacy key, swapped to rework Reader at slice R4) |
| `Screen.ChapterImagesRework(min tuple)` | `App.kt:636-642` | `ChapterImagesReworkScreenRoute` | rework `ReaderScreen` | rework `DetailsScreen` chapter-row tap (`onNavigateToReader`) |

Key facts:
- Legacy `MangaDetailsScreen` + `MangaDerailsViewModel` + legacy reader (`ChapterImagesScreenRoute`)
  + `:shared` `ReaderViewModel`/`SharedChaptersViewModel` were all **retired** (§430 details, R5 reader).
  No legacy Details or Reader screen is user-reachable. (`App.kt:497-503`, `Screen.kt:128-151`)
- Details from **Home/Library/History/Updates** → `MangaDetailsByUrlReworkScreenRoute` (URL-only),
  whose chapter-row tap navigates to `Screen.ChapterImagesRework` (the rework reader minimal-tuple
  key), NOT to `Screen.ChapterImagesFragment`. (`MangaDetailsReworkScreenRoute.kt:213-226`)
- Chapter taps from **Home/History/Updates list rows** (not via Details) still emit the legacy
  `Screen.ChapterImagesFragment(...)` → `ChapterImagesByLegacyArgsReworkScreenRoute`. (`App.kt:495-508`)
- **One latent asymmetry (INFERRED):** the legacy-args reader adapter
  (`ChapterImagesByLegacyArgsReworkScreenRoute`) sets `chapter.name = args.chapterNumber`
  (`ChapterImagesByLegacyArgsReworkScreenRoute.kt:118-125`) i.e. the chapter **name is the number**,
  whereas the Details→`ChapterImagesRework` path passes the real `chapter.name`
  (`MangaDetailsReworkScreenRoute.kt:122`). The reader top-bar title therefore reads the chapter
  number (not name) when reached from a list-row tap, but the proper name when reached via Details.
  Both adapters host identical multi-chapter Next/Prev (it's VM-internal), so navigation is unaffected.

---

### Manga Details page
- **Entry/route:** REWORK for all user paths. `composable<Screen.MangaDetails>` →
  `MangaDetailsByUrlReworkScreenRoute` (`App.kt:489`, adapter at
  `MangaDetailsReworkScreenRoute.kt:194-233`); debug full-tuple route →
  `MangaDetailsReworkScreenRoute` (`App.kt:622`). Both host the same `:ui` `DetailsScreen` /
  `DetailsScreenByUrl` (`DetailsScreen.kt:131-204`) over one Koin `DetailsViewModel`.
- **Layout & components:** `Scaffold` with `DetailsTopBar` + snackbar host (`DetailsScreen.kt:285-322`).
  Body = `VerticalFastScroller`-wrapped `LazyColumn` (`DetailsScreen.kt:501-544`) with items:
  `"header"` (`DetailsHeader`), optional `"description"` (`bodyMedium` Text), optional `"genres"`
  (`GenreChipRow`), `"chapters-header"` (count title), then `ChapterRow` per chapter keyed by
  `chapter.url` (`DetailsScreen.kt:511-542`). Header = `Box` with blurred gradient backdrop
  (`DetailsHeaderBackdrop`, `DetailsScreen.kt:682-709`) behind a `Row` of `DetailsCover` (96dp,
  aspect 0.7) + metadata Column (title / author / status / `api · language` source line / `★ rating`
  / Schedule `AssistChip` last-chapter-date / conditional "Download all" `OutlinedButton`)
  (`DetailsScreen.kt:549-668`).
- **Visual:** spacing from `LocalSpacing` (lg/md/sm/xs). Cover `RoundedCornerShape(6.dp)`,
  `surfaceVariant` placeholder bg (`DetailsScreen.kt:787,805-809`). Backdrop blur 24dp + vertical
  gradient scrim `surface 0.45f → surface` (`DetailsScreen.kt:693,701-706`). Adult cover blur is an
  **animated** 32dp↔0dp `animateDpAsState` 300ms tween (`DetailsScreen.kt:801-804,833-835`). Title
  `titleLarge`/SemiBold 2-line ellipsis; status `labelLarge`/primary; source/rating `bodySmall`/
  onSurfaceVariant; genres up to `MAX_GENRE_CHIPS = 8` `AssistChip`s (`DetailsScreen.kt:847,901`).
  Read chapters dimmed to onSurfaceVariant, unread onSurface (`DetailsScreen.kt:871-875`). Downloaded
  chapter shows an 8dp primary dot (`DetailsScreen.kt:888-897`).
- **States:**
  - loading: `state.isInitialLoading` (loading && no details) → centered `CircularProgressIndicator`
    (`DetailsScreen.kt:334-336`).
  - error: `error != null && !hasDetails` → `DetailsErrorPane` (message + "Open in WebView"
    `OutlinedButton` + Retry `Button`) (`DetailsScreen.kt:337-342,449-478`).
  - success: `details != null` → `DetailsBody`. Note saved/local-DB details can land BEFORE network,
    so a Library-opened manga renders its chapter list immediately/offline (`DetailsViewModel.kt:177-191`).
  - 403/Cloudflare: fetch failure with `statusCode in {403,429,503,520-524}` emits
    `SolveCloudflareChallenge` instead of `ShowError` (`DetailsViewModel.kt:120,360-364`); UI routes
    to WebView + auto-retries on return (`DetailsScreen.kt:276-280`, solver at
    `MangaDetailsReworkScreenRoute.kt:251-279`).
  - empty: no dedicated empty pane; a 0-chapter success renders header + "0 chapters" title only (INFERRED).
- **Interactions:** chapter row `clickable` → `OnChapterClick` → `NavigateToReader` effect
  (`DetailsScreen.kt:541`, `DetailsViewModel.kt:368-371`). Title **long-press** copies title to
  clipboard via `LocalClipboardManager` (`DetailsScreen.kt:583-586`). Cover-blur unblur is a
  300ms tween. Genre chips + last-chapter `AssistChip` are non-interactive (`onClick = {}`,
  chip disabled) (`DetailsScreen.kt:638-639,848`). No pinch-zoom, no swipe on this screen.
- **Dialogs/sheets/snackbars:** (1) `AdultConfirmationDialog` — `AlertDialog` shown once per manga
  visit when `state.isAdult`, Continue dismiss+unblur / Go Back dismiss+`OnBackClick`
  (`DetailsScreen.kt:353-364,970-1002`). (2) `AddBookmarkConfirmDialog` — first-time-add confirm on
  the not-in-library bookmark tap; remove path is direct, no confirm (asymmetric, legacy parity)
  (`DetailsScreen.kt:366-374,1033-1064`). (3) Snackbar host for `ShowError` (localized via
  `AppErrorMessages`) (`DetailsScreen.kt:273,322,931-949`). **No sort/filter bottom sheet** on the
  rework Details (gap vs legacy — see Cluster notes).
- **Forms & validation:** none on Details.
- **Data/behavior:**
  - fetch: `OnEnter`(full tuple) or `OnEnterByUrl`(api,url) → `FetchMangaDetailsUseCase`; re-entry
    idempotent on (api,language,title) or (api,url) (`DetailsViewModel.kt:137-249`).
  - offline-first: `ObserveSavedMangaDetailsUseCase` renders local Room chapter list immediately,
    then network details are `overlaidWith` saved read/downloaded/bookmark marks so refresh never
    wipes progress (`DetailsViewModel.kt:177-191,294-350,467-480`).
  - bookmark: `OnToggleInLibrary` → `ToggleInLibraryUseCase`; reactive via `ObserveInLibraryUseCase`,
    `isTogglingBookmark` double-tap gate (`DetailsViewModel.kt:264-276,437-448`); bookmark button
    disabled until title resolves (URL-only guard) (`DetailsScreen.kt:307`).
  - downloads: top-bar Download button → `NavigateToDownloads` → `Screen.DownloadsRework`
    (`DetailsViewModel.kt:131`, `MangaDetailsReworkScreenRoute.kt:131`). Header "Download all"
    (in-library only) → `EnqueueAllChaptersDownloadUseCase`, fire-and-forget
    (`DetailsViewModel.kt:393-399`, UI gate `DetailsScreen.kt:655-666`).
  - webview: top-bar ↗ + error-pane button → `OnOpenInWebView` → `NavigateToWebView` →
    `Screen.WebView(url,api)` (`DetailsViewModel.kt:415-418`, `MangaDetailsReworkScreenRoute.kt:139`).
  - adult classify: tentative from nav genres, re-classified from fetched genres
    (`DetailsViewModel.kt:141,300-301`).
  - navigation: back via `OnBackClick` → `NavigateBack` → `safePopBackStack`. No history write from
    Details (history is recorded by the Reader on chapter open) (INFERRED — no history use case in `DetailsViewModel`).
- **Feature inventory:** back; refresh (disabled while loading); bookmark heart (filled/outline, add-confirm
  dialog, double-tap gate); Downloads (→ DownloadsRework); Open-in-WebView (top-bar + error-pane);
  Download-all (in-library only); chapter open; title long-press-copy; cover adult-blur + adult dialog;
  source/language line; rating caption; last-chapter-date chip; genre chips (max 8); fast-scroller
  quick-jump; offline saved-details render; per-chapter read-dim + downloaded-dot; 403 auto-recovery.
  **Absent vs legacy:** sort dropdown, filter bottom sheet, per-chapter download/bookmark/mark-read
  actions on the row, "share manga", schedule/help affordances beyond the inert date chip.
- **Citations:** `DetailsScreen.kt:131-1064`; `DetailsViewModel.kt:84-480`; `DetailsState.kt:83-98`;
  `DetailsIntent.kt:66-183`; `DetailsEffect.kt:15-96`; `MangaDetailsReworkScreenRoute.kt:85-279`;
  `App.kt:469-493,620-626`.

### Reader (all reading modes)
- **Entry/route:** REWORK for all user paths. Legacy key `Screen.ChapterImagesFragment` →
  `ChapterImagesByLegacyArgsReworkScreenRoute` (`App.kt:504`, adapter
  `ChapterImagesByLegacyArgsReworkScreenRoute.kt:79-150`); rework key `Screen.ChapterImagesRework`
  → `ChapterImagesReworkScreenRoute` (`App.kt:638`, `ChapterImagesReworkScreenRoute.kt:71-165`).
  Both host the same `:ui` `ReaderScreen` (`ReaderScreen.kt:294-320`) over one Koin `ReaderViewModel`.
- **Layout & components:** `Scaffold` with animated `ReaderTopBar` + snackbar host
  (`ReaderScreen.kt:426-462`). Body = `BoxWithConstraints` (harvests viewport height for placeholder
  reservation) with a tap-detector for chrome toggle (`ReaderScreen.kt:474-486`). Page area wrapped in
  a `drawWithContent` `GraphicsLayer`-recording Box for share-capture (`ReaderScreen.kt:507-514`).
  `ReaderPageLayout` dispatches by `ReadingMode` (`ReaderScreen.kt:899-963`):
  - RIGHT_TO_LEFT → `ReaderHorizontalPager(reverse=true)`
  - LEFT_TO_RIGHT → `ReaderHorizontalPager(reverse=false)`
  - DEFAULT / VERTICAL → `ReaderVerticalPager`
  - WEBTOON / CONTINUOUS_VERTICAL → `ReaderVerticalList` (free-scroll `LazyColumn`)
  Bottom chrome stack (`ReaderScreen.kt:542-575`): HUD pill (`ReaderPageIndicatorHud`) + jump-to-page
  `Slider` (`ReaderPageScrubber`, only when >1 page).
- **Visual:** top bar `titleMedium` ellipsized chapter title; HUD pill = `surfaceVariant` rounded
  `Surface` "X / Y" no elevation (`ReaderScreen.kt:776-794`). LazyColumn modes paint
  `colorScheme.background` behind items (gapless webtoon) (`ReaderScreen.kt:1042`). Pagers use
  `ContentScale.Fit`; vertical list uses `ContentScale.FillWidth` (`ReaderScreen.kt:1213,1136,1200`).
  Per-page placeholder reserves `defaultMinSize(minHeight = screenHeightDb)` so streaming items don't
  collapse (`ReaderScreen.kt:1296,1331`). Chrome fades+slides via `AnimatedVisibility` on
  `state.isUiVisible` (`ReaderScreen.kt:433-437,553-557`).
- **States:**
  - loading: `isInitialLoading` → centered `CircularProgressIndicator` (`ReaderScreen.kt:492-494`).
  - per-page loading: determinate ring when `PageDownloadProgress.InProgress.fraction != null`, else
    indeterminate spinner (`ReaderScreen.kt:1292-1304`). Android gets per-byte fraction; iOS/Desktop
    Started→Complete only (`ReaderScreen.kt:1248-1263`).
  - error (chapter-level): `error != null && !hasPages` → `ReaderErrorPane` (message + Retry)
    (`ReaderScreen.kt:495-499,1367-1387`).
  - per-page error: `failed_to_load_image` text + Retry (`painter.restart()`, Coil-level, no MVI) +
    "Open in WebView" button (`ReaderScreen.kt:1306-1363`).
  - 403/Cloudflare: page-fetch failure with `statusCode == 403` emits
    `ReaderEffect.SolveCloudflareChallenge` (not ShowError) → WebView + auto-retry on return
    (`ReaderViewModel.kt:534-540`, solver `ChapterImagesReworkScreenRoute.kt:182-211`).
  - success: `state.hasPages` → page layout.
- **Interactions:**
  - tap page area → `OnUiToggle` (show/hide chrome) (`ReaderScreen.kt:484`).
  - swipe: HorizontalPager (one page/swipe, RTL reverse), VerticalPager (one page/vertical swipe),
    LazyColumn free-scroll (`ReaderScreen.kt:1081-1203`).
  - pinch-zoom + pan: `net.engawapg.lib.zoomable` `.zoomable(rememberZoomState())` on ALL three
    layouts (pager modifier ordering `.zoomable` before `.fillMaxSize`) (`ReaderScreen.kt:1032,1120-1122,1185-1187`).
  - scrubber `Slider` drag → `OnPageChanged` → VM → layout `LaunchedEffect(currentPageIndex)` calls
    `scrollToItem`/`scrollToPage` (jump-to-page) (`ReaderScreen.kt:831-853,997-1002,1107-1112,1175-1180`).
  - scroll → `snapshotFlow{firstVisibleItemIndex / currentPage}.distinctUntilChanged()` → `OnPageChanged`
    (`ReaderScreen.kt:985-989,1099-1103,1168-1172`).
  - mode-toggle preserves scroll position via threaded `currentPageIndex` initial state
    (`ReaderScreen.kt:981,1095,1164`).
- **Dialogs/sheets/snackbars:** reading-mode `DropdownMenu` (not a dialog/sheet) anchored on the
  overflow icon, lists all 6 `ReadingMode.entries` with checkmark on the selected one
  (`ReaderScreen.kt:701-742`). Snackbar host for `ShowError` (`ReaderScreen.kt:462,387-413`). No
  bottom sheet. Per-page share uses platform share sheet via `ScreenshotProvider`
  (`ChapterImagesReworkScreenRoute.kt:153-160`).
- **Forms & validation:** none.
- **Data/behavior:**
  - fetch: `OnEnter(manga,chapter)` → `FetchChapterPagesUseCase` (streaming-aware; replaces page list
    on each Success; clamps page index) (`ReaderViewModel.kt:353-405,491-548`).
  - chapter list: `ListChaptersUseCase` on manga change → drives Next/Prev; silent on failure
    (`ReaderViewModel.kt:592-609`).
  - reading-mode persistence: `ObserveReadingModeUseCase` (init) + `SetReadingModeUseCase`; on-disk is
    single source of truth, no optimistic flip (`ReaderViewModel.kt:296-300,344-351`).
  - resume position: `LoadPagePositionUseCase` seeds index on enter; `SavePagePositionUseCase` writes
    on page change (fire-and-forget) (`ReaderViewModel.kt:369,457-466`).
  - history: `RecordHistoryUseCase` fired on every chapter establish/change; incognito-gated in use
    case (`ReaderViewModel.kt:403`).
  - mark-read: `MarkChapterReadUseCase` on reaching last page AND on Next-chapter advance (marks the
    leaving chapter) (`ReaderViewModel.kt:433-436,474-476`).
  - bookmark: `ObserveChapterBookmarkUseCase` per chapter + `ToggleChapterBookmarkUseCase`; reactive,
    degrades safely for not-in-library chapters (`ReaderViewModel.kt:333-342,407-420`).
  - statistics: `OnScreenResumed`/`OnScreenPaused` (DisposableEffect(Unit)) → Start/End reading
    session use cases; one continuous span across intra-manga Next/Prev (`ReaderScreen.kt:377-380`,
    `ReaderViewModel.kt:315-316`).
  - per-page progress: `PageProgressRepository` bridge; VM starts per-URL collectors on Success
    (`ReaderViewModel.kt:576-590`, reporter wired `ChapterImagesReworkScreenRoute.kt:163`).
  - system nav bar hidden via `HideNavigationBarSideEffect()` at route adapter
    (`ChapterImagesReworkScreenRoute.kt:79`, `ChapterImagesByLegacyArgsReworkScreenRoute.kt:86`).
  - share: `OnShareCurrentPage` → `ShareCurrentPage` effect → GraphicsLayer→ImageBitmap→PNG→platform
    share (`ReaderViewModel.kt:322-331`, `ReaderScreen.kt:401-405`).
  - navigation: back via `OnBackClick`→`NavigateBack`→`safePopBackStack`; multi-chapter Next/Prev is
    in-place `onEnter` recursion (no new nav destination) (`ReaderViewModel.kt:422-446`).
- **Feature inventory:** back; Prev-chapter / Next-chapter (disabled at list ends / during fetch);
  chapter-position label "N / M"; page-count label "X / Y" (top bar); bookmark toggle (always
  rendered, reactive); share current page (only when pages loaded); reading-mode picker dropdown
  (6 modes, checkmark); pinch-zoom+pan (all modes); jump-to-page scrubber; tap-to-toggle chrome;
  bottom HUD pill; per-page retry + per-page Open-in-WebView; chapter-level retry; resume-position;
  read-marking (last-page + next-advance); reading-history record; reading-session timer; per-page
  download-% indicator (Android determinate / others indeterminate); 403 auto-WebView recovery +
  auto-retry; system nav-bar hide. **Absent vs legacy (INFERRED):** brightness/orientation/keep-screen-on
  controls, in-reader settings sheet (rerouted to Settings hub), per-source DEFAULT mode resolution,
  page-gap/spacing config, double-page spread.
- **Citations:** `ReaderScreen.kt:294-1439`; `ReaderViewModel.kt:230-619`; `ReaderState.kt:142-270`;
  `ReaderIntent.kt:81-252`; `ReaderEffect.kt:50-118`; `ChapterImagesReworkScreenRoute.kt:71-211`;
  `ChapterImagesByLegacyArgsReworkScreenRoute.kt:79-150`; `App.kt:495-508,636-642`;
  `reader/internal/ReaderDecoderHints.kt` (per-platform decode hints).

### Reading-mode coverage (6/6)
- **Entry/route:** internal `ReaderPageLayout` `when(readingMode)` dispatch (`ReaderScreen.kt:910-962`).
- **Layout & components:** RTL/LTR = `HorizontalPager`; DEFAULT/VERTICAL = `VerticalPager`;
  WEBTOON/CONTINUOUS_VERTICAL = free-scroll `LazyColumn`. (`ReaderScreen.kt:911-961`)
- **Visual:** pagers `ContentScale.Fit`, list `FillWidth`; list paints theme background for gapless
  panels (`ReaderScreen.kt:1042,1136,1200,1213`).
- **States/Interactions:** identical per-page loading/error/zoom across all three (each layout owns its
  own `LazyListState`/`PagerState` + snapshotFlow + scrubber `LaunchedEffect`).
- **Data/behavior:** `DEFAULT` is treated as a synonym for `VERTICAL` (per-source resolution deferred,
  `ReaderScreen.kt:931-934`). Mode persisted via DataStore-backed `readingModeFlow`.
- **Feature inventory:** all 6 enum modes render a layout; selection via top-bar dropdown.
- **Citations:** `ReaderScreen.kt:899-1203`; `ReaderState.readingMode` `ReaderState.kt:179`.

### Cluster notes
- **No split-brain at the screen level.** All user paths render the rework Details + rework Reader.
  The duplication is in *route adapters* (URL-only vs full-tuple Details; legacy-args vs rework-args
  Reader), all backed by the same VMs and `:ui` composables. (`App.kt:469-642`)
- **Latent inconsistency (INFERRED):** legacy-args reader path sets `chapter.name = chapterNumber`
  (`ChapterImagesByLegacyArgsReworkScreenRoute.kt:120`) so the reader top-bar title shows the number,
  not the human name, when entered from a Home/History/Updates row tap — but shows the real name when
  entered via Details. Minor cosmetic divergence between the two reader entry paths.
- **Details parity gaps vs legacy (present-but-absent):** no sort dropdown, no filter bottom sheet,
  no per-chapter row actions (download/bookmark/mark-read), no per-chapter context menu. Legacy
  `CustomFilterBottomSheet`/`SortOptionsSection`/`FilterChipsRow` exist only in the retired
  `:composeApp` legacy tree (per task list §531-533) and were not ported to rework `:ui` Details.
- **"Download all" label is a hardcoded English literal** `"Download all"` (`DetailsScreen.kt:664`) and
  the last-chapter-date labels ("Today"/"Yesterday"/"N days ago"/"No chapter yet") are English-only
  inline literals (`DetailsScreen.kt:718-727`) — not localized via `stringResource`. Likewise the reader
  Share content-description is the hardcoded literal `"Share"` (`ReaderScreen.kt:697`).
- **Adult cover blur no-ops on Android API 26-30** (`Modifier.blur` requires API 31+); the modal
  `AdultConfirmationDialog` still gates interaction, so this is acceptable parity (`DetailsScreen.kt:786-794`).
- **AdultConfirmationDialog "Continue" is a plain dismiss** — the legacy monetization MStep flow was
  not ported (`DetailsScreen.kt:958-962`). No `ic_pluss18` icon (`:ui` doesn't depend on `:composeApp`
  resources) (`DetailsScreen.kt:966-968`).
- **403/Cloudflare recovery is wired identically on both Details and Reader** (WebView + auto-retry on
  back-stack return), and Details broadens the challenge-status set to {403,429,503,520-524} while the
  Reader only treats 403 as a challenge (`DetailsViewModel.kt:120` vs `ReaderViewModel.kt:534`) — a minor
  asymmetry: a 503 on a reader page falls to a generic snackbar, not auto-WebView (INFERRED gap).
- **Bookmark on Reader is always-rendered/always-enabled**; on Details it is gated until title resolves
  and on the double-tap flag. The reader bookmark degrades to a no-op for not-in-library chapters
  (`ReaderScreen.kt:683-687`, `ReaderViewModel.kt:340`).
- **Multi-chapter is VM-internal in-place navigation** (no `NavigateToChapter` effect), so both reader
  adapters get Next/Prev for free even though only the Details→reader path passes a real chapter name
  (`ReaderViewModel.kt:422-446`, `ReaderState.kt:250-269`).
- **Per-page retry is Coil-only (`painter.restart()`), not MVI** — chapter-level retry is the only
  MVI-routed retry (`ReaderScreen.kt:1306-1364`).
- **Heavy stale-KDoc noise:** every file in this cluster carries multi-paragraph "§253 audit-trail
  postscripts" describing retired legacy symbols (e.g. `Screen.kt:88-152`, the ~280-line
  `App.kt` postscript). These are historical record only; the live code is as audited above.


---

# CLUSTER: downloads_sources

# KMP (Rework) Audit — Downloads + Sources / RepoSettings Cluster

Read-only audit of the architecture-rework KMP app. Scope: the Downloads list
surface and the Sources / RepoSettings surface (language toggles, repo/source
toggles, request-source dialog, upcoming-languages info card, onboarding
Finish / auto-seed). All citations are `file:line` against the rework tree at
`D:/yami manga/yami-kmp/`.

---

### DownloadsScreen

- **Entry/route:**
  - Composable `DownloadsScreen(viewModel, onBack, modifier)` at
    `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/downloads/DownloadsScreen.kt:161-185`.
  - TWO nav keys render the SAME rework screen + rework `DownloadsViewModel`:
    - `Screen.DownloadsScreen` (legacy key, post-swap §295) via
      `DownloadsScreenRoute` — `composeApp/.../App.kt:550-556`; route at
      `navigation/routes/DownloadsScreenRoute.kt:242-252`.
    - `Screen.DownloadsRework` (rework key) via `DownloadsReworkScreenRoute` —
      `App.kt:572-578`; route at `navigation/routes/DownloadsReworkScreenRoute.kt:97-107`.
  - Both routes resolve a per-`NavBackStackEntry` VM via `koinViewModel()`; the
    underlying repositories are `single`-scoped and shared, so Room state is
    identical across both (`DownloadsScreenRoute.kt:109-119`).
  - User-reachable entry: rework Settings hub Downloads row → `Screen.DownloadsRework`
    (`navigation/routes/SettingsRoute.kt:154`, `SettingsReworkScreenRoute.kt:126`).
    Library nav drawer Downloads link points at `Screen.DownloadsScreen`
    (per `DownloadsScreenRoute.kt:206-209` prose; live).
  - `onBack = { navController.safePopBackStack() }` (both routes).
  - Bottom bar hidden on both routes (`SideEffect { onBottomBarVisibleChange(false) }`,
    `App.kt:551`, `App.kt:573`).

- **Layout & components:**
  - `Scaffold` with `TopAppBar` + `SnackbarHost` (`DownloadsScreen.kt:196-258`).
  - Top bar: title `Text(downloads)` + a **labelled `TextButton` "Back"** in the
    `navigationIcon` slot — NOT an icon (`DownloadsScreen.kt:199-206`). KDoc notes
    icon conversion was deferred (`DownloadsScreen.kt:101-108`).
  - Body: `Box` (background color) → if loading, `YamiLoadingState`; else `Column`
    with a 3-tab `TabRow` + the selected bucket's `LazyColumn`
    (`DownloadsScreen.kt:210-256`).
  - `TabRow` 3 tabs: "Active" / "Failed" / "Completed"
    (`DownloadsScreen.kt:221-237`), `containerColor = background`.
  - Per-bucket list `DownloadBucketList` → `LazyColumn`, contentPadding
    `vertical 8.dp, horizontal 16.dp`, items keyed by `chapterId`, 8.dp `Spacer`
    between rows (`DownloadsScreen.kt:260-283`).
  - Two card variants: `RunningDownloadCard` for RUNNING rows; `DownloadCard` for
    all other states (`DownloadsScreen.kt:274-279`).

- **Visual:** spacing, typography, colors, shapes/elevation
  - Cards: `RoundedCornerShape(12.dp)`, `containerColor = surfaceVariant`, no
    explicit elevation (`DownloadsScreen.kt:291-296`, `389-394`).
  - Inner card padding `spacing.md` (`LocalSpacing`) (`DownloadsScreen.kt:299-301`).
  - Title: `titleMedium`, `FontWeight.SemiBold`, `onSurface`, 1 line + ellipsis
    (`DownloadsScreen.kt:303-310`).
  - Status text: `bodyMedium`; color `error` when FAILED else `onSurfaceVariant`;
    2 lines + ellipsis (`DownloadsScreen.kt:312-322`).
  - Running progress %: `labelMedium`, `onSurfaceVariant` (`DownloadsScreen.kt:415-419`).
  - `LinearProgressIndicator` height `6.dp`, full width (`DownloadsScreen.kt:422-427`).
  - Spacers `spacing.xs` / `spacing.sm` between elements.

- **States:** loading / empty / error / success
  - **Loading:** `state.isLoading` (default `true`) → `YamiLoadingState` (centred
    spinner) covers whole content area, early-returns before the TabRow
    (`DownloadsScreen.kt:216-219`; `DownloadsState.kt:68`).
  - **Empty (per-bucket):** when the selected bucket list is empty,
    `YamiEmptyState(title = <empty label>)` — labels "No active downloads" /
    "No failed downloads" / "No completed downloads"
    (`DownloadsScreen.kt:266-268`, `241/247/252`).
  - **Error:** no screen-level error pane. Mutation failures surface as a snackbar
    (see Dialogs/snackbars). The observe upstream has no error state
    (`DownloadsViewModel.kt:53-60`; `DownloadsState` has no `error` field).
  - **Success:** populated `LazyColumn` of cards; success of a mutation is silent —
    Room re-emit drives the row state change (FAILED→QUEUED on retry, row vanishes
    on delete) (`DownloadsViewModel.kt:37-40`; `DownloadsEffect.kt:14-24`).

- **Interactions:** clicks, long-press, gestures, swipe, pull-to-refresh, animations
  - Tab click → `DownloadsIntent.OnTabSelect(index)` (`DownloadsScreen.kt:233`).
  - Per-row action buttons (see Feature inventory). All are labelled `TextButton`s,
    right-aligned (`Arrangement.End`).
  - **No long-press, no swipe-to-dismiss, no pull-to-refresh** — the screen is
    flow-driven; there is no `OnRefresh` intent (reactivity via Room re-emit,
    `DownloadsViewModel.kt:163-184`).
  - **Animation:** running-row progress bar animates via
    `animateFloatAsState(tween(300ms))` (`DownloadsScreen.kt:385-388`, `423`).
  - No row-tap navigation (terminal screen; `DownloadsReworkScreenRoute.kt:20-23`).

- **Dialogs/sheets/snackbars:**
  - `SnackbarHost` anchored to the Scaffold (`DownloadsScreen.kt:208`).
  - `DownloadsEffect.ShowError(message)` collected via
    `LaunchedEffect(viewModel) { effects.collectLatest { ... } }` →
    `snackbarHostState.showSnackbar(effect.message)` (`DownloadsScreen.kt:170-176`).
  - Message = throwable `message` ?: `simpleName` ?: "Unknown error"
    (`DownloadsViewModel.kt:224-231`).
  - **No confirmation dialogs** for delete/cancel (single-tap acts immediately).
    Intent carries the full `DownloadedChapter` so a future "Delete chapter X?"
    dialog could read fields without a re-lookup (`DownloadsIntent.kt:12-21`).

- **Forms & validation:** none — no text input on this screen.

- **Data/behavior:** fetches, side effects, navigation, permissions
  - VM `init {}` collects `ObserveDownloadsUseCase()` and partitions every emission
    into 3 buckets with three `.filter {}` passes (`DownloadsViewModel.kt:163-184`):
    - **Active** = RUNNING ∪ QUEUED ∪ COMPRESSING
    - **Failed** = FAILED
    - **Completed** = SUCCESS
  - `selectedTab` default = **2 (Completed)** — preserves legacy first-open behaviour
    (`DownloadsState.kt:73`, `36-39`).
  - Mutations are fire-and-forget `viewModelScope.launch {}` calls to the matching
    use case; failures call `emitOnFailure` (`DownloadsViewModel.kt:186-231`):
    - `OnRetry` → `RetryDownloadUseCase(chapterId)`
    - `OnCancel` → `CancelDownloadUseCase(chapterId)` (queue-prune)
    - `OnCancelRunning` → `CancelRunningDownloadUseCase(chapterId, mangaId)` (interrupt in-flight)
    - `OnDelete` → `DeleteDownloadUseCase(chapterId)`
  - **No in-flight guard** on mutations — relies on idempotency (cancel-twice no-op,
    etc.) (`DownloadsViewModel.kt:85-92`).
  - No debouncing/sampling on the upstream — relies on Compose `collectAsState`
    natural debounce of structurally-equal emissions (`DownloadsViewModel.kt:73-83`).
  - No permissions requested in this screen.
  - `:data` impl is a strangler-fig over the legacy `DownloadRepository.observeAllDownloads()`
    Room flow (`DownloadsReworkScreenRoute.kt:28-34`).

- **Feature inventory:** EVERY affordance
  1. Back `TextButton` (top bar) → `onBack` → `safePopBackStack`.
  2. 3 tabs Active / Failed / Completed → `OnTabSelect`.
  3. **RUNNING row** (`RunningDownloadCard`, Active tab): title (weight 1f) + "%"
     label row, animated `LinearProgressIndicator`, **"Cancel" `TextButton`** →
     `OnCancelRunning` (`DownloadsScreen.kt:378-439`).
  4. **QUEUED / COMPRESSING row** (Active tab): title + status label + **"Cancel"
     `TextButton`** → `OnCancel` (`DownloadsScreen.kt:335-345`).
  5. **FAILED row** (Failed tab): title + red status ("Failed: <reason>") +
     **"Retry" `TextButton`** → `OnRetry` and **"Delete" `TextButton`** → `OnDelete`
     (`DownloadsScreen.kt:346-359`).
  6. **SUCCESS row** (Completed tab): title + "Downloaded" status + **"Delete"
     `TextButton`** → `OnDelete` (`DownloadsScreen.kt:360-370`).
  7. Status labels via `statusLabel()`: Queued / Running / Compressing / Downloaded /
     "Failed: <reason>" (reason ?: "unknown") (`DownloadsScreen.kt:441-451`).
  8. Snackbar on mutation failure.
  - **Gap vs legacy (deferred, present-but-text-only):** all row actions and the
    back arrow are labelled text, not the legacy `Icons.Default.Cancel/Refresh/Delete`
    + `ArrowBack` icons; icon conversion deferred (`DownloadsScreen.kt:101-108`;
    `DownloadsScreenRoute.kt:77-82`). No affordance is missing, only the glyph form.
  - **No "Run all" / "Clear all" / "Cancel all" / pause / global affordances** — the
    audit scope mentions "run-all/clear" but the rework screen has NO bulk/global
    action (no `DownloadsIntent` variant for it; only the 5 listed intents exist in
    `DownloadsIntent.kt:59-107`). (INFERRED gap — no bulk control surfaced.)
  - **No download chapter cover thumbnail** on rows (text-only cards).
  - No paging — plain `List<DownloadedChapter>` (`DownloadsScreenRoute.kt:121-125`).

- **Citations:** file:line
  - `ui/.../downloads/DownloadsScreen.kt:161-451` (whole screen)
  - `presentation/.../downloads/DownloadsState.kt:67-74`
  - `presentation/.../downloads/DownloadsIntent.kt:59-107`
  - `presentation/.../downloads/DownloadsEffect.kt:78-86`
  - `presentation/.../downloads/DownloadsViewModel.kt:153-232`
  - `domain/.../model/downloads/DownloadedChapter.kt:107-115`
  - `domain/.../model/downloads/DownloadState.kt:59-74`
  - `composeApp/.../navigation/routes/DownloadsReworkScreenRoute.kt:97-107`
  - `composeApp/.../navigation/routes/DownloadsScreenRoute.kt:242-252`
  - `composeApp/.../App.kt:550-578`

---

### SourcesScreen (also serves RepoSettings + onboarding Sources)

- **Entry/route:**
  - Composable `SourcesScreen(viewModel, modifier, onFinish?, onboardingLanguageTag?)`
    at `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/sources/SourcesScreen.kt:235-252`.
  - ONE rework screen serves THREE nav keys, all backed by the rework `SourcesViewModel`
    (`single`-scoped `SourcesRepository` shared across all):
    - `Screen.Sources` (onboarding step 3) → `SourcesScreenRoute`
      (`App.kt:416-422`; route `navigation/routes/SourcesScreenRoute.kt:155-171`).
      Passes `onboardingLanguageTag = userLanguageCode` (from
      `DataStoreHelper.languageFlow`) AND `onFinish = navigate(Screen.RepoSettings(isFirstOpen=true))`.
    - `Screen.RepoSettings` (onboarding step 4 + in-settings entry from Home) →
      `RepoSettingsScreenRoute` (`App.kt:534-540`; route
      `navigation/routes/RepoSettingsScreenRoute.kt:123-148`). `onFinish` is non-null
      ONLY when `args.isFirstOpen == true` (flips `first_launch` pref → navigate Library
      with full backstack clear); else `null` (no Finish button).
    - `Screen.SourcesRework` (standalone rework key) → `SourcesReworkScreenRoute`
      (`App.kt:712-718`; route `navigation/routes/SourcesReworkScreenRoute.kt:126-133`).
      Passes NO `onFinish` and NO `onboardingLanguageTag` (both null).
  - No `onBack` parameter — relies on system back; no custom nav icon
    (`RepoSettingsScreenRoute.kt:55-63`).

- **Layout & components:**
  - `Scaffold` with `TopAppBar(title = "Sources")`, `SnackbarHost`, and a
    `bottomBar` that renders `FinishButton` iff `onFinish != null`
    (`SourcesScreen.kt:285-297`).
  - Body branches: loading → `LoadingBox`; empty → `EmptyBox("No sources available")`;
    else → `SourcesList` (`SourcesScreen.kt:298-310`).
  - `SourcesList` = `LazyColumn` (`SourcesScreen.kt:331-387`) with, in order:
    1. `item("request-source")`: `RequestSourceRow` + `HorizontalDivider`.
    2. `item("upcoming-languages")`: `UpcomingLanguagesCard` + `HorizontalDivider`.
    3. Per language group: a `LanguageHeader` item (key `header-$language`) then
       per-source `SourceRow` items (key `source-${api}`) each followed by a
       `HorizontalDivider`.
  - `RequestSourceRow`: clickable Row, title "Request adding source" + subtitle
    "Enter the URL for the site…" (`SourcesScreen.kt:389-415`).
  - `UpcomingLanguagesCard`: non-clickable Column, title "Upcoming Languages" +
    multi-line description (with emoji) (`SourcesScreen.kt:440-461`).
  - `LanguageHeader`: Row, language name (parens stripped) + "x of N enabled"
    caption + per-language `Switch` (`SourcesScreen.kt:500-542`).
  - `SourceRow`: Row, `source.api` label (weight 1f) + per-source `Switch`
    (`SourcesScreen.kt:544-568`).
  - `FinishButton`: full-width pill `Button`, `RoundedCornerShape(26.dp)`, height
    `50.dp`, label "Finish" (`SourcesScreen.kt:478-498`).

- **Visual:** spacing, typography, colors, shapes/elevation
  - List rows padded `horizontal = spacing.lg`, `vertical = spacing.md` (LocalSpacing)
    (`SourcesScreen.kt:362-382`).
  - Row titles `titleMedium` SemiBold; subtitles/captions `bodySmall` on
    `onSurfaceVariant` (`SourcesScreen.kt:403-412`, `527-535`).
  - Source label `bodyLarge` (`SourcesScreen.kt:558-561`).
  - `FinishButton` Box padding `horizontal 24.dp / vertical 12.dp`; pill corner 26.dp;
    label `labelLarge`; Material3 default primary colors (`SourcesScreen.kt:478-498`).
  - `HorizontalDivider` between every source row + after the two header items.
  - Material 3 `Switch` (default colors).
  - **No `AnimatedBackground` / gradient overlay** — cosmetic decoration intentionally
    omitted (`SourcesScreen.kt:106-108`, audit-trail at `192-233`).

- **States:** loading / empty / error / success
  - **Loading:** `state.isLoading` (default `true`) → `YamiLoadingState`
    (`SourcesScreen.kt:300`, `321-324`; `SourcesState.kt:75`).
  - **Empty:** `state.isEmpty` (= `!isLoading && items.isEmpty()`) →
    `YamiEmptyState("No sources available")` (`SourcesScreen.kt:301`,
    `SourcesState.kt:82`).
  - **Error:** no screen error state — Room observe doesn't throw; no `error` field
    on state (`SourcesState.kt:10-15`). Submit failures surface via snackbar.
  - **Success:** language-grouped list of toggle rows.

- **Interactions:** clicks, long-press, gestures, swipe, pull-to-refresh, animations
  - Tap "Request adding source" row → `OnOpenComplaintDialog` (`SourcesScreen.kt:347`).
  - Per-language `Switch` → `OnToggleLanguage(language, enabled)`
    (`SourcesScreen.kt:367-369`). `checked = sources.any { it.isEnabled }`
    ("ON if any enabled"; flipping OFF bulk-disables the group)
    (`SourcesScreen.kt:508`, `537-540`).
  - Per-source `Switch` → `OnToggleSource(source, enabled)` (`SourcesScreen.kt:377-379`).
  - Tap "Finish" (onboarding only) → `onFinish()` callback.
  - **No long-press, no swipe, no pull-to-refresh** (flow-driven, reactive via Room
    re-emit; no `OnRefresh` intent).
  - **No explicit animations** (`AnimatedBackground` intentionally dropped).

- **Dialogs/sheets/snackbars:**
  - `SnackbarHost` anchored to Scaffold (`SourcesScreen.kt:290`); consumes
    `SourcesEffect.ShowSnackbar` via `LaunchedEffect(effects) { effects.collect {...} }`
    (`SourcesScreen.kt:267-273`).
  - **`RequestSourceDialog`** (`AlertDialog`) shown when `state.complaintDialogOpen`
    (`SourcesScreen.kt:312-318`, `592-698`):
    - `RoundedCornerShape(20.dp)`, `surface` container, tonalElevation 3.dp.
    - Title "Request adding source"; body text "We will add it as soon as possible";
      single `OutlinedTextField` labelled "Enter the site URL" (minLines 4, maxLines 6,
      heightIn min 120.dp).
    - Confirm `Button` (label "Submit" / "Submitting…" while in-flight) →
      `OnSubmitComplaint(body)`; Dismiss `TextButton` "Cancel" → `OnDismissComplaintDialog`.
  - Snackbar messages (English literals, VM-built): success
    "Thanks! Your request was submitted."; failure "Failed to submit request: <cause>"
    (`SourcesViewModel.kt:185`, `191`). (INFERRED: not localized — hardcoded English.)

- **Forms & validation:**
  - `RequestSourceDialog` body field (`SourcesScreen.kt:631-664`):
    - Local `remember { mutableStateOf("") }` — NOT mirrored into MVI state
      (`SourcesScreen.kt:600`).
    - **Length cap 500** — `onValueChange` short-circuits past 500 chars
      (`SourcesScreen.kt:633`).
    - **Min length 5** — `submitEnabled = body.length >= 5 && !isSubmitting`
      (`SourcesScreen.kt:602`); `isError` + "Minimum 5 characters" helper when
      non-empty and < 5 (`SourcesScreen.kt:642-656`); char counter "len/500".
    - **Known UI/use-case mismatch:** the UI gate is 5 but the underlying
      `SendComplaintUseCase` requires ≥ 8 — bodies of 5-7 chars pass the UI then fail
      server-side with a snackbar error (`SourcesScreen.kt:579-583`). (Documented bug
      surface; flagged for Phase 10 reconciliation.)
  - Dialog dismissal gated while submitting: `DialogProperties(dismissOnBackPress =
    !isSubmitting, dismissOnClickOutside = !isSubmitting)`; Cancel disabled; VM also
    guards `OnDismissComplaintDialog` (`SourcesScreen.kt:604-609`;
    `SourcesViewModel.kt:161-164`).

- **Data/behavior:** fetches, side effects, navigation, permissions
  - VM `init {}` collects `ObserveSourcesUseCase()` → `items` snapshot
    (`SourcesViewModel.kt:143-149`). No `catch{}` (Room observe doesn't throw).
  - `groupedByLanguage` = `items.groupBy { it.language }` (insertion-ordered;
    `:ui` consumes directly) (`SourcesState.kt:99-100`).
  - `enabledCount` derived getter (surfaced but not currently rendered as a
    top-level header line) (`SourcesState.kt:106`).
  - Toggle intents = fire-and-forget `viewModelScope.launch {}` to
    `SetSourceEnabledUseCase(api, enabled)` / `SetLanguageEnabledUseCase(language, enabled)`;
    no failure handling (UPDATE is infallible) (`SourcesViewModel.kt:152-158`).
    Per-source fan-out for language toggle lives in `:data` impl.
  - **Onboarding auto-seed:** `LaunchedEffect(onboardingLanguageTag)` fires
    `OnSeedDefaultLanguage(tag)` when the tag is non-null (no-op for null, i.e. the
    standalone/in-settings entries) (`SourcesScreen.kt:279-283`). VM →
    `EnableDefaultLanguageSourcesUseCase(tag)` (use case owns uppercase+parens +
    EN-fallback) (`SourcesViewModel.kt:166-168`). Idempotent (Room no-re-emit on
    unchanged row) (`SourcesScreen.kt:93-97`).
  - **Submit complaint:** `handleSubmitComplaint` re-entry guarded by
    `isSubmittingComplaint`; sets flag true; `SubmitFeedbackUseCase(type=SITES_ADD,
    subject=type.name, body)`; on success closes dialog + success snackbar; on failure
    keeps dialog open (preserves typed text) + error snackbar
    (`SourcesViewModel.kt:172-194`).
  - **Navigation:** onboarding step 3 Finish → `Screen.RepoSettings(isFirstOpen=true)`
    (`SourcesScreenRoute.kt:167-169`); step 4 (RepoSettings) Finish → flip `first_launch`
    pref + `navigate(Screen.Library)` with `popUpTo(start){inclusive} + launchSingleTop`
    (`RepoSettingsScreenRoute.kt:134-146`). Onboarding chain: Welcome → Theme → Sources →
    RepoSettings → Library (`SourcesScreenRoute.kt:45-52`).
  - **No permissions** requested by this screen.
  - **No outbound nav** from the standalone `Screen.SourcesRework` entry
    (`SourcesReworkScreenRoute.kt:16-19`).

- **Feature inventory:** EVERY affordance
  1. "Request adding source" header row (title + subtitle) → opens dialog.
  2. "Upcoming Languages" info card (title + description, non-interactive).
  3. Per-language section header: stripped language name + "x of N enabled" caption +
     per-language `Switch` (any-enabled semantics, bulk toggle).
  4. Per-source row: `api` label + per-source `Switch`.
  5. `HorizontalDivider`s between rows.
  6. Onboarding "Finish" pill button (only when `onFinish != null`).
  7. `RequestSourceDialog`: URL text field (5-500 char validation, char counter),
     "Submit"/"Submitting…" confirm, "Cancel" dismiss, dismissal-gating while submitting.
  8. Success/failure snackbars.
  - **No source tabs** — the rework groups by language sections in a single
    `LazyColumn`; there is NO tabbed source UI (audit scope mentions "source tabs" but
    none exist here). (INFERRED gap vs scope expectation.)
  - **No per-source priority badge, no cover, no chapter info** — intentional
    (`SourcesScreen.kt:133-135`).
  - **No site-state (WORKING/STOPPED) indicator** — `Source` is a 4-field model
    (api/language/priority/isEnabled); siteState/baseUrl/etc. left on the legacy entity
    (`Source.kt:13-20`, `86-95`).
  - **No search/filter** of the source list.
  - Upcoming-languages card and Request-source subtitle/body strings ARE localized
    (en + ar) via `stringResource`; submit-result snackbars are hardcoded English (gap).

- **Citations:** file:line
  - `ui/.../sources/SourcesScreen.kt:235-698` (whole screen)
  - `presentation/.../sources/SourcesState.kt:74-107`
  - `presentation/.../sources/SourcesIntent.kt:99-195`
  - `presentation/.../sources/SourcesEffect.kt:63-78`
  - `presentation/.../sources/SourcesViewModel.kt:133-195`
  - `domain/.../model/sources/Source.kt:86-95`
  - `composeApp/.../navigation/routes/SourcesScreenRoute.kt:155-171`
  - `composeApp/.../navigation/routes/RepoSettingsScreenRoute.kt:123-148`
  - `composeApp/.../navigation/routes/SourcesReworkScreenRoute.kt:126-133`
  - `composeApp/.../App.kt:416-422, 534-540, 712-718`
  - Resources: `ui/.../composeResources/values/strings.xml` (request_adding_source,
    languages_coming_soon_title/description, enter_the_site_url,
    minimum_5_characters_required, sources_enabled_count, finish, submit, submitting,
    no_sources_available, etc.) + values-ar mirror.

---

### Cluster notes

- **Single rework screen, three Sources entries.** `SourcesScreen` is a parameterized
  one-file surface that covers the legacy onboarding-Sources, RepoSettings, and the
  standalone rework key. The only behavioral difference between entries is the nullable
  `onFinish` (Finish button gate) and nullable `onboardingLanguageTag` (auto-seed gate).
  Legacy `RepoSettingsScreen.kt` / onboarding `SourcesScreen.kt` are retired (§307/§353);
  no legacy Sources/RepoSettings UI is user-reachable.

- **Single rework screen, two Downloads entries.** `Screen.DownloadsScreen` (legacy key,
  post-swap) and `Screen.DownloadsRework` both render the identical rework `DownloadsScreen`
  + VM with shared `single`-scoped repos. User reaches it via the rework Settings hub
  Downloads row (→ `Screen.DownloadsRework`) and the Library drawer (→ `Screen.DownloadsScreen`).
  Legacy Downloads UI + `DownloadViewModelv2` are retired (§352/§439).

- **Icon-vs-text deferral (Downloads).** Every Downloads row action (Cancel/Retry/Delete)
  and the back arrow render as labelled `TextButton`s, not icons. No affordance is missing
  — only the glyph form differs from the legacy `Icons.Default.*`. Tracked under the
  UP-2/UP-4 icon work; `:ui` ships `materialIconsExtended` but this screen wasn't part of
  the conversion set (`DownloadsScreen.kt:101-108`).

- **No bulk/global Downloads controls (likely gap vs native).** The audit scope lists
  "run-all/clear" but the rework Downloads screen has NO Run-all / Clear-all /
  Cancel-all / pause affordance, and no corresponding `DownloadsIntent`. Only per-row
  Cancel/Retry/Delete exist. (INFERRED gap — verify against OLD app audit.)

- **No "source tabs" in rework Sources (likely gap vs native).** The rework Sources uses a
  single language-grouped `LazyColumn`, not a tabbed source browser. The scope mention of
  "source tabs" has no rework counterpart on this surface (a separate legacy `SourcesTabs.kt`
  exists in the Home/Search cluster, not here). (INFERRED — out-of-cluster.)

- **Validation mismatch bug (Sources request dialog).** UI min-length gate is 5 chars but
  the underlying use case requires ≥ 8 (`SendComplaintUseCase`), so 5-7 char bodies pass UI
  validation then fail server-side with an error snackbar
  (`SourcesScreen.kt:579-583`). Real, documented, not yet reconciled.

- **Hardcoded English snackbars (Sources).** Submit success/failure snackbar literals are
  built in the VM in English ("Thanks! Your request was submitted." / "Failed to submit
  request: …") — not `stringResource`, so they bypass the ar locale
  (`SourcesViewModel.kt:185,191`). Most other Sources copy IS localized. (Gap.)

- **No confirmation dialogs on destructive Downloads actions.** Delete / Cancel act on a
  single tap with no "Are you sure?" — the intent already carries the full
  `DownloadedChapter` so a future confirm dialog is a low-friction add
  (`DownloadsIntent.kt:12-21`). (Present-but-unbuilt extension hook.)

- **No pull-to-refresh on either surface.** Both are purely Room-flow-driven and reactive;
  neither exposes a manual refresh. Consistent with the rework History/Updates/Statistics
  posture.

- **KDoc/source ratio.** Both clusters carry very large §253 audit-trail postscripts (the
  Downloads/Sources screens are ~75% KDoc by line count); these are historical lineage
  records and do not affect runtime behavior.


---

# CLUSTER: settings_theme_language

# KMP (Rework) Audit — Settings + Theme + Language Cluster

Read-only audit of the architecture-rework KMP app. Scope: Settings hub, Theme picker
(`themepicker`), Language picker, plus the `:ui/theme/` design-token system and the
`:presentation` MVI slices + `:composeApp` route adapters that back them. All citations are
`file:line`. Inferences marked `(INFERRED)`.

Module map for this cluster:
- `:ui` screens — `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/{settings,themepicker,language}/`
- `:ui` design tokens — `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/`
- `:presentation` MVI — `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/{settings,theme,language}/`
- `:composeApp` routes — `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/`
- Resources — `ui/src/commonMain/composeResources/{values,values-ar,font,drawable}/`

---

### SettingsScreen (Settings hub)

- **Entry/route:** `Screen.SettingsRework` → `SettingsReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt:109-139`), wired in `App.kt:874-880`
  inside `composable<Screen.SettingsRework>`. VM resolved via `koinViewModel()`
  (`SettingsReworkScreenRoute.kt:114`). Bottom bar hidden: `SideEffect { onBottomBarVisibleChange(false) }`
  (`App.kt:875`). Composable entry: `SettingsScreen(viewModel, onNavigate, modifier, isAdmin, initialTestingMode, onToggleTestingMode)`
  (`ui/.../settings/SettingsScreen.kt:206-230`); stateless inner `SettingsScreenContent`
  (`SettingsScreen.kt:232-304`). **NOTE present-but-unwired:** the App.kt comment (`:858-873`) still
  claims "not surfaced in any user-facing entry yet — reachable via `navController.navigate(Screen.SettingsRework)` from a future developer trigger." This audit found NO drawer/menu wiring of `Screen.SettingsRework` in the navigation graph; reachability from a real user entry point is unconfirmed in this scope `(INFERRED)`.

- **Layout & components:** `Scaffold` with `TopAppBar` (title `Res.string.settings`, `FontWeight.SemiBold`)
  + `SnackbarHost` (`SettingsScreen.kt:255-267`). Body is a `Box` filling size, padded by `innerPadding`,
  background `MaterialTheme.colorScheme.background` (`:269-274`). Content is a `LazyColumn`
  (`SettingsList`, `:307-465`) with `contentPadding = PaddingValues(vertical = spacing.md)` and
  `verticalArrangement = Arrangement.spacedBy(spacing.sm)` (`:319-323`). Seven `item`s, each a
  `SectionCard` (`:467-495`): a primary-colored `titleSmall` header + a `Card`
  (`surfaceVariant` container, `RoundedCornerShape(12.dp)`) holding rows separated by `HorizontalDivider`.
  Row composables: `ToggleRow` (`:497-538`), `NavRow` (`:589-615`), `ReadingModeRow` (`:617-646`),
  `CacheRow` (`:648-683`), `CompressExistingRow` (`:547-587`).
- **Visual:** spacing all from `LocalSpacing` (xxs/xs/sm/md/lg). Section header `typography.titleSmall`,
  `colorScheme.primary`, SemiBold (`:479-484`). Row label `bodyLarge` on `onSurface`; description
  `bodySmall` on `onSurfaceVariant` (`:520-531`). Card corners `12.dp`; container `surfaceVariant`
  (`:487-490`). Admin "Testing Mode" label tinted `Color.Red.copy(alpha = 0.5f)` (`:348`).
  Spinner uses default `CircularProgressIndicator` size.
- **States:** loading → centered `CircularProgressIndicator` while `state.isLoading`
  (`:275-276`); else `SettingsList`. No explicit empty/error states — the hub always renders its
  fixed section set. Failures surface as transient snackbars via `SettingsEffect.ShowSnackbar`
  (`:250`). Cache-clear in-flight → trailing spinner on the cache row + row disabled (`:658,:679-681`).
  CBZ "compress existing" in-flight → button spinner + "Converting..." (`:574-585`).
- **Interactions:** every `ToggleRow` is `clickable { onCheckedChange(!checked) }` AND has a `Switch`
  (`:512,:533-536`). Nav rows `clickable(onClick)` (`:600-604`); the Help row passes `onClick = null`
  → no clickable modifier, no ripple (`:442,:600`) — inert placeholder. Reading-mode row opens a
  dialog (`:421`). Cache row `clickable(enabled = !isClearing)` (`:658`). No gesture/animation work
  beyond default Material ripples + Switch thumb animation. Effect collection via single
  `LaunchedEffect(effects)` (`:246-253`).
- **Dialogs/sheets/snackbars:** **FeedbackDialog** (`:289-295`, def `:781-939`) — `AlertDialog`,
  `RoundedCornerShape(20.dp)`, `surface` container, `tonalElevation 3.dp`. **ReadingModeDialog**
  (`:297-303`, def `:984-1043`). Snackbar host owned by the screen (`:244,:267`), `SnackbarDuration.Short`
  default (no `withDismissAction`).
- **Forms & validation:** FeedbackDialog has a category `ExposedDropdownMenuBox` (readonly
  `OutlinedTextField` + 6 `ComplaintType` items, `:825-860`) and a body `OutlinedTextField`
  (multiline, `minLines = 4, maxLines = 6, heightIn(min = 120.dp)`, `:869-902`). Validation:
  `submitEnabled = selectedType != null && body.length >= 5 && !isSubmitting` (`:794`); body capped at
  500 chars (`:871`), char counter `${body.length}/500` (`:896`); `isError` + "minimum 5 characters
  required" helper below 5 (`:880,:887-888`). **KNOWN MISMATCH** (documented in KDoc `:765-769`): UI
  gate is 5 chars but `SubmitFeedbackUseCase` requires ≥8, so 5–7 char bodies fail server-side and
  surface an error snackbar. Local state only (`selectedType`, `body`, `expanded` are
  `remember{mutableStateOf}`, `:790-792`) — payload rides with `OnSubmitFeedback`.
- **Data/behavior:** state from `SettingsViewModel.state` (`:219`); intents via `viewModel::submit`.
  Persistence: toggles → `UpdateSettingsToggleUseCase` (fire-and-forget,
  `SettingsViewModel.kt:184-186`); cache → `ClearCacheUseCase` with re-entrance guard
  (`SettingsViewModel.kt:223-239`); reading mode → `SetReadingModeUseCase` (`:241-244`); feedback →
  `SubmitFeedbackUseCase` with `subject = type.name` (`:246-267`); CBZ compress →
  `CompressExistingDownloadsUseCase` with guard (`:205-221`). Navigation is an effect:
  `OnNavigate` → `SettingsEffect.NavigateTo(destination)` → route adapter maps to `Screen.<X>Rework`
  (`SettingsReworkScreenRoute.kt:117-129`). Admin gating: `isAdmin`/`testingMode` supplied by the
  adapter from the `:shared` `Admin` runtime object (`SettingsReworkScreenRoute.kt:135-137`); COMPLAINT
  destination routes to `ComplaintAdminRework` vs `ComplaintRework` on `Admin.isAdmin`
  (`:123-124`). No permissions requested by this screen.
- **Feature inventory (every row + action):**
  - Section **General** (`Res.string.section_general`, `SettingsScreen.kt:324-356`):
    1. **Downloaded only** toggle (`SettingsToggle.DOWNLOADED_ONLY`), desc "Filters all entries in
       your library" (`:326-332`).
    2. **Incognito mode** toggle (`SettingsToggle.INCOGNITO`), desc "Stop saving your reading
       history." (`:334-340`).
    3. **Testing Mode** toggle — admin-only (`if (isAdmin)`), red-tinted, flips `Admin.testingMode`
       (`:341-354`). Local mirror state (`:318`).
  - Section **Theme** (`Res.string.theme_screen_title`, `:358-385`):
    4. **Follow system theme** toggle (`FOLLOW_SYSTEM_THEME`), desc "Follow System Theme" (`:360-366`).
    5. **Dark/Light Mode** toggle (`DARK_MODE`) — **gated on `!state.followSystemTheme`** (`:367-376`);
       desc switches "Dark Mode"/"Light Mode" on value.
    6. **Pure black** toggle (`PURE_BLACK`, `Res.string.pure_black_mode_title` → "Pure black dark
       mode") — always visible, no description (`:377-383`).
  - Section **Downloads/CBZ** (`Res.string.downloads`, `:387-415`):
    7. **Use Yami Compressor** toggle (`USE_CBZ_FORMAT`, inline literal label + desc) (`:389-397`).
    8. **Auto-convert on Download** toggle (`AUTO_CONVERT_TO_CBZ`) — visible only when
       `useCbzFormat` (`:398-407`).
    9. **Compress Existing Downloads** action (`CompressExistingRow`, full-width Button "Start
       Conversion"/"Converting...") — visible only when `useCbzFormat` (`:409-412`).
  - Section **Reading** (`Res.string.section_reading`, `:417-424`):
    10. **Reading mode** row (subtitle = current mode label) → opens ReadingModeDialog (`:419-422`).
  - Section **Navigation** (`Res.string.section_navigation`, `:426-444`): iterates
    `SettingsDestination.entries` (7 rows) + an inert Help row:
    11. **Theme** → `Screen.ThemeRework`
    12. **Statistics** → `Screen.StatisticsRework`
    13. **Language** → `Screen.LanguageRework`
    14. **About** → `Screen.AboutRework`
    15. **Feedback Manager** (`COMPLAINT`) → admin/user complaint route
    16. **What's new** (`WHATSNEW`) → `Screen.WhatsNewRework`
    17. **Downloads** → `Screen.DownloadsRework`
    18. **Help** row — `onClick = null`, inert placeholder (`:441-442`).
  - Section **Storage** (`Res.string.section_storage`, `:446-454`):
    19. **Clear cache** row + "Cached: <size>" subtitle (`Res.string.cached_size`) (`:448-452`,
        `:666-677`).
  - Section **Feedback** (`Res.string.section_feedback`, `:456-463`):
    20. **Request feature / bug** row → opens FeedbackDialog (`:458-461`).
- **Citations:** `ui/.../settings/SettingsScreen.kt:206-1043`;
  `presentation/.../settings/SettingsViewModel.kt:144-268`;
  `presentation/.../settings/SettingsState.kt:127-147`;
  `presentation/.../settings/SettingsIntent.kt:84-224`;
  `presentation/.../settings/SettingsEffect.kt:61-86`;
  `presentation/.../settings/SettingsDestination.kt:103-111`;
  `composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt:109-139`; `App.kt:874-880`;
  `domain/.../model/settings/SettingsSnapshot.kt:98-133`.

---

### FeedbackDialog (Settings hub modal)

- **Entry/route:** rendered from `SettingsScreenContent` when `state.feedbackDialogOpen`
  (`SettingsScreen.kt:289-295`); opened by `OnOpenFeedbackDialog` from the "Request feature / bug"
  row.
- **Layout & components:** `AlertDialog` (`:796-938`) — title `Res.string.request_feature_bug`
  (`headlineSmall`, Bold); scrollable `Column` (`verticalScroll`) with a "Category" label
  (`labelLarge` SemiBold) + `ExposedDropdownMenuBox` + "Your feedback" label + body `OutlinedTextField`;
  confirm `Button`, dismiss `TextButton`.
- **Visual:** `RoundedCornerShape(20.dp)`, `surface` container, `tonalElevation 3.dp` (`:802-804`);
  fields `RoundedCornerShape(12.dp)`; spacing from `LocalSpacing`.
- **States:** in-flight `isSubmitting` → confirm button label "Submitting…" else "Submit"
  (`:915-919`); dropdown/field disabled while submitting (`:828,:834,:872`).
- **Interactions:** dropdown expand/collapse; row select sets `selectedType`. `@Suppress("DEPRECATION")`
  on the deprecated `.menuAnchor()` overload (`:825,:838`).
- **Dialogs/sheets/snackbars:** on success → snackbar "Thanks! Your feedback was submitted."
  (`SettingsViewModel.kt:258`); on failure → "Failed to submit feedback: <cause>" (`:264`).
- **Forms & validation:** see SettingsScreen Forms (5-char UI gate vs 8-char use-case gate; 500-char
  cap; counter; error helper). Dismissal gated during submit via
  `DialogProperties(dismissOnBackPress/ClickOutside = !isSubmitting)` (`:798-801`); VM-side dismiss
  guard `if (isSubmittingFeedback) return` (`SettingsViewModel.kt:192`).
- **Data/behavior:** submit → `SubmitFeedbackUseCase(type, subject = type.name, body)`
  (`SettingsViewModel.kt:250`). On success closes dialog (`:255`); on failure keeps it open
  (typed text preserved).
- **Feature inventory:** category dropdown (6 `ComplaintType`: Technical, Languages, Sites add, Site
  error, Features, Other — `SettingsScreen.kt:741-748`), body field, char counter, Submit, Cancel.
- **Citations:** `ui/.../settings/SettingsScreen.kt:741-939`;
  `presentation/.../settings/SettingsViewModel.kt:246-267`.

---

### ReadingModeDialog (Settings hub modal)

- **Entry/route:** rendered when `state.readingModeDialogOpen` (`SettingsScreen.kt:297-303`);
  opened by `OnOpenReadingModeDialog` from the Reading-mode row.
- **Layout & components:** `AlertDialog` (`:991-1042`) — title `Res.string.reading_mode` (Bold
  headlineSmall); body `Column` of 6 selectable rows (`RadioButton(onClick = null)` + label,
  the whole `Row` is `clickable { onSelect(mode) }`); empty `confirmButton`, dismiss `TextButton`
  (Cancel).
- **Visual:** `RoundedCornerShape(20.dp)`, `surface`, `tonalElevation 3.dp`; rows spaced `spacing.xs`,
  vertical pad `spacing.xs`; label `bodyLarge` on `onSurface`.
- **States:** none beyond current selection driven by `currentMode` (no loading/error). Single-tap-commits.
- **Interactions:** tapping a row both persists and closes (`SettingsViewModel.kt:241-244` sets
  `readingModeDialogOpen = false` then `setReadingMode(mode)`). No Apply/Revert.
- **Dialogs/sheets/snackbars:** Cancel button (`Res.string.cancel`); back-press/outside-tap dismiss.
- **Forms & validation:** none.
- **Data/behavior:** 6 `ReadingMode` entries (DEFAULT, RIGHT_TO_LEFT, LEFT_TO_RIGHT, VERTICAL,
  WEBTOON, CONTINUOUS_VERTICAL — `domain/.../model/reader/ReadingMode.kt:85-92`); labels via
  `readingModeLabel` (`SettingsScreen.kt:951-958`). Persisted via `SetReadingModeUseCase` (shares the
  same `reading_mode` pref key as the legacy reader path).
- **Feature inventory:** 6 mode radio rows + Cancel.
- **Citations:** `ui/.../settings/SettingsScreen.kt:951-1043`;
  `presentation/.../settings/SettingsViewModel.kt:241-244`.

---

### ThemeScreen (Theme picker — `themepicker`)

- **Entry/route:** `Screen.ThemeRework` → `ThemeReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt:160-167`); wired `App.kt:736-742`,
  bottom bar hidden (`:737`). `navController`/`backStackEntry` both `@Suppress("UNUSED_PARAMETER")`
  — terminal screen, no outbound nav (`ThemeReworkScreenRoute.kt:162-163`). Composable entry:
  `ThemeScreen(viewModel, modifier, onContinue?, hasNotificationPermission = true,
  onRequestNotificationPermission?)` (`ui/.../themepicker/ThemeScreen.kt:266-282`); stateless inner
  `ThemeScreenContent` (`:286-318`). **NOTE present-but-unwired:** the route adapter passes NONE of
  the 3 onboarding params (`ThemeReworkScreenRoute.kt:166` calls `ThemeScreen(viewModel = viewModel)`
  only), so the Continue button + notification grant row are dead code on this route — they exist for
  a future wizard caller (Phase 7.x.theme.swap), per KDoc (`ThemeScreen.kt:69-93`,
  `ThemeReworkScreenRoute.kt:21-37`). The `AnimatedBackground` gradient overlay + auto-permission-request
  lifecycle from the legacy onboarding picker are DEFERRED `(INFERRED — documented but not implemented)`.
- **Layout & components:** `Scaffold` + `TopAppBar` (title `Res.string.theme_screen_title` → "Theme")
  (`:295-299`). Body: loading `Box` spinner or `ThemePickerColumn` (`:300-317`). `ThemePickerColumn`
  (`:332-385`): a `Column` (padded `spacing.lg` horizontal / `spacing.md` vertical) containing —
  "Choose Your Theme" `titleMedium` text → a `TabRow` of 3 `Tab`s (Light/Dark/System) →
  `PureBlackRow` (Switch) → optional `NotificationPermissionRow` → optional Continue `Button`.
- **Visual:** spacing from `LocalSpacing` (lg/md). TabRow text-only (`Tab` not `LeadingIconTab`,
  no icons — `:360-366`); `selectedTabIndex = selected.indexInPicker` (Light=0/Dark=1/System=2,
  `:476-481`). PureBlackRow label `bodyLarge` (`:404-408`). All Material 3 defaults otherwise.
- **States:** loading → centered `CircularProgressIndicator` in `LoadingBox` while `state.isLoading`
  (`:301-302,:320-330`); else picker. No empty/error states (pure preference flow). Success = live
  tab selection + switch state.
- **Interactions:** tab tap → `ThemeIntent.OnSelectTheme(theme)` (`:363`); pure-black switch →
  `OnTogglePureBlack(it)` (`:370`); both fire-and-forget, upstream re-emits drive UI
  (`ThemeViewModel.kt:137-146`). Continue button gated on
  `onRequestNotificationPermission == null || hasNotificationPermission` (`ThemeScreen.kt:378`).
  Grant button → `onRequest()` (`:461`). No animations beyond Material defaults.
- **Dialogs/sheets/snackbars:** none. `ThemeEffect` is an empty sealed interface — no effect
  collection (`presentation/.../theme/ThemeEffect.kt:59`).
- **Forms & validation:** none.
- **Data/behavior:** state from `ThemeViewModel` (two independent `init{}` collectors —
  `ObserveAppThemeUseCase` + `ObservePureBlackUseCase`, `ThemeViewModel.kt:123-135`). Theme tri-state
  collapses the legacy two-boolean (`darkMode`+`followSystem`) representation; persists via
  `SetAppThemeUseCase` / `SetPureBlackUseCase` to the same `:shared SettingsRepository` SharedPrefs
  keys (`KEY_THEME_MODE`/`KEY_THEME_SYSTEM`/`KEY_PURE_BLACK`). Single source of truth shared with the
  Settings-hub theme toggles (toggling on one reflects on the other). No permissions actually
  requested on this route.
- **Feature inventory:**
  1. **Light** tab → `OnSelectTheme(AppTheme.Light)`
  2. **Dark** tab → `OnSelectTheme(AppTheme.Dark)`
  3. **System** tab → `OnSelectTheme(AppTheme.System)`
  4. **Pure black dark mode** Switch (`PureBlackRow`) → `OnTogglePureBlack` — always interactive,
     no enabled-gate (`:368-371,:395-414`).
  5. **Enable Notifications** grant row (title + body `Res.string.notification_permission` + "Grant
     Permission" button) — only when `onRequestNotificationPermission != null && !hasNotificationPermission`
     (`:372-373,:442-466`) — UNWIRED on this route.
  6. **Continue** button (`Res.string.continue_string`) — only when `onContinue != null`
     (`:375-383`) — UNWIRED on this route.
- **Citations:** `ui/.../themepicker/ThemeScreen.kt:264-494`;
  `presentation/.../theme/ThemeViewModel.kt:114-147`; `presentation/.../theme/ThemeState.kt:80-84`;
  `presentation/.../theme/ThemeIntent.kt:68-98`; `presentation/.../theme/ThemeEffect.kt:59`;
  `composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt:160-167`; `App.kt:736-742`;
  `domain/.../model/theme/AppTheme.kt:74-78`.

---

### LanguageScreen (Language picker)

- **Entry/route:** `Screen.LanguageRework` → `LanguageReworkScreenRoute(navController, backStackEntry)`
  (`composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt:117-124`); wired `App.kt:803-809`,
  bottom bar hidden (`:804`). Both nav params `@Suppress("UNUSED_PARAMETER")` — terminal screen.
  Composable entry: `LanguageScreen(viewModel, modifier)` (`ui/.../language/LanguageScreen.kt:154-167`);
  stateless inner `LanguageScreenContent` (`:169-226`). **NOTE present-but-unwired:** App.kt comment
  (`:799-802`) still says "not surfaced in any user-facing entry yet" — reachable only via the Settings
  hub LANGUAGE nav row in practice `(INFERRED)`.
- **Layout & components:** `Scaffold` + `TopAppBar` (title `Res.string.select_language` → "Select
  Language") + `SnackbarHost` (`:195-200`). Body: loading `Box`/`LanguageList`. `LanguageList`
  (`:240-272`): `LazyColumn` of `LanguageRow`s (key = `language.code`) each followed by
  `HorizontalDivider`, plus a trailing `RequestLanguageRow` item (key `"__request_language__"`).
- **Visual:** spacing from `LocalSpacing` (lg horizontal / md vertical). `LanguageRow` (`:284-324`):
  `Row` with `SpaceBetween` arrangement — left `Column`(displayName `bodyLarge` + code `bodySmall`
  on `onSurfaceVariant`), right fixed-size `Box(24.dp)` reserving the check-icon slot (uniform row
  height regardless of selection). Selected row shows `Icon(YamiIcons.Check, tint = primary)`
  (`:316-320`). `RequestLanguageRow` (`:332-350`): single `bodyLarge` primary-colored row.
- **States:** loading → centered spinner in `LoadingBox` while `state.isLoading`
  (`:202-203,:228-238`); else list. No empty state (the 11-language list is a compile-time constant
  read synchronously at VM construction — `LanguageViewModel.kt:157`). Request-submission failure →
  snackbar (no persistent error). Success = trailing check on the selected row.
- **Interactions:** row tap → `OnSelectLanguage(code)` (`:258`) → `SetLanguageUseCase` (fire-and-forget,
  triggers `applyApplicationLocale` side effect; Android recreates the activity tree, iOS/Desktop
  no-op). Re-tapping current row is a no-op at DataStore level. Request row tap →
  `OnOpenRequestDialog` (`:265`). Effect collection via `LaunchedEffect(effects)` (`:184-193`).
- **Dialogs/sheets/snackbars:** **LanguageRequestDialog** (`:217-225`, def `:371-433`). Snackbars:
  `RequestSubmitted` → "Request submitted successfully" (`Res.string.request_submitted_successfully`),
  `RequestFailed` → "Request failed" (`Res.string.request_failed`); strings resolved before the
  collector coroutine (`:181-182`).
- **Forms & validation:** in the request dialog (below).
- **Data/behavior:** state from `LanguageViewModel`; `languages` set once at construction from
  `GetSupportedLanguagesUseCase` (`LanguageViewModel.kt:157`); `selectedCode` flow-driven from
  `ObserveSelectedLanguageUseCase` (`:160-166`). 11 supported langs (en/ar/de/es/fr/in/it/ja/pt/ru/tr)
  with native endonyms live in the `:data LanguageRepositoryImpl` (single source of truth). No
  permissions.
- **Feature inventory:** N language rows (tap-to-select, trailing check on selected) + 1 "Request a
  language" row. Top bar title only (no actions, no back IconButton — system back only).
- **Citations:** `ui/.../language/LanguageScreen.kt:154-435`;
  `presentation/.../language/LanguageViewModel.kt:151-211`;
  `presentation/.../language/LanguageState.kt:112-119`;
  `presentation/.../language/LanguageIntent.kt:74-132`;
  `presentation/.../language/LanguageEffect.kt:64-86`;
  `composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt:117-124`; `App.kt:803-809`;
  `domain/.../model/language/Language.kt:79-82`.

---

### LanguageRequestDialog (Language picker modal)

- **Entry/route:** rendered when `state.requestDialogVisible` (`LanguageScreen.kt:217-225`); opened
  by `OnOpenRequestDialog` from the "Request a language" row.
- **Layout & components:** `AlertDialog` (`:380-432`) — title `Res.string.request_add_language` →
  "Request a language"; body `Column`: prompt text (`Res.string.request_language_prompt`,
  `bodyMedium` on `onSurfaceVariant`) + `Spacer(12.dp)` + multiline `OutlinedTextField`
  (`minLines = 4, maxLines = 6, heightIn(min = 120.dp)`, label `Res.string.enter_your_language`);
  confirm `Button`, dismiss `TextButton` (Cancel). **NOTE:** unlike the Settings FeedbackDialog,
  this dialog has NO category dropdown — the `:data` impl hardcodes `subject = "Languages"`.
- **Visual:** default `AlertDialog` shape/colors (no custom RoundedCornerShape/tonalElevation, unlike
  the Settings dialogs). Material 3 defaults.
- **States:** in-flight `submitting` → confirm button shows an 18.dp `CircularProgressIndicator`
  (strokeWidth 2.dp) in place of the "Submit" label, button disabled (`:413-425`). TextField stays
  interactive during submit (`:391-409`).
- **Interactions:** typing → `OnRequestTextChange(text)` (per keystroke, `:221`); Submit →
  `OnSubmitRequest` (`:222`); Cancel/scrim → `OnDismissRequestDialog` (`:223`).
- **Dialogs/sheets/snackbars:** success/failure surfaced as snackbars on the underlying screen (see
  LanguageScreen). On success the VM also clears text + closes dialog; on failure keeps dialog open
  with typed text (`LanguageViewModel.kt:194-206`).
- **Forms & validation:** `submitEnabled = !submitting && text.length >= MIN_REQUEST_LENGTH (8)`
  (`:379,:435`). `isError` + "At least 8 characters" helper below threshold
  (`Res.string.at_least_n_characters`, `:400-408`). VM re-entrance guard
  `if (requestSubmitting) return` (`LanguageViewModel.kt:189`).
- **Data/behavior:** submit → `SendLanguageRequestUseCase(body)` (`LanguageViewModel.kt:193`) → writes
  to the same legacy `:shared` complaint/Firestore pipeline as the Settings feedback flow.
- **Feature inventory:** prompt text, body field, Submit (spinner while in flight), Cancel.
- **Citations:** `ui/.../language/LanguageScreen.kt:371-435`;
  `presentation/.../language/LanguageViewModel.kt:188-208`;
  `presentation/.../language/LanguageEffect.kt:64-86`.

---

### Cluster notes — full KMP design-token inventory

The rework design system lives in `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/`. Tokens
copied verbatim from legacy `composeApp/.../theme/Theme.kt` (commit `e0466ce` baseline) per the
behavior-preservation KDocs. The applied root composable is `YamiTheme` (`YamiTheme.kt:88-114`).

**NOTE — app-root theme not yet rewired:** Per `YamiTheme.kt:55-66` KDoc, `App.kt` still applies the
LEGACY `YamiMangaTheme`, not the rework `YamiTheme`. Leaf `:ui` screens use `YamiTheme` at screen
scope but the app-root provider migration is unfulfilled. So feature screens read tokens via
`MaterialTheme.*` / `LocalSpacing`, and the actual `ColorScheme` at runtime comes from the legacy
`YamiMangaTheme` (which holds byte-identical hex values). `dynamicColor` is a no-op stub awaiting a
`DynamicColorProvider` SPI `(INFERRED — both documented as not landed)`.

#### Colors — `YamiColors.kt` (Material 3 `darkColorScheme` / `lightColorScheme`)

**Dark scheme** (`YamiColors.kt:54-86`):
- primary `#B0C6FF`, onPrimary `#002D6E`, primaryContainer `#00429B`, onPrimaryContainer `#D7E2FF`
- secondary `#B0C6FF`, onSecondary `#002D6E`, secondaryContainer `#00429B`, onSecondaryContainer `#D7E2FF`
- tertiary `#B8D0FF`, onTertiary `#003063`, tertiaryContainer `#2C2C2F`, onTertiaryContainer `#D6E3FF`
- background `#15202B` (Twitter-night-blue, deliberate override of M3 `#1B1B1F`), onBackground `#E3E2E6`
- surface `#15202B`, onSurface `#E3E2E6`, surfaceVariant `#44464F`, onSurfaceVariant `#C4C6D0`
- outline `#8E9099`, inverseOnSurface `#1B1B1F`, inverseSurface `#E3E2E6`, inversePrimary `#0058CA`
- error `#FFB4AB`, onError `#690005`, errorContainer `#93000A`, onErrorContainer `#FFDAD6`

**Light scheme** (`YamiColors.kt:88-120`):
- primary `#0058CA`, onPrimary `#FFFFFF`, primaryContainer `#D7E2FF`, onPrimaryContainer `#001945`
- secondary `#0058CA`, onSecondary `#FFFFFF`, secondaryContainer `#D7E2FF`, onSecondaryContainer `#001945`
- tertiary `#0061A3`, onTertiary `#FFFFFF`, tertiaryContainer `#2C2C2F`, onTertiaryContainer `#001D36`
- background `#FEFBFF`, onBackground `#1B1B1F`, surface `#FEFBFF`, onSurface `#1B1B1F`,
  surfaceVariant `#E3E2EC`, onSurfaceVariant `#44464F`
- outline `#757780`, inverseOnSurface `#F2F0F4`, inverseSurface `#303034`, inversePrimary `#B0C6FF`
- error `#BA1A1A`, onError `#93000A`, errorContainer `#FFDAD6`, onErrorContainer `#410002`

**Pure-black / OLED:** applied in `YamiTheme` (`YamiTheme.kt:96-103`) — when `darkTheme && pureBlack`,
`baseScheme.copy(background = Color.Black, surfaceContainer = Color.Black)`. Base palettes stay pure;
no per-screen OLED branch. Drawables/colors resource dirs exist (`composeResources/drawable`) but no
custom `values/colors.xml` was found — colors are pure code tokens `(INFERRED)`.

#### Typography — `YamiTypography.kt` (Gellix)

Font family `gellixFontFamily()` (`YamiTypography.kt:30-34`): Gellix Regular (`FontWeight.Normal`),
Gellix SemiBold (`FontWeight.Medium`), Gellix Bold (`FontWeight.Bold`). Font files:
`composeResources/font/gellix_{regular,semibold,bold}.ttf`.

`yamiTypography()` (`:40-48`) overrides ONLY 3 slots (legacy parity, byte-for-byte), all others
inherit M3 defaults:
- `bodyLarge` = Gellix Bold, 16.sp
- `titleMedium` = Gellix Medium, 14.sp
- `titleSmall` = Gellix Normal, 12.sp

Gellix is Latin-only; the remaining slots intentionally keep the M3 default family to preserve Arabic
glyph fallback (`:23-27`). `(INFERRED)` runtime application of `yamiTypography()` depends on the
`YamiTheme` vs legacy `YamiMangaTheme` root question above.

#### Shapes — `YamiShapes.kt` (M3 `Shapes`)

`YamiShapes` (`YamiShapes.kt:36-42`): extraSmall `RoundedCornerShape(4.dp)`, small `8.dp`,
medium `12.dp`, large `16.dp`, extraLarge `0.dp` (intentional full-bleed for reader surfaces).
NOTE: the settings/feedback dialogs use hardcoded `RoundedCornerShape(20.dp)` (dialog) /
`12.dp` (fields/cards) literals rather than `YamiShapes` slots.

#### Spacing — `Spacing.kt` (8-pt grid via `LocalSpacing`)

`Spacing` data class (`Spacing.kt:50-58`): xxs `2.dp`, xs `4.dp`, sm `8.dp`, md `12.dp`, lg `16.dp`,
xl `24.dp`, xxl `32.dp`. Exposed via `val LocalSpacing = compositionLocalOf { Spacing() }` (`:64`) —
default lambda means previews work without a `YamiTheme` scope. `YamiTheme` installs it via
`CompositionLocalProvider(LocalSpacing provides Spacing())` (`YamiTheme.kt:110`). All three screens
in this cluster consume `LocalSpacing.current.*` rather than `.dp` literals (with the dialog-shape
exceptions noted).

#### Root theme — `YamiTheme.kt`

`YamiTheme(darkTheme, pureBlack = false, dynamicColor = false (no-op), content)` (`:88-114`):
picks dark/light scheme, applies pure-black override, wraps `MaterialTheme(colorScheme, typography =
yamiTypography(), shapes = YamiShapes)` + installs `LocalSpacing`. `dynamicColor` reserved/dormant.

#### Resource strings (relevant keys, `values/strings.xml` — en; `values-ar` exists for reused keys)

`settings`="Settings", `theme_screen_title`="Theme", `theme_light`="Light Mode", `theme_dark`="Dark
Mode", `theme_system`="System", `pure_black_mode_title`="Pure black dark mode", `follow_system_theme`=
"Follow system theme", `setting_downloaded_only`="Downloaded only", `setting_incognito`="Incognito
mode", `reading_mode`="Reading mode", `clear_cache`="Clear cache", `cached_size`="Cached: %1$s",
`help`="Help", `request_feature_bug`="Request feature / bug", `choose_your_theme`="Choose Your Theme",
`continue_string`="Continue", `enable_notifications`="Enable Notifications", `notification_permission`=
"We need this permission to send you the latest chapters…", `grant_permission`="Grant Permission",
`select_language`="Select Language", `request_add_language`="Request a language", `enter_your_language`=
"Enter your language", `request_language_prompt`="Let us know which language you'd like us to support.",
`at_least_n_characters`="At least %1$d characters", `selected`="Selected"
(`ui/.../composeResources/values/strings.xml:28-158`).

**Localization gaps (en-only inline literals, pending trusted Arabic):** the CBZ section labels
("Use Yami Compressor", "Auto-convert on Download", "Compress Existing Downloads", "Start Conversion",
"Converting..."), the General toggle descriptions, the theme-toggle descriptions, "Testing Mode", and
all VM snackbar strings ("Cache cleared", "Conversion complete", "Thanks! Your feedback was
submitted.", "Failed to clear cache: …", etc.) are hardcoded literals, not `stringResource`
(`SettingsScreen.kt:329,337,363,393-404,560-583`; `SettingsViewModel.kt:212,229-235,258,264`).

---

### Cross-cutting notes / parity risks

- **Settings 5-char vs use-case 8-char feedback gate** (`SettingsScreen.kt:794` vs KDoc `:765-769`):
  5–7 char bodies pass UI but fail server-side. Documented known mismatch.
- **ThemeScreen onboarding affordances are dead code on the live route**: Continue button +
  notification grant row + permission-gated Continue are present in the composable but never wired by
  `ThemeReworkScreenRoute` (passes no params). `AnimatedBackground` + auto-permission-request lifecycle
  fully deferred.
- **App-root still on legacy `YamiMangaTheme`**, not rework `YamiTheme` (`YamiTheme.kt:55-66`);
  `dynamicColor` no-op.
- **Reachability**: App.kt comments repeatedly assert these rework routes are "not surfaced in any
  user-facing entry yet." In practice Theme/Statistics/Language/About/Complaint/WhatsNew/Downloads are
  reachable via the Settings hub nav rows; whether the Settings hub itself is user-reachable was not
  confirmed in this scope `(INFERRED)`.


---

# CLUSTER: history_updates_statistics

# KMP App Audit — History + Updates + Statistics cluster

Scope: the architecture-rework (`:ui` / `:presentation` / `:domain` / `:composeApp`) implementations of the History list, the Updates feed, and the Statistics screen. Read-only audit; no sources modified. Citations are `file:line` against the rework KMP tree under `D:/yami manga/yami-kmp/`.

**Routing note (applies to all three screens).** Each screen is reachable through TWO route keys:
- The user-facing legacy key now hosts the rework `:ui` screen (post-swap, tasks #286/#288/#289):
  - `Screen.History` → `HistoryScreenRoute` → `ui.history.HistoryScreen` (`App.kt:453-459`; adapter import `HistoryScreenRoute.kt:9`, call `:173`).
  - `Screen.Updates` → `UpdatesScreenRoute` → `ui.updates.UpdatesScreen` (`App.kt:461-467`; adapter import `UpdatesScreenRoute.kt:9`, call `:108`).
  - `Screen.Statistics` → `StatisticsScreenRoute` → `ui.statistics.StatisticsScreen` (`App.kt:518-524`; adapter import `StatisticsScreenRoute.kt:8`, call `:130`).
- A second, parallel `*Rework` key renders the SAME `:ui` screen through a near-identical adapter, debug-reachable only, not surfaced in any user nav (`App.kt:654-696`): `Screen.StatisticsRework`/`HistoryRework`/`UpdatesRework`. These are documented as "reachable via `navController.navigate(...)` from a future developer trigger" — i.e. **present but unwired** in production nav. (`App.kt:651-653`, `:669-671`, `:687-689`.)

---

### HistoryScreen

- **Entry/route:** `Screen.History` (bottom-nav-eligible; `onBottomBarVisibleChange(true)`, `App.kt:454`) → `HistoryScreenRoute(navController, backStackEntry)` (`App.kt:455`). Also `Screen.HistoryRework` → `HistoryReworkScreenRoute` (`App.kt:672-678`, debug-only duplicate). Composable: `HistoryScreen(viewModel, onNavigateToDetails, onNavigateToReader, modifier)` (`ui/.../history/HistoryScreen.kt:138-154`). VM resolved via `koinViewModel()` in the adapter (`HistoryReworkScreenRoute.kt:79`).
- **Layout & components:** `Scaffold` (`HistoryScreen.kt:175`) with a `TopAppBar` (`:178`) whose title is `stringResource(Res.string.history)` (`:179`) and whose single action is a `TextButton` labelled "Clear all" (`:181-186`). Body is a `Box(fillMaxSize + background)` (`:191-196`) switching on state. Success path: `HistoryList` = `LazyColumn` (`:216`) with `stickyHeader` date-group headers (`:221-232`) and `items(key = { it.id })` rows (`:234-241`). Each row is `HistoryRow`: a `Card` (`:254`) containing a `Row` with a 72×108dp cover `AsyncImage` (`:271-284`), a weighted `Column` of three `Text`s (manga title / chapter title / relative date) (`:288-311`), and a trailing "Delete" `TextButton` (`:313-315`).
- **Visual:** spacing via `LocalSpacing.current` (`:253`) mixed with raw `.dp` literals. Card: `RoundedCornerShape(12.dp)` (`:262`), `containerColor = surfaceVariant` (`:260`), padded `horizontal=16dp, vertical=4dp` (`:257`), inner padding `spacing.md` (`:267`). Cover: `RoundedCornerShape(6.dp)` clip + faint background tint `background.copy(alpha=0.15f)` (`:270,279-282`), `ContentScale.Crop` (`:274`). Group header: `titleMedium` + `FontWeight.SemiBold`, `onBackground`, opaque `background` strip, padding `h=16,v=8dp` (`:222-231`). Title `titleMedium`/`onSurface`/`maxLines=2`/ellipsis (`:292-298`); chapter `bodyMedium`/`maxLines=1` (`:299-305`); date `bodySmall`/`onSurfaceVariant` (`:306-310`). "Clear all" and "Delete" both colored `colorScheme.error` (`:185,314`). No explicit elevation set on the Card (defaults).
- **States:** loading → `YamiLoadingState()` (centered `CircularProgressIndicator`, `:198`; component at `YamiStateViews.kt:48-56`). empty → `YamiEmptyState(title = no_reading_history)` (`:199`; centered muted `YamiIcons.Empty` icon + title, `YamiStateViews.kt:69-112`). success → `HistoryList` (`:200-203`). No error state — `HistoryState` has no `error` field by design (Room observe-site no-throw; `HistoryState.kt:75-82`).
- **Interactions:** row body click → `OnChapterClick(entry)` (`:237`); cover click → `OnMangaClick(entry)` (`:283,238`); per-row "Delete" → `OnDeleteEntry(entry)` (`:239,313`); top-bar "Clear all" → `OnDeleteAll`, enabled only when `items.isNotEmpty()` (`:182-183`). No long-press, no swipe, no undo, no explicit animations. Nav effects collected in a `LaunchedEffect(effects)` (`:166-173`).
- **Dialogs/sheets/snackbars:** NONE. There is **no delete-confirmation dialog and no undo** for History deletes — `OnDeleteEntry`/`OnDeleteAll` fire immediately and irreversibly (VM `HistoryViewModel.kt:124-129`). (Contrast: Updates has undo; Library has a confirm dialog.) (INFERRED gap vs. typical parity expectations.)
- **Forms & validation:** none.
- **Data/behavior:** flow-driven. VM subscribes to `ObserveHistoryUseCase()` in `init {}` and projects each snapshot into `items` with `isLoading=false` (`HistoryViewModel.kt:114-120`). Deletes are fire-and-forget `viewModelScope.launch { deleteHistoryEntry / deleteAllHistory }` (`:124-129`); the upstream Room flow re-emits, so the list updates reactively with no imperative mutation. Click intents emit effects only (`:130-136`). Effects: `NavigateToDetails(api, mangaUrl)` and `NavigateToReader(entry)` (`HistoryEffect.kt:71-87`). Route adapter maps `NavigateToDetails` → `Screen.MangaDetails(mangaUrl, api)` (`HistoryReworkScreenRoute.kt:83-90` / `HistoryScreenRoute.kt:173+`), and `NavigateToReader` → `Screen.ChapterImagesFragment(...)` carrying the full identity tuple, with the legacy quirks `chapterId = entry.id` and `chapterNumber = entry.chapterTitle` (`HistoryReworkScreenRoute.kt:91-108`). No permissions. Cover loads via singleton ImageLoader (no per-request builder).
- **Date grouping:** `groupByDate` = `groupBy { it.lastReadDate.date }` → sortedByDescending → `associate` into LinkedHashMap (`:325-330`). Group label formatter: Today / Yesterday / "N days ago" (2..6) / else `"MMM d, yyyy"` with English-only `monthAbbrev` (`:332-345,362-376`). Per-row relative date: Today / Yesterday / days_ago(<7) / weeks_ago(<30) / months_ago(<365) / years_ago (`:347-360`). Uses `Clock.System.now()` in current TZ.
- **Feature inventory:** (1) top bar title; (2) "Clear all" action (error-colored, disabled when empty); (3) sticky date-group headers; (4) per-row cover thumbnail (tappable → details); (5) per-row manga title; (6) per-row chapter title; (7) per-row relative-date subtitle; (8) per-row "Delete" (error-colored); (9) row-body tap → reader; (10) loading spinner; (11) empty illustration. NO: refresh, sort, filter, search, multi-select, swipe, undo, confirm dialog, icon affordances (labels are text `TextButton`s, not icons — by design, no `materialIconsExtended` in `:ui`).
- **Citations:** `ui/.../history/HistoryScreen.kt:138-376`; `presentation/.../history/HistoryViewModel.kt:106-139`; `HistoryState.kt:75-82`; `HistoryIntent.kt:61-89`; `HistoryEffect.kt:71-87`; `domain/.../model/history/HistoryEntry.kt:95-125`; `composeApp/.../routes/HistoryReworkScreenRoute.kt:74-110`; `composeApp/.../routes/HistoryScreenRoute.kt:9,173`; `App.kt:453-459,672-678`; `YamiStateViews.kt:48-112`.

---

### UpdatesScreen

- **Entry/route:** `Screen.Updates` (`onBottomBarVisibleChange(true)`, `App.kt:462`) → `UpdatesScreenRoute` → `ui.updates.UpdatesScreen` (`UpdatesScreenRoute.kt:9,108`). Also `Screen.UpdatesRework` → `UpdatesReworkScreenRoute` (`App.kt:690-696`, debug-only duplicate). Composable: `UpdatesScreen(viewModel, onNavigateToDetails, onNavigateToReader, modifier)` (`ui/.../updates/UpdatesScreen.kt:185-201`).
- **Layout & components:** `Scaffold` (`:253`) with `TopAppBar` titled `Res.string.updates` (`:257`) and TWO actions: "Mark all read" `TextButton` (`:259-264`) and "Clear all" `TextButton` (`:265-270`); plus a `snackbarHost = SnackbarHost(snackbarHostState)` (`:274`). Body `Box(fillMaxSize+background)` (`:276`) switches on state. Success: `UpdatesList` = `LazyColumn` (`:306`) with `stickyHeader` date headers (`:311-322`) + `items(key={it.id})` (`:324-333`). Row = `UpdatesRow`: `Card` (`:350`) → `Row` with 72×108dp cover `AsyncImage` (cover tap → manga, `:367-380`), a weighted `Column` of three texts (`:384-408`), and a trailing `Column(End)` of three stacked `TextButton`s: "Mark read", "Download"/"Downloaded", "Delete" (`:410-432`).
- **Visual:** identical card/cover idiom to History (`RoundedCornerShape(12.dp)`, `surfaceVariant`, 72×108 cover w/ 6dp clip + alpha-0.15 tint; `:355-380`). **Read-state styling:** unread title `FontWeight.SemiBold`, read `Normal`; read rows render content at 60% alpha (`titleWeight`/`contentAlpha` at `:348-349`, applied to all three texts `:391,399,406`). Title `titleMedium`/maxLines=2; chapter (`entry.chapterNumber`) `bodyMedium`/maxLines=1; date `bodySmall`/`onSurfaceVariant` (`:388-407`). "Clear all" colored `error` (`:269`); "Delete" colored `error` (`:430`); "Mark read"/"Download" default tint. Date headers same as History (`:312-321`).
- **States:** loading → `CircularProgressIndicator` centered (NOT the shared `YamiLoadingState` — hand-rolled here, `:283`). empty → centered `Text(no_updates)` in `bodyMedium`/`onSurfaceVariant` (NOT the shared `YamiEmptyState` illustration — no icon, `:284-289`). success → `UpdatesList` (`:290-293`). No persistent error state — errors surface transiently via snackbar effect. (Note: inconsistent with History's use of the shared state components — INFERRED minor parity drift.)
- **Interactions:** row body → `OnChapterClick` (`:327`); cover → `OnMangaClick` (`:328`); "Mark read" → `OnMarkAsRead`, disabled when `entry.isRead` (`:411-414,329`); "Download" → `OnDownloadClick`, disabled & relabelled "Downloaded" when `entry.isDownloaded` (`:417-428,330`); "Delete" → `OnRequestDelete` (soft-delete, `:331,429`); top-bar "Mark all read" → `OnMarkAllAsRead`, enabled iff `state.hasUnreadItems` (`:260-261`); top-bar "Clear all" → `OnDeleteAll`, enabled iff `visibleItems.isNotEmpty()` (`:266-267`). No swipe gestures (legacy swipe-to-dismiss was reframed as the per-row Delete button → undo-snackbar). No long-press. No explicit animations.
- **Dialogs/sheets/snackbars:** **Delete-with-undo snackbar** (`:225-240`). Per-row "Delete" dispatches `OnRequestDelete`; VM stages `entry.id` into `pendingDeleteIds` (hiding the row from `visibleItems`) and emits `ShowUndoSnackbar(entry)` (`UpdatesViewModel.kt:177-180`). The effect collector calls `snackbarHostState.showSnackbar(message=update_deleted, actionLabel=undo, withDismissAction=true, duration=Short)` (`:227-232`); `SnackbarResult.ActionPerformed` → `OnUndoDelete` (unstage, no DB write); `Dismissed` → `OnConfirmDelete` (unstage + `deleteUpdateEntry`) (`:233-238`; VM `:181-187`). Snackbar strings resolved in composable scope and captured by the `LaunchedEffect` (`:217-218`). Also a generic error snackbar: `ShowError(message)` → `showSnackbar(message)` (`:241-248`). No AlertDialogs/bottom sheets.
- **Forms & validation:** none.
- **Data/behavior:** flow-driven. VM `init{}` subscribes `ObserveUpdatesUseCase()` → `items` (`UpdatesViewModel.kt:137-143`). Reducer handles TEN intents (`:145-189`): 4 immediate mutating launches (mark-read, mark-all-read, delete-entry [legacy/OCP path], delete-all); 2 nav effects (manga/chapter click); download enqueue with `.onFailure { emit(ShowError(throwable.message ?: "Download failed")) }` (`:166-176`); soft-delete trio (request/undo/confirm, `:177-187`). Download enqueue → `EnqueueDownloadUseCase(chapterId, mangaTitle, api)` (`:168-172`). `OnDeleteEntry` is retained but UNUSED by the UI (the UI Delete button dispatches `OnRequestDelete`); kept for OCP (`UpdatesIntent.kt:68-80`) — **present-but-unwired intent**. Nav: `NavigateToDetails` → `Screen.MangaDetails(mangaUrl, api)`; `NavigateToReader` → `Screen.ChapterImagesFragment(... chapterId = entry.chapterId ...)` (dedicated field, no History-style quirk) (`UpdatesReworkScreenRoute.kt:122-148`). No permissions.
- **Unread state:** `entry.isRead` drives title weight + 60% content alpha (`:348-349`); `hasUnreadItems = visibleItems.any { !it.isRead }` gates "Mark all read" (`UpdatesState.kt:114`). `visibleItems` filters `pendingDeleteIds` out so soft-deleted rows disappear during the undo window (`UpdatesState.kt:96-98`).
- **Feature inventory:** (1) title; (2) "Mark all read" (disabled when all read/empty); (3) "Clear all" (error-colored, disabled when empty); (4) sticky date headers; (5) per-row cover (→ details); (6) title with unread-weight styling; (7) chapter-number text; (8) relative-date subtitle; (9) per-row "Mark read" (disabled when read); (10) per-row "Download"/"Downloaded" (disabled when downloaded); (11) per-row "Delete" → undo snackbar; (12) row tap → reader; (13) undo snackbar; (14) error snackbar; (15) read/unread visual (weight + alpha); (16) loading spinner; (17) empty text. NO: refresh, sort, filter, search, multi-select, swipe gestures, per-row running/queued download spinner (legacy had `DownloadViewModelv2` progress — DEFERRED then replaced by enqueue-only; `UpdatesReworkScreenRoute.kt:53-87`), icon affordances.
- **Citations:** `ui/.../updates/UpdatesScreen.kt:185-494`; `presentation/.../updates/UpdatesViewModel.kt:126-190`; `UpdatesState.kt:72-115`; `UpdatesIntent.kt:52-155`; `UpdatesEffect.kt:78-131`; `domain/.../model/updates/UpdateEntry.kt:105-134`; `composeApp/.../routes/UpdatesReworkScreenRoute.kt:113-149`; `composeApp/.../routes/UpdatesScreenRoute.kt:9,108`; `App.kt:461-467,690-696`.

---

### StatisticsScreen

- **Entry/route:** `Screen.Statistics` (`onBottomBarVisibleChange(false)`, `App.kt:519`) → `StatisticsScreenRoute` → `ui.statistics.StatisticsScreen(viewModel, onBack)` (`StatisticsScreenRoute.kt:8,130`). Also `Screen.StatisticsRework` → `StatisticsReworkScreenRoute` (`App.kt:654-660`, debug-only duplicate; bottom bar hidden). Back is wired to `navController.safePopBackStack()` (`StatisticsReworkScreenRoute.kt:113`). Composable signature `StatisticsScreen(viewModel, onBack, modifier)` (`StatisticsScreen.kt:139-151`).
- **Layout & components:** `Scaffold` (`:160`) with `TopAppBar` titled `Res.string.statistics` (`:164`) and a `navigationIcon` that is a labelled "Back" `TextButton` (NOT an icon — `:165-173`). Body `Column(fillMaxSize + background + padding lg)` (`:188-194`) containing: (1) `StatsOverview` card — a `Card`/`Row(SpaceEvenly)` of three `StatsOverviewCell`s: in-library count, read-duration string, completed count (`:195-199,228-272`); (2) `SectionTitle("Entries")` (`:203`) + `StatsItemsGroup` card with 3 `StatsItem` rows (in library / started / completed) separated by `StatsDivider` (`:204-210`); (3) `SectionTitle("Chapters")` (`:214`) + `StatsItemsGroup` card with 4 `StatsItem` rows (total / read / downloaded / bookmarked) (`:215-223`). `StatsItem` = `Row` with weighted title `Text` + value `Text` (`:299-321`).
- **Visual:** `LocalSpacing` tokens (`:187,234,257,276,301`) + raw `.dp`. Cards `RoundedCornerShape(12.dp)` + `surfaceVariant` (`:236-239,289-291`). Overview cell value `headlineSmall`/`SemiBold`/`onSurface`, label `labelMedium`/`onSurfaceVariant` (`:259-270`). Section title `titleMedium`/`SemiBold`/`onBackground`, vertical pad `spacing.sm` (`:277-283`). `StatsItem` title `bodyMedium`/`onSurface`, value `titleMedium`/`FontWeight.Medium`/`onSurface`, padded `h=spacing.lg, v=spacing.md` (`:299-321`). `StatsDivider` = `HorizontalDivider(background.copy(alpha=0.8f))` (`:323-328`). No charts/graphs — text-only numeric rows.
- **States:** loading → centered `CircularProgressIndicator` with early `return@Scaffold` (`:177-186`). success → the two-panel column. No empty state (always renders 8 numbers, zeros if no data). No error state — `StatisticsState` has no `error` field and the VM has no `.catch{}` (`StatisticsViewModel.kt:96-114`; `StatisticsState.kt:64-74`).
- **Interactions:** ONLY the top-bar "Back" `TextButton` → `onBack()` → `safePopBackStack()` (`:170-172`; adapter `StatisticsReworkScreenRoute.kt:113`). No clicks/long-press/swipe on any stat (terminal display screen). No animations. No effect collection (`StatisticsEffect` is an empty sealed interface).
- **Dialogs/sheets/snackbars:** NONE.
- **Forms & validation:** none. `StatisticsIntent` is an empty sealed interface; VM `handle()` is a no-op (`StatisticsViewModel.kt:116-118`).
- **Data/behavior:** flow-driven, read-only. VM `init{}` subscribes `ObserveReadingStatisticsUseCase()` and copies all 8 fields + `isLoading=false` into state (`StatisticsViewModel.kt:96-114`). Upstream is a `combine` of legacy `StatisticsRepository` flows (strangler-fig); cross-app writes (reader marking chapters read, etc.) re-emit naturally (`StatisticsState.kt:42-51`). No outbound nav (no per-manga drill-down, no per-day chart, no "clear read time"). No permissions.
- **Metrics/cards inventory (every metric):**
  - **StatsOverview card (3 cells):** in-library (`state.inLibrary`), read time (`state.readDuration`, a VM-supplied pre-formatted "Xh Ym" string), completed entries (`state.entriesCompleted`) (`:248-250`).
  - **Entries section (3 rows):** In library (`inLibrary`), Started (`entriesStarted`), Completed (`entriesCompleted`) (`:205-209`).
  - **Chapters section (4 rows):** Total (`chaptersTotal`), Read (`chaptersRead`), Downloaded (`chaptersDownloaded`), Bookmarked (`chaptersBookmarked`) (`:216-222`).
  - Total distinct backing fields = 8 (`inLibrary`, `readDuration`, `entriesStarted`, `entriesCompleted`, `chaptersTotal`, `chaptersRead`, `chaptersDownloaded`, `chaptersBookmarked`; `StatisticsState.kt:64-74` / `ReadingStatistics.kt:69-86`). `inLibrary` and `entriesCompleted` appear twice (overview + Entries section).
  - **Charts:** NONE present. No graph/bar/pie/timeline; purely numeric rows. (`readDuration` localization is deferred — `read_time` is en-only, the "Xh Ym" value itself stays VM/legacy-formatted; `StatisticsScreen.kt:78-81`, `ReadingStatistics.kt:13-17`.)
- **Feature inventory:** (1) title; (2) "Back" text button; (3) overview card with 3 big-number cells; (4) Entries section header; (5) 3 Entries rows; (6) Chapters section header; (7) 4 Chapters rows; (8) dividers between rows; (9) loading spinner. NO: icons (legacy used outlined material icons — intentionally omitted), charts, refresh, date-range selector, per-stat drill-down, clear/reset action, empty state.
- **Citations:** `ui/.../statistics/StatisticsScreen.kt:139-328`; `presentation/.../statistics/StatisticsViewModel.kt:90-119`; `StatisticsState.kt:64-74`; `StatisticsIntent.kt` (empty sealed); `StatisticsEffect.kt` (empty sealed); `domain/.../model/statistics/ReadingStatistics.kt:69-86`; `composeApp/.../routes/StatisticsReworkScreenRoute.kt:102-115`; `composeApp/.../routes/StatisticsScreenRoute.kt:8,130`; `App.kt:518-524,654-660`.

---

### Cluster notes

- **Icon-free posture across all three** is deliberate: `:ui` omits `compose.materialIconsExtended`, so legacy icon affordances (History `DeleteForever`/`Delete`, Updates `DownloadDone`, Statistics outlined glyphs + `ArrowBack`) are replaced by labelled `TextButton`s. Documented inline in each screen's KDoc (`HistoryScreen.kt:85-90`, `UpdatesScreen.kt:95-99`, `StatisticsScreen.kt:67-74,166-169`). The shared design-system DOES expose icons via `YamiIcons` (used by `YamiEmptyState`/`YamiErrorState`, `YamiStateViews.kt:72,139`), so this is a per-screen choice, not a capability gap. (Likely parity gap vs. native — INFERRED.)
- **State-component inconsistency:** History uses the shared `YamiLoadingState`/`YamiEmptyState` (illustrated icon + title); Updates hand-rolls a bare `CircularProgressIndicator` + plain `Text` empty state; Statistics hand-rolls its own spinner. The shared components exist and would unify these (`YamiStateViews.kt`). (INFERRED minor drift.)
- **Delete semantics differ:** Updates has soft-delete + undo snackbar; History deletes are immediate and irreversible with no dialog or undo (`HistoryViewModel.kt:124-129`). If native History offered confirm/undo this is a gap. (INFERRED.)
- **Date grouping is duplicated, not shared:** History and Updates each carry their own copy of `groupByDate` / `formatGroupLabel` / `formatRelativeDate` / `monthAbbrev` (`HistoryScreen.kt:325-376` and `UpdatesScreen.kt:443-494`) despite KDoc claiming `:ui` convergence on one idiom (`UpdatesScreen.kt:111-115`). The functions are identical except the History variant groups by `lastReadDate.date` (LocalDateTime) vs Updates `notificationDate` (LocalDate). (INFERRED: the "convergence point" is aspirational, not realized — code is copy-pasted.)
- **Absolute-date fallback is English-only** in both lists (`monthAbbrev` returns "Jan".."Dec"; `HistoryScreen.kt:362-376`, `UpdatesScreen.kt:480-494`); only relative-date keys (today/yesterday/N-ago) are localized. RTL/Arabic users see English month names for entries >6 days old in group headers.
- **Double-routing redundancy:** the `*ReworkScreenRoute` adapters (`Statistics/History/Updates Rework`) and the swapped legacy-key adapters render the same `:ui` screens. The Rework keys are unreachable in production nav (`App.kt:651-696`), so they are dead-but-compiled debug entries. The History/Statistics route adapters carry extensive `@Suppress("UNUSED_PARAMETER")` and large §253 audit-trail KDoc blocks; the parameters (`navController` for Statistics; `backStackEntry` for all) are genuinely unused beyond signature parity.
- **No `error` field anywhere in the cluster** — all three states model only `isLoading` + data; History/Statistics have no failure surface at all, Updates surfaces failures transiently via `ShowError` snackbar (only on download enqueue). Room observe-sites are assumed non-throwing.
- **`UpdatesIntent.OnDeleteEntry` is present-but-unwired** (UI uses `OnRequestDelete`); retained for OCP (`UpdatesIntent.kt:68-80`, VM branch `:153-155`).
- **Cover thumbnails** in History/Updates use plain `AsyncImage(model = url)` relying on the singleton ImageLoader (AVIF decoder, OkHttp fetcher, header interceptor, Skia decoder) — no per-request builder; consistent with Library card posture (`HistoryScreen.kt:271-284`, `UpdatesScreen.kt:367-380`).
- All string keys used by the three screens exist in `ui/.../composeResources/values/strings.xml` (verified: `history`, `clear_all`, `no_reading_history`, `today/yesterday/days_ago/weeks_ago/months_ago/years_ago`, `delete`, `updates`, `mark_all_read`, `mark_read`, `download`, `downloaded`, `no_updates`, `update_deleted`, `undo`, `statistics`, `section_entries/section_chapters`, `label_*`, `read_time`, `back`). Several Updates/Statistics-specific keys are noted as en-only pending trusted Arabic.


---

# CLUSTER: complaint_whatsnew_welcome_about

# KMP (rework) audit — Complaint / Admin Complaint / WhatsNew / Welcome / About cluster

Read-only audit of the architecture-rework `:ui` + `:presentation` + `:composeApp` route layer.
All citations are absolute-path `file:line`. Inferences are marked `(INFERRED)`.

Scope files read:
- `:ui` — `complaint/ComplaintScreen.kt`, `complaint/ComplaintActionDialog.kt`, `complaint/ComplaintStatusChip.kt`, `complaint/ComplaintStatusDisplay.kt`, `complaint/ComplaintTypeDisplay.kt`, `complaint/ComplaintDateFormat.kt`, `complaint/ComplaintIcons.kt`, `complaint/admin/AdminComplaintScreen.kt`, `complaint/admin/AdminComplaintActionDialog.kt`, `whatsnew/WhatsNewScreen.kt`, `welcome/WelcomeScreen.kt`, `about/AboutScreen.kt`, `about/SocialMediaIcons.kt`
- `:presentation` — complaint/{State,Intent,VM,Effect}, complaint/admin/{State,Intent,VM,Effect}, whatsnew/{State,Intent,VM,Effect}, about/{State,Intent,VM,Effect}
- `:composeApp` — routes/{ComplaintRework,AdminComplaintRework,WhatsNewRework,Welcome,AboutRework}ScreenRoute.kt, App.kt (nav registration)

---

### ComplaintScreen (user-side Feedback Manager)
- **Entry/route:** `Screen.ComplaintRework` registered in App.kt:826-828 → `ComplaintReworkScreenRoute` (`D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ComplaintReworkScreenRoute.kt:137-147`), which resolves `ComplaintViewModel` via `koinViewModel()` and passes `onBack = { navController.safePopBackStack() }`. The legacy `Screen.Complaint` route was swapped to render the rework screen too (§293, per route KDoc). Reachable from Settings hub (INFERRED — KDoc only).
- **Layout & components:** `Scaffold` with `TopAppBar` (title `feedback_manager_title`, SemiBold; navigationIcon = back IconButton using inline `ComplaintArrowBack` vector) + `SnackbarHost` (`ComplaintScreen.kt:201-221`). Body `Box` background = `colorScheme.background`. List = `LazyColumn` (`ComplaintScreen.kt:298-344`): first item is `SearchAndFilterSection`, then either a "no matches" item or `items(state.filtered)` of `ComplaintRow` cards.
- **Visual:** `LazyColumn` vertical content padding `spacing.md`, item spacing `spacing.sm` (`:298-302`). Cards: `surfaceVariant` container, `RoundedCornerShape(12.dp)`, inner padding `spacing.md`, item spacing `spacing.xs` (`:430-442`). Subject = `titleMedium`/SemiBold, 1 line ellipsis; body = `bodyMedium`, 3 lines ellipsis; type label = `bodySmall`/`onSurfaceVariant`; timestamp = `bodySmall`/`onSurfaceVariant` (`:449-492`). Search field `OutlinedTextField` `RoundedCornerShape(12.dp)`, singleLine (`:362-387`).
- **States:** loading → centered `CircularProgressIndicator` (`:230-232`); error (`state.error != null`) → centered `ErrorContent` = error-color text + Retry `TextButton` firing `OnRetry` (`:233-239`, `:269-289`); empty (`state.all.isEmpty()`) → centered `complaint_no_feedback` text (`:240-247`); success → `ComplaintList`. No-matches (filtered empty but query/status active) → centered `ComplaintSearchOff` icon + `complaint_no_matches` text (`:312-334`).
- **Interactions:** row click → `OnRowClick(complaint)` opens action dialog at MENU (`:339`); body long-press → writes `AnnotatedString(complaint.body)` to `LocalClipboardManager` then fires `OnCopyBody` (snackbar "Copied to clipboard") (`:467-473`); search text change → `OnSearchChange` (`:364`); clear-X trailing icon → `OnClearSearch` (`:375`); status filter chips → `OnStatusFilter(status|null)` with toggle-off (`:393-408`). No swipe, no paging, no animations.
- **Dialogs/sheets/snackbars:** `ComplaintActionDialog` mounts when `actionDialogMode != NONE && activeComplaint != null` (`:256-264`). Snackbars via `LaunchedEffect(viewModel)` collecting `ComplaintEffect.ShowSuccessMessage`/`ShowErrorMessage` → `snackbarHostState.showSnackbar` (`:173-180`); Material 3 default Short duration, no dismiss action.
- **Forms & validation:** search box only (no validation). Filter chips: "All" + `ComplaintStatus.entries` (8 statuses) (`:392-408`).
- **Data/behavior:** VM `init{}` calls `loadList()` via `ObserveUserComplaintsUseCase` (`ComplaintViewModel.kt:124-126`, `:251-279`). `filtered` derived in VM via `applyFilter` (search across subject/body/id case-insensitive + status) (`:281-292`). No outbound nav except back.
- **Feature inventory:** TopAppBar title + back-nav icon; search field (leading search icon, trailing clear-X); status filter chip row (All + 8); results-count text (`complaint_results_count`); per-row card (subject-or-type, status chip, 3-line body, type label, optional createdAt timestamp); row click → action dialog; body long-press copy; loading/error/empty/no-match states; snackbars.
- **Citations:** `ComplaintScreen.kt:164-189` (wiring), `:191-267` (scaffold/states), `:293-345` (list), `:347-419` (search+filter), `:421-495` (row); `ComplaintViewModel.kt:124-292`; `ComplaintReworkScreenRoute.kt:137-147`; `App.kt:826-828`.

### ComplaintActionDialog (user-side Reply/Edit/Delete)
- **Entry/route:** mounted by `ComplaintScreen` (`ComplaintScreen.kt:256-264`); internal composable (`ComplaintActionDialog.kt:216-258`). Driven by `state.actionDialogMode` (`ActionDialogMode` enum: NONE/MENU/REPLY/EDIT/DELETE — `ComplaintState.kt:149-155`).
- **Layout & components:** `Dialog` → `Card` (`fillMaxWidth`, padding 16.dp, elevation 8.dp, `RoundedCornerShape(16.dp)`) (`:226-232`); `when(mode)` selects MENU `ActionSelectionContent`, REPLY `ReplyContent`, EDIT `EditContent`, DELETE `DeleteConfirmationContent` (`:233-255`). Each sub-panel has a header Row (title + Close/Back TextButton) + `ComplaintPreviewCard`.
- **Visual:** sub-panel padding 24.dp, `spacing.md` arrangement. `ComplaintPreviewCard` = `surfaceVariant` card showing subject-or-type / 3-line body / status chip / `complaint_id_label` (`:682-729`). Reply preview card highlights `complaint_replying_to` in primary color (`:361-394`). Edit info card uses `primaryContainer` (`:494-510`); Delete warning card uses `errorContainer` (`:621-641`).
- **States:** all sub-panels gate buttons on `isSubmitting` (= `state.isSubmittingAction`); submit buttons swap label for a 16.dp `CircularProgressIndicator` while submitting (`:445-453`, `:576-584`, `:665-677`).
- **Interactions:** MENU → ElevatedButton Reply (always) + OutlinedButton Edit + OutlinedButton Delete (error-tinted), but Edit/Delete are HIDDEN when `complaint.status == ComplaintStatus.PINNED` (`:302-321`). Sub-mode Back → `OnSelectAction(MENU)`; Close/dismiss → `OnDismissActionDialog` (blocked while submitting — `:223-224`). Submit Reply → `OnSubmitReply(trimmed)`; Save → `OnSubmitEdit(subject, body)`; Delete forever → `OnConfirmDelete`.
- **Dialogs/sheets/snackbars:** is itself the dialog; success/error feedback flows through the parent screen's snackbar host.
- **Forms & validation:** Reply `rememberSaveable` text, ≤500 chars enforced in `onValueChange` + `isError` + char counter; Send enabled only when non-blank and ≤500 (`:327-455`). Edit subject (singleLine, no cap) + body (≤1000, counter, `isError`); Save enabled when subject+body non-blank and body ≤1000 (`:458-587`). Delete = confirmation-only (no input).
- **Data/behavior:** intents routed to use cases by `ComplaintViewModel` (`handleSubmitReply`/`handleSubmitEdit`/`handleConfirmDelete` → `ReplyToComplaintUseCase`/`EditComplaintUseCase`/`DeleteComplaintUseCase`, `ComplaintViewModel.kt:198-229`). On success: `completeAction` clears dialog, emits success snackbar, refires `loadList()`; on failure: keep dialog open at current mode + error snackbar (`:231-249`). In-flight guard via `isSubmittingAction` (`:172,182,192,200`).
- **Feature inventory:** MENU (Reply/Edit/Delete affordances + preview + Close); REPLY (parent preview card, reply textarea+counter, Cancel/Send); EDIT (editing-info card, subject field, body field+counter, Cancel/Save); DELETE (warning card, preview, Cancel/Delete-forever); PINNED-gating of Edit+Delete; submit spinners.
- **Citations:** `ComplaintActionDialog.kt:216-258` (shell), `:260-324` (MENU), `:326-456` (REPLY), `:458-587` (EDIT), `:589-680` (DELETE), `:682-729` (preview); `ComplaintViewModel.kt:198-249`.

### AdminComplaintScreen (admin dashboard)
- **Entry/route:** `Screen.ComplaintAdminRework` in App.kt:850-852 → `AdminComplaintReworkScreenRoute` (`D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/AdminComplaintReworkScreenRoute.kt:147-157`); `koinViewModel<AdminComplaintViewModel>()`, `onBack = safePopBackStack()`. Admin gate is at the calling site (Settings hub via `Admin.isAdmin`), NOT re-checked here (route KDoc:51-55). Legacy `Screen.ComplaintAdmin` swapped to rework (§365).
- **Layout & components:** `Scaffold` with `TopAppBar` (title `admincomplaint_title`; **back is an `actions`-slot `TextButton` labeled "back", NOT a navigationIcon** — `AdminComplaintScreen.kt:208-224`) + `SnackbarHost`. `LazyColumn` (`:323-371`): item 1 = `StatisticsCard`, item 2 = `SearchAndFilterSection`, then no-matches text OR `items(state.filtered)` of `AdminComplaintRow`.
- **Visual:** Card style identical to user-side (`surfaceVariant`, `RoundedCornerShape(12.dp)`). `StatisticsCard` (`:556-644`) = `statistics` title, `admincomplaint_total_complaints` count, per-status rows (count Monospace), per-app-version rows (both Monospace). Row footer shows type label, `userId.take(8)` (Monospace), optional `vX` appVersion (Monospace), optional createdAt timestamp (`:700-736`).
- **States:** loading → centered spinner (`:234-235`); error → `ErrorContent`+Retry `OnRetry` (`:237-243`); empty (`state.all.isEmpty()`) → `admincomplaint_no_complaints` text (`:244-251`); success → list; no-matches (filtered empty + any filter/search active) → `admincomplaint_no_matches` text (`:345-361`). **Note: admin no-matches has NO icon** (vs user-side which shows `ComplaintSearchOff`).
- **Interactions:** row click → `OnRowClick` (admin MENU); body long-press → clipboard write + `OnCopyBody`; search → `OnSearchChange`; clear → `OnClearSearch` (trailing is a TextButton "clear", not an icon — `:398-406`); status filter chips → `OnStatusFilter`; type filter chips → `OnTypeFilter`; app-version chips → `OnAppVersionFilter` (only rendered if `availableAppVersions.isNotEmpty()`, `:465-492`); sort dropdown → `OnSortChange`.
- **Dialogs/sheets/snackbars:** `AdminComplaintActionDialog` mounts when mode != NONE && active != null (`:260-268`). Snackbars via `LaunchedEffect(viewModel)` (`:180-187`).
- **Forms & validation:** search box; 2–3 filter-chip axes (status + type always; appVersion conditional); sort `OutlinedButton`+`DropdownMenu` over 7 `AdminSortMode.entries` with "▾" affix (`:518-543`); results-count singular/plural (`:504-512`).
- **Data/behavior:** VM `init{}` → `loadList()` via `ObserveAllComplaintsUseCase`; `computeStatistics` aggregates total/byStatus/byAppVersion from full `all` list (`AdminComplaintViewModel.kt:258-296`); `applyFilterAndSort` filters (search incl. userId) then sorts (`:298-326`). `availableAppVersions` computed at `:ui` boundary with semver-aware comparator (`AdminComplaintScreen.kt:302-322`).
- **Feature inventory:** title + back TextButton; statistics card (total + per-status + per-version); search (clear TextButton); status chip row; type chip row; conditional app-version chip row; sort dropdown (7 modes); results-count text; per-row card (subject/status chip/body/type/userId/version/timestamp); row click→admin dialog; body long-press copy; snackbars. **NO bulk-select UI present** despite task #265 "bulk" and KDoc references (see Cluster notes).
- **Citations:** `AdminComplaintScreen.kt:171-271` (scaffold/states), `:295-372` (list+version derivation), `:374-516` (search/filter/sort), `:518-554` (sort dropdown), `:556-644` (stats card), `:646-739` (row); `AdminComplaintViewModel.kt:82-326`; `AdminComplaintReworkScreenRoute.kt:147-157`.

### AdminComplaintActionDialog (admin mutations)
- **Entry/route:** mounted by `AdminComplaintScreen` (`AdminComplaintScreen.kt:260-268`); internal (`AdminComplaintActionDialog.kt:165-212`). Driven by `AdminActionDialogMode` (NONE/MENU/STATUS_CHANGE/CLOSURE_REASON/DELETE_CONFIRM/EDIT — `AdminComplaintState.kt:183-190`).
- **Layout & components:** same `Dialog`→`Card` shell (16.dp pad, 8.dp elev, 16.dp corner) (`:172-211`). MENU = `AdminComplaintPreviewCard` + 4 buttons: Change status (Elevated), Edit (Outlined), Add closure reason (Outlined), Delete (Outlined, error-tinted) — ordering Status/Edit/Closure/Delete (`:214-284`). Preview card shows `admincomplaint_preview_user` userId.take(12) Monospace + subject + 3-line body (`:612-646`).
- **Visual:** STATUS_CHANGE shows `admincomplaint_current_status` (primary) + a button per `ComplaintStatus` excluding current (`:286-350`); CLOSURE_REASON = textarea + char counter (≤500) + Add button (`:352-420`); EDIT = subject + body fields with ≤1000 body cap + counter (`:422-551`); DELETE_CONFIRM = warning text + error-tinted Delete-forever button (`:553-610`).
- **States:** all gated on `isSubmitting`; STATUS_CHANGE shows a centered 20.dp spinner while submitting (`:341-348`); CLOSURE_REASON/EDIT/DELETE swap button content for spinner.
- **Interactions:** MENU buttons → `OnSelectAction(mode)`; STATUS button → `OnSubmitStatusChange(candidate)`; Add → `OnSubmitClosureReason(reason)`; Save → `OnSubmitEdit(subject, body)`; Delete forever → `OnConfirmDelete`; Back → `OnSelectAction(MENU)`; Close/dismiss → `OnDismissActionDialog`. **No PINNED gate** (admin override — `AdminComplaintActionDialog.kt:118-120`).
- **Dialogs/sheets/snackbars:** is the dialog; feedback via parent snackbar host.
- **Forms & validation:** STATUS_CHANGE excludes current status (`:329-330`); CLOSURE_REASON non-blank + ≤500 (Add disabled when blank — `:407-411`); EDIT non-blank subject+body, body ≤1000 (`:527-538`); DELETE confirmation-only.
- **Data/behavior:** VM handlers → `ChangeComplaintStatusUseCase`/`AddClosureReasonUseCase`/`AdminEditComplaintUseCase`/`AdminDeleteComplaintUseCase` (`AdminComplaintViewModel.kt:190-232`); same `completeAction` refire-on-success / keep-open-on-failure pattern (`:238-256`).
- **Feature inventory:** MENU (4 admin actions + preview + Close); STATUS_CHANGE (current-status label + status buttons); CLOSURE_REASON (reason textarea + counter + Add); EDIT (subject/body + counters + Cancel/Save); DELETE_CONFIRM (warning + Delete-forever); per-action submit spinners.
- **Citations:** `AdminComplaintActionDialog.kt:165-212` (shell), `:214-284` (MENU), `:286-350` (STATUS), `:352-420` (CLOSURE), `:422-551` (EDIT), `:553-610` (DELETE), `:612-646` (preview); `AdminComplaintViewModel.kt:190-256`.

### WhatsNewScreen
- **Entry/route:** `Screen.WhatsNewRework` in App.kt:779-781 → `WhatsNewReworkScreenRoute` (`D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WhatsNewReworkScreenRoute.kt:72-82`); `koinViewModel<WhatsNewViewModel>()`, `onEffect = { /* no-op */ }` (effect interface is empty). Reached from About "What's new" row (in-app nav) and via swap of legacy `Screen.WhatsNew` (§290).
- **Layout & components:** `Scaffold` + `TopAppBar` (title `what_s_new`, NO back nav icon — relies on system back) (`WhatsNewScreen.kt:195-199`). Body 4-state `when` (`:200-217`). Loaded → `FeaturePager` = `HorizontalPager` of `FeatureCard`s (weight 1f) + `PageIndicatorRow` (dot row) (`:261-299`).
- **Visual:** pager `contentPadding = spacing.lg`; `FeatureCard` = `surfaceVariant` card `RoundedCornerShape(12.dp)`, padding `spacing.lg`, title `titleMedium`/SemiBold + optional `NewChip`, description `bodyMedium`/`onSurfaceVariant` (`:338-370`). `NewChip` = primary-color `Surface` `RoundedCornerShape(6.dp)`, `new_badge` text labelSmall/Bold/onPrimary (`:377-392`). Dot row: selected dot 10.dp primary, unselected 8.dp `onSurfaceVariant` alpha 0.3 (`:307-330`).
- **States:** loading → `YamiLoadingState`; error (`errorMessage != null`) → `YamiErrorState` + Retry (`OnRetry`); empty (`features.isEmpty()`) → `YamiEmptyState(whats_new_empty)`; loaded → pager (`:202-217`). Error path is wired-but-dormant — `:data` swallows failures and returns empty list (KDoc:59-67); empty is the de-facto path.
- **Interactions:** horizontal swipe drives pager; `LaunchedEffect(pagerState.currentPage)` dispatches `OnPageChanged(index)` mirroring page into VM state (bi-directional sync) (`:273-275`). No clicks, no long-press, no media.
- **Dialogs/sheets/snackbars:** none. `EffectBridge` collects `WhatsNewEffect` (empty interface) → dormant (`:182-186`).
- **Forms & validation:** none.
- **Data/behavior:** VM `init{}`→`loadFeatures()` via `GetWhatsNewFeaturesUseCase` (`WhatsNewViewModel.kt:147-170`); `OnMarkSeen` wired to `MarkWhatsNewSeenUseCase` but NEVER dispatched by `:ui` (auto-show gate deferred). `currentPage` survives recomposition via VM (`WhatsNewState.kt:98-103`).
- **Feature inventory:** title bar; HorizontalPager of feature cards; per-card title + optional NEW chip + description; page-indicator dot row; loading/error(+Retry)/empty states. NOT present (all deferred): images, video, fullscreen viewer, version-group headers, nav arrows, tabs.
- **Citations:** `WhatsNewScreen.kt:166-217` (wiring/states), `:261-299` (pager), `:307-330` (dots), `:338-392` (card+chip); `WhatsNewViewModel.kt:140-171`; `WhatsNewState.kt:98-103`; `WhatsNewReworkScreenRoute.kt:72-82`; `App.kt:779-781`.

### WelcomeScreen (first-launch intro)
- **Entry/route:** `Screen.Welcome` in App.kt:400-402 → `WelcomeScreenRoute` (`D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WelcomeScreenRoute.kt:128-138`); `onGetStarted = { navController.navigate(Screen.Theme) }`. `rootStart = if (firstLaunch) Screen.Welcome else Screen.Library` (App.kt:393). Step 1 of onboarding chain Welcome→Theme→Sources→RepoSettings→Library.
- **Layout & components:** stateless composable, no VM (`WelcomeScreen.kt:118-122`). `Surface` → `Box` (fillMaxSize) with: (1) animated diagonal gradient background, (2) vertical-gradient legibility overlay, (3) bottom-aligned centered `Column` with title/subtitle/Button (`:122-219`).
- **Visual:** title `welcome_title` headlineLarge 36.sp ExtraBold primary; subtitle `welcome_suptitle` bodyMedium 18.sp onBackground alpha 0.9; Get-Started `Button` fillMaxWidth, 52.dp height, `RoundedCornerShape(26.dp)`, primary container, label `get_started` 18.sp onPrimary (`:178-218`). Column padding 24.dp + `animateContentSize(tween(600))`.
- **States:** single static state (no loading/error/empty).
- **Interactions:** Get-Started button → `onGetStarted` (nav to Theme). Background uses `rememberInfiniteTransition` 9000ms `LinearEasing` Reverse-repeat `animateFloat` panning a `Brush.linearGradient` (primary→secondary→base) (`:131-154`).
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** pure UI; nav callback only. **NO Lottie** — the in-file comment (`:124-130`) explicitly states lottie-compose is NOT on the `:ui` classpath and the asset at `ui/.../composeResources/files/background.lottie` is unused; a Compose-native gradient substitutes. (See Cluster notes re: task #743.)
- **Feature inventory:** animated gradient background; legibility overlay; title; subtitle; Get-Started CTA button.
- **Citations:** `WelcomeScreen.kt:118-222`; `WelcomeScreenRoute.kt:128-138`; `App.kt:393,400-402`.

### AboutScreen
- **Entry/route:** `Screen.AboutRework` in App.kt:758-760 → `AboutReworkScreenRoute` (`D:/yami manga/yami-kmp/composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/AboutReworkScreenRoute.kt:137-155`); `koinViewModel<AboutViewModel>()` + `koinInject<IntentLauncher>()`; `onBack = safePopBackStack()`. Effects bridged: `OpenPlayStorePage`→`launcher.openPlayStorePage`, `OpenUrl`→`launcher.openUrl`, `NavigateToWhatsNew`→`navController.navigate(Screen.WhatsNewRework)` (`:146-152`). Legacy `Screen.AboutScreen` swapped (§287).
- **Layout & components:** `Scaffold` + `TopAppBar` (title `about`; navigationIcon = "back" `TextButton`, `AboutScreen.kt:223-232`). Body `Column` (bg, padding `spacing.lg`): **logo `Image` (`ic_launcher_foreground`, 120.dp, centered)** (`:253-259`), then `AboutItemsGroup` card of 5 rows with dividers, then `SocialMediaRow`, then `PackageLabel` footer (`:245-302`).
- **Visual:** logo 120.dp centered. Rows card = `surfaceVariant` `RoundedCornerShape(12.dp)`; each `AboutRow` = title `bodyLarge`/onSurface + optional description `bodySmall`/onSurfaceVariant, padded `spacing.lg`×`spacing.md` (`:333-362`); dividers `background` alpha 0.8 (`:364-369`). Package footer = labelSmall centered onSurfaceVariant (`:376-388`). Social buttons = `IconButton` in primaryContainer-tinted circle, adaptive size 36–56.dp, icon 18–28.dp (`:444-542`).
- **States:** loading → centered `CircularProgressIndicator` (early `return@Scaffold`, `:234-243`); loaded → full Column. No error/empty (metadata read is structurally infallible).
- **Interactions:** Version row = display-only (onClick null); "Check for update" + "Rate our app" → `OnOpenPlayStore`; "Privacy policy" → `OnOpenUrl(PRIVACY_POLICY_URL)`; "What's new" → `OnOpenWhatsNew`; social buttons → `OnOpenUrl(...)` for X/Facebook/Instagram/WhatsApp/Website; Discord button is a present-but-no-op tap target (`:488-493`).
- **Dialogs/sheets/snackbars:** none.
- **Forms & validation:** none.
- **Data/behavior:** VM `init{}` loads `GetAppMetadataUseCase` → versionName/packageName (`AboutViewModel.kt:115-126`); intents are pure pass-throughs to effects (`:128-136`). `EffectBridge` collects via `LaunchedEffect(effects)` (`AboutScreen.kt:206-211`). URLs are inline `private const` literals (`:395-414`).
- **Feature inventory:** back TextButton; app-logo image; Version row (display-only); Check-for-update row; Rate-our-app row; Privacy-policy row; What's-new row (in-app nav); 6-button SocialMediaRow (X/Facebook/Instagram/WhatsApp/Discord-noop/Website); package-id footer label. NOT present: Source-code row (intentionally omitted — disabled in legacy).
- **Citations:** `AboutScreen.kt:189-304` (wiring/scaffold/body), `:253-259` (logo), `:263-293` (rows), `:444-542` (social row+button), `:395-414` (URL constants); `SocialMediaIcons.kt:120-556` (6 vendored vectors); `AboutViewModel.kt:109-137`; `AboutReworkScreenRoute.kt:137-155`; `App.kt:758-760`.

---

### Cluster notes

**Architecture (all four features):** strict-MVI. `:presentation` owns `State`/`Intent`/`Effect`/`ViewModel`; `:ui` is stateless projection; `:composeApp` route adapters bridge nav + platform facades. `:ui` deliberately omits `compose.materialIconsExtended` — complaint icons (`ComplaintIcons.kt`) and social icons (`SocialMediaIcons.kt`) are vendored as inline `ImageVector`s. Both complaint screens and both dialogs share the same `surfaceVariant`/`RoundedCornerShape(12.dp)`/16.dp-dialog-corner visual language.

**Shared complaint helpers (`:ui/complaint`):** `ComplaintStatusChip` (`ComplaintStatusChip.kt:114-133`) — per-status colored pill; PINNED/UNKNOWN use `Color.Black`/`Color.White`, NOT_PLANNED `Color.Gray`/`Color.White` (theme-independent, `:98-112`). `ComplaintStatus.displayName()` (`ComplaintStatusDisplay.kt:60-69`) and `ComplaintType.displayName()` (`ComplaintTypeDisplay.kt:63-70`) are `@Composable stringResource` lookups. `formatComplaintTimestamp` (`ComplaintDateFormat.kt:101-105`) = "MMM dd, yyyy HH:mm" English-abbreviated, system TZ; null `createdAt` omits the row entirely (PINNED FAQ entries). All three are reused by the admin screen via cross-package import.

**Discrepancy — AboutScreen KDoc is stale/contradictory:** the class KDoc (`AboutScreen.kt:94-102`) says the app-icon image is "dropped"/"Deferred", but the body actually renders it (`:253-259`, `Res.drawable.ic_launcher_foreground` at 120.dp). The drawable exists at `D:/yami manga/yami-kmp/ui/src/commonMain/composeResources/drawable/ic_launcher_foreground.xml`. This matches task #743 ("About logo header"); the KDoc was never updated. Live behavior = logo IS shown.

**Discrepancy — Welcome Lottie NOT implemented despite task #743 title ("About logo header + Welcome Lottie"):** `WelcomeScreen.kt:124-130` explicitly documents that lottie-compose is not on the `:ui` classpath and the `background.lottie` asset is unused; a Compose-native infinite-gradient animation is the substitute. Only the About-logo half of #743 landed for this cluster; Welcome still has no Lottie. (Not user-visibly broken — the gradient animation works.)

**Discrepancy — AdminComplaintScreen "bulk select" (task #265) is NOT in the rendered UI.** No multi-select checkboxes, no bulk-action bar, and `AdminComplaintIntent` (`AdminComplaintIntent.kt:104-237`) has no bulk-mutation variants — only single-row actions. The KDoc on `AdminComplaintReworkScreenRoute.kt` and `AdminComplaintScreen.kt` reference bulk as "landed", but the present surface has no bulk affordance. Either bulk was reverted or never wired to `:ui` (INFERRED — flag for parity review).

**Stale-but-harmless KDoc citations:** every file in this cluster carries large "§253 audit-trail postscript" blocks documenting retired-legacy citations; these are documentation-only and do not affect behavior. WhatsNewReworkScreenRoute.kt:84-167 contains an unusually large machine-generated postscript.

**Admin back-affordance inconsistency:** user-side ComplaintScreen uses a `navigationIcon` back arrow (`ComplaintScreen.kt:211-218`); AdminComplaintScreen uses an `actions`-slot TextButton labeled "back" (`AdminComplaintScreen.kt:218-222`) and About uses a `navigationIcon` TextButton (`AboutScreen.kt:226-230`). Three different back treatments across the cluster (INFERRED parity nit vs native).

**Admin no-matches lacks the SearchOff icon** that the user-side screen shows (user `ComplaintScreen.kt:323-327` vs admin `AdminComplaintScreen.kt:352-360` plain text only).

**Effect surfaces:** WhatsNewEffect is empty (dormant). About/Complaint/AdminComplaint effects are live. All snackbar collectors use `collectLatest` keyed on `viewModel`.

**Present-but-unwired:** `WhatsNewIntent.OnMarkSeen` (VM-wired to `MarkWhatsNewSeenUseCase` but never dispatched by `:ui` — auto-show gate deferred); WhatsNew error state (wired but dormant — `:data` returns empty on failure); WhatsNew `onEffect` callback (empty interface). About-screen URL constants `TWITTER_URL`/`FACEBOOK_URL`/etc. are referenced by SocialMediaRow; `WEBSITE_URL` used for the globe button; Discord has no URL (no-op).


---

# CLUSTER: webview_nav_shell

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

