package me.manga.kira.ui.search

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.search_pfix_filter_collapsed
import me.manga.kira.ui.generated.resources.search_pfix_filter_expanded
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

@OptIn(ExperimentalTestApi::class)
class SearchFilterSheetExpansionTest {
    private val multiSelect =
        SourceFilter(
            id = "custom-multi",
            label = "Multi options",
            type = FilterControlType.MULTISELECT,
            options = listOf(FilterOption("m1", "Multi 1"), FilterOption("m2", "Multi 2")),
        )
    private val singleSelect =
        SourceFilter(
            id = "custom-select",
            label = "Select options",
            type = FilterControlType.SELECT,
            options = (1..9).map { FilterOption("s$it", "Select $it") },
        )
    private val selections = mapOf(multiSelect.id to listOf("m1"), singleSelect.id to listOf("s1"))

    @Test
    fun chipHeadersExposeLocalizedExpansionAndPreserveSelectionCallbacks() =
        runComposeUiTest {
            val changes = mutableListOf<Pair<String, List<String>>>()
            var expanded = ""
            var collapsed = ""
            setContent {
                KiraTheme(darkTheme = false) {
                    expanded = stringResource(Res.string.search_pfix_filter_expanded)
                    collapsed = stringResource(Res.string.search_pfix_filter_collapsed)
                    SearchFilterSheet(
                        filters = listOf(multiSelect, singleSelect),
                        selections = selections,
                        onFilterChange = { id, values -> changes += id to values },
                        onApplyDrafts = { fail("Header toggles must not apply input drafts") },
                        onResetFilters = { fail("Header toggles must not reset filters") },
                        onDismiss = { fail("Header toggles must not dismiss the sheet") },
                    )
                }
            }
            awaitIdle()
            assertExpansionRoundTrip(multiSelect, singleSelect, expanded, collapsed)
            assertExpansionRoundTrip(singleSelect, multiSelect, expanded, collapsed)
            assertEquals(emptyList(), changes)
            onNodeWithText("Multi 2").performScrollTo().performClick()
            onNodeWithText("Select 2").performScrollTo().performClick()
            assertEquals(
                listOf(multiSelect.id to listOf("m1", "m2"), singleSelect.id to listOf("s2")),
                changes,
            )
        }

    private suspend fun ComposeUiTest.assertExpansionRoundTrip(
        filter: SourceFilter,
        other: SourceFilter,
        expanded: String,
        collapsed: String,
    ) {
        header(other.label, expanded)
        onNodeWithText(filter.options.first().label).performScrollTo().assertIsDisplayed()
        header(filter.label, expanded)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        header(filter.label, collapsed)
        header(other.label, expanded)
        filter.options.forEach { onNodeWithText(it.label).assertDoesNotExist() }
        header(filter.label, collapsed)
            .performScrollTo()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        header(filter.label, expanded)
        header(other.label, expanded)
        filter.options.forEach {
            onNodeWithText(it.label).performScrollTo().assertIsDisplayed()
        }
    }

    private fun ComposeUiTest.header(
        title: String,
        state: String,
    ): SemanticsNodeInteraction =
        onNodeWithText(title, useUnmergedTree = false)
            .assertTextEquals(title)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state))
}
