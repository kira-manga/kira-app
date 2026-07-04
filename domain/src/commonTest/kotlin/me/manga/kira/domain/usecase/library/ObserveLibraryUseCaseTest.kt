package me.manga.kira.domain.usecase.library

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.testing.FakeLibraryRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Harness-proving test for the `:domain` test infrastructure: exercises the Turbine +
 * coroutines-test stack against [FakeLibraryRepository] through [ObserveLibraryUseCase].
 *
 * The use case is a thin pass-through, so the value asserted here is twofold: (1) it proves the
 * test toolchain (kotlin-test + coroutines-test + Turbine) runs end-to-end on the JVM target, and
 * (2) it pins the contract that observing the library is a pure read — it never invokes a
 * repository mutator. A richer pass-through assertion with real [LibraryManga] rows lands with the
 * LibraryManga test-factory in the next slice (it needs a `kotlin.time.Instant`).
 */
class ObserveLibraryUseCaseTest {

    @Test
    fun forwards_the_repository_library_flow() = runTest {
        val repo = FakeLibraryRepository()
        val useCase = ObserveLibraryUseCase(repo)

        useCase().test {
            assertEquals(emptyList<LibraryManga>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observing_does_not_invoke_any_repository_mutator() = runTest {
        val repo = FakeLibraryRepository()
        val useCase = ObserveLibraryUseCase(repo)

        useCase().test {
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(repo.calls.isEmpty(), "observe must be read-only; recorded calls: ${repo.calls}")
    }
}
