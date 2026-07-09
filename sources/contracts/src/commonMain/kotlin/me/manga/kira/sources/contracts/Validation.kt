package me.manga.kira.sources.contracts

import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Schema + referential validation of a parsed [SourceConfigDocument], run before any source from it
 * is trusted. Distinct from signature verification ([ConfigSignatureVerifier], which proves the bytes
 * are authentic): validation proves the *content* is well-formed and references only strategies this
 * build ships. A document that fails validation is rejected wholesale and the previous good document
 * (cached, else bundled) stays active.
 */
interface SourceConfigValidator {
    fun validate(document: SourceConfigDocument): ValidationResult
}

/**
 * Outcome of validation. [errors] is empty iff [isValid]. Per-source problems are reported as
 * messages keyed by api in the text so each bad stanza is individually diagnosable.
 *
 * NOTE (doc corrected 2026-07): acceptance is **all-or-nothing** — `:sources:config`'s
 * `RemoteSourceConfigManager` drops the ENTIRE document when any error exists (no per-source drop
 * policy is implemented). For the bundled tier that means a single bad stanza silently costs every
 * generic source; `ConfigBackedSourceCompletenessTest` in `:composeApp` is the build-time gate that
 * prevents it, and the manager's `onDocumentRejected` hook is the runtime alarm.
 */
data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList(),
) {
    companion object {
        val OK = ValidationResult(isValid = true)

        fun failed(errors: List<String>): ValidationResult =
            ValidationResult(isValid = false, errors = errors)
    }
}
