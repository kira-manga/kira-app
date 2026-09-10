package me.manga.kira.sources.engine.internal

/** Name-only publication policy for header filters; static placeholder values are a separate policy. */
internal object HeaderNamePolicy {
    /** RFC 9110 field-name = token: exact, non-empty ASCII, never trimmed or repaired. */
    fun isHttpFieldName(name: String): Boolean =
        name.isNotEmpty() &&
            name.all { char ->
                char in 'a'..'z' ||
                    char in 'A'..'Z' ||
                    char in '0'..'9' ||
                    char in HTTP_TOKEN_PUNCTUATION
            }

    fun isForbidden(name: String): Boolean = name.lowercase() in FORBIDDEN_HEADER_NAMES

    fun isSensitive(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SENSITIVE_HEADER_NAMES || SENSITIVE_HEADER_SUBSTRINGS.any { it in lower }
    }

    private val FORBIDDEN_HEADER_NAMES = setOf("cookie", "set-cookie", "proxy-authorization")
    private val SENSITIVE_HEADER_NAMES = setOf("authorization", "x-api-key", "api-key", "x-auth-token")
    private val SENSITIVE_HEADER_SUBSTRINGS = listOf("token", "secret", "password")
    private const val HTTP_TOKEN_PUNCTUATION = "!#\$%&'*+-.^_`|~"
}
