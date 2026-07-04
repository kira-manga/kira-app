package me.manga.yamiapk.core.network_cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.CacheControl
import okhttp3.Interceptor
import java.util.concurrent.TimeUnit

fun forceCacheForDados() = Interceptor { chain ->
    val response = chain.proceed(chain.request())

    if (chain.request().url.toString()
            .startsWith("https://api2.manhastro.net/dados")
    ) {
        response.newBuilder()
            .header(
                "Cache-Control",
                "public, max-age=86400" // 1 day (change if you want)
            )
            .removeHeader("Pragma")
            .build()
    } else {
        response
    }
}
 fun offlineCacheInterceptor(context: Context) = Interceptor { chain ->
    var request = chain.request()

    if (!isNetworkAvailable(context) &&
        request.url.toString().startsWith("https://api2.manhastro.net/dados")
    ) {
        request = request.newBuilder()
            .cacheControl(
                CacheControl.Builder()
                    .onlyIfCached()
                    .maxStale(7, TimeUnit.DAYS) // allow stale cache
                    .build()
            )
            .build()
    }

    chain.proceed(request)
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}