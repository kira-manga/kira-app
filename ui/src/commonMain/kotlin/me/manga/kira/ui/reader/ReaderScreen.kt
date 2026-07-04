package me.manga.kira.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.automirrored.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.ViewDay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Size
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import net.engawapg.lib.zoomable.ExperimentalZoomableApi
import net.engawapg.lib.zoomable.rememberZoomState
import net.engawapg.lib.zoomable.zoomableWithScroll
import org.jetbrains.compose.resources.stringResource
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.reading_mode
import me.manga.kira.ui.generated.resources.reading_mode_default
import me.manga.kira.ui.generated.resources.reading_mode_rtl
import me.manga.kira.ui.generated.resources.reading_mode_ltr
import me.manga.kira.ui.generated.resources.reading_mode_vertical
import me.manga.kira.ui.generated.resources.reading_mode_webtoon
import me.manga.kira.ui.generated.resources.reading_mode_continuous
import me.manga.kira.ui.generated.resources.reader_previous_chapter
import me.manga.kira.ui.generated.resources.reader_next_chapter
import me.manga.kira.ui.generated.resources.reader_toggle_bookmark
import me.manga.kira.ui.generated.resources.np_reader_bookmark_not_in_library
import me.manga.kira.ui.generated.resources.failed_to_load_image
import me.manga.kira.ui.generated.resources.action_open_in_browser
import me.manga.kira.ui.generated.resources.reader_chapter_fallback
import me.manga.kira.ui.generated.resources.reader_error_network
import me.manga.kira.ui.generated.resources.error_network_bad_gateway
import me.manga.kira.ui.generated.resources.error_network_bad_request
import me.manga.kira.ui.generated.resources.error_network_forbidden
import me.manga.kira.ui.generated.resources.error_network_gateway_timeout
import me.manga.kira.ui.generated.resources.error_network_no_connectivity
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.error_network_request_timeout
import me.manga.kira.ui.generated.resources.error_network_server
import me.manga.kira.ui.generated.resources.error_network_service_unavailable
import me.manga.kira.ui.generated.resources.error_network_timeout
import me.manga.kira.ui.generated.resources.error_network_unauthorized
import me.manga.kira.ui.generated.resources.reader_error_storage
import me.manga.kira.ui.generated.resources.reader_error_validation
import me.manga.kira.ui.generated.resources.reader_error_auth
import me.manga.kira.ui.generated.resources.reader_error_platform
import me.manga.kira.ui.generated.resources.reader_error_cancelled
import me.manga.kira.ui.generated.resources.reader_error_unexpected
import me.manga.kira.ui.generated.resources.np_reader_mode_dialog_title
import me.manga.kira.ui.generated.resources.np_reader_mode_apply
import me.manga.kira.ui.generated.resources.np_reader_mode_revert
import me.manga.kira.ui.generated.resources.np_reader_you_are_in
import me.manga.kira.ui.generated.resources.np_reader_going_to
import me.manga.kira.ui.generated.resources.np_reader_no_next_chapter
import me.manga.kira.ui.generated.resources.np_reader_share
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.reader.Page
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.reader.isPaged
import me.manga.kira.presentation.reader.ReaderEffect
import me.manga.kira.presentation.reader.ReaderFeedItem
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.presentation.reader.ReaderViewModel
import me.manga.kira.presentation.reader.buildReaderFeed
import me.manga.kira.ui.reader.internal.applyReaderDecoderHints
import me.manga.kira.ui.reader.internal.readerDecodeMaxWidthPx
import me.manga.kira.ui.theme.LocalSpacing

/**
 * Reader screen — Compose entry point for the Reader MVI slice (Phase 7.x.reader).
 *
 * Mirrors the [me.manga.kira.ui.details.DetailsScreen] precedent (Phase 7.x for Details):
 *  - Stateful wrapper subscribes to the VM's state + effects, dispatches a one-shot
 *    [ReaderIntent.OnEnter] on first composition keyed on the target (manga, chapter) identity.
 *  - Stateless [ReaderScreenContent] does the rendering so previews and tests can feed canned
 *    state without spinning up a VM.
 *  - Scroll position drives [ReaderIntent.OnPageChanged] via a `snapshotFlow` over
 *    `LazyListState.firstVisibleItemIndex`.
 *
 * Effects routed:
 *  - [ReaderEffect.NavigateBack] → [onNavigateBack]
 *  - [ReaderEffect.ShowError] → snackbar host
 *
 * **Scope (minimum-viable shell)**: the slice ships a vertical-scroll page list with loading
 * spinner, error pane (retry), and back navigation. It deliberately excludes:
 *  - **Reading-mode layouts (current branch state).** As of Phase
 *    7.x.reader.modelayout.vertical the screen branches on `state.readingMode` and reaches
 *    legacy-parity for four of the six modes:
 *      - `RIGHT_TO_LEFT` → `HorizontalPager(reverseLayout = true)`
 *      - `LEFT_TO_RIGHT` → `HorizontalPager(reverseLayout = false)`
 *      - `DEFAULT` / `VERTICAL` → `VerticalPager` (paged-vertical, matching legacy
 *        `VerticalReadingMode.kt`)
 *      - `WEBTOON` / `CONTINUOUS_VERTICAL` → vertical `LazyColumn` (free-scroll stitched
 *        panels, matching legacy `WebToonReadingMode.kt` / `ContinuousVerticalReadingMode.kt`)
 *    The two `LazyColumn` modes still differ from legacy in *progress-percentage*
 *    placeholder ergonomics (legacy `WebToonReadingMode.kt` shows a download-%
 *    indicator on the loading placeholder; rework shows a plain CircularProgress
 *    only). Reserved height itself is parity-correct as of §70. Per-source resolution
 *    of `DEFAULT` (the legacy "use the source's preferred mode" behaviour) is
 *    deferred until the rework has a sources-metadata story. See `ARCHITECTURE.md`
 *    §64–§66 for the full deferral list.
 *  - **Top-bar overlay + tap-to-toggle UI chrome.** Legacy hides the top bar after a tap and
 *    re-shows on next tap. The shell keeps the top bar always-visible because the underlying
 *    intent surface has no `OnUiToggle`; adding it is a pure presentation concern that fits a
 *    later UX-polish micro-slice.
 *  - **Page indicator HUD ("3 / 27").** Same rationale — `ReaderState.currentPageIndex` is
 *    already tracked; the HUD is a presentation overlay that fits a later micro-slice.
 *  - **Bookmark / share / settings actions in the top bar.** Each needs its own future intent
 *    + use case (see [ReaderViewModel] KDoc deferral table). The shell has only a back button.
 *  - ~~**Per-page Android RGB_565 + allowHardware(false) decoder hints.**~~ Resolved in Phase
 *    7.x.reader.modelayout.pageprogress Step 7 — lifted into `:ui/.../reader/internal/` as
 *    `applyReaderDecoderHints()` (expect in commonMain; Android actual applies
 *    `allowHardware(false) + bitmapConfig(RGB_565)`; iOS / Desktop actuals are no-ops because
 *    Skiko quality is supplied by `HighQualitySkiaImageDecoder` on the singleton ImageLoader).
 *    The Reader's inline [ImageRequest] now chains `.applyReaderDecoderHints()` so Android
 *    page decode matches legacy parity — no more ARGB_8888 cache-pressure regression.
 *
 * The deferred items are all logged in the Phase 7.x.reader entry of `ARCHITECTURE.md` §59.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster100.staleKdocSweep.cascade,
 * Task #556, 2026-05-28): the file-scope manifest above plus the seven
 * internal helper KDocs at L158 (ReaderScreenContent), L487 (reading-
 * ModeLabel), L505 (ReaderPageIndicatorHud), L535 (ReaderPageScrubber),
 * L594 (ReaderPageLayout dispatch), L804 (ReaderHorizontalPager), L880
 * (ReaderVerticalPager) are classified as follows after recursive symbol
 * verification across the KMP graph (forty-first sibling of the cluster-
 * 57-99 sweep — solo `:ui/reader/` cluster closing the wave-7 exhaustive
 * `:ui/commonMain` sweep):
 *  (a) "Stateful wrapper subscribes to the VM's state plus effects,
 *  dispatches a one-shot [ReaderIntent.OnEnter] on first composition
 *  keyed on the target (manga, chapter) identity" — LIVE-NOT-STALE.
 *  L134-156 realization: `viewModel.state.collectAsState()` plus
 *  ReaderScreenContent delegation; L183-185 `LaunchedEffect(manga.api,
 *  manga.language, manga.title, chapter.url) { onIntent(ReaderIntent.
 *  OnEnter(manga, chapter)) }` keyed exactly on the documented
 *  identity tuple.
 *  (b) "Stateless [ReaderScreenContent] does the rendering so previews
 *  and tests can feed canned state without spinning up a VM" — LIVE-
 *  NOT-STALE. L170-180 declares the stateless host signature exactly
 *  as documented. The L158-167 SRP KDoc ("wire to VM vs render state
 *  are separate responsibilities") stays the durable contract.
 *  (c) "Scroll position drives [ReaderIntent.OnPageChanged] via a
 *  `snapshotFlow` over `LazyListState.firstVisibleItemIndex`" —
 *  LIVE-NOT-STALE. `ReaderVerticalList` at L720-728 LIVE implements
 *  the `rememberLazyListState` plus `snapshotFlow { listState.first-
 *  VisibleItemIndex }.distinctUntilChanged().collect(onPageChanged)`
 *  exactly as forecast. The pager siblings `ReaderHorizontalPager`
 *  (L834-842) and `ReaderVerticalPager` (L903-911) follow the same
 *  `snapshotFlow { pagerState.currentPage }` pattern on PagerState.
 *  (d) "Effects routed: [ReaderEffect.NavigateBack] rename-to [on-
 *  NavigateBack], [ReaderEffect.ShowError] rename-to snackbar host"
 *  — LIVE-NOT-STALE. ReaderEffect symbol-set at `presentation/src/
 *  commonMain/kotlin/me/manga/yamiapk/presentation/reader/Reader-
 *  Effect.kt` LIVE hosts both variants plus the §229-era addition
 *  `OpenChapterInWebView(url, api)` at L45. NavigateBack plus Show-
 *  Error route exactly as documented.
 *  (e) "Reading-mode layouts (current branch state) ... reaches
 *  legacy-parity for four of the six modes" — LIVE-NOT-STALE (with
 *  reading-mode coverage now 6/6 via the documented pair-collapse).
 *  The `ReaderPageLayout` dispatch at L639-701 exhaustively branches
 *  every [ReadingMode] entry: RIGHT_TO_LEFT rename-to ReaderHorizontal-
 *  Pager(reverse=true); LEFT_TO_RIGHT rename-to ReaderHorizontalPager
 *  (reverse=false); DEFAULT/VERTICAL rename-to ReaderVerticalPager;
 *  WEBTOON/CONTINUOUS_VERTICAL rename-to ReaderVerticalList. The
 *  rule-of-three branch §64.2 design intent holds. The placeholder-
 *  ergonomics caveat ("download-percent indicator on webtoon mode")
 *  is RESOLVED by Phase 7.x.reader.modelayout.pageprogress (Task #236)
 *  plus its ktor3 follow-up (Task #237) — the `ReaderPageItem` loading
 *  slot at L1019-1043 LIVE dispatches determinate/indeterminate
 *  `CircularProgressIndicator` based on `(progress as? PageDownload-
 *  Progress.InProgress)?.fraction`. Per-source DEFAULT resolution
 *  (§66 deferral) remains FORECAST-NOT-YET-FULFILLED.
 *  (f) "Top-bar overlay plus tap-to-toggle UI chrome ... the shell
 *  keeps the top bar always-visible because the underlying intent
 *  surface has no `OnUiToggle`; adding it is a pure presentation
 *  concern that fits a later UX-polish micro-slice" — STALE-
 *  SUPERSEDED. `ReaderIntent.OnUiToggle` LIVE at `presentation/.../
 *  ReaderIntent.kt:66` (data object); `ReaderState.isUiVisible`
 *  LIVE at `ReaderState.kt:98` (default `true`); `ReaderViewModel`
 *  L187 reducer arm LIVE `OnUiToggle rename-to updateState { it.copy
 *  (isUiVisible = !it.isUiVisible) }`. THIS file LIVE imports L3
 *  `AnimatedVisibility` plus chrome wraps at L238 (top bar) plus
 *  L336 (bottom HUD + scrubber Column) plus L443 (prev/next chevrons)
 *  in `AnimatedVisibility(state.isUiVisible)`, and the L282-284 outer
 *  Box `detectTapGestures(onTap = { onIntent(ReaderIntent.OnUiToggle)
 *  })` drives the toggle. Phase 7.x.reader.chrome (Task #218) LIVE-
 *  FULFILLED this deferral; the original prose is preserved as a
 *  historical record of the slice's arrival.
 *  (g) "Page indicator HUD ('3 / 27'). Same rationale — `ReaderState.
 *  currentPageIndex` is already tracked; the HUD is a presentation
 *  overlay that fits a later micro-slice" — STALE-SUPERSEDED.
 *  `ReaderPageIndicatorHud` LIVE at L516-533 as a rounded `Surface`
 *  pill rendering `pageIndicator(state)` text; consumed at L347
 *  inside the bottom-chrome `AnimatedVisibility`. Phase 7.x.reader.
 *  chrome (Task #218) closed this deferral too; Phase 7.x.reader.
 *  pagecounthud (Task #234) added the parallel top-bar X/Y label so
 *  the HUD reading is reinforced by the appbar subtitle.
 *  (h) "Bookmark / share / settings actions in the top bar. Each
 *  needs its own future intent plus use case (see [ReaderViewModel]
 *  KDoc deferral table). The shell has only a back button" — MIXED.
 *  (h.1) Bookmark IconButton — FORECAST-NOT-YET-FULFILLED. Task #217
 *  Phase 6.4.x.bookmark (ObserveChapterBookmarkUseCase plus Toggle-
 *  ChapterBookmarkUseCase) is still PENDING; the `ReaderTopBar`
 *  surface invoked at L243-258 currently exposes onBack/onPrevChapter
 *  /onNextChapter/onSelectReadingMode only — no bookmark callback.
 *  (h.2) Share IconButton — FORECAST-NOT-YET-FULFILLED. No share
 *  intent has landed on ReaderIntent; THIS file's top bar hosts no
 *  share IconButton. (h.3) Settings IconButton — REROUTED-OUT-OF-
 *  SURFACE. The legacy in-reader settings sheet was rerouted to the
 *  rework Settings hub via Task #256 (Phase 7.x.settings.readingmode
 *  added the reading-mode picker dialog there), and the in-reader
 *  ReadingMode dropdown menu at L255-257 already provides the most
 *  load-bearing in-session affordance. Original deferral prose
 *  preserved as historical record of the surface-allocation choice.
 *  (i) "Per-page Android RGB_565 plus allowHardware(false) decoder
 *  hints ... Resolved in Phase 7.x.reader.modelayout.pageprogress
 *  Step 7 — lifted into `:ui/.../reader/internal/` as `applyReader-
 *  DecoderHints()`" — FULFILLED-PREDICTION (self-acknowledged at
 *  L124-130). THIS file L80 LIVE imports `me.manga.kira.ui.reader.
 *  internal.applyReaderDecoderHints` and L986 LIVE chains
 *  `.applyReaderDecoderHints()` on the per-page `ImageRequest.
 *  Builder`. Cross-reference: [me.manga.kira.ui.reader.internal.
 *  applyReaderDecoderHints] KDoc received its own cluster99 post-
 *  script at Task #555. Pair holds.
 *  (j) Internal helper KDocs jointly held LIVE-NOT-STALE — six
 *  realizations verified: [readingModeLabel] at L487-503 (six-arm
 *  exhaustive `when` over ReadingMode; i18n forecast at L492-494
 *  remains FORECAST-NOT-YET-FULFILLED, durable as documentation of
 *  the planned string-resources slice); [ReaderPageIndicatorHud] at
 *  L515-533 (M3 HUD-pill pattern with `surfaceVariant` tonal LIVE
 *  at L520); [ReaderPageScrubber] at L570-592 (Phase 7.x.reader.
 *  pagescrubber Task #235 LIVE — Slider with same `OnPageChanged`
 *  intent reuse plus layout-LaunchedEffect close-the-loop at L736-
 *  741 / L846-851 / L914-919); [ReaderPageLayout] at L638-702
 *  (rule-of-three branch plus state-pushed-down plus scrollpos §69
 *  plus pagescrubber jump-loop LIVE in dispatch and each layout);
 *  [ReaderHorizontalPager] at L820-878 (`.zoomable` before
 *  `.fillMaxSize` modifier ordering verified L859-861; reverse-
 *  layout RTL parity verified L854); [ReaderVerticalPager] at
 *  L892-942 (same pinch-zoom posture L924-926; no reverseLayout
 *  parameter as documented L888-890). Six helper KDocs verified
 *  intact against their realizations.
 *  Five LIVE-NOT-STALE classifications plus one FULFILLED-PREDICTION
 *  plus two STALE-SUPERSEDED plus one MIXED (three sub-classifications)
 *  plus one joint helper-KDoc LIVE-NOT-STALE block STAND on their own
 *  merits as a faithful Phase 7.x.reader file-scope manifest. Original
 *  Phase 7.x.reader-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    manga: Manga,
    chapter: Chapter,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSharePage: (ImageBitmap) -> Unit,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ReaderScreenContent(
        state = state,
        effects = viewModel.effects,
        manga = manga,
        chapter = chapter,
        onIntent = viewModel::submit,
        onNavigateBack = onNavigateBack,
        onOpenInWebView = onOpenInWebView,
        onSharePage = onSharePage,
        onSolveCloudflareChallenge = onSolveCloudflareChallenge,
        onReportProgress = onReportProgress,
        modifier = modifier,
    )
}

/**
 * Stateless host — split from [ReaderScreen] so previews and tests can feed canned state
 * without spinning up a real ViewModel. SRP: "wire to VM" vs "render state" are separate
 * responsibilities.
 *
 * The [manga] / [chapter] parameters are the *target* identity from the navigation host, used
 * to submit [ReaderIntent.OnEnter]. The currently-rendered identity lives in [ReaderState]
 * and is updated by the reducer after OnEnter; the screen never reads identity from
 * navigation arguments directly during render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderScreenContent(
    state: ReaderState,
    effects: Flow<ReaderEffect>,
    manga: Manga,
    chapter: Chapter,
    onIntent: (ReaderIntent) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    onSharePage: (ImageBitmap) -> Unit,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Snackbars run in this scope so showing one never suspends the effect collector below — a
    // direct showSnackbar() call there would block later effects (e.g. NavigateBack) for the whole
    // snackbar duration, which is why the user couldn't leave a screen while a snackbar was up.
    val scope = rememberCoroutineScope()
    // Reader parity item #5 (share current page). Capture target for the share action: a
    // GraphicsLayer recording of the page-layout area. The Box that hosts ReaderPageLayout draws
    // its content into this layer via `drawWithContent`; on a ShareCurrentPage effect we read the
    // layer back as an ImageBitmap and hand it to the route adapter for PNG-encode + platform
    // share. Mirrors the legacy reader's whole-frame PixelCopy capture, scoped to the page area so
    // chrome (top bar / HUD / scrubber) is excluded from the shared image — same intent as legacy
    // `hideControls()` before capture.
    val pageGraphicsLayer = rememberGraphicsLayer()
    // ON-DEMAND capture flag for the share action (WEBTOON-SCROLL FIX, 2026-06). Default false ⇒ the
    // page area draws its content DIRECTLY during normal reading/scrolling. The `GraphicsLayer.record`
    // is engaged for a single frame only while this is true (set by the ShareCurrentPage effect). The
    // previous code recorded EVERY frame: ~free on Android (RenderNode) but a full Skia-layer re-record
    // per frame on iOS, which stalls the render thread on tall webtoon panels and makes the scroll feel
    // sticky/laggy — the iOS-only "drag barely moves while a fling coasts" report. Native has no capture.
    var captureRequested by remember { mutableStateOf(false) }

    // Reading-mode picker dialog open/close flag. Hoisted here (rather than local to the top bar)
    // so the bottom action bar's Settings icon can trigger it — matching native, where the
    // reading-mode dialog opens from the BOTTOM action bar's settings icon
    // (`ControlOverlay.kt:174-180` → `ReaderScreen.kt:589-590 showReadingModeDialog = true`),
    // not the top bar. Pure UI chrome with no observable contract, so it stays out of [ReaderState]
    // (a config change while open just closes it — same as native's local dialog flag).
    var showReadingModeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(manga.api, manga.language, manga.title, chapter.url) {
        onIntent(ReaderIntent.OnEnter(manga, chapter))
    }

    // Reading-session timer bracket (Phase 6.4.x.statistics; #7 lifecycle-scoped).
    //
    // #7: bracket on the host's LIFECYCLE, not bare composition. The old DisposableEffect(Unit)
    // only ended the session on composition tear-down, so time spent while the app was BACKGROUNDED
    // (Android stop-not-destroy, iOS background, Desktop minimize — composition stays alive) was
    // billed as reading time. Observe the LocalLifecycleOwner instead: ON_RESUME/ON_START start a
    // session, ON_STOP/ON_PAUSE end it, so only foreground spans count.
    //
    // We key on the lifecycleOwner (not manga/chapter): an intra-manga Next/Prev must NOT re-bracket
    // — the session is one continuous foreground span regardless of which chapter is on screen
    // (same posture as legacy ReaderActivity.onResume/onPause). The final onDispose end also bounds
    // the total to the screen's lifetime (covers screen-leave / config change, the case the old
    // onDispose handled). begin/end are idempotent (begin overwrites start; end no-ops when start==0),
    // so repeated or unpaired events can never corrupt the read_minutes counter.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START ->
                    onIntent(ReaderIntent.OnScreenResumed)
                Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_PAUSE ->
                    onIntent(ReaderIntent.OnScreenPaused)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            onIntent(ReaderIntent.OnScreenPaused)
        }
    }

    // GAP-RDR-10: auto-hide the chrome after ~3 s of inactivity (legacy
    // `LaunchedEffect(showControls){ delay(3000); showControls=false }`). Keyed on
    // `isUiVisible` (re-arms each time chrome is shown) AND `currentPageIndex` (a page
    // turn while chrome is up resets the countdown, matching the legacy "any interaction
    // keeps it alive" feel). Only schedules the hide when chrome is currently visible;
    // when already hidden the effect is a no-op so it never fights a manual show. The hide
    // dispatches the same `OnUiToggle` the tap path uses — the reducer flips
    // `isUiVisible` to false, which the wrapping AnimatedVisibility animates out.
    LaunchedEffect(state.isUiVisible, state.currentPageIndex) {
        if (state.isUiVisible) {
            delay(3000)
            if (state.isUiVisible) onIntent(ReaderIntent.OnUiToggle)
        }
    }

    // `stringResource` cannot run inside the effects collector below, so resolve the
    // AppError → user-message mapping into a captured lambda in composable scope first
    // (hoisting pattern). The collector then calls the pre-resolved `errorMessage`.
    val errorMessage = rememberAppErrorMessageResolver()
    // #15 — same hoisting: resolve the "add to Library first" instruction here so the collector
    // (a non-composable coroutine) can show it without calling stringResource.
    val notInLibraryMsg = stringResource(Res.string.np_reader_bookmark_not_in_library)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                ReaderEffect.NavigateBack -> onNavigateBack()
                is ReaderEffect.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(errorMessage(effect.error)) }
                // #15 — bookmark tapped on a not-in-library chapter (toggle no-op): nudge the
                // user to add the manga to their Library first instead of silently doing nothing.
                ReaderEffect.ShowNotInLibrary ->
                    scope.launch { snackbarHostState.showSnackbar(notInLibraryMsg) }
                is ReaderEffect.OpenChapterInWebView ->
                    onOpenInWebView(effect.url, effect.api)
                // Reader parity item #5: capture the recorded page area and forward the bitmap
                // to the route adapter for PNG-encode + platform share via ScreenshotProvider.
                // `toImageBitmap()` is suspend (it reads the recorded layer back off the render
                // thread); we're already inside the effects collector coroutine so we can call it
                // directly. Guarded on a non-zero recorded size — the layer has nothing to read
                // back until the page Box has drawn at least one frame into it.
                ReaderEffect.ShareCurrentPage -> {
                    // Engage the recording for ONE frame, let two frames pass so the page Box draws
                    // into the layer with the flag set (frame callbacks fire before that frame's draw,
                    // so the first await schedules and the second lands after the recorded draw), read
                    // the bitmap back, then clear the flag so scrolling returns to direct drawing.
                    captureRequested = true
                    withFrameNanos { }
                    withFrameNanos { }
                    if (pageGraphicsLayer.size.width > 0 && pageGraphicsLayer.size.height > 0) {
                        onSharePage(pageGraphicsLayer.toImageBitmap())
                    }
                    captureRequested = false
                }
                // Reader parity item #6: AUTO 403→WebView recovery. Forward to the dedicated
                // route-adapter callback that navigates to the WebView AND arms a one-shot retry
                // so the chapter re-fetches once the challenge clears (cookies minted).
                is ReaderEffect.SolveCloudflareChallenge ->
                    onSolveCloudflareChallenge(effect.url, effect.api)
            }
        }
    }

    // Per-page Open-in-WebView callback — closed over the screen's chapter URL +
    // source api. Threaded down the layout chain to `ReaderPageItem`'s error slot.
    // Each per-page tap dispatches `OnOpenInWebView(url, api)` which the VM emits as
    // `OpenChapterInWebView`, consumed by the effect collector above and routed to
    // the navhost via `onOpenInWebView`. The url + api don't vary per page (the whole
    // chapter shares one source URL), so we pre-bind the closure here and the inner
    // composables only see a parameterless `() -> Unit`.
    val openInWebView: () -> Unit = {
        onIntent(ReaderIntent.OnOpenInWebView(chapter.url, manga.api))
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Full-screen immersive reader (2026-06): zero the Scaffold insets so the page content fills
        // edge-to-edge (under the status bar + home indicator) with NO gaps above/below the image.
        // The chrome overlays re-apply their own insets when shown — the top bar via
        // `WindowInsets.statusBars`, the bottom chrome stack via `navigationBarsPadding()`. The app
        // root also exempts the reader route from its bottom inset (see App.kt `fullBleed`).
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        // BoxWithConstraints to harvest viewport `maxHeight` as a `Dp` (the
        // "screen-height-Dp" legacy reading-modes call `screenHeightDb`). Threaded into
        // `ReaderPageLayout` so each page's loading / error placeholder can reserve full
        // viewport height via `defaultMinSize(minHeight = screenHeightDb)` — without this
        // reservation, a streaming LazyColumn item collapses to 0 during the bitmap-in-
        // flight window and the scroll position visibly jolts as the decoded image lands.
        // Matches legacy parity for the LazyColumn-backed modes
        // (`WebToonReadingMode.kt` lines 200–211, `ContinuousVerticalReadingMode.kt`
        // lines 200–211) and is a no-op for the pagers (their pages already fill the
        // viewport via `.fillMaxSize()`). Phase 7.x.reader.modelayout.placeholder.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Outer chrome-toggle tap detector (restored 2026-06-30, reverting the af56e1c2
                // "WEBTOON-SCROLL FIX" that stripped it). `detectTapGestures` only fires on a clean tap
                // (down → up within the tap timeout, no drag past touchSlop); vertical drags are consumed
                // by the LazyColumn's own scroll, so the two coexist. Attached to the outer Box (not the
                // list) so taps in the loading / error states also toggle chrome — matches the native app.
                // The iOS-only scroll problem this was removed for no longer applies: iOS now runs the
                // native Swift reader by default, so this Compose path is Android/Desktop (+ iOS fallback).
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onIntent(ReaderIntent.OnUiToggle) })
                },
        ) {
            val screenHeightDb: Dp = maxHeight
            // Capture nullable state into a local — public-API smart casts across modules
            // are not allowed by the Kotlin compiler. (Same caveat as DetailsScreen.)
            val error = state.error
            when {
                // First-chapter loading indicator (reader-controls finding #11 / reader-core
                // finding #7): native `ReaderScreen.kt:623-631` wraps the spinner in a
                // 16.dp-padded full-size centered Box and tints it `colorScheme.primary`. Match
                // both the primary tint and the 16.dp padding here. (Native gates on
                // `currentChapterIndex == startIndex` — i.e. only the very first chapter — while
                // the rework gates on `isInitialLoading = isLoading && pages.isEmpty()`; that
                // gating difference is the multi-chapter-feed cross-cutting deferral, not a
                // cosmetic gap, so only the color + padding are aligned here.)
                state.isInitialLoading -> CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
                error != null && !state.hasPages -> ReaderErrorPane(
                    error = error,
                    onRetry = { onIntent(ReaderIntent.OnRetry) },
                    modifier = Modifier.align(Alignment.Center),
                )
                // Page area wrapped in a Box that records its drawn content into
                // [pageGraphicsLayer] (Reader parity item #5). `drawWithContent` first draws the
                // content into the layer, then draws the layer to the screen — so the page list is
                // rendered exactly as before, and the layer holds a fresh snapshot of just the page
                // area (chrome is drawn outside this Box, so it is excluded from the captured PNG,
                // matching legacy `hideControls()`-before-capture intent). `fillMaxSize` so the
                // recorded surface spans the whole viewport.
                state.hasPages -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Record into [pageGraphicsLayer] ONLY for the single frame a share is in
                        // flight ([captureRequested]); otherwise draw the page content directly. An
                        // always-on per-frame record stalls iOS scroll (Skia re-record) while being
                        // free on Android (RenderNode) — see [captureRequested] / ShareCurrentPage.
                        .drawWithContent {
                            if (captureRequested) {
                                pageGraphicsLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(pageGraphicsLayer)
                            } else {
                                drawContent()
                            }
                        },
                ) {
                    ReaderPageLayout(
                        pages = state.pages,
                        readingMode = state.readingMode,
                    // Live page index threaded into each layout. Two roles:
                    //   1. Initial value for the layout's `remember*State` so a mode-toggle
                    //      mid-chapter opens the new layout on the page the user was on
                    //      (Phase 7.x.reader.modelayout.scrollpos). `remember*State` is
                    //      keyed on the composable identity, so within the same layout this
                    //      value drives only the FIRST composition's scroll position.
                    //   2. Drive scroll-on-change so the bottom scrubber's drag can move
                    //      the layout to the target page (Phase 7.x.reader.pagescrubber).
                    //      Each layout hosts a `LaunchedEffect(currentPageIndex)` that
                    //      calls `scrollToItem` / `scrollToPage` when the param changes and
                    //      the layout's own scroll/pager state lags behind. The if-guard
                    //      makes scroll-driven updates (snapshotFlow → onPageChanged → VM
                    //      → recompose) a no-op (positions already match).
                        currentPageIndex = state.currentPageIndex,
                        screenHeightDb = screenHeightDb,
                        onPageChanged = { index -> onIntent(ReaderIntent.OnPageChanged(index)) },
                        // Reach-end behaviour (#5):
                        //  - Continuous-scroll modes (WEBTOON / CONTINUOUS_VERTICAL) APPEND the next
                        //    chapter below the current one in the same scroll list — the current
                        //    chapter is NOT removed, so the user can scroll up/down freely between
                        //    them (native parity). The VM's onAppendNextChapter is idempotent + guarded.
                        //  - Paged modes (DEFAULT / L-R / R-L / VERTICAL) keep the existing
                        //    clear-and-jump advance (OnNextChapter), per the owner's choice to leave
                        //    paged modes' per-chapter behaviour unchanged.
                        onReachedEnd = {
                            onIntent(
                                if (state.readingMode.isPaged) ReaderIntent.OnNextChapter
                                else ReaderIntent.OnAppendNextChapter,
                            )
                        },
                        // Inline boundary-card tap (continuous modes): append the next chapter below.
                        onAppendNext = { onIntent(ReaderIntent.OnAppendNextChapter) },
                        pageChapters = state.pageChapters,
                        chapters = state.chapters,
                        anchorChapter = state.chapter,
                        // Paged modes (#14): the chapter currently in view + the next chapter (null
                        // on the terminal chapter) so the pagers can append a dummy "Next Chapter"
                        // page after the last image — the last image is then dwellable and the
                        // advance fires only when the user swipes onto that extra page.
                        activeChapter = state.activeChapter ?: chapter,
                        nextChapter = if (state.canGoNext) {
                            state.chapters.getOrNull(state.currentChapterIndex + 1)
                        } else {
                            null
                        },
                        onOpenInWebView = openInWebView,
                        // gestures-zoom finding #2: chrome-toggle fed into the zoomable gesture
                        // layer (in addition to the outer Box detector), matching native which
                        // passes `onTap` into every reading-mode's `zoomableWithScroll`.
                        onToggleUi = { onIntent(ReaderIntent.OnUiToggle) },
                        pageProgress = state.pageProgress,
                        onReportProgress = onReportProgress,
                    )
                }
                // #4 safety net: a loaded-but-empty, no-error state must NEVER render nothing (the
                // silent black screen). The VM now classifies a zero-page result as an error, so this
                // branch is defensive — if any path lands here it shows the retry pane, not a blank Box.
                else -> ReaderErrorPane(
                    error = error ?: AppError.Unexpected("This chapter returned no pages."),
                    onRetry = { onIntent(ReaderIntent.OnRetry) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Next-chapter transition overlay (GAP-RDR-05). Mirrors the legacy `NextChapterCard`
            // / `errorCard`: once the user reaches the LAST page of the current chapter, a
            // full-screen surface fades in over the page area showing "You are in <current>" →
            // "Going to <next>", tappable to advance (dispatches OnNextChapter). When there is no
            // next chapter, it shows a terminal "last chapter" message instead (legacy errorCard
            // "No Next Chapter"). The card supplements the existing top-bar Next button + silent
            // auto-advance with the visible transition affordance the audit flagged as missing.
            //
            // Gated on: pages loaded AND positioned on the last page. Suppressed for a 1-page
            // chapter only when there's also no next chapter (nothing to transition to and the
            // user just opened it). The overlay sits ABOVE the page Box but BELOW the chrome
            // stack so the top bar / HUD stay tappable over it.
            // #5: the floating last-page overlay is for PAGED modes only (pagers can't host an inline
            // list item). Continuous-scroll modes (WEBTOON / CONTINUOUS_VERTICAL) render the
            // next-chapter affordance INLINE as a boundary card after the last image in
            // [ReaderVerticalList] — so gating here on `isPaged` avoids showing both.
            // #14: when a next chapter EXISTS the pagers now append a full dummy "Next Chapter" page
            // after the last image (see [ReaderHorizontalPager] / [ReaderVerticalPager]); the floating
            // banner would duplicate that affordance, so it is shown only for the TERMINAL chapter
            // (`!canGoNext`) to surface the "last chapter" message on the final image.
            val onLastPage = state.hasPages && state.currentPageIndex >= state.pages.lastIndex
            if (onLastPage && state.readingMode.isPaged && !state.canGoNext) {
                NextChapterOverlay(
                    currentChapter = state.activeChapter ?: chapter,
                    nextChapter = null,
                    isLoadingNext = state.isLoading,
                    // Terminal chapter: nothing to advance to (the card is a non-clickable message).
                    onGoToNext = { onIntent(ReaderIntent.OnNextChapter) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // Bottom-center chrome stack — HUD pill, jump-to-page seekbar, then the action bar.
            // All fade together via the shared `state.isUiVisible` gate (a single wrapping
            // AnimatedVisibility) so chrome appears / disappears as one unit. Native order
            // (`ControlOverlay.kt:74-100`): SeekBarContainer then a Spacer then BottomActionBar.
            // The rework keeps its HUD pill (a pre-existing rework addition, not in native) above
            // the seekbar, then the seekbar (`ReaderPageScrubber`), then the action bar
            // (`ReaderBottomActionBar`). HUD pill renders only when there are pages to count;
            // scrubber renders only when there are ≥2 pages to scrub between (a 1-page chapter
            // has nothing to drag); the action bar renders whenever there are pages (settings /
            // bookmark are always relevant, share once a page is on screen) — matching native,
            // which shows the action bar with the controls.
            val hudText = pageIndicator(state)
            // #5: gate on the ACTIVE chapter's page count (the slider is chapter-scoped) — a 1-page
            // active chapter has nothing to scrub even if the appended feed has more pages.
            val showScrubber = state.hasPages && state.activeChapterPageCount > 1
            val showActionBar = state.hasPages
            if (hudText != null || showScrubber || showActionBar) {
                AnimatedVisibility(
                    visible = state.isUiVisible,
                    // Matching chrome timing for the bottom stack (reader-controls finding #10):
                    // tween(250) enter / tween(200) exit, native `ControlOverlay.kt:71-72`.
                    enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it },
                    exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        // Full-bleed reader: the action bar (below) carries the navigation-bar inset
                        // itself — its translucent background fills down to the screen edge — so the
                        // stack must NOT add its own bottom inset, which left an empty gap under the bar.
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (hudText != null) {
                            ReaderPageIndicatorHud(text = hudText)
                        }
                        if (showScrubber) {
                            ReaderPageScrubber(
                                positionInChapter = (state.activeChapterPageNumber - 1).coerceAtLeast(0),
                                chapterPageCount = state.activeChapterPageCount,
                                canGoPrevChapter = state.canGoPrev,
                                canGoNextChapter = state.canGoNext,
                                // Map the slider's within-chapter position back to the absolute feed
                                // page index before dispatching, so the VM's page-index space is
                                // unchanged (boundary cards are a pure UI artifact).
                                onSeekToChapterPage = { rel ->
                                    state.activeChapterPageIndices.getOrNull(rel)?.let {
                                        onIntent(ReaderIntent.OnPageChanged(it))
                                    }
                                },
                                onPrevChapter = { onIntent(ReaderIntent.OnPrevChapter) },
                                onNextChapter = { onIntent(ReaderIntent.OnNextChapter) },
                            )
                        }
                        if (showActionBar) {
                            // Bottom action bar (reader-core finding #5 / reader-controls finding
                            // #5): native `ControlOverlay.BottomActionBar` — three weight(1f)
                            // IconButtons (settings → reading-mode dialog, bookmark, share) on a
                            // 0.8-alpha background. Replaces folding these into the top bar.
                            ReaderBottomActionBar(
                                isBookmarked = state.isBookmarked,
                                canShare = state.hasPages,
                                onSettings = { showReadingModeDialog = true },
                                onToggleBookmark = { onIntent(ReaderIntent.OnToggleBookmark) },
                                onShare = { onIntent(ReaderIntent.OnShareCurrentPage) },
                            )
                        }
                    }
                }
            }

            // Top bar rendered as a top-aligned OVERLAY inside the page Box (not the Scaffold
            // topBar slot), mirroring the bottom chrome stack above. Native overlays `ControlOverlay`
            // inside the same Box as the reading layouts, so the page draws edge-to-edge UNDER the
            // translucent (0.8-alpha) bar and toggling / auto-hiding chrome never resizes the
            // viewport. Hosting it in the Scaffold slot instead padded the page by the bar height,
            // so every toggle / 3-second auto-hide animated the whole page area and the translucency
            // only revealed the scaffold background, not the page.
            AnimatedVisibility(
                visible = state.isUiVisible,
                // Chrome slide+fade timing (reader-controls finding #10): explicit
                // tween(250) enter / tween(200) exit, matching native
                // `ControlOverlay.kt:57-58` (`fadeIn(tween(250)) + slideInVertically(tween(250))`
                // / `fadeOut(tween(200)) + slideOutVertically(tween(200))`) instead of the
                // Compose-default AnimatedVisibility spring/spec.
                enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { -it },
                exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it },
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                ReaderTopBar(
                    // Two-line title (controls parity): primary line is the MANGA NAME, subtitle
                    // is the chapter number — matching native `ControlOverlay.kt:60-65 / 133-146`
                    // (`title = currentChapter.mangaName`, `number = currentChapter.chapterNumber`).
                    // The manga name comes from the rework's `Manga.title` (== legacy `mangaName`);
                    // the chapter number from `Chapter.number`, falling back to the chapter
                    // name / generic label only when the source ships no number (defensive — native
                    // renders the raw number, which is non-empty in practice).
                    //
                    // reader-core finding #4 / reader-controls findings #1-#3: the top bar now holds
                    // ONLY back + the 2-line title, matching native `ControlOverlay.TopAppBar`
                    // (back + title + a no-op dots menu). The chapter Prev/Next chevrons + chapter/
                    // page count labels moved to the bottom seekbar (native `SeekBarContainer`,
                    // reader-controls #3); bookmark / share / reading-mode-settings moved to the
                    // bottom action bar (native `ControlOverlay.BottomActionBar`, reader-core #5).
                    title = (state.manga ?: manga).title,
                    // #5: track the chapter actually in view (the appended segment, if the user
                    // scrolled past a boundary), not the anchor chapter.
                    chapterSubtitle = chapterNumberSubtitle(state.activeChapter ?: chapter),
                    // chapterNumberSubtitle is @Composable (its blank-number fallback resolves a
                    // stringResource); it is invoked here inside the overlay composable lambda.
                    onBack = { onIntent(ReaderIntent.OnBackClick) },
                )
            }
        }
    }

    // Reading-mode picker dialog (reader-core finding #1) — opened from the bottom action bar's
    // Settings icon, matching native. Staged FilterChip selection with Apply / Revert (see
    // [ReadingModeDialog]). Rendered at the [ReaderScreenContent] level (not inside the chrome
    // AnimatedVisibility) so it stays up even if the chrome auto-hides while the dialog is open.
    if (showReadingModeDialog) {
        ReadingModeDialog(
            currentMode = state.readingMode,
            onApply = { mode ->
                showReadingModeDialog = false
                onIntent(ReaderIntent.OnReadingModeChanged(mode))
            },
            onDismiss = { showReadingModeDialog = false },
        )
    }
}

/**
 * Reader top bar — back button + two-line title only, matching native
 * `ControlOverlay.TopAppBar` (`ControlOverlay.kt:106-156`: back IconButton + a Column with the
 * manga name over the chapter number + a no-op dots menu).
 *
 * reader-controls finding #1: native's bar is a translucent 56.dp Row floating over the page
 * (background at 0.8 alpha). The rework uses an M3 [TopAppBar] but applies a translucent
 * `containerColor` (`background @0.8f`) via `TopAppBarDefaults.topAppBarColors` so the page shows faintly through
 * the bar — closing the "solid vs translucent" gap without abandoning the M3 inset/layout
 * handling. The native no-op dots menu is omitted (it does nothing); the reading-mode picker it
 * never opened in native is reached from the bottom action bar's Settings icon instead.
 *
 * reader-controls findings #2/#3 + reader-core findings #4/#5: chapter Prev/Next + chapter/page
 * count labels moved to the bottom seekbar ([ReaderPageScrubber]); bookmark / share / reading-
 * mode-settings moved to the bottom action bar ([ReaderBottomActionBar]) — so the top bar holds
 * only back + title, exactly as native.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    chapterSubtitle: String,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            // Two-line title Column — manga name (titleLarge, onSurface, 1 line, ellipsis) over
            // the chapter number (bodySmall, onSurface @ 0.75 alpha, 1 line) with a 2.dp gap.
            // Mirrors native `ControlOverlay.kt:127-147` (titleLarge mangaName + 2.dp Spacer +
            // bodySmall chapterNumber @ 0.75 alpha onBackground). PRESERVED existing fix.
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = chapterSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        },
        navigationIcon = {
            KiraIconButton(
                icon = KiraIcons.Back,
                contentDescription = stringResource(Res.string.back),
                onClick = onBack,
            )
        },
        // Translucent container (reader-controls #1): theme background at 0.8 alpha so the page
        // shows faintly through the bar, matching native's `background.copy(alpha = 0.8f)`.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
        ),
        // Full-bleed reader (2026-06): the page now draws edge-to-edge under the status bar (the
        // Scaffold no longer insets the content), so the top bar re-applies the status-bar inset
        // itself to keep the title clear of the status bar / notch.
        windowInsets = WindowInsets.statusBars,
    )
}

/**
 * Bottom action bar — faithful port of native `ControlOverlay.BottomActionBar`
 * (`ControlOverlay.kt:158-196`): a 56.dp-tall Row at 0.8-alpha background with three weight(1f)
 * IconButtons spread evenly:
 *  - **Settings** → opens the reading-mode dialog (native `onSettings = { showReadingModeDialog
 *    = true }`). Native uses the `ic_reader_setting` gear; the rework maps it to [KiraIcons.Tune]
 *    (sliders), the closest semantic "reader settings" glyph available.
 *  - **Bookmark** → toggles the chapter bookmark; filled glyph when bookmarked, outline otherwise
 *    (native `Icons.Filled.Bookmark` / `Icons.Outlined.BookmarkBorder`).
 *  - **Share** → shares the current page (native `ic_panal_shera`; rework [KiraIcons.Share]).
 *    Rendered only when there are pages to capture ([canShare]).
 *
 * reader-core finding #5 / reader-controls finding #5: this bar was entirely absent in the rework
 * (its actions had been folded into the top bar). Reintroducing it restores native's two-bar
 * bottom chrome (seekbar row + action row) and the thumb-reachable placement of these actions.
 */
@Composable
private fun ReaderBottomActionBar(
    isBookmarked: Boolean,
    canShare: Boolean,
    onSettings: () -> Unit,
    onToggleBookmark: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        // Background BEFORE navigationBarsPadding so the translucent bar fills down to the screen
        // bottom (no empty gap under it in the full-bleed reader); the icons stay above the home
        // indicator via the inset. The 56.dp is the content (icon-row) height.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
            .navigationBarsPadding()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        KiraIconButton(
            icon = KiraIcons.Tune,
            contentDescription = stringResource(Res.string.reading_mode),
            onClick = onSettings,
            modifier = Modifier.weight(1f),
        )
        KiraIconButton(
            icon = if (isBookmarked) KiraIcons.Bookmark else KiraIcons.BookmarkOutline,
            contentDescription = stringResource(Res.string.reader_toggle_bookmark),
            onClick = onToggleBookmark,
            modifier = Modifier.weight(1f),
        )
        if (canShare) {
            KiraIconButton(
                icon = KiraIcons.Share,
                contentDescription = stringResource(Res.string.np_reader_share),
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Reading-mode picker dialog (GAP-RDR-02) — staged selection with Apply / Revert, matching the
 * legacy `ReadingModeDialog` + `ReadingModeChips`. Replaces the immediate-apply [DropdownMenu].
 *
 * Behaviour parity:
 *  - A scrollable column of [FilterChip]s (icon + localized title); the staged pick is
 *    highlighted (`selected = true`). Selecting a chip stages it locally without committing.
 *  - Footer: **Revert** (OutlinedButton) discards the staged pick and dismisses; **Apply**
 *    (Button with a check icon) commits the staged pick via [onApply] (which dispatches
 *    `OnReadingModeChanged`) and dismisses. The staged-vs-committed split is the legacy contract
 *    (select → Apply), as opposed to the dropdown's instant apply.
 *  - Dismissing (scrim/back) discards the stage — same as Revert.
 *
 * The dialog open/close flag (`showReadingModeDialog`) is hoisted to [ReaderScreenContent] so the
 * bottom action bar's Settings icon can trigger it (native opens this dialog from the bottom
 * settings icon, not the top bar). The staged mode is local to this composable, seeded from
 * [currentMode] so reopening starts from the live choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingModeDialog(
    currentMode: ReadingMode,
    onApply: (ReadingMode) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var staged by remember(currentMode) { mutableStateOf(currentMode) }
    Dialog(onDismissRequest = onDismiss) {
        // Surface parity (reader-controls finding #2 / reader-core finding #3): native
        // `ReadingModeDialog.kt:57-63` uses `surfaceContainerHigh` at tonalElevation 8.dp with
        // a fixed `RoundedCornerShape(16.dp)` (not the spacing-token corner) and fillMaxWidth(1f).
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Header parity (reader-controls finding #2): native `ReadingModeDialog.kt:67-74`
                // renders the title in headlineSmall + FontWeight.Bold, onSurface, with
                // 24.dp horizontal / 16.dp vertical padding — not titleLarge with the lg token.
                Text(
                    text = stringResource(Res.string.np_reader_mode_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.xs),
                ) {
                    ReadingMode.entries.forEach { mode ->
                        // Per-mode glyph + chip styling parity (reader-controls findings #1/#6 /
                        // reader-core finding #3): native `ReadingModeChips.kt:55-100` renders each
                        // mode as a custom FilterChip carrying a distinct direction/orientation
                        // glyph (RTL arrow, LTR arrow, vertical, webtoon strip, continuous-vertical)
                        // so modes are recognizable at a glance. The chip itself uses a primary
                        // selected container with onPrimary label, an unselected
                        // `inverseOnSurface @ 0.3` container, a fixed `RoundedCornerShape(18.dp)`,
                        // a 40.dp height, 12/6 dp padding, and an explicit primary/onSurface@0.12
                        // border. The icon + label tint follows the selection (onPrimary when
                        // selected, primary @ 0.9 alpha otherwise). Native renders the icon + label
                        // inside the chip's own `label` slot (a Row) rather than the M3
                        // `leadingIcon` slot, so the icon sits inside the pill; the chip's
                        // `selected` highlight is the only selection affordance (the Check glyph
                        // lives on the Apply button, not the chip).
                        val isSelected = mode == staged
                        val chipContentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { staged = mode },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = readingModeIcon(mode),
                                        contentDescription = null,
                                        tint = chipContentColor,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = readingModeLabel(mode),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = chipContentColor,
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                containerColor =
                                    MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.3f),
                            ),
                            shape = RoundedCornerShape(18.dp),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                selectedBorderColor = MaterialTheme.colorScheme.primary,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .height(40.dp),
                        )
                    }
                }
                // Footer parity (reader-controls finding #3 / reader-core finding #3): native
                // `ReadingModeDialog.kt:98-148` — a 16/12 dp-padded Row, End-aligned, with a
                // Revert OutlinedButton (RoundedCornerShape(8.dp), 1.dp onBackground border,
                // bold onBackground label) + a 12.dp Spacer + an Apply filled Button
                // (RoundedCornerShape(8.dp), `onBackground` container, a 18.dp Check icon tinted
                // `background` + a 4.dp Spacer + a bold `background`-colored label).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onBackground),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.np_reader_mode_revert),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { onApply(staged) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = KiraIcons.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.background,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(Res.string.np_reader_mode_apply),
                            color = MaterialTheme.colorScheme.background,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Localized label for a [ReadingMode] entry. Kept inline as a `when` over the enum so adding a
 * new entry yields a compile error (exhaustive matching), matching the rework's "labels are a
 * `:ui` concern, not an enum-constructor concern" rule documented on [ReadingMode] KDoc.
 *
 * `@Composable` so each arm resolves a `stringResource` (UP-3 localization). The labels are
 * deliberately short to fit the dropdown's natural width. Reuses the shared `reading_mode_*`
 * keys already shipped for the Settings reading-mode picker.
 */
@Composable
private fun readingModeLabel(mode: ReadingMode): String = when (mode) {
    ReadingMode.DEFAULT -> stringResource(Res.string.reading_mode_default)
    ReadingMode.RIGHT_TO_LEFT -> stringResource(Res.string.reading_mode_rtl)
    ReadingMode.LEFT_TO_RIGHT -> stringResource(Res.string.reading_mode_ltr)
    ReadingMode.VERTICAL -> stringResource(Res.string.reading_mode_vertical)
    ReadingMode.WEBTOON -> stringResource(Res.string.reading_mode_webtoon)
    ReadingMode.CONTINUOUS_VERTICAL -> stringResource(Res.string.reading_mode_continuous)
}

/**
 * Per-mode glyph for the reading-mode picker chips (reader-core finding #1 / reader-controls
 * finding #6). Native `ReadingMode.kt` assigns each entry a bespoke drawable (`ic_reader_rtl_24dp`,
 * `ic_reader_ltr`, `ic_reader_vertical_24dp`, `ic_reader_webtoon_24dp`,
 * `ic_reader_continuous_vertical_24dp`); the rework maps each entry to the closest
 * material-icons-extended vector so the chips stay scannable by orientation/direction:
 *  - DEFAULT / CONTINUOUS_VERTICAL → stacked horizontal panels (`ViewDay`) — native uses the same
 *    `ic_reader_continuous_vertical_24dp` drawable for both.
 *  - RIGHT_TO_LEFT / LEFT_TO_RIGHT → directional text-flow arrows (auto-mirrored only flips the
 *    glyph's intrinsic chrome; the LToR/RToL pair already encodes the reading direction).
 *  - VERTICAL → single portrait page (`StayCurrentPortrait`) — paged top-to-bottom.
 *  - WEBTOON → tall single device strip (`Smartphone`) — the continuous-scroll strip.
 *
 * Kept inline as an exhaustive `when` (compile error on a new entry), co-located with
 * [readingModeLabel], matching the rework's "icon + label live in `:ui`, not the `:domain` enum
 * constructor" rule documented on [ReadingMode]'s KDoc.
 */
private fun readingModeIcon(mode: ReadingMode): ImageVector = when (mode) {
    ReadingMode.DEFAULT -> Icons.Filled.ViewDay
    ReadingMode.RIGHT_TO_LEFT -> Icons.AutoMirrored.Filled.FormatTextdirectionRToL
    ReadingMode.LEFT_TO_RIGHT -> Icons.AutoMirrored.Filled.FormatTextdirectionLToR
    ReadingMode.VERTICAL -> Icons.Filled.StayCurrentPortrait
    ReadingMode.WEBTOON -> Icons.Filled.Smartphone
    ReadingMode.CONTINUOUS_VERTICAL -> Icons.Filled.ViewDay
}

/**
 * Page-indicator HUD pill rendered as a bottom-center overlay. Pure presentation — the text is
 * formed by [pageIndicator] from `state.currentPageIndex` + `pages.size`. The HUD doesn't
 * subscribe to scroll directly; the VM is the source of truth (scroll → `OnPageChanged` →
 * state update → HUD recomposition).
 *
 * Surface choice: `Surface` with rounded corners + `surfaceVariant` tonal background mirrors
 * Material 3's recommended HUD/Pill pattern. No elevation — we want a flat, unobtrusive
 * overlay over the image pages.
 */
@Composable
private fun ReaderPageIndicatorHud(text: String) {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(spacing.lg),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = spacing.lg),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(
                horizontal = spacing.md,
                vertical = spacing.xs,
            ),
        )
    }
}

/**
 * Bottom-center page seekbar — faithful port of native `SeekBarContainer.kt` (reader-core
 * finding #3 / reader-controls finding #4). Three rounded translucent pills in a Row:
 *
 *  - **Next-chapter pill** (left): an IconButton that steps to the NEXT chapter — native puts the
 *    forward action on the FIRST pill (left = forward, matching the right-to-left manga reading
 *    flow), `IconButton(enabled = hasNext, onClick = onNext)` behind the `ic_previous` left-chevron
 *    glyph (`SeekBarContainer.kt:50-62`). Native's flanking seekbar buttons step CHAPTERS
 *    (`onPrevious`/`onNext` wired to chapter nav in `ControlOverlay.kt:83-89`), NOT pages — so the
 *    rework's earlier page-step buttons are replaced with chapter-step ones to match the
 *    source-of-truth semantics (reader-controls finding #3). Disabled at the last chapter.
 *  - **Slider pill** (center, weight 1f): the page Slider flanked by the current-page number
 *    (`progress + 1`) on the left and the total page count on the right — both `bodySmall`,
 *    each `weight 1f`, the Slider `weight 9f` — matching native `SeekBarContainer.kt:74-104`.
 *    This restores the inline numeric readout the rework had dropped (it previously lived only
 *    in the separate HUD pill / top bar).
 *  - **Prev-chapter pill** (right): steps to the PREVIOUS chapter — native puts the back action on
 *    the LAST pill (`SeekBarContainer.kt:114-129`). Disabled at the first chapter.
 *
 * Each pill is a `Surface` with `RoundedCornerShape(50)` and `background.copy(alpha = 0.8f)`,
 * mirroring native's `Card(RoundedCornerShape(50), background @0.8f)`.
 *
 * Slider MVI re-use is unchanged: the page Slider dispatches the SAME [ReaderIntent.OnPageChanged]
 * the scroll/pager `snapshotFlow` effects emit, and each layout's `LaunchedEffect(currentPageIndex)`
 * closes the jump-to-page loop (scrubber drag → VM state → recompose → scroll). The if-guard in
 * `onValueChange` skips intent dispatch when the integer page didn't change.
 *
 * Slider config: `value` = `currentPageIndex` coerced into range; `valueRange = 0f..lastIndex`;
 * `steps = pagesCount - 2` (Material3 counts internal steps, so N-2 internal + 2 endpoints = N
 * discrete page positions) — same `valueRange 0f..total-1` / `steps total-1` posture as native.
 */
@Composable
private fun ReaderPageScrubber(
    positionInChapter: Int,
    chapterPageCount: Int,
    canGoPrevChapter: Boolean,
    canGoNextChapter: Boolean,
    onSeekToChapterPage: (Int) -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // #5: the slider is scoped to the ACTIVE chapter — its range is that chapter's page count and its
    // value is the position WITHIN that chapter. In a continuous feed with appended chapters it
    // re-binds to the chapter in view (the caller derives [positionInChapter]/[chapterPageCount] from
    // `activeChapterPageNumber`/`activeChapterPageCount`), so it reads "3 / 20", not "23 / 60".
    val lastIndex = (chapterPageCount - 1).coerceAtLeast(0)
    // Pill background: theme background at 0.8 alpha (native `SeekBarContainer` Card color).
    val pillColor = MaterialTheme.colorScheme.background.copy(alpha = 0.8f)
    val pillShape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        // Next-chapter pill (left, prev-chevron glyph): native puts the NEXT-chapter action on the
        // FIRST pill — left = forward, matching the right-to-left manga reading flow
        // (native `SeekBarContainer.kt:50-62`: `IconButton(enabled = hasNext, onClick = onNext)`
        // behind the `ic_previous` left-chevron glyph).
        Surface(shape = pillShape, color = pillColor) {
            KiraIconButton(
                icon = KiraIcons.PrevChapter,
                contentDescription = stringResource(Res.string.reader_next_chapter),
                onClick = onNextChapter,
                enabled = canGoNextChapter,
            )
        }
        // Slider pill with flanking current/total page numbers.
        Surface(
            shape = pillShape,
            color = pillColor,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = spacing.sm),
            ) {
                Text(
                    text = "${positionInChapter + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Slider(
                    value = positionInChapter.toFloat().coerceIn(0f, lastIndex.toFloat()),
                    onValueChange = { newValue ->
                        val newRel = newValue.roundToInt().coerceIn(0, lastIndex)
                        if (newRel != positionInChapter) {
                            onSeekToChapterPage(newRel)
                        }
                    },
                    valueRange = 0f..lastIndex.toFloat(),
                    steps = (chapterPageCount - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(9f),
                )
                Text(
                    text = "$chapterPageCount",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Prev-chapter pill (right, next-chevron glyph): native puts the PREVIOUS-chapter action on
        // the LAST pill (native `SeekBarContainer.kt:114-129`: `onClick = onPrevious`).
        Surface(shape = pillShape, color = pillColor) {
            KiraIconButton(
                icon = KiraIcons.NextChapter,
                contentDescription = stringResource(Res.string.reader_previous_chapter),
                onClick = onPrevChapter,
                enabled = canGoPrevChapter,
            )
        }
    }
}

/**
 * Dispatch composable that selects a layout based on the user's persisted [ReadingMode]
 * (Phase 7.x.reader.modelayout — finally surfaces the field stored by Phase 6.4.x.mode as a
 * user-visible behaviour change).
 *
 * **Branch table (legacy parity reference)**: the rework reader now mirrors the legacy app's
 * layout choices for every [ReadingMode] entry:
 *  - [ReadingMode.RIGHT_TO_LEFT] / [ReadingMode.LEFT_TO_RIGHT] → [ReaderHorizontalPager]
 *  - [ReadingMode.DEFAULT] / [ReadingMode.VERTICAL] → [ReaderVerticalPager]
 *  - [ReadingMode.WEBTOON] / [ReadingMode.CONTINUOUS_VERTICAL] → [ReaderVerticalList]
 *
 * Three composables, six modes — the pairing follows directly from how the legacy app groups
 * them. The `when` arms are explicit (no fall-through), so the compiler enforces exhaustiveness
 * and adding a new [ReadingMode] entry triggers a compile error here. See `ARCHITECTURE.md`
 * §64–§66 for slice-by-slice history and deferred polish (per-source `DEFAULT` resolution).
 * Gapless-webtoon spacing landed in §68; mode-toggle scroll-position preservation landed
 * in §69 — both removed from the deferral list.
 *
 * **State ownership pushed down**: each layout composable owns its own scroll/page state
 * (`LazyListState` for vertical, `PagerState` for horizontal) and installs its own
 * `snapshotFlow → onPageChanged` effect. This screen does NOT hoist a list-type-specific
 * state — that would couple the parent to one layout shape and prevent future layouts
 * (e.g. a `Pager`-backed RTL) from joining without invasive refactors. The contract is just
 * `(pages, readingMode) → emits page indices via onPageChanged`.
 *
 * **State preserved on mode toggle (Phase 7.x.reader.modelayout.scrollpos / §69)**:
 * swapping `readingMode` recomposes a different layout subtree, so the previous
 * layout's [LazyListState] / [PagerState] is discarded. The VM's `currentPageIndex`
 * persists across the swap (it's in [ReaderState]) and is threaded in as
 * `currentPageIndex` so the new layout opens on the same page the user was reading.
 * `remember*State`'s `initial*` parameter is honoured only on first composition — within
 * the same layout, recompositions ignore it and the live state remains the source of
 * truth for the layout's own scroll/pager state. Improves on legacy, which jumps back
 * to page 0 on every mode toggle.
 *
 * **Scrubber-driven scroll (Phase 7.x.reader.pagescrubber)**: each layout also installs a
 * `LaunchedEffect(currentPageIndex)` that calls `scrollToItem` / `scrollToPage` when the
 * param changes and the layout's own scroll/pager state lags behind. That closes the loop
 * for the bottom Slider scrubber: scrubber drag → `OnPageChanged` → VM state update →
 * recompose with new `currentPageIndex` → layout LaunchedEffect → scroll. The if-guard
 * makes the inverse direction (scroll → snapshotFlow → onPageChanged → state) a no-op,
 * keeping the loop stable. Side benefit: future jump-to-page sources (e.g. Prev/Next
 * chapter resume, deeplink-to-page) reuse the same plumbing without additional wiring.
 */
@Composable
private fun ReaderPageLayout(
    pages: List<Page>,
    readingMode: ReadingMode,
    currentPageIndex: Int,
    screenHeightDb: Dp,
    onPageChanged: (Int) -> Unit,
    onReachedEnd: () -> Unit,
    onAppendNext: () -> Unit,
    pageChapters: List<String>,
    chapters: List<Chapter>,
    anchorChapter: Chapter?,
    activeChapter: Chapter,
    nextChapter: Chapter?,
    onOpenInWebView: () -> Unit,
    onToggleUi: () -> Unit,
    pageProgress: Map<String, PageDownloadProgress>,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    when (readingMode) {
        ReadingMode.RIGHT_TO_LEFT -> ReaderHorizontalPager(
            pages = pages,
            reverseLayout = true,
            currentPageIndex = currentPageIndex,
            screenHeightDb = screenHeightDb,
            onPageChanged = onPageChanged,
            onReachedEnd = onReachedEnd,
            activeChapter = activeChapter,
            nextChapter = nextChapter,
            onOpenInWebView = onOpenInWebView,
            onToggleUi = onToggleUi,
            pageProgress = pageProgress,
            onReportProgress = onReportProgress,
        )
        ReadingMode.LEFT_TO_RIGHT -> ReaderHorizontalPager(
            pages = pages,
            reverseLayout = false,
            currentPageIndex = currentPageIndex,
            screenHeightDb = screenHeightDb,
            onPageChanged = onPageChanged,
            onReachedEnd = onReachedEnd,
            activeChapter = activeChapter,
            nextChapter = nextChapter,
            onOpenInWebView = onOpenInWebView,
            onToggleUi = onToggleUi,
            pageProgress = pageProgress,
            onReportProgress = onReportProgress,
        )
        // DEFAULT and VERTICAL both render the paged-vertical layout in legacy
        // (`VerticalReadingMode.kt`). Treating `DEFAULT` as a synonym for `VERTICAL` here is
        // a temporary parity-correct choice; the eventual per-source default resolution would
        // map `DEFAULT` to whatever the source/manga prefers (logged at §66 deferrals).
        ReadingMode.DEFAULT,
        ReadingMode.VERTICAL,
        -> ReaderVerticalPager(
            pages = pages,
            currentPageIndex = currentPageIndex,
            screenHeightDb = screenHeightDb,
            onPageChanged = onPageChanged,
            onReachedEnd = onReachedEnd,
            activeChapter = activeChapter,
            nextChapter = nextChapter,
            onOpenInWebView = onOpenInWebView,
            onToggleUi = onToggleUi,
            pageProgress = pageProgress,
            onReportProgress = onReportProgress,
        )
        // WEBTOON and CONTINUOUS_VERTICAL both render as free-scroll `LazyColumn` in legacy
        // (`WebToonReadingMode.kt` / `ContinuousVerticalReadingMode.kt`). They're not paged
        // — they're vertically-stitched panels. They differ in image `ContentScale`
        // (gestures-zoom finding #3): native WEBTOON uses `ContentScale.FillWidth`
        // (`WebToonReadingMode.kt:204,256`) while CONTINUOUS_VERTICAL draws the Image with the
        // Compose-default `ContentScale.Fit` (`ContinuousVerticalReadingMode.kt:262-273` passes
        // no contentScale). The fit is derived here and threaded into the shared list layout so
        // CONTINUOUS_VERTICAL stops force-stretching non-portrait pages to full width.
        ReadingMode.WEBTOON,
        ReadingMode.CONTINUOUS_VERTICAL,
        -> ReaderVerticalList(
            pages = pages,
            pageChapters = pageChapters,
            chapters = chapters,
            anchorChapter = anchorChapter,
            currentPageIndex = currentPageIndex,
            screenHeightDb = screenHeightDb,
            contentScale = if (readingMode == ReadingMode.WEBTOON) {
                ContentScale.FillWidth
            } else {
                ContentScale.Fit
            },
            onPageChanged = onPageChanged,
            onReachedEnd = onReachedEnd,
            onAppendNext = onAppendNext,
            onOpenInWebView = onOpenInWebView,
            onToggleUi = onToggleUi,
            pageProgress = pageProgress,
            onReportProgress = onReportProgress,
        )
    }
}

@Composable
private fun ReaderVerticalList(
    pages: List<Page>,
    pageChapters: List<String>,
    chapters: List<Chapter>,
    anchorChapter: Chapter?,
    currentPageIndex: Int,
    screenHeightDb: Dp,
    contentScale: ContentScale,
    onPageChanged: (Int) -> Unit,
    onReachedEnd: () -> Unit,
    onAppendNext: () -> Unit,
    onOpenInWebView: () -> Unit,
    onToggleUi: () -> Unit,
    pageProgress: Map<String, PageDownloadProgress>,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    // #5 continuous reader: render an interleaved feed (pages + inline chapter-boundary cards) instead
    // of the raw page list. `pages`/`currentPageIndex` stay in PAGE-index space (the VM is unchanged);
    // the two maps translate between page-index space and feed-index (LazyColumn) space. Memoized so
    // the feed + maps rebuild only when the page list / tags / chapter list change.
    val feed = remember(pages, pageChapters, chapters, anchorChapter) {
        buildReaderFeed(pages, pageChapters, chapters, anchorChapter)
    }
    val lastPageIndex = (pages.size - 1).coerceAtLeast(0)
    // `currentPageIndex` is honoured by `rememberLazyListState` only on the FIRST
    // composition (see KDoc: "the state will only be created once" per composable
    // identity). Mode-toggle creates a fresh `ReaderVerticalList` composition → fresh
    // state → initial scroll lands on the user's current page. Within the same layout,
    // recompositions don't recreate the state; the live `currentPageIndex` value is
    // instead consumed by the scrubber-driven LaunchedEffect below.
    // Initial scroll lands on the FEED position of the resume/current page (a page may be preceded by
    // boundary cards, so the page index and the feed index differ).
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = feed.pageToFeed.getOrElse(
            currentPageIndex.coerceIn(0, lastPageIndex),
        ) { 0 },
    )
    // Drive OnPageChanged from scroll position. snapshotFlow + distinctUntilChanged keeps the VM out
    // of the hot scroll path — only landings on a new top-visible item dispatch. The feed index is
    // mapped back to a PAGE index via `feedToPage`; a boundary row carries the finished chapter's last
    // page (so the active chapter doesn't flip to the next one until the next chapter's first image is
    // top-visible). Keyed on `feed` so an append re-subscribes with the grown map. Reducer-side
    // clamping + identity-drop guards against any out-of-range / stale emissions.
    LaunchedEffect(listState, feed) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { feedIdx ->
                val pageIdx = feed.feedToPage.getOrNull(feedIdx)
                pageIdx?.let(onPageChanged)
            }
    }
    // Drive scroll from `currentPageIndex` — closes the loop for the bottom scrubber (and resume). The
    // target is the page's FEED position (`pageToFeed`). Keyed ONLY on `currentPageIndex` (NOT `feed`) so
    // an append — which grows the feed without changing the current page — never re-runs this.
    LaunchedEffect(currentPageIndex) {
        val scrolling = listState.isScrollInProgress
        val clampedPage = currentPageIndex.coerceIn(0, lastPageIndex)
        val target = feed.pageToFeed.getOrNull(clampedPage)
        val mappedNow = feed.feedToPage.getOrNull(listState.firstVisibleItemIndex)
        // Never auto-scroll while the user is actively scrolling/flinging — the scroll-driven
        // currentPageIndex lags the live position by a frame; firing scrollToItem then yanks the list
        // back and cancels the drag. scrollToItem here is only for explicit jumps (scrubber / resume),
        // which fire while the list is idle.
        if (scrolling) return@LaunchedEffect
        if (target == null) return@LaunchedEffect
        // Compare in PAGE space, not feed space (a boundary row aliases to the previous image's page).
        if (mappedNow != clampedPage) {
            listState.scrollToItem(target)
        }
    }
    // Auto-load the next chapter on scroll-to-end (BusinessLogic parity). Mirrors native
    // `WebToonReadingMode.kt:95-106` / `ContinuousVerticalReadingMode.kt:82-93`: watch
    // `listState.isScrolledToTheEnd()` and fire `onReachedEnd` ONCE per end-reach, re-armed only after
    // the user scrolls back off the end (`alreadyLoading` latch).
    var alreadyLoading by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrolledToTheEnd() }
            .collect { reachedEnd ->
                if (reachedEnd && !alreadyLoading) {
                    alreadyLoading = true
                    onReachedEnd()
                } else if (!reachedEnd && alreadyLoading) {
                    alreadyLoading = false
                }
            }
    }
    ReaderVerticalListBody(
        feedItems = feed.items,
        listState = listState,
        screenHeightDb = screenHeightDb,
        onOpenInWebView = onOpenInWebView,
        onToggleUi = onToggleUi,
        onAppendNext = onAppendNext,
        contentScale = contentScale,
        pageProgress = pageProgress,
        onReportProgress = onReportProgress,
    )
}

@OptIn(ExperimentalZoomableApi::class)
@Composable
private fun ReaderVerticalListBody(
    feedItems: List<ReaderFeedItem>,
    listState: LazyListState,
    screenHeightDb: Dp,
    onOpenInWebView: () -> Unit,
    onToggleUi: () -> Unit,
    onAppendNext: () -> Unit,
    contentScale: ContentScale,
    pageProgress: Map<String, PageDownloadProgress>,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            // Pinch-zoom + tap-toggle on the continuous list (restored 2026-06-30, reverting the af56e1c2
            // "WEBTOON-SCROLL FIX"). `zoomableWithScroll` is the engawapg overload for lazy lists
            // (enableNestedScroll = true): a pan past the zoomed-content edge hands off to the list's own
            // vertical scroll, and `onTap` toggles chrome at the gesture layer (matches native-app
            // WebToonReadingMode / ContinuousVerticalReadingMode). This was removed to chase an iOS-only
            // scroll stall; that no longer applies — iOS now runs the native Swift reader by default, so
            // this Compose path is Android/Desktop (where scroll was always fine) plus the iOS fallback.
            .zoomableWithScroll(rememberZoomState(), onTap = { onToggleUi() })
            // Theme background painted at the LazyColumn level (not the outer Box) so it
            // sits *behind* the items only — matches legacy
            // `WebToonReadingMode.kt` line 134 and `ContinuousVerticalReadingMode.kt` line
            // 118. Practical effect: any inter-item void (e.g. an item briefly collapsed to
            // zero height during a streaming Success → Success swap, or a sub-screen-height
            // image that doesn't fill its slot vertically) shows the theme background
            // rather than a transparent/parent-color bleed. The pagers don't need this
            // because each page fills the viewport — their legacy counterparts
            // (`HorizontalReadingMode.kt` / `VerticalReadingMode.kt`) deliberately omit it.
            .background(MaterialTheme.colorScheme.background),
    ) {
        itemsIndexed(
            items = feedItems,
            // Stable keys keep already-decoded composables warm across streaming/append growth so
            // Coil's memory cache stays warm and the scroll position doesn't jump. Image: the feed
            // index combined with the page URL — scraped chapters can legitimately repeat an image
            // URL (appended credit/recruitment pages, loosely-matched banners), and two duplicate
            // URLs co-composed in the appended feed would otherwise throw a duplicate-key crash; the
            // index suffix mirrors native WebToonReadingMode.kt. Boundary: namespaced by the FINISHED
            // chapter URL — never the next chapter (which is null for the terminal card and flips to
            // interior on append). The "boundary:" prefix can't collide with an http/file page URL.
            key = { index, item ->
                when (item) {
                    is ReaderFeedItem.Image -> "$index:${item.page.url}"
                    is ReaderFeedItem.Boundary -> "boundary:${item.finishedChapter?.url ?: "anchor"}"
                }
            },
        ) { index, item ->
            when (item) {
                is ReaderFeedItem.Image -> {
                    ReaderPageItem(
                        page = item.page,
                        screenHeightDb = screenHeightDb,
                        onOpenInWebView = onOpenInWebView,
                        progress = pageProgress[item.page.url] ?: PageDownloadProgress.Idle,
                        onReportProgress = onReportProgress,
                        // contentScale differs between the two LazyColumn modes (gestures-zoom finding #3):
                        // WEBTOON = FillWidth (strip fills width, height follows), CONTINUOUS_VERTICAL = Fit
                        // (image fits within its slot, native default). Passed down from ReaderVerticalList.
                        contentScale = contentScale,
                    )
                }
                // Inline chapter boundary AFTER the last image of a chapter (native parity). It is the
                // tappable "next chapter" affordance only when it is the LAST feed item (terminal) and
                // a next chapter exists — an interior boundary (next chapter already loaded below) is a
                // passive divider the user scrolls through.
                is ReaderFeedItem.Boundary -> NextChapterBoundaryCard(
                    finishedChapter = item.finishedChapter,
                    nextChapter = item.nextChapter,
                    isTerminal = index == feedItems.lastIndex,
                    screenHeightDb = screenHeightDb,
                    onGoToNext = onAppendNext,
                )
            }
        }
    }
}

/**
 * Paginated horizontal layout — one page per swipe, optionally with [reverseLayout] flipped so
 * page 0 lands on the right edge (RTL manga convention).
 *
 * `PagerState.currentPage` is the canonical index of the page currently snapped to the
 * viewport; [reverseLayout] only flips the visual order, not the indexing — so a `currentPage`
 * of 2 always corresponds to `pages[2]` regardless of which side it appears on. This keeps the
 * dispatch contract identical to [ReaderVerticalList]: both compose a stream of integer page
 * indices through `onPageChanged`, and the VM's `OnPageChanged` reducer doesn't care which
 * layout produced them.
 *
 * `ContentScale.Fit` for the page bitmap is intentional: paged reading expects each image to
 * fit within the viewport (both axes) without cropping. The vertical scroll layout uses
 * `FillWidth` because pages stack vertically with overflow becoming scroll distance — the two
 * shapes need different fits, hence the parameter on [ReaderPageItem].
 */
@OptIn(ExperimentalZoomableApi::class)
@Composable
private fun ReaderHorizontalPager(
    pages: List<Page>,
    reverseLayout: Boolean,
    currentPageIndex: Int,
    screenHeightDb: Dp,
    onPageChanged: (Int) -> Unit,
    onReachedEnd: () -> Unit,
    activeChapter: Chapter,
    nextChapter: Chapter?,
    onOpenInWebView: () -> Unit,
    onToggleUi: () -> Unit,
    pageProgress: Map<String, PageDownloadProgress>,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    // #14: when a next chapter exists, append ONE dummy "Next Chapter" page after the last image
    // (index == pages.size). The last image is then dwellable — it is no longer the pager's last
    // index — and the chapter advance fires only once the user swipes ONTO this extra page, instead
    // of the moment they land on the final image. No dummy on the terminal chapter (nothing to
    // advance to).
    val hasDummyPage = nextChapter != null
    val pageCount = pages.size + if (hasDummyPage) 1 else 0
    // Same `currentPageIndex` semantics as `ReaderVerticalList`: used as the initial page
    // on first composition (mode-toggle creates a fresh PagerState) AND consumed by the
    // scrubber-driven LaunchedEffect below for jump-to-page (Phase 7.x.reader.pagescrubber).
    val pagerState = rememberPagerState(
        initialPage = currentPageIndex,
        pageCount = { pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            // Don't report the dummy page as a real page index (it has no [Page]); the VM stays in
            // page-index space and the active chapter holds on the last real image while on it.
            .collect { page -> if (page < pages.size) onPageChanged(page) }
    }
    // Auto-advance to the next chapter only once the user swipes ONTO the dummy "Next Chapter" page
    // (index == pages.size) — i.e. one swipe PAST the last image, never on mere arrival at it. Guard
    // on a forward transition (`previous < page`) so opening / resuming directly on the dummy page is
    // a no-op. Disabled when there is no dummy page (terminal chapter). Upstream `canGoNext` already
    // gated `nextChapter`, so reaching here means a real advance.
    LaunchedEffect(pagerState, pages.size, hasDummyPage) {
        if (!hasDummyPage) return@LaunchedEffect
        var previous = pagerState.currentPage
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                if (page == pages.size && previous < page) {
                    onReachedEnd()
                }
                previous = page
            }
    }
    // Scrubber-driven scroll. See `ReaderVerticalList`'s analogous LaunchedEffect for
    // rationale. `scrollToPage` is the suspend pager equivalent of `scrollToItem` — no
    // animation, settles in the same frame so the scrubber feels snappy. Targets real pages only.
    LaunchedEffect(currentPageIndex) {
        val target = currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    // Pin the pager's layout direction to Ltr so `reverseLayout` is interpreted against a fixed
    // axis: `reverseLayout = true` (RIGHT_TO_LEFT) always pages right-to-left and `false`
    // (LEFT_TO_RIGHT) always pages left-to-right, regardless of the ambient UI locale. Without this
    // the app-root RTL direction (Arabic locale) double-mirrors the pager, swapping the two modes so
    // each reads the opposite of its label. The page scrubber / chrome stay in the ambient direction.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = reverseLayout,
            // `.zoomableWithScroll` before `.fillMaxSize` so the pinch gesture is recognized at the
            // pager level, mirroring native `HorizontalReadingMode.kt:47` (`zoomableWithScroll(...,
            // onTap = { onTap() })`). Native uses the `enableNestedScroll = true` overload even on the
            // pagers so a pan past the zoomed-page edge can coordinate with the pager's own swipe;
            // matching the source-of-truth modifier choice (gestures-zoom finding #1). `onTap` is fed
            // into the gesture layer too (gestures-zoom finding #2): native wires the chrome-toggle at
            // BOTH the outer Box and inside `zoomableWithScroll`, so a single tap toggles chrome
            // regardless of whether the zoomable's gesture detector consumed the pointer.
            modifier = Modifier
                .zoomableWithScroll(rememberZoomState(), onTap = { onToggleUi() })
                .fillMaxSize(),
            // Index-composite key: scraped chapters can repeat a page URL, so the bare URL would
            // throw a duplicate-key crash; the index suffix mirrors native WebToonReadingMode.kt.
            // The trailing dummy page (index == pages.size) gets a stable sentinel key.
            key = { index -> if (index < pages.size) "$index:${pages[index].url}" else "next-chapter" },
        ) { index ->
            if (index < pages.size) {
                // `screenHeightDb` threaded for placeholder reserved-height parity with the
                // LazyColumn modes. For pagers it's a no-op inside the page (the page's
                // `.fillMaxSize()` already constrains height to the viewport), but uniform
                // plumbing keeps `ReaderPageItem`'s contract single-shape.
                ReaderPageItem(
                    page = pages[index],
                    screenHeightDb = screenHeightDb,
                    onOpenInWebView = onOpenInWebView,
                    progress = pageProgress[pages[index].url] ?: PageDownloadProgress.Idle,
                    onReportProgress = onReportProgress,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                // Dummy "Next Chapter" page — full-viewport boundary card; tapping it also advances.
                NextChapterBoundaryCard(
                    finishedChapter = activeChapter,
                    nextChapter = nextChapter,
                    isTerminal = true,
                    screenHeightDb = screenHeightDb,
                    onGoToNext = onReachedEnd,
                )
            }
        }
    }
}

/**
 * Paginated vertical layout — one page per vertical swipe. Mirror of [ReaderHorizontalPager]
 * on the vertical axis; same `PagerState.currentPage` indexing semantics (each snap target
 * corresponds 1:1 to `pages[i]`), same `ContentScale.Fit` rationale (paged reading needs each
 * page to fit the viewport without cropping). Used for [ReadingMode.DEFAULT] and
 * [ReadingMode.VERTICAL] — both render this layout in legacy
 * (`VerticalReadingMode.kt` in `:composeApp`'s legacy reader).
 *
 * No `reverseLayout` parameter: legacy doesn't expose a reverse-vertical reading mode
 * (top-to-bottom is the only sensible direction for vertical paging), so adding the flag
 * would be unused complexity.
 */
@OptIn(ExperimentalZoomableApi::class)
@Composable
private fun ReaderVerticalPager(
    pages: List<Page>,
    currentPageIndex: Int,
    screenHeightDb: Dp,
    onPageChanged: (Int) -> Unit,
    onReachedEnd: () -> Unit,
    activeChapter: Chapter,
    nextChapter: Chapter?,
    onOpenInWebView: () -> Unit,
    onToggleUi: () -> Unit,
    pageProgress: Map<String, PageDownloadProgress>,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
) {
    // #14: dummy "Next Chapter" page after the last image — see [ReaderHorizontalPager] for the full
    // rationale. The last image is dwellable and the advance fires only when swiping onto the dummy.
    val hasDummyPage = nextChapter != null
    val pageCount = pages.size + if (hasDummyPage) 1 else 0
    // Same `currentPageIndex` semantics as the horizontal pager.
    val pagerState = rememberPagerState(
        initialPage = currentPageIndex,
        pageCount = { pageCount },
    )
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            // Don't report the dummy page as a real page index (it has no [Page]).
            .collect { page -> if (page < pages.size) onPageChanged(page) }
    }
    // Auto-advance only once the user swipes ONTO the dummy "Next Chapter" page (index == pages.size).
    // See `ReaderHorizontalPager`'s analogous effect for the full rationale.
    LaunchedEffect(pagerState, pages.size, hasDummyPage) {
        if (!hasDummyPage) return@LaunchedEffect
        var previous = pagerState.currentPage
        snapshotFlow { pagerState.currentPage }
            .collect { page ->
                if (page == pages.size && previous < page) {
                    onReachedEnd()
                }
                previous = page
            }
    }
    // Scrubber-driven scroll. See `ReaderVerticalList`'s analogous LaunchedEffect for
    // rationale. Targets real pages only.
    LaunchedEffect(currentPageIndex) {
        val target = currentPageIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }
    VerticalPager(
        state = pagerState,
        // Same `.zoomableWithScroll` + `onTap` posture as `ReaderHorizontalPager`, mirroring native
        // `VerticalReadingMode.kt:39-43` (`zoomableWithScroll(..., onTap = { onTap() })`).
        // gestures-zoom findings #1 (nested-scroll overload on the pager) + #2 (chrome-toggle fed
        // into the gesture layer as well as the outer Box).
        modifier = Modifier
            .zoomableWithScroll(rememberZoomState(), onTap = { onToggleUi() })
            .fillMaxSize(),
        // Index-composite key: scraped chapters can repeat a page URL, so the bare URL would
        // throw a duplicate-key crash; the index suffix mirrors native VerticalReadingMode.kt.
        // The trailing dummy page (index == pages.size) gets a stable sentinel key.
        key = { index -> if (index < pages.size) "$index:${pages[index].url}" else "next-chapter" },
    ) { index ->
        if (index < pages.size) {
            // `screenHeightDb` threaded for placeholder reserved-height parity with the
            // LazyColumn modes. No-op inside the page (the page's `.fillMaxSize()` already
            // constrains height to the viewport). See `ReaderHorizontalPager` rationale.
            ReaderPageItem(
                page = pages[index],
                screenHeightDb = screenHeightDb,
                onOpenInWebView = onOpenInWebView,
                progress = pageProgress[pages[index].url] ?: PageDownloadProgress.Idle,
                onReportProgress = onReportProgress,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            // Dummy "Next Chapter" page — full-viewport boundary card; tapping it also advances.
            NextChapterBoundaryCard(
                finishedChapter = activeChapter,
                nextChapter = nextChapter,
                isTerminal = true,
                screenHeightDb = screenHeightDb,
                onGoToNext = onReachedEnd,
            )
        }
    }
}

@Composable
private fun ReaderPageItem(
    page: Page,
    screenHeightDb: Dp,
    onOpenInWebView: () -> Unit,
    progress: PageDownloadProgress,
    onReportProgress: (url: String, status: PageDownloadProgress) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    val context = LocalPlatformContext.current
    // Wrap [onReportProgress] in `rememberUpdatedState` so the Coil listener (captured into the
    // `remember`-cached [ImageRequest]) always invokes the latest callback, even if the screen's
    // navigation parameters change identity across recompositions. Without this, the listener
    // would keep firing the first-composition's lambda forever — usually fine because the
    // route adapter binds `repo::report` once per route, but defensive against future scenarios
    // (e.g. an unmemoized lambda passed by a parent composable). Cost is one extra
    // `MutableState` allocation per page composable; negligible.
    val reportProgress by rememberUpdatedState(onReportProgress)
    val windowWidthPx = LocalWindowInfo.current.containerSize.width
    val request = remember(page.url, page.headers, windowWidthPx) {
        val headers = NetworkHeaders.Builder().apply {
            page.headers.forEach { (key, value) -> add(key, value) }
        }.build()
        ImageRequest.Builder(context)
            .data(page.url)
            .httpHeaders(headers)
            // Coil 3.4 defaults `maxBitmapSize` to `Size(4096, 4096)` and the loader-level
            // override in `:composeApp/App.kt` doesn't always propagate to every request.
            // Webtoon strips are typically 800×~14000; with the default cap, aspect-
            // preservation collapses width to ~234 px before the decoder runs, producing a
            // low-resolution bitmap that Compose then upscales at draw time. So HEIGHT stays
            // Undefined (natural strip height decodes unrestricted) — but the WIDTH is capped
            // at window-width × zoom headroom (mobile hardening 2026-07-04): with no cap at
            // all, FillWidth's Scale.FILL made the sample size always 1 and a wide-and-tall
            // page decoded at full natural size as a software bitmap — an OOM vector. Coil
            // applies the two axes independently (no width-collapse) and scales bilinearly.
            // See [me.manga.kira.ui.reader.internal.readerDecodeMaxWidthPx].
            .maxBitmapSize(
                readerDecodeMaxWidthPx(windowWidthPx)
                    ?.let { Size(Dimension.Pixels(it), Dimension.Undefined) }
                    ?: Size(Dimension.Undefined, Dimension.Undefined),
            )
            // Per-platform decode hints (Phase 7.x.reader.modelayout.pageprogress Step 7).
            // Android adds `allowHardware(false) + bitmapConfig(RGB_565)`; iOS / Desktop are
            // no-ops. RGB_565 halves cache pressure vs default ARGB_8888 — the load-bearing
            // anti-blur fix documented in the project's image-quality memory: without it,
            // Coil's memory cache fills ~2× faster, evicted pages re-decode at sample
            // size >1, producing visibly blurry mid-scroll output. See
            // [me.manga.kira.ui.reader.internal.applyReaderDecoderHints] KDoc.
            .applyReaderDecoderHints()
            // Per-request lifecycle listener (Phase 7.x.reader.modelayout.pageprogress).
            // Bridges Coil's image-load callbacks to the rework's [PageProgressRepository]
            // via the `:domain`-typed [onReportProgress] callback. The route adapter binds
            // this to `PageProgressRepository::report`, keeping `:ui` decoupled from
            // `:data`. Coil 3.x exposes onStart / onCancel / onError / onSuccess but NO
            // per-byte hook — for per-byte fraction the Android slice adds an OkHttp
            // body wrap in `:platform/androidMain` (Step 6 of the slice plan). iOS /
            // Desktop ktor3 stays Started → Complete / Failed only (no fraction available
            // in commonMain). [PageDownloadProgress.Idle] is reported on cancel so a
            // chapter swap mid-fetch returns the slot to its placeholder default.
            .listener(
                onStart = { reportProgress(page.url, PageDownloadProgress.Started) },
                onCancel = { reportProgress(page.url, PageDownloadProgress.Idle) },
                onSuccess = { _, _ -> reportProgress(page.url, PageDownloadProgress.Complete) },
                onError = { _, _ -> reportProgress(page.url, PageDownloadProgress.Failed) },
            )
            .build()
    }
    // SubcomposeAsyncImage (not AsyncImage) so the Loading / Error states can render
    // distinct content with their own modifiers. The reserved-height contract:
    // `defaultMinSize(minHeight = screenHeightDb)` on the loading + error placeholders
    // keeps a streaming LazyColumn item from collapsing to 0 during the bitmap-in-flight
    // window — without this, the LazyColumn scroll visibly jolts as each item's decoded
    // height lands. For the pagers it's a no-op (the page's `.fillMaxSize()` already
    // gives them maxHeight = viewportHeight ≥ screenHeightDb). Matches legacy parity
    // (`composeApp/.../reading_modes/WebToonReadingMode.kt` lines 200–211 +
    // `ContinuousVerticalReadingMode.kt` lines 200–211). Phase 7.x.reader.modelayout.placeholder.
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        loading = {
            // Determinate vs indeterminate placeholder dispatch (Phase
            // 7.x.reader.modelayout.pageprogress). Only [PageDownloadProgress.InProgress] with
            // a non-null `fraction` produces a determinate ring — every other state stays
            // indeterminate. `coerceIn(0f, 1f)` is a defensive guard against malformed
            // Content-Length headers from misbehaving CDNs (see [PageDownloadProgress.InProgress]
            // KDoc). The repository's `Idle` default is the placeholder's "nothing yet" state
            // (chapter just entered, no per-page listener tick yet) — also indeterminate so
            // the user sees a spinner instead of a frozen 0% ring. Decoding emits as
            // indeterminate too because Coil's `onSuccess` lands almost immediately after the
            // bytes arrive, so a brief flicker through a final spinner is the right ergonomics
            // (matches legacy `WebToonReadingMode.kt` page placeholder).
            val fraction = (progress as? PageDownloadProgress.InProgress)?.fraction
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = screenHeightDb),
                contentAlignment = Alignment.Center,
            ) {
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction.coerceIn(0f, 1f) })
                } else {
                    CircularProgressIndicator()
                }
            }
        },
        error = {
            // Per-page retry button (Phase 7.x.reader.modelayout.pageretry). Driven by
            // Coil-level `AsyncImagePainter.restart()` — re-issues this page's individual
            // ImageRequest without touching the rest of the chapter. Mirrors legacy parity
            // in `composeApp/.../reading_modes/WebToonReadingMode.kt` line 211,
            // `PagerImageItem.kt` line 83, and `ContinuousVerticalReadingMode.kt` line 212
            // (all three use `onRetry = { painter.restart() }`). No MVI plumbing required:
            // per-page retry is a UI-only concern that Coil's painter contract already
            // expresses; the `SubcomposeAsyncImageScope` receiver exposes `painter` here.
            // Chapter-level OnRetry (top-bar refresh action) remains the only MVI-routed
            // retry and re-fetches the whole page list. Legacy's "Open in WebView" half
            // of the error pane is still deferred — it needs a new `ReaderEffect`
            // (`OpenChapterInWebView`) + route adapter + platform IntentLauncher; tracked
            // in ARCHITECTURE.md §71 deferrals.
            // Capture `painter` from the `SubcomposeAsyncImageScope` receiver before
            // entering the nested `BoxScope` / `ColumnScope` lambdas — the scope's
            // implicit receiver is shadowed by inner Box/Column scopes, and Coil's
            // `SubcomposeAsyncImageScope.painter` is only accessible at the top of
            // the `error` slot (Kotlin compile error otherwise:
            // "cannot be called in this context with an implicit receiver").
            val errorPainter = painter
            val errorSpacing = LocalSpacing.current
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = screenHeightDb),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(errorSpacing.md),
                ) {
                    Text(
                        text = stringResource(Res.string.failed_to_load_image),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // Retry + Open-in-WebView buttons side-by-side. Mirrors legacy
                    // `ImageLoadError` `Row` of two `BorderedPrimaryButton`s — same
                    // visual shape, same affordance pair. Phase 7.x.reader.modelayout.openwebview
                    // (closes the §71.7 deferral on the Open-in-WebView half of the
                    // legacy parity gap). Retry is Coil-level via `painter.restart()`
                    // (§71); Open-in-WebView goes through MVI as
                    // `ReaderIntent.OnOpenInWebView` → `ReaderEffect.OpenChapterInWebView`
                    // → route adapter → `navController.safeNavigate(Screen.WebView(url, api))`.
                    // Error-pane buttons geometry parity (reader-controls finding #8): native
                    // `ImageLoadError.kt:42-54` lays out two `BorderedPrimaryButton`s — height
                    // 38.dp, RoundedCornerShape(16.dp), contentPadding 8.dp vertical / 28.dp
                    // horizontal, primary container + onPrimary content, elevation 4.dp — in a
                    // 12.dp-spaced Row (`ReaderScreen.kt:731-765`). Replaces the rework's two
                    // default M3 Buttons.
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ReaderBorderedPrimaryButton(
                            text = stringResource(Res.string.retry),
                            onClick = { errorPainter.restart() },
                        )
                        ReaderBorderedPrimaryButton(
                            text = stringResource(Res.string.action_open_in_browser),
                            onClick = onOpenInWebView,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Filled primary button with the native `BorderedPrimaryButton` geometry
 * (`native-app/.../reader/ui/screens/ReaderScreen.kt:731-765`), used by the per-page error pane
 * (reader-controls finding #8): fixed 38.dp height (min-height floor removed so the 38.dp takes
 * effect), `RoundedCornerShape(16.dp)`, 8.dp-vertical / 28.dp-horizontal content padding, primary
 * container + onPrimary content, and 4.dp resting elevation. Native also paints a 1.dp transparent
 * border before clipping to the same shape — a no-op once `shape` already rounds + clips the
 * button, so it is dropped here.
 */
@Composable
private fun ReaderBorderedPrimaryButton(
    text: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minHeight = 0.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        contentPadding = PaddingValues(vertical = 8.dp, horizontal = 28.dp),
    ) {
        Text(text = text)
    }
}

/**
 * Next-chapter transition card (GAP-RDR-05) — legacy `NextChapterCard` / `errorCard` parity.
 *
 * Rendered as a banner pinned to the bottom of the page area once the user is on the last page.
 * Two shapes:
 *  - **Has next chapter**: "You are in <currentLabel>" / "Going to <nextLabel>" with the current
 *    chapter in `onSurface` and the next in `primary` (legacy titleLarge primary/secondary split).
 *    The whole card is `clickable` → [onGoToNext] (dispatches `OnNextChapter`). While the next
 *    chapter's pages are fetching ([isLoadingNext]) a spinner replaces the affordance, matching
 *    the legacy card's loading state.
 *  - **No next chapter**: a terminal "you're on the last chapter" message in `onSurfaceVariant`
 *    (legacy errorCard "No Next Chapter"), not clickable.
 *
 * A bottom banner (rather than the legacy full-screen page) is the rework-appropriate shape: the
 * reader auto-advances on the last page already (each layout's end-of-chapter watcher dispatches
 * `OnNextChapter` — see [ReaderPageLayout] `onReachedEnd`, native parity with
 * `ReaderScreen.kt:257-266` / `WebToonReadingMode.kt:96-106`), so this is the VISIBLE affordance
 * layered over the last page — it doesn't replace the page or block reading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextChapterOverlay(
    currentChapter: Chapter,
    nextChapter: Chapter?,
    isLoadingNext: Boolean,
    onGoToNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.md),
        shape = RoundedCornerShape(spacing.md),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        tonalElevation = 6.dp,
        onClick = onGoToNext,
        enabled = nextChapter != null && !isLoadingNext,
    ) {
        NextChapterContent(
            currentChapter = currentChapter,
            nextChapter = nextChapter,
            isLoadingNext = isLoadingNext,
        )
    }
}

/**
 * Inline chapter-boundary card placed AFTER the last image of a chapter in the continuous feed
 * (#5, native `NextChapterCard` parity). Full-viewport height so the previous image (its own list
 * item) is fully seen first and the user can stop on the boundary naturally; the auto-append watcher
 * only fires once this card is scrolled to its bottom (a deliberate scroll-through). Tappable to
 * advance ONLY when it is the terminal card AND a next chapter exists; an interior boundary (the next
 * chapter is already loaded below) is a passive divider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextChapterBoundaryCard(
    finishedChapter: Chapter?,
    nextChapter: Chapter?,
    isTerminal: Boolean,
    screenHeightDb: Dp,
    onGoToNext: () -> Unit,
) {
    val canAdvance = isTerminal && nextChapter != null
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(screenHeightDb),
        color = MaterialTheme.colorScheme.background,
        onClick = onGoToNext,
        enabled = canAdvance,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            NextChapterContent(
                currentChapter = finishedChapter,
                nextChapter = nextChapter,
                isLoadingNext = false,
            )
        }
    }
}

/**
 * Shared inner content for the next-chapter affordance — used by both the paged-mode floating
 * [NextChapterOverlay] and the continuous-mode inline [NextChapterBoundaryCard].
 *
 * Typography/colors match native `NextChapterCard.kt:58-74`: current chapter in `primary` titleLarge,
 * next chapter in `secondary` titleLarge; a terminal boundary shows the "last chapter" message.
 */
@Composable
private fun NextChapterContent(
    currentChapter: Chapter?,
    nextChapter: Chapter?,
    isLoadingNext: Boolean,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Text(
            text = stringResource(Res.string.np_reader_you_are_in),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (currentChapter != null) {
            Text(
                text = chapterDisplayTitle(currentChapter),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (nextChapter != null) {
            Text(
                text = stringResource(Res.string.np_reader_going_to),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (isLoadingNext) {
                CircularProgressIndicator(modifier = Modifier.padding(top = spacing.xs))
            } else {
                Text(
                    text = chapterDisplayTitle(nextChapter),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        } else {
            Text(
                text = stringResource(Res.string.np_reader_no_next_chapter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReaderErrorPane(
    error: AppError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        Text(
            text = error.toUserMessage(),
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onRetry) {
            Text(stringResource(Res.string.retry))
        }
    }
}

@Composable
private fun chapterDisplayTitle(chapter: Chapter): String =
    chapter.name.ifBlank { chapter.number.ifBlank { stringResource(Res.string.reader_chapter_fallback) } }

/**
 * Subtitle line for the reader top bar: the chapter NUMBER (controls parity — native
 * `ControlOverlay.kt:141-146` renders `currentChapter.chapterNumber` as the bodySmall subtitle
 * under the manga name). Falls back to the chapter name, then the generic chapter label, only
 * when the source ships no number — native renders the raw number, which is non-empty in
 * practice, but the fallback keeps the subtitle line from going empty for number-less sources.
 */
@Composable
private fun chapterNumberSubtitle(chapter: Chapter): String =
    chapter.number.ifBlank { chapter.name.ifBlank { stringResource(Res.string.reader_chapter_fallback) } }

/**
 * True only when the user has actually scrolled to the BOTTOM of the stitched chapter strip in the
 * WEBTOON / CONTINUOUS_VERTICAL modes — used by [ReaderVerticalList] to auto-load the next chapter.
 *
 * The generic `isScrolledToTheEnd` (`presentation/common/componants/isScrolledToTheEnd.kt`) returns
 * true the instant the last item's index is merely *visible* — correct for paginated lists that
 * prefetch the next page, but WRONG for the reader: a manga page is typically taller than the
 * viewport, so "last item visible" fires the moment the last page's TOP edge scrolls in. On a short
 * chapter (e.g. 3 images) that auto-advanced to the next chapter the instant the last image appeared,
 * before it was read (the user-reported "starts image 3 → jumps to next chapter"). So the reader
 * needs its own stricter check: the last item must be the final one AND its bottom edge must have
 * reached the viewport end (fully scrolled through), AND the list must have actually been scrolled
 * ([canScrollBackward]) so a chapter that fits entirely on screen doesn't advance the instant it opens.
 */
private fun LazyListState.isScrolledToTheEnd(): Boolean {
    val info = layoutInfo
    val totalItems = info.totalItemsCount
    if (totalItems == 0) return false
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return false
    val atBottomOfLastItem = lastVisible.index == totalItems - 1 &&
        lastVisible.offset + lastVisible.size <= info.viewportEndOffset
    return atBottomOfLastItem && canScrollBackward
}

private fun pageIndicator(state: ReaderState): String? {
    if (!state.hasPages) return null
    // #5: show position WITHIN the active chapter segment (not across the whole appended feed), so a
    // continuous reader with appended chapters still reads "3 / 20", not "23 / 60".
    return "${state.activeChapterPageNumber} / ${state.activeChapterPageCount}"
}

// AppError → localized user-message mapping (UP-3 localization). `@Composable` so each arm
// resolves a `stringResource`; consumed by [ReaderErrorPane] (composable scope) and, via
// [rememberAppErrorMessageResolver], by the effects collector (which cannot call
// `stringResource` directly — see the hoisting note at the collector).
@Composable
private fun AppError.toUserMessage(): String = when (this) {
    is AppError.Network -> networkUserMessage()
    is AppError.Storage -> stringResource(Res.string.reader_error_storage)
    is AppError.Validation -> stringResource(Res.string.reader_error_validation)
    is AppError.Auth -> stringResource(Res.string.reader_error_auth)
    is AppError.Platform -> stringResource(Res.string.reader_error_platform)
    is AppError.Cancelled -> stringResource(Res.string.reader_error_cancelled)
    is AppError.Unexpected -> stringResource(Res.string.reader_error_unexpected)
}

// P1 parity: native distinguishes network failures by HTTP status code / transport failure
// (`State.kt` `httpStatusMessage` + `fromException`) rather than collapsing every [AppError.Network]
// into one string. Codes native does not name individually fall back to `reader_error_network`.
@Composable
private fun AppError.Network.networkUserMessage(): String = when (this) {
    is AppError.Network.NoConnectivity -> stringResource(Res.string.error_network_no_connectivity)
    is AppError.Network.Timeout -> stringResource(Res.string.error_network_timeout)
    is AppError.Network.Serialization -> stringResource(Res.string.reader_error_network)
    is AppError.Network.Http -> when (statusCode) {
        400 -> stringResource(Res.string.error_network_bad_request)
        401 -> stringResource(Res.string.error_network_unauthorized)
        403 -> stringResource(Res.string.error_network_forbidden)
        404 -> stringResource(Res.string.error_network_not_found)
        408 -> stringResource(Res.string.error_network_request_timeout)
        500 -> stringResource(Res.string.error_network_server)
        502 -> stringResource(Res.string.error_network_bad_gateway)
        503 -> stringResource(Res.string.error_network_service_unavailable)
        504 -> stringResource(Res.string.error_network_gateway_timeout)
        else -> stringResource(Res.string.reader_error_network)
    }
}

// Pre-resolves every AppError variant's localized message in composable scope and returns a
// plain lambda. The effects collector (a coroutine `collect {}`) cannot invoke `stringResource`
// directly, so the snackbar path captures this resolver instead (the established hoist-the-
// string pattern). All seven strings are resolved up-front; the lambda is a cheap when-lookup.
@Composable
private fun rememberAppErrorMessageResolver(): (AppError) -> String {
    val network = stringResource(Res.string.reader_error_network)
    // P1 parity: native distinguishes network failures by HTTP status code / transport failure
    // (`State.kt`); codes native does not name individually fall back to `reader_error_network`.
    val noConnectivity = stringResource(Res.string.error_network_no_connectivity)
    val timeout = stringResource(Res.string.error_network_timeout)
    val badRequest = stringResource(Res.string.error_network_bad_request)
    val unauthorized = stringResource(Res.string.error_network_unauthorized)
    val forbidden = stringResource(Res.string.error_network_forbidden)
    val notFound = stringResource(Res.string.error_network_not_found)
    val requestTimeout = stringResource(Res.string.error_network_request_timeout)
    val server = stringResource(Res.string.error_network_server)
    val badGateway = stringResource(Res.string.error_network_bad_gateway)
    val serviceUnavailable = stringResource(Res.string.error_network_service_unavailable)
    val gatewayTimeout = stringResource(Res.string.error_network_gateway_timeout)
    val storage = stringResource(Res.string.reader_error_storage)
    val validation = stringResource(Res.string.reader_error_validation)
    val auth = stringResource(Res.string.reader_error_auth)
    val platform = stringResource(Res.string.reader_error_platform)
    val cancelled = stringResource(Res.string.reader_error_cancelled)
    val unexpected = stringResource(Res.string.reader_error_unexpected)
    return { error ->
        when (error) {
            is AppError.Network -> when (error) {
                is AppError.Network.NoConnectivity -> noConnectivity
                is AppError.Network.Timeout -> timeout
                is AppError.Network.Serialization -> network
                is AppError.Network.Http -> when (error.statusCode) {
                    400 -> badRequest
                    401 -> unauthorized
                    403 -> forbidden
                    404 -> notFound
                    408 -> requestTimeout
                    500 -> server
                    502 -> badGateway
                    503 -> serviceUnavailable
                    504 -> gatewayTimeout
                    else -> network
                }
            }
            is AppError.Storage -> storage
            is AppError.Validation -> validation
            is AppError.Auth -> auth
            is AppError.Platform -> platform
            is AppError.Cancelled -> cancelled
            is AppError.Unexpected -> unexpected
        }
    }
}
