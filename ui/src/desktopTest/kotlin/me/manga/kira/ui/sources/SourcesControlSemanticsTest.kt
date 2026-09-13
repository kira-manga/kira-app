package me.manga.kira.ui.sources

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.sources.Source
import me.manga.kira.presentation.sources.SourcesIntent
import me.manga.kira.presentation.sources.SourcesState
import me.manga.kira.ui.accessibility.assertSingleSwitch
import me.manga.kira.ui.accessibility.assertSwitchLabelOrder
import me.manga.kira.ui.accessibility.clickSwitchControl
import me.manga.kira.ui.accessibility.clickSwitchLabel
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.common.LocalSourceIconResolver
import me.manga.kira.ui.common.SourceIconResolution
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.enable_disable_all_sources
import me.manga.kira.ui.generated.resources.source_disabled
import me.manga.kira.ui.generated.resources.source_enabled
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SourcesControlSemanticsTest {
    @Test
    fun englishLabelsAssociateWithMasterAndIndividualActions() =
        sourceControls(Locale.US, LayoutDirection.Ltr)

    @Test
    fun arabicResourcesAndRtlPreserveNamesStatesAndActions() =
        sourceControls(Locale.forLanguageTag("ar"), LayoutDirection.Rtl)

    private fun sourceControls(locale: Locale, direction: LayoutDirection) = runSharedControlSemanticsTest(locale) {
        val surface = SourcesControlFixture()
        surface.render(this, direction)
        awaitIdle()
        assertEquals(if (locale.language == "ar") "مُفعّل" else "Enabled", surface.enabled)
        assertEquals(if (locale.language == "ar") "مُعطّل" else "Disabled", surface.disabled)
        assertEquals(
            if (locale.language == "ar") "تفعيل او الغاء مصادر ال (ar)" else "Enable/disable all (ar) sources",
            surface.masterHint,
        )
        onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch),
            useUnmergedTree = true,
        ).assertCountEquals(3)
        assertSourceRows(surface, master = true, first = true, second = false)
        assertSwitchLabelOrder(MASTER_LABEL, direction)
        assertSwitchLabelOrder(FIRST_SOURCE.displayName, direction)
        assertSingleSwitch(MASTER_LABEL, checked = true, hint = surface.masterHint, minimumHeight = 72.dp)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertCollapsedGroup(surface)
        assertEquals(listOf(SourcesIntent.OnToggleLanguage(LANGUAGE, false)), surface.intents)
        clickSwitchLabel(MASTER_LABEL)
        awaitIdle()
        assertSourceRows(surface, master = true, first = true, second = true)
        clickSwitchControl(FIRST_SOURCE.displayName, direction)
        awaitIdle()
        assertSourceRows(surface, master = true, first = false, second = true)
        assertSingleSwitch(FIRST_SOURCE.displayName, checked = false, stateDescription = surface.disabled)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertSourceRows(surface, master = true, first = true, second = true)
        clickSwitchLabel(FIRST_SOURCE.displayName)
        awaitIdle()
        assertSourceRows(surface, master = true, first = false, second = true)
        clickSwitchControl(MASTER_LABEL, direction)
        awaitIdle()
        assertCollapsedGroup(surface)
        assertEquals(
            listOf(
                SourcesIntent.OnToggleLanguage(LANGUAGE, false),
                SourcesIntent.OnToggleLanguage(LANGUAGE, true),
                SourcesIntent.OnToggleSource(FIRST_SOURCE, false),
                SourcesIntent.OnToggleSource(FIRST_SOURCE.copy(isEnabled = false), true),
                SourcesIntent.OnToggleSource(FIRST_SOURCE, false),
                SourcesIntent.OnToggleLanguage(LANGUAGE, false),
            ),
            surface.intents,
        )
    }

    private fun ComposeUiTest.assertCollapsedGroup(surface: SourcesControlFixture) {
        // Existing behavior: all-disabled language groups hide their individual source rows.
        assertSingleSwitch(MASTER_LABEL, checked = false, hint = surface.masterHint, minimumHeight = 72.dp)
        onNodeWithText(FIRST_SOURCE.displayName).assertDoesNotExist()
        onNodeWithText(SECOND_SOURCE.displayName).assertDoesNotExist()
    }

    private fun ComposeUiTest.assertSourceRows(
        surface: SourcesControlFixture,
        master: Boolean,
        first: Boolean,
        second: Boolean,
    ) {
        assertSingleSwitch(MASTER_LABEL, checked = master, hint = surface.masterHint, minimumHeight = 72.dp)
        for ((source, checked) in listOf(FIRST_SOURCE to first, SECOND_SOURCE to second)) {
            // Exact Text contains only the source name: neither avatar initials nor state copy.
            assertSingleSwitch(
                source.displayName,
                checked = checked,
                stateDescription = if (checked) surface.enabled else surface.disabled,
                minimumHeight = 72.dp,
            )
        }
    }
}

private const val LANGUAGE = "(ar)"
private const val MASTER_LABEL = "ar"
private val FIRST_SOURCE = Source("app34-birch", LANGUAGE, 0, true, "Birch Source")
private val SECOND_SOURCE = Source("app34-cedar", LANGUAGE, 1, false, "Cedar Source")

@OptIn(ExperimentalTestApi::class)
private class SourcesControlFixture {
    var state by mutableStateOf(SourcesState(isLoading = false, items = listOf(FIRST_SOURCE, SECOND_SOURCE)))
    val intents = mutableListOf<SourcesIntent>()
    var masterHint = ""
    var enabled = ""
    var disabled = ""

    fun render(ui: ComposeUiTest, direction: LayoutDirection) {
        ui.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalSourceIconResolver provides { SourceIconResolution.None },
            ) {
                KiraTheme(darkTheme = false) {
                    masterHint = stringResource(Res.string.enable_disable_all_sources, LANGUAGE)
                    enabled = stringResource(Res.string.source_enabled)
                    disabled = stringResource(Res.string.source_disabled)
                    SourcesScreenContent(
                        state = state,
                        effects = emptyFlow(),
                        onIntent = ::accept,
                        onImportFromStorage = {},
                    )
                }
            }
        }
    }

    private fun accept(intent: SourcesIntent) {
        intents += intent
        state = when (intent) {
            is SourcesIntent.OnToggleLanguage -> state.copy(
                items = state.items.map {
                    if (it.language == intent.language) it.copy(isEnabled = intent.enabled) else it
                },
            )
            is SourcesIntent.OnToggleSource -> state.copy(
                items = state.items.map {
                    if (it.api == intent.source.api) it.copy(isEnabled = intent.enabled) else it
                },
            )
            else -> state
        }
    }
}
