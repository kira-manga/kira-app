package me.manga.kira.data.repository

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Sources Migration — Phase 2. [SourceUrlMigrator] host-move coverage: when a source's base URL
 * changes, stored manga / chapter / history / notification page URLs must be rewritten to the new
 * host with the path/query/fragment preserved; the image pass rewrites only the cover/image URLs
 * against the IMAGE host; rows for other sources are never touched; and an unchanged base costs
 * zero writes.
 */
class SourceUrlMigratorTest {
    private fun migrator(
        manga: StatefulMangaDao,
        chapter: StatefulChapterDao,
        history: StatefulHistoryDao,
        notification: StatefulNotificationDao,
    ) = SourceUrlMigrator(manga, chapter, history, notification)

    @Test
    fun migratePageUrls_swapsHost_preservesPath_acrossAllTables() =
        runTest {
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://old.azora.test/manga/1")))
            val chapter = StatefulChapterDao(listOf(chapterRow(10, 1, "https://old.azora.test/manga/1/ch/3")))
            val history =
                StatefulHistoryDao(
                    listOf(
                        historyRow(1, "Azora", "https://old.azora.test/manga/1", "https://old.azora.test/manga/1/ch/3"),
                    ),
                )
            val notification =
                StatefulNotificationDao(
                    listOf(notificationRow(1, "Azora", "https://old.azora.test/manga/1", "https://old.azora.test/manga/1/ch/4")),
                )

            migrator(manga, chapter, history, notification).migratePageUrls("Azora", "https://new.azora.test")

            assertEquals("https://new.azora.test/manga/1", manga.rows.single().url)
            assertEquals("https://new.azora.test/manga/1/ch/3", chapter.rows.single().url)
            assertEquals("https://new.azora.test/manga/1", history.rows.single().mangaUrl)
            assertEquals("https://new.azora.test/manga/1/ch/3", history.rows.single().chapterUrl)
            assertEquals("https://new.azora.test/manga/1", notification.rows.single().mangaUrl)
            assertEquals("https://new.azora.test/manga/1/ch/4", notification.rows.single().chapterUrl)
        }

    @Test
    fun migratePageUrls_leavesOtherSourcesUntouched() =
        runTest {
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Azora", "https://old.azora.test/manga/1"),
                        mangaRow(2, "Other", "https://other.test/manga/2"),
                    ),
                )
            val chapter = StatefulChapterDao()
            val history = StatefulHistoryDao()
            val notification = StatefulNotificationDao()

            migrator(manga, chapter, history, notification).migratePageUrls("Azora", "https://new.azora.test")

            assertEquals("https://new.azora.test/manga/1", manga.rows.first { it.id == 1L }.url)
            assertEquals("https://other.test/manga/2", manga.rows.first { it.id == 2L }.url) // untouched
        }

    @Test
    fun migratePageUrls_unchangedBase_writesNothing() =
        runTest {
            val manga = StatefulMangaDao(listOf(mangaRow(1, "Azora", "https://azora.test/manga/1")))
            val chapter = StatefulChapterDao(listOf(chapterRow(10, 1, "https://azora.test/manga/1/ch/3")))
            val history = StatefulHistoryDao()
            val notification = StatefulNotificationDao()

            migrator(manga, chapter, history, notification).migratePageUrls("Azora", "https://azora.test")

            assertEquals(0, manga.updates.size)
            assertEquals(0, chapter.updates.size)
        }

    @Test
    fun migrateImageUrls_rewritesOnlyImageUrls_notPageUrls() =
        runTest {
            val manga =
                StatefulMangaDao(
                    listOf(mangaRow(1, "Azora", "https://old.azora.test/manga/1", imageUrl = "https://oldimg.azora.test/c/1.jpg")),
                )
            val chapter = StatefulChapterDao()
            val history =
                StatefulHistoryDao(
                    listOf(
                        historyRow(
                            1,
                            "Azora",
                            "https://old.azora.test/manga/1",
                            "https://old.azora.test/manga/1/ch/3",
                            mangaImageUrl = "https://oldimg.azora.test/c/1.jpg",
                        ),
                    ),
                )
            val notification =
                StatefulNotificationDao(
                    listOf(
                        notificationRow(
                            1,
                            "Azora",
                            "https://old.azora.test/manga/1",
                            "https://old.azora.test/manga/1/ch/4",
                            mangaImageUrl = "https://oldimg.azora.test/c/1.jpg",
                        ),
                    ),
                )

            migrator(manga, chapter, history, notification).migrateImageUrls("Azora", "https://newimg.azora.test")

            // cover/image URLs swapped to the new IMAGE host
            assertEquals("https://newimg.azora.test/c/1.jpg", manga.rows.single().imageUrl)
            assertEquals("https://newimg.azora.test/c/1.jpg", history.rows.single().mangaImageUrl)
            assertEquals("https://newimg.azora.test/c/1.jpg", notification.rows.single().mangaImageUrl)
            // page URLs must NOT be touched by the image pass
            assertEquals("https://old.azora.test/manga/1", manga.rows.single().url)
            assertEquals("https://old.azora.test/manga/1", history.rows.single().mangaUrl)
            assertEquals("https://old.azora.test/manga/1/ch/4", notification.rows.single().chapterUrl)
        }

    // --- SourceRegistry retirement Phase 3: selective (previousHosts) mode ------------------------

    @Test
    fun migratePageUrls_withFromHosts_rewritesOnlyDeclaredHosts_acrossAllTables() =
        runTest {
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Azora", "https://ancient.azora.old/manga/1"),
                        mangaRow(2, "Azora", "https://my-mirror.example/manga/2"), // NOT declared → untouched
                    ),
                )
            val chapter =
                StatefulChapterDao(
                    listOf(
                        chapterRow(10, 1, "https://ancient.azora.old/manga/1/ch/3"),
                        chapterRow(20, 2, "https://my-mirror.example/manga/2/ch/1"),
                    ),
                )
            val history =
                StatefulHistoryDao(
                    listOf(
                        historyRow(
                            1,
                            "Azora",
                            "https://ancient.azora.old/manga/1",
                            "https://my-mirror.example/m/1/ch/3",
                        ),
                    ),
                )
            val notification =
                StatefulNotificationDao(
                    listOf(
                        notificationRow(
                            1,
                            "Azora",
                            "https://ANCIENT.azora.OLD/manga/1",
                            "https://ancient.azora.old:8080/m/1/ch/4",
                        ),
                    ),
                )

            migrator(manga, chapter, history, notification)
                .migratePageUrls("Azora", "https://azora.test", fromHosts = setOf("ancient.azora.old"))

            assertEquals("https://azora.test/manga/1", manga.rows.first { it.id == 1L }.url)
            assertEquals("https://my-mirror.example/manga/2", manga.rows.first { it.id == 2L }.url)
            assertEquals("https://azora.test/manga/1/ch/3", chapter.rows.first { it.id == 10L }.url)
            assertEquals("https://my-mirror.example/manga/2/ch/1", chapter.rows.first { it.id == 20L }.url)
            // Mixed history row: only the declared-host half is rewritten.
            assertEquals("https://azora.test/manga/1", history.rows.single().mangaUrl)
            assertEquals("https://my-mirror.example/m/1/ch/3", history.rows.single().chapterUrl)
            // Host matching ignores case and port.
            assertEquals("https://azora.test/manga/1", notification.rows.single().mangaUrl)
            assertEquals("https://azora.test/m/1/ch/4", notification.rows.single().chapterUrl)
        }

    // --- 2026-07 audit: per-row failure isolation ---------------------------------------------------

    @Test
    fun migratePageUrls_rowCollision_skipsOnlyThatRow_restOfTableStillMigrates() =
        runTest {
            // Row 1's rewrite collides with row 3's UNIQUE url (the user re-saved the same manga
            // after the host move). The collision must abort ONLY row 1 — row 2 and the later
            // tables still migrate. Pre-fix, the collision aborted the whole saved_manga pass.
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Azora", "https://old.azora.test/manga/dup"),
                        mangaRow(2, "Azora", "https://old.azora.test/manga/2"),
                        mangaRow(3, "Azora", "https://new.azora.test/manga/dup"),
                    ),
                )
            manga.updateFailure = { updated ->
                if (manga.rows.any { it.id != updated.id && it.url == updated.url }) {
                    IllegalStateException("UNIQUE constraint failed: saved_manga.url")
                } else {
                    null
                }
            }
            val chapter = StatefulChapterDao(listOf(chapterRow(10, 1, "https://old.azora.test/manga/dup/ch/1")))
            val history = StatefulHistoryDao()
            val notification = StatefulNotificationDao()

            migrator(manga, chapter, history, notification).migratePageUrls("Azora", "https://new.azora.test")

            // The colliding row keeps its old url (skipped, not REPLACE-deleted)…
            assertEquals("https://old.azora.test/manga/dup", manga.rows.first { it.id == 1L }.url)
            // …while the row iterated AFTER the collision still migrates…
            assertEquals("https://new.azora.test/manga/2", manga.rows.first { it.id == 2L }.url)
            // …and the later tables run too.
            assertEquals("https://new.azora.test/manga/dup/ch/1", chapter.rows.single().url)
        }

    @Test
    fun migratePageUrlsStrict_propagates_write_failure_for_transaction_rollback() =
        runTest {
            val manga =
                StatefulMangaDao(
                    listOf(
                        mangaRow(1, "Azora", "https://old.azora.test/manga/dup"),
                        mangaRow(2, "Azora", "https://old.azora.test/manga/2"),
                        mangaRow(3, "Azora", "https://new.azora.test/manga/dup"),
                    ),
                )
            manga.updateFailure = { updated ->
                if (manga.rows.any { it.id != updated.id && it.url == updated.url }) {
                    IllegalStateException("simulated projection failure")
                } else {
                    null
                }
            }

            assertFailsWith<IllegalStateException> {
                migrator(
                    manga,
                    StatefulChapterDao(),
                    StatefulHistoryDao(),
                    StatefulNotificationDao(),
                ).migratePageUrlsStrict("Azora", "https://new.azora.test")
            }

            assertEquals("https://old.azora.test/manga/dup", manga.rows.first { it.id == 1L }.url)
            assertEquals("https://old.azora.test/manga/2", manga.rows.first { it.id == 2L }.url)
        }

    @Test
    fun urlHost_extractsLowercasedPortlessHost_orNullForSchemelessInput() {
        assertEquals("azora.test", urlHost("https://azora.test/manga/1?p=2#frag"))
        assertEquals("azora.test", urlHost("https://AZORA.test:8443/manga/1"))
        assertEquals("azora.test", urlHost("https://azora.test"))
        assertEquals(null, urlHost("not-a-url"))
        assertEquals(null, urlHost(""))
        assertEquals(null, urlHost("https://"))
    }
}
