package me.manga.kira.data.remote.complaint

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.yield
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import kotlin.test.assertNotNull

/** Real HTTPS/OkHttp/Ktor fixture. Its private CA is a test-only builder override, never a factory API. */
internal class AndroidSessionEngineFixture(
    requestHost: String = "localhost",
    enrollment: Boolean = false,
    basePath: String = "",
) : Closeable {
    val server = MockWebServer()
    val resources = AndroidComplaintSessionResources()
    val url: Url
    val owner: ComplaintSessionEngineOwner
    val client: HttpClient
    val bootstrapUrl: Url get() = Url("$url/bootstrap")

    init {
        var initialized = false
        var createdOwner: ComplaintSessionEngineOwner? = null
        try {
            val certificate =
                HeldCertificate
                    .Builder()
                    .commonName("localhost")
                    .addSubjectAlternativeName("localhost")
                    .build()
            val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.useHttps(serverTrust.sslSocketFactory())
            server.start()
            url =
                Url(
                    server
                        .url("$basePath/api/v1/installations" + if (enrollment) "" else "/session")
                        .newBuilder()
                        .host(requestHost)
                        .build()
                        .toString(),
                )
            val engine =
                OkHttp.create {
                    config {
                        if (enrollment) {
                            complaintEnrollmentPolicy(assertNotNull(androidComplaintEnrollmentTarget(url)), resources)
                        } else {
                            complaintSessionPolicy(assertNotNull(androidComplaintSessionTarget(url)), resources)
                        }
                        sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
                    }
                }
            owner = resources.own(engine)
            createdOwner = owner
            client = sessionTestClient(owner.engine)
            initialized = true
        } finally {
            if (!initialized) closeUnclaimed(createdOwner)
        }
    }

    private fun closeUnclaimed(owner: ComplaintSessionEngineOwner?) {
        try {
            if (owner == null) resources.close() else owner.close()
        } finally {
            server.close()
        }
    }

    suspend fun exchange(requestUrl: Url = url): SessionEngineResponse = sessionExchange(client, requestUrl)

    suspend fun bootstrap(requestUrl: Url = bootstrapUrl): SessionEngineResponse = bootstrapExchange(client, requestUrl)

    suspend fun enroll(requestUrl: Url = url): SessionEngineResponse = enrollmentExchange(client, requestUrl)

    override fun close() {
        try {
            client.close()
        } finally {
            try {
                owner.close()
            } finally {
                server.close()
            }
        }
    }
}

internal fun sessionTestClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        useDefaultTransformers = false
        install(HttpTimeout) {
            requestTimeoutMillis = SESSION_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = SESSION_IO_TIMEOUT_MS
            socketTimeoutMillis = SESSION_IO_TIMEOUT_MS
        }
    }

internal suspend fun sessionExchange(
    client: HttpClient,
    url: Url,
): SessionEngineResponse =
    client
        .preparePost(url.toString()) {
            headers {
                append("Accept-Encoding", "identity")
                append("Cache-Control", "no-store, no-transform")
            }
            setBody(
                ByteArrayContent(
                    "{\"secret\":\"synthetic-test-only\"}".encodeToByteArray(),
                    ContentType.Application.Json,
                ),
            )
        }.execute { response ->
            SessionEngineResponse(
                response.status.value,
                sessionResponseBytes(response.bodyAsChannel()).decodeToString(),
            )
        }

internal suspend fun bootstrapExchange(
    client: HttpClient,
    url: Url,
): SessionEngineResponse =
    client
        .prepareGet(url.toString()) {
            headers {
                append("Accept", "application/json, application/problem+json")
                append("Accept-Encoding", "identity")
                append("Cache-Control", "no-store, no-transform")
            }
        }.execute { response ->
            SessionEngineResponse(
                response.status.value,
                sessionResponseBytes(response.bodyAsChannel()).decodeToString(),
            )
        }

internal suspend fun enrollmentExchange(
    client: HttpClient,
    url: Url,
): SessionEngineResponse =
    client
        .preparePost(url.toString()) {
            headers {
                append("Accept", "application/json, application/problem+json")
                append("Accept-Encoding", "identity")
                append("Cache-Control", "no-store, no-transform")
            }
            setBody(ByteArrayContent(ENROLLMENT_TEST_BODY.encodeToByteArray(), ContentType.Application.Json))
        }.execute { response ->
            SessionEngineResponse(
                response.status.value,
                sessionResponseBytes(response.bodyAsChannel()).decodeToString(),
            )
        }

private suspend fun sessionResponseBytes(channel: ByteReadChannel): ByteArray {
    val bytes = ByteArray(ComplaintSessionReceiveBudget.MAX_BYTES + 1)
    var count = 0
    while (count < bytes.size) {
        val read = channel.readAvailable(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) yield() else count += read
    }
    check(count <= ComplaintSessionReceiveBudget.MAX_BYTES)
    return bytes.copyOf(count)
}

internal data class SessionEngineResponse(
    val status: Int,
    val body: String,
)

internal const val SESSION_TEST_TIMEOUT_MS = 5_000L
internal const val ENROLLMENT_TEST_BODY =
    "{\"installationId\":\"11111111-1111-4111-8111-111111111111\"," +
        "\"secret\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\",\"platform\":\"android\"," +
        "\"expectedDataScopeId\":\"00000000-0000-0000-0000-000000000000\"}"
private const val SESSION_REQUEST_TIMEOUT_MS = 30_000L
private const val SESSION_IO_TIMEOUT_MS = 10_000L
