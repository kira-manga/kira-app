package me.manga.kira.core.webview

/** iOS always ships a working embedded `WKWebView`. */
actual fun isEmbeddedWebViewAvailable(): Boolean = true
