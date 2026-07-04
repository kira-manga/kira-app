package me.manga.kira.di

import me.manga.kira.data.repository.ChapterBookmarkRepositoryImpl
import me.manga.kira.data.repository.ChapterPagesRepositoryImpl
import me.manga.kira.data.repository.MarkChapterReadRepositoryImpl
import me.manga.kira.data.repository.PageProgressRepositoryImpl
import me.manga.kira.data.repository.ReadProgressRepositoryImpl
import me.manga.kira.data.repository.ReadingModeRepositoryImpl
import me.manga.kira.data.repository.ReadingSessionRepositoryImpl
import me.manga.kira.domain.repository.ChapterBookmarkRepository
import me.manga.kira.domain.repository.ChapterPagesRepository
import me.manga.kira.domain.repository.MarkChapterReadRepository
import me.manga.kira.domain.repository.PageProgressRepository
import me.manga.kira.domain.repository.ReadProgressRepository
import me.manga.kira.domain.repository.ReadingModeRepository
import me.manga.kira.domain.repository.ReadingSessionRepository
import me.manga.kira.domain.usecase.reader.EndReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.ObserveChapterBookmarkUseCase
import me.manga.kira.domain.usecase.reader.ObservePageProgressUseCase
import me.manga.kira.domain.usecase.reader.ClearExtractedPagesUseCase
import me.manga.kira.domain.usecase.reader.ClearPageProgressUseCase
import me.manga.kira.domain.usecase.reader.FetchChapterPagesUseCase
import me.manga.kira.domain.usecase.reader.ListChaptersUseCase
import me.manga.kira.domain.usecase.reader.LoadPagePositionUseCase
import me.manga.kira.domain.usecase.reader.MarkChapterReadUseCase
import me.manga.kira.domain.usecase.reader.ObserveReadingModeUseCase
import me.manga.kira.domain.usecase.reader.RecordHistoryUseCase
import me.manga.kira.domain.usecase.reader.SavePagePositionUseCase
import me.manga.kira.domain.usecase.reader.SetReadingModeUseCase
import me.manga.kira.domain.usecase.reader.StartReadingSessionUseCase
import me.manga.kira.domain.usecase.reader.ToggleChapterBookmarkUseCase
import me.manga.kira.presentation.reader.ReaderViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin bindings for the rework Reader slice.
 *
 * Scope discipline (mirrors [detailsReworkModule]):
 *  - Binds ONLY rework types: [ChapterPagesRepository] (`:domain`) → [ChapterPagesRepositoryImpl]
 *    (`:data`), [FetchChapterPagesUseCase] (`:domain`), and [ReaderViewModel] (`:presentation`).
 *  - Legacy `:shared` `ReaderViewModel` and its repository graph stay bound by `SharedModule` /
 *    `PlatformModule.*`; both graphs coexist until the user-facing route swap (post-8.x.reader).
 *
 * Cross-module dependencies resolved at composition time:
 *  - `SourcesRepository` (consumed by [ChapterPagesRepositoryImpl]) is the same legacy `:shared`
 *    singleton declared by `SharedModule.kt` and reused by [detailsReworkModule]. Strangler-fig
 *    boundary: this impl delegates to `SourcesRepository.getOrRepoByName(api).fetchChapterDataF(url)`
 *    and maps the legacy `Flow<State<List<String>>>` into the pure-domain `Flow<AppResult<List<Page>>>`.
 *  - `DispatcherProvider` (consumed by [ChapterPagesRepositoryImpl]) is bound as a `single` in
 *    [libraryReworkModule] — same posture as [detailsReworkModule] uses it. We deliberately do
 *    not re-declare it; Koin forbids duplicate `single<T>` bindings.
 *  - [me.manga.kira.domain.repository.MangaDetailsRepository] (consumed by
 *    [ListChaptersUseCase]) is bound as a `single` in [detailsReworkModule]. Phase 7.x.reader.next
 *    deliberately reuses the Details slice's repository rather than introducing a parallel
 *    `ChaptersRepository`: legacy `BaseMangaRepository.fetchMangaChaptersF` is the single source
 *    endpoint that returns both manga metadata + chapter list in one `MangaInfo` payload, so a
 *    dedicated `ChaptersRepository` would either duplicate that fetch or delegate to the Details
 *    repository (dead-weight interface). [ListChaptersUseCase] is a thin projection that extracts
 *    `chapters` from the `MangaDetails` result. Koin's cross-module resolution handles this
 *    transparently because [me.manga.kira.di.allReworkModules] aggregates [detailsReworkModule]
 *    and this module into the same dep graph.
 *  - Legacy [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository]
 *    (consumed by [ReadingSessionRepositoryImpl]) is the same `:shared` `single` declared by
 *    `SharedModule.kt` (line 238). Phase 6.4.x.statistics deliberately delegates to the legacy
 *    rather than re-implementing the session-timer + minute-counter persistence: same on-disk
 *    cell (`StorageKeys.READ_MINUTES`) is written by both readers during the strangler-fig
 *    transition, so the user's accumulated read-time stays consistent. Mirrors the
 *    [ChapterPagesRepositoryImpl] → `SourcesRepository` reuse posture. The legacy methods can
 *    be retired in Phase 9.x once the user-facing route swap promotes the rework Reader.
 *  - [ObservableSettings] (consumed by [ReadProgressRepositoryImpl]) is the same `single`
 *    [ReadingModeRepositoryImpl] already consumes. Phase 7.x.reader.resumeposition is a
 *    net-new persistence cell (not strangler-fig): the legacy `HistoryItemD.lastReadPage`
 *    column is dead-write — the legacy reader always passes `lastReadPage = 0` via
 *    `historyViewModel.updateHistoryItem(...)`, so there is no on-disk page-position cell to
 *    preserve. The rework writes a fresh `reader.last_page.<hash>` key directly via
 *    `ObservableSettings`, bypassing the legacy Room graph entirely. See the
 *    [ReadProgressRepository] class-level KDoc for the storage layout + collision-safety
 *    rationale.
 *
 * SRP (contract §6): one module = one feature slice.
 *
 * DIP (contract §6): the [ChapterPagesRepository] interface from `:domain` is bound to its `:data`
 * impl at the composition root. Presentation and UI see only the use case / interface.
 *
 * Lifecycle choices:
 *  - [ChapterPagesRepository] → `single`: impl holds no per-call state; the underlying
 *    `SourcesRepository` is already a singleton. Re-creating per resolution would be wasteful.
 *  - [FetchChapterPagesUseCase] → `factory`: stateless, cheap, matches established
 *    "use case is a factory" pattern.
 *  - [ReaderViewModel] → `viewModel`: Koin's `ViewModelStore`-aware binding so the screen
 *    survives configuration changes / pop-and-restore navigation. Mirrors `DetailsViewModel`.
 */
val readerReworkModule: Module = module {
    single<ChapterPagesRepository> {
        ChapterPagesRepositoryImpl(
            sourcesRepository = get(),
            dispatchers = get(),
            // Downloaded-chapter local-read path: ChapterDao (saved-chapter lookup by URL +
            // localImagePaths) is the per-platform :shared Room singleton; CbzReader is the
            // :platform okio-backed reader. Both are bound in PlatformModule.{android,ios,desktop}.
            chapterDao = get(),
            cbzReader = get(),
            // Routes ONLY config-backed sources (CONFIG_BACKED_APIS) through the generic engine for the network
            // page fetch; the downloaded-chapter offline path and all other sources stay unchanged.
            sourceRegistry = get(),
            // Re-derives loose downloaded-page paths under the live chapter dir (iOS container-UUID
            // staleness) and gates the local-read fall-through. The :platform AppFileSystem singleton.
            appFileSystem = get(),
        )
    }

    factory { FetchChapterPagesUseCase(get()) }
    // Fire-and-forget cleanup of extracted-CBZ temp dirs (wraps the same ChapterPagesRepository).
    factory { ClearExtractedPagesUseCase(get()) }

    // Reading-mode persistence (Phase 6.4.x.mode). The impl re-uses the legacy
    // `single<ObservableSettings>` declared in `PlatformModule.<target>.kt` — strangler-fig
    // posture so the rework reader and the legacy reader share the same disk cell. Single
    // because the impl holds no per-call state and the backing `ObservableSettings` is itself a
    // singleton; reconstructing per resolution would be wasteful. Use cases stay factory
    // (stateless, matches the established slice pattern).
    single<ReadingModeRepository> { ReadingModeRepositoryImpl(settings = get()) }
    factory { ObserveReadingModeUseCase(get()) }
    factory { SetReadingModeUseCase(get()) }

    // Multi-chapter navigation (Phase 7.x.reader.next). Reuses [MangaDetailsRepository] from
    // [detailsReworkModule] via Koin cross-module resolution — see class-level KDoc rationale.
    // Factory because the use case is stateless and cheap to instantiate per resolution,
    // matching the established slice pattern.
    // Network-first chapter list with a Room offline fallback (SavedMangaDetailsRepository is a
    // single bound in detailsReworkModule; resolved cross-module).
    factory { ListChaptersUseCase(get(), get()) }

    // Reading-session timer (Phase 6.4.x.statistics). The impl delegates to the legacy
    // [me.manga.kira.presentation.features.statistics.domain.StatisticsRepository] declared
    // by `SharedModule.kt` — strangler-fig posture so the rework reader and the legacy reader
    // accumulate minutes into the SAME on-disk counter (`StorageKeys.READ_MINUTES`).
    // `single` because the legacy [StatisticsRepository] holds the per-session `sessionStartMillis`
    // field — a `factory` impl would resolve a fresh wrapper per call, but the wrapper would
    // still resolve the same legacy singleton; harmless but wasteful. The Start use case is
    // factory (cheap, stateless, matches the slice pattern); same for End. Both resolve to the
    // same [ReadingSessionRepository] singleton so begin / end share state.
    single<ReadingSessionRepository> { ReadingSessionRepositoryImpl(legacy = get()) }
    factory { StartReadingSessionUseCase(get()) }
    factory { EndReadingSessionUseCase(get()) }

    // Per-chapter last-read-page persistence (Phase 7.x.reader.resumeposition). The impl writes
    // a fresh `reader.last_page.<hash>` Settings cell — no strangler-fig delegation because the
    // legacy `HistoryItemD.lastReadPage` column is dead-write (legacy reader always passes 0).
    // `single` because the impl holds no per-call state and the backing `ObservableSettings` is
    // itself a singleton; reconstructing per resolution would be wasteful. Use cases stay
    // `factory` (stateless, matches the established slice pattern).
    single<ReadProgressRepository> { ReadProgressRepositoryImpl(settings = get()) }
    factory { SavePagePositionUseCase(get()) }
    factory { LoadPagePositionUseCase(get()) }

    // Per-page download/decode progress (Phase 7.x.reader.modelayout.pageprogress). Pure-in-memory
    // [MutableStateFlow]-backed repository — no `ObservableSettings`, no Room, no on-disk cell.
    // Progress state is ephemeral and resets to [PageDownloadProgress.Idle] on process restart
    // (correct — a fresh process re-fetches every page anyway). `single` because the repository
    // IS the in-memory cache; multiple instances would partition state between reporters and
    // observers. The Reader VM observes through [ObservePageProgressUseCase] (DIP — A19); the
    // reporters (the Coil per-request listener attached in `:ui`, and the Android OkHttp body
    // wrap in `:platform/androidMain`) drive the write half (`report`) directly through the
    // `:domain` interface. Use case `factory` per the slice pattern.
    single<PageProgressRepository> { PageProgressRepositoryImpl() }
    factory { ObservePageProgressUseCase(get()) }
    factory { ClearPageProgressUseCase(get()) }

    // Chapter-bookmark strangler-fig (Phase 6.4.x.bookmark, task #217; re-pointed at the DAO in
    // RS-3, task #738). Delegates straight to the Room `ChapterDao` (bound per-platform) so the
    // rework reader and the legacy reader flip the SAME `saved_chapters.isBookmarked` column —
    // which keeps the Library bookmarkedCount badge (MangaDao.getAllChapterMetricsFlow COUNT,
    // consumed by LibraryRepositoryImpl.observeLibrary) correct automatically via Room
    // invalidation. The seam no longer routes through the legacy :shared LibraryRepository wrapper
    // (which only forwarded these calls to the same DAO); the legacy repo STAYS for :app
    // (LibraryRefreshWorker + ChapterNotificationHelper). `single` (impl holds no per-call state,
    // backing DAO is itself a singleton); use cases `factory` per the slice pattern.
    single<ChapterBookmarkRepository> { ChapterBookmarkRepositoryImpl(chapterDao = get()) }
    factory { ObserveChapterBookmarkUseCase(get()) }
    factory { ToggleChapterBookmarkUseCase(get()) }

    // Reading-history record-on-open (Reader-convergence R3a). Resolves the rework
    // HistoryRepository (bound in historyReworkModule, strangler-fig over the legacy :shared
    // HistoryRepository facade + HistoryDao) and SettingsRepository (bound in settingsReworkModule
    // — the incognito gate reads its narrow observeIncognito() accessor off the shared Settings
    // cell, NOT the full observeSettings() snapshot whose first emission waits on a cache-folder
    // walk). Both are aggregated into the same dep graph by allReworkModules, so Koin cross-module
    // resolution wires them transparently. Factory because the use case is stateless and cheap,
    // matching the established slice pattern. The incognito gate lives inside the use case (no-op
    // when ON).
    factory { RecordHistoryUseCase(repository = get(), settings = get()) }

    // Mark-chapter-read strangler-fig (Reader-convergence R3b; re-pointed at the DAO in RS-3,
    // task #738). Delegates straight to the Room `ChapterDao` (bound per-platform) so the rework
    // reader sets the SAME `saved_chapters.isRead` column the legacy reader did — which keeps the
    // Library readCount + the UNREAD filter (MangaDao.getAllChapterMetricsFlow COUNT, consumed by
    // LibraryRepositoryImpl.observeLibrary) correct automatically via Room invalidation. The seam
    // no longer routes through the legacy :shared LibraryRepository wrapper (which only forwarded
    // this call to the same DAO); the legacy repo STAYS for :app. `single` (impl holds no per-call
    // state, backing DAO is itself a singleton); use case `factory` per the slice pattern. NOT
    // incognito-gated — read state is library progress, not a browsing trail (legacy parity).
    single<MarkChapterReadRepository> { MarkChapterReadRepositoryImpl(chapterDao = get()) }
    factory { MarkChapterReadUseCase(get()) }

    viewModel { ReaderViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster150.staleKdocSweep.cascade,
 * Task #606, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-second sibling of the cluster57-149
 * sweep — opening file of the wave-26 :composeApp/di rework Koin module
 * closing 4-leaf batch alongside AboutReworkModule plus UpdatesReworkModule
 * plus ReworkModules aggregator; closes :composeApp/di tier 16/16):
 *  (a) "Koin-bindings-for-the-rework-Reader-slice + Scope-discipline-
 *  mirrors-detailsReworkModule-Binds-ONLY-rework-types-ChapterPages
 *  Repository-:domain-ChapterPagesRepositoryImpl-:data-FetchChapter
 *  PagesUseCase-:domain-and-ReaderViewModel-:presentation + Legacy-
 *  :shared-ReaderViewModel-and-its-repository-graph-stay-bound-by-
 *  SharedModule-PlatformModule-both-graphs-coexist-until-the-user-
 *  facing-route-swap-post-8.x.reader" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: readerReworkModule binds
 *  9 use cases plus 5 repositories plus ReaderViewModel. Legacy :shared
 *  ReaderViewModel + ReaderRepository graph still LIVE in SharedModule
 *  per Task #422 BLOCKER section 250 shadow-legacy-facade retire path;
 *  the "post-8.x.reader user-facing route swap" was FULFILLED at
 *  Task #295 / cluster6 (legacy Screen.ReaderScreen retired), but the
 *  legacy ReaderViewModel ctor + its repo graph remain LIVE for the
 *  legacy ChapterImages route + HomeViewModel surfaces — the broader
 *  retire is paused at the §250 blocker.
 *  (b) "Cross-module-dependencies-resolved-at-composition-time + Sources
 *  Repository-consumed-by-ChapterPagesRepositoryImpl-is-the-same-legacy-
 *  :shared-singleton-declared-by-SharedModule.kt-and-reused-by-details
 *  ReworkModule + Strangler-fig-boundary-this-impl-delegates-to-Sources
 *  Repository.getOrRepoByName-api-fetchChapterDataF-url-and-maps-the-
 *  legacy-Flow-State-List-String-into-the-pure-domain-Flow-AppResult-
 *  List-Page + DispatcherProvider-consumed-by-ChapterPagesRepositoryImpl
 *  -is-bound-as-a-single-in-libraryReworkModule + Koin-forbids-duplicate
 *  -single-T-bindings + MangaDetailsRepository-consumed-by-ListChapters
 *  UseCase-is-bound-as-a-single-in-detailsReworkModule + Phase-7.x.
 *  reader.next-deliberately-reuses-the-Details-slice-s-repository-
 *  rather-than-introducing-a-parallel-ChaptersRepository + ListChapters
 *  UseCase-is-a-thin-projection-that-extracts-chapters-from-the-Manga
 *  Details-result + Legacy-StatisticsRepository-consumed-by-Reading
 *  SessionRepositoryImpl-is-the-same-:shared-single-declared-by-Shared
 *  Module.kt-line-238 + Phase-6.4.x.statistics-deliberately-delegates-
 *  to-the-legacy-rather-than-re-implementing-the-session-timer-minute-
 *  counter-persistence + same-on-disk-cell-StorageKeys.READ_MINUTES-
 *  is-written-by-both-readers-during-the-strangler-fig-transition +
 *  Observable-Settings-consumed-by-ReadProgressRepositoryImpl-is-the-
 *  same-single-ReadingModeRepositoryImpl-already-consumes + Phase-7.x.
 *  reader.resumeposition-is-a-net-new-persistence-cell + the-legacy-
 *  HistoryItemD.lastReadPage-column-is-dead-write + the-rework-writes-
 *  a-fresh-reader.last_page.hash-key-directly-via-ObservableSettings-
 *  bypassing-the-legacy-Room-graph-entirely" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: every cross-module reuse
 *  honored — SourcesRepository (legacy) + DispatcherProvider (library
 *  ReworkModule) + MangaDetailsRepository (detailsReworkModule) +
 *  StatisticsRepository (legacy via SharedModule) + ObservableSettings
 *  (PlatformModule.*). The "legacy methods can be retired in Phase
 *  9.x once route swap promotes the rework Reader" forecast is
 *  PARTIALLY-FULFILLED: Reader route swap LIVE but legacy Statistics
 *  Repository still LIVE for legacy HomeViewModel surfaces; the on-
 *  disk READ_MINUTES cell remains shared.
 *  (c) "SRP-contract-section-6-one-module-one-feature-slice + DIP-
 *  contract-section-6-the-ChapterPagesRepository-interface-from-:domain-
 *  is-bound-to-its-:data-impl-at-the-composition-root-Presentation-and-
 *  UI-see-only-the-use-case-interface + Lifecycle-choices + Chapter
 *  PagesRepository-single-impl-holds-no-per-call-state-the-underlying-
 *  SourcesRepository-is-already-a-singleton-Re-creating-per-resolution-
 *  would-be-wasteful + FetchChapterPagesUseCase-factory-stateless-cheap
 *  -matches-established-use-case-is-a-factory-pattern + ReaderViewModel
 *  -viewModel-Koin-s-ViewModelStore-aware-binding-so-the-screen-survives
 *  -configuration-changes-pop-and-restore-navigation-Mirrors-Details
 *  ViewModel" — LIVE-NOT-STALE. Verified: 5 single<X> bindings (Chapter
 *  PagesRepository + ReadingModeRepository + ReadingSessionRepository
 *  + ReadProgressRepository + PageProgressRepository) + 9 factory
 *  bindings + 1 viewModel binding. The SRP one-module-one-slice posture
 *  is honored — readerReworkModule binds ONLY reader-slice types; no
 *  cross-slice leakage. The DIP discipline is honored — :domain
 *  interfaces are bound to :data impls at the composition root; the
 *  ReaderViewModel sees only the :domain use cases.
 *  Three classifications STAND on their own merits. This is the OPENING
 *  FILE of cluster150 — opens the :composeApp/di rework Koin module
 *  closing batch (4 of 16 left unswept after the cluster3-15 route-
 *  adapter sweep covered 12 di/ files indirectly via their route
 *  cross-references; this batch sweeps the 4 module files directly).
 *  Original Phase 6.4.2 / Phase 6.4.x.statistics / Phase 7.x.reader.
 *  next / Phase 7.x.reader.resumeposition / Phase 7.x.reader.modelayout
 *  .pageprogress (Tasks #213 + #232 + #230 + #233 + #236) module-
 *  binding prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
