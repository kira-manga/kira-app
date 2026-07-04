import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

suspend fun downloadAndConvertImageBytes(ctx: Context, url: String): ByteArray =
    withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")

            val body = resp.body ?: throw IOException("Empty response body")
            var inputStream = body.byteStream()

            // handle gzip if server compressed the body
            val encoding = resp.header("Content-Encoding")
            if (encoding?.contains("gzip", true) == true) {
                inputStream = GZIPInputStream(inputStream)
            }

            val rawBytes = inputStream.readBytes()

            // 1) If it decodes as an image -> done
            if (rawBytes.isValidImage()) return@withContext rawBytes

            // 2) If it's a zip (e.g., contains image inside), try to extract first image entry
            if (rawBytes.isZip()) {
                val extracted = rawBytes.extractFirstImageFromZip()
                if (extracted != null && extracted.isValidImage()) return@withContext extracted
            }

            // 3) Try extracting a base64 substring (some APIs embed base64)
            val base64Decoded = rawBytes.tryFindAndDecodeBase64()
            if (base64Decoded != null && base64Decoded.isValidImage()) return@withContext base64Decoded

            // fallback: try a second decode attempt with relaxed charset -> already binary so unlikely.
            throw IOException("Downloaded data is not a recognized image format")
        }
    }

// ------- helpers -------

private fun ByteArray.isZip(): Boolean {
    // ZIP files start with "PK" 0x50 0x4B
    if (this.size < 4) return false
    return this[0] == 0x50.toByte() && this[1] == 0x4B.toByte()
}

private fun ByteArray.isValidImage(): Boolean {
    // Quick decode check without allocating huge bitmaps
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(this, 0, this.size, opts)
        opts.outWidth > 0 && opts.outHeight > 0
    } catch (e: Throwable) {
        false
    }
}

private fun ByteArray.extractFirstImageFromZip(): ByteArray? {
    return try {
        ZipInputStream(ByteArrayInputStream(this)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    // read this entry fully
                    val entryBytes = zis.readBytes()
                    if (entryBytes.isValidImage()) return entryBytes
                }
                entry = zis.nextEntry
            }
            null
        }
    } catch (e: Exception) {
        null
    }
}

private fun ByteArray.tryFindAndDecodeBase64(): ByteArray? {
    // Turn bytes to ISO-8859-1 string to retain raw byte values as chars
    val s = try {
        String(this, Charsets.ISO_8859_1)
    } catch (e: Exception) {
        return null
    }

    // crude search for long base64-looking substring (>=100 chars)
    val regex = Regex("([A-Za-z0-9+/=\\r\\n]{100,})")
    val match = regex.find(s) ?: return null
    val candidate = match.value.replace(Regex("\\s+"), "")
    return try {
        Base64.decode(candidate, Base64.DEFAULT)
    } catch (e: IllegalArgumentException) {
        null
    }
}
