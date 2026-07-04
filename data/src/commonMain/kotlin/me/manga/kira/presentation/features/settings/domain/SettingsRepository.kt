package me.manga.kira.presentation.features.settings.domain

import kotlinx.coroutines.flow.Flow
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.clearCacheLargerThan
import me.manga.kira.platform.filesystem.folderSize
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import me.manga.kira.presentation.features.settings.data.ONE_MB

/**
 * Migration notes (Phase 8.13 batch B):
 *  - `android.content.Context` + `@ApplicationContext` constructor injection dropped. Cache-clearing
 *    and folder-size helpers now operate on an injected [AppFileSystem] instead of a raw
 *    `java.io.File` argument. Source's signature
 *      `fun clearFilesLargerThan1MB(dir: File)` / `fun getFolderSize(dir: File): Long`
 *    is replaced with no-arg helpers that target the platform cache directory exposed by
 *    [AppFileSystem.cacheDir]. The ViewModel layer (Phase 9) will be updated to call the new
 *    arg-free shapes — see `AppFileSystem.kt` for the underlying okio-based implementations.
 *  - `android.content.res.Configuration.UI_MODE_NIGHT_MASK` fallback inside [isDarkMode]: the
 *    source consulted the device's current UI-mode when no explicit theme pref was stored. That
 *    requires a platform-aware "system dark-mode" provider which does not yet exist in
 *    `:shared/commonMain`. Behavioural change: when the user has never explicitly set a theme,
 *    [isDarkMode] now returns `false` (light theme). A `SystemThemeProvider` expect/actual can be
 *    introduced in a later phase if first-run-system-dark detection is required. Persisted user
 *    preference (after toggling once) round-trips identically.
 *  - `R.string.{kilobytes, megabytes, gigabytes, bytes}` lookups inside the interim `formatSize`
 *    helper → FULFILLED 2026-07 (backlog L15): the helper is deleted; [getCacheFolderSize] hands
 *    raw bytes up the typed wire and `:ui` formats via `formatByteSize(...)` + the localized
 *    `size_*` resource patterns (restoring the native per-locale units, e.g. French `Ko/Mo/Go`).
 *  - `java.io.File.walkTopDown/walkBottomUp` cache-clearing logic migrated into
 *    [AppFileSystem.clearFilesLargerThan] (okio-based, cross-platform).
 *  - Android-only `context.externalCacheDir` IS now cleared too (native parity): [clearFilesLargerThan1MB]
 *    calls [AppFileSystem.clearCacheLargerThan], which sweeps both `cacheDir` and
 *    [AppFileSystem.externalCacheDir] (the latter is non-null only on Android; `null` no-op on
 *    iOS/Desktop). The displayed cache size stays internal-only, matching native.
 *  - Hilt `@Singleton` / `@Inject` / `@ApplicationContext` annotations dropped. Koin will bind this
 *    as `single { … }` in the follow-up SharedModule wiring step.
 */
class SettingsRepository(
    private val prefsHelper: SharedPrefsHelper,
    private val ds: DataStoreHelper,
    private val fs: AppFileSystem,
) {
    val downloadedOnlyFlow = ds.downloadedOnlyFlow
    val incognitoFlow = ds.incognitoFlow
    val readingModeFlow = ds.readingModeFlow
    val languageFlow: Flow<String> = ds.languageFlow

    suspend fun setLanguage(code: String) = ds.setLanguage(code)
    suspend fun setDownloadedOnly(v: Boolean) = ds.setDownloadedOnly(v)
    suspend fun setIncognito(v: Boolean) = ds.setIncognito(v)
    suspend fun setReadingMode(m: String) = ds.setReadingMode(m)

    fun setDarkMode(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_THEME_MODE, enabled)
    }

    fun setPureBlack(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_PURE_BLACK, enabled)
    }

    fun setFollowSystem(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_THEME_SYSTEM, enabled)
    }

    fun isDarkMode(): Boolean {
        // See migration note above: the "no-pref → query system uiMode" fallback used by source
        // requires a platform-aware system-theme provider that hasn't been ported yet. Falls back
        // to false (light) until that provider lands.
        return prefsHelper.getBoolean(StorageKeys.KEY_THEME_MODE, defaultValue = false)
    }

    fun isPureBlack(): Boolean =
        prefsHelper.getBoolean(StorageKeys.KEY_PURE_BLACK, defaultValue = true)

    fun isFollowSystem(): Boolean =
        prefsHelper.getBoolean(StorageKeys.KEY_THEME_SYSTEM, defaultValue = true)

    // Provide Flow<Boolean> for dark mode
    val darkModeFlow: Flow<Boolean> = prefsHelper.booleanPrefFlow(
        key = StorageKeys.KEY_THEME_MODE,
        default = isDarkMode(),
    )

    val followSystemFlow: Flow<Boolean> = prefsHelper.booleanPrefFlow(
        key = StorageKeys.KEY_THEME_SYSTEM,
        default = true, // or true, whatever makes sense for first-run
    )

    val pureBlackFlow: Flow<Boolean> = prefsHelper.booleanPrefFlow(
        key = StorageKeys.KEY_PURE_BLACK,
        default = isPureBlack(),
    )

    /**
     * Source: `fun clearFilesLargerThan1MB(dir: File)` taking an arbitrary directory.
     *
     * Port: [AppFileSystem.clearCacheLargerThan] sweeps the platform [AppFileSystem.cacheDir] and,
     * on Android, [AppFileSystem.externalCacheDir] — restoring native parity (the source cleared
     * both `context.cacheDir` and `context.externalCacheDir`). No-op on the external sweep where
     * the platform has no external cache (iOS/Desktop).
     */
    fun clearFilesLargerThan1MB() {
        fs.clearCacheLargerThan(ONE_MB)
    }

    /**
     * Source: `fun getFolderSize(dir: File): Long` walking an arbitrary directory.
     *
     * Port: defaults to the platform cache directory. The single caller in source
     * (`SettingsViewModel`) passed `context.cacheDir`, so the no-arg shape matches its only use.
     */
    fun getCacheFolderSize(): Long = fs.folderSize(fs.cacheDir)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster208.staleKdocSweep.cascade, Task #664, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster208 leaf 1/5 — :shared/settings/domain/ tier SINGLE-LEAF (single .kt file in subdir),
 * sibling 379. Cumulative §253-postscript count = 104 leaves with this commit (was 103 post-
 * cluster207).
 *
 * File-shape note: 147-line class — `SettingsRepository` with 3 constructor deps (prefsHelper:
 * SharedPrefsHelper, ds: DataStoreHelper, fs: AppFileSystem). Surfaces 4 reactive Flow properties
 * (downloadedOnly + incognito + readingMode + language) with paired setters; theme triplet
 * (darkMode + pureBlack + followSystem) with getter+setter+Flow trios; cache-clear/cache-size
 * helpers via fs; locale-independent formatSize bytes-to-human-readable formatter. Heavy class-
 * level KDoc block (lines 12-40) carries Phase 8.13 batch B migration prose covering Context-
 * drop, AppFileSystem-shift, R.string→literal substitution, and Hilt→Koin DI swap.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed settings-cell SOURCE — direct consumers (verified via
 *     15-hit FQN grep with receiver-anchored `settingsRepository.X` reaches):
 *       1. SettingsViewModel.kt (:shared/.../settings/ui/viewmodel/) — legacy settings screen VM
 *          consumes the full surface (theme triplet + reading-mode + language + cache-clear +
 *          cache-size + formatSize).
 *       2. HistoryViewModel.kt (:shared/.../history/ui/viewmodel/) — reads readingModeFlow +
 *          downloadedOnlyFlow for the per-row download-only filter and reader-mode routing.
 *       3. ChaptersViewModel.kt (:shared/.../details/ui/viewmodel/) — reads downloadedOnlyFlow
 *          for the chapter-list filter toggle.
 *       4. ReaderViewModel.kt (:shared/.../reader/ui/viewmodel/) — reads readingModeFlow +
 *          incognitoFlow for the reader-page lifecycle.
 *
 *   • INVERTED-PARALLEL-PARTIAL — rework counterpart at :domain/repository/SettingsRepository.kt
 *     + :data SettingsRepositoryImpl reaches a SUBSET of this surface via dedicated use cases
 *     (ObserveLanguageUseCase, ObserveReadingModeUseCase, ToggleIncognitoUseCase,
 *     ClearCacheUseCase). The rework LIFTS individual settings cells to per-cell use cases per
 *     ISP; the legacy unified facade stays alive because the LEGACY settings screen has not yet
 *     been swapped to the rework. Will retire alongside the future legacy-settings-screen swap
 *     campaign — currently unscheduled.
 *
 *   • KDOC-MIGRATION-NOTES-LOAD-BEARING — the 28-line class-level KDoc (lines 12-40) is a Phase
 *     8.13 batch B migration record. PRESERVE during cleanup passes — load-bearing port-lineage
 *     prose with TWO Phase 10 forward-work pointers (SystemThemeProvider expect/actual for
 *     first-run-system-dark detection + stringResource rewire for formatSize localisation). Per
 *     §253.
 *
 *   • ISDARKMODE-OBSERVABLE-CHANGE-PINNED — the line-comment at lines 69-71 documents a
 *     deliberate first-run behavioural change (no-pref → false instead of source-app's "query
 *     system uiMode" path). DO NOT collapse to the bare getBoolean call during cleanup — the
 *     comment is the contract-difference record between source-app and KMP-port, surfaced to
 *     future readers as a deliberate-not-bug record.
 *
 *   • ONE_MB-CROSS-LEAF-COUPLING — line 10 imports `ONE_MB` from sibling 378 (settings/data/
 *     ONE_MB.kt, cluster207). The clearFilesLargerThan1MB() method at line 103-105 is the
 *     constant's SOLE consumer. SHADOW-ORPHAN coupling: if SettingsRepository is retired in the
 *     future legacy-settings-screen swap, ONE_MB.kt (cluster207 sibling 378) becomes coupled-
 *     orphan and should be retired in the same slice — forward-work pointer registered on the
 *     ONE_MB postscript.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 7 imports: kotlinx.coroutines.flow.Flow + 3 core.files
 *     (AppFileSystem + clearCacheLargerThan + folderSize) + 3 core.storage (DataStoreHelper +
 *     SharedPrefsHelper + StorageKeys) + 1 sibling-378 ONE_MB. All LIVE.
 */
