package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceRevisionArtifact

/** Bounded HTTPS transport for the v2 signed manifest and immutable source revisions. */
class KtorRemoteSourceCatalog(
    private val httpClient: HttpClient,
    configuration: SourceRemoteConfiguration,
) : RemoteSourceCatalog {
    private val baseUrl = configuration.baseUrl.takeIf { configuration.enabled }?.let(::validatedBaseUrl)
    private val appVersion = configuration.appVersion

    override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult {
        val origin = baseUrl ?: return SourceCatalogManifestResult.Unavailable
        val response =
            httpClient.get(origin + MANIFEST_PATH) {
                parameter("appVersion", appVersion)
                etag?.let { header(HttpHeaders.IfNoneMatch, quotedEtag(it)) }
            }
        if (response.status == HttpStatusCode.NotModified) {
            return SourceCatalogManifestResult.NotModified
        }
        check(response.status == HttpStatusCode.OK) {
            "source-catalog manifest returned HTTP ${response.status.value}"
        }
        response.requireJsonContentType()
        val bytes = response.boundedBody(MAX_MANIFEST_BYTES, "source-catalog manifest")
        val previousRevision = response.headers["X-Config-Previous-Revision"]?.toLong()
        val previousChecksum = response.headers["X-Config-Previous-Checksum"]
        require((previousRevision == null) == (previousChecksum == null)) {
            "source-catalog chain metadata is incomplete"
        }
        val checksum = response.requiredHeader("X-Config-Checksum")
        require(response.etagValue() == checksum) { "source-catalog manifest ETag mismatch" }
        return SourceCatalogManifestResult.Modified(
            SignedSourceCatalogManifest(
                payload = bytes.decodeToString(throwOnInvalidSequence = true),
                metadata =
                    ConfigSignatureMetadata(
                        format = response.requiredHeader("X-Config-Signature-Format"),
                        algorithm = response.requiredHeader("X-Config-Signature-Algorithm"),
                        keyId = response.requiredHeader("X-Config-Signing-Key-Id"),
                        signatureBase64 = response.requiredHeader("X-Config-Signature"),
                        revision = response.requiredHeader("X-Config-Revision").toLong(),
                        checksum = checksum,
                        createdAt = response.requiredHeader("X-Config-Created-At"),
                        previousRevision = previousRevision,
                        previousChecksum = previousChecksum,
                    ),
            ),
        )
    }

    override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
        val origin = requireNotNull(baseUrl) { "source catalog remote is not configured" }
        val encodedApi = entry.api.encodeURLPathPart()
        val path = "$SOURCE_PATH/$encodedApi/revisions/${entry.sourceRevision}"
        val response = httpClient.get(origin + path)
        check(response.status == HttpStatusCode.OK) {
            "source revision returned HTTP ${response.status.value}"
        }
        response.requireJsonContentType()
        val bytes = response.boundedBody(MAX_SOURCE_BYTES, "source revision")
        val responseApi = response.requiredHeader("X-Source-Api")
        val responseRevision = response.requiredHeader("X-Source-Revision").toLong()
        val responseChecksum = response.requiredHeader("X-Source-Checksum")
        val canonVersion = response.requiredHeader("X-Source-Canon-Version")
        require(responseApi == entry.api && responseRevision == entry.sourceRevision) {
            "source revision identity metadata mismatch"
        }
        require(responseChecksum == entry.checksum && response.etagValue() == entry.checksum) {
            "source revision checksum metadata mismatch"
        }
        require(canonVersion == CANON_VERSION) { "unsupported source canonicalization" }
        return SourceRevisionArtifact(
            api = responseApi,
            sourceRevision = responseRevision,
            checksum = responseChecksum,
            canonVersion = canonVersion,
            payload = bytes.decodeToString(throwOnInvalidSequence = true),
        )
    }

    private suspend fun io.ktor.client.statement.HttpResponse.boundedBody(
        limit: Long,
        label: String,
    ): ByteArray {
        headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { check(it <= limit) }
        val bytes = body<ByteArray>()
        check(bytes.size.toLong() <= limit) { "$label exceeds the configured size limit" }
        return bytes
    }

    private fun io.ktor.client.statement.HttpResponse.requiredHeader(name: String): String =
        requireNotNull(headers[name]?.takeIf(String::isNotBlank)) { "response is missing $name" }

    private fun io.ktor.client.statement.HttpResponse.etagValue(): String =
        requiredHeader(HttpHeaders.ETag).removeSurrounding("\"")

    private fun io.ktor.client.statement.HttpResponse.requireJsonContentType() {
        require(requiredHeader(HttpHeaders.ContentType).startsWith(APPLICATION_JSON)) {
            "source-catalog response must be application/json"
        }
    }

    private fun validatedBaseUrl(value: String): String {
        val parsed = Url(value)
        require(parsed.protocol.name == "https" && parsed.user == null && parsed.password == null) {
            "source-config base URL must be credential-free HTTPS"
        }
        require(parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) {
            "source-config base URL must not contain query or fragment"
        }
        return value.trimEnd('/')
    }

    private fun quotedEtag(value: String): String = "\"$value\""

    private companion object {
        const val MANIFEST_PATH = "/api/v2/source-config/manifest"
        const val SOURCE_PATH = "/api/v2/source-config/sources"
        const val CANON_VERSION = "kcj-1"
        const val APPLICATION_JSON = "application/json"
        const val MAX_MANIFEST_BYTES = 5L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 256L * 1024
    }
}
