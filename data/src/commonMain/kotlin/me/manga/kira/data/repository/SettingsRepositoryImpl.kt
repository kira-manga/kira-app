package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.dao.MangaDao
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.model.settings.SettingsSnapshot
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.domain.repository.SettingsRepository
import me.manga.kira.platform.cbz.CbzWriter
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.filesystem.folderSize
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.Path.Companion.toPath
import kotlin.coroutines.cancellation.CancellationException
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/**
 * [SettingsRepository] strangler-fig delegate over the legacy `:shared`
 * [LegacySettingsRepository].
 *
 * Phase 7.x.settings.foundation rework. Combines the 5 upstream toggle flows (2 DataStore +
 * 3 SharedPreferences) + a derived cache-size flow into a single [SettingsSnapshot]. Mirrors
 * [ThemeRepositoryImpl] / [ReadingStatisticsRepositoryImpl]'s strangler-fig posture — the legacy
 * facade remains the cell of truth for `SharedPreferences` / `DataStore` / `AppFileSystem` reads
 * until the Phase 9.x route-swap retires the legacy Settings screen.
 *
 * **SRP (contract §6)**: owns ONE rule — "bundle the legacy Settings facade's 5 toggle flows +
 * the cache-size derivation into a single coherent snapshot stream, and dispatch toggle / clear
 * writes through the same facade". The DataStore / SharedPrefs / okio plumbing lives in the
 * legacy facade and its `prefsHelper` / `dataStoreHelper` / `appFileSystem` collaborators.
 *
 * **DIP (contract §6)**: depends on the legacy [LegacySettingsRepository] type and the rework
 * [DispatcherProvider] — both injected. The legacy dep is the strangler-fig boundary; the
 * dispatcher dep keeps `Dispatchers.IO` off the call site (per contract §13: domain/data may
 * not reference `kotlinx.coroutines.Dispatchers` directly, must go through
 * [DispatcherProvider]).
 *
 * **Import-alias note** — the rework [SettingsRepository] interface in `:domain` and the legacy
 * `me.manga.kira.presentation.features.settings.domain.SettingsRepository` are sibling types
 * with the same simple name. The `as LegacySettingsRepository` alias keeps the boundary visible
 * — every reference to the legacy facade in this file is grep-able by the alias prefix.
 *
 * **Why two chained `combine` calls** (rather than one vararg combine of all 6 sources):
 *  - `kotlinx.coroutines.flow.combine(vararg flows: Flow<T>)` requires all flows share element
 *    type `T`. The 6 sources are 5 `Flow<Boolean>` + 1 derived `Flow<String>` — a vararg
 *    collapse would force `Flow<Any>` (banned per contract §17).
 *  - Two chained `combine` calls — first the 5 booleans into a typed `BooleansBundle`, then a
 *    2-arity `combine` with the cache-size flow — keeps every step typed; no `Any` anywhere.
 *  - The stdlib also exposes a 5-arg `combine` overload, which lets the booleans collapse in
 *    a single typed call. Going to 6 sources directly would have required the `Any` workaround.
 *
 * **Cache-size flow** — neither the legacy `AppFileSystem.getCacheFolderSize()` nor the legacy
 * `clearFilesLargerThan1MB()` are flow-shaped (both are synchronous okio file-walks). To still
 * emit a fresh size when the user clears the cache, the impl owns a [MutableSharedFlow] refresh
 * trigger ([cacheRefresh]) with `replay = 1` so a new subscriber immediately receives the
 * current size; on a successful `clearLargeCache()` call, the impl re-emits `Unit` to push a
 * fresh size through the chain. The `flowOn(dispatchers.io)` upstream of [observeSettings]'s
 * `cacheSize` projection runs the okio walk on the IO dispatcher.
 *
 * **`combine` emission semantics** — from `kotlinx.coroutines.flow.combine` KDoc: "emits a
 * value by combining the latest values from each flow whenever any of the flows emits a value".
 * When the user toggles a single boolean, only that one upstream flow emits; the others
 * contribute their last cached value. The downstream sees one snapshot per toggle.
 *
 * **Concurrent-write nuance** — Theme triplet (`followSystem` / `darkMode` / `pureBlack`) and
 * General doublet (`downloadedOnly` / `incognito`) writes are independent `SharedPrefs` /
 * `DataStore` writes. If two writes hit close together, the `combine` may emit an intermediate
 * state with one flipped pref before the other settles. Compose recomposition coalesces faster
 * than the user can perceive; same posture as [ThemeRepositoryImpl]'s `setAppTheme` nuance.
 *
 * **`suspend` despite sync legacy writes** — the legacy `setDarkMode` / `setPureBlack` /
 * `setFollowSystem` are non-suspend `SharedPreferences.putBoolean` calls; the legacy
 * `setDownloadedOnly` / `setIncognito` ARE suspending DataStore writes. The rework
 * [SettingsRepository.setToggle] is uniformly `suspend` — forward-compatibility room for a
 * future SharedPrefs → DataStore migration. Matches the [ThemeRepository.setAppTheme] /
 * [ReadingModeRepository.set] posture.
 *
 * **`runCatching` wrap at the boundary** — both mutators (`setToggle`, `clearLargeCache`) wrap
 * the legacy call in `runCatching {}`. Legacy calls can throw IO / security exceptions in
 * extreme circumstances (DataStore corruption, SharedPrefs revocation, okio file-walk hitting
 * a deleted dir); the rework consumer (rework `SettingsViewModel`) sees a [Result] and emits
 * the appropriate snackbar. Same posture as the `:domain` `Result<Unit>`-returning use cases.
 *
 * **Lifecycle**: `single` in Koin (per [SettingsRepository] KDoc). The upstream legacy
 * [LegacySettingsRepository] is `single` (declared by `SharedModule`); a `factory` here would
 * resubscribe the upstream pref flows on each resolution AND construct a new [cacheRefresh]
 * `MutableSharedFlow` per resolution — both wasteful and would break the refresh-trigger
 * propagation (a clear-cache emission would only reach subscribers of the same impl instance).
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the per-host
 * repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, `:platform`, or
 * any Reader-path code. Settings is pure preference + cache plumbing. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster23.staleKdocSweep.cascade,
 * Task #479, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Line 24 ("the legacy facade remains the cell of truth for
 *    `SharedPreferences` / `DataStore` / `AppFileSystem` reads until
 *    the Phase 9.x route-swap retires the legacy Settings screen").
 *    FACTUALLY INVERTED — Phase 7.x.settings.swap (§301) re-pointed
 *    `Screen.Settings`'s rendering adapter to the rework
 *    `SettingsScreen` already (7.x-prefixed, earlier than the
 *    §253-era forecast predicted); Phase
 *    9.x.settings_about.legacyui.retire (§354) deleted the 11-file
 *    legacy `:composeApp/.../features/settings/` chain including the
 *    legacy `SettingsViewModel` + legacy `SettingsScreen.kt`.
 *    HOWEVER — the legacy [LegacySettingsRepository] facade + its
 *    `prefsHelper` + `dataStoreHelper` + `appFileSystem`
 *    collaborators STILL EXIST as the cell of truth that this impl
 *    delegates to via `legacy = get()` (verified at the constructor
 *    signature below — `private val legacy: LegacySettingsRepository`).
 *    The "cell of truth ... until the Phase 9.x route-swap retires
 *    the legacy Settings screen" forecast happened as a §301
 *    7.x-prefixed swap (earlier than predicted) followed by §354
 *    9.x retire. The strangler-fig backbone holds; only the legacy
 *    consumer-side surfaces were retired across §§301 + 354. Mirror
 *    of §476 settings cluster + §477/478 cluster-tier
 *    partially-fulfilled-inversion precedent.
 * The SRP / DIP / import-alias / two-chained-combine /
 * cache-size-flow / concurrent-write / suspend-despite-sync /
 * runCatching / lifecycle / load-bearing sub-sections all stand on
 * their own merits past the §§301 + 354 fulfilled landings. The
 * SettingsRepositoryImpl remains LIVE as the canonical
 * strangler-fig delegate for the rework settings surface. Original
 * §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the design lineage including the deferred-route-swap
 * forecast that was subsequently fulfilled across §§301 + 354.
 */
class SettingsRepositoryImpl(
    private val legacy: LegacySettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val dataStore: DataStoreHelper,
    private val chapterDao: ChapterDao,
    private val cbzWriter: CbzWriter,
    private val mangaDao: MangaDao,
    // B4: lets the manual compressor skip chapters that the background download engine is actively
    // transferring/finalizing, so the two never race on the same chapter dir's .cbz.part + loose pages.
    private val chapterDownloadDao: ChapterDownloadDao,
    // Ledger-size invariant (ChapterDownloadEntity KDoc): after a convert rewrites the chapter dir,
    // the SUCCESS row's sizeBytes must be re-walked or Details keeps showing the loose-pages size.
    private val appFileSystem: AppFileSystem,
) : SettingsRepository {
    private val cacheRefresh = MutableSharedFlow<Unit>(replay = 1)

    // GAP-SET-16 — hot progress state for the CBZ bulk-convert run, native-parity port of the
    // native `CbzConversionViewModel._conversionProgress` MutableStateFlow. A `single`-lifecycle
    // field (see SettingsRepository KDoc) so a fresh `:presentation` subscriber immediately sees
    // the current snapshot. Mutated from the conversion loop on `dispatchers.io`; the
    // StateFlow.update path is conflated + thread-safe under contention.
    private val conversionProgress = MutableStateFlow(CbzConversionProgress())

    // GAP-SET-16 — cancellation flag the conversion loop checks between chapters (native
    // `shouldStopConversion`). Modelled as a StateFlow<Boolean> rather than a plain `var` so the
    // write from `stopConversion()` (caller thread) is safely visible to the loop reader thread
    // (`dispatchers.io`) without a memory-model race — atomicfu/StateFlow guarantees the
    // happens-before edge. Reset to `false` at the start of every run.
    private val shouldStop = MutableStateFlow(false)

    override fun observeSettings(): Flow<SettingsSnapshot> {
        val booleans: Flow<BooleansBundle> =
            combine(
                legacy.downloadedOnlyFlow,
                legacy.incognitoFlow,
                legacy.followSystemFlow,
                legacy.darkModeFlow,
                legacy.pureBlackFlow,
            ) { downloaded, incog, follow, dark, pure ->
                BooleansBundle(downloaded, incog, follow, dark, pure)
            }
        // Phase 7.x.settings.cbz — Yami Compressor toggles read from the legacy DataStoreHelper
        // KEY_USE_CBZ_FORMAT / KEY_AUTO_CONVERT_TO_CBZ cells (the same cells the legacy
        // CbzConversionViewModel wrote). A second typed bundle keeps every flow typed (no `Any`).
        val cbz: Flow<CbzBundle> =
            combine(
                dataStore.useCbzFormatFlow,
                dataStore.autoConvertToCbzFlow,
            ) { useCbz, autoConvert ->
                CbzBundle(useCbz, autoConvert)
            }
        // Typed wire (2026-07 backlog L15): the snapshot carries RAW bytes; the localized
        // "1.23 GB"-style rendering happens in :ui (formatByteSize + the size_* patterns).
        val cacheSizeBytes: Flow<Long> =
            cacheRefresh
                .onStart { emit(Unit) }
                .map { legacy.getCacheFolderSize() }
                .flowOn(dispatchers.io)
        return combine(booleans, cbz, cacheSizeBytes) { b, c, size ->
            SettingsSnapshot(
                downloadedOnly = b.downloaded,
                incognito = b.incognito,
                followSystemTheme = b.follow,
                darkMode = b.dark,
                pureBlack = b.pure,
                cacheSizeBytes = size,
                useCbzFormat = c.useCbz,
                autoConvertToCbz = c.autoConvert,
            )
        }
    }

    // Narrow incognito read straight off the legacy DataStore cell — no cache-folder walk (unlike
    // observeSettings, whose first emission waits on the recursive cache-size computation). Used by
    // the record-history hot path on every chapter open / Next / Prev.
    override fun observeIncognito(): Flow<Boolean> = legacy.incognitoFlow

    override suspend fun setToggle(
        toggle: SettingsToggle,
        value: Boolean,
    ): Result<Unit> =
        runCatchingCancellable {
            when (toggle) {
                SettingsToggle.DOWNLOADED_ONLY -> legacy.setDownloadedOnly(value)
                SettingsToggle.INCOGNITO -> legacy.setIncognito(value)
                SettingsToggle.FOLLOW_SYSTEM_THEME -> legacy.setFollowSystem(value)
                SettingsToggle.DARK_MODE -> legacy.setDarkMode(value)
                SettingsToggle.PURE_BLACK -> legacy.setPureBlack(value)
                SettingsToggle.USE_CBZ_FORMAT -> dataStore.setUseCbzFormat(value)
                SettingsToggle.AUTO_CONVERT_TO_CBZ -> dataStore.setAutoConvertToCbz(value)
            }
        }

    override suspend fun clearLargeCache(): Result<Unit> =
        runCatchingCancellable {
            withContext(dispatchers.io) {
                legacy.clearFilesLargerThan1MB()
            }
            cacheRefresh.tryEmit(Unit)
        }

    // Phase 7.x.settings.cbz — the bulk convert-existing-downloads engine, strangler-figged into a
    // cross-platform `:data` pipeline over the `:shared` [ChapterDao] + the `:platform` [CbzWriter]
    // SPI. Mirrors the legacy `CbzMigrationWorker.doWork`: walk every downloaded chapter, repack the
    // loose page files into a single `.cbz` via [CbzWriter.createCbzWithSplitting] (which deletes the
    // originals on success), then rewrite the chapter row's `localImagePaths` to point at the archive.
    //
    // Per-chapter isolation: each chapter's convert+rewrite is wrapped in its own `runCatching` so a
    // single failure (e.g. iOS [NotImplementedError] from `IosCbzWriter`, or a corrupt page on
    // Android/Desktop) is counted and skipped without aborting the whole batch. The batch itself
    // returns [Result.success] whenever the DAO walk completes — the use case + VM only need the
    // pass/fail of the overall action, not the per-chapter tally. The outer `runCatching` still
    // catches a failure of the `getAllDownloadedChapters()` walk itself.
    //
    // Already-`.cbz` chapters are skipped via the legacy predicate (a single-element path list whose
    // sole entry ends in `.cbz`), so re-running the action is idempotent.
    //
    // GAP-SET-16 — the loop now drives [conversionProgress] per chapter (native parity with
    // `CbzConversionViewModel.startConversion`): it looks up the manga title via [mangaDao], emits
    // the current manga title + chapter number + the converted/total counts, honors the
    // [shouldStop] cancellation flag between chapters (finishing the in-flight chapter to avoid a
    // half-written archive), and emits a terminal Stopped / Completed / Error snapshot. The
    // `Result<Unit>` return is preserved for fire-and-forget callers; richer UI observes the flow.
    //
    // Terminal-message localization note: `:data` is not an Android module and has no
    // compose-resources access, so the terminal [CbzConversionProgress.successMessage] /
    // [CbzConversionProgress.error] fields carry a stable non-localized marker that signals the
    // terminal state only — the `:ui` `CbzConversionDialog` builds the displayed copy from the
    // structured count fields (`convertedChapters` / `totalChapters` / `wasStopped`) via
    // `stringResource`. Native builds the message in the VM with `context.getString(...)`; the KMP
    // split moves the string lookup to `:ui` where resources live, preserving the same counts.
    override suspend fun compressExistingDownloads(): Result<Unit> =
        runCatchingCancellable {
            shouldStop.value = false
            conversionProgress.value = CbzConversionProgress(isConverting = true)
            try {
                withContext(dispatchers.io) {
                    // B4: a chapter the background download engine is still transferring/finalizing shares this
                    // chapter's dir (.cbz.part + loose pages); compressing it concurrently corrupts one of the two
                    // writers. Skip any chapter with an active download row — the engine finalizes it on its own.
                    val activeStates =
                        setOf(
                            DownloadingState.QUEUED,
                            DownloadingState.RUNNING,
                            DownloadingState.DOWNLOADED,
                            DownloadingState.COMPRESSING,
                        )
                    val chapters =
                        chapterDao.getAllDownloadedChapters().filter { chapter ->
                            chapter.localImagePaths.isNotEmpty() &&
                                !(
                                    chapter.localImagePaths.size == 1 &&
                                        chapter.localImagePaths.first().endsWith(".cbz")
                                ) &&
                                chapterDownloadDao.getDownloadByChapter(chapter.id)?.state !in activeStates
                        }
                    val total = chapters.size
                    conversionProgress.update { it.copy(totalChapters = total) }

                    if (total == 0) {
                        // Nothing to convert — terminal Completed with zero counts (native's
                        // "no_chapters_to_convert" success path collapses into the same Completed state;
                        // the `:ui` renders the localized summary from the 0/0 counts).
                        conversionProgress.value =
                            CbzConversionProgress(
                                isConverting = false,
                                successMessage = TERMINAL_MARKER,
                            )
                        return@withContext
                    }

                    var converted = 0
                    chapters.forEachIndexed { index, chapter ->
                        if (shouldStop.value) {
                            emitStopped(total = total, converted = converted)
                            return@withContext
                        }
                        val mangaTitle =
                            runCatchingCancellable { mangaDao.getMangaById(chapter.mangaId)?.title }
                                .getOrNull()
                                .orEmpty()
                        conversionProgress.update {
                            it.copy(
                                convertedChapters = index,
                                currentMangaTitle = mangaTitle,
                                currentChapterNumber = chapter.number,
                            )
                        }
                        runCatchingCancellable {
                            val cbz =
                                cbzWriter.createCbzWithSplitting(
                                    imagePaths = chapter.localImagePaths.map { it.toPath() },
                                    mangaId = chapter.mangaId,
                                    chapterId = chapter.id,
                                )
                            chapterDao.updateChapterLocalPaths(chapter.id, listOf(cbz.toString()))
                            // The WebP re-encode changed the chapter's on-disk size — refresh the
                            // chapter_downloads SUCCESS row's sizeBytes (the canonical size ledger
                            // Details reads) with an engine-parity dir walk. Keyed by chapter.id
                            // (unique-indexed), never by url. Best-effort in its own guard: a
                            // walk/DB hiccup keeps the convert successful, and a row-only-deleted
                            // ledger row makes updateSize a no-op — never resurrect a queue row.
                            runCatchingCancellable {
                                val sizeBytes =
                                    appFileSystem.folderSize(
                                        appFileSystem.chapterDir(chapter.mangaId, chapter.id),
                                    )
                                if (sizeBytes > 0L) chapterDownloadDao.updateSize(chapter.id, sizeBytes)
                            }
                        }.onSuccess { converted++ }
                    }

                    // Re-check after the last chapter so a Stop pressed during the final convert still
                    // surfaces the Stopped terminal state (native re-checks `shouldStopConversion` too).
                    if (shouldStop.value) {
                        emitStopped(total = total, converted = converted)
                    } else {
                        conversionProgress.value =
                            CbzConversionProgress(
                                isConverting = false,
                                totalChapters = total,
                                convertedChapters = converted,
                                successMessage = TERMINAL_MARKER,
                            )
                    }
                }
            } catch (ce: CancellationException) {
                // Navigating away from Settings mid-run cancels viewModelScope -> this coroutine.
                // runCatchingCancellable rethrows CancellationException so .onFailure never resets the
                // app-lifetime single's flow; without this, isConverting stays true forever and the
                // :ui CbzConversionDialog becomes a permanently undismissable modal. Reset to idle and
                // rethrow so structured concurrency is preserved.
                conversionProgress.value = CbzConversionProgress()
                throw ce
            }
            Unit
        }.onFailure {
            // The DAO walk itself threw (the per-chapter convert is isolated above). Emit the terminal
            // Error snapshot unless the user already stopped (native suppresses the error on a stop).
            if (!shouldStop.value) {
                conversionProgress.value =
                    CbzConversionProgress(
                        isConverting = false,
                        error = TERMINAL_MARKER,
                    )
            }
        }

    override fun observeCbzConversion(): Flow<CbzConversionProgress> = conversionProgress.asStateFlow()

    override fun stopConversion() {
        shouldStop.value = true
    }

    // #14 — reset the hot progress flow to the idle baseline so a terminal Complete/Stopped/Error
    // snapshot does not replay into a recreated SettingsViewModel's dialog (native
    // CbzConversionViewModel.clearError()). The :presentation VM calls this only on the dismiss
    // path after its in-converting guard, so this never clobbers a live run's snapshot.
    override fun clearConversionProgress() {
        conversionProgress.value = CbzConversionProgress()
    }

    /**
     * Emit the terminal Stopped snapshot (native `stopConversion()` body) — `wasStopped = true`,
     * `isConverting = false`, carrying the converted count + the implied remaining
     * (`total - converted`). The `:ui` renders the localized "stopped by user" summary from these.
     */
    private fun emitStopped(
        total: Int,
        converted: Int,
    ) {
        conversionProgress.value =
            CbzConversionProgress(
                isConverting = false,
                totalChapters = total,
                convertedChapters = converted,
                wasStopped = true,
                successMessage = TERMINAL_MARKER,
            )
    }

    private data class BooleansBundle(
        val downloaded: Boolean,
        val incognito: Boolean,
        val follow: Boolean,
        val dark: Boolean,
        val pure: Boolean,
    )

    private data class CbzBundle(
        val useCbz: Boolean,
        val autoConvert: Boolean,
    )

    private companion object {
        // GAP-SET-16 — non-localized sentinel for the terminal CbzConversionProgress message
        // fields. `:data` has no compose-resources access; this only signals "terminal state
        // reached" so the dialog flips out of the converting shell. The `:ui` builds the actual
        // displayed copy from the structured count fields. Any non-null value works; a stable
        // constant keeps the intent grep-able.
        const val TERMINAL_MARKER = "done"
    }
}
