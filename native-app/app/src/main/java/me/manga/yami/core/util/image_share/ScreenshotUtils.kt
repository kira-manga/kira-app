package me.manga.yamiapk.core.util.image_share

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream

object ScreenshotUtils {
    suspend fun captureAndShare(
        activity: Activity,
        rootView: View,
        hideControls: () -> Unit,
        showControls: () -> Unit
    ) {
        // 1) hide controls immediately
        hideControls()
        delay(200)

        // 2) wait for next frame
        rootView.post {
            val width = rootView.width
            val height = rootView.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            // 3) request pixel copy
            PixelCopy.request(
                activity.window,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        // 4) save to cache
                        val cachePath = File(activity.cacheDir, "images").apply { mkdirs() }
                        val file = File(cachePath, "screenshot.png")
                        FileOutputStream(file).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        // 5) share via FileProvider
                        val uri = FileProvider.getUriForFile(
                            activity,
                            "${activity.packageName}.fileprovider",
                            file
                        )
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        activity.startActivity(
                            Intent.createChooser(shareIntent, "Share screenshot")
                        )
                    }
                    // 6) restore controls
                    showControls()
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

}