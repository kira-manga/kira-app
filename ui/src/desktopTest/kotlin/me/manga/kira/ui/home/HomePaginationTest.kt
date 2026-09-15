package me.manga.kira.ui.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HomePaginationTest {
    @Test
    fun gridRearmsForShortPagesAndStopsAtTerminalPage() = shortPages(grid = true)

    @Test
    fun listRearmsForShortPagesAndStopsAtTerminalPage() = shortPages(grid = false)

    @Test
    fun gridDoesNotRetryFailureButRearmsAfterRefreshAndTabSwitch() = resetPages(grid = true)

    @Test
    fun listDoesNotRetryFailureButRearmsAfterRefreshAndTabSwitch() = resetPages(grid = false)

    @Test
    fun gridDoesNotRetryWhenLoadingFooterFallsBelowViewport() = footerFailure(grid = true)

    @Test
    fun listDoesNotRetryWhenLoadingFooterFallsBelowViewport() = footerFailure(grid = false)

    private fun footerFailure(grid: Boolean) =
        runSkikoComposeUiTest(size = VIEWPORT, density = Density(1f)) {
            val fixture = HomePaginationFixture(grid, itemCount = LONG_FEED_COUNT)
            showHomePagination(fixture)
            assertRequests(fixture, 0)
            val feed = onNode(
                hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            )
            val lastIndex = if (grid) LONG_FEED_COUNT - 1 else LONG_FEED_COUNT // List has a Latest header.
            feed.performScrollToIndex(lastIndex)
            assertRequests(fixture, 1)
            val scroll = feed.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(scroll.value() < scroll.maxValue(), "The loading footer must extend below the viewport")
            runOnIdle { fixture.state.value = fixture.state.value.copy(isLoadingNextPage = false) }
            assertRequests(fixture, 1)
        }

    private fun shortPages(grid: Boolean) =
        runSkikoComposeUiTest(size = VIEWPORT, density = Density(1f)) {
            val fixture = HomePaginationFixture(grid)
            showHomePagination(fixture)
            assertRequests(fixture, 1)
            // Spinner/recompositions must not duplicate an in-flight request.
            assertRequests(fixture, 1)
            runOnIdle { fixture.appendPage() }
            assertRequests(fixture, 2)
            runOnIdle { fixture.appendPage() }
            assertRequests(fixture, THIRD_REQUEST)
            runOnIdle {
                // An empty/all-duplicate result advances the VM cursor but closes pagination.
                fixture.state.value =
                    fixture.state.value.copy(
                        page = fixture.state.value.page + 1,
                        isLoadingNextPage = false,
                        hasMorePages = false,
                    )
            }
            assertRequests(fixture, THIRD_REQUEST)
        }

    private fun resetPages(grid: Boolean) =
        runSkikoComposeUiTest(size = VIEWPORT, density = Density(1f)) {
            val fixture = HomePaginationFixture(grid)
            showHomePagination(fixture)
            assertRequests(fixture, 1)
            runOnIdle { fixture.state.value = fixture.state.value.copy(isLoadingNextPage = false) }
            assertRequests(fixture, 1)
            runOnIdle { fixture.state.value = fixture.state.value.copy(isRefreshing = true, isFeedLoading = true) }
            assertRequests(fixture, 1)
            runOnIdle { fixture.state.value = fixture.state.value.copy(isRefreshing = false, isFeedLoading = false) }
            assertRequests(fixture, 2)
            runOnIdle { fixture.switchSource() }
            assertRequests(fixture, THIRD_REQUEST)
            assertEquals(listOf("alpha", "alpha", "beta"), fixture.requestedSources)
        }

    private suspend fun ComposeUiTest.assertRequests(
        fixture: HomePaginationFixture,
        expected: Int,
    ) {
        awaitIdle()
        assertEquals(expected, fixture.requestedSources.size)
    }
}

private const val LONG_FEED_COUNT = 80
private const val THIRD_REQUEST = 3
private const val VIEWPORT_WIDTH = 1_400f
private const val VIEWPORT_HEIGHT = 1_100f
private val VIEWPORT = Size(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
