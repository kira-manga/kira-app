package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.connectivity.ConnectivityObserver
import me.manga.kira.platform.connectivity.ConnectivityObserver.Status
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #4 — [ConnectivityRepositoryImpl] collapses the platform 4-state [Status] enum to the domain
 * boolean: only [Status.Available] is online; Unavailable / Losing / Lost map to `false`.
 */
class ConnectivityRepositoryImplTest {

    private class FakeConnectivityObserver(private val statuses: List<Status>) : ConnectivityObserver {
        override fun observe(): Flow<Status> = flowOf(*statuses.toTypedArray())
    }

    @Test
    fun mapsAvailableToTrue_everythingElseToFalse() = runTest {
        val repo = ConnectivityRepositoryImpl(
            FakeConnectivityObserver(
                listOf(Status.Available, Status.Unavailable, Status.Losing, Status.Lost),
            ),
        )
        assertEquals(
            listOf(true, false, false, false),
            repo.observeIsOnline().toList(),
        )
    }
}
