package me.manga.kira.di

import io.ktor.client.HttpClient
import me.manga.kira.core.logging.KermitLoggerAdapter
import me.manga.kira.data.local.MangaDatabase
import me.manga.kira.data.local.dao.SourceCatalogDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.platform.storage.DataStoreHelper
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageProvider
import me.manga.kira.sources.config.IncrementalSourceCatalogManager
import me.manga.kira.sources.contracts.CloudflareChallengeSignal
import me.manga.kira.sources.contracts.HeaderStore
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceCatalogStore
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRegistry
import me.manga.kira.sources.contracts.SourceUpdateManager
import me.manga.kira.sources.contracts.StrategyRegistry
import me.manga.kira.sources.engine.DefaultSourceConfigValidator
import me.manga.kira.sources.engine.DefaultStrategyRegistry
import me.manga.kira.sources.engine.GenericSourceClient
import me.manga.kira.sources.runtime.CONFIG_BACKED_SOURCES_JSON
import me.manga.kira.sources.runtime.ConfigHostTrust
import me.manga.kira.sources.runtime.DataStoreHeaderStore
import me.manga.kira.sources.runtime.DbSourceBaseUrlProvider
import me.manga.kira.sources.runtime.DefaultSourceRegistry
import me.manga.kira.sources.runtime.Ed25519ConfigSignatureVerifier
import me.manga.kira.sources.runtime.KtorHttpExecutor
import me.manga.kira.sources.runtime.KtorRemoteSourceCatalog
import me.manga.kira.sources.runtime.RegistryChapterPageProvider
import me.manga.kira.sources.runtime.RoomSourceCatalogStore
import me.manga.kira.sources.runtime.SourceRemoteConfiguration
import org.koin.dsl.module

/**
 * Assembles the generic-sources subsystem at the composition root and binds the
 * [SourceRegistry]/[SourceUpdateManager] facades the rest of the app consumes.
 *
 * **Signed remote posture — registry live with a bundled floor and authenticated updates:**
 *  - The registry is BOUND and CONSUMED by four `:data` repositories (HomeFeed / Search /
 *    MangaDetails / ChapterPages), each branching on `sourceRegistry.isConfigBacked(api)`.
 *  - Active `engine="generic"` stanzas are served by the generic engine. Every absent, disabled,
 *    retired, removed, or non-generic source has no client.
 *  - The bundled config ([CONFIG_BACKED_SOURCES_JSON]) ships in the signed binary (trusted, no
 *    detached signature). The HTTPS client is wired in every build, while an empty release base URL
 *    makes it a no-op. Signed manifests and immutable source revisions are accepted only after
 *    Ed25519 verification with pinned keys.
 *  - The real [DataStoreHeaderStore] lets the generic clients reuse captured Cloudflare headers.
 *
 * Dependencies pulled from the merged graph are the shared Ktor [HttpClient] and
 * [DataStoreHelper].
 */
val sourcesGenericModule =
    module {

        // Engine governance + validation (pure, from :sources:engine).
        single<StrategyRegistry> { DefaultStrategyRegistry() }
        single<SourceConfigValidator> { DefaultSourceConfigValidator(get()) }

        // Ports (composition-root implementations of :sources:contracts interfaces).
        single<HttpExecutor> { KtorHttpExecutor(get<HttpClient>()) }
        single<HeaderStore> { DataStoreHeaderStore(get<DataStoreHelper>()) }
        single<SourceCatalogStore> {
            RoomSourceCatalogStore(
                database = get<MangaDatabase>(),
                catalogDao = get<SourceCatalogDao>(),
                sourcesDao = get<SourcesDao>(),
                migrator = get(),
                bundledJson = CONFIG_BACKED_SOURCES_JSON,
            )
        }
        single { SourceRemoteConfiguration.fromGenerated() }
        single<SourceCatalogSignatureVerifier> {
            Ed25519ConfigSignatureVerifier(get<SourceRemoteConfiguration>().pinnedPublicKeys)
        }
        single<RemoteSourceCatalog> { KtorRemoteSourceCatalog(get<HttpClient>(), get()) }
        // Live base URL from the active catalog projection, while preserving a user's explicitly
        // configured mirror according to the descriptor's previous-host policy.
        single<SourceBaseUrlProvider> { DbSourceBaseUrlProvider(get<SourcesDao>()) }
        // SourceRegistry retirement Phase 3 (R7): config-declared hosts (baseUrl/imageBase/
        // previousHosts/previousImageHosts/trustedHosts) join the push deep-link trust gate in App.kt.
        single { ConfigHostTrust(get()) }
        single<CloudflareChallengeSignal> {
            val logger = KermitLoggerAdapter()
            CloudflareChallengeSignal { api, _ ->
                logger.w(
                    "SourceCatalog",
                    "$api reported an upstream challenge; no alternate source implementation is allowed",
                )
            }
        }

        // Config lifecycle. The exact-12 bundle validates at construction, so activeDocument()
        // is immediately safe; refresh may atomically replace it with a complete signed catalog.
        single<SourceUpdateManager> {
            val logger = KermitLoggerAdapter()
            IncrementalSourceCatalogManager(
                store = get(),
                verifier = get(),
                validator = get(),
                remote = get(),
                onRejected = { reason ->
                    logger.e(
                        "SourceConfig",
                        "source catalog candidate rejected; the complete previous catalog stays active: $reason",
                    )
                },
            )
        }

        // The registry: generic-ONLY for every engine="generic" stanza in the validated active
        // document (the single authority — no in-binary api allow-list); no adapter for the rest.
        single<SourceRegistry> {
            val httpExecutor = get<HttpExecutor>()
            val headerStore = get<HeaderStore>()
            val cloudflare = get<CloudflareChallengeSignal>()
            val baseUrlProvider = get<SourceBaseUrlProvider>()
            DefaultSourceRegistry(
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
            )
        }

        // Sources Migration Phase 3: the download engines' routing seam. Routes chapter DOWNLOADS of
        // config-backed sources through the SourceRegistry (generic-ONLY — the registry has no
        // fallback) instead of calling a scraper. Missing sources fail closed. Consumed by
        // DownloadWorkerV2 (Android, via GlobalContext) and CoroutineDownloadRepositoryImpl
        // (iOS/Desktop, ctor-injected) in :data:download.
        single<ChapterPageProvider> { RegistryChapterPageProvider(sourceRegistry = get()) }
    }
