package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.mapper.savedIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real Room add/re-add boundary, including generated IDs and insert-only chapter state. */
class LibraryRepositoryAddTest {
    @Test
    fun addToLibrary_persists_the_manga_and_its_chapters() = runTest {
        LibraryIdentityFixture().use { f ->
            val fetched = libraryFetched(chapters = listOf("c/3", "c/2", "c/1").map { libraryChapter(it) })
            val owner = assertNotNull(f.repository.addToLibrary(fetched).getOrNull())
            val saved = assertNotNull(f.db.mangaDao().getMangaById(owner.id))
            val chapters = f.db.backupDao().getChaptersForManga(owner.id)
            assertEquals(listOf("c/1", "c/2", "c/3"), chapters.map { it.url })
            assertTrue(chapters.all { it.id > 0 && it.mangaId == owner.id && !it.isNew })
            assertEquals(fetched.requested, owner.locator)
            assertEquals(fetched.details.title, saved.title)
            assertEquals(fetched.details.language, saved.language)
            assertEquals(fetched.details.description, saved.description)
            assertEquals(fetched.details.author, saved.author)
            assertEquals(fetched.details.coverUrl, saved.imageUrl)
            assertEquals(fetched.details.rating, saved.rating)
            assertEquals(fetched.details.status, saved.status)
            assertEquals(fetched.details.genres, saved.genres)
        }
    }

    @Test
    fun addToLibrary_is_idempotent_and_inserts_only_new_chapter_urls() = runTest {
        LibraryIdentityFixture().use { f ->
            val parent = f.parent()
            val child = f.chapter(librarySavedChapter(parent, "c/1"))
            val request = libraryFetched(parent, listOf(libraryChapter("c/2"), libraryChapter("c/1")))
            assertEquals(parent.savedIdentity(), f.repository.addToLibrary(request).getOrNull())
            assertEquals(parent.savedIdentity(), f.repository.addToLibrary(request).getOrNull())
            val rows = f.db.backupDao().getChaptersForManga(parent.id)
            assertEquals(listOf("c/1", "c/2"), rows.map { it.url })
            assertEquals(child, rows.first(), "re-add cannot replace any saved child state")
            val actual = assertNotNull(f.db.mangaDao().getMangaById(parent.id))
            assertEquals(parent.savedTimestamp, actual.savedTimestamp)
            assertEquals(parent.lastOpenTimestamp, actual.lastOpenTimestamp)
            assertEquals(parent.isLiked, actual.isLiked)
            assertEquals(parent.isWatchingNow, actual.isWatchingNow)
            assertEquals(request.details.title, actual.title)
            assertEquals(1, f.db.backupDao().getAllSavedManga().size)
        }
    }

    @Test
    fun addToLibrary_with_empty_chapters_persists_only_the_manga_row() = runTest {
        LibraryIdentityFixture().use { f ->
            val owner = assertNotNull(f.repository.addToLibrary(libraryFetched()).getOrNull())
            assertEquals(1, f.db.backupDao().getAllSavedManga().size)
            assertTrue(f.db.backupDao().getChaptersForManga(owner.id).isEmpty())
        }
    }
}
