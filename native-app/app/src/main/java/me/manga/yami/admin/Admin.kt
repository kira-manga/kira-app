package me.manga.yamiapk.admin

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Admin {

    var isAdmin = true
    var testingMode by mutableStateOf(false)


    fun logLong(tag: String, message: String, level: Int = Log.DEBUG) {
        val logFunc: (String, String) -> Int = when (level) {
            Log.ERROR -> Log::e
            Log.WARN  -> Log::w
            Log.INFO  -> Log::i
            Log.VERBOSE -> Log::v
            else -> Log::d
        }

        if (message.length <= 4000) {
            logFunc(tag, message)
        } else {
            var start = 0
            val chunkSize = 4000
            val length = message.length
            while (start < length) {
                val end = (start + chunkSize).coerceAtMost(length)
                logFunc(tag, message.substring(start, end))
                start = end
            }
        }
    }

}