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
