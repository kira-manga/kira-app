package me.manga.kira.presentation.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.domain.repository.ThemeRepository
import me.manga.kira.domain.usecase.theme.ObserveAppThemeUseCase
import me.manga.kira.domain.usecase.theme.ObservePureBlackUseCase
import me.manga.kira.domain.usecase.theme.SetAppThemeUseCase
import me.manga.kira.domain.usecase.theme.SetPureBlackUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the [ThemeViewModel] two-collector posture and mutate-and-re-emit contract (backlog T1):
 *  - the two upstream flows project independently (no combine coupling — an emission on one
 *    never disturbs the other's field),
 *  - `isLoading` clears on the FIRST emission from EITHER upstream,
 *  - the two intents dispatch to the matching repository writes; state changes arrive only via
 *    upstream re-emission (never imperative VM mutation).
 */
class ThemeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class RecordingThemeRepository : ThemeRepository {
        val themeUpstream = MutableSharedFlow<AppTheme>(replay = 1)
        val pureBlackUpstream = MutableSharedFlow<Boolean>(replay = 1)
        val writes = mutableListOf<String>()
        override fun observeAppTheme(): Flow<AppTheme> = themeUpstream
        override suspend fun setAppTheme(theme: AppTheme) {
            writes += "theme:$theme"
        }
        override fun observePureBlack(): Flow<Boolean> = pureBlackUpstream
        override suspend fun setPureBlack(enabled: Boolean) {
            writes += "pureBlack:$enabled"
        }
    }

    private fun viewModel(repo: RecordingThemeRepository) = ThemeViewModel(
        ObserveAppThemeUseCase(repo),
        ObservePureBlackUseCase(repo),
        SetAppThemeUseCase(repo),
        SetPureBlackUseCase(repo),
    )

    @Test
    fun eitherUpstreamEmission_clearsLoading_andProjectsOnlyItsOwnField() = runTest {
        val repo = RecordingThemeRepository()
        val vm = viewModel(repo)
        assertTrue(vm.state.value.isLoading)

        repo.pureBlackUpstream.tryEmit(false)
        assertFalse(vm.state.value.isLoading, "first emission from EITHER upstream clears the spinner")
        assertFalse(vm.state.value.pureBlack)
        assertEquals(AppTheme.System, vm.state.value.theme, "the theme field keeps its default — no cross-trigger")

        repo.themeUpstream.tryEmit(AppTheme.Dark)
        assertEquals(AppTheme.Dark, vm.state.value.theme)
        assertFalse(vm.state.value.pureBlack, "the pureBlack field is untouched by the theme emission")
    }

    @Test
    fun intents_dispatchToTheMatchingRepositoryWrite() = runTest {
        val repo = RecordingThemeRepository()
        val vm = viewModel(repo)

        vm.submit(ThemeIntent.OnSelectTheme(AppTheme.Light))
        vm.submit(ThemeIntent.OnTogglePureBlack(true))

        assertEquals(listOf("theme:Light", "pureBlack:true"), repo.writes)
    }

    @Test
    fun stateTracksUpstream_notTheIntent() = runTest {
        val repo = RecordingThemeRepository()
        val vm = viewModel(repo)

        vm.submit(ThemeIntent.OnSelectTheme(AppTheme.Dark))
        assertEquals(
            AppTheme.System,
            vm.state.value.theme,
            "mutate-and-re-emit: the VM never imperatively mutates the field — only the upstream re-emit does",
        )

        repo.themeUpstream.tryEmit(AppTheme.Dark)
        assertEquals(AppTheme.Dark, vm.state.value.theme)
    }
}
