package me.manga.kira.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.library.LibraryState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.library_source_badge_format
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class LibrarySourceBadgeContrastTest {
    @Test
    fun realBadgeKeepsOpaqueBackingAndRenderedForeground() =
        runComposeUiTest {
            val scenario = mutableStateOf(badgeCases.first())
            var label = ""
            showBadge(scenario) { label = it }
            badgeCases.forEach { case ->
                runOnIdle { scenario.value = case }
                awaitIdle()
                val layout = assertTextForeground(label, case.foreground, case.fontScale)
                assertEquals(
                    if (case.enlargedRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    layout.layoutInput.layoutDirection,
                )
                if (case.enlargedRtl) assertTrue(layout.isLineEllipsized(0), "long RTL label")
                assertBrandInterior(onNodeWithTag(BADGE_TAG).captureToImage(), case.brand, case.toString())
            }
        }

    @Test
    fun actualCardsKeepBadgeGatingAndSelectionPlaceholderComposition() =
        runComposeUiTest {
            val item = savedManga()
            val initial =
                LibraryState(isLoading = false, hasLibraryItems = true, items = listOf(item), itemsPerRow = 2)
            val selected =
                initial.copy(
                    selection = setOf(MangaKey(item.manga.api, item.manga.language, item.manga.title)),
                    isInSelectionMode = true,
                )
            val hidden = initial.copy(display = initial.display.copy(showSource = false))
            val blankApi = initial.copy(items = listOf(item.copy(manga = item.manga.copy(api = "  "))))
            val state = mutableStateOf(initial)
            var label = ""
            showLibrary(state) { label = it }
            listOf(initial to true, selected to true, hidden to false, blankApi to false).forEach { (next, visible) ->
                runOnIdle { state.value = next }
                awaitIdle()
                onNodeWithText(item.manga.title, useUnmergedTree = true).assertIsDisplayed()
                if (visible) {
                    assertTextForeground(label, Color.Black, 1f)
                } else {
                    onNodeWithText(label, useUnmergedTree = true).assertDoesNotExist()
                }
            }
        }

    private fun ComposeUiTest.showBadge(state: State<BadgeCase>, onLabel: (String) -> Unit) {
        setContent {
            val case = state.value
            CompositionLocalProvider(
                LocalDensity provides Density(1f, case.fontScale),
                LocalLayoutDirection provides if (case.enlargedRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                KiraTheme(darkTheme = case.theme != BadgeTheme.LIGHT, pureBlack = case.theme == BadgeTheme.PURE_BLACK) {
                    onLabel(stringResource(Res.string.library_source_badge_format, case.api, case.language))
                    Box(
                        modifier = Modifier.size(270.dp, 72.dp).coverPixels(case.underlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        LibrarySourceBadge(case.api, case.language, Modifier.testTag(BADGE_TAG))
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.showLibrary(state: State<LibraryState>, onLabel: (String) -> Unit) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                KiraTheme(darkTheme = false) {
                    val manga = state.value.items.single().manga
                    onLabel(stringResource(Res.string.library_source_badge_format, manga.api, manga.language))
                    Box(Modifier.size(400.dp, 720.dp)) {
                        LibraryScreenContent(
                            state = state.value,
                            effects = emptyFlow(),
                            onIntent = {},
                            onNavigateToDetails = {},
                            onNavigateToDownloads = {},
                            onNavigateToBackupExport = {},
                            coverModel = { null },
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.assertTextForeground(
        label: String,
        expected: Color,
        fontScale: Float,
    ): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        onNodeWithText(label, useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
        val layout = results.single()
        assertEquals(label, layout.layoutInput.text.text)
        assertEquals(expected, layout.layoutInput.style.color)
        assertEquals(1f, layout.layoutInput.style.color.alpha)
        assertEquals(8.sp, layout.layoutInput.style.fontSize)
        assertEquals(FontWeight.Bold, layout.layoutInput.style.fontWeight)
        assertEquals(fontScale, layout.layoutInput.density.fontScale)
        assertEquals(1, layout.layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        return layout
    }

    private fun assertBrandInterior(image: ImageBitmap, brand: Color, context: String) {
        val pixels = image.toPixelMap()
        assertTrue(pixels.width > 12 && pixels.height > 8, context)
        // Density is 1: this strip lies inside the real 6dp padding, away from rounded corners,
        // shadows and glyphs. Flattened alpha1 alone would also pass a translucent brand@0.8.
        for (x in 2..4) {
            for (y in pixels.height / 2 - 1..pixels.height / 2 + 1) {
                assertEquals(brand.toArgb(), pixels[x, y].toArgb(), "$context interior ($x,$y)")
            }
        }
    }

    // Only deterministic cover pixels are synthetic; LibrarySourceBadge is never copied.
    private fun Modifier.coverPixels(underlay: Underlay): Modifier =
        drawBehind {
            drawRect(if (underlay == Underlay.BLACK) Color.Black else Color.White)
            if (underlay == Underlay.BUSY) {
                val step = 8.dp.toPx()
                repeat((size.width / step).toInt() + 1) { column ->
                    repeat((size.height / step).toInt() + 1) { row ->
                        if ((column + row) % 2 == 0) {
                            drawRect(Color.Black, Offset(column * step, row * step), Size(step, step))
                        }
                    }
                }
            }
        }

    private enum class BadgeTheme {
        LIGHT,
        DARK,
        PURE_BLACK,
    }

    private enum class Underlay {
        WHITE,
        BLACK,
        BUSY,
    }

    private data class BadgeCase(
        val theme: BadgeTheme,
        val underlay: Underlay,
        val enlargedRtl: Boolean = false,
    ) {
        val api: String get() = if (enlargedRtl) "Mangabuddy" else "Azora"
        val language: String get() = if (enlargedRtl) "العربية ".repeat(8) else "ar"
        val fontScale: Float get() = if (enlargedRtl) 2f else 1f
        val brand: Color get() = if (enlargedRtl) Color(0xFF0000A2) else Color(0xFF867C01)
        val foreground: Color get() = if (enlargedRtl) Color.White else Color.Black
    }

    private companion object {
        const val BADGE_TAG = "library-source-badge-under-test"

        val badgeCases =
            listOf(
                BadgeCase(BadgeTheme.LIGHT, Underlay.WHITE),
                BadgeCase(BadgeTheme.LIGHT, Underlay.BLACK),
                BadgeCase(BadgeTheme.DARK, Underlay.WHITE),
                BadgeCase(BadgeTheme.DARK, Underlay.BLACK),
                BadgeCase(BadgeTheme.PURE_BLACK, Underlay.WHITE),
                BadgeCase(BadgeTheme.PURE_BLACK, Underlay.BLACK),
                BadgeCase(BadgeTheme.PURE_BLACK, Underlay.BUSY, enlargedRtl = true),
            )
    }
}

private fun savedManga(): LibraryManga =
    LibraryManga(
        manga =
            Manga(
                api = "Azora",
                language = "ar",
                title = "Saved manga",
                url = "https://example.invalid/manga",
                coverUrl = "",
                rating = null,
                genres = emptyList(),
            ),
        addedAt = Instant.fromEpochMilliseconds(0),
        unreadCount = 0,
        hasDownloads = false,
        totalChapters = 1,
        lastReadAt = null,
        lastOpenedAt = Instant.fromEpochMilliseconds(0),
        bookmarkedCount = 0,
        downloadedCount = 0,
        isLiked = false,
        isWatchingNow = false,
    )
