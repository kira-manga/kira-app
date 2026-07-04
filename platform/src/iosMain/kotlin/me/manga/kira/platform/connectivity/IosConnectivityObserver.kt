package me.manga.kira.platform.connectivity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import me.manga.kira.platform.connectivity.ConnectivityObserver.Status
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * iOS implementation of [ConnectivityObserver] backed by the Network framework's `NWPathMonitor`
 * (OS-level reachability), replacing the previous Google `generate_204` HEAD probe.
 *
 * `nw_path_monitor` pushes a [path][platform.Network.nw_path_t] update whenever the OS network
 * state changes; we map `nw_path_status_satisfied` → [Status.Available] and everything else
 * (`unsatisfied` / `requires_connection`) → [Status.Unavailable]. This reports the device's
 * *actual* connectivity — it does NOT depend on any single host being reachable, so users on
 * Google-blocked networks (e.g. some regions / corporate filters) are no longer wrongly reported
 * permanently offline.
 *
 * `Losing` / `Lost` are not distinguished by `nw_path_status` (it has no transition states), so
 * they collapse into `Unavailable`, matching the [ConnectivityObserver] contract for coarse
 * backends.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalForeignApi::class)
class IosConnectivityObserver : ConnectivityObserver {

    override fun observe(): Flow<Status> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            val status = if (nw_path_get_status(path) == nw_path_status_satisfied) {
                Status.Available
            } else {
                Status.Unavailable
            }
            trySend(status)
        }
        // A serial dispatch queue (label/attr null) for the path-update callbacks — the canonical
        // NWPathMonitor setup. trySend hops the result back onto the flow's channel.
        nw_path_monitor_set_queue(monitor, dispatch_queue_create(null, null))
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }.distinctUntilChanged()
}
