package me.manga.kira.data.identity

import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.PreviousHostAuthority

/** Exact local recovery is separate from authority to equate different URLs. */
sealed interface WorkAliasComparison {
    data object Exact : WorkAliasComparison
    data object DeclaredAlias : WorkAliasComparison
    data object Distinct : WorkAliasComparison
    data class Rejected(val reason: WorkAliasRejection) : WorkAliasComparison
}

/** Unsupported identity evidence must not silently become permission to insert or rewrite. */
enum class WorkAliasRejection {
    InvalidApi,
    MalformedUrl,
    UnsupportedScheme,
    MalformedHost,
    UnsupportedHost,
    UserInfo,
    NonDefaultPort,
    MissingAcceptedRule,
    AmbiguousAcceptedRule,
    InvalidAcceptedBaseUrl,
    InvalidDeclaredPageHost,
    NonRootBaseUrl,
    KnownBasePathChange,
    UnprovedPreviousHost,
    UnverifiedOrigin,
    UndeclaredPageHost,
}

/**
 * Pure, source-scoped comparison under one immutable accepted snapshot.
 *
 * Declared aliases require valid current/previous page hosts, a root accepted base and a compatible
 * transition. HTTP and HTTPS are distinct; explicit scheme-default ports equal omission. The path,
 * query and fragment suffix stays byte-for-byte, including empty versus slash. No title, image,
 * trust-only host, user mirror or fetched redirect can extend this authority.
 */
class WorkAliasPolicy(private val snapshot: SourceAliasSnapshot) {
    /** The exact accepted catalog underlying every comparison made by this instance. */
    val token: AcceptedCatalogToken get() = snapshot.token

    /** Exact same-source raw identity remains recoverable even without usable alias authority. */
    fun compare(first: WorkLocator, second: WorkLocator): WorkAliasComparison {
        if (first.api != second.api) return WorkAliasComparison.Distinct
        if (first.url == second.url) return WorkAliasComparison.Exact
        val rawLeft = parseIdentityUrl(first.url)
        val rawRight = parseIdentityUrl(second.url)
        if (rawLeft is IdentityUrlRead.Parsed && rawRight is IdentityUrlRead.Parsed && rawLeft.url.scheme != rawRight.url.scheme) {
            return WorkAliasComparison.Distinct
        }
        val left = when (val parsed = authorizedPage(first)) {
            is IdentityUrlRead.Parsed -> parsed.url
            is IdentityUrlRead.Rejected -> return WorkAliasComparison.Rejected(parsed.reason)
        }
        val right = when (val parsed = authorizedPage(second)) {
            is IdentityUrlRead.Parsed -> parsed.url
            is IdentityUrlRead.Rejected -> return WorkAliasComparison.Rejected(parsed.reason)
        }
        return if (left.scheme == right.scheme && left.suffix == right.suffix) {
            WorkAliasComparison.DeclaredAlias
        } else {
            WorkAliasComparison.Distinct
        }
    }

    /**
     * Gate for an unowned request. Comparing a locator with itself is always Exact and is NOT an
     * insertion check; only a complete owner resolution may return Missing for insertion.
     */
    fun unownedRequestRejection(request: WorkLocator): WorkAliasRejection? =
        when (val parsed = authorizedPage(request)) {
            is IdentityUrlRead.Parsed -> null
            is IdentityUrlRead.Rejected -> parsed.reason
        }

    private fun authorizedPage(locator: WorkLocator): IdentityUrlRead {
        if (locator.api.isBlank()) return IdentityUrlRead.Rejected(WorkAliasRejection.InvalidApi)
        val url = when (val parsed = parseIdentityUrl(locator.url)) {
            is IdentityUrlRead.Parsed -> parsed.url
            is IdentityUrlRead.Rejected -> return parsed
        }
        val rule = when (val lookup = snapshot.ruleFor(locator.api)) {
            is AliasRuleLookup.Found -> lookup.rule
            AliasRuleLookup.Unavailable -> return IdentityUrlRead.Rejected(WorkAliasRejection.MissingAcceptedRule)
            AliasRuleLookup.Ambiguous -> return IdentityUrlRead.Rejected(WorkAliasRejection.AmbiguousAcceptedRule)
        }
        declaredHostRejection(rule, url)?.let { return IdentityUrlRead.Rejected(it) }
        return IdentityUrlRead.Parsed(url)
    }

    private fun declaredHostRejection(rule: AcceptedSourceAliasRule, url: IdentityUrl): WorkAliasRejection? {
        val base = when (val parsed = parseIdentityUrl(rule.currentBaseUrl)) {
            is IdentityUrlRead.Parsed -> parsed.url
            is IdentityUrlRead.Rejected -> return WorkAliasRejection.InvalidAcceptedBaseUrl
        }
        if (base.suffix.isNotEmpty() && base.suffix != "/") return WorkAliasRejection.NonRootBaseUrl
        if (url.scheme != base.scheme) return WorkAliasRejection.UnverifiedOrigin
        if (url.host == base.host) return null
        val previous = rule.previousPageHosts()
        if (previous.any { pageHostRejection(it.host) != null }) return WorkAliasRejection.InvalidDeclaredPageHost
        return when (rule.authority(url.host)) {
            PreviousHostAuthority.PROVEN_COMPATIBLE_ROOT -> null
            PreviousHostAuthority.PROVEN_INCOMPATIBLE -> WorkAliasRejection.KnownBasePathChange
            PreviousHostAuthority.UNKNOWN -> WorkAliasRejection.UnprovedPreviousHost
            null -> WorkAliasRejection.UndeclaredPageHost
        }
    }
}
