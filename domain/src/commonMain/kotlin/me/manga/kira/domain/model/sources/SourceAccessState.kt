package me.manga.kira.domain.model.sources

/** Permanent visibility state for the source-management UI. */
enum class SourceAccessState {
    LOCKED,
    ACTIVATED,
}

/** Result of checking an activation link and persisting source-management access. */
enum class SourceActivationResult {
    INVALID_LINK,
    ACTIVATED,
    ALREADY_ACTIVATED,
}

/** Deliberately simple discoverability check; activation is not a security boundary. */
object SourceActivationLinkValidator {
    fun isValid(link: String): Boolean = link.trim().contains(ACTIVATION_MARKER, ignoreCase = true)

    private const val ACTIVATION_MARKER = "kiramanga"
}
