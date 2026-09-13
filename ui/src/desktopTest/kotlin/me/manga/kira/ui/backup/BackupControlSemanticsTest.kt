package me.manga.kira.ui.backup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performSemanticsAction
import kotlinx.coroutines.flow.emptyFlow
import me.manga.kira.presentation.backup.BackupIntent
import me.manga.kira.presentation.backup.BackupState
import me.manga.kira.ui.accessibility.assertSingleSwitch
import me.manga.kira.ui.accessibility.clickSwitchControl
import me.manga.kira.ui.accessibility.clickSwitchLabel
import me.manga.kira.ui.accessibility.runSharedControlSemanticsTest
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backup_include_downloads
import me.manga.kira.ui.generated.resources.backup_include_downloads_hint
import me.manga.kira.ui.theme.KiraTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class BackupControlSemanticsTest {
    @Test
    fun includeDownloadsHasOneNamedActionAndKeepsDisabledStates() = runSharedControlSemanticsTest {
        var state by mutableStateOf(BackupState())
        val intents = mutableListOf<BackupIntent>()
        var label = ""
        var hint = ""
        setContent {
            KiraTheme(darkTheme = false) {
                label = stringResource(Res.string.backup_include_downloads)
                hint = stringResource(Res.string.backup_include_downloads_hint)
                BackupScreenContent(
                    state = state,
                    effects = emptyFlow(),
                    onIntent = {
                        intents += it
                        if (it == BackupIntent.OnToggleIncludeDownloads) {
                            state = state.copy(includeDownloads = !state.includeDownloads)
                        }
                    },
                    onNavigateBack = {},
                    onLaunchExportPicker = { _, _ -> },
                    onLaunchImportPicker = {},
                )
            }
        }
        awaitIdle()
        assertSingleSwitch(label, checked = false, hint = hint)
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        awaitIdle()
        assertSingleSwitch(label, checked = true, hint = hint)
        clickSwitchLabel(label)
        awaitIdle()
        assertSingleSwitch(label, checked = false, hint = hint)
        clickSwitchControl(label)
        awaitIdle()
        assertSingleSwitch(label, checked = true, hint = hint)
        assertEquals<List<BackupIntent>>(List(3) { BackupIntent.OnToggleIncludeDownloads }, intents)

        for (checked in listOf(false, true)) {
            runOnIdle {
                intents.clear()
                state = state.copy(includeDownloads = checked, isCbzConversionRunning = true)
            }
            assertSingleSwitch(label, checked = checked, enabled = false, hint = hint)
            clickSwitchLabel(label)
            clickSwitchControl(label)
            awaitIdle()
            assertEquals(emptyList(), intents)
            assertSingleSwitch(label, checked = checked, enabled = false, hint = hint)
        }
    }
}
