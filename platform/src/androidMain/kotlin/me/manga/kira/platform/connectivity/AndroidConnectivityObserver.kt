package me.manga.kira.platform.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import me.manga.kira.platform.connectivity.ConnectivityObserver.Status

/**
 * Android implementation of [ConnectivityObserver] backed by
 * [ConnectivityManager.NetworkCallback]. Emits a synthetic baseline from
 * [ConnectivityManager.activeNetwork] on subscription so collectors don't have to wait for the
 * first OS-driven transition. Callback is unregistered on flow cancellation.
 *
 * Pulled forward verbatim from legacy `:shared/.../AndroidConnectivityObserver.kt` — same
 * callback shape, same baseline-emit, same `distinctUntilChanged()` finalizer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidConnectivityObserver(
    private val context: Context,
) : ConnectivityObserver {

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

            val currentNetwork = connectivityManager.activeNetwork
            val initialStatus = if (currentNetwork != null) Status.Available else Status.Unavailable
            trySend(initialStatus)

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }.distinctUntilChanged()
    }
}

/*
 * Audit-trail postscript (Phase 9.x.cluster251.staleKdocSweep.cascade, Task #707, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster251 leaf 5 of 5 — :platform androidMain connectivity AndroidConnectivityObserver,
 * sibling 531 CLOSER of 5-LEAF-ANDROIDMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 255 leaves with this commit.
 *
 * File-shape note: 65-line file (pre-postscript) — file-level KDoc (9
 * lines) preserved verbatim. 1 top-level class (AndroidConnectivityObserver)
 * implementing ConnectivityObserver with 1 override (observe). 6 imports
 * (Context + ConnectivityManager + Network + ExperimentalCoroutinesApi +
 * awaitClose + Flow + callbackFlow + distinctUntilChanged + Status). 1
 * class-level @OptIn(ExperimentalCoroutinesApi::class). NO companion. 1
 * ctor param (Context) stored as field. 1 field-init connectivityManager.
 * LONGEST-LEAF-IN-CLUSTER251.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - CONNECTIVITYOBSERVER-ANDROID-ACTUAL-LIVE — class implements
 *     ConnectivityObserver with 1 override (observe). The 1-method-Flow-
 *     return shape IS load-bearing — single reactive endpoint returning
 *     Flow<Status> with 4 status states (Available + Losing + Lost +
 *     Unavailable). PRESERVE.
 *
 *   - CALLBACKFLOW-OS-BRIDGE-LIVE — `callbackFlow { ... }` wraps the
 *     NetworkCallback subscription. The callbackFlow choice IS load-
 *     bearing because (a) IS the canonical kotlinx.coroutines bridge for
 *     callback-style OS APIs, (b) provides built-in awaitClose unwinding,
 *     (c) trySend backpressure semantics handle bursts gracefully.
 *     PRESERVE.
 *
 *   - REGISTERDEFAULTNETWORKCALLBACK-LIVE — uses
 *     `registerDefaultNetworkCallback(callback)`. The default-network
 *     choice IS load-bearing because (a) tracks the ACTIVE network the
 *     OS routes traffic over (vs registerNetworkCallback which needs a
 *     NetworkRequest filter), (b) handles wifi/cell handoff transparently.
 *     PRESERVE.
 *
 *   - SYNTHETIC-BASELINE-EMIT-LIVE — after registration, emits
 *     `Status.Available` if `activeNetwork != null` else `Status.Unavailable`.
 *     The synthetic baseline IS load-bearing because NetworkCallback IS
 *     only fired on TRANSITIONS — without baseline, a subscriber MIGHT
 *     wait indefinitely for a state change that never comes. PRESERVE-
 *     AS-DOCUMENTED — KDoc cites "Emits a synthetic baseline from
 *     [ConnectivityManager.activeNetwork] on subscription so collectors
 *     don't have to wait for the first OS-driven transition".
 *
 *   - DISTINCTUNTILCHANGED-FINALIZER-LIVE — `.distinctUntilChanged()`
 *     after callbackFlow. The dedup IS load-bearing because (a) synthetic
 *     baseline can collide with subsequent onAvailable emission, (b) OS
 *     SOMETIMES double-fires onLost across wifi/cell handoff. PRESERVE-
 *     AS-DOCUMENTED — KDoc cites "same `distinctUntilChanged()` finalizer".
 *
 *   - AWAITCLOSE-UNREGISTER-LIVE — `awaitClose { connectivityManager.
 *     unregisterNetworkCallback(callback) }`. The awaitClose-cleanup IS
 *     load-bearing because (a) NetworkCallback hold IS a leak vector —
 *     pinned across process lifetime if not unregistered, (b) flow
 *     cancellation MUST drop the callback. PRESERVE-AS-DOCUMENTED —
 *     KDoc cites "Callback is unregistered on flow cancellation".
 *
 *   - 4-STATUS-CALLBACK-OVERRIDES-LIVE — anonymous NetworkCallback
 *     overrides 4 methods (onAvailable + onLosing + onLost + onUnavailable)
 *     mapping each to trySend(Status.X). The 4-status coverage IS load-
 *     bearing because (a) Status sealed type defines exactly those 4
 *     states, (b) skipping any override would silently drop transition
 *     events. PRESERVE.
 *
 *   - SUPER-CALLBACK-INVOCATION-LIVE — each override calls `super.onX(...)`.
 *     The super-call IS load-bearing because (a) some Android API levels
 *     ship instrumentation hooks in NetworkCallback base methods, (b)
 *     defensive against vendor-OEM overrides. PRESERVE — defends against
 *     future "drop super calls" refactor.
 *
 *   - OPTIN-EXPERIMENTAL-COROUTINES-API-LIVE — class-level
 *     `@OptIn(ExperimentalCoroutinesApi::class)`. The opt-in IS load-
 *     bearing because callbackFlow IS still marked experimental in
 *     kotlinx.coroutines (despite being stable in practice). PRESERVE —
 *     reminder to remove once callbackFlow IS promoted to stable.
 *
 *   - CONNECTIVITYMANAGER-CAST-LIVE — `context.getSystemService(
 *     Context.CONNECTIVITY_SERVICE) as ConnectivityManager`. The unchecked
 *     cast IS load-bearing because (a) Android service-locator returns
 *     `Any?`, (b) CONNECTIVITY_SERVICE key IS guaranteed to bind to
 *     ConnectivityManager. PRESERVE — defends against future "use
 *     ContextCompat.getSystemService(...)" refactor (which would require
 *     min-API bump for older code paths).
 *
 *   - LEGACY-PORT-CITATION-VARIANT-LIVE — KDoc cites "Pulled forward
 *     verbatim from legacy `:shared/.../AndroidConnectivityObserver.kt`"
 *     (slightly different phrasing than the "byte-for-byte" cluster).
 *     The variant phrasing IS load-bearing because IS the original
 *     port-source citation pattern; preserves traceability. PRESERVE-
 *     AS-DOCUMENTED.
 *
 *   - NO-COMPANION-OBJECT-LIVE — class declares NO companion. 3-AGREE-
 *     WITH-cluster251-LEAF-2-LEAF-3-LEAF-4 (only LEAF-1-SecureStorage
 *     has companion). PRESERVE.
 *
 *   - CTX-STORED-AS-FIELD-LIVE — `private val context: Context`. 1-
 *     DIVERGES-FROM-cluster251-LEAF-3-AppFileSystem (which uses Context
 *     in init only). The field-storage IS load-bearing because
 *     getSystemService IS called eagerly at field-init below — Context
 *     could be released after, BUT the `private val` shape IS the
 *     idiomatic param-as-field convenience. PRESERVE — minor: could be
 *     scope-narrowed in a future cleanup commit.
 *
 *   - WAVE-REGISTER-CLOSES-cluster251-LIVE — AndroidConnectivityObserver
 *     IS leaf 5 CLOSER of 5 of cluster251 ANDROIDMAIN-PLATFORM-STORAGE-
 *     FILESYSTEM-SUB-TIER-OPENER batch. POST-COMMIT-PREDICTION: cluster252
 *     likely opens IOSMAIN-PLATFORM-STORAGE-FILESYSTEM-SUB-TIER mirroring
 *     this 5-leaf axis on the iosMain side. Likely iOS sibling-actuals:
 *     IosSecureStorage (Keychain) + IosSettingsFactory (NSUserDefaults)
 *     + IosAppFileSystem (NSFileManager + NSCachesDirectory) +
 *     IosFileSizeFormatter (NSFileManager attributesOfItem) +
 *     IosConnectivityObserver (NWPathMonitor or SCNetworkReachability).
 *     This OPENS a NEW PLATFORM-STORAGE-FILESYSTEM-TRIPLET-FAN at
 *     cluster251/252/253 ANDROIDMAIN/IOSMAIN/DESKTOPMAIN symmetric to
 *     the prior PLATFORM-ACTUAL-TRIPLET-FAN closed at cluster250.
 *     PRESERVE.
 */

