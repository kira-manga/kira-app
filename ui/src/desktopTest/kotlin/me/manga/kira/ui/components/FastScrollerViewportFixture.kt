package me.manga.kira.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal const val SCROLLER_HOST_TAG = "fast-scroller-host"
internal const val SCROLLER_CONTENT_TAG = "fast-scroller-content"
internal const val SCROLLER_ITEM_COUNT = 1000
internal const val SCROLLER_WIDTH_PX = 240
internal const val SCROLLER_THUMB_HEIGHT_PX = 48
internal const val SCROLLER_INITIAL_HEIGHT_PX = 400
private const val GRID_AFTER_PADDING_PX = 96
private const val GRID_COLUMN_COUNT = 3

internal fun scrollerItemTag(index: Int): String = "fast-scroller-item-$index"

/** Local fixed-size content only: both scenarios use the production wrapper and real lazy state. */
internal class FastScrollerViewportFixture(
    val grid: Boolean,
) {
    var heightPx by mutableIntStateOf(SCROLLER_INITIAL_HEIGHT_PX)
    var topPaddingPx by mutableIntStateOf(0)
    val expectedAfterPaddingPx: Int = if (grid) GRID_AFTER_PADDING_PX else 0
    private val listState = LazyListState()
    private val gridState = LazyGridState()
    private var scope: CoroutineScope? = null

    val firstIndex: Int
        get() = if (grid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
    val firstOffset: Int
        get() = if (grid) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
    val isScrolling: Boolean
        get() = if (grid) gridState.isScrollInProgress else listState.isScrollInProgress
    val canScrollForward: Boolean
        get() = if (grid) gridState.canScrollForward else listState.canScrollForward
    val totalCount: Int
        get() = if (grid) gridState.layoutInfo.totalItemsCount else listState.layoutInfo.totalItemsCount
    val visibleCount: Int
        get() = if (grid) gridState.layoutInfo.visibleItemsInfo.size else listState.layoutInfo.visibleItemsInfo.size
    val afterPaddingPx: Int
        get() = if (grid) gridState.layoutInfo.afterContentPadding else listState.layoutInfo.afterContentPadding

    @Composable
    fun content() {
        val compositionScope = rememberCoroutineScope()
        SideEffect { scope = compositionScope }
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            MaterialTheme {
                if (grid) gridContent() else listContent()
            }
        }
    }

    fun scrollTo(
        index: Int,
        offset: Int,
    ) {
        checkNotNull(scope).launch {
            if (grid) gridState.scrollToItem(index, offset) else listState.scrollToItem(index, offset)
        }
    }

    @Composable
    private fun listContent() {
        VerticalFastScroller(
            listState = listState,
            modifier = hostModifier(),
            thumbColor = Color.Magenta,
            topContentPadding = topPaddingPx.dp,
        ) {
            LazyColumn(state = listState, modifier = contentModifier()) {
                items(SCROLLER_ITEM_COUNT) { item(it) }
            }
        }
    }

    @Composable
    private fun gridContent() {
        val columns = GridCells.Fixed(GRID_COLUMN_COUNT)
        val arrangement = Arrangement.spacedBy(8.dp)
        val padding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = expectedAfterPaddingPx.dp)
        VerticalGridFastScroller(
            state = gridState,
            columns = columns,
            arrangement = arrangement,
            contentPadding = padding,
            modifier = hostModifier(),
            thumbColor = Color.Magenta,
            topContentPadding = topPaddingPx.dp,
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = columns,
                contentPadding = padding,
                horizontalArrangement = arrangement,
                modifier = contentModifier(),
            ) {
                items(SCROLLER_ITEM_COUNT) { item(it) }
            }
        }
    }

    @Composable
    private fun item(index: Int) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color.LightGray)
                    .testTag(scrollerItemTag(index)),
        )
    }

    private fun hostModifier(): Modifier =
        Modifier
            .size(SCROLLER_WIDTH_PX.dp, heightPx.dp)
            .background(Color.White)
            .testTag(SCROLLER_HOST_TAG)

    private fun contentModifier(): Modifier = Modifier.fillMaxSize().testTag(SCROLLER_CONTENT_TAG)
}
