package me.manga.kira.data.remote.complaint

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import mockwebserver3.MockWebServer
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.Closeable
import kotlin.test.assertNotNull
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Only the new route policy differs; ownership, test client and failure-preserving drain are reused. */
internal class AndroidMutationEngineFixture(
    basePath: String = "",
) : Closeable {
    val server = MockWebServer()
    val resources = AndroidComplaintSessionResources()
    val createUrl: Url
    val statusUrl: Url
    val owner: ComplaintSessionEngineOwner
    val client: HttpClient

    init {
        var initialized = false
        var claimedOwner: ComplaintSessionEngineOwner? = null
        try {
            val trust = startMutationServer(server)
            createUrl = Url(server.url(basePath + Policy.CREATE_PATH).toString())
            statusUrl = Url(server.url(basePath + Policy.STATUS_PATH).toString())
            val engine =
                OkHttp.create {
                    config {
                        complaintMutationPolicy(assertNotNull(androidComplaintMutationTarget(createUrl)), resources)
                        sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                    }
                }
            owner = resources.own(engine)
            claimedOwner = owner
            client = sessionTestClient(owner.engine)
            initialized = true
        } finally {
            if (!initialized) closeNative(claimedOwner)
        }
    }

    suspend fun exchange(
        route: ComplaintMutationRoute = ComplaintMutationRoute.CREATE,
        size: Int = 1,
        change: HttpRequestBuilder.() -> Unit = {},
    ): SessionEngineResponse {
        val request =
            HttpRequestBuilder().apply {
                method = HttpMethod.Post
                url(if (route == ComplaintMutationRoute.CREATE) createUrl.toString() else statusUrl.toString())
                mutationTestHeaders(route).filterNot { it.first == "Content-Type" }.forEach { (name, value) ->
                    headers.append(name, value)
                }
                setBody(ByteArrayContent(ByteArray(size) { 'x'.code.toByte() }, ContentType.Application.Json))
                change()
            }
        return client.prepareRequest(request).execute { response ->
            SessionEngineResponse(
                response.status.value,
                historyResponseBytes(response.bodyAsChannel()).decodeToString(),
            )
        }
    }

    private fun closeNative(claimedOwner: ComplaintSessionEngineOwner?) {
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
            closeNative(owner)
        }
    }
}

private fun startMutationServer(server: MockWebServer): HandshakeCertificates {
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
    return clientTrust
}
