package me.manga.kira.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FileDownloadDone
import androidx.compose.material.icons.outlined.NotStarted
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.manga.kira.presentation.statistics.StatisticsState
import me.manga.kira.presentation.statistics.StatisticsViewModel
import me.manga.kira.ui.components.KiraLoadingState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.desc_back
import me.manga.kira.ui.generated.resources.h_m
import me.manga.kira.ui.generated.resources.label_bookmarked
import me.manga.kira.ui.generated.resources.label_completed
import me.manga.kira.ui.generated.resources.label_completed_entries
import me.manga.kira.ui.generated.resources.label_downloaded
import me.manga.kira.ui.generated.resources.label_in_library
import me.manga.kira.ui.generated.resources.label_read
import me.manga.kira.ui.generated.resources.label_read_duration
import me.manga.kira.ui.generated.resources.label_started
import me.manga.kira.ui.generated.resources.label_total
import me.manga.kira.ui.generated.resources.section_chapters
import me.manga.kira.ui.generated.resources.section_entries
import me.manga.kira.ui.generated.resources.statistics
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.theme.KiraBrand
import me.manga.kira.ui.util.formatGroupedNumber
import org.jetbrains.compose.resources.stringResource

/**
 * Statistics screen — Compose entry point for the Statistics MVI slice.
 *
 * Renders [StatisticsState]'s reading-statistics aggregates in the **Redesign** visual language
 * (coral→amber brand accent, rounded cards, ranked progress bars). The state exposes only the
 * eight numbers below, so this screen visualizes exactly those — no time-series "reading activity"
 * and no per-source "top sources" aggregate exists in state, so neither is fabricated (the mockup
 * shows both, but they are intentionally omitted here because the data is not in the MVI state):
 *
 *  - **Overview card** at the top — the three big-number aggregates (in-library count,
 *    read-duration string, completed-entries count) laid out as a horizontal 3-cell row, the
 *    middle "Read duration" cell accented coral (`colorScheme.primary`) to match the Redesign
 *    mockup's accented middle cell.
 *  - **Entries section** — three ranked rows (in-library, started, completed), each with a leading
 *    outlined icon, label, a horizontal coral progress bar sized relative to the section's max
 *    count, and the count.
 *  - **Chapters section** — four ranked rows (total, read, downloaded, bookmarked), same ranked
 *    treatment, bars relative to the section's max count.
 *
 * Stateless inner [StatisticsScreenContent] mirrors the [me.manga.kira.ui.library.LibraryScreen]
 * pattern — separating "wire to VM" from "render state" so previews / tests can feed canned
 * state without spinning up a real VM. No intents are dispatched (the slice has no user-driven
 * mutations — see [me.manga.kira.presentation.statistics.StatisticsIntent] KDoc).
 *
 * **Visual notes**:
 *  - The top bar keeps native parity (Statistics is NOT a tab screen): a back-arrow `IconButton`
 *    tinted `onBackground` with the localized `desc_back` description, and a 24sp Bold title on a
 *    `background` container.
 *  - Cards use `RoundedCornerShape(18.dp)` over `surfaceContainerHigh`; progress bars use
 *    [KiraBrand.Gradient] (coral → amber). All sizing comes from [LocalSpacing]; numbers are
 *    locale-grouped via [formatGroupedNumber] and the read duration is re-localized through the
 *    `h_m` resource.
 *  - All labels resolve through `stringResource` (reused `statistics` / `section_*` / `label_*`
 *    keys across the native-supported locales). No new string keys are introduced.
 *
 * **Loading state**: while `state.isLoading` is true (between subscription and the first upstream
 * emission), the shared [KiraLoadingState] renders, avoiding a one-frame "all zeros" flash.
 */
@Composable
fun StatisticsScreen(
    viewModel: StatisticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    StatisticsScreenContent(
        state = state,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatisticsScreenContent(
    state: StatisticsState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                // Native parity (TopAppBarCom.kt:22-40): 24sp Bold titleLarge, onBackground text,
                // background-colored container.
                title = {
                    Text(
                        text = stringResource(Res.string.statistics),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                navigationIcon = {
                    // Native parity (StatisticsScreen.kt:53-55): an AutoMirrored ArrowBack
                    // IconButton tinted onBackground with the localized desc_back description
                    // (RTL-mirrored), not a text "Back" label.
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.desc_back),
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            // GAP-STAT-03: reuse the shared design-system loading state (matching History /
            // Updates) instead of a hand-rolled centered CircularProgressIndicator.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                KiraLoadingState()
            }
            return@Scaffold
        }
        val spacing = LocalSpacing.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(rememberScrollState())
                .padding(spacing.lg),
        ) {
            StatsOverview(
                inLibrary = state.inLibrary,
                readMinutes = state.readMinutes,
                entriesCompleted = state.entriesCompleted,
            )

            Spacer(Modifier.height(spacing.lg))

            // Entries — ranked rows with coral bars relative to the section's max count.
            SectionTitle(stringResource(Res.string.section_entries))
            StatsRankedGroup {
                val entries = listOf(
                    StatsRow(stringResource(Res.string.label_in_library), Icons.AutoMirrored.Outlined.LibraryBooks, state.inLibrary),
                    StatsRow(stringResource(Res.string.label_started), Icons.Outlined.NotStarted, state.entriesStarted),
                    StatsRow(stringResource(Res.string.label_completed), Icons.Outlined.DoneAll, state.entriesCompleted),
                )
                val max = entries.maxOf { it.value }
                entries.forEach { row ->
                    StatsRankedItem(title = row.title, icon = row.icon, value = row.value, max = max)
                }
            }

            Spacer(Modifier.height(spacing.lg))

            // Chapters — ranked rows with coral bars relative to the section's max count.
            SectionTitle(stringResource(Res.string.section_chapters))
            StatsRankedGroup {
                val chapters = listOf(
                    StatsRow(stringResource(Res.string.label_total), Icons.Outlined.SelectAll, state.chaptersTotal),
                    StatsRow(stringResource(Res.string.label_read), Icons.Outlined.RemoveRedEye, state.chaptersRead),
                    StatsRow(stringResource(Res.string.label_downloaded), Icons.Outlined.FileDownloadDone, state.chaptersDownloaded),
                    StatsRow(stringResource(Res.string.label_bookmarked), Icons.Outlined.BookmarkAdd, state.chaptersBookmarked),
                )
                val max = chapters.maxOf { it.value }
                chapters.forEach { row ->
                    StatsRankedItem(title = row.title, icon = row.icon, value = row.value, max = max)
                }
            }
        }
    }
}

/** Lightweight carrier for one ranked stat row — title + leading icon + the existing count. */
private data class StatsRow(val title: String, val icon: ImageVector, val value: Int)

@Composable
private fun StatsOverview(
    inLibrary: Int,
    readMinutes: Int,
    entriesCompleted: Int,
) {
    // Redesign overview card: `surfaceContainerHigh` container, rounded 18.dp, a `Row(spacedBy)` of
    // three equal-weight big-number cells. The middle "Read duration" cell is accented coral to
    // mirror the mockup's accented centre cell.
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsOverviewCell(
                value = formatCount(inLibrary),
                label = stringResource(Res.string.label_in_library),
                modifier = Modifier.weight(1f),
            )
            StatsOverviewCell(
                // Typed wire (2026-07 backlog L15): the raw minute counter arrives from the domain
                // model and is rendered here with the locale-specific unit suffixes (native parity,
                // StatisticsRepository.kt:44-48 — e.g. `7h 27m` → `7時 27分` on Japanese).
                value = stringResource(Res.string.h_m, readMinutes / 60, readMinutes % 60),
                label = stringResource(Res.string.label_read_duration),
                // Redesign: the middle overview value is the single coral accent in the card.
                valueColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            StatsOverviewCell(
                // Native parity (StatsOverview.kt:53): the overview big-number cell uses
                // label_completed_entries ("Completed entries"), distinct from the Entries-section
                // row's label_completed ("Completed").
                value = formatCount(entriesCompleted),
                label = stringResource(Res.string.label_completed_entries),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatsOverviewCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val spacing = LocalSpacing.current
    // Redesign overview cell: value 24.sp ExtraBold, label 12.sp onSurfaceVariant.
    Column(
        modifier = modifier.padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = valueColor,
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    val spacing = LocalSpacing.current
    // Native parity (SectionTitle.kt:14-21): 14.sp Bold, onBackground, padding(bottom = 8.dp) only.
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = spacing.sm),
    )
}

@Composable
private fun StatsRankedGroup(content: @Composable () -> Unit) {
    val spacing = LocalSpacing.current
    // Redesign group card: surfaceContainerHigh container, rounded 18.dp; the group supplies the
    // horizontal=16 / vertical=8 inset. Per-row vertical padding lives on StatsRankedItem.
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.sm),
        ) {
            content()
        }
    }
}

/**
 * Redesign ranked stat row (mockup "Top sources" treatment, applied to the existing Entries /
 * Chapters counts): a leading 24dp outlined icon, the label, the count on the right, and below the
 * label a horizontal coral progress bar whose fill is `value / max` of the section. Purely visual —
 * it ranks aggregates that already exist in state; it does not introduce any new data.
 */
@Composable
private fun StatsRankedItem(title: String, icon: ImageVector, value: Int, max: Int) {
    val spacing = LocalSpacing.current
    val fraction = if (max > 0) value.toFloat() / max.toFloat() else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading 24dp outlined icon, tinted onBackground (native StatsItem parity).
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(spacing.sm))
            StatsProgressBar(fraction = fraction)
        }
        Spacer(Modifier.width(spacing.md))
        // The count — coral, ExtraBold (the row's emphasis), locale-grouped.
        Text(
            text = formatCount(value),
            fontSize = 14.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Thin rounded progress bar with a coral→amber [KiraBrand.Gradient] fill over a faint track. The
 * fill width is `fraction` of the available width; the track reads as `onBackground` at low alpha
 * so it works on light, dark and pure-black themes.
 */
@Composable
private fun StatsProgressBar(fraction: Float) {
    val clamped = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(clamped)
                .height(7.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(KiraBrand.Gradient),
        )
    }
}

/**
 * GAP-STAT-01: native parity thousands grouping (the legacy `R.string.value_count` = `"%,d"`).
 * Native's `%,d` is LOCALE-AWARE (Arabic-Indic digits + Arabic grouping mark, German '.', French
 * thin-space, …), so this routes through the platform number formatter ([formatGroupedNumber]) rather
 * than hardcoding a U+002C comma — which previously diverged from native in ar/de/fr/ru at 4+ digits.
 */
private fun formatCount(value: Int): String = formatGroupedNumber(value.toLong())
