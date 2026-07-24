package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SignedConfigDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KtorRemoteConfigSourceTest {
    @Test
    fun maps_exact_body_and_all_detached_signature_headers() =
        runTest {
            val source = source(headers())
            val document = requireNotNull(source.fetch())

            assertEquals(RAW_DOCUMENT, document.payload)
            assertEquals(100, document.metadata.revision)
            assertEquals("test-key", document.metadata.keyId)
            assertEquals(99, document.metadata.previousRevision)
            assertEquals("b".repeat(64), document.metadata.previousChecksum)
        }

    @Test
    fun rejects_missing_signature_metadata_and_oversized_documents() =
        runTest {
            val missing =
                Headers.build {
                    headers().forEach { name, values ->
                        if (name != "X-Config-Signature") appendAll(name, values)
                    }
                }
            assertFailsWith<IllegalArgumentException> { source(missing).fetch() }

            val oversized =
                Headers.build {
                    headers().forEach { name, values -> appendAll(name, values) }
                    set("Content-Length", (5L * 1024 * 1024 + 1).toString())
                }
            assertFailsWith<IllegalStateException> { source(oversized).fetch() }
        }

    @Test
    fun sends_version_and_conditional_checksum_and_honors_not_modified() =
        runTest {
            val store = RecordingConfigStore(cachedChecksum = "c".repeat(64))
            val engine =
                MockEngine { request ->
                    assertEquals("1.0.0", request.url.parameters["appVersion"])
                    assertEquals("\"${"c".repeat(64)}\"", request.headers[HttpHeaders.IfNoneMatch])
                    respondError(HttpStatusCode.NotModified)
                }
            val source =
                KtorRemoteConfigSource(
                    HttpClient(engine),
                    SourceRemoteConfiguration.create(
                        "https://api.example.test",
                        "1.0.0",
                        mapOf("test-key" to "public"),
                    ),
                    store,
                )

            assertNull(source.fetch())
        }

    @Test
    fun rejects_insecure_or_credentialed_endpoints_and_non_success_statuses() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                KtorRemoteConfigSource(
                    HttpClient(MockEngine { respondError(HttpStatusCode.OK) }),
                    SourceRemoteConfiguration.create("http://api.example.test", "1.0.0", mapOf("key" to "public")),
                    EmptyConfigStore,
                )
            }
            assertFailsWith<IllegalArgumentException> {
                KtorRemoteConfigSource(
                    HttpClient(MockEngine { respondError(HttpStatusCode.OK) }),
                    SourceRemoteConfiguration.create(
                        "https://user:secret@api.example.test",
                        "1.0.0",
                        mapOf("key" to "public"),
                    ),
                    EmptyConfigStore,
                )
            }
            val unavailable = MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }
            val source =
                KtorRemoteConfigSource(
                    HttpClient(unavailable),
                    SourceRemoteConfiguration.create("https://api.example.test", "1.0.0", mapOf("key" to "public")),
                    EmptyConfigStore,
                )
            assertFailsWith<IllegalStateException> { source.fetch() }
        }

    private fun source(headers: Headers): KtorRemoteConfigSource {
        val engine = MockEngine { respond(RAW_DOCUMENT, HttpStatusCode.OK, headers) }
        return KtorRemoteConfigSource(
            HttpClient(engine),
            SourceRemoteConfiguration.create("https://api.example.test", "1.0.0", mapOf("test-key" to "public")),
            EmptyConfigStore,
        )
    }

    private fun headers(): Headers =
        Headers.build {
            append("Content-Length", RAW_DOCUMENT.encodeToByteArray().size.toString())
            append("X-Config-Signature-Format", "kira-source-signature-v1")
            append("X-Config-Signature-Algorithm", "Ed25519")
            append("X-Config-Signing-Key-Id", "test-key")
            append("X-Config-Signature", "signature")
            append("X-Config-Revision", "100")
            append("X-Config-Checksum", "a".repeat(64))
            append("X-Config-Created-At", "2026-07-18T00:00:00Z")
            append("X-Config-Previous-Revision", "99")
            append("X-Config-Previous-Checksum", "b".repeat(64))
        }

    private companion object {
        const val RAW_DOCUMENT = "{\"revision\":100,\"schemaVersion\":1,\"sources\":[]}"

        object EmptyConfigStore : ConfigStore {
            override fun readBundled(): String? = null

            override suspend fun readCached(): SignedConfigDocument? = null

            override suspend fun writeCached(document: SignedConfigDocument) = Unit
        }

        class RecordingConfigStore(
            cachedChecksum: String,
        ) : ConfigStore {
            private val cached =
                SignedConfigDocument(
                    payload = RAW_DOCUMENT,
                    metadata =
                        me.manga.kira.sources.contracts.ConfigSignatureMetadata(
                            format = "kira-source-signature-v1",
                            algorithm = "Ed25519",
                            keyId = "test-key",
                            signatureBase64 = "unused",
                            revision = 100,
                            checksum = cachedChecksum,
                            createdAt = "2026-07-18T00:00:00Z",
                        ),
                )

            override fun readBundled(): String? = null

            override suspend fun readCached(): SignedConfigDocument = cached

            override suspend fun writeCached(document: SignedConfigDocument) = Unit
        }
    }
}
