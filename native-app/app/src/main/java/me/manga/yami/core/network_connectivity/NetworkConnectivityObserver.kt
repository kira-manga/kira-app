package me.manga.yamiapk.core.network_connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver.Status
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class NetworkConnectivityObserver @Inject constructor(
    @ApplicationContext private val context: Context
): ConnectivityObserver {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun observe(): Flow<Status> {
        return callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    trySend(Status.Available)
                }

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    super.onLosing(network, maxMsToLive)
                    trySend(Status.Losing)
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    trySend(Status.Lost)
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    trySend(Status.Unavailable)
                }
            }

            connectivityManager.registerDefaultNetworkCallback(callback)

            // Send current status immediately
            val currentNetwork = connectivityManager.activeNetwork
            val initialStatus = if (currentNetwork != null) Status.Available else Status.Unavailable
            trySend(initialStatus)

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }.distinctUntilChanged()
    }
}