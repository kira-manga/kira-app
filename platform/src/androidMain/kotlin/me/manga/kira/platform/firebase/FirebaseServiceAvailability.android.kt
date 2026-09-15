package me.manga.kira.platform.firebase

import android.content.Context
import android.content.pm.ApplicationInfo

/** Only a non-debuggable Store installation may resolve the production Firebase SDK facades. */
fun firebaseServicesAvailable(context: Context): Boolean =
    context.packageName == "me.manga.kira" &&
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0
