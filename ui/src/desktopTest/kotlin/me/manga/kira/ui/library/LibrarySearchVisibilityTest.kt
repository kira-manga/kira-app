package me.manga.kira.ui.library

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.presentation.library.LibraryIntent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class LibrarySearchVisibilityTest {
    @Test
    fun retainedQueryRemainsVisibleAfterContentRecreation() =
        runComposeUiTest {
            val fixture = LibrarySearchVisibilityFixture(initialQuery = RETAINED_QUERY)
            showLibrarySearch(fixture)
            awaitIdle()
            onNodeWithText(fixture.labels.noResults).assertIsDisplayed()
            val queries = listOf(RETAINED_QUERY, WHITESPACE_QUERY)
            for (query in queries) {
                runOnIdle { fixture.deliverQuery(query) }
                awaitIdle()
                assertLibrarySearchEditor(fixture, query)
                recreateLibrarySearch(fixture)
                assertLibrarySearchEditor(fixture, query)
                if (query == RETAINED_QUERY) {
                    onNodeWithText(fixture.labels.noResults).assertIsDisplayed()
                }
                runOnIdle { assertEquals(query, fixture.state.value.searchQuery) }
            }
            runOnIdle {
                assertEquals(List(queries.size + 1) { LibraryIntent.OnEnter }, fixture.intents)
            }
        }

    @Test
    fun queryArrivalClearAndClosePreserveEditorUntilOwnerClear() =
        runComposeUiTest {
            val fixture = LibrarySearchVisibilityFixture()
            showLibrarySearch(fixture)
            awaitIdle()
            assertLibrarySearchClosed(fixture)
            receiveQueryAndReturnFromSelection(fixture)
            clearThenCloseWithDeferredOwner(fixture)
            recreateLibrarySearch(fixture)
            assertLibrarySearchClosed(fixture)
            onNodeWithContentDescription(fixture.labels.openSearch).performClick()
            awaitIdle()
            assertLibrarySearchEditor(fixture, "")
            runOnIdle { assertEquals("", fixture.state.value.searchQuery) }
        }

    private suspend fun ComposeUiTest.receiveQueryAndReturnFromSelection(fixture: LibrarySearchVisibilityFixture) {
        runOnIdle { fixture.deliverQuery(RETAINED_QUERY) }
        awaitIdle()
        assertLibrarySearchEditor(fixture, RETAINED_QUERY)
        runOnIdle { fixture.enterSelection() }
        awaitIdle()
        onNode(hasSetTextAction()).assertDoesNotExist()
        onNodeWithText(fixture.labels.cancelSelection).assertIsDisplayed().performClick()
        awaitIdle()
        assertLibrarySearchEditor(fixture, RETAINED_QUERY)
        runOnIdle {
            assertEquals(LibraryIntent.OnSelectionClear, fixture.intents.last())
            assertEquals(RETAINED_QUERY, fixture.state.value.searchQuery)
        }
    }

    private suspend fun ComposeUiTest.clearThenCloseWithDeferredOwner(fixture: LibrarySearchVisibilityFixture) {
        onNodeWithContentDescription(fixture.labels.clearQuery).performClick()
        awaitIdle()
        assertLibrarySearchEditor(fixture, "")
        onNode(hasSetTextAction()).performClick().performTextInput(TYPED_QUERY)
        awaitIdle()
        assertLibrarySearchEditor(fixture, TYPED_QUERY)
        runOnIdle { fixture.deferQueryClear = true }
        onNodeWithContentDescription(fixture.labels.closeSearch).performClick()
        awaitIdle()
        assertLibrarySearchEditor(fixture, TYPED_QUERY)
        runOnIdle {
            assertEquals(LibraryIntent.OnSearchQueryChange(""), fixture.intents.last())
            assertEquals(TYPED_QUERY, fixture.state.value.searchQuery)
            fixture.deliverPendingClear()
        }
        awaitIdle()
        assertLibrarySearchClosed(fixture)
    }

    private companion object {
        const val RETAINED_QUERY = "retained-query"
        const val WHITESPACE_QUERY = "  "
        const val TYPED_QUERY = "typed-query"
    }
}
