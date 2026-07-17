package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.model.settings.SettingsSnapshot
import me.manga.kira.domain.model.settings.SettingsToggle

/**
 * Reactive Settings access for the Settings hub screen rework.
 *
 * Phase 7.x.settings.foundation rework. Sibling (NOT extension) of:
 *  - The legacy `:shared` `me.manga.kira.presentation.features.settings.domain.
 *    SettingsRepository` (different package — no compile clash). The legacy fuses 17 surfaces
 *    (toggle flows + setters + reading-mode + language + cache + bookmark-modal-flag + …); the
 *    rework declares only the 7 members the user-side Settings hub screen consumes.
 *  - The rework `:domain` [ThemeRepository] (Phase 7.x.theme), which exposes a tri-state
 *    `AppTheme` + PureBlack toggle for the THEME picker screen. The rework Settings hub mirrors
 *    the legacy's 3 raw theme toggles (Follow-system / Dark-mode / Pure-black) for visual parity;
 *    the picker route is for users who want a single-tap tri-state selector. Both routes write to
 *    the same `SharedPreferences` keys — no source-of-truth divergence.
 *
 * Contract §6 SRP: ONE rule — "expose the Settings hub screen's read + mutation surface". The
 * surface is 7 members: 1 [SettingsSnapshot] flow (`observeSettings`), 1 enum-dispatched toggle
 * setter (`setToggle(SettingsToggle, Boolean)` — covers every boolean toggle, not one method
 * each), 1 cache-clear (`clearLargeCache`), and the CBZ-conversion sub-surface
 * (`compressExistingDownloads` to run, `observeCbzConversion` to watch, `stopConversion` to
 * cancel, `clearConversionProgress` to reset).
 *
 * Contract §6 OCP: adding a new toggle is an additive [SettingsToggle] enum variant + a
 * [SettingsSnapshot] field — the single `setToggle` dispatcher and every other member are
 * unchanged.
 *
 * Contract §6 ISP: this fused interface is DELIBERATE — the SettingsViewModel is the single
 * consumer and it touches every surface (observe + toggle + cache + CBZ conversion). Splitting
 * along READ/WRITE would over-segment for zero ISP benefit since the consumer needs both sides.
 * Contrasts with §94's `ComplaintListRepository` (READ-only) + §95's `ComplaintActionRepository`
 * (WRITE-only) split — there each side has genuinely-different consumers (the foundation's
 * `ObserveUserComplaintsUseCase` never touches the actions side).
 *
 * Contract §6 DIP: consumers (the 3 use cases — `ObserveSettingsUseCase`, `UpdateSettingsToggle
 * UseCase`, `ClearCacheUseCase`, and through them the rework `SettingsViewModel`) depend on this
 * interface, never on the legacy facade or `SharedPreferences` / `DataStore` directly. Koin
 * binds the impl at the composition root in `settingsReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `SettingsRepository`'s `single` lifecycle from `SharedModule`). A `factory` would re-subscribe
 * the upstream pref flows on each resolution — wasteful for a setting read across the app's
 * lifetime.
 *
 * Behaviour preservation: both the legacy `Screen.Setting` route and the rework `Screen.
 * SettingsRework` route write to the same `SharedPreferences` keys (theme triplet) +
 * `DataStore` keys (general triplet) + same `AppFileSystem` cache dir — toggling on either
 * route propagates to the other. Phase 9.x route-swap retires the legacy route.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Line 49 ("Phase 9.x route-swap retires the legacy route").
 *    FACTUALLY INVERTED — Phase 7.x.settings.swap (§301) re-pointed
 *    `Screen.Setting`'s rendering adapter to the rework
 *    `SettingsScreen` (7.x-prefixed, earlier than the §253-era
 *    forecast predicted); `SettingsRoute.kt` was REWRITTEN to host
 *    the rework `:ui/.../settings/SettingsScreen` backed by the
 *    rework `:presentation/.../settings/SettingsViewModel` (Koin-bound
 *    via `settingsReworkModule`). Phase 9.x.settings_about.legacyui.
 *    retire (§354 sweep, "delete 11-file legacy Settings+About
 *    orphan chain") deleted the orphan legacy `:shared`
 *    `SettingsScreen` + `SettingsViewModel` + 9 component files.
 *    HOWEVER — the legacy `:shared`
 *    `me.manga.kira.presentation.features.settings.domain.
 *    SettingsRepository` facade + the underlying `SharedPreferences`
 *    booleans (theme triplet: `KEY_THEME_MODE` + `KEY_THEME_SYSTEM` +
 *    `KEY_PURE_BLACK`) + `DataStore` keys (general triplet:
 *    downloadedOnly + incognito + reading-mode) + `AppFileSystem`
 *    cache dir STILL EXIST as the cell of truth that the rework
 *    `:data` `SettingsRepositoryImpl` delegates to via constructor
 *    injection (per the cluster6 / §462 + cluster23 / §479
 *    audit-trail postscripts covering the `:data` impl angle). The
 *    forecast resolved cleanly across both §301 (route-swap) + §354
 *    (legacy-screen-retire) — both predicted phases executed, only
 *    the legacy `SettingsRepository` facade remains as the
 *    persistence backbone (cross-cutting cell shared with the rework
 *    Theme picker + Language picker). The SRP / OCP / ISP / DIP /
 *    lifecycle / behaviour-preservation sub-sections all stand on
 *    their own merits past the §§301 + 354 fulfilled landings. The
 *    SettingsRepository interface remains LIVE as the canonical
 *    rework Settings hub read+mutate surface. Original §253-era
 *    prose preserved verbatim per the audit-trail-preservation
 *    convention — the citation is historical record of the design
 *    lineage including the deferred-route-swap forecast that was
 *    subsequently fulfilled across §§301 + 354.
 */
interface SettingsRepository {

    /**
     * Reactive Settings snapshot. Emits the latest [SettingsSnapshot] whenever any of the 9
     * underlying sources change (3 DataStore prefs + 5 SharedPrefs booleans + cache-size
     * recomputation on subscription / cache-clear).
     *
     * First-run defaults (mirroring legacy):
     *  - `downloadedOnly = false`, `incognito = false` (DataStore default `false`)
     *  - `followSystemTheme = true`, `darkMode = false`, `pureBlack = true` (SharedPrefs defaults
     *    from legacy `SettingsRepository.isFollowSystem() / isDarkMode() / isPureBlack()`)
     *  - `cacheSize = formatted bytes via legacy AppFileSystem`
     *
     * Hot or cold: the `:data` impl returns a cold flow that subscribes to the legacy flows on
     * collection; combine semantics naturally debounce simultaneous updates from the same
     * write transaction (e.g., setting follow-system might also coincide with the theme-picker
     * setting dark-mode; both updates from the same write coalesce into a single emission).
     */
    fun observeSettings(): Flow<SettingsSnapshot>

    /**
     * Narrow, cheap read of the incognito flag alone — a single DataStore cell with no cache-folder
     * walk. The hot reader path (record-history on every chapter open / Next / Prev) reads this
     * instead of [observeSettings], whose first emission can't fire until the recursive cache-size
     * walk completes. Emits the current value immediately on collection and on every change.
     */
    fun observeIncognito(): Flow<Boolean>

    /**
     * Mutate the toggle identified by [toggle] to [value]. Suspends until the underlying write
     * completes (DataStore: actual async commit; SharedPrefs: sync commit but wrapped in suspend
     * for forward-compat with a future DataStore migration).
     *
     * Returns [Result.success] on commit success; [Result.failure] wraps any persistence
     * exception (IO error on DataStore; security exception on SharedPrefs — neither is expected
     * in practice but the wrap keeps the boundary uniform with the rest of the rework
     * Result-bearing repos).
     */
    suspend fun setToggle(toggle: SettingsToggle, value: Boolean): Result<Unit>

    /**
     * Clear cache files larger than 1MB from the app's cache directory. Suspends on the IO
     * dispatcher. Returns [Result.success] when the legacy `AppFileSystem.clearCacheLargerThan
     * (ONE_MB)` returns normally; [Result.failure] wraps any okio exception.
     *
     * **Side effect**: after a successful clear, the [observeSettings] flow re-emits a new
     * snapshot with the recomputed `cacheSize` field. The impl signals this via an internal
     * refresh trigger (a `MutableSharedFlow<Unit>` or equivalent) so callers see the updated
     * size without restarting the subscription.
     */
    suspend fun clearLargeCache(): Result<Unit>

    /**
     * Phase 7.x.settings.cbz — "compress existing downloads" action behind the Yami Compressor
     * section. Converts all previously-downloaded chapters to CBZ to save storage.
     *
     * Behaviour (mirrors the legacy `CbzMigrationWorker.doWork`): the `:data` impl walks
     * `ChapterDao.getAllDownloadedChapters()`, skips chapters already archived (a single-element
     * `localImagePaths` whose sole entry ends in `.cbz`), and for each remaining chapter repacks
     * its loose page files into one `.cbz` via the `:platform` `CbzWriter.createCbzWithSplitting`
     * (which deletes the original loose images on a successful encode), then rewrites the chapter
     * row's `localImagePaths` to point at the archive. Each chapter is isolated in its own
     * `runCatching`, so one failure does not abort the batch — it is skipped and the walk
     * continues. Re-running is therefore idempotent.
     *
     * Per-platform: all three targets repack to a real WebP-encoded CBZ — Android via
     * `Bitmap.compress(WEBP)`, Desktop + iOS via the shared skiko-backed `SkiaWebpEncoder`. A page
     * that skiko cannot decode (e.g. AVIF) is stored verbatim under its true extension rather than
     * failing the chapter, so the per-chapter `runCatching` rarely trips and the batch completes.
     *
     * Returns [Result.success] whenever the chapter walk completes (regardless of how many
     * individual chapters converted); [Result.failure] only if the DAO walk itself throws. The
     * user-facing toggles ([SettingsToggle.USE_CBZ_FORMAT] / [SettingsToggle.AUTO_CONVERT_TO_CBZ])
     * separately govern new downloads. Returning a [Result] keeps the call site uniform so the VM
     * surfaces a snackbar either way.
     *
     * **Progress (GAP-SET-16)**: while this runs, the impl drives [observeCbzConversion] with a
     * per-chapter [CbzConversionProgress] snapshot (current manga title + chapter number + the
     * converted/total counts) and a terminal success / stopped / error snapshot. [stopConversion]
     * lets the caller cancel mid-walk. The `Result<Unit>` return is preserved for the existing
     * fire-and-forget call sites that only need pass/fail; richer UI observes the flow instead.
     */
    suspend fun compressExistingDownloads(): Result<Unit>

    /**
     * Observable progress of the in-flight (or most recent) [compressExistingDownloads] run
     * (GAP-SET-16) — native-parity port of the native `CbzConversionViewModel.conversionProgress`
     * `StateFlow`.
     *
     * Hot state: the impl backs this with a single `MutableStateFlow<CbzConversionProgress>`
     * (lifecycle `single` per the impl — see [SettingsRepository] KDoc), so a fresh subscriber
     * immediately receives the current snapshot (idle baseline before any run, the live snapshot
     * mid-run, or the terminal snapshot after a run). The `:presentation` `SettingsViewModel`
     * collects this via [me.manga.kira.domain.usecase.settings.ObserveCbzConversionUseCase]
     * and projects each field into its MVI state for the `:ui` `CbzConversionDialog`.
     *
     * Idle baseline: a default [CbzConversionProgress] (`isConverting = false`, both message
     * fields `null`) — the dialog treats it as "nothing to show".
     */
    fun observeCbzConversion(): Flow<CbzConversionProgress>

    /**
     * Request cancellation of the in-flight [compressExistingDownloads] run (GAP-SET-16) —
     * native-parity port of `CbzConversionViewModel.stopConversion()`.
     *
     * Sets an internal stop flag the conversion loop checks between chapters; the loop finishes
     * the current chapter (no mid-archive corruption), then emits a terminal Stopped
     * [CbzConversionProgress] (`wasStopped = true`, `isConverting = false`) carrying the
     * converted/remaining counts. A no-op if no run is in flight.
     */
    fun stopConversion()

    /**
     * Reset the observable [observeCbzConversion] progress back to the idle baseline (#14) —
     * native-parity port of `CbzConversionViewModel.clearError()` (called when the dialog is
     * dismissed).
     *
     * The progress flow is hot `single`-scoped state, so a terminal Complete/Stopped/Error
     * snapshot otherwise survives a `SettingsViewModel` recreation and replays into a freshly
     * opened dialog. The `:presentation` `SettingsViewModel` calls this on dialog dismiss (after
     * the in-converting guard) so a recreated VM starts from idle. A no-op while a run is in
     * flight is the caller's responsibility — the VM only clears on the terminal/dismiss path.
     */
    fun clearConversionProgress()
}
