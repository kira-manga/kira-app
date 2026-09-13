package me.manga.kira.ui.themepicker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performSemanticsAction
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.presentation.theme.ThemeIntent
import me.manga.kira.presentation.theme.ThemeState
import me.manga.kira.ui.accessibility.assertSingleSwitch
import me.manga.kira.ui.accessibility.clickSwitchControl
import me.manga.kira.ui.accessibility.clickSwitchLabel
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.pure_black_mode_title
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ThemeControlSemanticsTest {
    @Test
    fun pureBlackHasOneNamedActionAndRemainsAvailableInLightTheme() = runSharedControlSemanticsTest {
        var state by mutableStateOf(ThemeState(isLoading = false, theme = AppTheme.Light, pureBlack = false))
        val intents = mutableListOf<ThemeIntent>()
        var label = ""
        setContent {
            KiraTheme(darkTheme = false) {
                label = stringResource(Res.string.pure_black_mode_title)
                ThemeScreenContent(
                    state = state,
                    onIntent = {
                        intents += it
                        if (it is ThemeIntent.OnTogglePureBlack) state = state.copy(pureBlack = it.enabled)
                    },
                )
            }
        }
        awaitIdle()
        assertSingleSwitch(label, checked = false)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertSingleSwitch(label, checked = true)
        clickSwitchLabel(label)
        awaitIdle()
        assertSingleSwitch(label, checked = false)
        clickSwitchControl(label)
        awaitIdle()
        assertSingleSwitch(label, checked = true)
        assertEquals(
            listOf(
                ThemeIntent.OnTogglePureBlack(true),
                ThemeIntent.OnTogglePureBlack(false),
                ThemeIntent.OnTogglePureBlack(true),
            ),
            intents,
        )
    }
}
