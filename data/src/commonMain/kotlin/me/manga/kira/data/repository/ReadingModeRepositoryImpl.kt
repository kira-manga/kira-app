package me.manga.kira.data.repository

import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.coroutines.getStringFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.manga.kira.domain.model.reader.ReadingMode
import me.manga.kira.domain.repository.ReadingModeRepository

/**
 * [ReadingModeRepository] backed by the platform's [ObservableSettings] store.
 *
 * SRP (contract §6): owns ONE rule — "translate between the on-disk `String` representation of the
 * reading-mode preference and the typed [ReadingMode] enum, exposing the live stream + setter
 * surface that [ReadingModeRepository] declares".
 *
 * DIP: depends on [ReadingModeRepository] (`:domain`) and [ObservableSettings] (multiplatform-settings,
 * already bound as a `single` by the legacy `PlatformModule.android.kt` / `.ios.kt` / `.desktop.kt`
 * via `SettingsFactory.createObservable("kira_settings")`). The rework explicitly re-uses that
 * binding — strangler-fig posture — so the legacy `DataStoreHelper.readingModeFlow` and the rework
 * `observe()` read the exact same disk cell.
 *
 * Wire-format compatibility (preserves the legacy persisted value):
 *  - Key on disk: literal `"reading_mode"` — identical to legacy `StorageKeys.READING_MODE`. The
 *    constant is duplicated locally as [READING_MODE_KEY] rather than imported from the legacy
 *    `:shared` `StorageKeys` because the rework `:data` layer is forbidden from depending on
 *    legacy presentation/storage helpers (contract §6 DIP; layer boundary). The duplication is
 *    deliberate and load-bearing — if either side ever changes its key, users lose their
 *    preference across the strangler-fig transition. KDoc on both sides documents the invariant.
 *  - Value on disk: the enum `name` — same as legacy `DataStoreHelper.setReadingMode(mode)` which
 *    accepts a `String` and the legacy reader writes `ReadingMode.<X>.name`.
 *  - Default when unset: literal `"DEFAULT"` — mapped to [ReadingMode.DEFAULT].
 *
 * Unknown / malformed disk value (lines up with the [ReadingModeRepository] KDoc):
 *  - The legacy reader called `ReadingMode.valueOf(stringFromDisk)` which throws on unknown
 *    names. That posture would crash the rework's collector and tear down the VM. The rework
 *    swaps the crash for a silent fallback to [ReadingMode.DEFAULT] via [toReadingMode] —
 *    `entries.firstOrNull { it.name == raw }` — so a corrupt write or a removed-in-downgrade enum
 *    entry doesn't make the reader unusable. The fallback never writes back to disk: the next
 *    explicit user pick will overwrite the bad value normally.
 *
 * Why not [`flowOn(io)`]: `ObservableSettings.getStringFlow` emissions are not dispatcher-pinned —
 * the multiplatform-settings impl reads from in-memory state (SharedPreferences / NSUserDefaults
 * / java.util.prefs.Preferences are all in-memory snapshots after first read). The `.map` we
 * apply is pure CPU. Pinning to `io` would add a hop with no benefit. Same posture as legacy
 * `DataStoreHelper.readingModeFlow` (no `flowOn`).
 *
 * Why the setter is `suspend` despite a non-blocking platform write: contract parity with
 * [ReadingModeRepository.set] KDoc — keeps the door open for a future `withContext(io)` switch
 * (e.g. if a future settings backend grows synchronous I/O on Desktop). Today this is a direct
 * `putString` call which returns immediately.
 *
 * No-op write protection: a setter that writes the same value the store already holds would not
 * change disk state (`ObservableSettings.putString` short-circuits internally when the value is
 * unchanged) but **also wouldn't trigger an emission** — multiplatform-settings 1.3.0's
 * `getStringFlow` only re-emits when the underlying value actually changes. That's the behaviour
 * we want: a no-op `set(currentMode)` from the UI does not churn the VM. No explicit guard
 * needed here.
 */
@OptIn(ExperimentalSettingsApi::class)
class ReadingModeRepositoryImpl(
    private val settings: ObservableSettings,
) : ReadingModeRepository {

    override fun observe(): Flow<ReadingMode> =
        settings.getStringFlow(READING_MODE_KEY, defaultValue = DEFAULT_MODE_NAME)
            .map { it.toReadingMode() }

    override suspend fun set(mode: ReadingMode) {
        settings.putString(READING_MODE_KEY, mode.name)
    }

    private fun String.toReadingMode(): ReadingMode =
        ReadingMode.entries.firstOrNull { it.name == this } ?: ReadingMode.DEFAULT

    private companion object {
        const val READING_MODE_KEY = "reading_mode"
        const val DEFAULT_MODE_NAME = "DEFAULT"
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster152.staleKdocSweep.cascade,
 * Task #608, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-ninety-first sibling of the cluster57-151
 * sweep — second file of the wave-26 :data/repository reader-state tier
 * 5-leaf batch alongside ChapterPagesRepositoryImpl plus ReadingSession
 * RepositoryImpl plus ReadProgressRepositoryImpl plus PageProgressRepository
 * Impl):
 *  (a) "ReadingModeRepository-backed-by-the-platform-s-ObservableSettings-
 *  store + SRP-contract-section-6-owns-ONE-rule-translate-between-the-on-
 *  disk-String-representation-of-the-reading-mode-preference-and-the-typed-
 *  ReadingMode-enum-exposing-the-live-stream-plus-setter-surface + DIP-
 *  depends-on-ReadingModeRepository-:domain-and-ObservableSettings-multi
 *  platform-settings-already-bound-as-a-single-by-the-legacy-PlatformModule
 *  -:android-:ios-:desktop-via-SettingsFactory.createObservable-kira_settings
 *  + The-rework-explicitly-re-uses-that-binding-strangler-fig-posture-so-
 *  the-legacy-DataStoreHelper.readingModeFlow-and-the-rework-observe-read-
 *  the-exact-same-disk-cell + Wire-format-compatibility-preserves-the-legacy
 *  -persisted-value-Key-on-disk-literal-reading_mode-identical-to-legacy-
 *  StorageKeys.READING_MODE + The-constant-is-duplicated-locally-as-READING
 *  _MODE_KEY-rather-than-imported-from-the-legacy-:shared-StorageKeys-
 *  because-the-rework-:data-layer-is-forbidden-from-depending-on-legacy-
 *  presentation-storage-helpers-contract-section-6-DIP-layer-boundary + The
 *  -duplication-is-deliberate-and-load-bearing-if-either-side-ever-changes-
 *  its-key-users-lose-their-preference-across-the-strangler-fig-transition
 *  + Value-on-disk-the-enum-name + Default-when-unset-literal-DEFAULT-mapped
 *  -to-ReadingMode.DEFAULT + Unknown-malformed-disk-value-The-legacy-reader
 *  -called-ReadingMode.valueOf-stringFromDisk-which-throws-on-unknown-names
 *  + The-rework-swaps-the-crash-for-a-silent-fallback-to-ReadingMode.DEFAULT
 *  -via-toReadingMode-entries.firstOrNull-it.name-raw + The-fallback-never-
 *  writes-back-to-disk-the-next-explicit-user-pick-will-overwrite-the-bad-
 *  value-normally + Why-not-flowOn-io-ObservableSettings.getStringFlow-
 *  emissions-are-not-dispatcher-pinned-the-multiplatform-settings-impl-
 *  reads-from-in-memory-state + Why-the-setter-is-suspend-despite-a-non-
 *  blocking-platform-write-contract-parity-with-ReadingModeRepository.set-
 *  KDoc-keeps-the-door-open-for-a-future-withContext-io-switch + No-op-
 *  write-protection-ObservableSettings.putString-short-circuits-internally
 *  -when-the-value-is-unchanged-also-would-not-trigger-an-emission-no-
 *  explicit-guard-needed-here" — LIVE-NOT-STALE. Verified: ObservableSettings
 *  -backed reading-mode cell shipped. observe() returns settings.getString
 *  Flow(READING_MODE_KEY, defaultValue = DEFAULT_MODE_NAME).map { it.to
 *  ReadingMode() }; set(mode) writes settings.putString(READING_MODE_KEY,
 *  mode.name). The wire-format-compatibility stance is honored — READING
 *  _MODE_KEY = "reading_mode" string-literal-identical to the legacy
 *  StorageKeys.READING_MODE constant. The crash-swap fallback is honored —
 *  toReadingMode() uses ReadingMode.entries.firstOrNull { it.name == this }
 *  ?: ReadingMode.DEFAULT, so a corrupt write or removed-in-downgrade enum
 *  entry returns DEFAULT silently rather than throwing IllegalArgument
 *  Exception. The "no flowOn(io)" stance honored — observe() applies only
 *  a pure CPU .map projection. The suspend setter posture honored —
 *  override suspend fun set is declared suspend despite the underlying
 *  putString being non-blocking, keeping the door open for a future
 *  withContext(io) switch without contract breakage. ExperimentalSettings
 *  Api opt-in via @OptIn at class scope honored. Consumed by ObserveReading
 *  ModeUseCase + SetReadingModeUseCase (cluster93 sibling X) via the
 *  observe() / set() surface; the rework Reader VM consumes through the
 *  use cases at its own MVI boundary. One classification. Original Phase
 *  6.4.x.mode (Task #238) impl prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */

