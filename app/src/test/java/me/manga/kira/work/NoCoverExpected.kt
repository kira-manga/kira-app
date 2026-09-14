package me.manga.kira.work

import android.graphics.Bitmap
import me.manga.kira.core.util.notification.NotificationCovers

internal class NoCoverExpected : NotificationCovers {
    var calls = 0

    override suspend fun withCover(
        url: String,
        canPost: () -> Boolean,
        post: (Bitmap?) -> Unit,
    ) {
        calls++
        error("No display batch was committed")
    }
}
