package me.manga.kira.sources.runtime

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.data.local.dao.SourceConfigCacheDao
import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.data.local.entity.SourceConfigCacheEntity
import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SourceBaseUrlProvider

/**
 * The Stage-0 implementations of the config/header ports. They are intentionally minimal and SAFE:
 * the generic engine path is dark, so these exist to (a) make the registry graph resolvable and
 * (b) encode the safe default posture for the parts that ARE wired (config resolution, signatures).
 * Each carries a note on what its Stage-1 replacement does.
 */

/**
 * Bundled config storage for the Stage-1 config-backed: [readBundled] returns the in-binary config-backed config
 * (trusted because it shipped in the signed app binary). The active document is exactly that bundled
 * config (the 12-source generic config-backed descriptor), and the registry serves every config-backed source via
 * the engine + every other source via the legacy adapter.
 *
 * The in-memory [cache] backs [readCached]/[writeCached] and holds the last *remote-accepted* document.
 * Since remote is disabled in Stage-1, nothing ever writes it, so it stays null — bundled is the only
 * resolved source. Reading a signed remote document and persisting the cache to disk is Stage-2.
 */
class BundledSourceConfigStore(
    private val bundledJson: String,
) : ConfigStore {
    // In-memory only; remote is disabled so there is a single writer (refresh) in practice.
    private var cache: String? = null

    override fun readBundled(): String? = bundledJson
    override suspend fun readCached(): String? = cache
    override suspend fun writeCached(raw: String) {
        cache = raw
    }
}

/**
 * Durable config store (Sources Migration — Phase 1). [readBundled] returns the in-binary config-backed
 * config (the trusted floor); the cache tier is now persisted in Room (`source_config_cache`) so a
 * remote-accepted document survives process death — the previous [BundledSourceConfigStore] kept it
 * in memory only, so it was lost on every relaunch and never even written (remote disabled).
 *
 * The `ConfigStore` port speaks a raw JSON string; we store it verbatim (forward-compatible with
 * newer remote schemas) plus a cheaply-extracted `revision` for diagnostics. The store stays origin-
 * agnostic: whether the raw came from a future signed API or a manual write, [readCached] returns it.
 */
@OptIn(ExperimentalTime::class)
class RoomSourceConfigStore(
    private val dao: SourceConfigCacheDao,
    private val bundledJson: String,
) : ConfigStore {
    override fun readBundled(): String? = bundledJson

    override suspend fun readCached(): String? = dao.getCached()?.rawJson

    override suspend fun writeCached(raw: String) {
        dao.upsert(
            SourceConfigCacheEntity(
                rawJson = raw,
                revision = extractRevision(raw),
                updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            ),
        )
    }

    private companion object {
        // Lightweight, dependency-free revision read for the diagnostics column. The authoritative
        // revision/merge handling stays in RemoteSourceConfigManager (which re-parses the raw doc).
        private val REVISION = Regex("\"revision\"\\s*:\\s*(-?\\d+)")
        fun extractRevision(raw: String): Long =
            REVISION.find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }
}

/**
 * Stage-0 signature policy: deny everything. No remote source is wired, but even if one were, an
 * unverifiable document must never be trusted — so until a real public key is provisioned, every
 * non-bundled document is rejected and the bundled/cached document stays active. Fail-closed.
 *
 * Stage-1 replacement verifies a detached Ed25519/RSA signature against a pinned public key.
 */
class DenyRemoteSignatureVerifier : ConfigSignatureVerifier {
    override fun verify(payload: ByteArray, signatureBase64: String): Boolean = false
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
