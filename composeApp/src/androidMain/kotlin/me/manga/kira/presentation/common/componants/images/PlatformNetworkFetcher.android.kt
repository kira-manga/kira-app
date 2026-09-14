package me.manga.kira.presentation.common.componants.images

import android.content.Context
import coil3.network.NetworkFetcher
import coil3.network.okhttp.asNetworkClient
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.koin.core.context.GlobalContext
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Android's existing HTTP/cache/TLS policy, decorated only for tagged Reader executions. */
actual fun platformNetworkFetcherFactory(): NetworkFetcher.Factory? {
    val koin = GlobalContext.get()
    val context = koin.get<Context>()

    // 200MB disk cache rooted at <cacheDir>/okhttp_cache — same size + dir-name as native
    // AppModule.provideOkHttpClient (AppModule.kt:46-48/56). Without it, every cover / page
    // re-downloads across cold starts and cache eviction.
    val cache = Cache(File(context.cacheDir, "okhttp_cache"), COIL_DISK_CACHE_BYTES)

    val client = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(15, TimeUnit.SECONDS)
        // *.s3.wasabisys.com hostname-verifier bypass — native CoilModule.kt:37-47. Wasabi S3
        // buckets serve images on hosts whose TLS cert host can mismatch (the bucket vhost doesn't
        // match the wildcard the cert was issued for). Native blanket-accepted any cert for those
        // hosts; we tighten the bypass so it only accepts the known wildcard-mismatch shape — the
        // presented leaf cert must still be issued under *.wasabisys.com. The chain is already
        // CA-validated by the default trust manager before this verifier runs, so a MITM presenting
        // a valid cert for a domain they own is no longer accepted for those hosts.
        .hostnameVerifier { hostname, session ->
            if (hostname.endsWith(".s3.wasabisys.com")) {
                runCatching {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    leaf != null && certificateMatchesWasabi(leaf)
                }.getOrDefault(false)
            } else {
                HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
            }
        }
        .build()
    return NetworkFetcher.Factory(networkClient = { PageProgressNetworkClient(client.asNetworkClient()) })
}

/** 200 MB — matches native AppModule.provideOkHttpClient `cacheSize = 200L * 1024 * 1024`. */
private const val COIL_DISK_CACHE_BYTES = 200L * 1024 * 1024

/**
 * Accepts the Wasabi hostname-verifier bypass only when the CA-validated leaf cert is itself issued
 * under `wasabisys.com` — i.e. the known bucket-vhost-vs-wildcard mismatch shape — rather than
 * blanket-accepting any valid cert. Checks the certificate's DNS subjectAltNames (and the CN as a
 * legacy fallback) for an exact `wasabisys.com`, a `*.wasabisys.com` wildcard, or any
 * `*.wasabisys.com` subdomain.
 */
private fun certificateMatchesWasabi(cert: X509Certificate): Boolean {
    fun isWasabiName(raw: String?): Boolean {
        val name = raw?.trim()?.lowercase()?.removePrefix("*.") ?: return false
        return name == "wasabisys.com" || name.endsWith(".wasabisys.com")
    }
    val dnsSans = runCatching { cert.subjectAlternativeNames }.getOrNull().orEmpty()
        // subjectAltName entries are [type, value]; type 2 == dNSName (RFC 5280).
        .filter { (it.getOrNull(0) as? Int) == 2 }
        .mapNotNull { it.getOrNull(1) as? String }
    if (dnsSans.any(::isWasabiName)) return true
    // Legacy fallback for certs without SANs: parse the CN out of the subject DN.
    val cn = cert.subjectX500Principal.name
        .splitToSequence(',')
        .map { it.trim() }
        .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
        ?.substringAfter('=')
    return isWasabiName(cn)
}
