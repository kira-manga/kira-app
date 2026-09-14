package me.manga.kira.ui.sourceaccess

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.presentation.sourceaccess.StartReadingState
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
    fun guideAndWebsiteButtonsOpenTheirCanonicalDestinations() =
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

            assertEquals(listOf("https://kiramanga.me/guide", "https://kiramanga.me"), openedUrls)
            assertEquals(listOf(KIRA_GUIDE_URL), documentedGuideUrls())
        }

    private fun documentedGuideUrls(): List<String> {
        val classes = File(StartReadingScreenTest::class.java.protectionDomain.codeSource.location.toURI())
        val root = requireNotNull(
            generateSequence(classes) { it.parentFile }.firstOrNull {
                it.resolve("settings.gradle.kts").isFile && it.resolve("ui/build.gradle.kts").isFile
            },
        ) { "Cannot locate the compiled app checkout for the guide URL contract" }
        val declaration = Regex("^- guide: `([^`]+)`$")
        return root.resolve("docs/release/RELEASE_CONFIGURATION.md").readLines().mapNotNull {
            declaration.matchEntire(it)?.groupValues?.get(1)
        }
    }
}
