package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceRevisionArtifact

/** Scoped HTTPS transport bounding decoded catalog bytes; the borrowed client must omit HttpCache. */
class KtorRemoteSourceCatalog(
    private val httpClient: HttpClient,
    configuration: SourceRemoteConfiguration,
) : RemoteSourceCatalog {
    init {
        // HttpCache can eagerly buffer a response before the scoped streaming reader is reached.
        require(httpClient.pluginOrNull(HttpCache) == null) { "source catalog requires an uncached HTTP client" }
    }

    private val baseUrl = configuration.baseUrl.takeIf { configuration.enabled }?.let(::validatedBaseUrl)
    private val appVersion = configuration.appVersion

    override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult {
        val origin = baseUrl ?: return SourceCatalogManifestResult.Unavailable
        return httpClient
            .prepareGet(origin + MANIFEST_PATH) {
                parameter("appVersion", appVersion)
                // Operational-mode changes must be revalidated with the authority on every
                // foreground poll. The ETag still makes an unchanged catalog a cheap 304.
                header(HttpHeaders.CacheControl, "no-cache")
                etag?.let { header(HttpHeaders.IfNoneMatch, quotedEtag(it)) }
            }.execute { response ->
                if (response.status == HttpStatusCode.NotModified) {
                    return@execute SourceCatalogManifestResult.NotModified
                }
                check(response.status == HttpStatusCode.OK) {
                    "source-catalog manifest returned HTTP ${response.status.value}"
                }
                response.requireJsonContentType()
                val metadata = response.manifestMetadata()
                val payload = response.boundedBody(MAX_MANIFEST_BYTES, "source-catalog manifest")
                SourceCatalogManifestResult.Modified(SignedSourceCatalogManifest(payload, metadata))
            }
    }

    private fun HttpResponse.manifestMetadata(): ConfigSignatureMetadata {
        val previousRevision = headers["X-Config-Previous-Revision"]?.toLong()
        val previousChecksum = headers["X-Config-Previous-Checksum"]
        require((previousRevision == null) == (previousChecksum == null)) {
            "source-catalog chain metadata is incomplete"
        }
        val checksum = requiredHeader("X-Config-Checksum")
        require(etagValue() == checksum) { "source-catalog manifest ETag mismatch" }
        return ConfigSignatureMetadata(
            format = requiredHeader("X-Config-Signature-Format"),
            algorithm = requiredHeader("X-Config-Signature-Algorithm"),
            keyId = requiredHeader("X-Config-Signing-Key-Id"),
            signatureBase64 = requiredHeader("X-Config-Signature"),
            revision = requiredHeader("X-Config-Revision").toLong(),
            checksum = checksum,
            createdAt = requiredHeader("X-Config-Created-At"),
            previousRevision = previousRevision,
            previousChecksum = previousChecksum,
        )
    }

    override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
        val origin = requireNotNull(baseUrl) { "source catalog remote is not configured" }
        val encodedApi = entry.api.encodeURLPathPart()
        val path = "$SOURCE_PATH/$encodedApi/revisions/${entry.sourceRevision}"
        return httpClient.prepareGet(origin + path).execute { response ->
            check(response.status == HttpStatusCode.OK) {
                "source revision returned HTTP ${response.status.value}"
            }
            response.requireJsonContentType()
            response.requireSourceMetadata(entry)
            SourceRevisionArtifact(
                api = entry.api,
                sourceRevision = entry.sourceRevision,
                checksum = entry.checksum,
                canonVersion = CANON_VERSION,
                payload = response.boundedBody(MAX_SOURCE_BYTES, "source revision"),
            )
        }
    }

    private fun HttpResponse.requireSourceMetadata(entry: SourceCatalogEntry) {
        val responseApi = requiredHeader("X-Source-Api")
        val responseRevision = requiredHeader("X-Source-Revision").toLong()
        val responseChecksum = requiredHeader("X-Source-Checksum")
        val canonVersion = requiredHeader("X-Source-Canon-Version")
        require(responseApi == entry.api && responseRevision == entry.sourceRevision) {
            "source revision identity metadata mismatch"
        }
        require(responseChecksum == entry.checksum && etagValue() == entry.checksum) {
            "source revision checksum metadata mismatch"
        }
        require(canonVersion == CANON_VERSION) { "unsupported source canonicalization" }
    }

    private suspend fun HttpResponse.boundedBody(
        limit: Int,
        label: String,
    ): String {
        headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { check(it <= limit.toLong()) }
        val channel = bodyAsChannel()
        try {
            val bytes = ByteArray(limit + 1)
            var size = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(bytes, size, minOf(READ_CHUNK_BYTES, bytes.size - size))
                if (count == -1) {
                    break
                }
                size += count
                // Reject on the extra byte, without awaiting EOF or another chunk.
                check(size <= limit) { "$label exceeds the configured size limit" }
            }
            channel.closedCause?.let { throw it }
            currentCoroutineContext().ensureActive()
            return bytes.decodeToString(endIndex = size, throwOnInvalidSequence = true)
        } finally {
            // Also unblock the client-owned copier if it is backpressured on this consumed channel.
            channel.cancel()
        }
    }

    private fun HttpResponse.requiredHeader(name: String): String =
        requireNotNull(headers[name]?.takeIf(String::isNotBlank)) { "response is missing $name" }

    private fun HttpResponse.etagValue(): String = requiredHeader(HttpHeaders.ETag).removeSurrounding("\"")

    private fun HttpResponse.requireJsonContentType() {
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
        const val MAX_MANIFEST_BYTES = 5 * 1024 * 1024
        const val MAX_SOURCE_BYTES = 256 * 1024
        const val READ_CHUNK_BYTES = 8 * 1024
    }
}
