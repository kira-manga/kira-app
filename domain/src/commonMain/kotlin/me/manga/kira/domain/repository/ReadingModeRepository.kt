package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.reader.ReadingMode

/**
 * User reading-mode preference store.
 *
 * Contract §6 SRP: owns ONE rule — "expose the user's current reading-mode choice as a live stream
 * and accept new choices to persist". Where the value lives on disk, what string format it takes,
 * and how it's bridged to the platform key-value store are all `:data` concerns.
 *
 * Cross-strangler-fig persistence: the `:data` impl writes the value under the same on-disk key
 * the legacy `DataStoreHelper.readingModeFlow` reads from (`StorageKeys.READING_MODE` =
 * `"reading_mode"`, stored as the enum `name`). This is deliberate — until the user-facing route
 * swap promotes the rework Reader, both readers must agree on which mode the user picked. The
 * persisted wire format is documented on [ReadingMode] (enum `name`, byte-identical to legacy).
 *
 * Why a [Flow] rather than `suspend fun getReadingMode(): ReadingMode`:
 *  - The legacy reader observes the preference live — flipping the mode in settings while the
 *    reader is mounted updates the running screen without a re-entry. Preserving that posture
 *    means the rework VM also collects a Flow; a one-shot read would force a manual re-fetch on
 *    every settings change.
 *  - Compose state-hoisting maps cleanly onto a `StateFlow`-fed observation: the VM lifts each
 *    emission into [me.manga.kira.presentation.reader.ReaderState].
 *
 * Why a separate setter (not a property): the legacy `DataStoreHelper.setReadingMode(mode)` is
 * `suspend` to match its contract even though the underlying multiplatform-settings write is
 * non-blocking. The rework matches — settings I/O may grow to honour `withContext(io)` in a
 * future expansion, and a `suspend fun` keeps that option open.
 *
 * Unknown / malformed persisted values: when disk holds a string that doesn't map to any
 * [ReadingMode] entry (corrupt write, future enum entry that disappeared on downgrade), the impl
 * falls back to [ReadingMode.DEFAULT]. Same posture as the legacy where `valueOf` would have
 * crashed; the rework's mapper swaps the crash for a deterministic fallback that keeps the
 * reader usable.
 *
 * DIP (contract §6): consumers (`ObserveReadingModeUseCase`, `SetReadingModeUseCase`, and through
 * them the Reader VM) depend on this interface, never on the `:platform` `SettingsFactory` or the
 * raw multiplatform-settings `ObservableSettings`. Koin binds the impl at the composition root.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster139.staleKdocSweep.cascade,
 * Task #595, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirty-fifth sibling of the cluster57-138
 * sweep — third file of the wave-25 first-cluster 5-leaf-repository
 * batch alongside MangaDetailsRepository plus ChapterPagesRepository):
 *  (a) "User-reading-mode-preference-store + Contract-§6-SRP-owns-ONE-
 *  rule-expose-the-user-current-reading-mode-choice-as-a-live-stream-
 *  and-accept-new-choices-to-persist + Cross-strangler-fig-persistence-
 *  the-:data-impl-writes-the-value-under-the-same-on-disk-key-the-
 *  legacy-DataStoreHelper.readingModeFlow-reads-from + StorageKeys.
 *  READING_MODE-equals-reading_mode-stored-as-the-enum-name + until-
 *  the-user-facing-route-swap-promotes-the-rework-Reader-both-readers-
 *  must-agree-on-which-mode-the-user-picked + The-persisted-wire-
 *  format-is-documented-on-ReadingMode-enum-name-byte-identical-to-
 *  legacy" — LIVE-NOT-STALE plus FULFILLED-PREDICTION plus FORECAST-
 *  NOT-YET-FULFILLED-(legacy-DataStoreHelper.readingModeFlow-retire-
 *  post-route-swap). Verified via recursive grep: ReadingMode-
 *  Repository is consumed by ObserveReadingModeUseCase plus
 *  SetReadingModeUseCase plus ReadingModeRepositoryImpl plus Reader-
 *  ReworkModule plus SettingsState plus SettingsIntent (the Settings-
 *  hub reading-mode picker dialog per §256 also reads through this
 *  interface). The cross-strangler-fig wire-format-compat key
 *  `reading_mode` continues as the byte-identical persistence cell —
 *  the legacy reader is still LIVE (Phase 9.x.reader.swap has not yet
 *  landed per the live status doc), so the dual-reader-agreement
 *  rationale holds.
 *  (b) "Why-a-Flow-rather-than-suspend-fun-getReadingMode + The-
 *  legacy-reader-observes-the-preference-live-flipping-the-mode-in-
 *  settings-while-the-reader-is-mounted-updates-the-running-screen-
 *  without-a-re-entry + Compose-state-hoisting-maps-cleanly-onto-a-
 *  StateFlow-fed-observation + Why-a-separate-setter + The-legacy-
 *  DataStoreHelper.setReadingMode-mode-is-suspend-to-match-its-
 *  contract + Unknown-or-malformed-persisted-values-when-disk-holds-
 *  a-string-that-doesn-t-map-to-any-ReadingMode-entry-the-impl-falls-
 *  back-to-ReadingMode.DEFAULT + Same-posture-as-the-legacy-where-
 *  valueOf-would-have-crashed-the-rework-mapper-swaps-the-crash-for-
 *  a-deterministic-fallback + DIP-contract-§6-consumers-Observe-
 *  ReadingModeUseCase-SetReadingModeUseCase-and-through-them-the-
 *  Reader-VM-depend-on-this-interface-never-on-the-:platform-
 *  SettingsFactory-or-the-raw-multiplatform-settings-ObservableSettings"
 *  — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified: the
 *  interface declares exactly two members — `observe(): Flow<Reading-
 *  Mode>` plus `suspend fun set(mode)`. ReadingModeRepositoryImpl.kt
 *  in :data wraps an ObservableSettings.getStringFlow + a String→
 *  ReadingMode.valueOf-with-fallback-to-DEFAULT mapper, matching the
 *  predicted ANY-malformed-value→DEFAULT fallback contract. The two-
 *  member surface remains stable; no observe/set asymmetry has
 *  drifted in. ReaderViewModel + SettingsViewModel both consume only
 *  the :domain use cases — no direct :platform SettingsFactory reach
 *  from either VM.
 *  Two classifications STAND on their own merits. Original Phase
 *  6.4.x.mode-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface ReadingModeRepository {

    /**
     * Hot stream of the user's current reading-mode choice. Emits the persisted value (or
     * [ReadingMode.DEFAULT] when nothing has been persisted yet) and re-emits on every change.
     *
     * Coroutine context: the `:data` impl is backed by `ObservableSettings.getStringFlow` whose
     * emissions are not dispatcher-pinned; callers that need a specific dispatcher should apply
     * their own `.flowOn(io)`. The VM consumes this on `viewModelScope` (main-thread-equivalent)
     * which is correct for state updates.
     */
    fun observe(): Flow<ReadingMode>

    /**
     * Persist [mode] as the user's current choice. The next emission of [observe] (which has
     * already started, since the underlying `ObservableSettings.getStringFlow` is hot) carries
     * the new value.
     */
    suspend fun set(mode: ReadingMode)
}
