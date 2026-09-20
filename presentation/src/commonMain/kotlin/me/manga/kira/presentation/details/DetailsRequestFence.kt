package me.manga.kira.presentation.details

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.identity.WorkLocator

internal data class DetailsRequestToken(val generation: Long, val work: WorkLocator)

/** Titles never invalidate a session; returning A → B → A still rejects the first A's late jobs. */
internal class DetailsRequestFence {
    var current: DetailsRequestToken? = null
        private set

    fun enter(manga: Manga): DetailsRequestToken? {
        val work = WorkLocator(manga.api, manga.url)
        if (current?.work == work) return null
        val previous = current?.generation ?: 0L
        check(previous < Long.MAX_VALUE)
        return DetailsRequestToken(previous + 1L, work).also { current = it }
    }

    fun accepts(token: DetailsRequestToken): Boolean = current == token
}
