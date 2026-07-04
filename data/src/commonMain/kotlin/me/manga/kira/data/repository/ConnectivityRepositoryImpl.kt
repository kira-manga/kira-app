package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.manga.kira.domain.repository.ConnectivityRepository
import me.manga.kira.platform.connectivity.ConnectivityObserver

/**
 * [ConnectivityRepository] strangler-fig delegate over the `:platform` [ConnectivityObserver] (#4).
 *
 * Collapses the platform 4-state enum to the domain boolean: only [ConnectivityObserver.Status.Available]
 * is "online"; Unavailable / Losing / Lost all map to `false`. The observer's actuals already ship and
 * are Koin-bound on all three targets (Android `ConnectivityManager` callback, iOS `NWPathMonitor`,
 * Desktop `NetworkInterface` OS-state poll), so this only adds a read-only consumer.
 */
class ConnectivityRepositoryImpl(
    private val observer: ConnectivityObserver,
) : ConnectivityRepository {

    override fun observeIsOnline(): Flow<Boolean> =
        observer.observe().map { it == ConnectivityObserver.Status.Available }
}
