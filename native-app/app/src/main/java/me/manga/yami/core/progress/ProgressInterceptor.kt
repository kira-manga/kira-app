package me.manga.yamiapk.core.progress

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import okio.ForwardingSource
import okio.buffer

/**
 * OkHttp interceptor that tracks download progress for image loading.
 * Reports progress to ProgressManager for real-time UI updates.
 */
class ProgressInterceptor : Interceptor {

    companion object {
        private const val TAG = "ProgressInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        return try {
            val response = chain.proceed(request)
            val body = response.body

            if (body == null) {
                Log.w(TAG, "Response body is null for $url")
                return response
            }

            val totalBytes = body.contentLength()

            // If content length is unknown, we can't track progress accurately
            if (totalBytes < 0) {
                Log.d(TAG, "Unknown content length for $url, progress tracking disabled")
                return response
            }

            val source = body.source()
            var readBytes = 0L

            val progressSource = object : ForwardingSource(source) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)

                    if (bytesRead > 0) {
                        readBytes += bytesRead
                        ProgressManager.updateProgress(url, readBytes, totalBytes)
                    } else if (bytesRead == -1L) {
                        // EOF reached - mark as completed
                        ProgressManager.markCompleted(url)
                    }

                    return bytesRead
                }
            }.buffer()

            val newBody = object : okhttp3.ResponseBody() {
                override fun contentLength() = totalBytes
                override fun contentType() = body.contentType()
                override fun source() = progressSource
            }

            response.newBuilder().body(newBody).build()

        } catch (e: Exception) {

            // Ignore HTTP/2 CANCEL — normal in Coil scroll behavior
            if (!e.message.orEmpty().contains("CANCEL")) {
                Log.e("ProgressInterceptor", "Real error for $url", e)
                ProgressManager.markFailed(url, e)
            }

            // Continue without progress tracking
            throw e

        }
    }
}