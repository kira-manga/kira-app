package me.manga.kira.sources.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.SourceResponse
import me.manga.kira.sources.contracts.SourceRevisionArtifact

/** Test-only outer envelope; signed payloads and metadata use the production consumer models. */
@Serializable
internal data class BootstrapSignedCatalogFixture(
    val fixtureVersion: Int,
    val verificationKeys: Map<String, String>,
    val manifest: SignedSourceCatalogManifest,
    val sources: List<SourceRevisionArtifact>,
) {
    companion object {
        const val VERSION = 1
        const val BUNDLED_REVISION = 6L
        const val CATALOG_REVISION = 100L
        const val SOURCE_REVISION = 1L
        const val SOURCE_COUNT = 12
        const val CREATED_AT = "2026-09-13T00:00:00Z"
        const val KEY_ID = "backend22-bootstrap-test-only-v1"

        // PUBLIC TEST VECTOR ONLY: RFC 8032 section 7.1 TEST 1, RFC 8410 X.509 encoding.
        // This pin never enters production runtime configuration or a release artifact.
        private const val PUBLIC_KEY = "MCowBQYDK2VwAyEA11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
        val PINNED_KEYS: Map<String, String> = mapOf(KEY_ID to PUBLIC_KEY)
        private const val RESOURCE = "/fixtures/bootstrap-v2-v6-signed.json"

        fun load(): BootstrapSignedCatalogFixture {
            val bytes =
                requireNotNull(BootstrapSignedCatalogFixture::class.java.getResourceAsStream(RESOURCE)) {
                    "Missing backend-produced $RESOURCE; transfer the reviewed producer artifact, do not synthesize it"
                }.use { it.readBytes() }
            return Json.decodeFromString(
                BootstrapSignedCatalogFixture.serializer(),
                bytes.decodeToString(throwOnInvalidSequence = true),
            )
        }
    }
}

/** A complete in-memory tier, not a claim about Room transactions or process-death persistence. */
internal class BootstrapCatalogMemoryStore : SourceSelectionTestStore(CONFIG_BACKED_SOURCES_JSON) {
    val activations: Int get() = activationCount
}

/** Returns the retained backend bytes, without re-encoding any signed body or metadata. */
internal class BootstrapCatalogRemote(
    private val fixture: BootstrapSignedCatalogFixture,
) : RemoteSourceCatalog {
    val requestedEtags = mutableListOf<String?>()
    val fetchedEntries = mutableListOf<SourceCatalogEntry>()
    var deliverManifest = true

    override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult {
        requestedEtags += etag
        return if (deliverManifest) SourceCatalogManifestResult.Modified(fixture.manifest) else SourceCatalogManifestResult.Unavailable
    }

    override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
        fetchedEntries += entry
        return fixture.sources.single {
            it.api == entry.api && it.sourceRevision == entry.sourceRevision && it.checksum == entry.checksum
        }
    }
}

/** The expected opt-in suffix is independent of the activated descriptor's endpoint template. */
internal class BootstrapAzoraDetailsHttp(
    baseUrl: String,
) : HttpExecutor {
    val itemUrl = "$baseUrl/api/post/?postId=92"
    val expectedUrl = "$itemUrl&includeChapters=true"
    val requests = mutableListOf<SourceRequest>()
    private val delegate = MapFakeHttp(mapOf(expectedUrl to AZORA_DETAILS_JSON))

    override suspend fun execute(request: SourceRequest): SourceResponse {
        requests += request
        return delegate.execute(request)
    }
}
