package me.manga.yamiapk.firebase_cores.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.analytics.FirebaseAnalytics

@Composable
fun rememberFirebaseAnalytics(): FirebaseAnalytics {
    val context = LocalContext.current
    // It’s safe to call getInstance repeatedly; it returns a singleton
    return remember { FirebaseAnalytics.getInstance(context) }
}
