package me.manga.kira.ui.updates

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.presentation.updates.UpdatesEffect
import me.manga.kira.presentation.updates.UpdatesIntent
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.details_mark_read
import me.manga.kira.ui.generated.resources.details_mark_unread
import me.manga.kira.ui.generated.resources.details_more_options
import me.manga.kira.ui.generated.resources.download
import me.manga.kira.ui.generated.resources.downloaded
import me.manga.kira.ui.generated.resources.undo
import kotlin.test.Test
import kotlin.test.assertTrue

/** Real content and effects with canned state; no VM, persistence or native AT claim. */
@OptIn(ExperimentalTestApi::class)
class UpdatesRowAccessibilityTest {
    @Test
    fun customActionsAndOpenMenuRebindToTheCurrentEntry() =
        runUpdatesAccessibilityTest {
            val fixture = UpdatesRowAccessibilityFixture(this)
            fixture.render()
            fixture.assertRowActions(unreadUpdate)
            fixture.assertRowActions(readUpdate)
            fixture.invokeCustom(unreadUpdate, Res.string.details_mark_read)
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(unreadUpdate))
            fixture.invokeCustom(readUpdate, Res.string.details_mark_unread)
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(readUpdate))
            fixture.invokeCustom(unreadUpdate, Res.string.delete)
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(unreadUpdate))
            fixture.control(unreadUpdate, Res.string.details_more_options).performTouchInput { click() }
            fixture.menu(Res.string.details_mark_read).assertIsDisplayed()
            val rebound =
                unreadUpdate.copy(isRead = true, chapterNumber = "1.5", chapterUrl = "file:///fixture/rebound-chapter")
            runOnIdle { fixture.state = fixture.state.copy(items = listOf(rebound, readUpdate)) }
            waitForIdle()
            fixture.assertRowActions(rebound)
            fixture.menu(Res.string.details_mark_read).assertDoesNotExist()
            fixture.menu(Res.string.details_mark_unread).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(rebound))
            fixture.invokeCustom(rebound, Res.string.details_mark_unread)
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(rebound))
            fixture.invokeCustom(rebound, Res.string.delete)
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(rebound))
        }

    @Test
    fun keyboardTraversalActivationMenuSelectionAndEscapeAreOperable() =
        runUpdatesAccessibilityTest {
            val fixture = UpdatesRowAccessibilityFixture(this)
            val keyboard = fixture.keyboard
            fixture.render()
            val overflow = fixture.control(unreadUpdate, Res.string.details_more_options)
            keyboard.open(overflow, Key.Enter)
            assertTrue(keyboard.select(fixture.menu(Res.string.details_mark_read), Key.Spacebar))
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(unreadUpdate))
            onNode(isPopup()).assertDoesNotExist()
            keyboard.open(overflow, Key.Spacebar)
            assertTrue(keyboard.select(fixture.menu(Res.string.delete), Key.Enter))
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(unreadUpdate))
            onNode(isPopup()).assertDoesNotExist()
            keyboard.open(overflow, Key.Enter)
            keyboard.press(Key.Escape)
            onNode(isPopup()).assertDoesNotExist()
            fixture.expectOnly()
            keyboard.open(overflow, Key.Spacebar)
            assertTrue(keyboard.select(fixture.menu(Res.string.details_mark_read), Key.Enter))
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(unreadUpdate))
            onNode(isPopup()).assertDoesNotExist()
            keyboard.tabTo(fixture.control(readUpdate, Res.string.details_more_options))
            fixture.expectOnly()
        }

    @Test
    fun pointerMenusPreserveIndependentChapterCoverAndDownloadControls() =
        runUpdatesAccessibilityTest {
            val fixture = UpdatesRowAccessibilityFixture(this)
            fixture.render()
            fixture.control(unreadUpdate, Res.string.details_more_options).performTouchInput { click() }
            fixture.menu(Res.string.details_mark_read).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(unreadUpdate))
            onNode(isPopup()).assertDoesNotExist()
            fixture.control(readUpdate, Res.string.details_more_options).performTouchInput { click() }
            fixture.menu(Res.string.details_mark_unread).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(readUpdate))
            fixture.control(unreadUpdate, Res.string.details_more_options).performTouchInput { click() }
            fixture.menu(Res.string.delete).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(unreadUpdate))
            fixture.row(unreadUpdate).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnChapterClick(unreadUpdate))
            fixture.cover(readUpdate).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnMangaClick(readUpdate))
            fixture.control(unreadUpdate, Res.string.download).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnDownloadClick(unreadUpdate))
            fixture.control(readUpdate, Res.string.downloaded).assertIsNotEnabled()
        }

    @Test
    fun ltrSwipesDispatchExactActionsAndSnapBack() =
        runUpdatesAccessibilityTest {
            UpdatesSwipeDriver(this).verify(LayoutDirection.Ltr)
        }

    @Test
    fun rtlSwipesUseLogicalDirectionsAndSnapBack() =
        runUpdatesAccessibilityTest {
            UpdatesSwipeDriver(this).verify(LayoutDirection.Rtl)
        }

    @Test
    fun narrowLargeFontRtlKeepsFullSemanticsAndUsableNonoverlappingTargets() =
        runUpdatesAccessibilityTest(fontScale = 2f) {
            val fixture = UpdatesRowAccessibilityFixture(this)
            fixture.render(LayoutDirection.Rtl)
            fixture.assertRowActions(unreadUpdate)
            fixture.assertRowActions(readUpdate)
            val controls =
                listOf(
                    fixture.cover(unreadUpdate),
                    fixture.control(unreadUpdate, Res.string.download),
                    fixture.control(unreadUpdate, Res.string.details_more_options),
                )
            val bounds = controls.map { it.assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot }
            bounds.forEach(::assertUsableWithinPhone)
            bounds.zipWithNext().forEach { (start, end) -> assertTrue(start.left >= end.right) }
            fixture.control(unreadUpdate, Res.string.details_more_options).performTouchInput { click() }
            val delete = fixture.menu(Res.string.delete).assertIsDisplayed()
            assertUsableWithinPhone(delete.fetchSemanticsNode().touchBoundsInRoot)
            delete.performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(unreadUpdate))
        }

    @Test
    fun realUndoEffectRestoresTheCannedRowsActionLifecycle() =
        runUpdatesAccessibilityTest {
            val fixture = UpdatesRowAccessibilityFixture(this)
            fixture.render()
            fixture.control(unreadUpdate, Res.string.details_more_options).performTouchInput { click() }
            fixture.menu(Res.string.delete).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnRequestDelete(unreadUpdate))
            runOnIdle { fixture.state = fixture.state.copy(pendingDeleteIds = setOf(unreadUpdate.id)) }
            mainClock.advanceTimeBy(UPDATES_SETTLE_MILLIS)
            waitForIdle()
            fixture.row(unreadUpdate).assertDoesNotExist()
            fixture.control(unreadUpdate, Res.string.details_more_options).assertDoesNotExist()
            onNode(isPopup()).assertDoesNotExist()
            fixture.keyboard.tabTo(fixture.control(readUpdate, Res.string.details_more_options))
            fixture.expectOnly()
            waitUntil { fixture.effects.subscriptionCount.value == 1 }
            runOnIdle { assertTrue(fixture.effects.tryEmit(UpdatesEffect.ShowUndoSnackbar(unreadUpdate))) }
            onNodeWithText(fixture.label(Res.string.undo)).performTouchInput { click() }
            fixture.expectOnly(UpdatesIntent.OnUndoDelete(unreadUpdate))
            runOnIdle { fixture.state = fixture.state.copy(pendingDeleteIds = emptySet()) }
            mainClock.advanceTimeBy(UPDATES_SETTLE_MILLIS)
            waitForIdle()
            fixture.assertRowActions(unreadUpdate)
            fixture.invokeCustom(unreadUpdate, Res.string.details_mark_read)
            fixture.expectOnly(UpdatesIntent.OnMarkAsRead(unreadUpdate))
        }

    private fun assertUsableWithinPhone(bounds: Rect) {
        assertTrue(bounds.width >= UPDATES_MIN_TARGET && bounds.height >= UPDATES_MIN_TARGET)
        assertTrue(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom).all { it.isFinite() })
        assertTrue(bounds.left >= 0 && bounds.top >= 0)
        assertTrue(bounds.right <= UPDATES_PHONE_WIDTH && bounds.bottom <= UPDATES_PHONE_HEIGHT)
    }
}
