package me.manga.kira.sources.contracts

import kotlinx.serialization.Serializable

/**
 * Transport port. The engine never touches Ktor (or any HTTP library) directly — it asks an
 * [HttpExecutor] to run a [SourceRequest] and hands it back a [SourceResponse]. This keeps
 * `:sources:engine` framework-free and unit-testable with a fake executor over golden fixtures; the
 * real Ktor-backed implementation lives at the composition root (`:composeApp`).
 */
interface HttpExecutor {
    suspend fun execute(request: SourceRequest): SourceResponse
}

enum class SourceHttpMethod { GET, POST_FORM, POST_JSON }

data class SourceRequest(
    val url: String,
    val method: SourceHttpMethod = SourceHttpMethod.GET,
    val headers: Map<String, String> = emptyMap(),
    /**
     * Ordered form entries. A list of pairs (not a map) so a repeated key — `genre[]=a&genre[]=b`,
     * the `repeat` filter encoding — is expressible; order is static-config entries first, then
     * filter contributions in declaration order (deterministic wire order).
     */
    val formBody: List<Pair<String, String>>? = null,
    val jsonBody: String? = null,
)

data class SourceResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    /** The final URL after redirects — some sources need it to resolve relative links. */
    val finalUrl: String? = null,
)

/**
 * Per-source captured headers (cookies, user-agent, Cloudflare clearance) persisted by the WebView
 * solver flow. The engine reads these to authenticate requests; it never writes them. Backed at the
 * composition root by the same `DataStoreHelper` the legacy sources already use, so a migrated
 * source reuses headers a user captured before migration.
 */
interface HeaderStore {
    suspend fun headersFor(api: String): Map<String, String>

    suspend fun save(
        api: String,
        headers: Map<String, String>,
    )
}

/**
 * Storage for the raw signed config document. [readBundled] returns the asset shipped in the binary
 * (always present, the floor); [readCached]/[writeCached] hold the last accepted remote document.
 * The bundle is raw JSON; the cache is the complete signed envelope so it can be authenticated again
 * before parsing after every process restart.
 */
interface ConfigStore {
    fun readBundled(): String?

    suspend fun readCached(): SignedConfigDocument?

    suspend fun writeCached(document: SignedConfigDocument)
}

/** Immutable detached-signature metadata supplied by the backend and covered by Ed25519. */
@Serializable
data class ConfigSignatureMetadata(
    val format: String,
    val algorithm: String,
    val keyId: String,
    val signatureBase64: String,
    val revision: Long,
    val checksum: String,
    val createdAt: String,
    val previousRevision: Long? = null,
    val previousChecksum: String? = null,
)

/** Exact UTF-8 document plus all metadata required to verify it again after process death. */
@Serializable
data class SignedConfigDocument(
    val payload: String,
    val metadata: ConfigSignatureMetadata,
)

/**
 * Verifies the detached signature over a config payload before it is ever parsed or trusted. Every
 * cached or remote document requires a valid pinned-key signature; the bundled asset is trusted
 * implicitly because it shipped inside the signed app binary.
 */
interface ConfigSignatureVerifier {
    fun verify(document: SignedConfigDocument): Boolean
}

/**
 * One-way signal raised when the engine detects a Cloudflare/anti-bot interstitial (mirrors the
 * legacy 403→WebView-solver routing). The composition root wires this to the existing WebView
 * challenge flow; the engine stays unaware of any UI.
 */
fun interface CloudflareChallengeSignal {
    fun onChallenge(
        api: String,
        url: String,
    )
}

/**
 * Supplies the live, current base URL for a source — the same one the legacy path follows when a
 * server-pushed / user-edited domain move is stored in the sources DB. The engine prefers this over
 * the (frozen) [me.manga.kira.sources.contracts.model.SourceConfig.baseUrl] so a piloted source
 * whose host moves keeps working without a remote config refresh; null/blank means "no override,
 * use the config's baseUrl". Backed at the composition root by the same source store the legacy
 * repositories read.
 */
fun interface SourceBaseUrlProvider {
    suspend fun baseUrlFor(api: String): String?
}
