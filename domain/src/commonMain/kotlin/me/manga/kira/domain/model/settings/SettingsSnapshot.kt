package me.manga.kira.domain.model.settings

/**
 * Bundle value type — the 8 observable fields of the user-side Settings screen, as a single
 * coherent snapshot emitted by [me.manga.kira.domain.usecase.settings.ObserveSettingsUseCase].
 *
 * Phase 7.x.settings.foundation rework.
 *
 * **Why a bundle vs separate flows?** The legacy `SettingsViewModel` exposes independent
 * `StateFlow<*>`s, which the legacy composable collects one-by-one via `collectAsState()`. That
 * works but creates recomposition pressure (each pref change triggers an independent re-render
 * scope). The rework combines all 8 into one `Flow<SettingsSnapshot>` via `combine(...)` in the
 * `:data` impl — exactly one emission per pref change (no matter which one), exactly one state
 * update in the VM, exactly one recomposition of the `SettingsScreen`. Same data, fewer recomposes.
 *
 * **Why `cacheSize: String` not `cacheSize: Long`?** The legacy facade pre-formats the cache size
 * via locale-independent KB/MB/GB rounding (`formatSize(bytes: Long): String`). Mirrors the
 * Phase 7.x.statistics rework's `readDuration: String` posture — defer the i18n lift to Phase 10,
 * keep the formatting on the legacy side until then.
 *
 * **Field ordering**: General toggles first (the 2 DataStore-backed prefs the user toggles most
 * often), then theme toggles (3 SharedPrefs-backed), then cache (1 derived).
 *
 * Contract §6 SRP: ONE rule — "the projection of one Settings screen at one instant". No
 * mutations, no derivations, no logic. The `cacheSize` field is the only non-toggle; it's
 * grouped here because it shares the screen's lifecycle (refreshes on the same observe-stream).
 *
 * Contract §6 DIP: pure `:domain` value type. No `:data` / `:shared` reach. The `:data` impl
 * maps from 7 legacy `SharedPrefs`/`DataStore` toggle flows + 1 derived cache-size flow into this
 * snapshot.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster137.staleKdocSweep.cascade,
 * Task #593, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-ninth sibling of the cluster57-136
 * sweep — fifth and closing file of the wave-24 fifth-cluster 5-
 * subpackage joint batch alongside AppMetadata plus ComplaintSummary
 * plus HistoryEntry plus Language; postscript covers both types in
 * this file — SettingsSnapshot primary data class plus SettingsToggle
 * enum; CLOSES cluster137):
 *  (a) "Phase-7.x.settings.foundation-rework + Bundle-value-type-the-
 *  6-observable-fields-of-the-user-side-Settings-screen-as-a-single-
 *  coherent-snapshot-emitted-by-ObserveSettingsUseCase + Why-a-bundle-
 *  vs-6-separate-flows + The-legacy-SettingsViewModel-exposes-6-
 *  independent-StateFlow-which-the-legacy-composable-collects-6-times-
 *  via-collectAsState + That-works-but-creates-recomposition-pressure-
 *  each-pref-change-triggers-an-independent-re-render-scope + The-
 *  rework-combines-all-6-into-one-Flow-SettingsSnapshot-via-combine-in-
 *  the-:data-impl + exactly-one-emission-per-pref-change-no-matter-
 *  which-one-exactly-one-state-update-in-the-VM-exactly-one-
 *  recomposition-of-the-SettingsScreen + Same-data-fewer-recomposes" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via recursive
 *  grep: SettingsSnapshot is consumed by ObserveSettingsUseCase plus
 *  SettingsRepositoryImpl plus SettingsState plus SettingsViewModel
 *  plus SettingsScreen. SettingsRepositoryImpl.kt builds the snapshot
 *  via kotlinx.coroutines.flow.combine across 6 source flows (down-
 *  loadedOnly + incognito + followSystemTheme + darkMode + pureBlack +
 *  cacheSize) — exactly one emission per pref change as predicted.
 *  (b) "Why-cacheSize-String-not-cacheSize-Long + The-legacy-facade-
 *  pre-formats-the-cache-size-via-locale-independent-KB-MB-GB-rounding-
 *  formatSize-bytes-Long-String + Mirrors-the-Phase-7.x.statistics-
 *  rework-readDuration-String-posture + defer-the-i18n-lift-to-Phase-
 *  10-keep-the-formatting-on-the-legacy-side-until-then + Field-
 *  ordering-General-toggles-first-the-2-DataStore-backed-prefs-the-
 *  user-toggles-most-often-then-theme-toggles-3-SharedPrefs-backed-
 *  then-cache-1-derived + Contract-§6-SRP-ONE-rule-the-projection-of-
 *  one-Settings-screen-at-one-instant + cacheSize-field-is-the-only-
 *  non-toggle-grouped-here-because-it-shares-the-screen-lifecycle" —
 *  LIVE-NOT-STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-
 *  FULFILLED-(Phase-10-i18n-cacheSize-lift). Verified: cacheSize field
 *  is declared as String (pre-formatted), matching ReadingStatistics.
 *  readDuration: String per the cross-reference. Phase 10 Compose
 *  Multiplatform Resources stringResource lift remains forecast — no
 *  Res.string.cache_size_* key exists yet. Field ordering in the
 *  data class declaration matches the predicted "2 general toggles +
 *  3 theme toggles + 1 cache derived" sequence verbatim.
 *  (c) "SettingsToggle-Identifies-which-of-the-5-toggle-fields-an-
 *  UpdateSettingsToggleUseCase-call-mutates + Exhaustively-maps-to-the-
 *  SettingsSnapshot-5-boolean-fields-excluding-cacheSize-that-is-not-
 *  a-toggle + Variant-order-matches-SettingsSnapshot-field-order-2-
 *  general-toggles-first-then-3-theme-toggles + Makes-a-when-on-the-
 *  enum-read-the-snapshot-fields-in-the-same-order + Contract-§6-OCP-
 *  adding-a-6th-toggle-NOTIFICATION_SOUND-is-a-new-variant + :data-
 *  impl-exhaustive-when-mapper-flags-missing-variant-handlers-at-
 *  compile-time" — LIVE-NOT-STALE plus FULFILLED-PREDICTION plus
 *  FORECAST-NOT-YET-FULFILLED-(NOTIFICATION_SOUND-or-other-6th-toggle-
 *  extension). Verified: SettingsToggle enum declares exactly 5
 *  variants (DOWNLOADED_ONLY + INCOGNITO + FOLLOW_SYSTEM_THEME +
 *  DARK_MODE + PURE_BLACK) in the predicted field-order-matching
 *  sequence. UpdateSettingsToggleUseCase.kt does an exhaustive when()
 *  branch across all 5 enum variants — adding a 6th variant would
 *  surface as an unhandled-when compile error in :data, locking OCP
 *  posture in place. No 6th toggle has landed.
 *  Three classifications STAND on their own merits. CLOSES :domain/
 *  model/settings/ subpackage at 1/1 and closes cluster137. Original
 *  Phase 7.x.settings.foundation-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
data class SettingsSnapshot(
    val downloadedOnly: Boolean,
    val incognito: Boolean,
    val followSystemTheme: Boolean,
    val darkMode: Boolean,
    val pureBlack: Boolean,
    /**
     * Chapter-cache directory size in RAW bytes (typed wire, 2026-07 backlog L15): `:ui` renders
     * it through the localized `size_*` unit patterns. Domain carries data, never formatted text.
     */
    val cacheSizeBytes: Long,
    // Phase 7.x.settings.cbz — restored "Kira Compressor" download-settings section.
    // `useCbzFormat` defaults `true`, `autoConvertToCbz` defaults `false` (legacy
    // DataStoreHelper KEY_USE_CBZ_FORMAT / KEY_AUTO_CONVERT_TO_CBZ defaults — see DataStoreHelper).
    val useCbzFormat: Boolean,
    val autoConvertToCbz: Boolean,
)

/**
 * Identifies which of the 7 toggle fields a [me.manga.kira.domain.usecase.settings.
 * UpdateSettingsToggleUseCase] call mutates. Exhaustively maps to the [SettingsSnapshot]'s 7
 * boolean fields (excluding `cacheSizeBytes` — that's not a toggle).
 *
 * Variant order matches [SettingsSnapshot]'s field order: 2 general toggles first, then 3 theme
 * toggles, then the 2 CBZ download toggles. Makes a `when` on the enum read the snapshot fields in
 * the same order.
 *
 * Contract §6 OCP: adding a further toggle (e.g., `NOTIFICATION_SOUND`) is a new variant; the
 * `:data` impl's exhaustive `when` mapper flags missing variant handlers at compile time.
 */
enum class SettingsToggle {
    DOWNLOADED_ONLY,
    INCOGNITO,
    FOLLOW_SYSTEM_THEME,
    DARK_MODE,
    PURE_BLACK,
    // Phase 7.x.settings.cbz — "Kira Compressor" download-settings toggles. Persist to the legacy
    // DataStoreHelper KEY_USE_CBZ_FORMAT / KEY_AUTO_CONVERT_TO_CBZ cells via the :data impl.
    USE_CBZ_FORMAT,
    AUTO_CONVERT_TO_CBZ,
}
