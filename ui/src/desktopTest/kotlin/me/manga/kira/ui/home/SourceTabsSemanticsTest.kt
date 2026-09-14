package me.manga.kira.ui.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.domain.model.home.SiteState
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.ui.accessibility.assertTabSelector
import me.manga.kira.ui.accessibility.rawSubtree
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.accessibility.tabMatcher
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.home_edit_sources
import me.manga.kira.ui.generated.resources.home_new_source_badge
import me.manga.kira.ui.home.components.SourceTabsRow
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Hosted common mobile controls; not a TalkBack/VoiceOver announcement qualification. */
@OptIn(ExperimentalTestApi::class)
class SourceTabsSemanticsTest {
    @Test
    fun englishTabsAndEditBadgeExposeSelectionAndOneAction() = sourceControls(Locale.US, LayoutDirection.Ltr)

    @Test
    fun arabicRtlTabsAndEditBadgeExposeSelectionAndOneAction() = sourceControls(Locale.forLanguageTag("ar"), LayoutDirection.Rtl)

    private fun sourceControls(
        locale: Locale,
        direction: LayoutDirection,
    ) = runSharedControlSemanticsTest(locale) {
        val names =
            if (locale.language == "ar") {
                listOf("أزورا", "تيم إكس", "مانجا")
            } else {
                listOf("Azora", "Team X", "Manga")
            }
        val surface = SourceTabsSemanticsFixture(names, locale.language)
        surface.render(this, direction)
        awaitIdle()
        assertEquals(if (locale.language == "ar") "تعديل المصادر" else "Edit sources", surface.editLabel)
        assertEquals(if (locale.language == "ar") "جديد" else "New", surface.badgeLabel)
        assertTabSelector(names, selectedIndex = 0, direction)
        assertSelectionActions(surface, direction)
        assertEditBadgeActions(surface)
        assertTabSelector(names, selectedIndex = 0, direction)
        assertEquals(listOf(1, 2, 0, 0), surface.selections)
    }

    private suspend fun ComposeUiTest.assertSelectionActions(
        surface: SourceTabsSemanticsFixture,
        direction: LayoutDirection,
    ) {
        onNode(tabMatcher(surface.names[1])).performTouchInput { click() }
        awaitIdle()
        assertTabSelector(surface.names, selectedIndex = 1, direction)
        onNode(tabMatcher(surface.names[2])).performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertTabSelector(surface.names, selectedIndex = 2, direction)
        val first = onNode(tabMatcher(surface.names.first()))
        first.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        awaitIdle()
        first.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        awaitIdle()
        assertTabSelector(surface.names, selectedIndex = 0, direction)
        first.performClick()
        awaitIdle()
        assertTabSelector(surface.names, selectedIndex = 0, direction)
        assertEquals(listOf(1, 2, 0, 0), surface.selections)
        assertEquals(0, surface.edits)
    }

    private suspend fun ComposeUiTest.assertEditBadgeActions(surface: SourceTabsSemanticsFixture) {
        assertEditAction(surface, badgeActive = true).performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertEditAction(surface, badgeActive = false)
        assertEquals(1, surface.edits)
        runOnIdle { surface.showNewBadge = true }
        awaitIdle()
        val edit = assertEditAction(surface, badgeActive = true)
        edit.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        awaitIdle()
        edit.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        awaitIdle()
        assertEditAction(surface, badgeActive = false)
        assertEquals(2, surface.edits)
        runOnIdle {
            surface.showNewBadge = true
            surface.badgeLabelAvailable = false
        }
        awaitIdle()
        assertEditAction(surface, badgeActive = false)
        assertEquals(2, surface.edits)
    }

    private fun ComposeUiTest.assertEditAction(
        surface: SourceTabsSemanticsFixture,
        badgeActive: Boolean,
    ): SemanticsNodeInteraction {
        onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertCountEquals(1)
        onAllNodesWithContentDescription(surface.editLabel).assertCountEquals(1)
        assertOneEditFocusTarget()
        val edit =
            onNodeWithContentDescription(surface.editLabel)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(surface.editLabel)))
        assertEquals(
            if (badgeActive) surface.badgeLabel else null,
            edit.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
        val raw = rawSubtree(edit)
        assertEquals(1, raw.count { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
        assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.OnClick) != null })
        assertEquals(1, raw.count { it.config.getOrNull(SemanticsActions.RequestFocus) != null })
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription), useUnmergedTree = true)
            .assertCountEquals(if (badgeActive) 1 else 0)
        onAllNodesWithContentDescription(surface.badgeLabel, useUnmergedTree = true).assertCountEquals(0)
        onAllNodesWithText(surface.badgeLabel, useUnmergedTree = true).assertCountEquals(0)
        return edit
    }

    private fun ComposeUiTest.assertOneEditFocusTarget() {
        val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        val focusable = SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus)
        onAllNodes(focusable and !group and !hasAnyAncestor(group), useUnmergedTree = true).assertCountEquals(1)
    }
}

@OptIn(ExperimentalTestApi::class)
private class SourceTabsSemanticsFixture(
    val names: List<String>,
    language: String,
) {
    var activeIndex by mutableIntStateOf(0)
    var showNewBadge by mutableStateOf(true)
    var badgeLabelAvailable by mutableStateOf(true)
    val selections = mutableListOf<Int>()
    var edits = 0
    var editLabel = ""
    var badgeLabel = ""
    private val tabs =
        names.mapIndexed { index, name ->
            SourceTab("source-$index", language, iconKey = null, siteState = SiteState.WORKING, displayName = name)
        }

    fun render(
        ui: ComposeUiTest,
        direction: LayoutDirection,
    ) {
        ui.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KiraTheme(darkTheme = false) {
                    editLabel = stringResource(Res.string.home_edit_sources)
                    badgeLabel = stringResource(Res.string.home_new_source_badge)
                    SourceTabsRow(
                        tabs = tabs,
                        activeTabIndex = activeIndex,
                        onTabSelected = ::selectTab,
                        onEditSources = ::editSources,
                        showNewBadge = showNewBadge,
                        newBadgeLabel = badgeLabel.takeIf { badgeLabelAvailable },
                        editContentDescription = editLabel,
                    )
                }
            }
        }
    }

    private fun selectTab(index: Int) {
        selections += index
        activeIndex = index
    }

    private fun editSources() {
        edits++
        showNewBadge = false
    }
}
