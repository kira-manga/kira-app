package me.manga.kira.sources.config

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.sources.contracts.ConfigSignatureMetadata
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.contracts.SignedSourceCatalogManifest
import me.manga.kira.sources.contracts.SourceCatalogEntry
import me.manga.kira.sources.contracts.SourceCatalogManifestResult
import me.manga.kira.sources.contracts.SourceCatalogSignatureVerifier
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.SourceRevisionArtifact
import me.manga.kira.sources.contracts.StoredSourceCatalog
import me.manga.kira.sources.contracts.ValidationResult
import me.manga.kira.sources.contracts.model.SourceConfigDocument
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Existing manager fixtures extracted without claiming cryptographic or Room evidence. */
internal fun manager(store: FakeCatalogStore, remote: FakeRemote) =
    IncrementalSourceCatalogManager(store, FakeCatalogVerifier, SchemaOnlyValidator, remote)

internal class FakeRemote(
    var manifestResult: SourceCatalogManifestResult,
    private val artifacts: Map<String, SourceRevisionArtifact> = emptyMap(),
) : RemoteSourceCatalog {
    var sourceFetches = 0
        private set
    var manifestFetches = 0
        private set
    val fetchedApis = mutableListOf<String>()
    var beforeSourceFetch: suspend () -> Unit = {}
    var beforeManifestFetch: suspend () -> Unit = {}

    override suspend fun fetchManifest(etag: String?): SourceCatalogManifestResult {
        manifestFetches++
        beforeManifestFetch()
        return manifestResult
    }

    override suspend fun fetchSource(entry: SourceCatalogEntry): SourceRevisionArtifact {
        sourceFetches++
        fetchedApis += entry.api
        beforeSourceFetch()
        return requireNotNull(artifacts[entry.api])
    }
}

internal object FakeCatalogVerifier : SourceCatalogSignatureVerifier {
    override fun verifyManifest(manifest: SignedSourceCatalogManifest): Boolean = true
    override fun verifySource(entry: SourceCatalogEntry, artifact: SourceRevisionArtifact): Boolean =
        entry.api == artifact.api && entry.sourceRevision == artifact.sourceRevision && entry.checksum == artifact.checksum
}

internal object SchemaOnlyValidator : SourceConfigValidator {
    override fun validate(document: SourceConfigDocument): ValidationResult =
        if (document.schemaVersion == 1) ValidationResult.OK else ValidationResult.failed(listOf("bad schema"))
}

internal const val BUNDLED_REVISION = 5L

internal fun bundledJson(): String =
    """{"schemaVersion":1,"revision":$BUNDLED_REVISION,"sources":[${sourceJson("floor") }]}"""

internal fun sourceJson(
    api: String,
    siteState: String = "WORKING",
    baseUrl: String = "https://$api.test",
    previousHosts: List<String> = emptyList(),
): String =
    """{"api":"$api","language":"en","baseUrl":"$baseUrl","engine":"generic","siteState":"$siteState",""" +
        """"previousHosts":[${previousHosts.joinToString(",") { "\"$it\"" }}]}"""

internal fun entry(api: String, revision: Long, lifecycle: String = "active") = SourceCatalogEntry(
    api, revision, checksum(revision), 0, lifecycle, "generic", "test-key", "signature",
)

internal fun artifact(api: String, revision: Long, siteState: String = "WORKING") =
    SourceRevisionArtifact(api, revision, checksum(revision), "kcj-1", sourceJson(api, siteState))

internal fun storedCatalog(
    revision: Long,
    entries: List<Pair<SourceCatalogEntry, SourceRevisionArtifact>>,
    removedApis: List<String> = emptyList(),
) = StoredSourceCatalog(
    signedManifest(revision, entries.mapIndexed { index, pair -> pair.first.copy(order = index) }, removedApis = removedApis),
    entries.map { it.second },
)

internal fun signedManifest(
    revision: Long,
    entries: List<SourceCatalogEntry>,
    previousRevision: Long? = null,
    removedApis: List<String> = emptyList(),
): SignedSourceCatalogManifest {
    val sources = entries.mapIndexed { index, entry -> entry.copy(order = index) }.joinToString(",", transform = ::entryJson)
    val removed = removedApis.joinToString(",") { """{"api":"$it","lifecycle":"removed"}""" }
    val payload = """{"schemaVersion":1,"sourceSchemaVersion":1,"catalogRevision":$revision,""" +
        """"generatedAt":"2026-07-23T00:00:00Z","sources":[$sources],"removedSources":[$removed]}"""
    return SignedSourceCatalogManifest(payload, ConfigSignatureMetadata(
        format = "kira-source-catalog-manifest-v1", algorithm = "Ed25519", keyId = "test-key", signatureBase64 = "signature",
        revision = revision, checksum = checksum(revision), createdAt = "2026-07-23T00:00:00Z",
        previousRevision = previousRevision, previousChecksum = previousRevision?.let(::checksum),
    ))
}

private fun entryJson(entry: SourceCatalogEntry): String =
    """{"api":"${entry.api}","sourceRevision":${entry.sourceRevision},"checksum":"${entry.checksum}",""" +
        """"order":${entry.order},"lifecycle":"${entry.lifecycle}","engine":"${entry.engine}",""" +
        """"sourceSigningKeyId":"${entry.sourceSigningKeyId}","sourceSignature":"${entry.sourceSignature}"}"""

internal fun checksum(value: Long): String = value.toString(16).padStart(64, '0')

/** Synthetic equality marker only. Real SHA-256/serialization is covered in the composition-root tests. */
internal fun syntheticDigest(value: Any): String = checksum(value.hashCode().toLong() and 0xffffffffL)

internal suspend fun cancelRequiredFetch(manager: IncrementalSourceCatalogManager, fetching: CompletableDeferred<Unit>) = coroutineScope {
    val cancelled = CompletableDeferred<CancellationException>()
    val request = launch {
        try { manager.refresh() } catch (cause: CancellationException) { cancelled.complete(cause); throw cause }
    }
    try {
        withTimeout(5_000) { fetching.await() }
        request.cancel(CancellationException("fixture refresh cancellation"))
        request.join()
        assertTrue(cancelled.isCompleted, "refresh must rethrow caller cancellation")
        assertEquals("fixture refresh cancellation", cancelled.await().message)
    } finally { withContext(NonCancellable) { request.cancelAndJoin() } }
}

internal fun verifiedCatalogAt(revision: Long, base: String, previous: List<String> = emptyList()): VerifiedCatalog {
    val source = artifact("floor", revision).copy(payload = sourceJson("floor", baseUrl = base, previousHosts = previous))
    val store = FakeCatalogStore(null)
    return requireNotNull(SourceCatalogVerifier(store, FakeCatalogVerifier, SchemaOnlyValidator, {}).stored(
        storedCatalog(revision, listOf(entry("floor", revision) to source)),
    ))
}
