package me.manga.kira.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.presentation.settings.SettingsIntent
import me.manga.kira.presentation.settings.SettingsState
import me.manga.kira.ui.accessibility.assertSingleSwitch
import me.manga.kira.ui.accessibility.clickSwitchControl
import me.manga.kira.ui.accessibility.clickSwitchLabel
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.pure_black_mode_title
import me.manga.kira.ui.generated.resources.setting_downloaded_only
import me.manga.kira.ui.generated.resources.setting_downloaded_only_desc
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SettingsControlSemanticsTest {
    @Test
    fun titleOnlyAndDescribedRowsDoNotRetainIndependentChildActions() =
        runSharedControlSemanticsTest {
            var state by mutableStateOf(SettingsState(isLoading = false, pureBlack = false))
            val intents = mutableListOf<SettingsIntent>()
            var downloads = ""
            var downloadHint = ""
            var pureBlack = ""
            setContent {
                KiraTheme(darkTheme = false) {
                    downloads = stringResource(Res.string.setting_downloaded_only)
                    downloadHint = stringResource(Res.string.setting_downloaded_only_desc)
                    pureBlack = stringResource(Res.string.pure_black_mode_title)
                    SettingsScreenContent(
                        state = state,
                        effects = emptyFlow(),
                        onIntent = {
                            intents += it
                            if (it is SettingsIntent.OnToggle) {
                                state =
                                    when (it.toggle) {
                                        SettingsToggle.DOWNLOADED_ONLY -> state.copy(downloadedOnly = it.value)
                                        SettingsToggle.PURE_BLACK -> state.copy(pureBlack = it.value)
                                        else -> state
                                    }
                            }
                        },
                        onNavigate = {},
                    )
                }
            }
            awaitIdle()
            for ((label, hint, toggle) in listOf(
                Triple(downloads, downloadHint, SettingsToggle.DOWNLOADED_ONLY),
                Triple(pureBlack, null, SettingsToggle.PURE_BLACK),
            )) {
                assertSingleSwitch(label, checked = false, hint = hint)
                    .assertHeightIsAtLeast(64.dp)
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
                awaitIdle()
                assertSingleSwitch(label, checked = true, hint = hint).assertHeightIsAtLeast(64.dp)
                clickSwitchLabel(label)
                awaitIdle()
                assertSingleSwitch(label, checked = false, hint = hint).assertHeightIsAtLeast(64.dp)
                clickSwitchControl(label)
                awaitIdle()
                assertSingleSwitch(label, checked = true, hint = hint).assertHeightIsAtLeast(64.dp)
                assertEquals<List<SettingsIntent>>(
                    listOf(
                        SettingsIntent.OnToggle(toggle, true),
                        SettingsIntent.OnToggle(toggle, false),
                        SettingsIntent.OnToggle(toggle, true),
                    ),
                    intents,
                )
                runOnIdle { intents.clear() }
            }
        }
}
