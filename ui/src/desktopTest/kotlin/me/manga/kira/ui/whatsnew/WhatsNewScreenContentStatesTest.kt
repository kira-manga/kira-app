package me.manga.kira.ui.whatsnew

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import me.manga.kira.core.error.AppError
import me.manga.kira.presentation.whatsnew.WhatsNewIntent
import me.manga.kira.presentation.whatsnew.WhatsNewState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.error_network
import me.manga.kira.ui.generated.resources.error_network_forbidden
import me.manga.kira.ui.generated.resources.error_network_no_connectivity
import me.manga.kira.ui.generated.resources.error_network_timeout
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.np_close
import me.manga.kira.ui.generated.resources.np_p3_whats_new_empty_title
import me.manga.kira.ui.generated.resources.np_whats_new_close
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Canned-state checks with localized labels resolved in the screen's own composition. */
@OptIn(ExperimentalTestApi::class)
class WhatsNewScreenContentStatesTest {
    @Test
    fun unexpectedFailureRendersLocalizedErrorAndRetryEmitsOnlyOnRetry() =
        runComposeUiTest {
            val intents = mutableListOf<WhatsNewIntent>()
            var closeCalls = 0
            var errorCopy = ""
            var retryLabel = ""
            var emptyTitle = ""
            showScreen(
                state =
                    WhatsNewState(
                        isLoading = false,
                        error = AppError.Unexpected(MESSAGE_SENTINEL, IllegalStateException(CAUSE_SENTINEL)),
                    ),
                onIntent = { intents += it },
                onClose = { closeCalls++ },
            ) {
                errorCopy = stringResource(Res.string.error_occurred)
                retryLabel = stringResource(Res.string.retry)
                emptyTitle = stringResource(Res.string.np_p3_whats_new_empty_title)
            }
            onNodeWithText(errorCopy).assertIsDisplayed()
            onNodeWithText(retryLabel).assertIsDisplayed()
            onNodeWithText(emptyTitle).assertDoesNotExist()
            onNodeWithText(MESSAGE_SENTINEL, substring = true).assertDoesNotExist()
            onNodeWithText(CAUSE_SENTINEL, substring = true).assertDoesNotExist()
            onNodeWithText(retryLabel).performClick()
            assertEquals(listOf<WhatsNewIntent>(WhatsNewIntent.OnRetry), intents)
            assertEquals(0, closeCalls)
        }

    @Test
    fun forbiddenFailureUsesNetworkCopyWithoutUnsupportedHelpInstruction() =
        runComposeUiTest {
            var networkCopy = ""
            var unsupportedHelpCopy = ""
            var retryLabel = ""
            showScreen(WhatsNewState(isLoading = false, error = AppError.Network.Http(statusCode = 403))) {
                networkCopy = stringResource(Res.string.error_network)
                unsupportedHelpCopy = stringResource(Res.string.error_network_forbidden)
                retryLabel = stringResource(Res.string.retry)
            }
            onNodeWithText(networkCopy).assertIsDisplayed()
            onNodeWithText(unsupportedHelpCopy).assertDoesNotExist()
            onNodeWithText(retryLabel).assertIsDisplayed()
        }

    @Test
    fun unloadedCancelledEmptyShowsRetryAndCloseInsteadOfSuccessfulEmpty() =
        runComposeUiTest {
            val intents = mutableListOf<WhatsNewIntent>()
            var closeCalls = 0
            var errorCopy = ""
            var retryLabel = ""
            var closeLabel = ""
            var emptyTitle = ""
            showScreen(
                state = WhatsNewState(isLoading = false, hasLoadedSuccessfully = false),
                onIntent = { intents += it },
                onClose = { closeCalls++ },
            ) {
                errorCopy = stringResource(Res.string.error_occurred)
                retryLabel = stringResource(Res.string.retry)
                closeLabel = stringResource(Res.string.np_close)
                emptyTitle = stringResource(Res.string.np_p3_whats_new_empty_title)
            }
            onNodeWithText(errorCopy).assertIsDisplayed()
            onNodeWithText(closeLabel).assertIsDisplayed()
            onNodeWithText(emptyTitle).assertDoesNotExist()
            onNodeWithText(retryLabel).assertIsDisplayed().performClick()
            assertEquals(listOf<WhatsNewIntent>(WhatsNewIntent.OnRetry), intents)
            assertEquals(0, closeCalls)
        }

    @Test
    fun successfulEmptyHasNoRetryAndCardCloseDoesNotMarkSeen() =
        runComposeUiTest {
            val intents = mutableListOf<WhatsNewIntent>()
            var closeCalls = 0
            var emptyTitle = ""
            var retryLabel = ""
            var closeLabel = ""
            showScreen(
                state = WhatsNewState(isLoading = false, hasLoadedSuccessfully = true),
                onIntent = { intents += it },
                onClose = { closeCalls++ },
            ) {
                emptyTitle = stringResource(Res.string.np_p3_whats_new_empty_title)
                retryLabel = stringResource(Res.string.retry)
                closeLabel = stringResource(Res.string.np_close)
            }
            onNodeWithText(emptyTitle).assertIsDisplayed()
            onNodeWithText(retryLabel).assertDoesNotExist()
            onNodeWithText(closeLabel).performClick()
            assertEquals(1, closeCalls)
            assertTrue(intents.isEmpty(), "The empty card Close is not an explicit seen mark")
        }

    @Test
    fun errorCardCloseOnlyClosesWithoutMarkingSeenOrRetrying() =
        runComposeUiTest {
            val intents = mutableListOf<WhatsNewIntent>()
            var closeCalls = 0
            var closeLabel = ""
            var timeoutCopy = ""
            showScreen(
                state = WhatsNewState(isLoading = false, error = AppError.Network.Timeout()),
                onIntent = { intents += it },
                onClose = { closeCalls++ },
            ) {
                closeLabel = stringResource(Res.string.np_close)
                timeoutCopy = stringResource(Res.string.error_network_timeout)
            }
            onNodeWithText(timeoutCopy).assertIsDisplayed()
            onNodeWithText(closeLabel).performClick()
            assertEquals(1, closeCalls)
            assertTrue(intents.isEmpty(), "The error card Close must neither mark seen nor retry")
        }

    @Test
    fun headerCloseExplicitlyMarksSeenAndClosesAfterFailure() =
        runComposeUiTest {
            val intents = mutableListOf<WhatsNewIntent>()
            var closeCalls = 0
            var closeDescription = ""
            var connectivityCopy = ""
            showScreen(
                state = WhatsNewState(isLoading = false, error = AppError.Network.NoConnectivity()),
                onIntent = { intents += it },
                onClose = { closeCalls++ },
            ) {
                closeDescription = stringResource(Res.string.np_whats_new_close)
                connectivityCopy = stringResource(Res.string.error_network_no_connectivity)
            }
            onNodeWithText(connectivityCopy).assertIsDisplayed()
            onNodeWithContentDescription(closeDescription).performClick()
            assertEquals(listOf<WhatsNewIntent>(WhatsNewIntent.OnMarkSeen), intents)
            assertEquals(1, closeCalls)
        }

    private fun ComposeUiTest.showScreen(
        state: WhatsNewState,
        onIntent: (WhatsNewIntent) -> Unit = {},
        onClose: () -> Unit = {},
        readLabels: @Composable () -> Unit,
    ) {
        setContent {
            KiraTheme(darkTheme = false) {
                readLabels()
                WhatsNewScreenContent(state = state, onIntent = onIntent, onGetStarted = onClose)
            }
        }
    }

    private companion object {
        const val MESSAGE_SENTINEL = "RAW_WHATS_NEW_FAILURE https://example.invalid/private-response"
        const val CAUSE_SENTINEL = "RAW_WHATS_NEW_BODY"
    }
}
