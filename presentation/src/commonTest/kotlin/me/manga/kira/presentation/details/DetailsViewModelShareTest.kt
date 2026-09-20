package me.manga.kira.presentation.details

import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.repository.SavedMangaDetailsRepository
import me.manga.kira.presentation.testing.FakeLibraryRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Share effects through real MVI/use cases; the existing fixtures do not prove Room alias policy. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailsViewModelShareTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers =
        object : DispatcherProvider {
            override val main = dispatcher
            override val mainImmediate = dispatcher
            override val default = dispatcher
            override val io = dispatcher
            override val unconfined = dispatcher
        }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun share_usesResolvedDetails_forBothEntryShapes() =
        runTest {
            val seed = manga(title = "Navigation title").copy(url = "https://old.source.example/manga")
            val entries = listOf(DetailsIntent.OnEnter(seed), DetailsIntent.OnEnterByUrl(seed.api, seed.url))
            val resolved = details(emptyList()).copy(title = "Resolved title", url = "https://source.example/manga")
            entries.forEach { entry ->
                val pending = CompletableDeferred<Unit>()
                withShareVm(seed, AppResult.Success(resolved), beforeSavedRead = { pending.await() }) { vm, fetch ->
                    fetch.respond = {
                        pending.await()
                        AppResult.Success(resolved)
                    }
                    vm.effects.test {
                        vm.submit(DetailsIntent.OnShare)
                        expectNoEvents()
                        vm.submit(entry)
                        assertTrue(vm.state.value.isInitialLoading)
                        assertEquals(0, fetch.fetchCount, "Initial loading waits for the retained local snapshot")
                        vm.submit(DetailsIntent.OnShare)
                        expectNoEvents()
                        pending.complete(Unit)
                        runCurrent()
                        assertEquals(1, fetch.fetchCount, "The empty-chapter saved row must still fetch")
                        assertEquals(resolved, vm.state.value.details)
                        assertEquals(savedOwner(seed = seed), vm.state.value.savedOwner)
                        assertTrue(vm.state.value.isInLibrary)
                        assertEquals(seed.url, vm.state.value.manga?.url, "The requested address remains the work key")
                        vm.submit(DetailsIntent.OnShare)
                        assertEquals(DetailsEffect.ShareManga(resolved.title, resolved.url), awaitItem())
                        expectNoEvents()
                    }
                }
            }
        }

    @Test
    fun share_doesNotInventUnavailableBlankOrAdultPayloads() =
        runTest {
            val seed = manga()
            val resolved = details(emptyList())
            val unavailable =
                listOf(
                    AppResult.Failure(AppError.Network.Timeout()) to false,
                    AppResult.Success(resolved.copy(title = "")) to true,
                    AppResult.Success(resolved.copy(url = "")) to true,
                    AppResult.Success(resolved.copy(genres = listOf("Adult"))) to true,
                    AppResult.Success(resolved) to false,
                )
            unavailable.forEach { (result, inLibrary) ->
                withShareVm(seed, result, inLibrary) { vm, fetch ->
                    vm.effects.filterIsInstance<DetailsEffect.ShareManga>().test {
                        vm.submit(DetailsIntent.OnEnterByUrl(seed.api, seed.url))
                        assertFalse(vm.state.value.isLoading)
                        assertEquals(1, fetch.fetchCount)
                        assertEquals(if (inLibrary) savedOwner(seed = seed) else null, vm.state.value.savedOwner)
                        assertEquals(inLibrary, vm.state.value.isInLibrary)
                        when (result) {
                            is AppResult.Success -> {
                                assertEquals(result.value, vm.state.value.details)
                                assertEquals("Adult" in result.value.genres, vm.state.value.isAdultGateActive)
                            }
                            is AppResult.Failure -> {
                                assertEquals(null, vm.state.value.details, "Unavailable means neither local nor fetched details")
                                assertEquals(result.error, vm.state.value.error)
                            }
                        }
                        vm.submit(DetailsIntent.OnShare)
                        expectNoEvents()
                    }
                }
            }
        }

    private suspend fun withShareVm(
        seed: Manga,
        fetched: AppResult<MangaDetails>,
        inLibrary: Boolean = true,
        beforeSavedRead: suspend () -> Unit = {},
        block: suspend (DetailsViewModel, FakeMangaDetailsRepository) -> Unit,
    ) {
        val owner = savedOwner(seed = seed)
        val library = FakeLibraryRepository().apply { if (inLibrary) emitMembership(owner) }
        val saved = FakeSavedMangaDetailsRepository(owner).apply {
            // A retained parent has a local projection even without chapters; null means not saved.
            if (inLibrary) this.saved.value = details(emptyList()).copy(
                api = seed.api,
                language = seed.language,
                title = seed.title,
                url = seed.url,
                coverUrl = seed.coverUrl,
                genres = seed.genres,
            )
        }
        val savedReads = object : SavedMangaDetailsRepository {
            override fun observeSavedDetails(work: WorkLocator) =
                saved.observeSavedDetails(work).onStart { beforeSavedRead() }
        }
        val options =
            VmFixtureOptions().apply {
                libraryRepo = library
                adultClassifier = RecordingAdultClassifier(seed.api to listOf("Adult"))
            }
        val (vm, fetch) = createVmWithFetchFake(fetched, savedReads, options, dispatchers)
        val store = ViewModelStore().apply { put("details", vm) }
        try {
            block(vm, fetch)
        } finally {
            store.clear()
        }
    }
}
