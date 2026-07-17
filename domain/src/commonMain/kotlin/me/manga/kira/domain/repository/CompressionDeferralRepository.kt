package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Observes whether downloaded-chapter compression (the CBZ finalize step) is currently DEFERRED
 * specifically because iOS **Low Power Mode** is active and the user has not opted in to compress
 * during Low Power Mode.
 *
 * iOS-only in practice: the iOS background-download engine defers the heavy CBZ encode while Low
 * Power Mode is on (unless the user opts in), leaving finished chapters readable-but-not-archived.
 * The Details screen consumes this (via [me.manga.kira.domain.usecase.downloads.ObserveCompressionDeferredUseCase])
 * to show a clear **"Paused — Low Power Mode"** state on such chapters instead of an endless
 * "Finalizing…". On Android/Desktop there is no such deferral, so the impl emits a constant `false`.
 *
 * DIP port: the concrete implementation lives at the `:composeApp` composition root (it depends on
 * the iOS-only `BackgroundWorkSignal` + the settings toggle), not in `:data` — mirroring how the iOS
 * background-download facades are wired per-target.
 */
interface CompressionDeferralRepository {
    /** Emits `true` while compression is paused by Low Power Mode (user opt-out); `false` otherwise. */
    fun observeLowPowerDeferral(): Flow<Boolean>
}
