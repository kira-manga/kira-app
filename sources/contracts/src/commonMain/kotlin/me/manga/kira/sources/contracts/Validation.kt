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
 * messages keyed by api in the text so a single bad source can be diagnosed without failing the rest
 * (the engine may choose to drop only the offending sources — that policy lives in `:sources:config`).
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
