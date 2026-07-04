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
