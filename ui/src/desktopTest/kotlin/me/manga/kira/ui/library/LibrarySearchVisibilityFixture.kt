@file:OptIn(ExperimentalTestApi::class)

package me.manga.kira.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.presentation.library.LibraryState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.contentDescription_search
import me.manga.kira.ui.generated.resources.contentDescription_search_clear
import me.manga.kira.ui.generated.resources.content_description_close_search
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource

/** Retained query owner only; this fixture does not duplicate the ViewModel's filtering. */
internal class LibrarySearchVisibilityFixture(
    initialQuery: String = "",
) {
    val state =
        mutableStateOf(
            LibraryState(isLoading = false, hasLibraryItems = true, searchQuery = initialQuery),
        )
    val mounted = mutableStateOf(true)
    val intents = mutableListOf<LibraryIntent>()
    var labels = LibrarySearchLabels()
    var deferQueryClear = false
    private var clearPending = false

    fun deliverQuery(query: String) {
        state.value = state.value.copy(searchQuery = query)
    }

    fun enterSelection() {
        state.value =
            state.value.copy(
                selection = setOf(MangaKey(api = "fixture", language = "en", title = "Selected manga")),
                isInSelectionMode = true,
            )
    }

    fun deliverPendingClear() {
        check(clearPending) { "No query-clear intent is pending" }
        clearPending = false
        deferQueryClear = false
        deliverQuery("")
    }

    fun onIntent(intent: LibraryIntent) {
        intents += intent
        when (intent) {
            is LibraryIntent.OnSearchQueryChange -> {
                if (deferQueryClear && intent.query.isEmpty()) {
                    clearPending = true
                } else {
                    deliverQuery(intent.query)
                }
            }
            LibraryIntent.OnSelectionClear -> {
                state.value = state.value.copy(selection = emptySet(), isInSelectionMode = false)
            }
            else -> Unit
        }
    }
}

internal fun ComposeUiTest.showLibrarySearch(fixture: LibrarySearchVisibilityFixture) {
    setContent {
        KiraTheme(darkTheme = false) {
            fixture.labels = librarySearchLabels()
            Box(Modifier.size(SCREEN_WIDTH_DP.dp, SCREEN_HEIGHT_DP.dp)) {
                if (fixture.mounted.value) {
                    LibraryScreenContent(
                        state = fixture.state.value,
                        effects = emptyFlow(),
                        onIntent = fixture::onIntent,
                        onNavigateToDetails = {},
                        onNavigateToDownloads = {},
                        onNavigateToBackupExport = {},
                        coverModel = { null },
                    )
                }
            }
        }
    }
}

internal suspend fun ComposeUiTest.recreateLibrarySearch(fixture: LibrarySearchVisibilityFixture) {
    runOnIdle { fixture.mounted.value = false }
    awaitIdle()
    onNode(hasSetTextAction()).assertDoesNotExist()
    runOnIdle { fixture.mounted.value = true }
    awaitIdle()
}

internal fun ComposeUiTest.assertLibrarySearchEditor(
    fixture: LibrarySearchVisibilityFixture,
    query: String,
) {
    onNode(hasSetTextAction())
        .assertIsDisplayed()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)))
    onNodeWithContentDescription(fixture.labels.closeSearch).assertIsDisplayed()
}

internal fun ComposeUiTest.assertLibrarySearchClosed(fixture: LibrarySearchVisibilityFixture) {
    onNode(hasSetTextAction()).assertDoesNotExist()
    onNodeWithContentDescription(fixture.labels.openSearch).assertIsDisplayed()
}

internal data class LibrarySearchLabels(
    val openSearch: String = "",
    val closeSearch: String = "",
    val clearQuery: String = "",
    val cancelSelection: String = "",
    val noResults: String = "",
)

@Composable
private fun librarySearchLabels() =
    LibrarySearchLabels(
        openSearch = stringResource(Res.string.contentDescription_search),
        closeSearch = stringResource(Res.string.content_description_close_search),
        clearQuery = stringResource(Res.string.contentDescription_search_clear),
        cancelSelection = stringResource(Res.string.cancel),
        noResults = stringResource(Res.string.no_results_found),
    )

private const val SCREEN_WIDTH_DP = 400
private const val SCREEN_HEIGHT_DP = 720
