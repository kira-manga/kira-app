package me.manga.kira.domain.usecase.library

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Observe the persisted RANDOM-sort shuffle seed (thin pass-through to
 * [LibraryPrefsRepository.observeRandomSeed]). The Library VM seeds `LibraryState.randomSeed` from
 * this so a RANDOM-sorted grid keeps a stable order across re-emissions and restarts.
 */
class ObserveLibraryRandomSeedUseCase(
    private val repository: LibraryPrefsRepository,
) {
    operator fun invoke(): Flow<Long> = repository.observeRandomSeed()
}
