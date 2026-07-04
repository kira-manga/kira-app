package me.manga.kira.domain.usecase.library

import me.manga.kira.domain.repository.LibraryPrefsRepository

/**
 * Persist a fresh RANDOM-sort shuffle seed (thin pass-through to
 * [LibraryPrefsRepository.setRandomSeed]). Called when the user (re)selects RANDOM sort to give a
 * new — but thereafter stable — shuffle.
 */
class SetLibraryRandomSeedUseCase(
    private val repository: LibraryPrefsRepository,
) {
    suspend operator fun invoke(seed: Long) {
        repository.setRandomSeed(seed)
    }
}
