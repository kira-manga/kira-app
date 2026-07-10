package me.manga.kira.sources.contracts

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

    suspend fun save(api: String, headers: Map<String, String>)
}

/**
 * Storage for the raw signed config document. [readBundled] returns the asset shipped in the binary
 * (always present, the floor); [readCached]/[writeCached] hold the last accepted remote document.
 * All three are raw JSON strings — verification and parsing happen above this port.
 */
interface ConfigStore {
    fun readBundled(): String?

    suspend fun readCached(): String?

    suspend fun writeCached(raw: String)
}

/**
 * Verifies the detached signature over a config payload before it is ever parsed or trusted. Stage-0
 * ships a verifier that requires a valid signature for any non-bundled document; the bundled asset is
 * trusted implicitly because it shipped inside the signed app binary.
 */
interface ConfigSignatureVerifier {
    fun verify(payload: ByteArray, signatureBase64: String): Boolean
}

/**
 * One-way signal raised when the engine detects a Cloudflare/anti-bot interstitial (mirrors the
 * legacy 403→WebView-solver routing). The composition root wires this to the existing WebView
 * challenge flow; the engine stays unaware of any UI.
 */
fun interface CloudflareChallengeSignal {
    fun onChallenge(api: String, url: String)
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
