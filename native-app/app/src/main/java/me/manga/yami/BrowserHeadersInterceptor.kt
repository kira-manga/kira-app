package me.manga.yamiapk

import okhttp3.Interceptor
import okhttp3.Response

class BrowserHeadersInterceptor(
    private val referer: String
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Referer", referer)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
            )
            .build()
        return chain.proceed(req)
    }
}
