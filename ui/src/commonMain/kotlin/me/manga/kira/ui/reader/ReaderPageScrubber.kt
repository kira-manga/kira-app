package me.manga.kira.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import me.manga.kira.presentation.reader.ReaderIntent
import me.manga.kira.presentation.reader.ReaderState
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.reader_next_chapter
import me.manga.kira.ui.generated.resources.reader_previous_chapter
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

private const val CHAPTER_SLIDER_WEIGHT = 9f

// Unit-emitting composables follow the PascalCase Compose convention.

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
 * Slider config: chapter-relative value coerced into `0f..lastIndex`; `steps = pageCount - 2`
 * (Material3 counts internal steps, so N-2 internal + 2 endpoints = N discrete page positions).
 * A one-page active chapter keeps both chapter buttons but replaces only the slider pill with an
 * inert spacer; the existing HUD supplies its page count. No pages means no controls. This real
 * state-to-controls boundary also owns relative-to-absolute seeking for an appended chapter feed.
 */
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ReaderPageScrubber(
    state: ReaderState,
    onIntent: (ReaderIntent) -> Unit,
) {
    val pageCount = state.activeChapterPageCount
    if (!state.hasPages || pageCount < 1) return
    val spacing = LocalSpacing.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.sm, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        ChapterNavigationPill(isNext = true, enabled = state.canGoNext) {
            onIntent(ReaderIntent.OnNextChapter)
        }
        if (pageCount > 1) {
            ChapterSliderPill(state, Modifier.weight(1f), onIntent)
        } else {
            Spacer(Modifier.weight(1f))
        }
        ChapterNavigationPill(isNext = false, enabled = state.canGoPrev) {
            onIntent(ReaderIntent.OnPrevChapter)
        }
    }
}

// Unit-emitting composables follow the PascalCase Compose convention.
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ChapterNavigationPill(
    isNext: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ReaderControlPill {
        KiraIconButton(
            icon = if (isNext) KiraIcons.PrevChapter else KiraIcons.NextChapter,
            contentDescription =
                stringResource(
                    if (isNext) Res.string.reader_next_chapter else Res.string.reader_previous_chapter,
                ),
            onClick = onClick,
            enabled = enabled,
        )
    }
}

// Unit-emitting composables follow the PascalCase Compose convention.
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ChapterSliderPill(
    state: ReaderState,
    modifier: Modifier,
    onIntent: (ReaderIntent) -> Unit,
) {
    val positionInChapter = (state.activeChapterPageNumber - 1).coerceAtLeast(0)
    val pageCount = state.activeChapterPageCount
    ReaderControlPill(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = LocalSpacing.current.sm),
        ) {
            ChapterPageNumber(positionInChapter + 1, Modifier.weight(1f))
            ChapterSlider(positionInChapter, pageCount, Modifier.weight(CHAPTER_SLIDER_WEIGHT)) { relative ->
                state.activeChapterPageIndices.getOrNull(relative)?.let { absolute ->
                    onIntent(ReaderIntent.OnPageChanged(absolute))
                }
            }
            ChapterPageNumber(pageCount, Modifier.weight(1f))
        }
    }
}

// Unit-emitting composables follow the PascalCase Compose convention.
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ChapterSlider(
    position: Int,
    pageCount: Int,
    modifier: Modifier,
    onSeek: (Int) -> Unit,
) {
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    Slider(
        value = position.toFloat().coerceIn(0f, lastIndex.toFloat()),
        onValueChange = { newValue ->
            val relative = newValue.roundToInt().coerceIn(0, lastIndex)
            if (relative != position) onSeek(relative)
        },
        valueRange = 0f..lastIndex.toFloat(),
        steps = (pageCount - 2).coerceAtLeast(0),
        modifier = modifier,
    )
}

// Unit-emitting composables follow the PascalCase Compose convention.
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ChapterPageNumber(
    value: Int,
    modifier: Modifier,
) {
    Text(
        text = "$value",
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

// Unit-emitting composables follow the PascalCase Compose convention.
@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ReaderControlPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
        modifier = modifier,
        content = content,
    )
}
