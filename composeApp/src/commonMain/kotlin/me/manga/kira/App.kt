package me.manga.kira

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.KiraTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import me.manga.kira.presentation.common.componants.sources.RepoIconResolver
import me.manga.kira.ui.sources.LocalSourceIconResolver
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageResult
import coil3.request.maxBitmapSize
import coil3.size.Size
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.image.ImageDecoderRegistry
import me.manga.kira.presentation.common.componants.images.platformNetworkFetcherFactory
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources_repositry.common.BaseManga
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.manga.kira.platform.toast.ToastRelay
import me.manga.kira.core.logging.KermitLoggerAdapter
import me.manga.kira.core.storage.SharedPrefsHelper
import me.manga.kira.core.storage.StorageKeys
import kotlinx.coroutines.launch
import me.manga.kira.domain.usecase.downloads.ReconcileDownloadsUseCase
import me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase
import me.manga.kira.domain.usecase.analytics.LogAppOpenUseCase
import me.manga.kira.domain.usecase.sources.SyncSourceCatalogUseCase
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.runtime.ConfigHostTrust
import me.manga.kira.locale.LocalAppLocale
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.push.NotificationRouter
import me.manga.kira.navigation.push.PushDestination
import me.manga.kira.navigation.push.isHostTrustedFor
import me.manga.kira.navigation.push.toScreen
import me.manga.kira.admin.Admin
import me.manga.kira.navigation.routes.AboutReworkScreenRoute
import me.manga.kira.navigation.routes.AdminComplaintReworkScreenRoute
import me.manga.kira.navigation.routes.AdminComplaintScreenRoute
import me.manga.kira.navigation.routes.ChapterImagesByLegacyArgsReworkScreenRoute
import me.manga.kira.navigation.routes.ComplaintReworkScreenRoute
import me.manga.kira.navigation.routes.ComplaintScreenRoute
import me.manga.kira.navigation.routes.DownloadsReworkScreenRoute
import me.manga.kira.navigation.routes.HistoryScreenRoute
import me.manga.kira.navigation.routes.HomeReworkScreenRoute
import me.manga.kira.navigation.routes.LanguageReworkScreenRoute
import me.manga.kira.navigation.routes.ChapterImagesReworkScreenRoute
import me.manga.kira.navigation.routes.LibraryScreenRoute
import me.manga.kira.navigation.routes.MangaDetailsByUrlReworkScreenRoute
import me.manga.kira.navigation.routes.MangaDetailsReworkScreenRoute
import me.manga.kira.navigation.routes.RepoSettingsScreenRoute
import me.manga.kira.navigation.routes.SettingsRoute
import me.manga.kira.navigation.routes.SourcesScreenRoute
import me.manga.kira.navigation.routes.BackupReworkScreenRoute
import me.manga.kira.navigation.routes.StatisticsReworkScreenRoute
import me.manga.kira.navigation.routes.ThemeReworkScreenRoute
import me.manga.kira.navigation.routes.ThemeSelectionScreenRoute
import me.manga.kira.navigation.routes.WhatsNewReworkScreenRoute
import me.manga.kira.navigation.routes.UpdatesScreenRoute
import me.manga.kira.navigation.routes.WebViewScreenRoute
import me.manga.kira.navigation.routes.WelcomeScreenRoute
import me.manga.kira.navigation.routes.WhatsNewScreenRoute
import me.manga.kira.presentation.common.componants.BottomNavigationBar
import me.manga.kira.presentation.features.settings.ui.viewmodel.SettingsViewModel
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Cross-platform root composable. Each platform host (Android, iOS, Desktop) wires its entry
 * point into this composable.
 *
 * Phase 10.4 wiring — port of upstream `app/.../navigation/NavGraphV2.kt` + `MainActivity.MainScreen`:
 *  1. Hosts a Material 3 [Scaffold] with the [BottomNavigationBar] for the five primary tabs
 *     (Library / Updates / Home / History / Setting). The bar's visibility is driven by per-route
 *     `SideEffect { showBottomBar = ... }` calls inside each [composable] block — same pattern as
 *     upstream's `onBottomBarVisibleChange` callback.
 *  2. Chooses the start destination based on the `first_launch` boolean in [SharedPrefsHelper]
 *     (defaults to `true`, i.e. first-run users land on [Screen.Welcome]; subsequent launches go
 *     straight to [Screen.Library]). Persists nothing here — the Welcome→Theme→Sources→RepoSettings
 *     onboarding chain is responsible for flipping the flag (see `RepoSettingsScreenRoute.onFinish`).
 *  3. (Historical) The NavHost formerly owned NavHost-scoped shared dependencies — the legacy
 *     `SharedChaptersViewModel` (reader chapter list) and `DownloadViewModelv2` (global download
 *     queue). Both were retired (`DownloadViewModelv2` in §439; `SharedChaptersViewModel` in
 *     Reader-convergence slice R5 alongside the legacy reader surface). The NavHost no longer
 *     resolves any VM at its own scope; each `composable<...>` block resolves its own via Koin.
 *
 *  3b. Phase 9.x.libdetails.retire (2026-05-28): the legacy `Screen.LibraryMangaDetails` route was
 *      retired as a cascade orphan — the route-key constructor was never instantiated anywhere in
 *      the source tree (confirmed by 3-pass reacher audit). Its host adapter
 *      `LibraryMangaScreenRoute` + the entire `presentation/features/library_details/` UI subtree
 *      + the legacy `LibraryDetailsViewModel` + Koin binding were all transitively dead. See
 *      ARCHITECTURE.md §-libdetails.retire for the full audit chain.
 *  4. `BackHandler`: FULFILLED (was a Phase 10.x TODO) — a commonMain expect/actual seam now
 *     exists at `:ui`'s `me.manga.kira.ui.util.BackHandler` and is consumed by the Home / Search /
 *     Library screens (search-overlay close, selection-mode clear). Android's system back
 *     otherwise pops the back stack normally; the upstream's `BackHandler(enabled = false)` on
 *     Library was a no-op and stays dropped.
 *  5. `Screen.Sources` is wired (Phase 10.x) — `SourcesScreenRoute.kt` lives in
 *     `commonMain/.../navigation/routes/`. Onboarding flows Welcome → Theme → Sources → Library
 *     (3-step, native-parity). The `first_launch` flag is flipped to `false` inside
 *     `SourcesScreenRoute.onFinish` (the final step), which then navigates straight to Library.
 *     `Screen.RepoSettings` is now only the in-settings edit-sources entry (reached from Home).
 *  6. `Handle403Error`, `AdViewModel` plumbing, and the `HomeTabReselectedHandler` plumbing from
 *     the upstream's Home block are deferred — they live in their respective route hosts already
 *     (or are stubbed pending ad-network expect/actual). The NavHost remains a pure dispatcher.
 *  7. `HelpVideoDialog`, `WebViewDialog`, and any other transient dialogs the upstream surfaced
 *     from the NavGraph level are owned by individual route hosts (see Wave 2B Cluster E).
 *
 * Deltas vs upstream NavGraphV2:
 *  - `hiltViewModel()` → `koinViewModel()` everywhere. The four shared VMs the upstream named at
 *    NavGraph scope are reduced to the two whose state actually needs to be shared across
 *    destinations (`SharedChaptersViewModel`, `DownloadViewModelv2`); the rest (`WhatsNewViewModel`,
 *    `RepoSettingsViewModel`, `AdViewModel`) are now resolved inside their route hosts via Koin —
 *    same instance because the Koin `viewModel { … }` binding is process-scoped.
 *  - `PrefsDelegate(context = …, key = "first_launch", defaultValue = true)` → direct
 *    [SharedPrefsHelper.getBoolean] read at NavHost composition time. The key string is unchanged so
 *    existing installs round-trip without migration.
 *  - `R.string.*` references are absent here — the NavHost dispatches to route hosts which own
 *    their own `Res.string.*` lookups via compose-resources.
 *  - The reader `ReadingScreenRoute(sharedChaptersVm = sharedChaptersVm)` upstream call first
 *    became the legacy `ChapterImagesScreenRoute` (a NavHost-scoped `SharedChaptersViewModel` was
 *    passed in explicitly). Reader-convergence slice R5 retired that legacy route +
 *    `SharedChaptersViewModel` entirely; `Screen.ChapterImagesFragment` now hosts the rework Reader
 *    via [ChapterImagesByLegacyArgsReworkScreenRoute], which resolves the rework `ReaderViewModel`
 *    via Koin and does not route chapter lists through any shared chapter-list VM.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster93.staleKdocSweep.cascade,
 * Task #549, 2026-05-28): the 7-item Phase 10.4 wiring manifest
 * plus the embedded §286-287 sub-postscript plus the 4-bullet
 * Deltas-vs-NavGraphV2 section plus the L143-170 sibling KDoc on
 * the [CoilSourceHeaderInterceptor] (Bug 4 layer 3 strategy) are
 * jointly classified as follows after recursive symbol verification
 * across the KMP graph (thirty-fifth sibling of the cluster57-92
 * sweep — last file visited in the `composeApp` root cluster,
 * structurally distinct as the cross-platform NavGraphV2 host plus
 * singleton-ImageLoader factory plus Bug-4-layer-3 source-header
 * Coil interceptor):
 *  (a) Item 1 — Material 3 Scaffold + BottomNavigationBar for five
 *  primary tabs — LIVE-NOT-STALE. L13 plus L15 plus L80 imports
 *  (`material3.HorizontalDivider` plus `Scaffold` plus
 *  `presentation.common.componants.BottomNavigationBar`) plus L248-
 *  274 (`Scaffold` plus `BottomNavigationBar(navController)`) plus
 *  per-route `SideEffect { onBottomBarVisibleChange(...) }` calls.
 *  (b) Item 2 — start destination from `first_launch` SharedPrefs
 *  flag — LIVE-NOT-STALE. L300-301 (`val firstLaunch = remember {
 *  prefs.getBoolean("first_launch", true) }` plus `val rootStart:
 *  Screen = if (firstLaunch) Screen.Welcome else Screen.Library`).
 *  (c) Item 3 — NavHost-scoped shared deps `[SharedChaptersView-
 *  Model]` plus `[DownloadViewModelv2]` — MIXED-with-inherited-
 *  staleness. `[SharedChaptersViewModel]` is LIVE-NOT-STALE
 *  (L81 import plus L294 `koinViewModel()`). `[DownloadViewModelv2]`
 *  is STALE-SYMBOL-REFERENCE (retired in Phase 9.x.downloadvmv2.
 *  retire §439); this is already partially documented in the
 *  inline sub-postscript at L288-294. Per §253 audit-trail-
 *  preservation convention the stale reference inside Item 3 prose
 *  stands as historical record without rewriting the original
 *  manifest.
 *  (d) Sub-Item 3b — `Phase 9.x.libdetails.retire (2026-05-28)`
 *  inline sub-postscript at L106-111 — LIVE-NOT-STALE. The §-
 *  libdetails.retire ARCHITECTURE.md cite documents the
 *  `Screen.LibraryMangaDetails` cascade-orphan retire (route key
 *  plus host adapter plus VM plus Koin binding).
 *  (e) Item 4 — `BackHandler` dropped, TODO Phase 10.x commonMain
 *  expect/actual — FORECAST-NOT-YET-FULFILLED. Recursive Grep for
 *  `BackHandler` across `:composeApp/commonMain` matches ZERO live
 *  references file-wide; the upstream's `BackHandler(enabled =
 *  false)` no-op holds as documented.
 *  (f) Item 5 — `Screen.Sources` wired with onboarding chain
 *  Welcome → Theme → Sources → Library — LIVE-NOT-STALE. L68-69
 *  imports (`SourcesReworkScreenRoute` plus `SourcesScreenRoute`)
 *  plus L77 `WelcomeScreenRoute` confirm the onboarding chain hosts
 *  are wired. The `first_launch` flag flip rationale stands.
 *  (g) Item 6 — Handle403Error plus AdViewModel plus HomeTab-
 *  ReselectedHandler deferred to route hosts — LIVE-NOT-STALE
 *  design rationale. Cluster44 already verified the HomeTab-
 *  Reselected wiring; the deferred items live in their respective
 *  route hosts (or are stubbed pending ad-network expect/actual).
 *  (h) Item 7 — HelpVideoDialog plus WebViewDialog owned by route
 *  hosts — LIVE-NOT-STALE. Cluster53 already verified HelpVideoDialog
 *  LIVE at `presentation/features/home/ui/components/HelpVideoDialog.
 *  kt`; the WebViewDialog reference is the inline interstitial-style
 *  legacy 403-handler that lives in the route-host cluster.
 *  (i) Delta bullet 1 — hiltViewModel rename to koinViewModel —
 *  FULFILLED-PREDICTION. Recursive Grep for `hiltViewModel` matches
 *  ZERO live references file-wide; L84 (`org.koin.compose.
 *  viewmodel.koinViewModel`) plus L83 (`org.koin.compose.koinInject`)
 *  are the live Koin entry points.
 *  (j) Delta bullet 2 — PrefsDelegate rename to SharedPrefsHelper.
 *  getBoolean — FULFILLED-PREDICTION. L44 import (`core.storage.
 *  SharedPrefsHelper`) plus L282 (`val prefs: SharedPrefsHelper =
 *  koinInject()`) plus L300 (`prefs.getBoolean("first_launch",
 *  true)`). Recursive Grep for `PrefsDelegate` matches ZERO live
 *  references file-wide.
 *  (k) Delta bullet 3 — `R.string.` references absent — LIVE-NOT-
 *  STALE. Recursive Grep for `R\.string` across this file matches
 *  ZERO live references; route hosts own their `Res.string.` lookups.
 *  (l) Delta bullet 4 — ReadingScreenRoute rename to ChapterImages-
 *  ScreenRoute — FULFILLED-PREDICTION. L50 import (`navigation.
 *  routes.ChapterImagesScreenRoute`) plus L61 (`ChapterImagesRework-
 *  ScreenRoute`). Recursive Grep for `ReadingScreenRoute` matches
 *  ZERO live references file-wide.
 *  (m) L143-170 sibling KDoc on [CoilSourceHeaderInterceptor] (Bug
 *  4 layer 3 strategy) — LIVE-NOT-STALE. 4-strategy-step prose:
 *  (1) extract image URL host — L177 (`Url(urlString).host.
 *  lowercase()`); (2) walk SourcesRepository.repos plus apex/sub-
 *  domain match — L179 (`sourcesRepository.findRepoByHost(host)`);
 *  (3) pull defaultHeaders skipping empty — L184-187; (4) convert
 *  to Coil 3 NetworkHeaders plus `withRequest(...).proceed()` —
 *  L189-194. The `[Headers]` plus `[CoilDbg]` println debug-output
 *  rationale at L168-169 holds (Kermit not on `composeApp/commonMain`
 *  classpath — cumulative auto-memory cite
 *  project_yami_okhttp_fetcher.md per cluster81's PlatformNetwork-
 *  Fetcher classification).
 *  Eight LIVE-NOT-STALE classifications plus four FULFILLED-
 *  PREDICTION classifications plus one MIXED-with-inherited-staleness
 *  classification plus one FORECAST-NOT-YET-FULFILLED classification
 *  STAND on their own merits as a faithful App.kt cross-platform
 *  NavHost-plus-singleton-ImageLoader-plus-Bug-4-layer-3-Coil-
 *  interceptor manifest. Original Phase 10.4-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
/**
 * Bug 4 layer 3: per-source header injection for Coil image fetches. The diagnostic version of
 * this interceptor (which only printed `[CoilDbg] httpHeaders=NetworkHeaders(data={})`) confirmed
 * that the singleton ImageLoader was attaching nothing to outbound image requests, so covers and
 * chapter pages got 403 from Cloudflare-protected hosts even after Layer 2 made the *site* HTML
 * fetch succeed.
 *
 * Strategy:
 *  1. Extract the image URL's host.
 *  2. Walk every registered [BaseMangaRepository] (from [SourcesRepository.repos]) and find the
 *     one whose `baseUrl` / `BASE_URL` / `imgBaseUrl` resolves to the same host, or to a parent
 *     domain (apex `lavascans.com` should match image host `cdn.lavascans.com`, etc.). Apex,
 *     leading-dot, and any-subdomain matches all count — same scheme the Desktop cookie capture
 *     uses, kept consistent on purpose.
 *  3. Pull `defaultHeaders` from the matched repo. By the time an image is laid out, the user has
 *     navigated through a flow that ran `fetchDataWithHeaders` → `ensureSiteInitialized()`, so the
 *     repo's `_cachedHeaders` is populated with the WebView-captured Cookie+User-Agent on top of
 *     its static defaults (Referer). If we land on a repo with empty defaultHeaders (e.g. user is
 *     on the home screen before any source-specific request), we skip injection rather than emit
 *     half a header set.
 *  4. Convert the `Map<String, String>` into Coil 3's [NetworkHeaders], attach via the
 *     `httpHeaders` extension on the [ImageRequest.Builder], and forward the new request through
 *     `chain.withRequest(...).proceed()`. We deliberately use `withRequest` rather than the
 *     experimental `proceed(request)` so we don't pull in `@ExperimentalCoilApi`.
 *
 * GAP-SHELL-04 (P3 cleanup): the former per-request `[CoilDbg]` `println` diagnostics (no-match /
 * empty-headers / injected-keys) were removed — they fired on every image load in production. The
 * header-injection logic itself is untouched. No logging facade replaces them (Kermit isn't on
 * `composeApp/commonMain`'s classpath); re-add temporary `println`s locally if this path needs
 * debugging again.
 */
private class CoilSourceHeaderInterceptor(
    private val sourcesRepository: SourcesRepository,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val req = chain.request
        // xcut-dup-9: request-level header attachment is authoritative. When the call site already
        // attached non-empty NetworkHeaders (rememberSourceImageRequest passes the fetch-time
        // Page.headers / per-api hydrated headers, which for the generic-config-backed sources can differ
        // from the matched legacy repo's static defaultHeaders), don't clobber them — proceed as-is.
        if (req.httpHeaders.asMap().isNotEmpty()) {
            return chain.proceed()
        }
        val urlString = req.data as? String ?: req.data.toString()
        val host = runCatching { Url(urlString).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }
        if (host == null) return chain.proceed()
        // GAP-SHELL-04 (P3 cleanup): the per-request `[CoilDbg]` stdout `println`s (no-match / empty-
        // headers / injected-keys) are removed — they fired on every image load in production. The
        // header-injection logic itself is load-bearing (Bug-4-layer-3) and is preserved unchanged.
        val match = sourcesRepository.findRepoByHost(host)
        if (match == null) {
            return chain.proceed()
        }
        var headers = match.defaultHeaders
        if (headers.isEmpty()) {
            // r5-cf-1: the matched repo hasn't been hydrated this session (its `_cachedHeaders` is
            // still null). For the generic-config-backed sources the feed/details fetch is served by
            // GenericSourceClient straight from the DataStore header store and never runs the legacy
            // `initSite()`, so plain-URL cover/search/details image loads would otherwise go out with
            // no Cookie/User-Agent and 403 on Cloudflare-gated config-backed sources even after the user solved the
            // challenge. Lazily hydrate via the idempotent, session-cached `ensureSiteInitialized()`
            // (same path rememberSourceImageRequest(url, api) uses) and re-read the headers.
            (match as? BaseManga)?.let { repo ->
                try {
                    repo.ensureSiteInitialized()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // headers stay empty; raw-URL fallback below
                }
            }
            headers = match.defaultHeaders
            if (headers.isEmpty()) {
                return chain.proceed()
            }
        }
        val networkHeaders = NetworkHeaders.Builder().apply {
            headers.forEach { (k, v) -> add(k, v) }
        }.build()
        val newReq = req.newBuilder().httpHeaders(networkHeaders).build()
        return chain.withRequest(newReq).proceed()
    }
}

@Composable
fun App() {
    // Wire the singleton ImageLoader so every AsyncImage / SubcomposeAsyncImage call site picks up
    // the per-source header injection without changes at the call sites. The factory runs once and
    // the resulting ImageLoader is memoized inside `SingletonImageLoader.setSafe`, so resolving
    // `SourcesRepository` via Koin here is safe (same singleton instance as everywhere else).
    val sourcesRepository: SourcesRepository = koinInject()

    // Restart-freeze fix (2026-06-02): on every launch, reconcile downloads orphaned in
    // RUNNING / COMPRESSING by a previous (killed) process — reset them to QUEUED and re-trigger the
    // engine so they resume instead of staying stuck "downloading" forever — and back-fill the size
    // of completed rows that pre-date the sizeBytes column. This single common seam covers
    // Android / iOS / Desktop identically (each legacy impl re-triggers via its own mechanism).
    // Best-effort like refreshSources(): the use case returns an AppResult/Result and never throws
    // except on cancellation, so a failure can never block launch.
    val reconcileDownloads: ReconcileDownloadsUseCase = koinInject()
    // #11: native-parity app_open analytics event. The cross-platform use case fires the event via
    // the :domain AnalyticsPort (Firebase on Android, no-op on iOS/Desktop) — same one-shot launch
    // seam as refreshSources/reconcileDownloads.
    val logAppOpen: LogAppOpenUseCase = koinInject()
    // Sources Migration Phase 1: the generic-sources config refresh seam. With remote delivery still
    // disabled this re-resolves the bundled+cached document (a near-no-op today), but it establishes
    // the startup entry point Phase 2 (config-driven catalog seed + baseUrl migration) and Phase 5
    // (signed remote fetch) hang off. Best-effort/own-coroutine like the others — never blocks launch.
    val sourceUpdateManager: SourceUpdateManager = koinInject()
    // Sources Migration Phase 2: config-driven catalog sync. Seeds config-backed sources into the
    // `sources` table and migrates stored URLs when a source's config.baseUrl (the trusted value)
    // changed. Best-effort/own-coroutine like the others — never blocks launch.
    val syncSourceCatalog: SyncSourceCatalogUseCase = koinInject()
    LaunchedEffect(Unit) {
        // ONCE PER PROCESS, not per composition: on Android an Activity recreation (rotation)
        // rebuilds App() and re-fires LaunchedEffect(Unit); without [StartupTasksOnce] the
        // reconciler below reset ACTIVELY-downloading rows to QUEUED mid-download and app_open
        // fired once per rotation (2026-07 audit). See the guard's KDoc.
        if (!StartupTasksOnce.claim()) return@LaunchedEffect
        // Independent one-shot startup tasks, each in its own child coroutine so none blocks the
        // others. reconcileDownloads() is pure local Room work to un-freeze interrupted downloads
        // — it must never wait on any network round-trip, or a stuck "downloading" row would stay
        // visibly frozen. (The legacy remote source-registry refresh that used to launch here was
        // retired in SourceRegistry retirement Phase 6 — the bundled config document, refreshed +
        // synced below, is the single authority for source metadata/lifecycle.)
        launch { reconcileDownloads() }
        // Refresh the config doc, then sync the catalog from it (seed + baseUrl migration). Sequenced
        // so the sync reads the freshest active document; both are no-throw best-effort.
        launch {
            sourceUpdateManager.refresh()
            syncSourceCatalog()
            // Zero generic stanzas after refresh = the bundled document was rejected wholesale
            // (validation is all-or-nothing) or is empty — every config-backed source is gone and
            // Home degrades to its error pane. The per-reason detail is logged at rejection time by
            // the manager's onDocumentRejected hook (SourcesGenericModule); this is the aggregate
            // startup alarm (2026-07 source-lifecycle hardening).
            if (sourceUpdateManager.activeDocument().sources.none { it.engine == "generic" }) {
                KermitLoggerAdapter().e(
                    "SourceConfig",
                    "startup: ZERO valid generic sources in the active config document — " +
                        "the source catalog is effectively empty",
                )
            }
        }
        // #11: fire app_open once per launch (synchronous, fast, best-effort telemetry).
        logAppOpen()
    }

    // Bug 5: register the AVIF decoder factory (on Android only) before the URL fetch interceptor so
    // chapter pages from Cloudflare-protected AVIF CDNs decode at full quality. iOS / Desktop
    // registries return empty lists, so this is a no-op there.
    val imageDecoderRegistry: ImageDecoderRegistry = koinInject()
    val decoderFactories = remember { imageDecoderRegistry.registerAll() }
    val networkFetcherFactory = remember { platformNetworkFetcherFactory() }
    // r5-mem-1: root the Coil disk cache under the app's OWN cache dir on every platform. Coil's
    // default singletonDiskCache() lives in FileSystem.SYSTEM_TEMPORARY_DIRECTORY, which on iOS is
    // the sandbox tmp/ dir and on Desktop the machine-wide $TMPDIR — neither is the dir the Settings
    // cache size/clear feature walks (AppFileSystem.cacheDir), so up to 250MB of cached images were
    // invisible to "cache size" and unclearable by "Clear cache" there (and on Desktop the temp dir
    // was shared across processes). Pinning it to `cacheDir/image_cache` makes Settings cover the
    // image cache and gives Desktop a per-user, per-app directory.
    val appFileSystem: AppFileSystem = koinInject()
    val imageCacheDir = remember { appFileSystem.cacheDir / "image_cache" }
    setSingletonImageLoaderFactory { ctx ->
        ImageLoader.Builder(ctx)
            .components {
                // Native pipeline order: NetworkFetcher first, then decoders, then interceptors.
                // On Android we force OkHttp (matches upstream `CoilModule.provideImageLoader`);
                // on Desktop/iOS the actual returns a ktor3 fetcher whose HttpClient carries the
                // page-progress observer and the 30s/60s timeouts (no whole-request ceiling).
                networkFetcherFactory?.let { add(it) }
                decoderFactories.forEach { add(it) }
                add(CoilSourceHeaderInterceptor(sourcesRepository))
            }
            .diskCache { DiskCache.Builder().directory(imageCacheDir).build() }
            // Disable the default 4096×4096 maxBitmapSize cap. Coil 3.3+ tightened how this cap
            // applies to tall manga / webtoon pages (PR #3259), causing aggressive downsampling
            // before the request's `.size(Pixels(screenWidthPx), Undefined)` constraint is honored.
            // Native (3.1.0) didn't have this behavior, so chapter pages there decoded at the
            // requested width regardless of height. Setting Size.ORIGINAL here removes the
            // image-loader-level cap entirely and lets the per-request size be the only constraint.
            .maxBitmapSize(Size.ORIGINAL)
            .build()
    }

    val settingsViewModel: SettingsViewModel = koinViewModel()
    val followSystem by settingsViewModel.followSystem.collectAsState()
    val darkMode by settingsViewModel.darkMode.collectAsState()
    val pureBlack by settingsViewModel.pureBlack.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = if (followSystem) systemDark else darkMode
    // GAP-THEME-01: app root binds the rework `:ui` design-system [KiraTheme] directly (was the
    // legacy `composeApp` `KiraMangaTheme` wrapper, which is itself a thin alias over this same
    // composable — so the swap is visually inert: KiraColors/kiraTypography()/KiraShapes + the
    // `pureBlack` Color.Black override are byte-for-byte identical, and `LocalSpacing` is now
    // provided at the root for the whole tree. Leaf `:ui` screens that re-wrap in `KiraTheme` stay
    // idempotent — they re-provide the same scheme/spacing, no double-theming artifact.
    // Native parity (MainActivity.onCreate -> KiraMangaTheme { ... MainScreen() }): the theme wraps
    // the Scaffold directly, with NO enclosing full-screen Surface. The previous KMP code added a
    // `Surface(Modifier.fillMaxSize())` whose default container color is `colorScheme.surface`,
    // which differs from the Scaffold's own `containerColor` default of `colorScheme.background`
    // (and from native's window background). Dropping the Surface lets the Scaffold paint the
    // background exactly as native does — the M3 Scaffold already fills the window and supplies its
    // own background container, so no surface layer is lost.
    //
    // INTENTIONALLY DROPPED — native's `Admin.testingMode` root branch (MainActivity.onCreate:
    // `if (Admin.testingMode) ApiTestScreen(api, context, scope) else MainScreen()`). The KMP root
    // unconditionally renders [MainScreen]. `Admin.testingMode` itself was ported (it lives in
    // `:shared` me.manga.kira.admin.Admin and the Settings hub reads/flips it), but the
    // `ApiTestScreen` admin API-test harness was deliberately NOT ported — it is a developer-only
    // network-probe surface (raw Retrofit calls + share-to-file) with no equivalent in the rework
    // `:ui` graph, and porting it would require a new `:ui`/`:shared` screen out of this slice's
    // scope. If the harness is wanted on KMP later, add an `Admin.testingMode` branch here (or a
    // debug-only nav entry) once a ported ApiTestScreen exists.
    // App-language override (GAP-LANG): selecting a language persisted the code, but compose-resources
    // kept resolving the system locale on iOS/Desktop (the per-platform LocaleSwitcher is a no-op
    // there), so the UI never switched language. Provide the chosen language at the root via
    // [LocalAppLocale] and `key` the content on it so every `stringResource` re-resolves in that
    // language across all platforms. The flow's first emission is the persisted code (or "" → system
    // default); the startup key flip is invisible (the NavHost is still at its start destination). A
    // mid-session change rebuilds the tree (returns to the start destination) — acceptable for a rare
    // settings action and analogous to native's activity recreate.
    val observeLanguage: ObserveSelectedLanguageUseCase = koinInject()
    // Wait for the persisted language's FIRST emission before building the root `key(language)` tree
    // below, so the key is created ONCE with the real code instead of flipping ""→"<code>" on the
    // first frame. That flip disposed and recreated MainScreen (and its NavController), discarding a
    // push deep-link the first composition had already navigated to (#1). `null` = not yet read; ""
    // = read, system default. The DataStore-backed flow always emits promptly and the splash is still
    // up on Android, so the wait is imperceptible (and it removes the old startup locale flash).
    val appLanguage: String? by remember { observeLanguage() }.collectAsState(initial = null)
    val language = appLanguage
    if (language == null) {
        KiraTheme(darkTheme = effectiveDark, pureBlack = pureBlack) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
        return
    }
    // RTL parity (GAP-LANG-RTL): on Android the LocalConfiguration locale override (LocalAppLocale)
    // makes Compose derive layout direction from the chosen locale, so picking Arabic flips the UI to
    // RTL there. iOS/Desktop have no such derivation, so also drive LocalLayoutDirection from the
    // selected language → Arabic (and other RTL codes) mirror the layout on every platform. Override
    // ONLY on an explicit selection; when blank (system default) keep the platform's current direction
    // so an Arabic *device* on Android still gets RTL without an in-app pick.
    //
    // Gate on isLiveLocaleSwitchSupported: on a platform where the locale can't move mid-session,
    // keep the platform's current direction so the layout doesn't flip ahead of the strings
    // (mirroring to RTL while every string stays in the old language). All three targets are live
    // today — iOS since PI2 (2026-07, AppleLanguages writes are process-visible; see
    // LocalAppLocale.ios.kt) — so the guard is dormant, but it stays: if the pinned iOS behavior
    // ever regresses, flipping the iOS actual back to false re-arms this without further changes.
    val layoutDirection = when {
        language.isBlank() || !LocalAppLocale.isLiveLocaleSwitchSupported -> LocalLayoutDirection.current
        isRtlLanguageTag(language) -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
    CompositionLocalProvider(
        LocalAppLocale provides language.ifBlank { null },
        LocalLayoutDirection provides layoutDirection,
        // Per-source brand-icon resolver for the Sources screens. The brand drawables live in
        // :composeApp's resources (out of :ui's reach), so :ui's SourceRow reads this resolver to
        // render a leading icon (native RepoToggleItem parity) without a :ui→:composeApp dependency.
        LocalSourceIconResolver provides { api -> RepoIconResolver.resolveByApi(api) },
    ) {
        key(language) {
            KiraTheme(darkTheme = effectiveDark, pureBlack = pureBlack) {
                MainScreen()
            }
        }
    }
}

/** BCP-47 language subtags that render right-to-left (used to mirror layout when a locale is picked). */
private val RTL_LANGUAGE_SUBTAGS = setOf("ar", "fa", "he", "iw", "ur", "ps", "sd", "ug", "yi", "dv")

private fun isRtlLanguageTag(tag: String): Boolean =
    RTL_LANGUAGE_SUBTAGS.contains(tag.substringBefore('-').substringBefore('_').lowercase())

// The #8 intent-redirection trust gate (`PushDestination.isHostTrustedFor`) lives in
// `navigation/push/PushDeepLinkTrust.kt` (extracted 2026-07 so the gate is unit-testable; it now
// also validates Reader.coverUrl).

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    var showBottomBar by remember { mutableStateOf(false) }
    // Full-bleed immersive routes (the reader) draw the page edge-to-edge under the system bars, so
    // they get NO root bottom inset (the screen re-applies its own chrome insets). Derived from the
    // current route so no per-route plumbing is needed — both reader routes contain "ChapterImages".
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val fullBleed = navBackStackEntry?.destination?.route?.contains("ChapterImages") == true

    // ---- Push-notification deep linking ----
    // A tapped push notification lands a PushDestination in the NotificationRouter (put there by the
    // Android MainActivity or the iOS AppDelegate bridge). Collect it here, navigate once, then consume
    // so an activity recreation (rotation) never replays it. Suppressed while onboarding — a brand-new
    // user finishes the wizard first (a null parse already means "just open the app normally", so
    // nothing to do). Note: FIAM in-app messages are NOT routed here — the SDK displays them itself.
    val notificationRouter: NotificationRouter = koinInject()
    val onboardingPrefs: SharedPrefsHelper = koinInject()
    val deepLinkSources: SourcesRepository = koinInject()
    val configHostTrust: ConfigHostTrust = koinInject()
    val pendingDeepLink by notificationRouter.pending.collectAsState()
    LaunchedEffect(pendingDeepLink) {
        val pending = pendingDeepLink ?: return@LaunchedEffect
        val destination = pending.destination
        // Read first_launch at event time — NOT a composition snapshot. Onboarding can complete
        // in-session (flipping the flag) while this composable stays composed, so a remembered value
        // would go stale and silently drop every post-onboarding deep link (#3). The pending value is
        // seq-tagged (NotificationRouter), so re-tapping the same destination always restarts this
        // effect instead of being conflated away (#10).
        val onboarding = onboardingPrefs.getBoolean(StorageKeys.FIRST_LAUNCH, true)
        // #8 (intent-redirection guard): MainActivity is an exported launcher, so a co-installed app
        // can start it with crafted extras. Require a manga/chapter deep link's URL to belong to its
        // own source's domain — else a forced Reader/Details nav would fetch an attacker URL through
        // the claimed source's client, attaching that source's stored Cookie/cf_clearance headers.
        if (!onboarding && destination.isHostTrustedFor(deepLinkSources, configHostTrust)) {
            // #9: navigate wrapped in runCatching (crash-safe, like safeNavigate — a stray/invalid
            // route can never crash the host) but deliberately WITHOUT safeNavigate's RESUMED
            // debounce. A cold-start tap runs this effect while the start entry may still be
            // STARTED-not-RESUMED; the debounce would then silently drop the nav while consume() below
            // still clears it, losing the deep link on the primary flow. The router already delivers
            // exactly once (seq + consume), so there is no double-nav to debounce. Tab destinations
            // reuse the bottom bar's single-instance options so a push doesn't stack duplicate
            // Home/Updates entries.
            runCatching {
                when (destination) {
                    PushDestination.Home, PushDestination.Updates ->
                        navController.navigate(destination.toScreen()) {
                            popUpTo(Screen.Library) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    else -> navController.navigate(destination.toScreen())
                }
            }
        }
        notificationRouter.consume()
    }

    // Firebase In-App Messaging campaigns are console-authored and auto-displayed by the SDK on every
    // screen (including the reader) — the app deliberately does not suppress them anywhere.

    // Visible toast surface for platforms with no native toast (iOS/Desktop). The platform
    // ToastShower posts to ToastRelay; Android uses android.widget.Toast and never posts here, so
    // this host stays inert on Android (no double toast).
    val snackbarHostState = remember { SnackbarHostState() }
    val toastScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        ToastRelay.events.collect { event ->
            // Dismiss the current toast and show the new one on a separate coroutine so the
            // collector keeps draining the relay (showSnackbar suspends for the whole display
            // duration; calling it inline would block the collector and, with the relay's bounded
            // buffer, silently drop a burst). Dismiss-then-show mirrors native Toast "latest wins".
            snackbarHostState.currentSnackbarData?.dismiss()
            toastScope.launch {
                snackbarHostState.showSnackbar(
                    message = event.message,
                    duration = if (event.long) SnackbarDuration.Long else SnackbarDuration.Short,
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // Only consume the bottom system bar inset (home indicator on iOS, nav bar on Android) so
        // the top status-bar inset stays unconsumed and flows down to each screen's Material 3
        // TopAppBar (whose default windowInsets = WindowInsets.statusBars). Without this, the outer
        // Scaffold consumed the top inset and inner TopAppBars rendered under the status bar /
        // Dynamic Island on iOS.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
    ) { paddingValues ->
        // Redesign 2026-06 — FLOATING bottom nav. The nav is intentionally NOT in the Scaffold
        // `bottomBar` slot: that slot reserves the capsule's full height as dead space above it (the
        // "content eaten by the nav / too much padding" problem). Instead the content fills the whole
        // window and the capsule is overlaid on top, so the feed scrolls edge-to-edge underneath it.
        // Each tab screen adds `LocalBottomBarPadding` to its scroll content's bottom `contentPadding`
        // so the last item still clears the capsule at rest (and stays reachable).
        val systemBottom = paddingValues.calculateBottomPadding()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Nav visible → full-bleed (capsule floats over content). Immersive reader →
                    // full-bleed (it owns its chrome insets). Other non-tab screens (Details/…) keep
                    // the system-bottom inset so they clear the home indicator exactly as before.
                    .padding(bottom = if (showBottomBar || fullBleed) 0.dp else systemBottom),
            ) {
                CompositionLocalProvider(
                    LocalBottomBarPadding provides
                        if (showBottomBar) systemBottom + FloatingNavBarSpace else 0.dp,
                ) {
                    AppNavHost(
                        navController = navController,
                        onBottomBarVisibleChange = { showBottomBar = it },
                    )
                }
            }
            if (showBottomBar) {
                BottomNavigationBar(
                    navController = navController,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/**
 * Vertical room the floating bottom-nav capsule occupies *above* the system navigation-bar inset
 * (capsule 66.dp + 10.dp top/bottom margins ≈ 76.dp, plus a ~12.dp breathing gap so the last list
 * item doesn't sit flush against the capsule). Added to the system inset to form [LocalBottomBarPadding].
 */
private val FloatingNavBarSpace = 88.dp

@Composable
private fun AppNavHost(
    navController: NavHostController,
    onBottomBarVisibleChange: (Boolean) -> Unit,
) {
    val prefs: SharedPrefsHelper = koinInject()

    // Epic H5b (Phase 9.x): the NavHost-scoped `sharedChaptersVm: SharedChaptersViewModel` val
    // (formerly resolved here via `koinViewModel()`) was retired alongside the legacy Home
    // surface. Reader-convergence slice R5 then retired `SharedChaptersViewModel` entirely along
    // with the legacy reader route + UI (its last reachers). The rework Reader doesn't route
    // chapter lists through any shared chapter-list VM. An earlier sibling val (`downloadViewModel:
    // DownloadViewModelv2`) was retired in Phase 9.x.downloadvmv2.retire (Task #439).

    // first_launch flag — read once at composition entry. Onboarding (Welcome → Theme → RepoSettings)
    // is responsible for flipping it to false via `RepoSettingsScreenRoute.onFinish`. Key string is
    // verbatim from upstream `MainActivity` / `PrefsDelegate` so existing installs round-trip
    // without migration.
    val firstLaunch = remember { prefs.getBoolean(StorageKeys.FIRST_LAUNCH, true) }
    val rootStart: Screen = if (firstLaunch) Screen.Welcome else Screen.Library

    NavHost(
        navController = navController,
        startDestination = rootStart,
    ) {

        composable<Screen.Welcome> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WelcomeScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.Theme> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ThemeSelectionScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.Sources> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            SourcesScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Epic H5a route-swap: the user-facing Home tab now renders the rework Home + Search
        // surface (`:ui/.../home/HomeScreen` + `:ui/.../search/SearchScreen`) via the Koin-bound
        // rework `HomeViewModel` + `SearchViewModel`, observed through `HomeReworkScreenRoute`
        // (overlay-swap on `HomeState.isSearching`; effects → Details/Reader/WebView/Sources). The
        // legacy `HomeScreenRoute` + its 4 `:shared` VMs (Manga/Home/Chapters/RepoSettings) and the
        // `sharedChaptersVm` chapter-list bridge are retired in H5b (the rework Reader doesn't route
        // through `SharedChaptersViewModel`, so the dropped bridge call is a no-op).
        composable<Screen.Home> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            HomeReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 9.x.library.swap (Task #346): user-facing Library route now renders the
        // rework `:ui/.../library/LibraryScreen` via the Koin-bound rework `LibraryViewModel`.
        // The adapter resolves both VMs internally (rework `LibraryViewModel` + the route-host
        // `WhatsNewViewModel` for the first-launch redirect orchestration). Legacy callbacks
        // (downloadViewModel / onOpenRandomClick / onLibraryMangaClick) are gone — random,
        // refresh, and delete are now internal to the rework VM/screen.
        composable<Screen.Library> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            LibraryScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.History> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            HistoryScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.Updates> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            UpdatesScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.MangaDetails> { backStackEntry ->
            // Phase 9.x.mangadetails.swap Slice 4 (ADR-6 / ADR-7): the legacy
            // `Screen.MangaDetails(mangaUrl, api)` route key is bound to the rework adapter via
            // its URL-only sibling host. The 4 legacy caller nav sites (Home, Library, History,
            // Updates) emit the same two-tuple Screen.MangaDetails(...) call as before — only
            // this composable<...> block flipped. The legacy MangaDetailsScreenRoute +
            // MangaDetailsScreen + DetailsContent + HeaderSection + ChapterItem composables were
            // retired in Slice 5a (Phase 9.x.mangadetails.retire); the legacy MangaDerailsViewModel
            // + its Koin binding + the legacy AdultConfirmationDialog/MConfirmationDialog dialogs
            // were retired in Slice 5b. ADR-8: the Screen.MangaDetails route key itself stays in
            // Screen.kt — deleting it would force every caller nav site to rewrite to the
            // full-tuple shape, exactly the work ADR-6 avoided.
            //
            // Chapter-row click navigates to the rework ChapterImages screen via the adapter's
            // onNavigateToReader callback (Slice 4 deliberately drops the legacy
            // sharedChaptersVm.setChaptersToReaderChaptersList call site — the rework Reader doesn't
            // route through sharedChaptersVm). Downloads click routes to Screen.DownloadsRework
            // (intentional UX change documented in Slice 2 ADR-3 / ADR-4); WebView click routes
            // to Screen.WebView (Slice 3 ADR-5).
            SideEffect { onBottomBarVisibleChange(false) }
            MangaDetailsByUrlReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.ChapterImagesFragment> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            // Reader-convergence slice R4 (ADR-8 route-swap): the user-facing
            // Screen.ChapterImagesFragment route key now hosts the REWORK Reader via the
            // by-legacy-args adapter, instead of the legacy `:shared` ChapterImagesScreenRoute.
            // The route key + all 3 caller sites (Home/History/Updates) are unchanged. Slice R5
            // then retired the legacy ChapterImagesScreenRoute file, the legacy reader UI subtree,
            // and the legacy `:shared` SharedChaptersViewModel / ReaderViewModel / HistoryViewModel
            // bindings (all route-orphaned once this adapter took over).
            ChapterImagesByLegacyArgsReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.Setting> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(true) }
            SettingsRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.WebView> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WebViewScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.RepoSettings> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            RepoSettingsScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Architecture-rework Downloads screen (Phase 7.x.downloads.foundation +
        // Phase 7.x.downloads.actions). Coexists with the legacy Screen.DownloadsScreen above —
        // both consume the same upstream DownloadRepository.observeAllDownloads() flow (the
        // rework :data impl is strangler-fig over it), so the list MUST agree across the two
        // routes for the same user data and add/cancel/state-transition events from either route
        // propagate to the other. User-reachable via the rework Settings hub's Downloads row
        // (Phase 7.x.downloads.actions swap — see
        // [me.manga.kira.navigation.routes.SettingsReworkScreenRoute]); the legacy
        // Screen.DownloadsScreen composable above remains bound for parity testing but is no
        // longer surfaced from any user-reachable entry — Phase 9.x route-swap retires it
        // completely once on-device parity is verified. The rework :ui re-adds the legacy's
        // mutation affordances (retry / cancel / delete / cancelRunning) via per-row buttons +
        // intent dispatch + snackbar effect on failure.
        // Bottom bar visibility false to mirror the legacy Downloads screen experience above.
        composable<Screen.DownloadsRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            DownloadsReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.Complaint> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ComplaintScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.WhatsNewScreen> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WhatsNewScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        composable<Screen.ComplaintAdmin> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            AdminComplaintScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 8.x — architecture-rework Manga Details route (debug-reachable).
        // Renders `:ui/.../details/DetailsScreen` backed by the rework `DetailsViewModel`
        // from `:presentation`, wired through `detailsReworkModule` (`:composeApp/commonMain/di/`).
        // Not surfaced in any user-facing entry yet — reachable via `navController.navigate(
        // Screen.MangaDetailsRework(...))` from a future developer trigger. The rework Library
        // route still navigates to the legacy `Screen.MangaDetails` for now; the swap to this
        // route lands in a later phase once Reader rework + image loading + parity actions arrive.
        // Bottom bar visibility false to mirror the legacy Details screen experience.
        composable<Screen.MangaDetailsRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            MangaDetailsReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 8.x.reader — architecture-rework Reader route (debug-reachable).
        // Renders `:ui/.../reader/ReaderScreen` backed by the rework `ReaderViewModel` from
        // `:presentation`, wired through `readerReworkModule` (`:composeApp/commonMain/di/`).
        // Reachable from the rework Details adapter's `onNavigateToReader` callback
        // (`MangaDetailsReworkScreenRoute`) — chapter taps from the rework Details screen
        // now route here instead of showing a placeholder toast. The legacy Details →
        // legacy Reader path is unchanged. Bottom bar visibility false to mirror the legacy
        // Reader screen experience (full-screen page list).
        composable<Screen.ChapterImagesRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ChapterImagesReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 7.x.statistics — architecture-rework Statistics route (debug-reachable).
        // Renders `:ui/.../statistics/StatisticsScreen` backed by the rework
        // `StatisticsViewModel` from `:presentation`, wired through `statisticsReworkModule`
        // (`:composeApp/commonMain/di/`). Coexists with `Screen.Statistics` (legacy) — both
        // routable simultaneously and consume the SAME legacy `StatisticsRepository` flows
        // under the hood (the rework `:data` impl is strangler-fig over the eight legacy
        // aggregates), so the numbers MUST agree across the two routes for the same user data.
        // Not surfaced in any user-facing entry yet — reachable via `navController.navigate(
        // Screen.StatisticsRework)` from a future developer trigger. Bottom bar visibility
        // false to mirror the legacy Statistics screen experience.
        composable<Screen.StatisticsRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            StatisticsReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Backup & restore (feature/backup): file export/import of the library or of a scoped
        // manga selection. Reached from Settings (full-library mode, scopeJson = "") and from
        // the Details top-bar / Library multi-select export actions (scoped mode). Bottom bar
        // hidden like the other Settings sub-screens.
        composable<Screen.BackupRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            BackupReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 7.x.theme — architecture-rework Theme picker route.
        // Renders `:ui/.../themepicker/ThemeScreen` backed by the rework `ThemeViewModel` from
        // `:presentation`, wired through `themeReworkModule` (`:composeApp/commonMain/di/`).
        // Coexists with `Screen.Theme` (legacy onboarding step) — both routable simultaneously
        // and consume the SAME `darkMode` + `followSystem` `SharedPreferences` booleans through
        // the legacy `SettingsRepository` facade (the rework `:data` impl is strangler-fig over
        // it), so toggling the theme in EITHER route flips the same two booleans and the change
        // propagates to the other screen via the upstream `darkModeFlow` / `followSystemFlow`
        // re-emit. The legacy `Screen.Theme` route stays bound to the onboarding flow's
        // `ThemeSelectionScreenRoute` (Welcome → Theme → Sources → RepoSettings → Library) with
        // its animated-background overlay + notification-permission grant chrome + `onContinue`
        // wizard advance; the rework slice is a standalone theme-picker surface, NOT part of an
        // onboarding wizard. Surfaced from the rework Settings hub — the Settings → Theme row
        // navigates here (`SettingsDestination.THEME → Screen.ThemeRework`); the adapter wires
        // an `onBack` pop for the screen's TopAppBar back arrow. Bottom bar visibility false to
        // mirror the legacy Theme onboarding step (which also hides the bottom bar).
        composable<Screen.ThemeRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ThemeReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Phase 7.x.about — architecture-rework About route (debug-reachable).
        // Renders `:ui/.../about/AboutScreen` backed by the rework `AboutViewModel` from
        // `:presentation`, wired through `aboutReworkModule` (`:composeApp/commonMain/di/`).
        // Coexists with `Screen.AboutScreen` (legacy `AboutScreenRoute`) — both routable
        // simultaneously and consume the SAME `versionName` + `packageName` from the legacy
        // `:shared` `AppVersionProvider` facade (the rework `:data` impl is strangler-fig over
        // it). The route adapter resolves a legacy `:shared` `IntentLauncher` via Koin and
        // bridges `AboutEffect.OpenPlayStorePage` / `AboutEffect.OpenUrl` emissions to it. The
        // legacy `Screen.AboutScreen` route stays bound to `AboutScreenRoute` with its
        // Whats-new + Source-code + SocialMediaRow surface; the rework slice is a reduced
        // version-and-actions surface (4 rows). Not surfaced in any user-facing entry yet —
        // reachable via `navController.navigate(Screen.AboutRework)` from a future developer
        // trigger. Bottom bar visibility false to mirror the legacy About route (which also
        // hides the bottom bar).
        composable<Screen.AboutRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            AboutReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Architecture-rework What's New screen (Phase 7.x.whatsnew foundation).
        // Renders `:ui/.../whatsnew/WhatsNewScreen` backed by the rework `WhatsNewViewModel`
        // from `:presentation`, wired through `whatsNewReworkModule` (`:composeApp/commonMain/
        // di/`). Coexists with `Screen.WhatsNewScreen` (legacy `WhatsNewScreenRoute`) — both
        // routable simultaneously and consume the SAME upstream `WhatsNewRemoteDataSource` +
        // `whats_new_last_shown_*` prefs keys via `SharedPrefsHelper` (the rework `:data` impl
        // is strangler-fig over four `:shared` collaborators). The legacy `Screen.WhatsNew
        // Screen` route stays bound to `WhatsNewScreenRoute` with its HorizontalPager + image/
        // video + fullscreen-viewer surface; the rework foundation slice is a reduced flat-list
        // surface (title + description + optional NEW chip) — image/video/pager/fullscreen all
        // defer to follow-on sub-slices. Not surfaced in any user-facing entry yet — reachable
        // via `navController.navigate(Screen.WhatsNewRework)` from a future developer trigger.
        // Bottom bar visibility false to mirror the legacy WhatsNew screen experience.
        composable<Screen.WhatsNewRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            WhatsNewReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Architecture-rework Language picker route (Phase 7.x.language). Hosts the rework
        // `LanguageViewModel` from `:presentation`, wired through `languageReworkModule`
        // (`:composeApp/commonMain/di/`). Coexists with `Screen.LanguageScreen` (legacy
        // `LanguageScreenRoute`) — both routable simultaneously and consume the SAME upstream
        // DataStore-backed pref via `SettingsRepository.languageFlow` + `setLanguage(code)`;
        // selecting a language in EITHER route writes the same IETF tag and triggers the same
        // `core.locale.applyApplicationLocale(tag)` side effect, with the other screen
        // reflecting the change reactively through the upstream flow re-emit. The legacy
        // `Screen.LanguageScreen` route stays bound to `LanguageScreenRoute` with its
        // FeedbackDialog-driven "Request a Language" surface (ComplaintViewModel + Snackbar
        // host); the rework foundation slice is a reduced row-list-only picker — the
        // FeedbackDialog/ComplaintViewModel/Snackbar cross-cutting integration defers to the
        // follow-on `Phase 7.x.language.request` sub-slice. Not surfaced in any user-facing
        // entry yet — reachable via `navController.navigate(Screen.LanguageRework)` from a
        // future developer trigger. Bottom bar visibility false to mirror the legacy Language
        // screen experience (line 506 sibling).
        composable<Screen.LanguageRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            LanguageReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Architecture-rework user-side Complaint LIST route (Phase 7.x.complaint.foundation —
        // "Feedback Manager" screen). Hosts the rework `ComplaintViewModel` from `:presentation`,
        // wired through `complaintReworkModule` (`:composeApp/commonMain/di/`). Coexists with
        // `Screen.Complaint` (legacy `ComplaintScreenRoute`) — both routable simultaneously and
        // consume the SAME upstream Firestore `complaints` collection via the legacy
        // `GetUserComplaintUseCase`; a submission via the legacy `ComplaintViewModel.sendComplaint`
        // or via the Request-Language slice's `LanguageViewModel` surfaces on BOTH list screens.
        // The legacy `Screen.Complaint` route stays bound to `ComplaintScreenRoute` with its
        // reply/edit/delete dialog surface and `ToastShower` feedback wiring; the rework
        // foundation slice is a reduced read-only LIST with search + status filter — the
        // reply/edit/delete + Snackbar cross-cutting integration defers to the follow-on
        // `Phase 7.x.complaint.actions` sub-slice. Not surfaced in any user-facing entry yet —
        // reachable via `navController.navigate(Screen.ComplaintRework)` from a future developer
        // trigger. Bottom bar visibility false to mirror the legacy Complaint screen experience
        // (line 531 sibling).
        composable<Screen.ComplaintRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            ComplaintReworkScreenRoute(
                navController = navController,
                backStackEntry = backStackEntry,
            )
        }

        // Architecture-rework admin Complaint dashboard route (Phase 7.x.complaint.admin —
        // "Admin Complaints" screen). Hosts the rework `AdminComplaintViewModel` from
        // `:presentation`, wired through `complaintAdminReworkModule` (`:composeApp/commonMain/
        // di/`). Coexists with `Screen.ComplaintAdmin` (legacy `AdminComplaintScreenRoute`) —
        // both routable simultaneously and consume the SAME upstream Firestore `complaints`
        // collection via the legacy `GetAllComplaintUseCase`; a user-side submission via the
        // legacy `ComplaintViewModel.sendComplaint` or via the Request-Language slice's
        // `LanguageViewModel` surfaces on BOTH admin LIST screens. The legacy
        // `Screen.ComplaintAdmin` route stays bound to `AdminComplaintScreenRoute` with its
        // 6 mutation dialogs + statistics card + sort dropdown + app-version filter + long-press
        // body-copy; the rework foundation slice is a reduced read-only LIST with search + 2-axis
        // filter (status + type) — all mutations defer to the follow-on
        // `Phase 7.x.complaint.admin.actions` sub-slice. Reachable from the rework Settings hub
        // via the `OnNavigate(COMPLAINT)` intent when `Admin.isAdmin` is `true` (see
        // `SettingsReworkScreenRoute`). Bottom bar visibility false to mirror the legacy admin
        // Complaint screen experience (line 549 sibling).
        composable<Screen.ComplaintAdminRework> { backStackEntry ->
            SideEffect { onBottomBarVisibleChange(false) }
            // C1 defense-in-depth: the Settings hub already picks the admin vs user screen off
            // Admin.isAdmin (fail-closed, debug-only — see Admin.kt), but re-check here so any
            // future navigate to this route from a non-admin build degrades to the user-side
            // Feedback Manager instead of exposing the moderation console.
            if (Admin.isAdmin) {
                AdminComplaintReworkScreenRoute(
                    navController = navController,
                    backStackEntry = backStackEntry,
                )
            } else {
                ComplaintReworkScreenRoute(
                    navController = navController,
                    backStackEntry = backStackEntry,
                )
            }
        }
    }
}
