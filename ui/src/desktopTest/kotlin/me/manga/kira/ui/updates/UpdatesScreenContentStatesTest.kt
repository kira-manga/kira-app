package me.manga.kira.ui.updates

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.updates.UpdatesIntent
import me.manga.kira.presentation.updates.UpdatesState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.no_updates
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Canned-state rendering checks for the stateless [UpdatesScreenContent] (backlog T2) — the
 * pattern the MVI contract promises ("previews/tests can drive them with canned flows"):
 * a plain [UpdatesState] + `emptyFlow()` effects + a recording `onIntent`, no ViewModel.
 *
 * Expected strings are resolved through the SAME `stringResource` calls inside the composition
 * (not hardcoded English) so the assertions hold on any machine locale.
 */
@OptIn(ExperimentalTestApi::class)
class UpdatesScreenContentStatesTest {

    @Test
    fun emptyState_rendersNoUpdatesPlaceholder() = runComposeUiTest {
        var noUpdates = ""
        setContent {
            KiraTheme(darkTheme = false) {
                noUpdates = stringResource(Res.string.no_updates)
                UpdatesScreenContent(
                    state = UpdatesState(isLoading = false),
                    effects = emptyFlow(),
                    onIntent = {},
                    onNavigateToDetails = {},
                    onNavigateToReader = {},
                )
            }
        }
        onNodeWithText(noUpdates).assertIsDisplayed()
    }

    @Test
    fun errorState_retryTap_emitsOnRetry() = runComposeUiTest {
        val intents = mutableListOf<UpdatesIntent>()
        var retryLabel = ""
        setContent {
            KiraTheme(darkTheme = false) {
                retryLabel = stringResource(Res.string.retry)
                UpdatesScreenContent(
                    state = UpdatesState(isLoading = false, loadError = AppError.Unexpected("boom")),
                    effects = emptyFlow(),
                    onIntent = { intents += it },
                    onNavigateToDetails = {},
                    onNavigateToReader = {},
                )
            }
        }
        onNodeWithText(retryLabel).performClick()
        assertEquals(listOf<UpdatesIntent>(UpdatesIntent.OnRetry), intents, "Retry must emit exactly OnRetry")
    }
}
