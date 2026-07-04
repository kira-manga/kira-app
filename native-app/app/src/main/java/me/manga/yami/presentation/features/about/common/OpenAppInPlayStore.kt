package me.manga.yamiapk.presentation.features.about.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

fun openAppInPlayStore(context: Context, appPackageName: String = context.packageName) {
    // URI for the Play Store app
    val marketUri = "market://details?id=$appPackageName".toUri()
    // Fallback URI for the browser
    val webUri = "https://play.google.com/store/apps/details?id=$appPackageName".toUri()
    val intent = Intent(Intent.ACTION_VIEW, marketUri).apply {
        // Ensure Play Store or browser is launched in a new task
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Play Store app not installed, open in browser
        context.startActivity(
            Intent(Intent.ACTION_VIEW, webUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}