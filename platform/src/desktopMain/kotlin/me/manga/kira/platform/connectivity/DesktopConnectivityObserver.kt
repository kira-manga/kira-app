package me.manga.kira.platform.connectivity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.kira.platform.connectivity.ConnectivityObserver.Status
import java.net.NetworkInterface

/**
 * Desktop (JVM) implementation of [ConnectivityObserver].
 *
 * The JVM has no first-class push API equivalent to Android's `NetworkCallback`. Instead of pinging
 * a remote host (which wrongly reports offline on networks that block that one host), we read the
 * OS's own network state: a poll every [intervalMs] checks whether ANY non-loopback
 * [NetworkInterface] is up with a bound address ([Status.Available]) and reports [Status.Unavailable]
 * only when no usable interface exists. This is local OS state — it never leaks a beacon to a
 * third-party host and never falsely fails on a host-blocked network. `Losing` / `Lost` aren't
 * distinguishable from this coarse state and collapse into `Unavailable`.
 *
 * Each check runs on [Dispatchers.IO] ([NetworkInterface.getNetworkInterfaces] does blocking native
 * calls), then the result is emitted on the callbackFlow's scope.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopConnectivityObserver(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) : ConnectivityObserver {

    override fun observe(): Flow<Status> = callbackFlow {
        val job = launch {
            while (isActive) {
                val status = withContext(Dispatchers.IO) { currentStatus() }
                trySend(status)
                delay(intervalMs)
            }
        }
        awaitClose { job.cancel() }
    }.distinctUntilChanged()

    private fun currentStatus(): Status = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        var available = false
        while (interfaces != null && interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isUp && !iface.isLoopback && iface.inetAddresses.hasMoreElements()) {
                available = true
                break
            }
        }
        if (available) Status.Available else Status.Unavailable
    } catch (_: Throwable) {
        // SocketException can be thrown if the interface list can't be read; treat as offline.
        Status.Unavailable
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 5_000L
    }
}
