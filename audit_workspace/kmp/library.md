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
