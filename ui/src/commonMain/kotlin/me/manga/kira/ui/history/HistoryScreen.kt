package me.manga.kira.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import me.manga.kira.domain.model.history.HistoryEntry
import me.manga.kira.presentation.history.HistoryEffect
import me.manga.kira.presentation.history.HistoryIntent
import me.manga.kira.presentation.history.HistoryState
import me.manga.kira.presentation.history.HistoryViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraLoadingState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.content_description_clear_history
import me.manga.kira.ui.generated.resources.content_description_delete
import me.manga.kira.ui.generated.resources.days_ago
import me.manga.kira.ui.generated.resources.history_title
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
import me.manga.kira.ui.generated.resources.months_ago
import me.manga.kira.ui.generated.resources.no_reading_history
import me.manga.kira.ui.generated.resources.today
import me.manga.kira.ui.generated.resources.weeks_ago
import me.manga.kira.ui.generated.resources.years_ago
import me.manga.kira.ui.generated.resources.yesterday
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.util.formatLocalizedInt
import org.jetbrains.compose.resources.stringResource

/**
 * History screen — Compose entry point for the History MVI slice.
 *
 * Phase 7.x.history rework. Renders [HistoryState] (a flat list grouped by read-date) and
 * dispatches [HistoryIntent]. One-shot navigational [HistoryEffect]s are collected via a
 * [LaunchedEffect] and forwarded through `onNavigateToDetails` / `onNavigateToReader` —
 * same posture as [me.manga.kira.ui.library.LibraryScreen]: the route adapter in
 * `:composeApp` owns the nav-graph specifics, the screen stays nav-host-agnostic.
 *
 * **Redesign 2026-06 (mockup-driven, `design/redesign/screens/history.html`)**: the plain M3
 * `TopAppBar` is replaced by a Home-style big-title header ([HistoryHeader]) — a large bold
 * "History" title with a circular clear-all action button styled like `HomeScreen`'s
 * `HeaderAction`. The header owns its own status-bar inset via `statusBarsPadding()` (mirroring
 * the M3 TopAppBar it replaced). Date-section labels render as an uppercase muted eyebrow, and each
 * history entry is a rounded `surface` Card row (rounded cover + title + "Chapter N · time" meta +
 * a per-row delete icon button styled like the mockup `.iconbtn`). Design tokens flow from
 * [MaterialTheme.colorScheme] / [LocalSpacing] / [RoundedCornerShape]; no hardcoded hex.
 *
 * **Behaviour parity** — unchanged from the prior rework: same MVI state/intents/callbacks, same
 * date-grouping algorithm, same relative-date / group-label formatters, same empty / loading
 * branches, same clear-all-disabled-when-empty rule, same `AsyncImage` cover posture, same
 * `contentWindowInsets(0)` + `LocalBottomBarPadding` bottom contentPadding. Only the visual layout
 * changed.
 *
 * **Date grouping**: identical algorithm to legacy `HistoryScreen.groupItemsByDate` —
 * `groupBy { it.lastReadDate.date }` + sort-by-date-desc + rebuild into a `LinkedHashMap`
 * (the JVM-only `toSortedMap` is not available in KMP commonMain). Group labels use the
 * same "Today / Yesterday / N days ago / MMM d, yyyy" formatter the legacy uses.
 *
 * **Cover thumbnail**: plain `AsyncImage(model = url)` — the singleton ImageLoader (set
 * in `:composeApp/App.kt` via `setSingletonImageLoaderFactory`) carries the AVIF decoder,
 * OkHttp fetcher, max-bitmap-size override, HighQualitySkiaImageDecoder, and the
 * `CoilSourceHeaderInterceptor` that transparently attaches per-source headers. No
 * per-screen `ImageRequest.Builder` needed (cf. [me.manga.kira.ui.library.LibraryScreen]
 * `LibraryCardCover` posture).
 *
 * **Empty state**: when [HistoryState.isEmpty] (not loading and no items), render a
 * centered "No reading history yet" placeholder. The clear-all action stays disabled in
 * that state (no-op via `enabled = false`) for clarity.
 *
 * Stateless inner [HistoryScreenContent] mirrors the [me.manga.kira.ui.library.LibraryScreen]
 * pattern — separating "wire to VM" from "render state" so previews / tests can feed canned
 * state without spinning up a real VM. SRP-clean separation.
 */
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateToDetails: (HistoryEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    HistoryScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateToDetails = onNavigateToDetails,
        onNavigateToReader = onNavigateToReader,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HistoryScreenContent(
    state: HistoryState,
    effects: Flow<HistoryEffect>,
    onIntent: (HistoryIntent) -> Unit,
    onNavigateToDetails: (HistoryEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is HistoryEffect.NavigateToDetails -> onNavigateToDetails(effect)
                is HistoryEffect.NavigateToReader -> onNavigateToReader(effect.entry)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Redesign 2026-06: a big-title header (mockup `.header`) replaces the plain M3 TopAppBar.
        // It owns the status-bar inset via `statusBarsPadding()` like the bar it replaced.
        topBar = {
            HistoryHeader(
                clearEnabled = state.items.isNotEmpty(),
                onClearAll = { onIntent(HistoryIntent.OnDeleteAll) },
            )
        },
        // The floating bottom nav overlays content from the app root; its inset reaches the list via
        // LocalBottomBarPadding (added to the list's bottom contentPadding). Zero the Scaffold insets
        // so the bottom isn't double-counted (the header owns the status-bar inset).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                state.isLoading -> KiraLoadingState()
                state.isEmpty -> KiraEmptyState(title = stringResource(Res.string.no_reading_history))
                else -> HistoryList(
                    items = state.items,
                    onIntent = onIntent,
                )
            }
        }
    }
}

/**
 * Redesign 2026-06 header (mockup `.header`): a large bold "History" title with a circular clear-all
 * action button on the trailing edge, styled like `HomeScreen`'s `HeaderAction`. The mockup's
 * "RECENTLY READ" eyebrow is intentionally omitted — no existing string-resource key carries it and
 * the i18n parity gate forbids inventing one. Owns its own status-bar inset (mirrors the M3
 * TopAppBar it replaced); the destructive clear-all keeps the `DeleteForever`/`error`-tint affordance
 * and the existing content description and enabled-when-non-empty rule.
 */
@Composable
private fun HistoryHeader(
    clearEnabled: Boolean,
    onClearAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.history_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            modifier = Modifier.weight(1f),
        )
        // Circular clear-all action (mockup `.cbtn`): 42dp rounded square, surface-variant fill,
        // destructive DeleteForever glyph in the error tint. Disabled (no-op) when there is no history.
        Surface(
            onClick = onClearAll,
            enabled = clearEnabled,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = stringResource(Res.string.content_description_clear_history),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryList(
    items: List<HistoryEntry>,
    onIntent: (HistoryIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val grouped = remember(items) { groupByDate(items) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Redesign 2026-06: rows carry their own 12dp vertical gap; add matching top/bottom breathing
        // room and keep the floating-nav bottom inset (LocalBottomBarPadding) on the last row.
        contentPadding = PaddingValues(
            top = spacing.xs,
            bottom = spacing.sm + LocalBottomBarPadding.current,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        grouped.forEach { (date, dayEntries) ->
            // Redesign 2026-06: date-section eyebrow (mockup `.glabel`) — uppercase, tracked, muted.
            stickyHeader(key = "header-${date.year}-${date.month.number}-${date.day}") {
                Text(
                    text = formatGroupLabel(date).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 20.dp, end = 20.dp, top = spacing.sm, bottom = spacing.sm),
                )
            }

            items(items = dayEntries, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    onChapterClick = { onIntent(HistoryIntent.OnChapterClick(entry)) },
                    onMangaClick = { onIntent(HistoryIntent.OnMangaClick(entry)) },
                    onDeleteClick = { onIntent(HistoryIntent.OnDeleteEntry(entry)) },
                )
            }
        }
    }
}

/**
 * Redesign 2026-06 history row (mockup `.row`): a rounded `surface` Card holding a rounded cover
 * thumbnail, the manga title (2-line clamp), a "Chapter · time" meta line with a dimmed dot
 * separator, and a circular delete action button (mockup `.iconbtn`). The whole card opens the
 * chapter; the cover taps through to manga details; the trailing icon deletes the entry — identical
 * intents to the prior rework, only the visual treatment changed.
 */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onChapterClick: () -> Unit,
    onMangaClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.lg)
            .clickable(onClick = onChapterClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mockup `.rcover`: 64×86 rounded cover. Plain AsyncImage on the singleton ImageLoader.
            AsyncImage(
                model = entry.mangaImageUrl,
                contentDescription = entry.mangaTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                    .clickable(onClick = onMangaClick),
            )

            Spacer(Modifier.width(spacing.md))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = entry.mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Mockup `.meta`: "Chapter N" · "time" with a dimmed dot separator. The chapter title
                // and relative-date strings are unchanged; the " · " separator is a literal glyph
                // (not a string-resource key).
                Text(
                    text = "${entry.chapterTitle} · ${formatRelativeDate(entry.lastReadDate)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(spacing.sm))

            // Mockup `.iconbtn`: circular delete action; neutral muted tint (only the header clear-all
            // carries the destructive error color). Outlined.Delete glyph, matching native HistoryItem.
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(42.dp),
            ) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(Res.string.content_description_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/**
 * Groups history items by their `lastReadDate.date`, preserving a date-descending iteration
 * order. KMP commonMain has no `TreeMap` / `toSortedMap`, so the result is built into a
 * `LinkedHashMap` after sorting the entries — same workaround the legacy uses.
 */
private fun groupByDate(items: List<HistoryEntry>): Map<LocalDate, List<HistoryEntry>> =
    items
        .groupBy { it.lastReadDate.date }
        .entries
        .sortedByDescending { it.key }
        .associate { it.key to it.value }

@OptIn(ExperimentalTime::class)
@Composable
private fun formatGroupLabel(date: LocalDate): String {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val days = date.daysUntil(today).toLong()
    return when {
        days == 0L -> stringResource(Res.string.today)
        days == 1L -> stringResource(Res.string.yesterday)
        days in 2..6 -> stringResource(Res.string.days_ago, formatLocalizedInt(days.toInt()))
        // GAP-HIST-07 / P3-HIST: absolute-date fallback (>6 days) renders a localized month name
        // via the per-month string resources; Arabic values now ship in the P3 history pfix
        // catalog so the header localizes under RTL, matching native's locale-aware formatter.
        else -> "${monthAbbrev(date.month.number)} ${date.day}, ${date.year}"
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun formatRelativeDate(date: LocalDateTime): String {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val days = date.date.daysUntil(today).toLong().coerceAtLeast(0L)
    return when {
        days == 0L -> stringResource(Res.string.today)
        days == 1L -> stringResource(Res.string.yesterday)
        days < 7 -> stringResource(Res.string.days_ago, formatLocalizedInt(days.toInt()))
        days < 30 -> stringResource(Res.string.weeks_ago, formatLocalizedInt((days / 7).toInt()))
        days < 365 -> stringResource(Res.string.months_ago, formatLocalizedInt((days / 30).toInt()))
        else -> stringResource(Res.string.years_ago, formatLocalizedInt((days / 365).toInt()))
    }
}

/**
 * GAP-HIST-07: localized month abbreviation. Resolves a per-month string resource
 * (`month_abbrev_*`) so the absolute-date group header renders Arabic month names under RTL
 * instead of a hard-coded English array. en and ar values both ship (ar in the P3 history pfix
 * catalog), so the header localizes like native's locale-aware date formatter.
 */
@Composable
private fun monthAbbrev(month: Int): String = when (month) {
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
