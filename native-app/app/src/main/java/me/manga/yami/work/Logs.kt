package me.manga.yamiapk.work

object Logs {
    fun logLongText(tag: String, message: String) {
        val maxLogSize = 3500   // You can tweak this limit
        var i = 0
        while (i < message.length) {
            val end = (i + maxLogSize).coerceAtMost(message.length)
            android.util.Log.d(tag, message.substring(i, end))
            i = end
        }
    }
}