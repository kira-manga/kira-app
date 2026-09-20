package me.manga.kira.data.identity

import me.manga.kira.data.repository.selection.SelectionPlanningProgress
import me.manga.kira.data.repository.selection.SelectionVisit
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.sources.contracts.SourceSelectionToken
import me.manga.kira.sources.contracts.SourceSelectionUnavailable

/** Private candidate policy, never the public committed snapshot provider or a URL canonicalizer. */
internal class SelectionUrlRewrites private constructor(
    token: SourceSelectionToken,
    rules: Collection<AcceptedSourceAliasRule>,
    private val progress: SelectionPlanningProgress,
) {
    private val accepted = rules.toList()
    private val snapshot = SourceAliasSnapshot(token, accepted)
    val policy = WorkAliasPolicy(snapshot)
    private val previousHosts = mutableSetOf<String>()
    private val readings = mutableMapOf<WorkLocator, SelectionLocatorRead>()
    private val comparisons = mutableMapOf<Pair<WorkLocator, WorkLocator>, WorkAliasComparison>()
    private val bases = mutableMapOf<String, IdentityUrl>()

    /** Rejected and opposite-scheme locators stay in this complete cache and all row/ID indices. */
    suspend fun read(locator: WorkLocator): SelectionLocatorRead {
        progress.visit(SelectionVisit.LOOKUP)
        readings[locator]?.let { return it }
        progress.visit(SelectionVisit.CLASSIFY)
        return progress.around {
            val parsed = parseIdentityUrl(locator.url)
            val rejection = policy.unownedRequestRejection(locator)
            val key =
                if (rejection == null) {
                    val url = (parsed as IdentityUrlRead.Parsed).url
                    SelectionAliasKey(locator.api, url.scheme, url.suffix)
                } else {
                    null
                }
            SelectionLocatorRead(parsed, rejection, affected(locator.url, parsed), key).also { readings[locator] = it }
        }
    }

    /**
     * Seed-only authority extraction deliberately also recognizes malformed old-host URLs. Userinfo,
     * bad escapes, unsupported schemes/ports and trailing dots must reach the strict parser and fail,
     * not disappear from coverage. This tolerant observation NEVER authorizes a comparison or move.
     */
    private fun affected(
        raw: String,
        parsed: IdentityUrlRead,
    ): Boolean {
        if (parsed is IdentityUrlRead.Parsed) return parsed.url.host in previousHosts
        val authority =
            raw
                .substringAfter("://", raw.removePrefix("//"))
                .substringBefore('/')
                .substringBefore('\\')
                .substringBefore('?')
                .substringBefore('#')
        val host =
            authority
                .substringAfterLast('@')
                .substringBefore(':')
                .trim()
                .trimEnd('.')
                .lowercase()
        return host in previousHosts
    }

    /** Supported current spellings are retained exactly; an old origin requires actual DeclaredAlias. */
    suspend fun destination(locator: WorkLocator): String {
        val original = supported(locator)
        val rule = rule(locator.api)
        val base = parsed(rule.currentBaseUrl)
        if (original.host == base.host) return locator.url
        val origin = rule.currentBaseUrl.dropLast(base.suffix.length)
        return checked(locator, origin + original.suffix)
    }

    /** Saved-row precedence may align an affected anchor, but it cannot waive alias authorization. */
    suspend fun checked(
        original: WorkLocator,
        destination: String,
    ): String {
        if (
            original.url != destination &&
            compare(original, WorkLocator(original.api, destination)) != WorkAliasComparison.DeclaredAlias
        ) {
            throw SourceSelectionUnavailable("selection URL move lacks declared alias authority")
        }
        return destination
    }

    /** Cache an actual policy comparison, not an inferred permission from equal indexed keys. */
    suspend fun compare(
        first: WorkLocator,
        second: WorkLocator,
    ): WorkAliasComparison {
        progress.visit(SelectionVisit.LOOKUP)
        val pair = first to second
        comparisons[pair]?.let { return it }
        return progress.around { policy.compare(first, second).also { comparisons[pair] = it } }
    }

    /** After policy validation, scheme + exact suffix is precisely the existing alias equivalence. */
    suspend fun key(locator: WorkLocator): SelectionAliasKey =
        read(locator).key
            ?: throw SourceSelectionUnavailable("unsupported selection family locator")

    private suspend fun supported(locator: WorkLocator): IdentityUrl {
        val value = read(locator)
        value.rejection?.let {
            throw SourceSelectionUnavailable("unsupported selection family locator: $it")
        }
        return (value.parsed as IdentityUrlRead.Parsed).url
    }

    private fun rule(api: String): AcceptedSourceAliasRule =
        when (val found = snapshot.ruleFor(api)) {
            is AliasRuleLookup.Found -> found.rule
            AliasRuleLookup.Ambiguous, AliasRuleLookup.Unavailable ->
                throw SourceSelectionUnavailable("selection alias rule is unavailable or ambiguous")
        }

    private suspend fun parsed(raw: String): IdentityUrl {
        progress.visit(SelectionVisit.LOOKUP)
        bases[raw]?.let { return it }
        return progress.around {
            when (val value = parseIdentityUrl(raw)) {
                is IdentityUrlRead.Parsed -> value.url.also { bases[raw] = it }
                is IdentityUrlRead.Rejected ->
                    throw SourceSelectionUnavailable("unsupported selection URL: ${value.reason}")
            }
        }
    }

    companion object {
        suspend fun create(
            token: SourceSelectionToken,
            rules: Collection<AcceptedSourceAliasRule>,
            progress: SelectionPlanningProgress,
        ): SelectionUrlRewrites {
            val value = progress.around { SelectionUrlRewrites(token, rules, progress) }
            for (rule in value.accepted) {
                progress.visit(SelectionVisit.ROW)
                for (host in rule.previousPageHosts()) {
                    progress.visit(SelectionVisit.ROW)
                    value.previousHosts += host.host.lowercase()
                }
            }
            return value
        }
    }
}

internal data class SelectionAliasKey(
    val api: String,
    val scheme: String,
    val suffix: String,
)

internal data class SelectionLocatorRead(
    val parsed: IdentityUrlRead,
    val rejection: WorkAliasRejection?,
    val affected: Boolean,
    val key: SelectionAliasKey?,
)
