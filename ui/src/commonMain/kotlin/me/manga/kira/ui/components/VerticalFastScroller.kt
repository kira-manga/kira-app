package me.manga.kira.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMaxBy
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fast-scroller overlay for a [LazyListState] — restores the legacy `VerticalFastScroller`
 * quick-jump scrollbar that the rework dropped, so long lists (the Details chapter list) get a
 * draggable thumb again.
 *
 * Behaviour (legacy parity with
 * `presentation/common/componants/scroll/LazyVerticalScrollerWithScrollBar.kt`):
 *  - A thumb is drawn on the (layout-direction) end edge whose vertical position tracks the list's
 *    scroll progress.
 *  - Dragging the thumb scrolls the list proportionally (`scrollToItem`).
 *  - The thumb fades in while scrolling/dragging and fades out ~2s after activity stops.
 *  - The thumb is only shown when the list actually overflows (more total items than visible).
 *
 * **`:ui`-scope deltas vs. the original :composeApp port:**
 *  1. The native `Modifier.systemGestureExclusion()` on the thumb is now restored via the no-arg
 *     [gestureExclusion] expect/actual seam (Android = `systemGestureExclusion()`, iOS/desktop =
 *     no-op), applied under the same `isThumbVisible && !isThumbDragged && !isScrollInProgress`
 *     condition as native — so an edge / predictive-back swipe starting on the thumb drags the thumb
 *     rather than being consumed by the OS back gesture. (Earlier the helper lived in `:composeApp`
 *     and could not be reached from here; lifting it into a local expect/actual closed that gap.)
 *  2. The legacy `computeScrollOffset`/`computeScrollRange` used a `!!` on the first
 *     non-sticky-header visible item. Rewritten here to fall back to `visibleItems.first()` when
 *     no non-header item is present, so there is no non-null assertion.
 *  3. The thumb fade duration is read from the platform via the [scrollBarFadeDurationMs]
 *     expect/actual seam: Android returns `ViewConfiguration.getScrollBarFadeDuration()` (the
 *     OEM-configured value the native `LazyVerticalScrollerWithScrollBar` uses); iOS/desktop fall
 *     back to the historical 250ms Android default. This restores exact platform parity that the
 *     earlier hardcoded-250ms :composeApp port could not reach from common code.
 *
 * The drag gesture feel needs an on-device pass to fully verify; the structure/math mirror the
 * legacy implementation 1:1.
 */
@OptIn(FlowPreview::class)
@Composable
fun VerticalFastScroller(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = listState.layoutInfo
            val showScroller = layoutInfo.visibleItemsInfo.size < layoutInfo.totalItemsCount
            if (!showScroller) return@subcompose

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
            val scrolled = remember {
                MutableSharedFlow<Unit>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                thumbTopPadding -
                thumbBottomPadding -
                listState.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx

            // When thumb dragged
            LaunchedEffect(thumbOffsetY) {
                if (layoutInfo.totalItemsCount == 0 || !isThumbDragged) return@LaunchedEffect
                val safeTrack = if (trackHeightPx > 0f && trackHeightPx.isFinite()) {
                    trackHeightPx
                } else {
                    return@LaunchedEffect
                }
                val scrollRatio = ((thumbOffsetY - thumbTopPadding) / safeTrack)
                    .coerceIn(0f, 1f)
                val scrollItem = layoutInfo.totalItemsCount * scrollRatio
                val scrollItemRounded = scrollItem.roundToInt()
                val scrollItemSize = layoutInfo.visibleItemsInfo.find { it.index == scrollItemRounded }?.size ?: 0
                val scrollItemOffset = scrollItemSize * (scrollItem - scrollItemRounded)
                listState.scrollToItem(index = scrollItemRounded, scrollOffset = scrollItemOffset.roundToInt())
                scrolled.tryEmit(Unit)
            }

            // When list scrolled
            LaunchedEffect(listState.firstVisibleItemScrollOffset) {
                if (listState.layoutInfo.totalItemsCount == 0 || isThumbDragged) return@LaunchedEffect
                val scrollOffset = computeScrollOffset(state = listState)
                val scrollRange = computeScrollRange(state = listState)
                val available = (scrollRange.toFloat() - heightPx)
                val proportion = if (available > 0f && available.isFinite()) {
                    (scrollOffset.toFloat() / available).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val newOffset = trackHeightPx * proportion + thumbTopPadding
                thumbOffsetY = if (newOffset.isFinite()) newOffset else thumbTopPadding
                scrolled.tryEmit(Unit)
            }

            // Thumb alpha
            val alpha = remember { Animatable(0f) }
            val isThumbVisible = alpha.value > 0f
            val fadeOutSpec = remember { fadeOutAnimationSpec() }
            val immediateFadeOutSpec = remember { immediateFadeOutAnimationSpec() }
            LaunchedEffect(scrolled, alpha) {
                scrolled
                    .sample(100)
                    .collectLatest {
                        if (thumbAllowed()) {
                            alpha.snapTo(1f)
                            alpha.animateTo(0f, animationSpec = fadeOutSpec)
                        } else {
                            alpha.animateTo(0f, animationSpec = immediateFadeOutSpec)
                        }
                    }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        // Recompose opts
                        if (isThumbVisible && !listState.isScrollInProgress) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Exclude the thumb region from the OS edge / predictive-back gesture only when
                        // it is the active drag target — matches native LazyVerticalScrollerWithScrollBar.
                        // Android = systemGestureExclusion(); iOS/desktop = no-op (see gestureExclusion).
                        if (isThumbVisible && !isThumbDragged && !listState.isScrollInProgress) {
                            Modifier.gestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    .height(ThumbLength)
                    .padding(horizontal = 8.dp)
                    .padding(end = endContentPadding)
                    .width(ThumbThickness)
                    .alpha(alpha.value)
                    .background(color = thumbColor, shape = ThumbShape),
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

/**
 * Per-column cumulative cross-axis widths for a [LazyVerticalGrid] laid out with [columns] and
 * [horizontalArrangement] inside [contentPadding]. Mirrors the legacy
 * `LazyVerticalScrollerWithScrollBar.rememberColumnWidthSums`: each entry is the running sum of cell
 * widths so callers can derive the grid's column count (`.size`). Used by [VerticalGridFastScroller]
 * to map a thumb position onto a grid item index.
 */
@Composable
private fun rememberColumnWidthSums(
    columns: GridCells,
    horizontalArrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
) = remember<Density.(Constraints) -> List<Int>>(
    columns,
    horizontalArrangement,
    contentPadding,
) {
    { constraints ->
        require(constraints.maxWidth != Constraints.Infinity) {
            "LazyVerticalGrid's width should be bound by parent"
        }
        val horizontalPadding = contentPadding.calculateStartPadding(LayoutDirection.Ltr) +
            contentPadding.calculateEndPadding(LayoutDirection.Ltr)
        val gridWidth = constraints.maxWidth - horizontalPadding.roundToPx()
        with(columns) {
            calculateCrossAxisCellSizes(
                gridWidth,
                horizontalArrangement.spacing.roundToPx(),
            ).toMutableList().apply {
                for (i in 1..<size) {
                    this[i] += this[i - 1]
                }
            }
        }
    }
}

/**
 * Fast-scroller overlay for a [LazyGridState] — the grid sibling of [VerticalFastScroller], restored
 * from the legacy `presentation/common/componants/scroll/LazyVerticalScrollerWithScrollBar.kt:229`
 * `VerticalGridFastScroller`. Wrap a `LazyVerticalGrid` with this (passing the same [columns],
 * [arrangement], and [contentPadding] the grid uses) to get the draggable quick-jump thumb the
 * native Library grid had — the KMP rework had ported only the list variant.
 *
 * Behaviour matches [VerticalFastScroller] (thumb tracks scroll progress, dragging scrolls
 * proportionally, fade-in/out, only shown on overflow). The grid-specific drag math (mapping a thumb
 * ratio onto an item index across [columnCount] columns) and the grid `computeScrollOffset`/
 * `computeScrollRange` are ported 1:1 from the legacy implementation.
 *
 * Carries the same `:ui`-scope deltas as [VerticalFastScroller]: the thumb's native
 * `systemGestureExclusion()` is restored via the [gestureExclusion] expect/actual seam (Android =
 * real exclusion, iOS/desktop = no-op), and the fade duration comes from the [scrollBarFadeDurationMs]
 * expect/actual seam (Android = `ViewConfiguration.getScrollBarFadeDuration()`, iOS/desktop = 250ms).
 */
@OptIn(FlowPreview::class)
@Composable
fun VerticalGridFastScroller(
    state: LazyGridState,
    columns: GridCells,
    arrangement: Arrangement.Horizontal,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    thumbAllowed: () -> Boolean = { true },
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    topContentPadding: Dp = Dp.Hairline,
    bottomContentPadding: Dp = Dp.Hairline,
    endContentPadding: Dp = Dp.Hairline,
    content: @Composable () -> Unit,
) {
    val slotSizesSums = rememberColumnWidthSums(
        columns = columns,
        horizontalArrangement = arrangement,
        contentPadding = contentPadding,
    )

    SubcomposeLayout(modifier = modifier) { constraints ->
        val contentPlaceable = subcompose("content", content).map { it.measure(constraints) }
        val contentHeight = contentPlaceable.fastMaxBy { it.height }?.height ?: 0
        val contentWidth = contentPlaceable.fastMaxBy { it.width }?.width ?: 0

        val scrollerConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val scrollerPlaceable = subcompose("scroller") {
            val layoutInfo = state.layoutInfo
            val showScroller = layoutInfo.visibleItemsInfo.size < layoutInfo.totalItemsCount
            if (!showScroller) return@subcompose

            val thumbTopPadding = with(LocalDensity.current) { topContentPadding.toPx() }
            var thumbOffsetY by remember(thumbTopPadding) { mutableFloatStateOf(thumbTopPadding) }

            val dragInteractionSource = remember { MutableInteractionSource() }
            val isThumbDragged by dragInteractionSource.collectIsDraggedAsState()
            val scrolled = remember {
                MutableSharedFlow<Unit>(
                    extraBufferCapacity = 1,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
            }

            val thumbBottomPadding = with(LocalDensity.current) { bottomContentPadding.toPx() }
            val heightPx = contentHeight.toFloat() -
                thumbTopPadding -
                thumbBottomPadding -
                state.layoutInfo.afterContentPadding
            val thumbHeightPx = with(LocalDensity.current) { ThumbLength.toPx() }
            val trackHeightPx = heightPx - thumbHeightPx

            val columnCount = remember(constraints.maxWidth, slotSizesSums) {
                slotSizesSums(constraints).size
            }

            // When thumb dragged
            LaunchedEffect(thumbOffsetY) {
                if (layoutInfo.totalItemsCount == 0 || !isThumbDragged) return@LaunchedEffect
                val safeTrack = if (trackHeightPx > 0f && trackHeightPx.isFinite()) {
                    trackHeightPx
                } else {
                    return@LaunchedEffect
                }
                val scrollRatio = ((thumbOffsetY - thumbTopPadding) / safeTrack)
                    .coerceIn(0f, 1f)
                val scrollItem = layoutInfo.totalItemsCount * scrollRatio
                val scrollItemWhole = scrollItem.toInt()
                val columnNum = ((scrollItemWhole + 1) % columnCount).takeIf { it != 0 } ?: columnCount
                val scrollItemFraction = if (scrollItemWhole == 0) scrollItem else scrollItem % scrollItemWhole
                val offsetPerItem = 1f / columnCount
                val offsetRatio = (offsetPerItem * scrollItemFraction) + (offsetPerItem * (columnNum - 1))

                val scrollItemSize = (1..columnCount).maxOf { num ->
                    val actualIndex = if (num != columnNum) {
                        scrollItemWhole + num - columnCount
                    } else {
                        scrollItemWhole
                    }
                    layoutInfo.visibleItemsInfo.find { it.index == actualIndex }?.size?.height ?: 0
                }
                val scrollItemOffset = scrollItemSize * offsetRatio

                state.scrollToItem(index = scrollItemWhole, scrollOffset = scrollItemOffset.roundToInt())
                scrolled.tryEmit(Unit)
            }

            // When grid scrolled
            LaunchedEffect(state.firstVisibleItemScrollOffset) {
                if (state.layoutInfo.totalItemsCount == 0 || isThumbDragged) return@LaunchedEffect
                val scrollOffset = computeScrollOffset(state = state)
                val scrollRange = computeScrollRange(state = state)
                val available = (scrollRange.toFloat() - heightPx)
                val proportion = if (available > 0f && available.isFinite()) {
                    (scrollOffset.toFloat() / available).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val newOffset = trackHeightPx * proportion + thumbTopPadding
                thumbOffsetY = if (newOffset.isFinite()) newOffset else thumbTopPadding
                scrolled.tryEmit(Unit)
            }

            // Thumb alpha
            val alpha = remember { Animatable(0f) }
            val isThumbVisible = alpha.value > 0f
            val fadeOutSpec = remember { fadeOutAnimationSpec() }
            val immediateFadeOutSpec = remember { immediateFadeOutAnimationSpec() }
            LaunchedEffect(scrolled, alpha) {
                scrolled
                    .sample(100)
                    .collectLatest {
                        if (thumbAllowed()) {
                            alpha.snapTo(1f)
                            alpha.animateTo(0f, animationSpec = fadeOutSpec)
                        } else {
                            alpha.animateTo(0f, animationSpec = immediateFadeOutSpec)
                        }
                    }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, thumbOffsetY.roundToInt()) }
                    .then(
                        // Recompose opts
                        if (isThumbVisible && !state.isScrollInProgress) {
                            Modifier.draggable(
                                interactionSource = dragInteractionSource,
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { delta ->
                                    val newOffsetY = thumbOffsetY + delta
                                    thumbOffsetY = newOffsetY.coerceIn(
                                        thumbTopPadding,
                                        thumbTopPadding + trackHeightPx,
                                    )
                                },
                            )
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Exclude the thumb region from the OS edge / predictive-back gesture only when
                        // it is the active drag target — matches native LazyVerticalScrollerWithScrollBar.
                        // Android = systemGestureExclusion(); iOS/desktop = no-op (see gestureExclusion).
                        if (isThumbVisible && !isThumbDragged && !state.isScrollInProgress) {
                            Modifier.gestureExclusion()
                        } else {
                            Modifier
                        },
                    )
                    .height(ThumbLength)
                    .padding(end = endContentPadding)
                    .width(ThumbThickness)
                    .alpha(alpha.value)
                    .background(color = thumbColor, shape = ThumbShape),
            )
        }.map { it.measure(scrollerConstraints) }
        val scrollerWidth = scrollerPlaceable.fastMaxBy { it.width }?.width ?: 0

        layout(contentWidth, contentHeight) {
            contentPlaceable.fastForEach {
                it.place(0, 0)
            }
            scrollerPlaceable.fastForEach {
                it.placeRelative(contentWidth - scrollerWidth, 0)
            }
        }
    }
}

private fun computeScrollOffset(state: LazyGridState): Int {
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    val startChild = visibleItems.firstOrNull() ?: return 0
    val endChild = visibleItems.lastOrNull() ?: return 0
    val minPosition = min(startChild.index, endChild.index)
    val maxPosition = max(startChild.index, endChild.index)
    val itemsBefore = minPosition.coerceAtLeast(0)
    val startDecoratedTop = startChild.offset.y
    val laidOutArea = abs((endChild.offset.y + endChild.size.height) - startDecoratedTop)
    val itemRange = abs(minPosition - maxPosition) + 1
    val avgSizePerRow = laidOutArea.toFloat() / itemRange
    return (itemsBefore * avgSizePerRow + (0 - startDecoratedTop)).roundToInt()
}

private fun computeScrollRange(state: LazyGridState): Int {
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    val startChild = visibleItems.firstOrNull() ?: return 0
    val endChild = visibleItems.lastOrNull() ?: return 0
    val laidOutArea = (endChild.offset.y + endChild.size.height) - startChild.offset.y
    val laidOutRange = abs(startChild.index - endChild.index) + 1
    return (laidOutArea.toFloat() / laidOutRange * state.layoutInfo.totalItemsCount).roundToInt()
}

/**
 * First visible item that is not a sticky header (keys prefixed with [STICKY_HEADER_KEY_PREFIX]),
 * or the first visible item if none qualify. Replaces the legacy `!!` non-null assertion with a
 * safe fallback. Returns `null` only when there are no visible items at all (callers already guard
 * on `totalItemsCount == 0`, so this stays defensive rather than load-bearing).
 */
private fun LazyListState.firstNonStickyVisibleItem(): LazyListItemInfo? {
    val visibleItems = layoutInfo.visibleItemsInfo
    return visibleItems
        .fastFirstOrNull { (it.key as? String)?.startsWith(STICKY_HEADER_KEY_PREFIX)?.not() ?: true }
        ?: visibleItems.firstOrNull()
}

private fun computeScrollOffset(state: LazyListState): Int {
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    val startChild = state.firstNonStickyVisibleItem() ?: return 0
    val endChild = visibleItems.lastOrNull() ?: return 0
    val minPosition = min(startChild.index, endChild.index)
    val maxPosition = max(startChild.index, endChild.index)
    val itemsBefore = minPosition.coerceAtLeast(0)
    val startDecoratedTop = startChild.top
    val laidOutArea = abs(endChild.bottom - startDecoratedTop)
    val itemRange = abs(minPosition - maxPosition) + 1
    val avgSizePerRow = laidOutArea.toFloat() / itemRange
    return (itemsBefore * avgSizePerRow + (0 - startDecoratedTop)).roundToInt()
}

private fun computeScrollRange(state: LazyListState): Int {
    if (state.layoutInfo.totalItemsCount == 0) return 0
    val visibleItems = state.layoutInfo.visibleItemsInfo
    val startChild = state.firstNonStickyVisibleItem() ?: return 0
    val endChild = visibleItems.lastOrNull() ?: return 0
    val laidOutArea = endChild.bottom - startChild.top
    val laidOutRange = abs(startChild.index - endChild.index) + 1
    return (laidOutArea.toFloat() / laidOutRange * state.layoutInfo.totalItemsCount).roundToInt()
}

private const val STICKY_HEADER_KEY_PREFIX = "sticky:"

private val ThumbLength = 48.dp
private val ThumbThickness = 12.dp
private val ThumbShape = RoundedCornerShape(ThumbThickness / 2)

/**
 * Scrollbar-thumb fade-out spec (the delayed fade after scroll activity stops). The duration is the
 * platform/OEM-configured scrollbar fade value via [scrollBarFadeDurationMs] — on Android that reads
 * `ViewConfiguration.getScrollBarFadeDuration()` exactly like the native
 * `LazyVerticalScrollerWithScrollBar`; iOS/desktop fall back to the historical 250ms Android default.
 */
private fun fadeOutAnimationSpec() = tween<Float>(
    durationMillis = scrollBarFadeDurationMs(),
    delayMillis = 2000,
)

/** Immediate (no-delay) variant of [fadeOutAnimationSpec], used when the thumb is not allowed. */
private fun immediateFadeOutAnimationSpec() = tween<Float>(
    durationMillis = scrollBarFadeDurationMs(),
)

private val LazyListItemInfo.top: Int
    get() = offset

private val LazyListItemInfo.bottom: Int
    get() = offset + size
