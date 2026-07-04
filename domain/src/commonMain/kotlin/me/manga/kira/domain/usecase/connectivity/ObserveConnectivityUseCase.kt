package me.manga.kira.domain.usecase.connectivity

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.ConnectivityRepository

/**
 * Observe whether the device is online (#4) — thin pass-through to
 * [ConnectivityRepository.observeIsOnline].
 *
 * Contract §6 SRP: ONE rule — expose the reachability flow. Contract §6 DIP: depends on the
 * `:domain` interface. Mirrors the [me.manga.kira.domain.usecase.downloads.ObserveDownloadsUseCase]
 * pass-through shape. Bound `factory` (stateless).
 */
class ObserveConnectivityUseCase(
    private val repository: ConnectivityRepository,
) {
    operator fun invoke(): Flow<Boolean> = repository.observeIsOnline()
}
