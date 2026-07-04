package me.manga.kira.ui.details

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import me.manga.kira.ui.components.VerticalFastScroller
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.util.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import kotlin.time.ExperimentalTime
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.presentation.details.AdultGateStep
import me.manga.kira.presentation.details.ChapterDownloadProgress
import me.manga.kira.presentation.details.ChapterFilterType
import me.manga.kira.presentation.details.ChapterSortType
import me.manga.kira.presentation.details.DetailsEffect
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.presentation.details.DetailsViewModel
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.downloads
import me.manga.kira.ui.generated.resources.downloaded
import me.manga.kira.ui.generated.resources.continue_string
import me.manga.kira.ui.generated.resources.action_open_in_browser
import me.manga.kira.ui.generated.resources.dropdown_button_refresh
import me.manga.kira.ui.generated.resources.add_library_title
import me.manga.kira.ui.generated.resources.chapters_count_format
import me.manga.kira.ui.generated.resources.details_chapter_selection_count
import me.manga.kira.ui.generated.resources.details_download_chapter
import me.manga.kira.ui.generated.resources.details_mark_read
import me.manga.kira.ui.generated.resources.details_mark_unread
import me.manga.kira.ui.generated.resources.download
import me.manga.kira.ui.generated.resources.mark_read
import me.manga.kira.ui.generated.resources.month_abbrev_apr
import me.manga.kira.ui.generated.resources.month_abbrev_aug
import me.manga.kira.ui.generated.resources.month_abbrev_dec
import me.manga.kira.ui.generated.resources.month_abbrev_feb
import me.manga.kira.ui.generated.resources.month_abbrev_jan
import me.manga.kira.ui.generated.resources.month_abbrev_jul
import me.manga.kira.ui.generated.resources.month_abbrev_jun
import me.manga.kira.ui.generated.resources.month_abbrev_mar
import me.manga.kira.ui.generated.resources.month_abbrev_may
import me.manga.kira.ui.generated.resources.month_abbrev_nov
import me.manga.kira.ui.generated.resources.month_abbrev_oct
import me.manga.kira.ui.generated.resources.month_abbrev_sep
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.details_error_network
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
import me.manga.kira.ui.generated.resources.details_error_storage
import me.manga.kira.ui.generated.resources.details_error_validation
import me.manga.kira.ui.generated.resources.details_error_auth
import me.manga.kira.ui.generated.resources.details_error_platform
import me.manga.kira.ui.generated.resources.details_error_cancelled
import me.manga.kira.ui.generated.resources.np_details_download_all
import me.manga.kira.ui.generated.resources.np_details_no_chapter_yet
import me.manga.kira.ui.generated.resources.np_details_today
import me.manga.kira.ui.generated.resources.np_details_yesterday
import me.manga.kira.ui.generated.resources.np_details_days_ago
// Parity-fix (pfix) Details strings — header action row, chapter filter/sort sheet, Resume FAB,
// remove-from-library confirm, per-chapter bookmark toggle (strings_pfix_details.xml).
import me.manga.kira.ui.generated.resources.action_bookmark
import me.manga.kira.ui.generated.resources.action_remove
import me.manga.kira.ui.generated.resources.action_downloading
import me.manga.kira.ui.generated.resources.contentDescription_filter
import me.manga.kira.ui.generated.resources.filter_all
import me.manga.kira.ui.generated.resources.filter_downloaded
import me.manga.kira.ui.generated.resources.filter_unread
import me.manga.kira.ui.generated.resources.filter_readed
import me.manga.kira.ui.generated.resources.filter_bookmarked
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_filter
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.generated.resources.sort_options_title
import me.manga.kira.ui.generated.resources.sort_by_label
import me.manga.kira.ui.generated.resources.sort_direction_label
import me.manga.kira.ui.generated.resources.sort_direction_ascending
import me.manga.kira.ui.generated.resources.sort_direction_descending
import me.manga.kira.ui.generated.resources.sort_type_id
import me.manga.kira.ui.generated.resources.sort_type_number
import me.manga.kira.ui.generated.resources.sort_type_date
import me.manga.kira.ui.generated.resources.sort_type_last_read_date
import me.manga.kira.ui.generated.resources.details_resume_chapter
import me.manga.kira.ui.generated.resources.details_resume_finished
import me.manga.kira.ui.generated.resources.details_resume_cd
import me.manga.kira.ui.generated.resources.details_bookmark_chapter
import me.manga.kira.ui.generated.resources.details_unbookmark_chapter
import me.manga.kira.ui.generated.resources.new_chapter
import me.manga.kira.ui.generated.resources.desc_newest_first
import me.manga.kira.ui.generated.resources.desc_oldest_first
import me.manga.kira.ui.generated.resources.np_details_genres_more
import me.manga.kira.ui.generated.resources.np_details_description_expand
import me.manga.kira.ui.generated.resources.np_details_description_collapse
import me.manga.kira.ui.generated.resources.np_details_title_copied
import me.manga.kira.ui.generated.resources.np_details_adult_icon_cd
// P2 MEDIUM parity-fix Details strings (strings_pfix_p2_details.xml): add-to-library long message
// + "Add to Library" confirm, relative chapter-date, error Help, multi-select bulk actions,
// download menu, top-bar overflow, total-downloaded-size banner.
import me.manga.kira.ui.generated.resources.details_add_library_message
import me.manga.kira.ui.generated.resources.details_confirm_add_to_library
import me.manga.kira.ui.generated.resources.details_chapter_date_today
import me.manga.kira.ui.generated.resources.details_chapter_date_yesterday
import me.manga.kira.ui.generated.resources.details_chapter_date_full
import me.manga.kira.ui.generated.resources.details_error_help
import me.manga.kira.ui.generated.resources.details_bookmark_all
import me.manga.kira.ui.generated.resources.details_mark_all_down_read
import me.manga.kira.ui.generated.resources.details_delete_downloaded
import me.manga.kira.ui.generated.resources.details_download_all_menu
import me.manga.kira.ui.generated.resources.details_custom_download
import me.manga.kira.ui.generated.resources.details_download_selected_format
import me.manga.kira.ui.generated.resources.details_cancel_selection
import me.manga.kira.ui.generated.resources.details_delete_all_downloaded
import me.manga.kira.ui.generated.resources.details_menu_share
import me.manga.kira.ui.generated.resources.details_cancel_all_downloads
import me.manga.kira.ui.generated.resources.details_more_options
import me.manga.kira.ui.generated.resources.details_downloaded_header
// PFIX-DLPROGRESS: live per-chapter download-progress cancel affordance content description
// (strings_pfix_dlprogress.xml). New key — not a duplicate of details_cancel_chapter_download.
import me.manga.kira.ui.generated.resources.pfix_dl_compressing
import me.manga.kira.ui.generated.resources.pfix_dl_downloading_format
import me.manga.kira.ui.generated.resources.pfix_dl_queued
import me.manga.kira.ui.generated.resources.pfix_dlprogress_cancel_chapter_download
import me.manga.kira.ui.generated.resources.pfix_dlsize_total_format
// P0-ADULT hard-block gate (native parity). "Content unavailable" header + Play-policy body,
// reused close/cancel/continue labels, the red ic_pluss18 18+ vector, and the anti_horny meme
// images (anti_horny_1..13 minus 10) split into the two MStep image pools.
import me.manga.kira.ui.generated.resources.adult_filter_removal_header
import me.manga.kira.ui.generated.resources.adult_filter_removal_title
import me.manga.kira.ui.generated.resources.close
import me.manga.kira.ui.generated.resources.ic_pluss18
import me.manga.kira.ui.generated.resources.anti_horny_1
import me.manga.kira.ui.generated.resources.anti_horny_2
import me.manga.kira.ui.generated.resources.anti_horny_3
import me.manga.kira.ui.generated.resources.anti_horny_4
import me.manga.kira.ui.generated.resources.anti_horny_5
import me.manga.kira.ui.generated.resources.anti_horny_6
import me.manga.kira.ui.generated.resources.anti_horny_7
import me.manga.kira.ui.generated.resources.anti_horny_8
import me.manga.kira.ui.generated.resources.anti_horny_9
import me.manga.kira.ui.generated.resources.anti_horny_11
import me.manga.kira.ui.generated.resources.anti_horny_12
import me.manga.kira.ui.generated.resources.anti_horny_13
import me.manga.kira.ui.generated.resources.details_delete_chapter

/**
 * Details screen — Compose entry point for the Details MVI slice.
 *
 * Mirrors the [me.manga.kira.ui.library.LibraryScreen] precedent (Phase 7.x for Library):
 *  - Stateful wrapper subscribes to the VM's state + effects, dispatches a one-shot
 *    [DetailsIntent.OnEnter] on first composition.
 *  - Stateless [DetailsScreenContent] does the rendering so previews and tests can feed canned
 *    state without spinning up a VM.
 *
 * Effects routed:
 *  - [DetailsEffect.NavigateBack] → [onNavigateBack]
 *  - [DetailsEffect.NavigateToReader] → [onNavigateToReader]
 *  - [DetailsEffect.ShowError] → snackbar host
 *
 * Scope discipline (same caveat as the Library slice): this is the minimum-viable screen — cover
 * placeholder (no Coil yet), title/status/author header, description text, genre chip row,
 * scrollable chapter list. Image loading, share/bookmark/download actions, sort/filter sheets,
 * and visual parity with the legacy `MangaDetailsScreen` land in subsequent micro-slices. The
 * legacy screen remains the user-facing binding in `:composeApp` until Phase 8.x ships the
 * guarded debug nav route to this one.
 */
@Composable
fun DetailsScreen(
    viewModel: DetailsViewModel,
    manga: Manga,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (Manga, Chapter) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    modifier: Modifier = Modifier,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit = onOpenInWebView,
) {
    val state by viewModel.state.collectAsState()
    // Full-tuple entry — dispatch OnEnter once per identity (api + language + title). The reducer
    // is idempotent on re-entry for the same triple; the LaunchedEffect key change covers
    // navigation to a sibling Details screen via the same VM (rare, but possible with deep links).
    LaunchedEffect(manga.api, manga.language, manga.title) {
        viewModel.submit(DetailsIntent.OnEnter(manga))
    }
    DetailsScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateBack = onNavigateBack,
        onNavigateToReader = onNavigateToReader,
        onNavigateToDownloads = onNavigateToDownloads,
        onOpenInWebView = onOpenInWebView,
        onSolveCloudflareChallenge = onSolveCloudflareChallenge,
        modifier = modifier,
    )
}

/**
 * URL-only entry point — used by the legacy `Screen.MangaDetails(mangaUrl, api)` route after the
 * Phase 9.x.mangadetails.swap (Slice 4) flip. The four legacy caller nav sites (Home, Library,
 * History, Updates) only carry `(mangaUrl, api)`; the rework VM's `OnEnterByUrl` intent builds
 * a tentative `Manga` placeholder and enriches it from the fetched `MangaDetails` on success.
 *
 * ADR-6 (Slice 4): one VM-local handler resolves the args-shape mismatch — the 4 caller sites
 * stay untouched. ADR-7: full-tuple and URL-only entries coexist; the screen renders both via
 * the shared stateless [DetailsScreenContent], differing only in which intent the wrapper
 * dispatches in its [LaunchedEffect].
 */
@Composable
fun DetailsScreenByUrl(
    viewModel: DetailsViewModel,
    api: String,
    mangaUrl: String,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (Manga, Chapter) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    modifier: Modifier = Modifier,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit = onOpenInWebView,
) {
    val state by viewModel.state.collectAsState()
    // URL-only entry — dispatch OnEnterByUrl once per (api, url). The reducer guards on the
    // same pair so a re-fire from configuration change (or a same-URL re-entry through a parent
    // back-stack pop) is a no-op. After the first fetch, state.manga.url matches the key here,
    // so this LaunchedEffect's recomposition path remains idempotent.
    LaunchedEffect(api, mangaUrl) {
        viewModel.submit(DetailsIntent.OnEnterByUrl(api = api, mangaUrl = mangaUrl))
    }
    DetailsScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateBack = onNavigateBack,
        onNavigateToReader = onNavigateToReader,
        onNavigateToDownloads = onNavigateToDownloads,
        onOpenInWebView = onOpenInWebView,
        onSolveCloudflareChallenge = onSolveCloudflareChallenge,
        modifier = modifier,
    )
}

/**
 * Stateless host — split from [DetailsScreen] / [DetailsScreenByUrl] so previews and tests can
 * feed canned state without spinning up a real ViewModel. SRP: "wire to VM" vs "render state"
 * are separate responsibilities.
 *
 * The currently-rendered identity lives in [DetailsState.manga] and is set by the reducer when
 * processing the wrapper's `OnEnter` / `OnEnterByUrl` intent. Rendering reads from state so a
 * config-change replay on a fresh host doesn't flash the placeholder — and so this stateless
 * host doesn't need a [Manga] parameter from the caller (which would force one of the two
 * entry shapes to fake the missing fields).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailsScreenContent(
    state: DetailsState,
    effects: Flow<DetailsEffect>,
    onIntent: (DetailsIntent) -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToReader: (Manga, Chapter) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onOpenInWebView: (url: String, api: String) -> Unit,
    modifier: Modifier = Modifier,
    onSolveCloudflareChallenge: (url: String, api: String) -> Unit = onOpenInWebView,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // GAP-DET-12: title-copied confirmation. The long-press copy lives down in DetailsHeader
    // (where the clipboard manager is), but the SnackbarHostState is owned here — so resolve the
    // localized message in composable scope and hand the header a callback that launches the
    // snackbar on this screen's coroutine scope (legacy `R.string.title_copied` toast parity).
    val titleCopiedMessage = stringResource(Res.string.np_details_title_copied)
    val snackbarScope = rememberCoroutineScope()
    val onTitleCopied: () -> Unit = {
        snackbarScope.launch { snackbarHostState.showSnackbar(titleCopiedMessage) }
    }

    // P0-ADULT hard-block gate (native parity). The gate is a compliance block, NOT a reveal-on-
    // confirm age-gate: an adult manga's cover/chapters are NEVER shown. The gate step lives in
    // MVI state (`state.adultGateStep`, driven by the VM from the authoritative fetched genres) so
    // it survives configuration change / process death — there is deliberately NO `:ui`-local
    // "adultConfirmed reveal" flag any more (removed; it inverted native's legal behaviour by
    // displaying content native explicitly blocks). While the gate is active the cover stays
    // blurred AND the chapter body is suppressed entirely (see the `when` below), mirroring native
    // `MangaDetailsScreen`, which renders `DetailsContent` only in the `DialogState.None` branch.
    val shouldBlurCover = state.isAdultGateActive

    // Add-bookmark confirm dialog visibility — legacy parity with `showAddBookmarkAlert`. Per
    // §48.6 (and ADR-2 on this slice): dialog-style one-shot UI flags live in `remember`, not in
    // MVI state, because they're within-frame affordances that don't need to survive process
    // death. Keyed on the in-state manga URL so a different title's bookmark gets its own prompt
    // and so the dialog auto-resets if the screen is reused for a different identity (e.g. a
    // search-result navigation). The dialog is only raised on the "not in library → add" path;
    // the reverse path (already in library → remove) dispatches directly without confirmation,
    // matching the legacy `HomeViewModel.toggleManga` semantics (the legacy dialog only gated
    // the add direction; remove was direct).
    var showAddBookmarkAlert by remember(state.manga?.url) { mutableStateOf(false) }

    // Chapter filter/sort bottom-sheet visibility — native `LibraryMangaScreen.showSheet`. A
    // within-frame UI affordance, so it lives in `:ui` `remember` (per §48.6), not MVI state. Keyed
    // on the manga URL so it auto-dismisses if the screen is reused for a different identity.
    var showFilterSheet by remember(state.manga?.url) { mutableStateOf(false) }

    // Shared bookmark-toggle entry point — used by the header action row (native HeaderSection
    // ActionButton). Un-bookmarked tap raises the first-time-add confirm dialog (legacy parity);
    // already-bookmarked tap toggles immediately (legacy direct-remove). Hoisted here so the header
    // row and any other surface dispatch the same rule.
    val onBookmarkClick: () -> Unit = {
        if (state.isInLibrary) {
            onIntent(DetailsIntent.OnToggleInLibrary)
        } else {
            showAddBookmarkAlert = true
        }
    }

    // Shared chapter-list scroll state — hoisted here (was previously local to DetailsBody) so the
    // Resume FAB can mirror native's expand-on-scroll-up / collapse-on-scroll-down behaviour
    // (LibraryMangaScreen.kt:153-175) while the same state still drives the body's LazyColumn +
    // fast-scroller. Created once and passed down.
    val listState = rememberLazyListState()
    // Resume FAB expand/collapse — native tracks scroll *direction* (expand when scrolling up,
    // collapse when scrolling down) via a snapshotFlow over (firstVisibleItemIndex,
    // firstVisibleItemScrollOffset). Default expanded = true (native `isFloatExpanded = true`).
    var resumeFabExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        var prevIndex = listState.firstVisibleItemIndex
        var prevOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (currentIndex, currentOffset) ->
                if (currentIndex > prevIndex || (currentIndex == prevIndex && currentOffset > prevOffset)) {
                    resumeFabExpanded = false
                } else if (currentIndex < prevIndex || (currentIndex == prevIndex && currentOffset < prevOffset)) {
                    resumeFabExpanded = true
                }
                prevIndex = currentIndex
                prevOffset = currentOffset
            }
    }

    // M-1 / L-6 parallax backdrop offset — native parity (DetailsContent.kt:62-73 /
    // LibraryMangaScreen.kt:130-141): the 250dp blurred backdrop band translates up at half the
    // list-scroll speed (`parallaxOffset = scrollOffset / 2`, capped at the band height so the
    // backdrop never scrolls fully off and reveals the bare surface). Computed in a derivedStateOf
    // over the first-item scroll offset; the value is handed to the backdrop as a lambda and read
    // inside its graphicsLayer at DRAW time, so per-frame scrolling never triggers recomposition.
    val density = LocalDensity.current
    val headerBandHeightPx = with(density) { DETAILS_BACKDROP_HEIGHT.toPx() }
    val parallaxOffset by remember(headerBandHeightPx) {
        derivedStateOf {
            val raw = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(headerBandHeightPx)
            } else {
                headerBandHeightPx
            }
            raw / 2f
        }
    }

    // L-9 parity (LibraryMangaScreen.kt:178-184): while in chapter multi-select mode, system back
    // first exits selection mode instead of popping the screen. Enabled only while there is a
    // selection; otherwise the handler is inert and back propagates to the nav host (pop). On
    // platforms where BackHandler is a no-op (iOS/desktop), the ChapterSelectionBar Close button
    // remains the selection-exit affordance.
    BackHandler(enabled = state.isInChapterSelectionMode) {
        onIntent(DetailsIntent.OnSelectionClear)
    }

    // Hoisted error strings — `stringResource` cannot run inside the `effects.collect` collector
    // below (it's a non-composable suspend lambda), so the AppError→message mapping is resolved
    // here in composable scope and captured by the LaunchedEffect.
    val errorMessages = rememberAppErrorMessages()
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                DetailsEffect.NavigateBack -> onNavigateBack()
                is DetailsEffect.NavigateToReader -> onNavigateToReader(effect.manga, effect.chapter)
                is DetailsEffect.ShowError -> snackbarScope.launch { snackbarHostState.showSnackbar(errorMessages.messageFor(effect.error)) }
                DetailsEffect.NavigateToDownloads -> onNavigateToDownloads()
                is DetailsEffect.NavigateToWebView -> onOpenInWebView(effect.url, effect.api)
                // 403 Cloudflare interstitial → route to the WebView challenge-solver (legacy
                // Handle403Error parity, bug #2). The `:composeApp` adapter navigates to the
                // WebView and auto-retries the fetch when control returns to Details, so the
                // re-fetch runs with the freshly-minted session cookies.
                is DetailsEffect.SolveCloudflareChallenge -> onSolveCloudflareChallenge(effect.url, effect.api)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            DetailsTopBar(
                // Title fallback chain: fetched details title (authoritative) → in-state Manga
                // title (set by OnEnter with the full-tuple nav arg; blank for URL-only OnEnterByUrl
                // entries until the fetch lands) → empty string. The brief blank window on URL-only
                // entries (~200-500ms while the fetch resolves) is acceptable parity with the
                // legacy screen, which also rendered a placeholder top-bar until its own fetch
                // resolved (ADR-6 §253: top-bar title shows briefly empty before resolving).
                title = state.details?.title
                    ?: state.manga?.title.orEmpty(),
                onBack = { onIntent(DetailsIntent.OnBackClick) },
                onRefresh = { onIntent(DetailsIntent.OnRetry) },
                refreshEnabled = !state.isLoading,
                // Filter/sort sheet trigger — native `MangaTopAppBar` FilterList icon
                // (MangaTopAppBar.kt:61-65). Shown only once details have loaded (there are no
                // chapters to filter before then).
                showFilter = state.hasDetails && !state.isAdultGateActive,
                onFilterClick = { showFilterSheet = true },
                // Downloads-queue button retained (rework KMP extra, ADR-3 — not flagged by the
                // audit; native has no per-screen downloads-queue button but keeps a download-all).
                onDownloadClick = { onIntent(DetailsIntent.OnDownloadClick) },
                // L-7 top-bar overflow + cancel-all (native MangaTopAppBar). The overflow's
                // delete-all-downloaded and the Stop cancel-all button are gated on in-library
                // (the overflow is only meaningful for a saved manga); the cancel-all Stop button
                // appears only while a download is active.
                showOverflow = state.hasDetails && state.isInLibrary && !state.isAdultGateActive,
                showCancelAll = state.isDownloadingAny,
                onCancelAllDownloads = { onIntent(DetailsIntent.OnCancelAllDownloads) },
                onDeleteAllDownloads = { onIntent(DetailsIntent.OnDeleteAllDownloads) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Resume FAB — native LibraryMangaScreen.kt:225-235. Shown only for an in-library manga with
        // loaded chapters and outside the adult gate (the gate suppresses the whole body). Computes
        // the first-unread chapter from the displayed (filtered/sorted) list and jumps into it.
        floatingActionButton = {
            if (state.isInLibrary && state.hasDetails && !state.isAdultGateActive) {
                ResumeFab(
                    firstUnread = state.firstUnreadChapter,
                    expanded = resumeFabExpanded,
                    onClick = {
                        state.firstUnreadChapter?.let { onIntent(DetailsIntent.OnChapterClick(it)) }
                    },
                )
            }
        },
    ) { padding ->
        // Native parity (DetailsContent.kt:116-123 / LibraryMangaScreen.kt:251-259): the whole
        // screen is a Box; the 250dp blurred parallax backdrop is painted FIRST (behind everything,
        // including under the transparent top bar so it shows through), then the content is offset
        // by only the top inset. The Box itself takes NO top padding so the backdrop band starts at
        // the very top of the screen behind the see-through app bar.
        Box(modifier = Modifier.fillMaxSize()) {
            // Screen-level parallax backdrop band — only drawn once details (and a cover URL) exist
            // and the adult gate isn't active (an adult manga never reveals its cover). Sits behind
            // the content + the transparent top bar.
            val backdropDetails = state.details
            if (backdropDetails != null && !state.isAdultGateActive) {
                DetailsParallaxBackdrop(
                    coverUrl = backdropDetails.coverUrl,
                    // Pass the offset as a lambda so the per-frame value is read at draw time
                    // (inside the backdrop's graphicsLayer), never in this content scope.
                    parallaxOffset = { parallaxOffset },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding()),
            ) {
            // Capture nullable state props into locals — public-API smart casts across modules
            // are not allowed by the Kotlin compiler.
            val details = state.details
            val error = state.error
            when {
                // P3-LOW parity (LoadingScreen.kt:18-31): native's full-screen LoadingScreen is a
                // centered CircularProgressIndicator tinted `colorScheme.inversePrimary` (no branding
                // beyond the tint). Match the tint here; the centered-spinner layout already mirrors
                // native's Box(Center) { Column { CircularProgressIndicator } }.
                state.isInitialLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.inversePrimary,
                )
                error != null && !state.hasDetails -> DetailsErrorPane(
                    error = error,
                    onRetry = { onIntent(DetailsIntent.OnRetry) },
                    onOpenInWebView = { onIntent(DetailsIntent.OnOpenInWebView) },
                    onBack = { onIntent(DetailsIntent.OnBackClick) },
                    modifier = Modifier.align(Alignment.Center),
                )
                // P0-ADULT: while the hard-block gate is active the chapter body is NEVER rendered
                // (native parity — `DetailsContent` shows only in `DialogState.None`). The gate
                // dialog chain below is the only thing the user sees over an empty body, and every
                // dialog path back-navigates, so adult content can never be reached. This branch
                // sits ABOVE `details != null` so a fetched adult manga's chapters stay suppressed.
                state.isAdultGateActive -> Unit
                details != null -> DetailsBody(
                    details = details,
                    listState = listState,
                    chapters = state.displayChapters,
                    // Redesign 2026-06: header CTA target + action — identical to the Resume FAB
                    // (same firstUnreadChapter, same OnChapterClick dispatch). When finished
                    // (firstUnreadChapter == null) the CTA disables itself, so the lambda is inert.
                    firstUnread = state.firstUnreadChapter,
                    onResumeClick = {
                        state.firstUnreadChapter?.let { onIntent(DetailsIntent.OnChapterClick(it)) }
                    },
                    // L-2 pull-to-refresh — native LibraryMangaScreen pull-refresh wired to
                    // refreshChapters(); the rework re-fetches via OnRetry. The spinner reflects the
                    // in-flight fetch (state.isLoading); a stale list stays visible while it runs.
                    isRefreshing = state.isLoading,
                    onRefresh = { onIntent(DetailsIntent.OnRetry) },
                    shouldBlurCover = shouldBlurCover,
                    isInLibrary = state.isInLibrary,
                    sortAscending = state.sortAscending,
                    selectedChapterUrls = state.selectedChapterUrls,
                    // PFIX-DLPROGRESS: pass the live per-chapter download status+progress map
                    // (url → state+progress) so each row renders live percent during download and
                    // flips to downloaded the moment Room writes isDownloaded=1 (native parity),
                    // instead of the old boolean "is downloading" membership set.
                    chapterDownloads = state.chapterDownloads,
                    totalDownloadedSizeLabel = state.totalDownloadedSizeLabel,
                    downloadedChapterCount = state.downloadedChapterCount,
                    isDownloadingAll = state.isDownloadingAny,
                    onChapterClick = { chapter ->
                        // In selection mode a tap toggles membership; otherwise it opens the reader.
                        if (state.isInChapterSelectionMode) {
                            onIntent(DetailsIntent.OnSelectionToggle(chapter))
                        } else {
                            onIntent(DetailsIntent.OnChapterClick(chapter))
                        }
                    },
                    onChapterLongClick = { chapter -> onIntent(DetailsIntent.OnChapterLongClick(chapter)) },
                    onToggleChapterRead = { chapter -> onIntent(DetailsIntent.OnToggleChapterRead(chapter)) },
                    onToggleChapterBookmark = { chapter -> onIntent(DetailsIntent.OnToggleChapterBookmark(chapter)) },
                    onDownloadChapter = { chapter -> onIntent(DetailsIntent.OnDownloadChapter(chapter)) },
                    onCancelChapterDownload = { chapter -> onIntent(DetailsIntent.OnCancelChapterDownload(chapter)) },
                    onDeleteChapter = { chapter -> onIntent(DetailsIntent.OnDeleteChapter(chapter)) },
                    onRequestAddBookmark = { showAddBookmarkAlert = true },
                    onDownloadAllClick = { onIntent(DetailsIntent.OnDownloadAllClick) },
                    // L-8 "Custom download" — native's discoverable custom-download path enters the
                    // checkbox multi-select mode. The rework ties selection mode to a non-empty set,
                    // so this enters it by selecting the first displayed chapter (long-click parity),
                    // surfacing the multi-select bar with its download/bookmark/delete actions.
                    onCustomDownloadClick = {
                        state.displayChapters.firstOrNull()?.let {
                            onIntent(DetailsIntent.OnChapterLongClick(it))
                        }
                    },
                    onBookmarkClick = onBookmarkClick,
                    onOpenInWebViewClick = { onIntent(DetailsIntent.OnOpenInWebView) },
                    onToggleSortDirection = { onIntent(DetailsIntent.OnToggleSortDirection) },
                    onTitleCopied = onTitleCopied,
                )
                else -> Unit
            }

            // Multi-select action bar — overlaid at the bottom while in selection mode. L-4 parity:
            // download + bookmark-all + mark-down-read (single-selection only) + delete-downloaded
            // (only when all selected are downloaded) + mark-read + clear, with the selected count.
            if (state.isInChapterSelectionMode) {
                ChapterSelectionBar(
                    selectedCount = state.selectedChapterUrls.size,
                    showMarkDownRead = state.selectedChapterUrls.size == 1,
                    showDeleteDownloaded = state.isSelectionAllDownloaded,
                    onDownload = { onIntent(DetailsIntent.OnDownloadSelected) },
                    onBookmarkAll = { onIntent(DetailsIntent.OnBookmarkSelected) },
                    onMarkDownRead = { onIntent(DetailsIntent.OnMarkSelectedDownRead) },
                    onDeleteDownloaded = { onIntent(DetailsIntent.OnDeleteSelectedDownloads) },
                    onMarkRead = { onIntent(DetailsIntent.OnMarkSelectedRead) },
                    onClear = { onIntent(DetailsIntent.OnSelectionClear) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }

            // P0-ADULT hard-block gate dialog chain (native parity). Driven by `state.adultGateStep`
            // (MVI state). NO path here reveals the content — every path either back-navigates or
            // advances to another non-revealing gate step that itself only ever back-navigates:
            //  - AdultWarning → "Content unavailable" dialog (red ic_pluss18 + Play-policy apology).
            //    Matches native EXACTLY: BOTH "Close" (confirm) AND "Cancel" (dismiss) AND outside-tap
            //    back-navigate (OnAdultGateBack). Native wires the warning's onConfirm to MStep1 but
            //    the dialog's "Close" button calls onDismiss (→ onBackClick), so onConfirm is never
            //    invoked; the only reachable warning outcome is back-navigation. We mirror that by
            //    wiring "Close" (onContinue) to OnAdultGateBack too, leaving OnAdultWarningContinue /
            //    MStep1 / MStep2 as native-parallel dead code (kept, never reached from the warning).
            //  - MStep1 → meme dialog (imgs1, showContinue = true). Continue → MStep2; Close → back.
            //    UNREACHABLE (native-parallel dead code): retained but never entered from the warning.
            //  - MStep2 → meme dialog (imgs2, showContinue = false, no Continue). Any dismiss → back.
            //    UNREACHABLE (native-parallel dead code): retained but never entered from the warning.
            //  - None → no dialog (non-adult manga).
            when (state.adultGateStep) {
                AdultGateStep.AdultWarning -> AdultConfirmationDialog(
                    // Native parity: "Close" back-navigates (native's Close → onDismiss → onBackClick),
                    // so OnAdultWarningContinue is never dispatched and MStep1/MStep2 stay unreachable.
                    onContinue = { onIntent(DetailsIntent.OnAdultGateBack) },
                    onGoBack = { onIntent(DetailsIntent.OnAdultGateBack) },
                )
                AdultGateStep.MStep1 -> MConfirmationDialog(
                    images = ADULT_MEME_IMAGES_STEP1,
                    showContinue = true,
                    onConfirm = { onIntent(DetailsIntent.OnAdultStep1Continue) },
                    onDismiss = { onIntent(DetailsIntent.OnAdultGateBack) },
                )
                AdultGateStep.MStep2 -> MConfirmationDialog(
                    images = ADULT_MEME_IMAGES_STEP2,
                    showContinue = false,
                    // showContinue = false → no Continue button rendered; onConfirm is never
                    // invoked, but it back-navigates anyway to mirror native exactly.
                    onConfirm = { onIntent(DetailsIntent.OnAdultStep2Dismiss) },
                    onDismiss = { onIntent(DetailsIntent.OnAdultStep2Dismiss) },
                )
                AdultGateStep.None -> Unit
            }

            if (showAddBookmarkAlert) {
                AddBookmarkConfirmDialog(
                    onConfirm = {
                        showAddBookmarkAlert = false
                        onIntent(DetailsIntent.OnToggleInLibrary)
                    },
                    onDismiss = { showAddBookmarkAlert = false },
                )
            }

            // Chapter filter/sort bottom sheet — native `CustomFilterBottomSheet` with Filter + Sort
            // tabs (LibraryMangaScreen.kt:386-458). Shown when the user taps the top-bar FilterList
            // icon. Operates on MVI filter/sort state; the rendered list re-derives reactively.
            if (showFilterSheet) {
                ChapterFilterSortSheet(
                    selectedFilter = state.chapterFilter,
                    selectedSort = state.chapterSort,
                    sortAscending = state.sortAscending,
                    onFilterSelected = { onIntent(DetailsIntent.OnSetChapterFilter(it)) },
                    onSortSelected = { onIntent(DetailsIntent.OnSetChapterSort(it)) },
                    onSortDirectionChange = { onIntent(DetailsIntent.OnToggleSortDirection) },
                    onDismiss = { showFilterSheet = false },
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsTopBar(
    title: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean,
    showFilter: Boolean,
    onFilterClick: () -> Unit,
    onDownloadClick: () -> Unit,
    showOverflow: Boolean,
    showCancelAll: Boolean,
    onCancelAllDownloads: () -> Unit,
    onDeleteAllDownloads: () -> Unit,
) {
    var overflowExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        // Native parity (DetailsContent.kt:102-111 / MangaTopAppBar.kt): a transparent container so
        // the blurred parallax backdrop shows through, with a 20sp Normal-weight title.
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            KiraIconButton(
                icon = KiraIcons.Back,
                contentDescription = stringResource(Res.string.back),
                onClick = onBack,
            )
        },
        actions = {
            // L-7 cancel-all-downloads Stop button — native `MangaTopAppBar` (MangaTopAppBar.kt:51-59)
            // shows a Stop icon with an error tint, only while a bulk/any download is running.
            if (showCancelAll) {
                KiraIconButton(
                    icon = Icons.Filled.Stop,
                    contentDescription = stringResource(Res.string.details_cancel_all_downloads),
                    onClick = onCancelAllDownloads,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            // Filter/sort — native `MangaTopAppBar` FilterList icon (MangaTopAppBar.kt:61-65) opens
            // the chapter filter/sort bottom sheet.
            if (showFilter) {
                KiraIconButton(
                    icon = Icons.Filled.FilterList,
                    contentDescription = stringResource(Res.string.contentDescription_filter),
                    onClick = onFilterClick,
                )
            }
            // Downloads — routes to the rework Downloads screen. ADR-3 + ADR-4: top-bar placement and
            // no isInLibrary gate (the rework Downloads screen renders its own empty state). This is
            // a rework KMP extra (a downloads *queue* shortcut), distinct from the header's
            // download-*all* action; not flagged by the audit.
            KiraIconButton(
                icon = KiraIcons.Download,
                contentDescription = stringResource(Res.string.downloads),
                onClick = onDownloadClick,
            )
            // Refresh — disabled while a fetch is in flight so tapping during loading doesn't
            // stack a second concurrent fetch. (Native nests Refresh inside the overflow; the
            // rework keeps it as a direct icon — an unflagged KMP affordance — AND also lists it
            // in the overflow below for native menu parity.)
            KiraIconButton(
                icon = KiraIcons.Refresh,
                contentDescription = stringResource(Res.string.dropdown_button_refresh),
                onClick = onRefresh,
                enabled = refreshEnabled,
            )
            // L-7 overflow — native `MangaTopAppBar` MoreVert menu (MangaTopAppBar.kt:67-97):
            // Delete all downloaded chapters / Refresh / Share. Shown only for an in-library manga.
            if (showOverflow) {
                KiraIconButton(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = stringResource(Res.string.details_more_options),
                    onClick = { overflowExpanded = true },
                )
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.details_delete_all_downloaded)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            overflowExpanded = false
                            onDeleteAllDownloads()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.dropdown_button_refresh)) },
                        leadingIcon = { Icon(KiraIcons.Refresh, contentDescription = null) },
                        onClick = {
                            overflowExpanded = false
                            onRefresh()
                        },
                    )
                    // Share — native menu item is a no-op stub (MangaTopAppBar.kt:91-96); kept as a
                    // disabled-effect entry for native menu parity. A real share action is a
                    // cross-cutting platform-share concern (recorded as NEEDS CROSS-CUTTING CHANGE).
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.details_menu_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = { overflowExpanded = false },
                    )
                }
            }
        },
    )
}

@Composable
private fun DetailsErrorPane(
    error: AppError,
    onRetry: () -> Unit,
    onOpenInWebView: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val errorMessages = rememberAppErrorMessages()
    // M-9 parity: native ErrorScreen exposes Retry / Open-in-browser / Help / Back (the Help action
    // opens a support video dialog — see DetailsHelpDialog KDoc). The Help video itself relies on a
    // platform video player; the `:ui`-portable Help here is an informational dialog with an
    // open-help-in-browser escape hatch (the video-player wiring is recorded as cross-cutting).
    var showHelp by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.padding(spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = errorMessages.messageFor(error),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        // Retry — native ErrorScreen primary action.
        Button(onClick = onRetry) { Text(stringResource(Res.string.retry)) }
        // Open-in-browser — native `onOpenInBrowser`. Same DetailsIntent.OnOpenInWebView as the
        // header open-in-browser button (ADR-5); also the Cloudflare-challenge escape hatch.
        OutlinedButton(onClick = onOpenInWebView) {
            Text(stringResource(Res.string.action_open_in_browser))
        }
        // Help — native `onHelp` (HelpVideoDialog support entry point).
        OutlinedButton(onClick = { showHelp = true }) {
            Text(stringResource(Res.string.details_error_help))
        }
        // Back — native ErrorScreen explicit `onBack`. Dispatches the same OnBackClick the top-bar
        // back arrow uses.
        TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) }
    }
    if (showHelp) {
        DetailsHelpDialog(
            onOpenHelp = {
                showHelp = false
                onOpenInWebView()
            },
            onDismiss = { showHelp = false },
        )
    }
}

/**
 * Error-state Help dialog — `:ui`-portable stand-in for native's `HelpVideoDialog` (M-9). Native's
 * dialog streams a support video via an Android `VideoView`; a multiplatform video player is a
 * `:composeApp` `VideoPlayerSlot` expect/actual (out of this slice's scope), so the in-`:ui` Help
 * surface presents the support message with an "open help" affordance that routes through the same
 * WebView escape hatch. Embedding the actual help video is recorded as NEEDS CROSS-CUTTING CHANGE.
 */
@Composable
private fun DetailsHelpDialog(
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.details_error_help)) },
        text = { Text(stringResource(Res.string.details_error_network)) },
        confirmButton = {
            TextButton(onClick = onOpenHelp) {
                Text(stringResource(Res.string.action_open_in_browser))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsBody(
    details: MangaDetails,
    listState: LazyListState,
    chapters: List<Chapter>,
    // Redesign 2026-06: the header's prominent reading CTA target + action — the same resume target
    // (DetailsState.firstUnreadChapter) and click the Resume FAB uses, threaded down to DetailsHeader.
    firstUnread: Chapter?,
    onResumeClick: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    shouldBlurCover: Boolean,
    isInLibrary: Boolean,
    sortAscending: Boolean,
    selectedChapterUrls: Set<String>,
    chapterDownloads: Map<String, ChapterDownloadProgress>,
    // Native size display (TotalSizeDisplay): the formatted total on-disk size of all downloaded
    // chapters ("150.5 MB") + how many are downloaded, for the per-manga header above the list.
    // Null total = nothing downloaded / no sizes known yet (the header line is hidden).
    totalDownloadedSizeLabel: String?,
    downloadedChapterCount: Int,
    isDownloadingAll: Boolean,
    onChapterClick: (Chapter) -> Unit,
    onChapterLongClick: (Chapter) -> Unit,
    onToggleChapterRead: (Chapter) -> Unit,
    onToggleChapterBookmark: (Chapter) -> Unit,
    onDownloadChapter: (Chapter) -> Unit,
    onCancelChapterDownload: (Chapter) -> Unit,
    onDeleteChapter: (Chapter) -> Unit,
    onRequestAddBookmark: () -> Unit,
    onDownloadAllClick: () -> Unit,
    onCustomDownloadClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onOpenInWebViewClick: () -> Unit,
    onToggleSortDirection: () -> Unit,
    onTitleCopied: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // `chapters` arrives pre-filtered + pre-sorted + de-duped by url from
    // [DetailsState.displayChapters] (the single chokepoint for what the list + fast-scroller
    // measure), so no further transformation is needed here. The `listState` is hoisted from the
    // caller so the Resume FAB can mirror the scroll-direction-driven expand/collapse.
    //
    // L-2 pull-to-refresh — native LibraryMangaScreen pull-refresh container around the chapter
    // list. The Material3 PullToRefreshBox dispatches [onRefresh] on pull and shows the spinner
    // while [isRefreshing].
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    VerticalFastScroller(
        listState = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item("header") {
                DetailsHeader(
                    details = details,
                    shouldBlurCover = shouldBlurCover,
                    isInLibrary = isInLibrary,
                    isDownloadingAll = isDownloadingAll,
                    firstUnread = firstUnread,
                    onResumeClick = onResumeClick,
                    onBookmarkClick = onBookmarkClick,
                    onDownloadAllClick = {
                        // Native parity (HeaderSection.kt:135 / ActionsRow.kt:42): tapping the
                        // download-all action while NOT saved opens the add-to-library prompt
                        // instead of enqueuing.
                        if (isInLibrary) onDownloadAllClick() else onRequestAddBookmark()
                    },
                    onCustomDownloadClick = onCustomDownloadClick,
                    onOpenInWebViewClick = onOpenInWebViewClick,
                    onTitleCopied = onTitleCopied,
                )
            }
            if (details.description.isNotBlank()) {
                item("description") {
                    ExpandableDescription(description = details.description)
                }
            }
            if (details.genres.isNotEmpty()) {
                item("genres") { GenreChipRow(genres = details.genres) }
            }
            item("chapters-header") {
                // Chapters count + inline sort-direction toggle — native LibraryMangaScreen.kt:299-320
                // (a Row(SpaceBetween) with the "N Chapters" label and a KeyboardDoubleArrowDown/Up
                // IconButton).
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(Res.string.chapters_count_format, chapters.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        KiraIconButton(
                            icon = if (sortAscending) {
                                Icons.Outlined.KeyboardDoubleArrowUp
                            } else {
                                Icons.Outlined.KeyboardDoubleArrowDown
                            },
                            contentDescription = if (sortAscending) {
                                stringResource(Res.string.desc_oldest_first)
                            } else {
                                stringResource(Res.string.desc_newest_first)
                            },
                            onClick = onToggleSortDirection,
                        )
                    }
                    // Native TotalSizeDisplay parity: "<total size> • <N> downloaded", shown only
                    // when at least one chapter is downloaded with a known size.
                    if (totalDownloadedSizeLabel != null) {
                        Text(
                            text = stringResource(
                                Res.string.pfix_dlsize_total_format,
                                totalDownloadedSizeLabel,
                                downloadedChapterCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            items(
                items = chapters,
                key = { chapter -> chapter.url },
            ) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    isSelected = chapter.url in selectedChapterUrls,
                    download = chapterDownloads[chapter.url],
                    isInLibrary = isInLibrary,
                    onClick = { onChapterClick(chapter) },
                    onLongClick = { onChapterLongClick(chapter) },
                    onToggleRead = { onToggleChapterRead(chapter) },
                    onToggleBookmark = { onToggleChapterBookmark(chapter) },
                    onDownload = { onDownloadChapter(chapter) },
                    onCancelDownload = { onCancelChapterDownload(chapter) },
                    onDelete = { onDeleteChapter(chapter) },
                    onRequestAddBookmark = onRequestAddBookmark,
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailsHeader(
    details: MangaDetails,
    shouldBlurCover: Boolean,
    isInLibrary: Boolean,
    isDownloadingAll: Boolean,
    // Redesign 2026-06: the mockup's prominent reading CTA. [firstUnread] is the same resume target
    // the Resume FAB uses (DetailsState.firstUnreadChapter); [onResumeClick] dispatches the IDENTICAL
    // action (DetailsIntent.OnChapterClick(firstUnread)). No new state/intent — purely a second,
    // always-visible entry point for the existing resume affordance.
    firstUnread: Chapter?,
    onResumeClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDownloadAllClick: () -> Unit,
    onCustomDownloadClick: () -> Unit,
    onOpenInWebViewClick: () -> Unit,
    onTitleCopied: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // LocalClipboardManager is deprecated in favor of LocalClipboard, but the replacement's
    // Clipboard.setClipEntry is suspend and ClipEntry has no common text factory in CMP 1.11.1
    // (only ClipEntry(nativeClipEntry), platform-specific) — migrating commonMain needs new
    // expect/actual construction, out of scope for this source-only deprecation pass. Retained.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    // M-1 / L-6: the blurred parallax backdrop is now painted at SCREEN level (behind the whole
    // content + the transparent top bar), matching native DetailsContent.kt:116-123 — so the header
    // no longer reconstructs an inline `matchParentSize` backdrop here (that drew only behind the
    // header Row, with no parallax and the wrong blur/gradient). This is just the centered content
    // column now.
    //
    // Layout: native `HeaderSection` (the source of truth, HeaderSection.kt:62-102) is a centered
    // Column — a 200x250dp cover (8dp corners), a centered Bold 20sp title, then centered metadata,
    // and finally the equal-weight 4-button ActionButton row (HeaderSection.kt:109-146).
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DetailsCover(coverUrl = details.coverUrl, shouldBlur = shouldBlurCover)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = details.title,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
                // Long-press the title to copy it to the clipboard — legacy parity with
                // `HeaderSection`'s `combinedClickable { onLongClick = setText(...) }`. Uses the
                // Compose-MP `LocalClipboardManager` (same pattern the complaint screens use), so
                // no platform callback is threaded through the route adapter — the clipboard API
                // is multiplatform and lives entirely inside `:ui`.
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    // GAP-DET-12: copy + confirmation snackbar (legacy `title_copied` toast).
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(details.title))
                        onTitleCopied()
                    },
                ),
            )
            // Source / language / status line — native renders the single centered line
            // "${api} ${language} - ${status}" (HeaderSection.kt:97-102), bodyMedium @ onSurface .7
            // alpha. M-8 parity: a single line (no separate author / rating / "·"-joined source
            // lines — the rework's extra rating caption was dropped to match native, which renders
            // neither author nor rating in this header area). The literal "${api} ${language} -
            // ${status}" composition is preserved verbatim, including the spaces/dash, so the
            // visible string matches native exactly.
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${details.api} ${details.language} - ${details.status}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Equal-weight 4-button ActionButton row — native HeaderSection.kt:109-146 /
            // ActionsRow.kt: Bookmark/Remove, last-chapter Schedule, Download-all, Open-in-browser
            // (Language icon), each Modifier.weight(1f), SpaceEvenly.
            val newestChapterDate = details.chapters.firstNotNullOfOrNull { it.date }
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val actionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                DetailsActionButton(
                    text = if (isInLibrary) {
                        stringResource(Res.string.action_remove)
                    } else {
                        stringResource(Res.string.action_bookmark)
                    },
                    icon = if (isInLibrary) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkBorder,
                    // Coral active-state: a saved manga's bookmark action reads in the brand color.
                    color = if (isInLibrary) MaterialTheme.colorScheme.primary else actionColor,
                    onClick = onBookmarkClick,
                    modifier = Modifier.weight(1f),
                )
                DetailsActionButton(
                    text = lastChapterDateLabel(newestChapterDate),
                    icon = Icons.Filled.Schedule,
                    color = actionColor,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                // L-8 download menu — native `ActionsRow` download button opens a `DownloadMenu`
                // dropdown ("Download all" / "Custom Download") for an in-library manga
                // (HeaderSection.kt:77-98). For a not-in-library manga the tap routes to the
                // add-to-library prompt (the screen wires onDownloadAllClick to onRequestAddBookmark
                // in that case), matching native; the menu is gated on in-library so the discoverable
                // custom-download path only appears for a saved manga.
                Box(modifier = Modifier.weight(1f)) {
                    var downloadMenuExpanded by remember { mutableStateOf(false) }
                    DetailsActionButton(
                        text = if (isDownloadingAll) {
                            stringResource(Res.string.action_downloading)
                        } else {
                            stringResource(Res.string.np_details_download_all)
                        },
                        icon = Icons.Filled.Download,
                        color = actionColor,
                        onClick = {
                            if (isInLibrary) downloadMenuExpanded = true else onDownloadAllClick()
                        },
                        isLoading = isDownloadingAll,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = downloadMenuExpanded,
                        onDismissRequest = { downloadMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.details_download_all_menu)) },
                            onClick = {
                                downloadMenuExpanded = false
                                onDownloadAllClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.details_custom_download)) },
                            onClick = {
                                downloadMenuExpanded = false
                                onCustomDownloadClick()
                            },
                        )
                    }
                }
                DetailsActionButton(
                    text = stringResource(Res.string.action_open_in_browser),
                    icon = Icons.Filled.Language,
                    color = actionColor,
                    onClick = onOpenInWebViewClick,
                    modifier = Modifier.weight(1f),
                )
            }
            // Redesign 2026-06 (mockup `.cta`): a prominent full-width coral primary CTA below the
            // title / metadata / action row — the headline addition of the new Details mockup. It is
            // the SAME resume affordance as the Resume FAB: when there's an unread chapter it reads
            // "Resume <number>" (details_resume_chapter) and dispatches the identical
            // OnChapterClick(firstUnreadChapter) via [onResumeClick]; when every chapter is read it
            // shows the inert "You finished this manga" state (details_resume_finished, disabled),
            // mirroring the FAB's finished posture. A plain Material3 Button's default container is
            // colorScheme.primary (#FF5B6E coral), matching the mockup's coral CTA — no new strings,
            // no new state, and the existing 4-action row above is left fully intact.
            //
            // Resolve the accessibility label in composable scope (stringResource cannot run inside
            // the non-composable `semantics` lambda) — reuses the FAB's details_resume_cd ("Resume").
            val resumeCtaContentDescription = stringResource(Res.string.details_resume_cd)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onResumeClick,
                enabled = firstUnread != null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = resumeCtaContentDescription },
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (firstUnread != null) {
                        stringResource(Res.string.details_resume_chapter, firstUnread.number)
                    } else {
                        stringResource(Res.string.details_resume_finished)
                    },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Labeled-icon action button — faithful `:ui` port of the native `ActionButton`
 * (presentation/common/.../buttons/ActionButton.kt). A centered Column with a 24dp icon (or a 24dp
 * 2dp-stroke spinner while [isLoading]), a 4dp spacer, and a single-line 10sp centered caption,
 * tinted [color]. Used in the header's equal-weight 4-button row (native HeaderSection parity).
 */
@Composable
private fun DetailsActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Transparent,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = text,
                    modifier = Modifier.size(24.dp),
                    tint = color,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = color,
            )
        }
    }
}

/** Native parity (ImageWithGradientOverlay.kt:21 / DetailsContent.kt:120): the backdrop band is a
 * fixed 250dp tall. */
private val DETAILS_BACKDROP_HEIGHT = 250.dp

/**
 * Screen-level blurred parallax backdrop — faithful `:ui` port of native `ImageWithGradientOverlay`
 * (`ImageWithGradientOverlay.kt`, used by `DetailsContent.kt:118-123` /
 * `LibraryMangaScreen.kt:253-259`). A fixed [DETAILS_BACKDROP_HEIGHT] (250dp) band filling the
 * width, painted behind the whole content + the transparent top bar:
 *  - **Parallax**: `graphicsLayer { translationY = -parallaxOffset() }` translates the band up at half
 *    the list-scroll speed (the caller computes `parallaxOffset = scrollOffset / 2`). The offset is
 *    passed as a `() -> Float` lambda and read at draw time so scrolling never recomposes the caller.
 *  - **Blur 14.dp**: native `blur = 14.dp` (the rework previously used 24.dp on a Row-sized band).
 *  - **Gradient**: native `startColor = background.copy(0.4f)` → `endColor = background` (the rework
 *    previously used `surface.copy(0.45f) → surface`). M-1: aligned to native's `colorScheme.background`
 *    token, the 0.4f start alpha, and the full 250dp band.
 *
 * Blank cover URLs render nothing (the transparent screen background shows through).
 */
@Composable
private fun DetailsParallaxBackdrop(coverUrl: String, parallaxOffset: () -> Float) {
    if (coverUrl.isBlank()) return
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(DETAILS_BACKDROP_HEIGHT)
            // Read the per-frame offset inside graphicsLayer so it re-runs at draw, not in
            // composition — keeps every scroll frame off the recomposition path.
            .graphicsLayer { translationY = -parallaxOffset() },
    ) {
        // Native `BlurredImageCoil(contentScale = FillBounds, padding(bottom = 4.dp))`.
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .matchParentSize()
                .padding(bottom = 4.dp)
                .blur(radius = 14.dp),
        )
        // Vertical scrim: fade from a partly-transparent background at the top (0.4f) to opaque
        // background at the bottom so the header text stays legible over the blurred cover
        // (native ImageWithGradientOverlay: background.copy(0.4f) → background).
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            background.copy(alpha = 0.4f),
                            background,
                        ),
                    ),
                ),
        )
    }
}

/**
 * Relative-date label for the newest chapter, mirroring the legacy `HeaderSection` Schedule chip
 * wording ("Today" / "Yesterday" / "N days ago" / "No chapter yet") and HistoryScreen's inline
 * kotlinx.datetime formatting.
 *
 * GAP-DET-14: localized via `stringResource` (was inline English literals). Reuses the legacy
 * key intent under namespaced `np_details_*` names (`np_details_no_chapter_yet` /
 * `np_details_today` / `np_details_yesterday` / `np_details_days_ago` ≈ legacy
 * `action_no_chapter_yet` / `action_today` / `action_yesterday` / `day_since_format`). Older
 * dates beyond a week fall back to the ISO date string (no localized template needed — the
 * numeric date is locale-neutral). `@Composable` so each arm can resolve a `stringResource`.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun lastChapterDateLabel(date: LocalDate?): String {
    if (date == null) return stringResource(Res.string.np_details_no_chapter_yet)
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val days = date.daysUntil(today).toLong()
    return when {
        days == 0L -> stringResource(Res.string.np_details_today)
        days == 1L -> stringResource(Res.string.np_details_yesterday)
        days in 2..6 -> stringResource(Res.string.np_details_days_ago, days.toInt())
        else -> date.toString()
    }
}

/**
 * 200x250dp cover backed by Coil's singleton [coil3.ImageLoader] — native parity
 * (`HeaderSection.kt:70-78`: `size(200.dp, 250.dp)`, 8dp rounded corners, `ContentScale.Crop`).
 *
 * The singleton is configured in `:composeApp`/`App.kt` (`setSingletonImageLoaderFactory {…}`)
 * and provides the load-bearing fixes the rework needs for free:
 *   - **AVIF decoder** registered on Android via `ImageDecoderRegistry().registerAll()` —
 *     covers from sources that serve AVIF (some Cloudflare-protected hosts) decode correctly.
 *   - **OkHttp network fetcher** on Android (matches the legacy `CoilModule.provideImageLoader`),
 *     ktor3 on Desktop/iOS. Avoids the ServiceLoader nondeterminism between okhttp + ktor3.
 *   - **`maxBitmapSize(Size.ORIGINAL)`** at the loader level — disables Coil 3.3+'s default
 *     4096×4096 clamp which would blur tall covers before Compose lays them out.
 *   - **`CoilSourceHeaderInterceptor`** auto-host-matches the request URL against
 *     `SourcesRepository` and injects the source's `defaultHeaders` (Cookie/User-Agent/Referer).
 *     Covers from Cloudflare-protected sources load with the same auth posture as the source
 *     HTML fetch. For Details, the user has just navigated FROM somewhere that hit the source
 *     (Home/Library/History/Search), so `ensureSiteInitialized` has already populated the
 *     headers — no proactive hydration needed.
 *   - **`HighQualitySkiaImageDecoder`** registered on iOS/Desktop via the same
 *     `ImageDecoderRegistry()` plumbing — replaces Skia's default nearest-neighbor resampling
 *     with Catmull-Rom for sharp downscales.
 *
 * Three rendered states (via [SubcomposeAsyncImage] — closes the §50.9 placeholder deferral):
 *   - **Loading**: a compact [CircularProgressIndicator] (20 dp, 2 dp stroke) centered over the
 *     surface-variant container. Subcomposition cost is one composable per Details screen; not
 *     in a hot list. (When the same cover pattern is ported to Library/History grids, the
 *     trade-off should be revisited — for a 50-cell grid, painter-based `placeholder()` /
 *     `error()` slots on `AsyncImage` may be cheaper than 50 subcompositions.)
 *   - **Success**: the decoded image with [Modifier.blur] applied to the success content only.
 *     Hosting the blur inside the success slot (instead of on the outer container modifier as
 *     §51 had it) keeps the loading spinner unblurred even when `shouldBlur == true` —
 *     otherwise the adult-content blur would obscure the very signal the user needs to know
 *     loading is in progress. Net behaviour change for the blur posture: zero (the loaded
 *     image still blurs identically; the spinner used to flash blurred briefly and now
 *     doesn't, which is a UX upgrade).
 *   - **Error**: no override → the [SubcomposeAsyncImage] composes nothing for the error slot,
 *     so the container modifier's surface-variant background remains visible — matches the
 *     blank-URL fallback below. A future polish slice can swap in a broken-image glyph; for
 *     now silent fallback is parity-with-legacy (which also renders a placeholder, not a
 *     broken-image icon).
 *
 * What this composable deliberately does NOT do:
 *   - **No per-platform decoder hints.** Android `RGB_565` + `allowHardware(false)` halve memory
 *     pressure and avoid hardware-bitmap restrictions, which matters when the reader holds
 *     ~20 full-page bitmaps simultaneously. A single 96dp cover thumbnail is a rounding error;
 *     ARGB_8888 is fine. These hints ride along with the reader rework.
 *   - **No per-request `maxBitmapSize(Undefined, Undefined)` override.** Same rationale —
 *     the loader-level `Size.ORIGINAL` already disables the cap; covers are nowhere near 4096px.
 *   - **No proactive `ensureSiteInitialized()` Effect.** Details is always entered FROM a screen
 *     that has already fetched from the source, so headers are hydrated by the time we render
 *     here. (If that assumption breaks for a future entry point — e.g. deep-linking — a follow-up
 *     slice can introduce a `LaunchedEffect` to hydrate.)
 *
 * Blank-URL fallback renders the same surface-variant `Box` the placeholder used pre-Coil,
 * so empty `coverUrl` strings don't show a broken-image icon.
 */
@Composable
private fun DetailsCover(coverUrl: String, shouldBlur: Boolean) {
    // Redesign 2026-06: hero cover corners 8 -> 16.dp to match the new poster language.
    val coverShape = RoundedCornerShape(16.dp)
    // Modifier.blur(radius) is the platform-portable Compose blur: backed by
    // Android API 31+ RenderEffect and by Skia (Desktop / iOS via CMP). On Android API 26-30
    // the modifier silently no-ops — the cover renders unblurred, but the modal
    // AdultConfirmationDialog is still on top, so the user can't interact with the screen
    // beneath it until they tap Continue or Go back. This is acceptable parity-vs-legacy: the
    // legacy `:composeApp` MStep1 also relies on the dialog being modally on top, not on a
    // blur. The 32dp radius matches the legacy MStep cover-blur posture.
    //
    // animateDpAsState produces a 300 ms tween (Compose default for Dp) when `shouldBlur`
    // flips — used for the Continue-tap unblur (32 → 0) and for any post-fetch isAdult
    // re-classification that flips false → true (0 → 32). The initial composition uses the
    // first target value as the start value (no opening animation from 0), so a cover that
    // enters already-adult does NOT play an unwanted "fade in the blur" on render.
    val blurRadius by animateDpAsState(
        targetValue = if (shouldBlur) 32.dp else 0.dp,
        label = "cover-blur",
    )
    val coverModifier = Modifier
        .size(width = 200.dp, height = 250.dp)
        .clip(coverShape)
        .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = coverShape)
    if (coverUrl.isBlank()) {
        Box(modifier = coverModifier)
        return
    }
    SubcomposeAsyncImage(
        model = coverUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = coverModifier,
        loading = {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center),
                strokeWidth = 2.dp,
            )
        },
        success = {
            // Blur scoped to the loaded image content only — the loading spinner and the
            // error-state surface-variant placeholder stay sharp. SubcomposeAsyncImageContent
            // renders the success state's painter using the parent SubcomposeAsyncImage's
            // contentDescription / contentScale / alignment defaults; only the modifier
            // changes here.
            SubcomposeAsyncImageContent(
                modifier = Modifier.blur(radius = blurRadius),
            )
        },
    )
}

/**
 * Genre chip row with a collapsed cap + "+N more" expand affordance (GAP-DET-10).
 *
 * Legacy parity (`GenresAndDescriptionSection`): collapsed shows the first
 * [COLLAPSED_GENRE_CHIPS] genres followed by a tappable `MoreGenresChip("+N more")`; tapping it
 * expands to reveal every genre in a wrapping [FlowRow]. The expanded state collapses back is
 * intentionally NOT offered (legacy had no collapse-back on genres — once expanded the user
 * scrolls past). When the genre count is at or below the cap, the overflow chip is omitted and
 * all chips render directly (no wasted affordance).
 *
 * `animateContentSize` smooths the height change as the FlowRow grows on expand.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChipRow(genres: List<String>) {
    var expanded by remember(genres) { mutableStateOf(false) }
    val overflowCount = (genres.size - COLLAPSED_GENRE_CHIPS).coerceAtLeast(0)
    val visibleGenres = if (expanded || overflowCount == 0) {
        genres
    } else {
        genres.take(COLLAPSED_GENRE_CHIPS)
    }
    // Native parity (GenresAndDescriptionSection.kt:57-64): FlowRow with 4dp horizontal /
    // 8dp vertical gaps, both centered.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        visibleGenres.forEach { genre ->
            GenreChip(text = genre)
        }
        if (!expanded && overflowCount > 0) {
            MoreGenresChip(remaining = overflowCount, onClick = { expanded = true })
        }
    }
}

/**
 * Bordered-Box genre chip — faithful `:ui` port of native `GenreChip`
 * (GenresAndDescriptionSection.kt:101-120): a 1dp `onBackground` @0.5 alpha border, 6dp rounded
 * corners, v8/h12 padding, single-line bodySmall `onSurface` @0.7 alpha text. Replaces the
 * Material3 AssistChip (whose filled-tonal styling, ripple, ~32dp min-height and default
 * typography all diverged from native).
 */
@Composable
private fun GenreChip(text: String) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * "+N more" overflow chip — native `MoreGenresChip` (GenresAndDescriptionSection.kt:80-97): a
 * bordered Box (1dp `onBackground` @0.7 alpha, 4dp corners) with the same v8/h12 padding and
 * bodySmall `onSurface` @0.7 text, tappable to expand the full genre list.
 */
@Composable
private fun MoreGenresChip(remaining: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.np_details_genres_more, remaining),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

/**
 * Description text that collapses to [COLLAPSED_DESCRIPTION_LINES] lines with a bottom gradient
 * fade and an expand chevron, and expands to the full text with a collapse chevron (GAP-DET-11).
 *
 * Legacy parity (`GenresAndDescriptionSection` CollapsedDescription / ExpandedDescription):
 *  - collapsed: `maxLines` + ellipsis, a vertical gradient overlay fading Transparent → surface
 *    over the bottom of the text so the truncated edge softens out, and an [ExpandMore] chevron.
 *  - expanded: full text (`Int.MAX_VALUE` lines), no fade, an [ExpandLess] chevron.
 *  - `animateContentSize` smooths the height transition (legacy used the same).
 *
 * The fade overlay is only drawn while collapsed AND there is something to expand (overflow); a
 * short description that fits within the cap shows no fade and no chevron.
 */
@Composable
private fun ExpandableDescription(description: String) {
    val spacing = LocalSpacing.current
    var expanded by remember(description) { mutableStateOf(false) }
    // Tracks whether the collapsed text actually overflowed its line cap — only then is the
    // expand affordance + gradient fade meaningful. Updated from the Text's onTextLayout.
    var hasOverflow by remember(description) { mutableStateOf(false) }
    // P3-LOW parity (GenresAndDescriptionSection.kt:130-143): native's collapsed fade scrim runs
    // Transparent -> colorScheme.background (the screen surface behind the parallax backdrop), not
    // -> surface. Aligned here so the ellipsized cut-off blends into the same token native fades to.
    val background = MaterialTheme.colorScheme.background
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                // P3-LOW parity (GenresAndDescriptionSection.kt:73 / 130): native center-aligns the
                // description text in both the collapsed and expanded states.
                textAlign = TextAlign.Center,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DESCRIPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> hasOverflow = layout.hasVisualOverflow || hasOverflow },
                modifier = Modifier.fillMaxWidth(),
            )
            // Bottom gradient fade over the last text band while collapsed — softens the
            // ellipsized cut-off (native CollapsedDescription Transparent -> background scrim).
            if (!expanded && hasOverflow) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(background.copy(alpha = 0f), background),
                            ),
                        ),
                )
            }
        }
        if (hasOverflow || expanded) {
            KiraIconButton(
                // Inline material-icons (GAP-DET-11) — KiraIcons has no expand chevrons and the
                // design-system file is out of scope to edit, so use Icons.* directly.
                icon = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    stringResource(Res.string.np_details_description_collapse)
                } else {
                    stringResource(Res.string.np_details_description_expand)
                },
                onClick = { expanded = !expanded },
                // P3-LOW parity (GenresAndDescriptionSection.kt:75 / 145-147): native centers the
                // expand/collapse chevron under the description (BottomCenter collapsed,
                // CenterHorizontally expanded) rather than aligning it to the End.
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * Chapter list row with per-chapter library-management affordances (GAP-LIB-02/03 + native
 * library_details parity).
 *
 * Layout in-library (native `LibraryChapterItem`): [title/date column] + read-toggle eye +
 * download/cancel/done + bookmark toggle. Not-in-library (native `ChapterItem`): a single download
 * IconButton that dispatches the add-to-library prompt ([onRequestAddBookmark]) — native
 * ChapterItem.kt:87-94 (`onClick = if (isSaved) onDownloadClick() else onRequestAddBookmark()`).
 * Tap opens the reader (or toggles selection in multi-select mode); long-press enters multi-select.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    isSelected: Boolean,
    // PFIX-DLPROGRESS: live download status+progress for THIS chapter, or null when it has no
    // active download row. Drives the native-parity progress affordance (determinate ring while
    // RUNNING, spinner while QUEUED / COMPRESSING) and is checked BEFORE chapter.isDownloaded so
    // the row never flashes the idle Download button on completion.
    download: ChapterDownloadProgress?,
    isInLibrary: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onRequestAddBookmark: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // GAP-DET-06: each chapter renders as an elevated rounded Card (native `ChapterItem` —
    // shadow 4.dp, RoundedCornerShape 8.dp, 16dp inner padding) rather than a flat row. The
    // selection highlight is carried by the card's container colour (primaryContainer when
    // selected) instead of a raw background modifier, so the rounded corners + shadow stay
    // consistent across both states.
    //
    // P3-LOW parity (ChapterItem.kt:67-70): the un-selected container colour is the screen
    // `background` token (native `containerColor = colorScheme.background`), not `surface`. The
    // selected state keeps `primaryContainer` for the multi-select highlight (a KMP affordance
    // native lacks — preserved). The 4dp default-elevation shadow matches native's `elevation = 4.dp`;
    // native additionally tints the shadow's ambient/spot to `onSurface @0.9` via a manual
    // `Modifier.shadow` — that subtle shadow-colour nuance is left at the Material default here
    // (forcing it would double-draw over the Card's own elevation shadow). Intentionally-different.
    Card(
        modifier = Modifier.fillMaxWidth(),
        // Redesign 2026-06: chapter-row corners 8 -> 12.dp, on-language for the dense list rows.
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.background
            },
        ),
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            // P3-LOW parity (ChapterItem.kt:73): native's inner Row uses a uniform 16dp padding.
            .padding(spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chapterDisplayLabel(chapter),
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    // Native parity (ChapterItem.kt:76-77 / LibraryChapterItem.kt:407): the
                    // chapter label is Bold, not Medium.
                    fontWeight = FontWeight.Bold,
                    // Read chapters are dimmed to onSurfaceVariant (legacy parity); unread stay at full
                    // onSurface emphasis. Sourced from the Room-backed `Chapter.isRead` (offline Details
                    // path) so a Library-opened manga shows its read/unread marks immediately.
                    color = if (chapter.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // NEW badge (native LibraryChapterItem.kt:621-640): a small red rounded Card with
                // bold white "NEW" label, shown for chapters a Library refresh inserted as newly
                // published (`Chapter.isNew` ← `saved_chapters.isNew`). Clears on chapter open via
                // the mark-read path. Faithful to native styling: Color.Red container, RoundedCorner
                // 4.dp, labelSmall bold white text, h6/v2 inner padding.
                if (chapter.isNew) {
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.Red),
                    ) {
                        Text(
                            text = stringResource(Res.string.new_chapter),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        )
                    }
                }
            }
            val date = chapter.date
            // Native size display (LibraryChapterItem.kt:419-..): a downloaded chapter shows its
            // on-disk size next to the date ("MMM d, yyyy • 12.3 MB"), tinted primary. The size
            // comes from the SUCCESS download entry's [ChapterDownloadProgress.sizeLabel] (back-filled
            // for pre-existing downloads by the startup reconcile); null while not downloaded.
            val sizeLabel = download?.sizeLabel
            if (date != null || sizeLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (date != null) {
                        Text(
                            // Native parity (ChapterItem.kt:80 / LibraryChapterItem.kt:419):
                            // chapter.date?.toRelativeString(context) → "today" / "yesterday" /
                            // "MMM d, yyyy" instead of the locale-neutral ISO LocalDate.toString().
                            text = chapterRelativeDate(date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    if (sizeLabel != null) {
                        if (date != null) {
                            Text(
                                text = " • ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            )
                        }
                        Text(
                            text = sizeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            // Active-download status line (PFIX: the user reported "no UI shows the compressing
            // step, only loading"). Native shows distinct download → compress phases; surface the
            // phase as text here so the user can tell archiving ("Compressing…") from downloading
            // ("Downloading N%"). Shown only while the download occupies a queue slot.
            if (download != null && download.isActive) {
                val statusText = when (download.state) {
                    DownloadState.RUNNING ->
                        stringResource(Res.string.pfix_dl_downloading_format, "${download.progress.coerceIn(0, 100)}%")
                    // COMPRESSING + DOWNLOADED (iOS background: pages on disk, finalization pending)
                    // both render the "finishing/compressing" label — reuses the existing string.
                    DownloadState.COMPRESSING, DownloadState.DOWNLOADED -> stringResource(Res.string.pfix_dl_compressing)
                    else -> stringResource(Res.string.pfix_dl_queued)
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        // Per-chapter trailing actions — only meaningful for a saved manga's chapters (GAP-LIB-02/03).
        if (isInLibrary) {
            // Read/unread toggle (RemoveRedEye) — primary tint when read, muted when unread.
            KiraIconButton(
                icon = KiraIcons.MarkRead,
                contentDescription = if (chapter.isRead) {
                    stringResource(Res.string.details_mark_unread)
                } else {
                    stringResource(Res.string.details_mark_read)
                },
                onClick = onToggleRead,
                tint = if (chapter.isRead) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // Download affordance (PFIX-DLPROGRESS + completion-freeze fix, native
            // LibraryChapterItem.kt:476-555 parity). The live download entry is checked FIRST:
            //  - ACTIVE (RUNNING / QUEUED / COMPRESSING) → a progress indicator wrapped in a clickable
            //    Box that cancels the download. RUNNING shows a determinate ring
            //    (CircularProgressIndicator(progress = pct/100)) tracking the live percent as each DAO
            //    tick re-emits; QUEUED / COMPRESSING show an indeterminate spinner (the native
            //    AnimatedCompressing drawable is Android-only — a plain spinner is the :ui stand-in).
            //  - DOWNLOADED (the entry's own SUCCESS state OR chapter.isDownloaded) → the DownloadDone
            //    glyph + the on-disk size. Checking the entry's SUCCESS state here is what makes the
            //    running→downloaded flip ATOMIC: the SUCCESS entry arrives in the SAME downloads-flow
            //    emission that dropped RUNNING, so the row never falls through to the idle button for a
            //    frame (the old "downloading → downloaded only after leave/return" flash). chapter.isDownloaded
            //    is kept as a fallback for chapters downloaded without a surviving download row.
            //  - else → the idle Download button.
            when {
                download != null && download.isActive -> {
                    val determinate = download.state == DownloadState.RUNNING
                    val progressFraction = (download.progress.coerceIn(0, 100) / 100f)
                    val cancelLabel = stringResource(Res.string.pfix_dlprogress_cancel_chapter_download)
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(onClick = onCancelDownload)
                            .semantics { contentDescription = cancelLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (determinate) {
                            CircularProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                download?.isDownloaded == true || chapter.isDownloaded -> {
                    // Downloaded + done — native `ChapterItem.kt:89-93` / `LibraryChapterItem`
                    // render the DownloadDone glyph (not a bare Check) tinted primary for a
                    // downloaded chapter. P3-LOW parity: use the same DownloadDone glyph here so the
                    // in-library downloaded state matches native. Non-interactive — it is the
                    // "downloaded" INDICATOR; delete is the dedicated trailing Delete button below.
                    KiraIconButton(
                        icon = Icons.Filled.DownloadDone,
                        contentDescription = stringResource(Res.string.downloaded),
                        onClick = {},
                        enabled = false,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    KiraIconButton(
                        icon = KiraIcons.Download,
                        contentDescription = stringResource(Res.string.details_download_chapter),
                        onClick = onDownload,
                    )
                }
            }
            // Per-chapter bookmark toggle (native LibraryChapterItem.kt:606-618) — BookmarkRemove
            // (filled, primary tint) when bookmarked, BookmarkBorder (muted) otherwise.
            KiraIconButton(
                icon = if (chapter.isBookmarked) Icons.Filled.BookmarkRemove else Icons.Filled.BookmarkBorder,
                contentDescription = if (chapter.isBookmarked) {
                    stringResource(Res.string.details_unbookmark_chapter)
                } else {
                    stringResource(Res.string.details_bookmark_chapter)
                },
                onClick = onToggleBookmark,
                tint = if (chapter.isBookmarked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // Per-chapter "delete from database" (user-requested, additive beyond native): removes
            // this chapter's saved_chapters row AND its download (files + chapter_downloads row) so
            // nothing is orphaned. Shown on EVERY in-library chapter row. The saved-details flow
            // re-emits without the chapter, so it drops out of the list (a later refresh may re-add it).
            KiraIconButton(
                icon = KiraIcons.Delete,
                contentDescription = stringResource(Res.string.details_delete_chapter),
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // Not-in-library: a download IconButton on EVERY row (native ChapterItem.kt:87-94).
            // Tapping routes to the add-to-library prompt (the chapter can't be downloaded until the
            // manga is saved); a downloaded chapter shows the DownloadDone glyph. This restores the
            // per-chapter download entry point the rework dropped (it had only a passive dot).
            KiraIconButton(
                icon = if (chapter.isDownloaded) Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = stringResource(Res.string.details_download_chapter),
                onClick = onRequestAddBookmark,
                tint = if (chapter.isDownloaded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
            )
        }
    }
    }
}

/**
 * Multi-select action bar — a bottom surface shown while chapters are selected. L-4 parity with the
 * native `ChapterSelectionActionsRow` (ChapterSelectionActionsRow.kt:44-99): a selected-count label
 * plus a horizontally-scrollable action row — Download all, Bookmark all, Mark-this-and-below-read
 * (only when exactly one chapter is selected), Delete-downloaded (only when every selected chapter
 * is downloaded), Mark all read, and Close. All actions are callback-only; the VM owns the logic.
 */
@Composable
private fun ChapterSelectionBar(
    selectedCount: Int,
    showMarkDownRead: Boolean,
    showDeleteDownloaded: Boolean,
    onDownload: () -> Unit,
    onBookmarkAll: () -> Unit,
    onMarkDownRead: () -> Unit,
    onDeleteDownloaded: () -> Unit,
    onMarkRead: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.details_chapter_selection_count, selectedCount),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = spacing.sm),
            )
            // Native (ChapterSelectionActionsRow.kt:61-98): the action cluster is horizontally
            // scrollable so the variable set of icons never clips on a narrow screen.
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                // Download all (native Download).
                KiraIconButton(
                    icon = KiraIcons.Download,
                    contentDescription = stringResource(Res.string.download),
                    onClick = onDownload,
                )
                // Bookmark all (native BookmarkBorder).
                KiraIconButton(
                    icon = Icons.Filled.BookmarkBorder,
                    contentDescription = stringResource(Res.string.details_bookmark_all),
                    onClick = onBookmarkAll,
                )
                // Mark this-and-below as read — only when exactly one chapter is selected (native
                // ic_done_down_arrow). Material-icons-extended PlaylistAddCheck is the closest
                // multiplatform glyph for "mark this and everything below".
                if (showMarkDownRead) {
                    KiraIconButton(
                        icon = Icons.AutoMirrored.Filled.PlaylistAddCheck,
                        contentDescription = stringResource(Res.string.details_mark_all_down_read),
                        onClick = onMarkDownRead,
                    )
                }
                // Delete downloaded — only when every selected chapter is downloaded (native Delete).
                if (showDeleteDownloaded) {
                    KiraIconButton(
                        icon = Icons.Filled.Delete,
                        contentDescription = stringResource(Res.string.details_delete_downloaded),
                        onClick = onDeleteDownloaded,
                    )
                }
                // Mark all read (native RemoveRedEye).
                KiraIconButton(
                    icon = KiraIcons.MarkRead,
                    contentDescription = stringResource(Res.string.mark_read),
                    onClick = onMarkRead,
                )
                // Close selection (native Close).
                KiraIconButton(
                    icon = KiraIcons.Close,
                    contentDescription = stringResource(Res.string.cancel),
                    onClick = onClear,
                )
            }
        }
    }
}

/**
 * Resume/continue extended FAB — native `AnimatedCircleExtendedFab` on the in-library detail screen
 * (LibraryMangaScreen.kt:225-235). Tapping jumps to the first unread chapter ([firstUnread]); when
 * every chapter is read, [firstUnread] is null and the FAB shows the "You finished this manga"
 * label and is inert. [expanded] mirrors native's scroll-direction-driven expand/collapse: expanded
 * renders the icon + text, collapsed renders an icon-only circular FAB.
 *
 * Native uses `Icons.Default.PlayArrow` and "Resume ${number}" / "You finished this manga".
 */
@Composable
private fun ResumeFab(
    firstUnread: Chapter?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val label = if (firstUnread != null) {
        stringResource(Res.string.details_resume_chapter, firstUnread.number)
    } else {
        stringResource(Res.string.details_resume_finished)
    }
    val contentDescription = stringResource(Res.string.details_resume_cd)
    if (expanded) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = contentDescription,
                )
            },
            text = { Text(label) },
        )
    } else {
        FloatingActionButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = contentDescription,
            )
        }
    }
}

/**
 * Chapter filter/sort modal bottom sheet — native `CustomFilterBottomSheet` with a Filter tab and a
 * Sort tab (LibraryMangaScreen.kt:386-458). Faithful `:ui` reconstruction (the native sheet +
 * FilterChipsRow + SortOptionsSection live in `:composeApp`, the wrong side of the `:ui` layering
 * boundary, so they are rebuilt inline from the same Material3 primitives).
 *
 *  - **Filter tab**: a wrapping row of FilterChips over the five [ChapterFilterType]s (native
 *    FilterChipsRow — selected chip carries a leading Done check).
 *  - **Sort tab**: an Ascending/Descending switch (native SortOptionsSection direction toggle) plus
 *    a wrapping row of FilterChips over the four [ChapterSortType]s.
 *
 * Sheet chrome matches native: `RoundedCornerShape(top 24dp)`, no drag handle, `surfaceContainerHigh`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterFilterSortSheet(
    selectedFilter: ChapterFilterType,
    selectedSort: ChapterSortType,
    sortAscending: Boolean,
    onFilterSelected: (ChapterFilterType) -> Unit,
    onSortSelected: (ChapterSortType) -> Unit,
    onSortDirectionChange: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 8.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = {},
        sheetState = rememberModalBottomSheetState(),
    ) {
        var tabIndex by remember { mutableStateOf(0) }
        Column(modifier = Modifier.padding(16.dp)) {
            val tabs = listOf(
                stringResource(Res.string.library_bottom_sheet_tab_filter),
                stringResource(Res.string.library_bottom_sheet_tab_sort),
            )
            SecondaryTabRow(
                selectedTabIndex = tabIndex,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = { Text(text = title, fontSize = 16.sp) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            when (tabIndex) {
                0 -> ChapterFilterTab(selectedFilter = selectedFilter, onFilterSelected = onFilterSelected)
                1 -> ChapterSortTab(
                    selectedSort = selectedSort,
                    sortAscending = sortAscending,
                    onSortSelected = onSortSelected,
                    onSortDirectionChange = onSortDirectionChange,
                )
            }
        }
    }
}

/** Filter tab — five FilterChips (native FilterChipsRow), selected chip carries a leading Done. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterFilterTab(
    selectedFilter: ChapterFilterType,
    onFilterSelected: (ChapterFilterType) -> Unit,
) {
    FlowRow(modifier = Modifier.fillMaxWidth()) {
        ChapterFilterType.entries.forEach { filter ->
            val selected = filter == selectedFilter
            FilterChip(
                selected = selected,
                onClick = { onFilterSelected(filter) },
                label = { Text(chapterFilterLabel(filter)) },
                leadingIcon = if (selected) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Done,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** Sort tab — Ascending/Descending switch + four sort-key FilterChips (native SortOptionsSection). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChapterSortTab(
    selectedSort: ChapterSortType,
    sortAscending: Boolean,
    onSortSelected: (ChapterSortType) -> Unit,
    onSortDirectionChange: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(Res.string.sort_options_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SwapVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.sort_direction_label))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sortAscending) {
                        stringResource(Res.string.sort_direction_ascending)
                    } else {
                        stringResource(Res.string.sort_direction_descending)
                    },
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = sortAscending,
                    onCheckedChange = { onSortDirectionChange() },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(Res.string.sort_by_label),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            ChapterSortType.entries.forEach { sort ->
                FilterChip(
                    selected = sort == selectedSort,
                    onClick = { onSortSelected(sort) },
                    label = { Text(chapterSortLabel(sort)) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Localized label for a [ChapterFilterType] (native FilterType.getDisplayName). */
@Composable
private fun chapterFilterLabel(filter: ChapterFilterType): String = when (filter) {
    ChapterFilterType.ALL -> stringResource(Res.string.filter_all)
    ChapterFilterType.DOWNLOADED -> stringResource(Res.string.filter_downloaded)
    ChapterFilterType.UNREAD -> stringResource(Res.string.filter_unread)
    ChapterFilterType.READED -> stringResource(Res.string.filter_readed)
    ChapterFilterType.BOOKMARKED -> stringResource(Res.string.filter_bookmarked)
}

/** Localized label for a [ChapterSortType] (native SortType.getDisplayName). */
@Composable
private fun chapterSortLabel(sort: ChapterSortType): String = when (sort) {
    ChapterSortType.ID -> stringResource(Res.string.sort_type_id)
    ChapterSortType.NUMBER -> stringResource(Res.string.sort_type_number)
    ChapterSortType.DATE -> stringResource(Res.string.sort_type_date)
    ChapterSortType.LAST_READ_DATE -> stringResource(Res.string.sort_type_last_read_date)
}

/**
 * GAP-DET-10: collapsed-state genre cap before the "+N more" overflow chip appears. Mirrors the
 * legacy `GenresAndDescriptionSection` collapsed cap (4 genres + overflow chip).
 */
private const val COLLAPSED_GENRE_CHIPS = 4

/**
 * GAP-DET-11: collapsed-state description line cap before the gradient fade + expand chevron.
 * Mirrors the legacy `CollapsedDescription` maxLines=4.
 */
private const val COLLAPSED_DESCRIPTION_LINES = 4

/**
 * Chapter row label — native parity (`ChapterItem.kt:76-77` / `LibraryChapterItem.kt:405`):
 * the raw source number string (`chapter.number`), falling back to the chapter name when the
 * number is blank. No "Ch. " prefix and no "N — name" join — native renders the bare
 * `chapter.number.ifBlank { chapter.name }` in Bold.
 */
private fun chapterDisplayLabel(chapter: Chapter): String =
    chapter.number.ifBlank { chapter.name }

/**
 * Relative chapter-date label — faithful `:ui` port of native `Date.toRelativeString`
 * (`Date.kt:13-18`): "today" when the date is today, "yesterday" the day before, otherwise the
 * `MMM d, yyyy` medium format (e.g. "Jun 1, 2026"). Replaces the locale-neutral ISO
 * `LocalDate.toString()` the rework previously rendered on every chapter row (M-4 parity fix).
 *
 * Native uses `java.time`'s `DateTimeFormatter.ofPattern("MMM d, yyyy")`, which localizes the
 * month under the locale override; here the abbreviated month name is resolved through the
 * localized `month_abbrev_*` resources via [detailsMonthAbbrev] and composed via the
 * `details_chapter_date_full` template so the day/year ordering can be localized too. `today` /
 * `yesterday` are lowercase to match native's `date_today` / `date_yesterday`.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun chapterRelativeDate(date: LocalDate): String {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    // Native matches on exact-day equality (this == now / now.minusDays(1)); daysUntil(today)
    // yields 0 for today and 1 for yesterday, and a future date (negative) falls to the absolute
    // format — same observable behaviour. Mirrors the HistoryScreen relative-date pattern.
    val days = date.daysUntil(today).toLong()
    return when (days) {
        0L -> stringResource(Res.string.details_chapter_date_today)
        1L -> stringResource(Res.string.details_chapter_date_yesterday)
        else -> stringResource(
            Res.string.details_chapter_date_full,
            detailsMonthAbbrev(date.month.number),
            date.day,
            date.year,
        )
    }
}

/**
 * GAP-DET-MONTHLOC: localized month abbreviation for [chapterRelativeDate]. Resolves a per-month
 * `month_abbrev_*` string resource (the same GAP-HIST-07 catalog HistoryScreen uses) so the
 * absolute chapter date renders Arabic month names under the locale override instead of the fixed
 * English table native's default-locale `MMM` formatter would otherwise localize.
 */
@Composable
private fun detailsMonthAbbrev(month: Int): String = when (month) {
    1 -> stringResource(Res.string.month_abbrev_jan)
    2 -> stringResource(Res.string.month_abbrev_feb)
    3 -> stringResource(Res.string.month_abbrev_mar)
    4 -> stringResource(Res.string.month_abbrev_apr)
    5 -> stringResource(Res.string.month_abbrev_may)
    6 -> stringResource(Res.string.month_abbrev_jun)
    7 -> stringResource(Res.string.month_abbrev_jul)
    8 -> stringResource(Res.string.month_abbrev_aug)
    9 -> stringResource(Res.string.month_abbrev_sep)
    10 -> stringResource(Res.string.month_abbrev_oct)
    11 -> stringResource(Res.string.month_abbrev_nov)
    12 -> stringResource(Res.string.month_abbrev_dec)
    else -> month.toString()
}

/**
 * Resolved [AppError] → localized message lookup, captured in composable scope so the snackbar
 * collector inside [DetailsScreenContent]'s [LaunchedEffect] (a non-composable suspend lambda)
 * can map an error to its message without calling `stringResource` itself. The 7 branch strings
 * are read here via `stringResource` and held in the returned holder; [messageFor] is a plain
 * non-composable `when` over the captured values.
 */
@Composable
private fun rememberAppErrorMessages(): AppErrorMessages = AppErrorMessages(
    network = stringResource(Res.string.details_error_network),
    // P1 parity: native distinguishes network failures by HTTP status code / transport failure
    // (`State.kt` `httpStatusMessage` + `fromException`) rather than collapsing all network errors
    // into one string. Codes native does not name individually fall back to `network`.
    noConnectivity = stringResource(Res.string.error_network_no_connectivity),
    timeout = stringResource(Res.string.error_network_timeout),
    badRequest = stringResource(Res.string.error_network_bad_request),
    unauthorized = stringResource(Res.string.error_network_unauthorized),
    forbidden = stringResource(Res.string.error_network_forbidden),
    notFound = stringResource(Res.string.error_network_not_found),
    requestTimeout = stringResource(Res.string.error_network_request_timeout),
    server = stringResource(Res.string.error_network_server),
    badGateway = stringResource(Res.string.error_network_bad_gateway),
    serviceUnavailable = stringResource(Res.string.error_network_service_unavailable),
    gatewayTimeout = stringResource(Res.string.error_network_gateway_timeout),
    storage = stringResource(Res.string.details_error_storage),
    validation = stringResource(Res.string.details_error_validation),
    auth = stringResource(Res.string.details_error_auth),
    platform = stringResource(Res.string.details_error_platform),
    cancelled = stringResource(Res.string.details_error_cancelled),
    unexpected = stringResource(Res.string.error_occurred),
)

private class AppErrorMessages(
    val network: String,
    val noConnectivity: String,
    val timeout: String,
    val badRequest: String,
    val unauthorized: String,
    val forbidden: String,
    val notFound: String,
    val requestTimeout: String,
    val server: String,
    val badGateway: String,
    val serviceUnavailable: String,
    val gatewayTimeout: String,
    val storage: String,
    val validation: String,
    val auth: String,
    val platform: String,
    val cancelled: String,
    val unexpected: String,
) {
    fun messageFor(error: AppError): String = when (error) {
        is AppError.Network -> networkMessage(error)
        is AppError.Storage -> storage
        is AppError.Validation -> validation
        is AppError.Auth -> auth
        is AppError.Platform -> platform
        is AppError.Cancelled -> cancelled
        is AppError.Unexpected -> unexpected
    }

    private fun networkMessage(error: AppError.Network): String = when (error) {
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
}

/**
 * P0-ADULT: first dialog of the hard-block adult-content gate — faithful `:ui` port of the native
 * `AdultConfirmationDialog`. This is a Google-Play-policy compliance BLOCK, not an age-gate: it
 * apologises that the content cannot be shown. It NEVER reveals the underlying body — every action
 * either back-navigates or advances to the next non-revealing step of the gate chain.
 *
 * Native parity (`presentation/.../details/ui/components/dialogs/AdultConfirmationDialog.kt`):
 *  - **Header** "Content unavailable" (`adult_filter_removal_header`), 20sp, Bold, red @ .8 alpha.
 *  - **Icon** the red `ic_pluss18` 18+ vector, 120dp + 8dp padding, tinted red @ .65 alpha,
 *    centered inside the body column (matches native's icon-in-`text`-slot layout).
 *  - **Body** the Play-policy apology (`adult_filter_removal_title`), 16sp, centered, onSurface.
 *  - **Buttons** confirm = "Close" (red, SemiBold); dismiss = "Cancel". Matches native EXACTLY:
 *    BOTH buttons AND outside-tap / system back back-navigate. Native's warning passes
 *    `onConfirm = { dialogState = MStep1 }`, but the dialog's "Close" button calls `onDismiss`
 *    (→ `onBackClick`), so `onConfirm` is never invoked — the only reachable outcome is back. The
 *    `:ui` call site mirrors this by wiring "Close" ([onContinue]) to back-navigation as well, so
 *    `MStep1`/`MStep2` are unreachable native-parallel dead code (kept, never reached). The content
 *    is unreachable from every step regardless (the meme steps only ever back-navigate too).
 *
 * Strings resolve through compose-resources, localized per the active app/system locale; the
 * `adult_filter_removal_*` keys mirror the native res catalog so existing translations port.
 */
@Composable
private fun AdultConfirmationDialog(
    onContinue: () -> Unit,
    onGoBack: () -> Unit,
) {
    AlertDialog(
        // Outside-tap / system back → back-navigate (native onDismissRequest → onBackClick).
        onDismissRequest = onGoBack,
        title = {
            Text(
                text = stringResource(Res.string.adult_filter_removal_header),
                fontWeight = FontWeight.Bold,
                color = Color.Red.copy(alpha = 0.8f),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Native: red ic_pluss18 vector, 120.dp size + 8.dp padding, tint red @ .65 alpha.
                Icon(
                    painter = painterResource(Res.drawable.ic_pluss18),
                    contentDescription = stringResource(Res.string.np_details_adult_icon_cd),
                    tint = Color.Red.copy(alpha = 0.65f),
                    modifier = Modifier
                        .size(120.dp)
                        .padding(8.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(Res.string.adult_filter_removal_title),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            // "Close" (red, SemiBold) — back-navigates (native parity: native's Close → onDismiss →
            // onBackClick). The call site wires [onContinue] to back, so MStep1 is never reached.
            TextButton(onClick = onContinue) {
                Text(
                    text = stringResource(Res.string.close),
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Red,
                )
            }
        },
        dismissButton = {
            // "Cancel" — back-navigate.
            TextButton(onClick = onGoBack) {
                Text(
                    text = stringResource(Res.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * P0-ADULT: meme step dialog of the hard-block adult-content gate — faithful `:ui` port of the
 * native `MConfirmationDialog`. Shows one randomly-picked "anti-horny" meme image and (when
 * [showContinue]) a Continue button. NEVER reveals the content: [onConfirm] advances to the next
 * gate step (MStep1→MStep2) and [onDismiss] / outside-tap back-navigates. On the final step
 * ([showContinue] = false) there is no Continue button and every dismiss path back-navigates.
 *
 * Native parity (`presentation/.../details/ui/components/dialogs/MConfirmationDialog.kt`):
 *  - one `remember`-picked random image from [images], rendered 240dp, clipped to a 8dp rounded
 *    corner, `ContentScale.FillBounds`.
 *  - confirm slot renders a "Continue" button ONLY when [showContinue].
 *  - dismiss slot renders a gray "Close" button; outside-tap / system back also dismiss.
 *
 * The meme images are local drawables in this module's Compose Resources bundle (copied from the
 * native res / `:composeApp` bundle), so [painterResource] loads them directly — no Coil needed.
 */
@Composable
private fun MConfirmationDialog(
    images: List<DrawableResource>,
    showContinue: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Pick one random image once per composition (native: `remember { images.random() }`).
    val randomImage = remember(images) { images.random() }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(randomImage),
                    contentDescription = stringResource(Res.string.np_details_adult_icon_cd),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
        },
        confirmButton = {
            if (showContinue) {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(Res.string.continue_string),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(Res.string.close),
                    color = Color.Gray,
                )
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/**
 * P0-ADULT meme-image pools for the two gate steps (native `Plus18memes.imgs1` / `imgs2`). The
 * `anti_horny_*` drawables were copied into this module's Compose Resources bundle (native res +
 * `:composeApp` bundle are on the wrong side of the `:ui` layering boundary). Same membership as
 * native: imgs1 = {2,4,5,6,8,9}, imgs2 = {3,1,7,11,12,13} (anti_horny_10 is absent in native too).
 */
private val ADULT_MEME_IMAGES_STEP1: List<DrawableResource> = listOf(
    Res.drawable.anti_horny_2,
    Res.drawable.anti_horny_4,
    Res.drawable.anti_horny_5,
    Res.drawable.anti_horny_6,
    Res.drawable.anti_horny_8,
    Res.drawable.anti_horny_9,
)

private val ADULT_MEME_IMAGES_STEP2: List<DrawableResource> = listOf(
    Res.drawable.anti_horny_3,
    Res.drawable.anti_horny_1,
    Res.drawable.anti_horny_7,
    Res.drawable.anti_horny_11,
    Res.drawable.anti_horny_12,
    Res.drawable.anti_horny_13,
)

/**
 * First-time-add bookmark confirmation dialog — legacy parity port of the `showAddBookmarkAlert`
 * `AlertDialog` raised by `MangaDetailsScreenRoute` on the un-bookmarked-tap path.
 *
 * Per ADR-2 (slice 1) + §48.6: the visibility flag for this dialog lives in the calling
 * composable's `remember` state, not in MVI state — dialog-style one-shot affordances are
 * within-frame, not cross-frame.
 *
 * Asymmetric posture (matches legacy + plan §253):
 *  - **Add direction** (not in library → confirm): this dialog is shown. Confirm dispatches
 *    [DetailsIntent.OnToggleInLibrary].
 *  - **Remove direction** (in library → unconfirmed): NOT shown. The bookmark IconButton
 *    dispatches `OnToggleInLibrary` directly. Mirrors the legacy `HomeViewModel.toggleManga`
 *    semantics, where only the add path gated on the prompt.
 *
 * Strings resolve through compose-resources (`stringResource(Res.string.<key>)`), localized per
 * the active app/system locale (UP-3 localization track), same as [AdultConfirmationDialog].
 *
 * **Audit-trail postscript** (Phase 9.x.mangadetails.staleKdocSweep.cascade, Task #446,
 * 2026-05-28): the cited `MangaDetailsScreenRoute` was retired in Phase 9.x.mangadetails.retire
 * (§430, Slice 5 of the Phase 7.x.details.parity campaign) along with the legacy
 * `MangaDetailsScreen.kt` it adapted. This dialog is now the SOLE first-time-add bookmark
 * confirmation surface in the Details flow. The asymmetric add/remove posture comparison
 * to `HomeViewModel.toggleManga` remains LIVE — that VM and its `toggleManga` method are
 * preserved (post-§431 bookmarkprune trimmed only the orphan `hasShownRemoveBookMark`
 * chain, not `toggleManga`). Verified by Glob search for `MangaDetailsScreenRoute.kt`
 * returning zero hits. Original prose preserved verbatim per §253 — the legacy-parity reference
 * stands as historical record of the design lineage.
 */
@Composable
private fun AddBookmarkConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.add_library_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            // M-10 parity: native add-to-library dialog uses the long explanatory body
            // (`add_library_message`) — "To download chapters and keep track of your reading
            // progress, please add this manga to your library first..." — not the short
            // "Save this manga to your library?".
            Text(
                text = stringResource(Res.string.details_add_library_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            // M-10 parity: native confirm label is "Add to Library" (`confirm_add_to_library`),
            // not "Add".
            TextButton(onClick = onConfirm) {
                Text(stringResource(Res.string.details_confirm_add_to_library), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}
