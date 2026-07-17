package me.manga.kira.presentation.sourceaccess

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.sources.SourceAccessState
import me.manga.kira.domain.repository.SourceAccessRepository
import me.manga.kira.domain.usecase.sourceaccess.ActivateSourceAccessUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StartReadingViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeSourceAccessRepository : SourceAccessRepository {
        override val state = MutableStateFlow(SourceAccessState.LOCKED)
        var writes = 0

        override suspend fun activatePermanently(): Boolean {
            if (state.value == SourceAccessState.ACTIVATED) return false
            writes++
            state.value = SourceAccessState.ACTIVATED
            return true
        }
    }

    @Test
    fun invalid_link_stays_on_screen_and_shows_field_error() =
        runTest {
            val repository = FakeSourceAccessRepository()
            val viewModel = StartReadingViewModel(ActivateSourceAccessUseCase(repository))

            viewModel.submit(StartReadingIntent.OnActivationLinkChanged("https://example.com"))
            viewModel.submit(StartReadingIntent.OnActivate)

            assertTrue(viewModel.state.value.invalidLink)
            assertFalse(viewModel.state.value.isActivating)
            assertEquals(0, repository.writes)
        }

    @Test
    fun valid_link_clears_raw_input_and_emits_success() =
        runTest {
            val repository = FakeSourceAccessRepository()
            val viewModel = StartReadingViewModel(ActivateSourceAccessUseCase(repository))
            val effects = mutableListOf<StartReadingEffect>()
            val collector = launch(dispatcher) { viewModel.effects.collect { effects += it } }

            viewModel.submit(StartReadingIntent.OnActivationLinkChanged("  HTTPS://KIRAMANGA.ME/activate  "))
            viewModel.submit(StartReadingIntent.OnActivate)

            assertEquals(listOf<StartReadingEffect>(StartReadingEffect.ActivationSucceeded), effects)
            assertEquals("", viewModel.state.value.activationLink)
            assertFalse(viewModel.state.value.invalidLink)
            assertEquals(1, repository.writes)
            collector.cancel()
        }

    @Test
    fun continue_and_import_are_navigation_only_and_do_not_activate() =
        runTest {
            val repository = FakeSourceAccessRepository()
            val viewModel = StartReadingViewModel(ActivateSourceAccessUseCase(repository))
            val effects = mutableListOf<StartReadingEffect>()
            val collector = launch(dispatcher) { viewModel.effects.collect { effects += it } }

            viewModel.submit(StartReadingIntent.OnContinueToLibrary)
            viewModel.submit(StartReadingIntent.OnImport)

            assertEquals(
                listOf<StartReadingEffect>(
                    StartReadingEffect.ContinueToLibrary,
                    StartReadingEffect.OpenImport,
                ),
                effects,
            )
            assertEquals(SourceAccessState.LOCKED, repository.state.value)
            assertEquals(0, repository.writes)
            collector.cancel()
        }
}
