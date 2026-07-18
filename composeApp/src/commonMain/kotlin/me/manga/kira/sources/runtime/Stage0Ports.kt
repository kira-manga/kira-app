package me.manga.kira.sources.runtime

import kotlinx.serialization.json.Json
import me.manga.kira.data.local.dao.SourceConfigCacheDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourceConfigCacheEntity
import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SignedConfigDocument
import me.manga.kira.sources.contracts.SourceBaseUrlProvider
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Bundled config storage for the Stage-1 config-backed: [readBundled] returns the in-binary config-backed config
 * (trusted because it shipped in the signed app binary). The active document is exactly that bundled
 * config (the 12-source generic config-backed descriptor), and the registry serves every config-backed source via
 * the engine + every other source via the legacy adapter.
 *
 * The in-memory [cache] backs [readCached]/[writeCached] and holds the last *remote-accepted* document.
 * This in-memory implementation is retained for isolated tests. Production uses
 * [RoomSourceConfigStore] so a verified envelope survives process death.
 */
class BundledSourceConfigStore(
    private val bundledJson: String,
) : ConfigStore {
    // In-memory only; callers still receive the complete signed envelope.
    private var cache: SignedConfigDocument? = null

    override fun readBundled(): String? = bundledJson

    override suspend fun readCached(): SignedConfigDocument? = cache

    override suspend fun writeCached(document: SignedConfigDocument) {
        cache = document
    }
}

/**
 * Durable config store (Sources Migration — Phase 1). [readBundled] returns the in-binary config-backed
 * config (the trusted floor); the cache tier is now persisted in Room (`source_config_cache`) so a
 * remote-accepted document survives process death — the previous [BundledSourceConfigStore] kept it
 * in memory only, so it was lost on every relaunch and never even written (remote disabled).
 *
 * The `ConfigStore` port persists the complete signed envelope, including the exact payload and
 * detached metadata needed to authenticate it again after process death. The Room column name stays
 * `rawJson` for schema compatibility, but its value is the serialized envelope rather than bare JSON.
 */
@OptIn(ExperimentalTime::class)
class RoomSourceConfigStore(
    private val dao: SourceConfigCacheDao,
    private val bundledJson: String,
) : ConfigStore {
    override fun readBundled(): String? = bundledJson

    override suspend fun readCached(): SignedConfigDocument? =
        dao.getCached()?.rawJson?.let { raw ->
            runCatching { CACHE_JSON.decodeFromString(SignedConfigDocument.serializer(), raw) }.getOrNull()
        }

    override suspend fun writeCached(document: SignedConfigDocument) {
        dao.upsert(
            SourceConfigCacheEntity(
                rawJson = CACHE_JSON.encodeToString(SignedConfigDocument.serializer(), document),
                revision = document.metadata.revision,
                updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private companion object {
        val CACHE_JSON = Json { ignoreUnknownKeys = false }
    }
}

/**
 * Test/development fallback signature policy. Production DI uses [Ed25519ConfigSignatureVerifier];
 * this implementation remains useful for deliberately disconnected stores and denies everything.
 */
class DenyRemoteSignatureVerifier : ConfigSignatureVerifier {
    override fun verify(document: SignedConfigDocument): Boolean = false
}

/**
 * Resolves a source's LIVE base URL from the same sources DB the legacy path follows. The legacy
 * `BaseManga.getBaseUrl()` reads `SourcesDao.getBaseUrlFor(API)` (the server-pushed / user-edited
 * value), falling back to its compiled `BASE_URL`; the generic engine reuses that very row so a
 * config-backed source whose host moves keeps working without a remote config refresh. A null/blank row
 * means "no override" — the engine then keeps the frozen config baseUrl (fail-closed).
 */
class DbSourceBaseUrlProvider(
    private val sourcesDao: SourcesDao,
) : SourceBaseUrlProvider {
    override suspend fun baseUrlFor(api: String): String? = sourcesDao.getBaseUrlFor(api)
}
