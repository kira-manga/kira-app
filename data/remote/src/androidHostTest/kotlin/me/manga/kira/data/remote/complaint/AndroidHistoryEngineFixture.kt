package me.manga.kira.data.remote.complaint

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.yield
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import kotlin.test.assertNotNull

/** Real HTTPS/Ktor history policy; the private CA override exists only in this host fixture. */
internal class AndroidHistoryEngineFixture(
    basePath: String = "",
) : Closeable {
    val server = MockWebServer()
    val resources = AndroidComplaintSessionResources()
    val url: Url
    val owner: ComplaintSessionEngineOwner
    val client: HttpClient
    val firstPage: Url get() = Url("$url?limit=50")

    init {
        var initialized = false
        var claimedOwner: ComplaintSessionEngineOwner? = null
        try {
            val clientTrust = startHistoryServer(server)
            url = Url(server.url("$basePath/api/v1/complaints").toString())
            val engine =
                OkHttp.create {
                    config {
                        complaintHistoryPolicy(assertNotNull(androidComplaintHistoryTarget(url)), resources)
                        sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
                    }
                }
            owner = resources.own(engine)
            claimedOwner = owner
            client = sessionTestClient(owner.engine)
            initialized = true
        } finally {
            if (!initialized) closeUnclaimed(claimedOwner)
        }
    }

    suspend fun get(requestUrl: Url = firstPage): HistoryEngineResponse = historyExchange(client, requestUrl)

    private fun closeUnclaimed(claimedOwner: ComplaintSessionEngineOwner?) {
        try {
            if (claimedOwner == null) resources.close() else claimedOwner.close()
        } finally {
            server.close()
        }
    }

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

private fun startHistoryServer(server: MockWebServer): HandshakeCertificates {
    val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
    val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    server.protocols = listOf(Protocol.HTTP_1_1)
    server.useHttps(serverTrust.sslSocketFactory())
    server.start()
    return clientTrust
}

internal fun HttpRequestBuilder.historyTestHeaders() {
    headers {
        append("Accept", "application/json, application/problem+json")
        append("Accept-Encoding", "identity")
        append("Cache-Control", "no-store, no-transform")
        append("Authorization", HISTORY_TEST_AUTHORIZATION)
    }
}

internal suspend fun historyExchange(
    client: HttpClient,
    url: Url,
): HistoryEngineResponse =
    client
        .prepareGet(url.toString()) { historyTestHeaders() }
        .execute { response ->
            HistoryEngineResponse(response.status.value, historyResponseBytes(response.bodyAsChannel()).decodeToString())
        }

internal suspend fun historyResponseBytes(channel: ByteReadChannel): ByteArray {
    val bytes = ByteArray(ComplaintHistoryReceiveBudget.MAX_BYTES + 1)
    var count = 0
    while (count < bytes.size) {
        val read = channel.readAvailable(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) yield() else count += read
    }
    channel.closedCause?.let { throw it }
    check(count <= ComplaintHistoryReceiveBudget.MAX_BYTES)
    return bytes.copyOf(count)
}

internal data class HistoryEngineResponse(
    val status: Int,
    val body: String,
)

internal const val HISTORY_TEST_AUTHORIZATION = "Bearer synthetic.header.signature"
internal const val ANDROID_HISTORY_TEST_URL = "https://example.invalid/api/v1/complaints"
