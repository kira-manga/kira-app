package me.manga.kira.ui.details

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import me.manga.kira.presentation.details.ChapterFilterType
import me.manga.kira.presentation.details.DetailsIntent
import me.manga.kira.presentation.details.DetailsState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.action_open_in_browser
import me.manga.kira.ui.generated.resources.action_remove
import me.manga.kira.ui.generated.resources.details_copy_title
import me.manga.kira.ui.generated.resources.details_custom_download
import me.manga.kira.ui.generated.resources.details_menu_share
import me.manga.kira.ui.generated.resources.details_more_options
import me.manga.kira.ui.generated.resources.details_resume_cd
import me.manga.kira.ui.generated.resources.details_resume_finished
import me.manga.kira.ui.generated.resources.np_details_download_all
import me.manga.kira.ui.generated.resources.np_details_title_copied
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Affordance regressions through the real Details screen, not reconstructed test buttons. */
@OptIn(ExperimentalTestApi::class)
class DetailsScreenActionsTest {
    @Test
    fun overflowShareDispatchesTheIntentAndDismisses() =
        runComposeUiTest {
            val fixture = DetailsActionsFixture(this)
            fixture.render()
            awaitIdle()
            fixture.action(Res.string.details_more_options).performClick()
            fixture.menu(Res.string.details_menu_share).assertIsDisplayed().performClick()
            awaitIdle()
            fixture.menu(Res.string.details_menu_share).assertDoesNotExist()
            fixture.expectOnly(DetailsIntent.OnShare)
            runOnIdle {
                fixture.state.value =
                    fixture.loaded.copy(details = requireNotNull(fixture.loaded.details).copy(url = ""))
            }
            awaitIdle()
            fixture.action(Res.string.details_more_options).performClick()
            fixture.menu(Res.string.details_menu_share).assertIsNotEnabled().performTouchInput { click() }
            fixture.expectOnly()
        }

    @Test
    fun resumeAndCustomDownloadTrackFinishedEmptyAndSelectionTransitions() =
        runComposeUiTest {
            val fixture = DetailsActionsFixture(this)
            fixture.render()
            awaitIdle()
            val unread = requireNotNull(fixture.loaded.firstUnreadChapter)
            fixture.resume(inHeader = false).assertIsDisplayed().performClick()
            fixture.expectOnly(DetailsIntent.OnChapterClick(unread))
            val details = requireNotNull(fixture.loaded.details)
            val finished =
                fixture.loaded.copy(details = details.copy(chapters = details.chapters.map { it.copy(isRead = true) }))
            runOnIdle { fixture.state.value = finished }
            awaitIdle()
            assertFinishedResume(fixture)
            runOnIdle { fixture.state.value = fixture.loaded.copy(details = details.copy(chapters = emptyList())) }
            awaitIdle()
            assertFinishedResume(fixture)
            assertEmptyThenRestoredCustomDownload(fixture, finished)
            runOnIdle { fixture.state.value = fixture.loaded.copy(selectedChapterUrls = setOf(unread.url)) }
            awaitIdle()
            fixture.resume(inHeader = false).assertDoesNotExist()
            onAllNodesWithContentDescription(getString(Res.string.details_resume_cd)).assertCountEquals(1)
            runOnIdle { fixture.state.value = fixture.loaded }
            awaitIdle()
            onAllNodesWithContentDescription(getString(Res.string.details_resume_cd)).assertCountEquals(2)
            fixture.resume(inHeader = true).assertIsEnabled().performClick()
            fixture.expectOnly(DetailsIntent.OnChapterClick(unread))
        }

    @Test
    fun titleLongCopyIsAccessibleAndCurrentWhileDateHasNoClick() =
        runComposeUiTest {
            val fixture = DetailsActionsFixture(this)
            fixture.render()
            awaitIdle()
            assertInformationalSemantics(fixture)
            val firstTitle = requireNotNull(fixture.loaded.details).title
            fixture.title().performTouchInput { longClick() }
            assertCopyConfirmation(fixture, listOf(firstTitle))
            val updated = requireNotNull(fixture.loaded.details).copy(title = "Updated displayed title")
            runOnIdle { fixture.state.value = fixture.loaded.copy(details = updated) }
            awaitIdle()
            fixture.title().performTouchInput { longClick() }
            assertCopyConfirmation(fixture, listOf(firstTitle, updated.title))
            val title = fixture.title().assertHasNoClickAction()
            val copyAction = title.fetchSemanticsNode().config[SemanticsActions.OnLongClick]
            assertEquals(getString(Res.string.details_copy_title), copyAction.label)
            title.performSemanticsAction(SemanticsActions.OnLongClick) { assertTrue(it()) }
            assertCopyConfirmation(fixture, listOf(firstTitle, updated.title, updated.title))
            fixture.action(Res.string.action_remove).assertHasClickAction().performClick()
            fixture.action(Res.string.action_open_in_browser).assertHasClickAction().performClick()
            fixture.expectOnly(DetailsIntent.OnToggleInLibrary, DetailsIntent.OnOpenInWebView)
        }

    private suspend fun ComposeUiTest.assertFinishedResume(fixture: DetailsActionsFixture) {
        fixture.resume(inHeader = false).assertDoesNotExist()
        fixture.resume(inHeader = true).assertIsNotEnabled().performTouchInput { click() }
        onNodeWithText(getString(Res.string.details_resume_finished)).assertIsDisplayed()
        onAllNodesWithContentDescription(getString(Res.string.details_resume_cd)).assertCountEquals(1)
        fixture.expectOnly()
    }

    private suspend fun ComposeUiTest.assertEmptyThenRestoredCustomDownload(
        fixture: DetailsActionsFixture,
        finished: DetailsState,
    ) {
        runOnIdle { fixture.state.value = finished.copy(chapterFilter = ChapterFilterType.UNREAD) }
        awaitIdle()
        fixture.action(Res.string.np_details_download_all).performClick()
        fixture.menu(Res.string.details_custom_download).assertIsNotEnabled().performTouchInput { click() }
        fixture.expectOnly()
        // Keep the menu open: the new availability and exact callback must both rebind.
        runOnIdle { fixture.state.value = finished }
        awaitIdle()
        val firstDisplayed = finished.displayChapters.first()
        assertTrue(firstDisplayed.isRead && firstDisplayed.isDownloaded)
        fixture.menu(Res.string.details_custom_download).assertIsEnabled().performClick()
        fixture.expectOnly(DetailsIntent.OnChapterLongClick(firstDisplayed))
    }

    private fun ComposeUiTest.assertInformationalSemantics(fixture: DetailsActionsFixture) {
        val title = fixture.title()
        val date = onNodeWithText(detailsActionsDate.toString())
        listOf(title, date).forEach { node ->
            node
                .assertIsDisplayed()
                .assertHasNoClickAction()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
                .assert(hasAnyAncestor(hasClickAction()).not())
                .performTouchInput { click() }
        }
        assertTrue(fixture.clipboard.writes.isEmpty(), "Ordinary taps must not copy the title")
        fixture.expectOnly()
    }

    private suspend fun ComposeUiTest.assertCopyConfirmation(
        fixture: DetailsActionsFixture,
        titles: List<String>,
    ) {
        awaitIdle()
        assertEquals(titles, fixture.clipboard.writes)
        val confirmation = getString(Res.string.np_details_title_copied)
        onNodeWithText(confirmation).assertIsDisplayed()
        onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { assertTrue(it()) }
        awaitIdle()
        onNodeWithText(confirmation).assertDoesNotExist()
    }
}
