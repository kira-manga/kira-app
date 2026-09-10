package me.manga.kira.sources.runtime

import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.MANIFEST
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.SOURCE
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.catalog
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.configuration
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.entry
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.manifestHeaders
import me.manga.kira.sources.runtime.CatalogTransportTestFixtures.sourceHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KtorRemoteSourceCatalogTest {
    @Test
    fun conditional_manifest_request_honors_not_modified_without_payload() =
        runTest {
            val checksum = "a".repeat(64)
            withCatalogClient({ request ->
                assertEquals("/api/v2/source-config/manifest", request.url.encodedPath)
                assertEquals("1.2.3", request.url.parameters["appVersion"])
                assertEquals("no-cache", request.headers[HttpHeaders.CacheControl])
                assertEquals("\"$checksum\"", request.headers[HttpHeaders.IfNoneMatch])
                respondError(HttpStatusCode.NotModified)
            }) { client ->
                assertIs<SourceCatalogManifestResult.NotModified>(catalog(client).fetchManifest(checksum))
            }
        }

    @Test
    fun manifest_maps_exact_bytes_and_signed_chain_metadata() =
        runTest {
            withCatalogClient({ respond(MANIFEST, HttpStatusCode.OK, manifestHeaders()) }) { client ->
                val result = assertIs<SourceCatalogManifestResult.Modified>(catalog(client).fetchManifest(null))
                assertEquals(MANIFEST, result.manifest.payload)
                assertEquals(11, result.manifest.metadata.revision)
                assertEquals(10, result.manifest.metadata.previousRevision)
                assertEquals("b".repeat(64), result.manifest.metadata.previousChecksum)
            }
        }

    @Test
    fun immutable_source_request_uses_exact_identity_and_rejects_mismatched_metadata() =
        runTest {
            val entry = entry()
            withCatalogClient({ request ->
                assertEquals("/api/v2/source-config/sources/Team%20X/revisions/7", request.url.encodedPath)
                respond(SOURCE, HttpStatusCode.OK, sourceHeaders(entry))
            }) { client ->
                val artifact = catalog(client).fetchSource(entry)
                assertEquals("Team X", artifact.api)
                assertEquals(7, artifact.sourceRevision)
                assertEquals(SOURCE, artifact.payload)
            }
            val badHeaders =
                Headers.build {
                    sourceHeaders(entry).forEach { name, values -> appendAll(name, values) }
                    set("X-Source-Revision", "8")
                }
            withCatalogClient({ respond(SOURCE, HttpStatusCode.OK, badHeaders) }) { client ->
                assertFailsWith<IllegalArgumentException> { catalog(client).fetchSource(entry) }
            }
        }

    @Test
    fun insecure_catalog_origin_is_rejected() =
        runTest {
            withCatalogClient({ error("constructor must not issue a request") }) { client ->
                assertFailsWith<IllegalArgumentException> {
                    KtorRemoteSourceCatalog(client, configuration("http://api.example.test"))
                }
            }
        }

    @Test
    fun cached_client_is_rejected_without_closing_the_borrowed_client() =
        runTest {
            withCatalogClient({ error("cached client must not be used") }, cacheResponses = true) { client ->
                val failure = assertFailsWith<IllegalArgumentException> { catalog(client) }
                assertEquals("source catalog requires an uncached HTTP client", failure.message)
                assertTrue(client.coroutineContext.job.isActive)
            }
        }

    @Test
    fun disabled_remote_remains_unavailable_without_a_request() =
        runTest {
            withCatalogClient({ error("disabled remote must not issue a request") }) { client ->
                val remote = KtorRemoteSourceCatalog(client, configuration(""))
                assertIs<SourceCatalogManifestResult.Unavailable>(remote.fetchManifest(null))
                assertFailsWith<IllegalArgumentException> { remote.fetchSource(entry()) }
            }
        }
}
