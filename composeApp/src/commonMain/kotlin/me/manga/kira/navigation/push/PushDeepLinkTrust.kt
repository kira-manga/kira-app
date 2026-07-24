package me.manga.kira.navigation.push

import io.ktor.http.Url
import me.manga.kira.sources.runtime.ConfigHostTrust

/**
 * #8 (intent-redirection guard): true if a push deep-link's URL(s) belong to its own source's
 * domain. `MainActivity` is an exported launcher, so a co-installed app can start it with crafted
 * extras; without this a forced Reader/Details navigation would fetch an attacker-controlled URL
 * through the claimed source's client, attaching that source's stored Cookie / cf_clearance /
 * User-Agent to the attacker host. Tab destinations carry no URL and are always trusted.
 *
 * EVERY url field is validated — including [PushDestination.Reader.coverUrl] (2026-07 audit): the
 * cover is persisted into history/library rows and later loaded through the source-scoped image
 * request path, which attaches the source's stored headers to whatever host the URL names. A push
 * whose cover legitimately lives on a separate CDN must declare that host in the source's config
 * `trustedHosts`/`imageBase`/`previousImageHosts`.
 *
 * Extracted from `App.kt` (unchanged semantics) so [PushDeepLinkTrustTest] can pin the gate;
 * `App.kt`'s deep-link collector is the only production caller.
 */
internal suspend fun PushDestination.isHostTrustedFor(
    configTrust: ConfigHostTrust,
): Boolean =
    when (this) {
        is PushDestination.MangaDetail -> ownsHostForApi(configTrust, url, api)
        is PushDestination.Reader ->
            ownsHostForApi(configTrust, mangaUrl, api) &&
                ownsHostForApi(configTrust, chapterUrl, api) &&
                ownsHostForApi(configTrust, coverUrl, api)
        PushDestination.Updates, PushDestination.Home -> true
    }

/**
 * True only when [url]'s host is declared by the active generic config for [api]. There is no
 * compiled-source fallback: an absent, disabled, retired, or removed API owns no hosts.
 */
private suspend fun ownsHostForApi(
    configTrust: ConfigHostTrust,
    url: String,
    api: String,
): Boolean {
    val host = runCatching { Url(url).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
    return configTrust.ownsHost(api, host)
}
