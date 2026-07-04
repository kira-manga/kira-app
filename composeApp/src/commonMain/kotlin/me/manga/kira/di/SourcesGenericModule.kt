package me.manga.kira.di

import io.ktor.client.HttpClient
import me.manga.kira.core.logging.KermitLoggerAdapter
import me.manga.kira.data.local.dao.SourceConfigCacheDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.sources.config.RemoteSourceConfigManager
import me.manga.kira.sources.contracts.CloudflareChallengeSignal
import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.StrategyRegistry
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import me.manga.kira.sources.runtime.CONFIG_BACKED_APIS
import me.manga.kira.sources.runtime.CONFIG_BACKED_SOURCES_JSON
import me.manga.kira.sources.runtime.ConfigHostTrust
import me.manga.kira.sources.runtime.DataStoreHeaderStore
import me.manga.kira.sources.runtime.DbSourceBaseUrlProvider
import me.manga.kira.sources.runtime.DefaultSourceRegistry
import me.manga.kira.sources.runtime.DenyRemoteSignatureVerifier
import me.manga.kira.sources.runtime.KtorHttpExecutor
import me.manga.kira.sources.runtime.RegistryChapterPageProvider
import me.manga.kira.sources.runtime.RoomSourceConfigStore
import me.manga.kira.sources.runtime.SourceDebugFlags
import me.manga.kira.sources_repositry.BaseMangaRepository
import org.koin.dsl.module

/**
 * Assembles the generic-sources subsystem at the composition root and binds the
 * [SourceRegistry]/[SourceUpdateManager] facades the rest of the app consumes.
 *
 * **Stage-1 posture — registry live for the config-backed sources, remote config still disabled:**
 *  - The registry is BOUND and CONSUMED by four `:data` repositories (HomeFeed / Search /
 *    MangaDetails / ChapterPages), each branching on `sourceRegistry.isConfigBacked(api)`.
 *  - The config-backed sources ([CONFIG_BACKED_APIS], 12 apis) are served by the generic engine over the bundled
 *    config, generic-ONLY (no legacy fallback — see [DefaultSourceRegistry]); every other source
 *    stays on the legacy adapter.
 *  - The bundled config ([CONFIG_BACKED_SOURCES_JSON]) ships in the signed binary (trusted, no detached
 *    signature). Remote config is DISABLED (`remote = null`); signatures are denied
 *    ([DenyRemoteSignatureVerifier]) so a remote document could never be trusted even if wired.
 *  - The real [DataStoreHeaderStore] lets the generic clients reuse captured Cloudflare headers.
 *
 * Dependencies pulled from the merged graph: the shared Ktor [HttpClient], the legacy
 * `Set<BaseMangaRepository>`, and [DataStoreHelper] (all from `SharedModule`/`platformModule`).
 */
val sourcesGenericModule =
    module {

        // Engine governance + validation (pure, from :sources:engine).
        single<StrategyRegistry> { DefaultStrategyRegistry() }
        single<SourceConfigValidator> { DefaultSourceConfigValidator(get()) }

        // Ports (composition-root implementations of :sources:contracts interfaces).
        single<HttpExecutor> { KtorHttpExecutor(get<HttpClient>()) }
        single<HeaderStore> { DataStoreHeaderStore(get<DataStoreHelper>()) }
        // Sources Migration Phase 1: durable Room-backed config cache (was in-memory only). The bundled
        // config-backed JSON stays the trusted floor; remote-accepted documents now persist across launches.
        single<ConfigStore> { RoomSourceConfigStore(get<SourceConfigCacheDao>(), CONFIG_BACKED_SOURCES_JSON) }
        single<ConfigSignatureVerifier> { DenyRemoteSignatureVerifier() }
        // Live base URL, read from the same sources DB row the legacy path follows (server-pushed /
        // user-edited domain moves) — so a config-backed source whose host moves keeps working without remote config.
        single<SourceBaseUrlProvider> { DbSourceBaseUrlProvider(get<SourcesDao>()) }
        // SourceRegistry retirement Phase 3 (R7): config-declared hosts (baseUrl/imageBase/
        // previousHosts/previousImageHosts/trustedHosts) join the push deep-link trust gate in App.kt.
        single { ConfigHostTrust(get()) }
        single<CloudflareChallengeSignal> {
            // No-op in production. When the generic-only debug flag is ON, surface the definitive Cloudflare
            // signal (this fires exactly when the engine detects a challenge) so a 403/503 can be attributed
            // to Cloudflare rather than to plain missing headers.
            val logger = KermitLoggerAdapter()
            CloudflareChallengeSignal { api, url ->
                if (SourceDebugFlags.DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING) {
                    logger.w("GenericSourceTest", "$api → Cloudflare challenge at $url (needs a solved cf_clearance cookie + matching UA)")
                }
            }
        }

        // Config lifecycle — remote disabled. The bundled config-backed config resolves at construction
        // (resolveBundled), so activeDocument() returns the config-backed descriptors without any refresh().
        single<SourceUpdateManager> {
            RemoteSourceConfigManager(
                store = get(),
                verifier = get(),
                validator = get(),
                remote = null,
            )
        }

        // The registry: generic-ONLY for the config-backed apis ([CONFIG_BACKED_APIS]); legacy adapter for the rest.
        single<SourceRegistry> {
            val httpExecutor = get<HttpExecutor>()
            val headerStore = get<HeaderStore>()
            val cloudflare = get<CloudflareChallengeSignal>()
            val baseUrlProvider = get<SourceBaseUrlProvider>()
            DefaultSourceRegistry(
                legacyRepos = get<Set<BaseMangaRepository>>(),
                updateManager = get(),
                genericClientFactory = { config ->
                    GenericSourceClient(
                        config = config,
                        http = httpExecutor,
                        headerStore = headerStore,
                        cloudflare = cloudflare,
                        baseUrlProvider = baseUrlProvider,
                    )
                },
                configBackedApis = CONFIG_BACKED_APIS,
            )
        }

        // Sources Migration Phase 3: the download engines' routing seam. Routes chapter DOWNLOADS of
        // config-backed sources through the SourceRegistry (generic-ONLY — the registry has no
        // legacy fallback) instead of calling the legacy scraper directly. Returns null for
        // non-config sources so their existing legacy download path stays unchanged. Consumed by
        // DownloadWorkerV2 (Android, via GlobalContext) and CoroutineDownloadRepositoryImpl
        // (iOS/Desktop, ctor-injected) in :data:download.
        single<ChapterPageProvider> { RegistryChapterPageProvider(sourceRegistry = get()) }
    }
