package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import kotlin.test.assertIs

/** Catalog-only fixtures; signature strings are metadata controls, not cryptographic evidence. */
internal object CatalogTransportTestFixtures {
    const val MANIFEST =
        """{"schemaVersion":1,"sourceSchemaVersion":1,"catalogRevision":11,"sources":[]}"""
    const val SOURCE =
        """{"api":"Team X","language":"ar","baseUrl":"https://teamx.test","engine":"generic"}"""
    val padding = ByteArray(8 * 1024) { ' '.code.toByte() }

    fun configuration(baseUrl: String = "https://api.example.test"): SourceRemoteConfiguration =
        SourceRemoteConfiguration.create(baseUrl, "1.2.3", mapOf("test-key" to "public"))

    fun catalog(client: HttpClient): KtorRemoteSourceCatalog = KtorRemoteSourceCatalog(client, configuration())

    fun manifestHeaders(length: Int? = MANIFEST.encodeToByteArray().size): Headers =
        Headers.build {
            append(HttpHeaders.ContentType, "application/json")
            length?.let { append(HttpHeaders.ContentLength, it.toString()) }
            append(HttpHeaders.ETag, "\"${"a".repeat(64)}\"")
            append("X-Config-Signature-Format", "kira-source-catalog-manifest-v1")
            append("X-Config-Signature-Algorithm", "Ed25519")
            append("X-Config-Signing-Key-Id", "test-key")
            append("X-Config-Signature", "signature")
            append("X-Config-Revision", "11")
            append("X-Config-Checksum", "a".repeat(64))
            append("X-Config-Created-At", "2026-07-23T00:00:00Z")
            append("X-Config-Previous-Revision", "10")
            append("X-Config-Previous-Checksum", "b".repeat(64))
        }

    fun sourceHeaders(
        entry: SourceCatalogEntry = CatalogTransportTestFixtures.entry(),
        length: Int? = SOURCE.encodeToByteArray().size,
    ): Headers =
        Headers.build {
            append(HttpHeaders.ContentType, "application/json; charset=UTF-8")
            length?.let { append(HttpHeaders.ContentLength, it.toString()) }
            append(HttpHeaders.ETag, "\"${entry.checksum}\"")
            append("X-Source-Api", entry.api)
            append("X-Source-Revision", entry.sourceRevision.toString())
            append("X-Source-Checksum", entry.checksum)
            append("X-Source-Canon-Version", "kcj-1")
        }

    fun entry(): SourceCatalogEntry =
        SourceCatalogEntry(
            api = "Team X",
            sourceRevision = 7,
            checksum = "c".repeat(64),
            order = 0,
            lifecycle = "active",
            engine = "generic",
            sourceSigningKeyId = "test-key",
            sourceSignature = "signature",
        )

    suspend fun writePadding(
        channel: ByteChannel,
        count: Int,
    ) {
        var remaining = count
        while (remaining > 0) {
            val size = minOf(remaining, padding.size)
            channel.writeFully(padding, 0, size)
            remaining -= size
        }
    }
}

internal enum class CatalogRoute(
    val limit: Int,
    val label: String,
) {
    MANIFEST(5 * 1024 * 1024, "source-catalog manifest"),
    SOURCE(256 * 1024, "source revision"),
    ;

    fun headers(length: Int? = null): Headers =
        when (this) {
            MANIFEST -> CatalogTransportTestFixtures.manifestHeaders(length)
            SOURCE -> CatalogTransportTestFixtures.sourceHeaders(length = length)
        }

    suspend fun payload(catalog: KtorRemoteSourceCatalog): String =
        when (this) {
            MANIFEST -> assertIs<SourceCatalogManifestResult.Modified>(catalog.fetchManifest(null)).manifest.payload
            SOURCE -> catalog.fetchSource(CatalogTransportTestFixtures.entry()).payload
        }
}

/** Factory ownership, deterministic test dispatcher, bounded test wait, and joined client/engine cleanup. */
internal suspend fun <T> TestScope.withCatalogClient(
    handler: MockRequestHandler,
    cacheResponses: Boolean = false,
    block: suspend (HttpClient) -> T,
): T {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val client =
        HttpClient(MockEngine) {
            engine {
                dispatcher = testDispatcher
                addHandler(handler)
            }
            if (cacheResponses) install(HttpCache)
        }
    try {
        return withTimeout(5_000) { block(client) }
    } finally {
        client.close()
        withContext(NonCancellable) {
            withTimeout(5_000) {
                client.coroutineContext.job.cancelAndJoin()
                client.engine.coroutineContext.job
                    .cancelAndJoin()
            }
        }
    }
}
