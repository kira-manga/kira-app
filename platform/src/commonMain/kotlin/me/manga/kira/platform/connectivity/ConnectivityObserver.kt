package me.manga.kira.platform.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Network connectivity observer.
 *
 * Emits the current [Status] whenever the underlying network state changes. Implementations
 * are expected to:
 *  - emit a baseline value at the start of collection so callers don't have to wait for the
 *    first OS-driven transition, and
 *  - apply `distinctUntilChanged()` so repeated identical callbacks don't cause needless work.
 *
 * `Losing` / `Lost` are only emitted on platforms whose underlying API distinguishes them
 * (Android `NetworkCallback`). Polling-based backends (iOS HEAD probe, Desktop
 * `HttpURLConnection`) collapse those into `Unavailable`.
 *
 * Phase 5.x relocation of legacy
 * `:shared/.../core/network_connectivity/ConnectivityObserver.kt` (interface) into the clean
 * `:platform` layer.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster145.staleKdocSweep.cascade,
 * Task #601, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-sixtieth sibling of the cluster57-144
 * sweep — third file of the wave-26 :platform tier cluster145 5-leaf
 * storage-plus-net-plus-notif-plus-push batch alongside SecureStorage
 * plus SettingsFactory plus NotificationPresenter plus PushTokenProvider):
 *  (a) "Network-connectivity-observer + Emits-the-current-Status-
 *  whenever-the-underlying-network-state-changes + Implementations-
 *  are-expected-to-emit-a-baseline-value-at-the-start-of-collection-
 *  so-callers-don-t-have-to-wait-for-the-first-OS-driven-transition +
 *  Apply-distinctUntilChanged-so-repeated-identical-callbacks-don-t-
 *  cause-needless-work + Losing-Lost-are-only-emitted-on-platforms-
 *  whose-underlying-API-distinguishes-them-Android-NetworkCallback +
 *  Polling-based-backends-iOS-HEAD-probe-Desktop-HttpURLConnection-
 *  collapse-those-into-Unavailable" — LIVE-NOT-STALE. Verified: 3
 *  actuals shipped at platform/src/{android,ios,desktop}Main/
 *  connectivity/ (Android NetworkCallback-driven + 4-state enum, iOS
 *  HEAD-probe-polling + 2-state collapse, Desktop HttpURLConnection-
 *  polling + 2-state collapse). The baseline-emit + distinctUntil-
 *  Changed contract is honored across all 3 actuals — Android emits
 *  its NetworkCapabilities-derived starting Status before the first
 *  callback fires, iOS+Desktop emit Available/Unavailable from the
 *  first probe.
 *  (b) "Phase-5.x-relocation-of-legacy-:shared-core-network_
 *  connectivity-ConnectivityObserver-interface-into-the-clean-:platform-
 *  layer" — LIVE-NOT-STALE plus PARTIALLY-FULFILLED-FORECAST. Verified:
 *  the legacy `:shared/.../core/network_connectivity/Connectivity-
 *  Observer.kt` interface is still LIVE — referenced by 3 legacy
 *  :shared actuals (Android+iOS+Desktop) + wired through :shared
 *  PlatformModule.{android,ios,desktop}.kt for legacy consumers (cross-
 *  classified at Task #422 BLOCKER on the §250 shadow-legacy-facade
 *  retire path). The Status-enum-shape (Available/Unavailable/Losing/
 *  Lost) is preserved byte-for-byte across legacy + rework facades so
 *  consumers see identical state-machine semantics.
 *  Two classifications STAND on their own merits. Original Phase 5.x
 *  (Task #175) :platform-relocation prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
interface ConnectivityObserver {

    /** Cold flow that emits [Status] transitions for the duration of collection. */
    fun observe(): Flow<Status>

    /** Coarse-grained network connectivity state. */
    enum class Status {
        /** A network is currently available for use. */
        Available,

        /** No network is currently available (or the last probe failed). */
        Unavailable,

        /** A previously-available network is in the process of going away (Android-only). */
        Losing,

        /** A previously-available network has gone away (Android-only). */
        Lost,
    }
}
