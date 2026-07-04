package me.manga.kira.presentation.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.domain.repository.ReadingModeRepository
import me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.settings.ClearCacheUseCase
import me.manga.kira.domain.usecase.settings.ClearCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.CompressExistingDownloadsUseCase
import me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase
import me.manga.kira.domain.usecase.settings.StopCbzConversionUseCase
import me.manga.kira.domain.usecase.settings.UpdateSettingsToggleUseCase
import me.manga.kira.presentation.testing.FakeSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural reducer tests for [SettingsViewModel], focused on #14 — the CBZ-conversion dialog
 * dismiss path must reset BOTH the projected state field AND the underlying hot progress flow so a
 * terminal snapshot does not replay into a recreated ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeReadingModeRepository : ReadingModeRepository {
        override fun observe(): Flow<ReadingMode> = flowOf(ReadingMode.DEFAULT)
        override suspend fun set(mode: ReadingMode) = Unit
    }

    private class FakeFeedbackRepository : FeedbackRepository {
        override suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit> =
            Result.success(Unit)
    }

    private fun vm(settings: FakeSettingsRepository): SettingsViewModel {
        val reading = FakeReadingModeRepository()
        val feedback = FakeFeedbackRepository()
        return SettingsViewModel(
            ObserveSettingsUseCase(settings),
            ObserveReadingModeUseCase(reading),
            UpdateSettingsToggleUseCase(settings),
            ClearCacheUseCase(settings),
            SubmitFeedbackUseCase(feedback),
            SetReadingModeUseCase(reading),
            CompressExistingDownloadsUseCase(settings),
            ObserveCbzConversionUseCase(settings),
            StopCbzConversionUseCase(settings),
            ClearCbzConversionUseCase(settings),
        )
    }

    @Test
    fun dismissFromTerminalState_resetsStateAndClearsUnderlyingFlow() = runTest {
        val settings = FakeSettingsRepository()
        // Seed a TERMINAL snapshot (run finished) BEFORE the VM subscribes — the init collector
        // projects it, mimicking a recreated VM that finds the hot flow at a terminal value.
        settings.conversionProgress.value = CbzConversionProgress(
            isConverting = false,
            totalChapters = 3,
            convertedChapters = 3,
            successMessage = "done",
        )
        val vm = vm(settings)
        assertEquals("done", vm.state.value.cbzConversion.successMessage, "terminal snapshot projected into state")

        vm.submit(SettingsIntent.OnDismissConversionDialog)

        assertEquals(CbzConversionProgress(), vm.state.value.cbzConversion, "state reset to idle on dismiss")
        assertEquals(1, settings.clearConversionCalls, "underlying progress flow cleared so it can't replay (#14)")
    }

    @Test
    fun dismissWhileConverting_isBlocked() = runTest {
        val settings = FakeSettingsRepository()
        settings.conversionProgress.value = CbzConversionProgress(
            isConverting = true,
            totalChapters = 5,
            convertedChapters = 2,
        )
        val vm = vm(settings)
        assertTrue(vm.state.value.cbzConversion.isConverting, "converting snapshot projected into state")

        vm.submit(SettingsIntent.OnDismissConversionDialog)

        assertTrue(vm.state.value.cbzConversion.isConverting, "dismiss ignored while a run is in flight")
        assertEquals(0, settings.clearConversionCalls, "in-converting guard blocks the clear")
        assertFalse(vm.state.value.cbzConversion.convertedChapters == 0, "live progress untouched")
    }
}
