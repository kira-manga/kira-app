package me.manga.kira.sources.runtime

/** Values are generated from release Gradle properties; an incomplete set leaves remote fail-closed. */
interface SourceRemoteConfiguration {
    val baseUrl: String
    val appVersion: String
    val pinnedPublicKeys: Map<String, String>
    val enabled: Boolean

    companion object {
        fun fromGenerated(): SourceRemoteConfiguration =
            create(
                baseUrl = GeneratedSourceRemoteConfig.BASE_URL,
                appVersion = GeneratedSourceRemoteConfig.APP_VERSION,
                pinnedPublicKeys = parseKeys(GeneratedSourceRemoteConfig.PINNED_KEYS),
            )

        internal fun create(
            baseUrl: String,
            appVersion: String,
            pinnedPublicKeys: Map<String, String>,
        ): SourceRemoteConfiguration = ImmutableSourceRemoteConfiguration(baseUrl, appVersion, pinnedPublicKeys)

        internal fun parseKeys(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val entries =
                raw.split(',').associate { entry ->
                    val separator = entry.indexOf('=')
                    require(separator > 0) { "source config keys must use key-id=base64 format" }
                    entry.substring(0, separator) to entry.substring(separator + 1)
                }
            require(entries.size == raw.split(',').size) { "source config key ids must be unique" }
            return entries
        }
    }
}

private data class ImmutableSourceRemoteConfiguration(
    override val baseUrl: String,
    override val appVersion: String,
    override val pinnedPublicKeys: Map<String, String>,
) : SourceRemoteConfiguration {
    override val enabled: Boolean get() = baseUrl.isNotBlank() && pinnedPublicKeys.isNotEmpty()
}
