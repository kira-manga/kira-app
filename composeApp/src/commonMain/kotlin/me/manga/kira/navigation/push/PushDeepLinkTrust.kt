package me.manga.kira.navigation.push

import io.ktor.http.Url
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
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
    sources: SourcesRepository,
    configTrust: ConfigHostTrust,
): Boolean =
    when (this) {
        is PushDestination.MangaDetail -> ownsHostForApi(sources, configTrust, url, api)
        is PushDestination.Reader ->
            ownsHostForApi(sources, configTrust, mangaUrl, api) &&
                ownsHostForApi(sources, configTrust, chapterUrl, api) &&
                ownsHostForApi(sources, configTrust, coverUrl, api)
        PushDestination.Updates, PushDestination.Home -> true
    }

/**
 * True if [url]'s host is owned by the source identified by [api]. Two authorities, checked in
 * order (SourceRegistry retirement Phase 3, R7):
 *  1. **Config metadata** ([ConfigHostTrust]) — the api's declared baseUrl/imageBase/previousHosts/
 *     previousImageHosts/trustedHosts. Authoritative and strictly per-api.
 *  2. **Legacy resolver fallback** — the same host→repo resolver the Coil header interceptor uses
 *     (matches a source's baseUrl / BASE_URL / imgBaseUrl host or a parent domain), for apis
 *     without config metadata.
 * A blank/unparseable URL, a host nothing declares, or a host owned by a different source all
 * return false.
 */
private suspend fun ownsHostForApi(
    sources: SourcesRepository,
    configTrust: ConfigHostTrust,
    url: String,
    api: String,
): Boolean {
    val host = runCatching { Url(url).host.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
    return configTrust.ownsHost(api, host) || sources.findRepoByHost(host)?.API == api
}
