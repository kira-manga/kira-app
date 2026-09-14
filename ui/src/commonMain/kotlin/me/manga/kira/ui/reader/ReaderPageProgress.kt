package me.manga.kira.ui.reader

import coil3.Extras
import coil3.getExtra
import coil3.request.ImageRequest
import me.manga.kira.domain.model.reader.PageProgressHandle

private val pageProgressHandleKey = Extras.Key<PageProgressHandle?>(null)

/** Capture the Reader's opaque page owner without changing the image's memory/disk cache keys. */
fun ImageRequest.Builder.pageProgressHandle(handle: PageProgressHandle?) = apply {
    extras[pageProgressHandleKey] = handle
}

/** Ownership consumed only by the composition root's request-execution interceptor. */
val ImageRequest.pageProgressHandle: PageProgressHandle?
    get() = getExtra(pageProgressHandleKey)
