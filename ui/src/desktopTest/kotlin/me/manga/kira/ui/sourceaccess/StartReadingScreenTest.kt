package me.manga.kira.ui.sourceaccess

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.presentation.sourceaccess.StartReadingState
import me.manga.kira.ui.components.WEBSITE_URL
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.start_reading_guide
import me.manga.kira.ui.generated.resources.start_reading_website
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StartReadingScreenTest {
    @Test
    fun websiteButtonsOpenKiraWebsite() =
        runComposeUiTest {
            val openedUrls = mutableListOf<String>()
            var guideLabel = ""
            var websiteLabel = ""
            setContent {
                KiraTheme(darkTheme = false) {
                    guideLabel = stringResource(Res.string.start_reading_guide)
                    websiteLabel = stringResource(Res.string.start_reading_website)
                    StartReadingScreenContent(
                        state = StartReadingState(),
                        effects = emptyFlow(),
                        onIntent = {},
                        actions =
                            StartReadingActions(
                                onActivationSucceeded = {},
                                onImport = {},
                                onContinueToLibrary = {},
                                onOpenUrl = openedUrls::add,
                            ),
                    )
                }
            }

            onNodeWithText(guideLabel).performScrollTo().performClick()
            onNodeWithText(websiteLabel).performScrollTo().performClick()

            assertEquals(listOf(WEBSITE_URL, WEBSITE_URL), openedUrls)
        }
}
