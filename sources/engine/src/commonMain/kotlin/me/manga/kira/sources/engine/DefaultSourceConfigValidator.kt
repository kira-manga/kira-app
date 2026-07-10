package me.manga.kira.sources.engine

import me.manga.kira.sources.contracts.SourceConfigValidator
import me.manga.kira.sources.contracts.StrategyRegistry
import me.manga.kira.sources.contracts.ValidationResult
import me.manga.kira.sources.contracts.model.IconSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.contracts.model.SourceConfigDocument

/**
 * Schema + referential validator. Runs after signature verification, before any source is trusted.
 * Two jobs: (1) the document is structurally sane (supported schema, non-blank keys, URL-shaped
 * base), and (2) every strategy/transform/date/pagination name a `generic` source references is one
 * this build ships (via [StrategyRegistry]). `legacy`/`kotlin:` sources skip the strategy checks —
 * their behavior lives in Kotlin, not the config.
 *
 * Errors are collected (not fail-fast) and keyed by api so a whole batch can be diagnosed at once.
 */
class DefaultSourceConfigValidator(
    private val strategies: StrategyRegistry,
) : SourceConfigValidator {
    override fun validate(document: SourceConfigDocument): ValidationResult {
        val errors = mutableListOf<String>()

        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            errors += "schemaVersion ${document.schemaVersion} is unsupported (this build expects $SUPPORTED_SCHEMA_VERSION)"
            return ValidationResult.failed(errors) // shape may differ entirely; don't probe further
        }

        val seenApis = mutableSetOf<String>()
        for (source in document.sources) {
            validateSource(source, seenApis, errors)
        }

        return if (errors.isEmpty()) ValidationResult.OK else ValidationResult.failed(errors)
    }

    private fun validateSource(
        source: SourceConfig,
        seenApis: MutableSet<String>,
        errors: MutableList<String>,
    ) {
        val tag = "source '${source.api}':"
        if (source.api.isBlank()) errors += "a source has a blank api"
        if (!seenApis.add(source.api)) errors += "$tag duplicate api"
        if (source.language.isBlank()) errors += "$tag blank language"
        if (!source.baseUrl.startsWith("http")) errors += "$tag baseUrl must be an absolute http(s) URL"

        val isGeneric = source.engine == "generic"
        val isLegacy = source.engine == "legacy" || source.engine.startsWith("kotlin:")
        if (!isGeneric && !isLegacy) {
            errors += "$tag unknown engine '${source.engine}' (expected 'generic', 'legacy', or 'kotlin:<id>')"
        }

        // Lifecycle metadata (SourceRegistry retirement §2) applies to EVERY engine — metadata-only
        // legacy stanzas carry exactly these fields, so they are validated before the generic-only
        // early return below.
        validateLifecycleMetadata(source, tag, errors)

        // Legacy sources carry no config-driven behavior to validate.
        if (!isGeneric) return

        if (!strategies.hasPagination(source.pagination.type)) {
            errors += "$tag unknown pagination strategy '${source.pagination.type}'"
        }
        if (source.endpoints["home"] == null && source.endpoints["featured"] == null) {
            errors += "$tag generic source must define at least a 'home' or 'featured' endpoint"
        }
        validateEndpoints(source, tag, errors)
        validateFields(source, tag, errors)
    }

    private fun validateLifecycleMetadata(
        source: SourceConfig,
        tag: String,
        errors: MutableList<String>,
    ) {
        if (source.siteState !in SUPPORTED_SITE_STATES) {
            errors += "$tag unknown siteState '${source.siteState}' (expected one of $SUPPORTED_SITE_STATES)"
        }
        if (source.lifecycle !in SUPPORTED_LIFECYCLES) {
            errors += "$tag unknown lifecycle '${source.lifecycle}' (expected one of $SUPPORTED_LIFECYCLES)"
        }
        validateHostList(tag, "previousHosts", source.previousHosts, errors)
        validateHostList(tag, "previousImageHosts", source.previousImageHosts, errors)
        validateHostList(tag, "trustedHosts", source.trustedHosts, errors)
        source.icon?.let { validateIcon(it, tag, errors) }
    }

    // Icons are render-only, but a malformed descriptor must die at validation (fail-closed), not
    // silently never resolve: a key outside the registry's `[a-z0-9_]` vocabulary or a non-https
    // URL would look authored-and-working while always falling through to the fallback avatar.
    private fun validateIcon(
        icon: IconSpec,
        tag: String,
        errors: MutableList<String>,
    ) {
        if (icon.resourceKey.isNotEmpty() && !icon.resourceKey.matches(ICON_RESOURCE_KEY)) {
            errors += "$tag icon resourceKey '${icon.resourceKey}' must match [a-z0-9_]{1,64}"
        }
        if (icon.remoteUrl.isNotEmpty() && !icon.remoteUrl.startsWith("https://")) {
            errors += "$tag icon remoteUrl must be an absolute https URL (no cleartext icons)"
        }
        if (icon.resourceKey.isEmpty() && icon.remoteUrl.isEmpty()) {
            errors += "$tag icon block is present but empty — set resourceKey and/or remoteUrl, or omit it"
        }
    }

    private fun validateEndpoints(
        source: SourceConfig,
        tag: String,
        errors: MutableList<String>,
    ) {
        for ((verb, spec) in source.endpoints) {
            if (spec.url.isBlank()) errors += "$tag endpoint '$verb' has a blank url"
            // The raw {query} var is the UNENCODED search term — legitimate only in formBody
            // values (the executor form-encodes those). In a URL it must be {queryEncoded} and in
            // a JSON body {queryJson}; the raw form would break (or corrupt the body) on the first
            // search containing a space, '&', quote, or backslash. Rejecting at validation keeps
            // the engine's fail-closed posture: a typo'd config falls back to legacy instead of
            // issuing malformed requests. ("{query}" cannot false-match "{queryEncoded}"/
            // "{queryJson}" — the closing brace pins the exact var name.)
            if (spec.url.contains("{query}")) {
                errors += "$tag endpoint '$verb' url uses raw {query} — use {queryEncoded}"
            }
            if (spec.jsonBody.contains("{query}")) {
                errors += "$tag endpoint '$verb' jsonBody uses raw {query} — use {queryJson}"
            }
            if (spec.method.isNotEmpty() && spec.method.lowercase() !in SUPPORTED_METHODS) {
                errors += "$tag endpoint '$verb' has unknown method '${spec.method}'"
            }
            if (spec.format.isNotEmpty() && spec.format !in SUPPORTED_FORMATS) {
                errors += "$tag endpoint '$verb' has unknown format '${spec.format}'"
            }
            for (filter in spec.listFilters) {
                if (filter.op !in SUPPORTED_FILTER_OPS) {
                    errors += "$tag endpoint '$verb' filter references unknown op '${filter.op}'"
                }
                if (filter.mode !in SUPPORTED_FILTER_MODES) {
                    errors += "$tag endpoint '$verb' filter has unknown mode '${filter.mode}'"
                }
            }
        }
    }

    private fun validateFields(
        source: SourceConfig,
        tag: String,
        errors: MutableList<String>,
    ) {
        for ((key, spec) in source.fields) {
            for (transform in spec.transform) {
                if (!strategies.hasTransform(transform.fn)) {
                    errors += "$tag field '$key' references unknown transform '${transform.fn}'"
                }
            }
            if (spec.dateStrategy.isNotEmpty() && !strategies.hasDateStrategy(spec.dateStrategy)) {
                errors += "$tag field '$key' references unknown date strategy '${spec.dateStrategy}'"
            }
            if (spec.imageStrategy.isNotEmpty() && !strategies.hasImageStrategy(spec.imageStrategy)) {
                errors += "$tag field '$key' references unknown image strategy '${spec.imageStrategy}'"
            }
        }
    }

    // The three host lists hold BARE hosts ("azoramoon.co"): matching against a stored URL's host
    // is exact, so a scheme/path/blank entry would silently never match — reject it at validation
    // (fail-closed) instead of letting a malformed entry neuter migration or trust.
    private fun validateHostList(
        tag: String,
        field: String,
        hosts: List<String>,
        errors: MutableList<String>,
    ) {
        for (host in hosts) {
            if (!isBareHost(host)) {
                errors += "$tag $field entry '$host' must be a bare host (no scheme, path, or spaces)"
            }
        }
    }

    private fun isBareHost(host: String): Boolean {
        if (host.isBlank() || host.contains("://")) return false
        // No port suffix either (2026-07 audit): every consumer (urlHost / ConfigHostTrust) compares
        // portless hosts, so a "host:8080" entry would validate yet silently never match — the exact
        // authoring mistake this rule exists to reject.
        return !host.contains('/') && !host.contains(':') && host.none { it.isWhitespace() }
    }

    companion object {
        /** The only schema major this build understands. Bumped only on incompatible model changes. */
        const val SUPPORTED_SCHEMA_VERSION = 1

        // Lifecycle metadata whitelists (SourceRegistry retirement §2). SUPPORTED_SITE_STATES
        // mirrors the persisted SourceState enum (:data:local) — the catalog sync maps the string
        // to the enum, and an unknown value must die here, not silently no-op there.
        private val SUPPORTED_SITE_STATES = setOf("WORKING", "UNDER_MAINTENANCE", "STOPPED", "ADULT_18_PLUS")
        private val SUPPORTED_LIFECYCLES = setOf("active", "disabled", "removed")
        private val ICON_RESOURCE_KEY = Regex("[a-z0-9_]{1,64}")

        // Whitelists mirror the engine's own string handling so a typo in a cached/remote document
        // is rejected at validation rather than silently degrading at runtime (unknown op drops every
        // item, unknown method falls back to GET, unknown format falls into the listSelector heuristic).
        private val SUPPORTED_METHODS =
            setOf(
                "get",
                "post-form",
                "post_form",
                "postform",
                "post-json",
                "post_json",
                "postjson",
            )
        private val SUPPORTED_FORMATS = setOf("json", "html", "script-json")
        private val SUPPORTED_FILTER_OPS = setOf("equals", "notEquals", "contains", "notNull", "isNull")
        private val SUPPORTED_FILTER_MODES = setOf("include", "exclude")
    }
}
