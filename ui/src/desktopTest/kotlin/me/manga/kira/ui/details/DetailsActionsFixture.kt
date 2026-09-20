package me.manga.kira.ui.details

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.datetime.LocalDate
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.SavedWorkIdentity
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.details_resume_cd
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlin.test.assertEquals

/** Real Details content with mutable input and an in-memory clipboard, never an OS clipboard. */
@Suppress("DEPRECATION") // Exercise the same common clipboard boundary as DetailsHeader.
@OptIn(ExperimentalTestApi::class)
internal class DetailsActionsFixture(
    private val ui: ComposeUiTest,
) {
    val loaded = detailsActionsState()
    val state = mutableStateOf(loaded)
    val clipboard = RecordingDetailsClipboard()
    private val intents = mutableListOf<DetailsIntent>()

    fun render() {
        ui.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                KiraTheme(darkTheme = false) {
                    DetailsScreenContent(
                        state = state.value,
                        effects = emptyFlow(),
                        onIntent = { intents += it },
                        onNavigateBack = {},
                        onNavigateToReader = { _, _ -> },
                        onNavigateToDownloads = {},
                        onNavigateToBackupExport = {},
                        onOpenInWebView = { _, _ -> },
                        onShare = { _, _ -> },
                    )
                }
            }
        }
    }

    suspend fun action(resource: StringResource): SemanticsNodeInteraction {
        val description = getString(resource)
        return ui.onNodeWithContentDescription(description)
    }

    suspend fun menu(resource: StringResource): SemanticsNodeInteraction = ui.onNodeWithText(getString(resource))

    fun title(): SemanticsNodeInteraction =
        ui.onNode(
            hasText(requireNotNull(state.value.details).title) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
        )

    suspend fun resume(inHeader: Boolean): SemanticsNodeInteraction {
        val insideList = hasAnyAncestor(hasScrollToIndexAction())
        val location = if (inHeader) insideList else insideList.not()
        return ui.onNode(
            hasContentDescription(getString(Res.string.details_resume_cd)) and location,
        )
    }

    fun expectOnly(vararg expected: DetailsIntent) {
        ui.runOnIdle {
            assertEquals(expected.toList(), intents)
            intents.clear()
        }
    }
}

@Suppress("DEPRECATION")
internal class RecordingDetailsClipboard : ClipboardManager {
    val writes = mutableListOf<String>()
    private var contents: AnnotatedString? = null

    override fun setText(annotatedString: AnnotatedString) {
        contents = annotatedString
        writes += annotatedString.text
    }

    override fun getText(): AnnotatedString? = contents
}

internal val detailsActionsDate = LocalDate.parse("2020-02-03")

private fun detailsActionsState(): DetailsState {
    val manga = Manga("fixture", "en", "Details actions", "https://source.example/manga", "", null, emptyList())
    val chapters =
        listOf("2", "1").map { number ->
            Chapter(number, "", "c/$number", detailsActionsDate, isDownloaded = number == "2", isBookmarked = false)
        }
    return DetailsState(
        manga = manga,
        details =
            MangaDetails(
                api = manga.api,
                language = manga.language,
                title = manga.title,
                url = manga.url,
                coverUrl = "",
                description = "",
                author = "",
                rating = "",
                status = "",
                genres = emptyList(),
                chapters = chapters,
            ),
        isInLibrary = true,
        savedOwner = SavedWorkIdentity(1L, WorkLocator(manga.api, manga.url)),
    )
}
