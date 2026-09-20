package me.manga.kira.data.identity

/**
 * Narrow identity parser, not a URL canonicalizer. Only scheme and ASCII DNS host case are folded;
 * default ports are recognized numerically. The suffix is never decoded, trimmed or rewritten.
 * Unicode hosts, bracketed IP literals, all-numeric hosts and trailing-dot authorities are
 * unsupported for aliases. Exact retained identities remain recoverable by the caller's exact branch.
 */
internal fun parseIdentityUrl(raw: String): IdentityUrlRead {
    if (raw.any(::isForbiddenUrlChar) || !hasValidEscapes(raw)) return rejectedUrl(WorkAliasRejection.MalformedUrl)
    val separator = raw.indexOf(SCHEME_SEPARATOR)
    if (separator <= 0) return rejectedUrl(WorkAliasRejection.MalformedUrl)
    val scheme = raw.substring(0, separator).lowercase()
    if (scheme != HTTP_SCHEME && scheme != HTTPS_SCHEME) return rejectedUrl(WorkAliasRejection.UnsupportedScheme)
    val authorityStart = separator + SCHEME_SEPARATOR.length
    val suffixStart = raw.indexOfAny(charArrayOf('/', '?', '#'), authorityStart).takeIf { it >= 0 } ?: raw.length
    val authority = raw.substring(authorityStart, suffixStart)
    authorityRejection(authority, scheme)?.let { return rejectedUrl(it) }
    return IdentityUrlRead.Parsed(
        IdentityUrl(scheme, authority.substringBefore(':').lowercase(), raw.substring(suffixStart)),
    )
}

internal fun pageHostRejection(host: String): WorkAliasRejection? {
    if (host.isEmpty() || host.length > MAX_DNS_HOST_LENGTH) return WorkAliasRejection.MalformedHost
    if (
        host.any { it.code > ASCII_END } || host.startsWith('[') || host.endsWith('.') ||
        host.all { it in '0'..'9' || it == '.' }
    ) {
        return WorkAliasRejection.UnsupportedHost
    }
    return if (host.split('.').all(::isDnsLabel)) null else WorkAliasRejection.MalformedHost
}

private fun authorityRejection(authority: String, scheme: String): WorkAliasRejection? {
    if ('@' in authority) return WorkAliasRejection.UserInfo
    if (authority.startsWith('[') || authority.count { it == ':' } > 1) return WorkAliasRejection.UnsupportedHost
    pageHostRejection(authority.substringBefore(':'))?.let { return it }
    if (':' !in authority) return null
    val port = authority.substringAfter(':')
    if (port.isEmpty() || port.any { it !in '0'..'9' }) return WorkAliasRejection.MalformedUrl
    val defaultPort = if (scheme == HTTP_SCHEME) HTTP_DEFAULT_PORT else HTTPS_DEFAULT_PORT
    return if (port.toIntOrNull() == defaultPort) null else WorkAliasRejection.NonDefaultPort
}

private fun isDnsLabel(label: String): Boolean =
    label.isNotEmpty() && label.length <= MAX_DNS_LABEL_LENGTH &&
        label.first().isAsciiLetterOrDigit() && label.last().isAsciiLetterOrDigit() &&
        label.all { it.isAsciiLetterOrDigit() || it == '-' }

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun isForbiddenUrlChar(value: Char): Boolean =
    value == '\\' || value in FORBIDDEN_URL_ASCII || value.isWhitespace() || value.code <= LOW_CONTROL_END ||
        value.code in HIGH_CONTROL_START..HIGH_CONTROL_END

private fun hasValidEscapes(raw: String): Boolean {
    var index = raw.indexOf('%')
    while (index >= 0) {
        if (
            index + PERCENT_ESCAPE_LENGTH > raw.length ||
            !raw[index + 1].isAsciiHexDigit() || !raw[index + 2].isAsciiHexDigit()
        ) {
            return false
        }
        index = raw.indexOf('%', index + PERCENT_ESCAPE_LENGTH)
    }
    return true
}

private fun Char.isAsciiHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun rejectedUrl(reason: WorkAliasRejection) = IdentityUrlRead.Rejected(reason)

internal data class IdentityUrl(val scheme: String, val host: String, val suffix: String)

internal sealed interface IdentityUrlRead {
    data class Parsed(val url: IdentityUrl) : IdentityUrlRead
    data class Rejected(val reason: WorkAliasRejection) : IdentityUrlRead
}

private const val SCHEME_SEPARATOR = "://"
private const val HTTP_SCHEME = "http"
private const val HTTPS_SCHEME = "https"
private const val HTTP_DEFAULT_PORT = 80
private const val HTTPS_DEFAULT_PORT = 443
private const val MAX_DNS_HOST_LENGTH = 253
private const val MAX_DNS_LABEL_LENGTH = 63
private const val ASCII_END = 127
private const val LOW_CONTROL_END = 31
private const val HIGH_CONTROL_START = 127
private const val HIGH_CONTROL_END = 159
private const val PERCENT_ESCAPE_LENGTH = 3
private const val FORBIDDEN_URL_ASCII = "\"<>^`{|}"
