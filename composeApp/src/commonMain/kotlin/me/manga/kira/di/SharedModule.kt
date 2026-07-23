package me.manga.kira.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import me.manga.kira.core.dispatchers.platformIoDispatcher
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.data.local.di.databaseModule
import me.manga.kira.data.remote.di.remoteModule
import me.manga.kira.domain.service.FileService
import me.manga.kira.sources.legacy.di.sourcePersistenceModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Common Koin module — registers everything that is platform-independent and has already been
 * moved into commonMain. Phase 8.12 expands this from "just the complaint use cases" to the full
 * common surface: the Ktor `HttpClient` (assembled by per-platform `createHttpClient()` actuals
 * but instantiated here so it's a process-wide singleton), the [ApiClient] wrapper, the 50+
 * per-source repositories, and supporting helpers like [ManhastroDadosStore].
 *
 * Hilt → Koin mapping (highlights):
 *
 *   @Provides @Singleton fun provideHttpClient(): OkHttpClient
 *     → single { createHttpClient() }
 *
 *   @Provides @Singleton fun provideApiClient(client: HttpClient): ApiClient
 *     → single { ApiClient(get()) }
 *
 *   @Provides @Singleton fun provideXxxRepository(api, dataStore, sourcesDao): XxxRepository
 *     → factory { XxxRepository(get(), get(), get()) }
 *
 *   @Provides @Singleton fun provide<UseCase>(repo: ComplaintRepository)
 *     → factory { <UseCase>(get()) }
 *
 * Repositories are bound as `factory { ... }` (not `single`) to mirror upstream's per-call
 * lifecycle — repository instances are stateless wrappers around the shared `ApiClient` and
 * `DataStoreHelper`, and the source app re-injected them into each ViewModel.
 *
 * **Why not bind every Comick/MangaPark language variant here?** The plan's 52-repo list
 * included several files that are intentionally empty in commonMain (full body kept as a
 * comment because the upstream Android source had the file commented out or because the EN
 * base class hasn't been ported yet — see the migration notes in each stub file). Binding a
 * non-existent class would not compile, so the disabled variants below (Comick {Ar, Es, Id, It,
 * PtBr, Ru, Tr}, MangaPark {It}, ReadComicOnline) intentionally omit their `factory {}` lines.
 * They will be wired in the same migration step that uncomments their bodies.
 *
 * `BaseMangaRepository` itself is not bound: the `params`-driven dispatch on `MangaSource` lives
 * in Phase 9 (a `MangaRepositoryProvider` ViewModel-scoped helper that picks the right concrete
 * repo per source). Consumer code holds the concrete type, never the abstract one.
 *
 * Bindings that need Context / Activity (DataStoreHelper, SettingsFactory, AppDatabase, SourcesDao,
 * the 18 expect/actual facades) live in [platformModule] and join the graph at runtime.
 */
val sharedModule: Module = module {

    // ---- HTTP / API ----
    // Relocated to :data:remote `remoteModule()` (strangler-fig Phase 2), added via allSharedModules().

    // ---- Per-source repositories (Phase 7 ports) ----
    // Relocated to :sources:legacy `legacySourcesModule()` (strangler-fig Phase 3), added via
    // allSharedModules(). The 43 scraper factories + ManhastroDadosStore + the Set<BaseMangaRepository>
    // registry live there now; `SourcesRepository` (below) resolves that Set cross-module by type.

    // ---- Phase 8.13 repos / helpers ----
    // Helpers first (storage / filesystem service), then domain repositories that depend on
    // DAOs + helpers.
    //
    // Hilt → Koin mapping for this block:
    //   @Provides @Singleton fun provideApplicationScope(): CoroutineScope
    //     → single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    //   @Provides @Singleton fun provideSharedPrefsHelper(settings: ObservableSettings)
    //     → single { SharedPrefsHelper(get()) }    // ObservableSettings bound in platformModule
    //   @Provides @Singleton fun provideFileService(fs: AppFileSystem)
    //     → single { FileService(get()) }          // AppFileSystem bound in platformModule
    //   @Provides + @Binds @IntoSet Set<BaseMangaRepository>
    //     → single<Set<BaseMangaRepository>> { setOf(get(), get(), ...) }
    //
    // DownloadRepository binding deferred to platformModule (Android-only impl, Phase 8.14).
    // The interface lives in commonMain but the only concrete impl (WorkManager-backed
    // DownloadRepositoryImpl) is Android-only — see DownloadRepository file header.
    //
    // Admin is a Kotlin `object` (singleton already), not a class — no DI needed.

    single<CoroutineScope> { CoroutineScope(SupervisorJob() + platformIoDispatcher) }

    single { SharedPrefsHelper(get()) }
    single { FileService(get()) }

    // MangaRepository (legacy domain/repos) deleted in strangler Phase 5 — it had zero live
    // consumers (its members were pruned to orphans over prior audits).
    // RS-2 (Task #738): legacy NotificationRepository wrapper retired. `UpdatesRepositoryImpl`
    // (:data) now talks to NotificationDao + LibraryDeo directly; the wrapper was its only
    // consumer (NotificationDao's other reachers — ChapterNotificationHelper / ChapterDownloadService
    // / LibraryRepository — always used the DAO receiver, never this facade).
    // StatisticsRepository + SettingsRepository relocated to :data legacyDataModule (strangler-fig
    // Phase 5), alongside WhatsNewRemoteDataSource.

    // LibraryRepository + SourcesRepository relocated to :sources:legacy legacySourcesModule()
    // (strangler-fig Phase 5) — they + the Set<BaseMangaRepository> registry now live together in
    // the module both :data:download and :app depend on.

    // Complaint (feedback) feature relocated to :data (strangler-fig Phase 5): the five use-case
    // factories moved to :data complaintUseCasesModule, and the per-target ComplaintRepository
    // binding (Firebase Android / Ktor REST iOS+Desktop) moved from the platformModule actuals to
    // :data complaintRepositoryModule() — both loaded via allReworkModules().

    // Legacy VMs (WebViewViewModel / SettingsViewModel / WhatsNewViewModel) relocated to
    // :composeApp legacySharedViewModelsModule (strangler-fig Phase 5) — they leave :shared so
    // their repo deps can move to :data without a cycle.

    // ---- ViewModels (Phase 9.5 ports) ----
    // MangaRepository / SettingsRepository / SourcesRepository all bound in the Phase 8.13 block
    // above. DownloadRepository (interface) lives in commonMain but its impl will be bound by
    // androidMain platformModule in Phase 8.14 — DownloadViewModelv2 won't resolve on Desktop/iOS
    // until then.
    //
    // Phase 9.x.downloadvmv2.retire (Task #439, 2026-05-28): the "Desktop/iOS resolution"
    // prophecy is now moot — `DownloadViewModelv2` was retired as a cascade orphan when its
    // sole external consumer (`LibraryMangaScreenRoute.kt`) was deleted in
    // Phase 9.x.libdetails.retire.5a (Task #435). The `DownloadRepository` interface stays
    // LIVE for `:data DownloadsActionRepositoryImpl` (strangler-fig) and for the other repo
    // impls; only the VM binding here was retired.
    //
    // Epic H5b (Phase 9.x): the legacy `HomeViewModel` + `RepoSettingsViewModel` bindings were
    // retired alongside the legacy Home/Search surface. Both VMs were sole-consumed by the
    // now-deleted `HomeScreenRoute.kt` (`koinViewModel()` at its lines 166 + 168); no other
    // `get()`/`koinViewModel()` reacher existed across the source tree. Their repository deps
    // (`MangaRepository` / `SourcesRepository`) stay LIVE for the rework feature VMs and other
    // repo consumers. The user-facing Home tab now renders the rework Home+Search surface
    // (`HomeReworkScreenRoute` → `:presentation` rework `HomeViewModel` + `SearchViewModel`),
    // committed in H5a (`5b09df0`).
    // Phase 9.x.downloadvmv2.componentprune (Task #411): narrowed from 2-arg `(get(), get())`
    // to 1-arg `(get())`. The duplicate `downloadRepo: DownloadRepository` ctor slot resolved
    // to the SAME `DownloadRepository` singleton as the first slot (same interface, no qualifier
    // on either side), so the second `get()` was a referentially-identical alias used by a
    // single internal call site (`onCancelChapterTapped`) which now routes through the first
    // slot. See `DownloadViewModelv2.kt` audit header for the full reacher-chain audit.
    //
    // Phase 9.x.downloadvmv2.retire (Task #439, 2026-05-28): binding retired alongside the VM.
    // The VM became a confirmed cascade orphan after Phase 9.x.libdetails.retire.5a (Task #435)
    // deleted `LibraryMangaScreenRoute.kt` — the VM's own KDoc at lines 23-25 (now-deleted)
    // self-described that route as its sole external consumer. The `koinViewModel()`
    // resolution site in `App.kt` (line ~290 of the pre-retire form) held the only other
    // reacher; that val was already unused post-Phase 9.x.library.swap (Task #346) and was
    // dropped together with this binding. `DownloadRepository` (the dep) stays LIVE for the
    // rework `:data DownloadsActionRepositoryImpl` strangler-fig and for other repo impls.
    // SettingsViewModel / LanguageViewModel / RefreshViewModel / ReaderViewModel SKIPPED in
    // Phase 9.5 — each pulls in Android-only platform APIs (Context+R.string+cacheDir for
    // SettingsViewModel, AppCompatDelegate.setApplicationLocales for LanguageViewModel,
    // WorkManager/LiveData for RefreshViewModel, Coil3 Bitmap + Compose UI BitmapPainter for
    // ReaderViewModel). They need expect/actual or composeApp-side refactor before commonMain.

    // ---- ViewModels (Phase 9.8 / 10.x ports) ----
    // Epic H5b (Phase 9.x): the legacy `MangaViewModel` binding was retired alongside the legacy
    // Home/Search surface. It was sole-consumed by the now-deleted `HomeScreenRoute.kt`
    // (`koinViewModel()` at its line 165); no other reacher existed. Its repository deps stay LIVE.
    // Reader-convergence slice R5: the `SharedChaptersViewModel` binding was retired alongside the
    // legacy reader surface. Its only LIVE reachers were the legacy `ChapterImagesScreenRoute.kt`
    // (`sharedChaptersVm` param) + legacy `ReaderScreen.kt` (chapter-list flow); both deleted in R5
    // when the rework Reader (which doesn't route chapter lists through `SharedChaptersViewModel`)
    // took over `Screen.ChapterImagesFragment`. Its deps (LibraryRepository / SourcesRepository /
    // SavedStateHandle) stay LIVE for other consumers.
    // Phase 9.x.libdetails.retire.5c (2026-05-28): LibraryDetailsViewModel binding
    // retired. The legacy Screen.LibraryMangaDetails route was unreachable from any
    // caller nav site (3-pass audit, zero LIVE reachers), making the VM + its 10-file
    // UI subtree + the route key itself cascade-orphan. The legacy facade dep set the
    // VM used (LibraryRepository / DownloadRepository / SettingsRepository, all
    // tagged @Single in their own modules) is untouched — the deps stay LIVE for
    // their other consumers.

    // ---- ViewModels (Phase 9.7 ports) ----
    // Epic H5b (Phase 9.x): the legacy `ChaptersViewModel` binding was retired alongside the
    // legacy Home/Search surface. Its sole LIVE reacher was `HomeScreenRoute.onSaveToggle`'s
    // `getChaptersDataR(url)` call (`koinViewModel()` at the now-deleted HomeScreenRoute.kt:167);
    // no other reacher existed. Its `SourcesRepository` dep stays LIVE.
    // Reader-convergence slice R5: the legacy `:shared` `HistoryViewModel` binding was retired
    //   alongside the legacy reader surface. Its only LIVE reachers were the legacy
    //   `ChapterImagesScreenRoute.kt` (history insert) + legacy `ReaderScreen.kt` (markChapterAsRead
    //   / history insert), both deleted in R5. The rework History screen binds a separate
    //   `me.manga.kira.presentation.history.HistoryViewModel` via `historyReworkModule`, and the
    //   rework Reader records history through `RecordHistoryUseCase` / `MarkChapterReadRepository`
    //   (`:domain` + `:data`), not this VM. Its deps (HistoryRepository / MangaRepository /
    //   SettingsRepository) stay LIVE for their other consumers.
    // SettingsViewModel deps: SettingsRepository. (Context dropped — cache helpers are no-arg on the
    //   updated SettingsRepository.)
    // Legacy RefreshViewModel retired in Phase 9.x.refreshvm.retire — rework consumers go
    // through `LibraryRefreshRepository` / `LibraryRefreshRepositoryImpl` (`:domain` + `:data`)
    // which observes the `LibraryRefresh` background job via `BackgroundJobScheduler.observeJobState`
    // directly, with no intermediate VM. Constants (`REFRESH_WORK_NAME`, `LIBRARY_REFRESH_WORKER_CLASS`)
    // moved to the repository impl.
    // SettingsViewModel + WhatsNewViewModel bindings relocated to :composeApp
    // legacySharedViewModelsModule (strangler-fig Phase 5).

    // WhatsNewRemoteDataSource relocated to :data legacyDataModule (strangler-fig Phase 5).

    // Legacy MangaDerailsViewModel binding retired in Phase 9.x.mangadetails.retire (Slice 5b).
    // The rework Details surface (DetailsViewModel, bound in DetailsReworkModule) replaces it via
    // the URL-only entry shape Screen.MangaDetails(mangaUrl, api) → MangaDetailsByUrlReworkScreenRoute.
    // Reader-convergence slice R5: the legacy `:shared` `ReaderViewModel` binding was retired
    //   alongside the legacy reader surface. Its only LIVE reacher was the legacy
    //   `ChapterImagesScreenRoute.kt` / `ReaderScreen.kt` pair, both deleted in R5. The rework
    //   Reader binds `me.manga.kira.presentation.reader.ReaderViewModel` via `readerReworkModule`
    //   on both `Screen.ChapterImagesFragment` (by-legacy-args adapter) and `Screen.ChapterImagesRework`.
    //   The VM's deps (LibraryRepository / SourcesRepository / SettingsRepository / StatisticsRepository
    //   / CbzReader) stay LIVE for their other consumers.
}

/**
 * Convenience: all KMP-portable common bindings exposed as a single list, so the host can do
 * `startKoin { modules(allSharedModules() + platformModule()) }`.
 */
fun allSharedModules(): List<Module> = listOf(sharedModule, databaseModule(), remoteModule(), sourcePersistenceModule())

/**
 * **Audit-trail postscript** (Phase 9.x.cluster172.staleKdocSweep.cascade,
 * Task #629, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-forty-first sibling of the cluster57-171
 * sweep — single-leaf file of the wave-42 commonMain shared-module batch;
 * SOLE commonMain SharedModule file 1/1 — closes the shared/{android,ios,desktop,common}Main/di/ tier
 * sweep entirely; together with cluster168 (KoinHelper.kt), clusters 169-
 * 170 (PlatformModule.{ios,android,desktop}.kt 3-actual fan), and cluster
 * 171 (PlatformModule.kt expect-decl + KoinInitializer.kt) this finishes
 * the 6-file shared/{android,ios,desktop,common}Main/di/ tier FULLY SWEPT).
 *
 * **Scope note**: this file already carries dense INLINE §-numbered audit-
 * trail markers AT EACH viewModel { ... } binding (lines 309-358 cluster
 * around the webviewvm/homevm/downloadvmv2 component-prune + retire
 * cascades; lines 375-381 around the libdetails retire; lines 415-418
 * around the mangadetails retire). Those inline markers are self-
 * contained Task-#N + date-stamped audit layers and are NOT re-classified
 * here — they ARE the §253-equivalent audit trail for the per-binding
 * post-prune/retire state. This postscript classifies ONLY the
 * top-level module KDoc paragraphs (the educational Hilt→Koin port
 * documentation and structural module-graph rationale) plus the trailing
 * allSharedModules() convenience-wrapper KDoc — none of which carry
 * inline §-markers and which therefore warrant explicit classification.
 *
 *  (a) Top-KDoc "Common-Koin-module-registers-everything-that-is-platform-
 *  independent-and-has-already-been-moved-into-commonMain + Phase-8-12-
 *  expands-this-from-just-the-complaint-use-cases-to-the-full-common-
 *  surface-the-Ktor-HttpClient-assembled-by-per-platform-createHttpClient-
 *  actuals-but-instantiated-here-so-it-is-a-process-wide-singleton-the-
 *  ApiClient-wrapper-the-50-plus-per-source-repositories-and-supporting-
 *  helpers-like-ManhastroDadosStore" — LIVE-NOT-STALE (verified: body
 *  lines 127-128 ship `single { createHttpClient() }` + `single { ApiClient
 *  (get()) }` as singletons; lines 132-194 ship 47 LIVE source-repository
 *  factory bindings across 12 language tiers; line 181 ships `single {
 *  ManhastroDadosStore() }` as the per-process cache the prose names.
 *  The Phase 8.12 expansion narrative IS the historical landing event;
 *  the "platform-independent and has already been moved into commonMain"
 *  scope assertion remains accurate). (b) Top-KDoc "Hilt-Koin-mapping-
 *  highlights + provideHttpClient-single-createHttpClient + provideApi
 *  Client-single-ApiClient + provideXxxRepository-factory-XxxRepository +
 *  provide-UseCase-factory-UseCase" — LIVE-NOT-STALE (all four mapping
 *  patterns verified against the current body: HttpClient via single,
 *  ApiClient via single, per-source repos via factory with 3-getter
 *  parameter shape uniform across all 47 LIVE bindings, complaint use-
 *  cases via factory at lines 295-299. The educational Hilt→Koin rosetta-
 *  stone is correct and remains load-bearing for future port understanding).
 *  (c) Top-KDoc "Repositories-are-bound-as-factory-not-single-to-mirror-
 *  upstream-per-call-lifecycle-repository-instances-are-stateless-wrappers-
 *  around-the-shared-ApiClient-and-DataStoreHelper-and-the-source-app-re-
 *  injected-them-into-each-ViewModel" — LIVE-NOT-STALE (grep-verified:
 *  every line in the 132-194 range starts with `factory {` not `single {`
 *  — the factory-not-singleton rationale matches the binding scope on
 *  every source-repo line. ManhastroDadosStore at line 181 IS the
 *  exception singleton precisely because it caches data across calls,
 *  honoring the documented exception-via-stateful-cache pattern). (d)
 *  Top-KDoc "Why-not-bind-every-Comick-MangaPark-language-variant-here +
 *  the-plan-s-52-repo-list-included-several-files-that-are-intentionally-
 *  empty-in-commonMain + Binding-a-non-existent-class-would-not-compile +
 *  the-disabled-variants-Comick-Ar-Es-Id-It-PtBr-Ru-Tr-MangaPark-It-
 *  ReadComicOnline-intentionally-omit-their-factory-lines + They-will-be-
 *  wired-in-the-same-migration-step-that-uncomments-their-bodies" —
 *  LIVE-NOT-STALE (verified inline at body lines 155 (ReadComicOnline
 *  disabled-stub note), 159 (Comick ES/AR variants), 171 (ComickId), 175
 *  (ComickIt + MangaParkIt), 178 (ComickPtBr), 186 (ComickRu), 191
 *  (ComickTr). The disabled-stub enumeration matches the prose-listed
 *  set exactly. The forecast "they will be wired in the same migration
 *  step that uncomments their bodies" remains FORWARD-LOOKING — no
 *  uncommenting has occurred yet; the stubs persist). (e) Top-KDoc
 *  "BaseMangaRepository-itself-is-not-bound-the-params-driven-dispatch-
 *  on-MangaSource-lives-in-Phase-9-a-MangaRepositoryProvider-ViewModel-
 *  scoped-helper-that-picks-the-right-concrete-repo-per-source" —
 *  FACTUALLY-DRIFTED-IN-PROSE-ONLY (the architectural rationale is
 *  correct — `BaseMangaRepository` itself is not bound as a type; rather
 *  a `Set<BaseMangaRepository>` IS bound at lines 234-289 + an
 *  `ActiveRepoProvider(set, get())` at line 292 IS the picker — but the
 *  prose names the picker class as `MangaRepositoryProvider` when the
 *  shipped class is `ActiveRepoProvider` (per the `import me.manga.
 *  yamiapk.di.sources.provider.ActiveRepoProvider` at line 9 + the LIVE
 *  binding at line 292). The Phase 9 dispatch mechanism shipped, but
 *  under a renamed class — prose-only drift, no architectural-fact
 *  divergence; the `Set<BaseMangaRepository>` + concrete-type consumer
 *  pattern documented IS the shipped reality). (f) Top-KDoc "Bindings-
 *  that-need-Context-Activity-DataStoreHelper-SettingsFactory-AppDatabase-
 *  SourcesDao-the-18-expect-actual-facades-live-in-platformModule-and-
 *  join-the-graph-at-runtime" — LIVE-NOT-STALE (cluster169-170 sweep
 *  verified: PlatformModule.android.kt binds Context-needing
 *  DataStoreHelper + SettingsFactory + AppDatabase + SourcesDao + ~18
 *  expect/actual platform facades; iOS/Desktop bind their actual
 *  counterparts of the same facade set. The cross-module split rationale
 *  documented here matches the present three-target-actual reality).
 *  (g) Trailing-KDoc "Convenience-all-KMP-portable-common-bindings-
 *  exposed-as-a-single-list-so-the-host-can-do-startKoin-modules-
 *  allSharedModules-plus-platformModule" — LIVE-NOT-STALE (verified:
 *  cluster171's KoinInitializer.kt body IS exactly `startKoin {
 *  appDeclaration(); modules(allSharedModules() + platformModule() +
 *  extraModules) }`; the documented host-side invocation pattern matches
 *  the shipped bootstrap entry call shape. The wrapper-as-list shape
 *  also lets the iOS-leg KoinHelperKt.doInitKoin (cluster168) and the
 *  iOS host bootstrapIosKoin (cluster166) compose the same allShared
 *  Modules() result alongside extras in identical concatenation order).
 *
 * Verified: val sharedModule: Module = module { ... } shipped with the
 * binding inventory described above; fun allSharedModules(): List<Module>
 * = listOf(sharedModule) shipped as the documented convenience wrapper.
 * Sibling: PlatformModule.kt + KoinInitializer.kt (cluster171's 2-leaf
 * batch — the expect-decl + entry-point counterparts of this module-list
 * file). SOLE FILE of the cluster172 commonMain shared-module 1-leaf
 * cluster (1 of 1). Seven classifications (six top-KDoc + one trailing-
 * KDoc); one FACTUALLY-DRIFTED-IN-PROSE-ONLY (point (e) — ActiveRepoProvider
 * shipped; prose names MangaRepositoryProvider). Inline §-numbered audit-
 * trail markers at the viewModel { ... } binding sites are NOT re-classified
 * — they self-document via their own Task-#N + date stamps. Original
 * Phase 8.12 module-expansion + Hilt→Koin port rationale prose preserved
 * verbatim per the audit-trail-preservation convention.
 */
