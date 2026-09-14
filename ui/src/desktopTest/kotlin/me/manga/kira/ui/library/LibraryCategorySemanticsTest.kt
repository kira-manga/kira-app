package me.manga.kira.ui.library

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.ui.accessibility.assertTabSelector
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.accessibility.tabMatcher
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.filter_all
import me.manga.kira.ui.generated.resources.library_category_liked
import me.manga.kira.ui.generated.resources.library_category_watching
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs Library's actual shared category row, not a reimplementation of its semantics. */
@OptIn(ExperimentalTestApi::class)
class LibraryCategorySemanticsTest {
    @Test
    fun englishCategoriesExposeExactlyOneSelectionAndPreserveIntents() = categoryControls(Locale.US, LayoutDirection.Ltr)

    @Test
    fun arabicRtlCategoriesExposeExactlyOneSelectionAndPreserveIntents() =
        categoryControls(Locale.forLanguageTag("ar"), LayoutDirection.Rtl)

    private fun categoryControls(
        locale: Locale,
        direction: LayoutDirection,
    ) = runSharedControlSemanticsTest(locale) {
        val surface = LibraryCategorySemanticsFixture()
        surface.render(this, direction)
        awaitIdle()
        val expectedLabels =
            if (locale.language == "ar") {
                listOf("الكل", "الإعجابات", "تشاهد الآن")
            } else {
                listOf("All", "Liked", "Watching")
            }
        assertEquals(expectedLabels, surface.labels)
        assertTabSelector(surface.labels, selectedIndex = 0, direction)
        assertCategoryActions(surface, direction)
        assertEquals(
            listOf(LibraryCategory.LIKED, LibraryCategory.WATCHING_NOW, LibraryCategory.NAN, LibraryCategory.NAN)
                .map { LibraryIntent.OnCategoryChange(it) },
            surface.intents,
        )
    }

    private suspend fun ComposeUiTest.assertCategoryActions(
        surface: LibraryCategorySemanticsFixture,
        direction: LayoutDirection,
    ) {
        onNode(tabMatcher(surface.labels[1])).performTouchInput { click() }
        awaitIdle()
        assertTabSelector(surface.labels, selectedIndex = 1, direction)
        onNode(tabMatcher(surface.labels[2])).performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertTabSelector(surface.labels, selectedIndex = 2, direction)
        val all = onNode(tabMatcher(surface.labels.first()))
        all.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        awaitIdle()
        all.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        awaitIdle()
        assertTabSelector(surface.labels, selectedIndex = 0, direction)
        all.performClick()
        awaitIdle()
        assertTabSelector(surface.labels, selectedIndex = 0, direction)
        assertEquals(LibraryCategory.NAN, surface.category)
    }
}

@OptIn(ExperimentalTestApi::class)
private class LibraryCategorySemanticsFixture {
    var category by mutableStateOf(LibraryCategory.NAN)
    var labels = emptyList<String>()
    val intents = mutableListOf<LibraryIntent>()

    fun render(
        ui: ComposeUiTest,
        direction: LayoutDirection,
    ) {
        ui.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                KiraTheme(darkTheme = false) {
                    labels =
                        listOf(
                            stringResource(Res.string.filter_all),
                            stringResource(Res.string.library_category_liked),
                            stringResource(Res.string.library_category_watching),
                        )
                    CategoryTabs(category = category, onIntent = ::accept)
                }
            }
        }
    }

    private fun accept(intent: LibraryIntent) {
        check(intent is LibraryIntent.OnCategoryChange)
        intents += intent
        category = intent.category
    }
}
