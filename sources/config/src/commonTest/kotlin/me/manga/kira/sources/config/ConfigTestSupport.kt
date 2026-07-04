package me.manga.kira.sources.config

import me.manga.kira.sources.contracts.ConfigSignatureVerifier
import me.manga.kira.sources.contracts.ConfigStore
import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.ValidationResult
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/** In-memory [ConfigStore]: a fixed bundled asset + a mutable cache that records writes. */
class FakeConfigStore(
    private val bundled: String?,
    private var cached: String? = null,
) : ConfigStore {
    var writeCount = 0
        private set
    var lastWritten: String? = null
        private set

    override fun readBundled(): String? = bundled
    override suspend fun readCached(): String? = cached
    override suspend fun writeCached(raw: String) {
        cached = raw
        lastWritten = raw
        writeCount++
    }
}

/** Verifier whose verdict is fixed by the test (true = signature trusted). */
class FakeVerifier(private val result: Boolean) : ConfigSignatureVerifier {
    override fun verify(payload: ByteArray, signatureBase64: String): Boolean = result
}

/** Minimal validator: accepts schemaVersion 1, rejects anything else — enough to test drop/keep paths. */
class SchemaOnlyValidator : SourceConfigValidator {
    override fun validate(document: SourceConfigDocument): ValidationResult =
        if (document.schemaVersion == 1) ValidationResult.OK else ValidationResult.failed(listOf("bad schema"))
}

/** A source descriptor for [configJson]. [label] rides on displayName so tests can see which doc won. */
data class SourceJson(val api: String, val priority: Int = 0, val label: String = api)

/** Builds a raw config-document JSON string (the on-the-wire form the manager parses). */
fun configJson(revision: Long, sources: List<SourceJson>, schemaVersion: Int = 1): String {
    val src = sources.joinToString(",") { s ->
        """{"api":"${s.api}","language":"en","baseUrl":"https://${s.api}.test","priority":${s.priority},"displayName":"${s.label}","engine":"legacy"}"""
    }
    return """{"schemaVersion":$schemaVersion,"revision":$revision,"sources":[$src]}"""
}
