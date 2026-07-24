package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class KtorRemoteSourceCatalogTest {
    @Test
    fun conditional_manifest_request_honors_not_modified_without_payload() =
        runTest {
            val checksum = "a".repeat(64)
            val engine =
                MockEngine { request ->
                    assertEquals("/api/v2/source-config/manifest", request.url.encodedPath)
                    assertEquals("1.2.3", request.url.parameters["appVersion"])
                    assertEquals("\"$checksum\"", request.headers[HttpHeaders.IfNoneMatch])
                    respondError(HttpStatusCode.NotModified)
                }

            val result = catalog(engine).fetchManifest(checksum)

            assertIs<SourceCatalogManifestResult.NotModified>(result)
        }

    @Test
    fun manifest_maps_exact_bytes_and_signed_chain_metadata() =
        runTest {
            val engine = MockEngine { respond(MANIFEST, HttpStatusCode.OK, manifestHeaders()) }

            val result = assertIs<SourceCatalogManifestResult.Modified>(catalog(engine).fetchManifest(null))

            assertEquals(MANIFEST, result.manifest.payload)
            assertEquals(11, result.manifest.metadata.revision)
            assertEquals(10, result.manifest.metadata.previousRevision)
            assertEquals("b".repeat(64), result.manifest.metadata.previousChecksum)
        }

    @Test
    fun immutable_source_request_uses_exact_identity_and_rejects_mismatched_metadata() =
        runTest {
            val entry = entry()
            val goodEngine =
                MockEngine { request ->
                    assertEquals("/api/v2/source-config/sources/Team%20X/revisions/7", request.url.encodedPath)
                    respond(SOURCE, HttpStatusCode.OK, sourceHeaders(entry))
                }
            val artifact = catalog(goodEngine).fetchSource(entry)
            assertEquals("Team X", artifact.api)
            assertEquals(7, artifact.sourceRevision)
            assertEquals(SOURCE, artifact.payload)

            val badHeaders =
                Headers.build {
                    sourceHeaders(entry).forEach { name, values -> appendAll(name, values) }
                    set("X-Source-Revision", "8")
                }
            val badEngine = MockEngine { respond(SOURCE, HttpStatusCode.OK, badHeaders) }
            assertFailsWith<IllegalArgumentException> { catalog(badEngine).fetchSource(entry) }
        }

    @Test
    fun insecure_catalog_origin_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            KtorRemoteSourceCatalog(
                HttpClient(MockEngine { respondError(HttpStatusCode.OK) }),
                SourceRemoteConfiguration.create(
                    baseUrl = "http://api.example.test",
                    appVersion = "1.2.3",
                    pinnedPublicKeys = mapOf("test-key" to "public"),
                ),
            )
        }
    }

    private fun catalog(engine: MockEngine): KtorRemoteSourceCatalog =
        KtorRemoteSourceCatalog(
            HttpClient(engine),
            SourceRemoteConfiguration.create(
                baseUrl = "https://api.example.test",
                appVersion = "1.2.3",
                pinnedPublicKeys = mapOf("test-key" to "public"),
            ),
        )

    private fun manifestHeaders(): Headers =
        Headers.build {
            append(HttpHeaders.ContentType, "application/json")
            append(HttpHeaders.ContentLength, MANIFEST.encodeToByteArray().size.toString())
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

    private fun sourceHeaders(entry: SourceCatalogEntry): Headers =
        Headers.build {
            append(HttpHeaders.ContentType, "application/json; charset=UTF-8")
            append(HttpHeaders.ContentLength, SOURCE.encodeToByteArray().size.toString())
            append(HttpHeaders.ETag, "\"${entry.checksum}\"")
            append("X-Source-Api", entry.api)
            append("X-Source-Revision", entry.sourceRevision.toString())
            append("X-Source-Checksum", entry.checksum)
            append("X-Source-Canon-Version", "kcj-1")
        }

    private fun entry(): SourceCatalogEntry =
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

    private companion object {
        const val MANIFEST =
            """{"schemaVersion":1,"sourceSchemaVersion":1,"catalogRevision":11,"sources":[]}"""
        const val SOURCE =
            """{"api":"Team X","language":"ar","baseUrl":"https://teamx.test","engine":"generic"}"""
    }
}
