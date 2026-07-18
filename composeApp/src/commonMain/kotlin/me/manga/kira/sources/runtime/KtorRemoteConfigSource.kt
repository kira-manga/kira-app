package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import me.manga.kira.sources.config.RemoteConfigSource
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SignedConfigDocument

/** Bounded HTTPS client for the public signed source-document endpoint. */
class KtorRemoteConfigSource(
    private val httpClient: HttpClient,
    configuration: SourceRemoteConfiguration,
    private val store: ConfigStore,
) : RemoteConfigSource {
    private val endpoint = configuration.baseUrl.takeIf { configuration.enabled }?.let(::endpoint)
    private val appVersion = configuration.appVersion

    override suspend fun fetch(): SignedConfigDocument? = endpoint?.let { fetchConfigured(it) }

    private suspend fun fetchConfigured(configuredEndpoint: String): SignedConfigDocument? {
        val cachedChecksum = store.readCached()?.metadata?.checksum
        val response =
            httpClient.get(configuredEndpoint) {
                parameter("appVersion", appVersion)
                cachedChecksum?.let { header(HttpHeaders.IfNoneMatch, "\"$it\"") }
            }
        if (response.status == HttpStatusCode.NotModified) return null
        check(response.status == HttpStatusCode.OK) {
            "source-config endpoint returned HTTP ${response.status.value}"
        }
        response.headers["Content-Length"]?.toLongOrNull()?.let { check(it <= MAX_DOCUMENT_BYTES) }
        val bytes = response.body<ByteArray>()
        check(bytes.size.toLong() <= MAX_DOCUMENT_BYTES) { "source-config document exceeds the configured size limit" }
        val previousRevision = response.headers["X-Config-Previous-Revision"]?.toLong()
        val previousChecksum = response.headers["X-Config-Previous-Checksum"]
        require((previousRevision == null) == (previousChecksum == null)) {
            "source-config chain metadata is incomplete"
        }
        return SignedConfigDocument(
            payload = bytes.decodeToString(throwOnInvalidSequence = true),
            metadata =
                ConfigSignatureMetadata(
                    format = response.requiredHeader("X-Config-Signature-Format"),
                    algorithm = response.requiredHeader("X-Config-Signature-Algorithm"),
                    keyId = response.requiredHeader("X-Config-Signing-Key-Id"),
                    signatureBase64 = response.requiredHeader("X-Config-Signature"),
                    revision = response.requiredHeader("X-Config-Revision").toLong(),
                    checksum = response.requiredHeader("X-Config-Checksum"),
                    createdAt = response.requiredHeader("X-Config-Created-At"),
                    previousRevision = previousRevision,
                    previousChecksum = previousChecksum,
                ),
        )
    }

    private fun io.ktor.client.statement.HttpResponse.requiredHeader(name: String): String =
        requireNotNull(headers[name]?.takeIf { it.isNotBlank() }) { "source-config response is missing $name" }

    private fun endpoint(baseUrl: String): String {
        val parsed = Url(baseUrl)
        require(parsed.protocol.name == "https" && parsed.user == null && parsed.password == null) {
            "source-config base URL must be credential-free HTTPS"
        }
        require(parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) {
            "source-config base URL must not contain query or fragment"
        }
        return baseUrl.trimEnd('/') + DOCUMENT_PATH
    }

    private companion object {
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024L
        const val DOCUMENT_PATH = "/api/v1/source-config/document"
    }
}
