package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Observe coarse-grained network reachability as a domain-appropriate boolean (#4).
 *
 * The `:platform` `ConnectivityObserver` exposes a 4-state enum (Available / Unavailable / Losing /
 * Lost); that platform detail is collapsed here to "is the device online" so `:presentation` gates
 * (e.g. blocking a chapter-download enqueue while offline) depend only on a plain `Boolean`. The
 * `:data` impl is a thin map over the platform observer.
 *
 * Cold flow: emits the current reachability on collection and re-emits on every transition for the
 * duration of collection. A consumer that defaults to "online" before the first emission keeps the
 * optimistic, no-regression posture (absence of a signal never blocks an action).
 */
interface ConnectivityRepository {
    /** Emits `true` while a network is available, `false` otherwise. */
    fun observeIsOnline(): Flow<Boolean>
}
