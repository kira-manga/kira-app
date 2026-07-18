@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package me.manga.kira.presentation.about

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.domain.model.about.AppMetadata
import me.manga.kira.domain.repository.AboutRepository
import me.manga.kira.domain.usecase.about.GetAppMetadataUseCase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Locks the [AboutViewModel] contract (backlog T1 tail — the last untested presentation feature):
 *  - the `init {}` one-shot load projects [AppMetadata] into state and clears the spinner,
 *  - a throwing load is absorbed by `launchSafely` and the `finally` still clears the spinner,
 *  - the four intents emit their one-shot effects — [AboutEffect.OpenPlayStorePage] carries the
 *    CURRENT state's package name, while review and URL actions are pure pass-throughs.
 */
class AboutViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeAboutRepository(
        var behavior: () -> AppMetadata,
    ) : AboutRepository {
        override suspend fun getMetadata(): AppMetadata = behavior()
    }

    private fun viewModel(
        behavior: () -> AppMetadata = {
            AppMetadata(versionName = "1.0.0", packageName = "me.manga.kira")
        },
    ) = AboutViewModel(GetAppMetadataUseCase(FakeAboutRepository(behavior)))

    @Test
    fun initLoad_projectsMetadata_andClearsLoading() =
        runTest {
            val vm = viewModel()

            val state = vm.state.value
            assertFalse(state.isLoading)
            assertEquals("1.0.0", state.versionName)
            assertEquals("me.manga.kira", state.packageName)
        }

    @Test
    fun throwingLoad_isAbsorbed_andSpinnerStillClears() =
        runTest {
            val vm = viewModel(behavior = { throw RuntimeException("metadata boom") })

            val state = vm.state.value
            assertFalse(state.isLoading, "the finally must clear the spinner on a failed load")
            assertEquals("", state.versionName, "fields degrade to the defaults, no crash")
        }

    @Test
    fun onOpenPlayStore_emitsEffectWithTheLoadedPackageName() =
        runTest {
            val vm = viewModel()
            val effects = mutableListOf<AboutEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

            vm.submit(AboutIntent.OnOpenPlayStore)

            assertEquals(
                listOf<AboutEffect>(AboutEffect.OpenPlayStorePage(packageName = "me.manga.kira")),
                effects,
            )
            collector.cancel()
        }

    @Test
    fun review_openUrl_andOpenWhatsNew_emitTheirOneShotEffects() =
        runTest {
            val vm = viewModel()
            val effects = mutableListOf<AboutEffect>()
            val collector = launch(dispatcher) { vm.effects.collect { effects += it } }

            vm.submit(AboutIntent.OnRequestReview)
            vm.submit(AboutIntent.OnOpenUrl("https://example.com/help"))
            vm.submit(AboutIntent.OnOpenWhatsNew)

            assertEquals(
                listOf(
                    AboutEffect.RequestReview,
                    AboutEffect.OpenUrl("https://example.com/help"),
                    AboutEffect.NavigateToWhatsNew,
                ),
                effects,
            )
            collector.cancel()
        }
}
