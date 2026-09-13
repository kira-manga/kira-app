package me.manga.kira.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.domain.model.filters.FilterCondition
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.filters_pfix_reset
import me.manga.kira.ui.generated.resources.search_pfix_apply_filters
import me.manga.kira.ui.generated.resources.search_pfix_filters_not_ready
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/** Desktop semantics and controlled props/lifetime; not device keyboard or swipe-dismiss evidence. */
@OptIn(ExperimentalTestApi::class)
class SearchFilterSheetDraftTest {
    @Test
    fun actualApplyCommitsTextAndNumberTogetherWithoutImeThenDismisses() =
        runComposeUiTest {
            val fixture = Fixture()
            show(fixture)
            edit(0, " edited ")
            edit(1, "002026")
            assertEquals(emptyList(), fixture.batches)
            assertEquals(emptyList(), fixture.changes)
            assertEquals(emptyList(), fixture.events)
            apply(fixture)
            assertEquals(listOf(mapOf("author" to " edited ", "year" to "002026")), fixture.batches)
            assertEquals(listOf("apply", "dismiss"), fixture.events)
            onAllNodes(hasSetTextAction()).assertCountEquals(0)
        }

    @Test
    fun blankClearAndUnchangedApplyHaveDistinctDeltas() =
        runComposeUiTest {
            val fixture = Fixture(selections = mapOf("author" to listOf("seed")))
            show(fixture)
            edit(0, "   ")
            apply(fixture)
            assertEquals(listOf(mapOf("author" to "   ")), fixture.batches)
            runOnIdle {
                fixture.selections = mapOf("author" to emptyList())
                fixture.mounted = true
            }
            awaitIdle()
            apply(fixture)
            assertEquals(1, fixture.batches.size)
            assertEquals(listOf("apply", "dismiss", "dismiss"), fixture.events)
        }

    @Test
    fun doneConsumesOnlyCurrentFieldAndApplyDoesNotRepeatItBeforeEcho() =
        runComposeUiTest {
            val fixture = Fixture()
            show(fixture)
            edit(0, "sent")
            edit(1, "7")
            done(0)
            awaitIdle()
            assertEquals(listOf(mapOf("author" to "sent")), fixture.batches)
            assertEquals(listOf("apply"), fixture.events)
            input(0).assertTextEquals("sent")
            done(0)
            apply(fixture)
            assertEquals(listOf(mapOf("author" to "sent"), mapOf("year" to "7")), fixture.batches)
            assertEquals(listOf("apply", "apply", "dismiss"), fixture.events)
        }

    @Test
    fun newEditAndOtherFieldSurviveDelayedDoneEchoAndImmediateChipChange() =
        runComposeUiTest {
            val chip =
                SourceFilter("extra", "Extra", FilterControlType.MULTISELECT, listOf(FilterOption("x", "Choice")))
            val fixture = Fixture(filters = listOf(textFilter, numberFilter, chip))
            show(fixture)
            edit(0, "sent")
            edit(1, "7")
            done(0)
            edit(0, "newer")
            onNodeWithText("Choice").performScrollTo().performClick()
            runOnIdle { fixture.selections = fixture.selections + ("author" to listOf("sent")) }
            awaitIdle()
            input(0).assertTextEquals("newer")
            input(1).assertTextEquals("7")
            apply(fixture)
            assertEquals(listOf("extra" to listOf("x")), fixture.changes)
            assertEquals(
                listOf(mapOf("author" to "sent"), mapOf("author" to "newer", "year" to "7")),
                fixture.batches,
            )
        }

    @Test
    fun resetShowsDefaultsImmediatelyEvenWhenUnchangedAndRejectsAnOlderDoneEcho() =
        runComposeUiTest {
            val filters =
                listOf(textFilter.copy(defaultValues = listOf("seed")), numberFilter.copy(defaultValues = listOf("4")))
            val fixture = Fixture(filters, mapOf("author" to listOf("seed"), "year" to listOf("4")))
            show(fixture)
            edit(0, "sent")
            done(0)
            edit(0, "discard")
            edit(1, "99")
            onNodeWithText(fixture.resetLabel).performScrollTo().performClick()
            awaitIdle()
            input(0).assertTextEquals("seed")
            input(1).assertTextEquals("4")
            runOnIdle { fixture.selections = fixture.selections + ("author" to listOf("sent")) }
            awaitIdle()
            input(0).assertTextEquals("seed")
            apply(fixture)
            assertEquals(listOf(mapOf("author" to "sent")), fixture.batches)
            assertEquals(listOf("apply", "reset", "dismiss"), fixture.events)
        }

    @Test
    fun hiddenDeclaredDraftsSurviveAndTypingDoesNotPreviewVisibility() =
        runComposeUiTest {
            val dependent = numberFilter.copy(visibleWhen = listOf(FilterCondition("author", listOf("show"))))
            val fixture = Fixture(listOf(textFilter, dependent), mapOf("author" to listOf("show")))
            show(fixture)
            edit(1, "42")
            edit(0, "hide")
            onAllNodes(hasSetTextAction()).assertCountEquals(2)
            done(0)
            runOnIdle { fixture.selections = mapOf("author" to listOf("hide")) }
            awaitIdle()
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
            apply(fixture)
            assertEquals(listOf(mapOf("author" to "hide"), mapOf("year" to "42")), fixture.batches)
        }

    @Test
    fun removalTypeChangeAndWithdrawalDiscardDraftsBeforeDescriptorsReturn() =
        runComposeUiTest {
            val fixture = Fixture()
            show(fixture)
            edit(0, "retired")
            edit(1, "99")
            runOnIdle { fixture.filters = listOf(textFilter.copy(type = FilterControlType.NUMBER)) }
            awaitIdle()
            input(0).assertTextEquals("")
            runOnIdle { fixture.filters = listOf(textFilter, numberFilter) }
            awaitIdle()
            input(0).assertTextEquals("")
            input(1).assertTextEquals("")
            edit(0, "withdrawn")
            runOnIdle { fixture.filters = emptyList() }
            awaitIdle()
            onNodeWithText(fixture.notReadyLabel).assertIsDisplayed()
            onNodeWithText(fixture.applyLabel).assertDoesNotExist()
            runOnIdle { fixture.filters = listOf(textFilter, numberFilter) }
            awaitIdle()
            input(0).assertTextEquals("")
            apply(fixture)
            assertEquals(emptyList(), fixture.batches)
        }

    @Test
    fun controlledUnmountAndReopenSeedsCommittedValuesNotCancelledDrafts() =
        runComposeUiTest {
            val fixture = Fixture(selections = mapOf("author" to listOf("committed")))
            show(fixture)
            edit(0, "cancelled")
            edit(1, "88")
            runOnIdle { fixture.mounted = false }
            awaitIdle()
            assertEquals(emptyList(), fixture.batches)
            runOnIdle { fixture.mounted = true }
            awaitIdle()
            input(0).assertTextEquals("committed")
            input(1).assertTextEquals("")
            apply(fixture)
            assertEquals(emptyList(), fixture.batches)
            assertEquals(listOf("dismiss"), fixture.events)
        }

    private fun ComposeUiTest.show(fixture: Fixture) {
        setContent { KiraTheme(darkTheme = false) { fixture.Content() } }
    }

    private suspend fun ComposeUiTest.apply(fixture: Fixture) {
        onNodeWithText(fixture.applyLabel).performScrollTo().performClick()
        awaitIdle()
    }

    /** Only immediate controls echo automatically; each test controls delayed draft/reset props. */
    private class Fixture(
        filters: List<SourceFilter> = listOf(textFilter, numberFilter),
        selections: Map<String, List<String>> = emptyMap(),
    ) {
        var filters by mutableStateOf(filters)
        var selections by mutableStateOf(selections)
        var mounted by mutableStateOf(true)
        val batches = mutableListOf<Map<String, String>>()
        val changes = mutableListOf<Pair<String, List<String>>>()
        val events = mutableListOf<String>()
        var applyLabel = ""
        var resetLabel = ""
        var notReadyLabel = ""

        @Suppress("FunctionNaming", "ktlint:standard:function-naming")
        @Composable
        fun Content() {
            applyLabel = stringResource(Res.string.search_pfix_apply_filters)
            resetLabel = stringResource(Res.string.filters_pfix_reset)
            notReadyLabel = stringResource(Res.string.search_pfix_filters_not_ready)
            if (!mounted) return
            SearchFilterSheet(
                filters = filters,
                selections = selections,
                onFilterChange = { id, values ->
                    changes += id to values
                    selections = selections + (id to values)
                },
                onApplyDrafts = {
                    batches += it
                    events += "apply"
                },
                onResetFilters = { events += "reset" },
                onDismiss = {
                    events += "dismiss"
                    mounted = false
                },
            )
        }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.input(index: Int) = onAllNodes(hasSetTextAction())[index]

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.edit(
    index: Int,
    value: String,
) {
    input(index).performScrollTo().performClick().performTextReplacement(value)
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.done(index: Int) {
    input(index).performScrollTo().performClick().performImeAction()
}

private val textFilter = SourceFilter("author", "Author", FilterControlType.TEXT)
private val numberFilter = SourceFilter("year", "Year", FilterControlType.NUMBER)
