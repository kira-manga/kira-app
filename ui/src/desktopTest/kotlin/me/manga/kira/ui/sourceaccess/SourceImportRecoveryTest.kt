package me.manga.kira.ui.sourceaccess

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.presentation.sources.SourcesState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.source_import_from_storage
import me.manga.kira.ui.sources.SourcesScreenContent
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SourceImportRecoveryTest {
    @Test
    fun noEnabledSourcesPrompt_opensImportFlow() =
        runComposeUiTest {
            var importLabel = ""
            var importRequests = 0
            setContent {
                KiraTheme(darkTheme = false) {
                    importLabel = stringResource(Res.string.source_import_from_storage)
                    ActivatedHomeSourcePrompt(
                        onEditSources = {},
                        onImportFromStorage = { importRequests += 1 },
                    )
                }
            }

            onNodeWithText(importLabel).assertIsDisplayed().performClick()

            assertEquals(1, importRequests)
        }

    @Test
    fun emptySourcesEditor_opensImportFlow() =
        runComposeUiTest {
            var importLabel = ""
            var importRequests = 0
            setContent {
                KiraTheme(darkTheme = false) {
                    importLabel = stringResource(Res.string.source_import_from_storage)
                    SourcesScreenContent(
                        state = SourcesState(isLoading = false),
                        effects = emptyFlow(),
                        onIntent = {},
                        onImportFromStorage = { importRequests += 1 },
                    )
                }
            }

            onNodeWithText(importLabel)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()

            assertEquals(1, importRequests)
        }
}
