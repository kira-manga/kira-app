package me.manga.kira.data.backup

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [BackupCodec]'s entity <-> DTO mappers and the `backup.json` wire format:
 * every wire field survives entity -> DTO -> entity, device-local columns come back pristine, and
 * the JSON posture (defaults on write, unknown keys ignored on read) holds.
 */
class BackupCodecRoundTripTest {

    private val mangaEntity = SavedMangaEntity(
        id = 42,
        api = "azora",
        language = "ar",
        url = "https://azora/manga/1",
        imageUrl = "https://azora/img.png",
        title = "Solo Leveling",
        description = "desc",
        status = "Ongoing",
        rating = "4.7",
        genres = listOf("action", "fantasy"),
        savedTimestamp = 1_111,
        lastOpenTimestamp = 2_222,
        isLiked = true,
        isWatchingNow = true,
    )

    private val chapterEntity = SavedChapterEntity(
        id = 9,
        mangaId = 42,
        name = "Chapter 100",
        number = "100",
        url = "https://azora/ch/100",
        date = LocalDate(2026, 3, 10),
        isDownloaded = true,
        isBookmarked = true,
        isRead = true,
        isNew = true,
        lastReadPage = 12,
        lastReadDate = 3_333,
        localImagePaths = listOf("/device/ch.cbz"),
        fetchedAt = 4_444,
    )

    @Test
    fun manga_roundTrip_preserves_wire_fields_and_resets_id() {
        val back = mangaEntity.toBackup(chapters = emptyList()).toEntity()

        assertEquals(0, back.id, "autogen id never travels")
        assertEquals(mangaEntity.copy(id = 0), back, "every other column round-trips")
    }

    @Test
    fun chapter_roundTrip_preserves_progress_and_resets_device_local_columns() {
        val dto = chapterEntity.toBackup(resumePage = 12, downloadEntry = "downloads/0.cbz")
        assertEquals(12, dto.resumePage)
        assertEquals("downloads/0.cbz", dto.downloadEntry)

        val back = dto.toEntity()
        assertEquals("Chapter 100", back.name)
        assertEquals("100", back.number)
        assertEquals(chapterEntity.url, back.url)
        assertEquals(LocalDate(2026, 3, 10), back.date, "publish date survives the epoch-day trip")
        assertTrue(back.isRead)
        assertTrue(back.isBookmarked)
        assertEquals(3_333, back.lastReadDate)
        // Device-local columns come back pristine — restoring a packed download flips them
        // separately, only after its CBZ is in place.
        assertFalse(back.isDownloaded)
        assertTrue(back.localImagePaths.isEmpty())
        assertFalse(back.isNew)
        assertEquals(0, back.fetchedAt)
        assertEquals(0, back.lastReadPage, "dead column stays dead")
        assertEquals(0, back.id)
        assertEquals(0, back.mangaId, "placeholder until the DAO resolves the local manga")
    }

    @Test
    fun chapter_null_publish_date_stays_null() {
        val back = chapterEntity.copy(date = null).toBackup(resumePage = null, downloadEntry = null).toEntity()
        assertNull(back.date)
    }

    @Test
    fun history_roundTrip_preserves_read_position_through_epochMs() {
        // Whole-second time: LocalDateTime <-> epoch-ms truncates sub-millisecond precision.
        val readAt = LocalDateTime(2026, 5, 20, 14, 30, 15)
        val entity = HistoryItemD(
            id = 5,
            api = "azora",
            language = "ar",
            mangaId = 42,
            mangaUrl = "https://azora/manga/1",
            mangaTitle = "Solo Leveling",
            mangaImageUrl = "https://azora/img.png",
            chapterUrl = "https://azora/ch/100",
            chapterTitle = "Chapter 100",
            isDownloaded = true,
            localImagePaths = listOf("/device/ch.cbz"),
            lastReadDate = readAt,
            lastReadPage = 12,
            totalPages = 30,
        )

        val back = entity.toBackup().toEntity(resolvedMangaId = 77)

        assertEquals(readAt, back.lastReadDate, "same-device round-trip is exact")
        assertEquals(77, back.mangaId, "resolved local id is applied")
        assertEquals(12, back.lastReadPage)
        assertEquals(30, back.totalPages)
        assertEquals(0, back.id)
        assertFalse(back.isDownloaded, "device-local download state never travels")
        assertTrue(back.localImagePaths.isEmpty())
    }

    @Test
    fun history_without_resolved_manga_falls_back_to_zero_id() {
        val entity = HistoryItemD(
            api = "azora",
            language = "ar",
            mangaUrl = "https://azora/manga/1",
            mangaTitle = "Solo Leveling",
            mangaImageUrl = "",
            chapterUrl = "https://azora/ch/1",
            chapterTitle = "Chapter 1",
            isDownloaded = false,
            lastReadDate = LocalDateTime(2026, 1, 1, 0, 0),
        )
        assertEquals(0, entity.toBackup().toEntity(resolvedMangaId = null).mangaId)
    }

    // --- backup.json wire posture ----------------------------------------------------------------

    @Test
    fun backupFile_json_roundTrips_exactly() {
        val document = BackupFile(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = "1.0.0",
            dbVersion = BACKUP_DB_VERSION,
            platform = "android",
            createdAtEpochMs = 1_234_567,
            includesDownloads = true,
            mangas = listOf(
                mangaEntity.toBackup(
                    chapters = listOf(chapterEntity.toBackup(resumePage = 12, downloadEntry = "downloads/0.cbz")),
                ),
            ),
            history = listOf(
                BackupHistoryItem(
                    api = "azora",
                    mangaUrl = "https://azora/manga/1",
                    lastReadDateEpochMs = 999,
                ),
            ),
        )

        val decoded = backupJson.decodeFromString<BackupFile>(backupJson.encodeToString(BackupFile.serializer(), document))

        assertEquals(document, decoded)
    }

    @Test
    fun decoder_ignores_unknown_keys_and_fills_defaults() {
        // A future writer added fields this build doesn't know; only identity fields are present.
        val json = """
            {
              "formatVersion": 1,
              "futureField": {"nested": true},
              "mangas": [
                {"api": "azora", "url": "https://azora/manga/1", "title": "Solo Leveling",
                 "chapters": [{"url": "https://azora/ch/1", "futureChapterField": 3}]}
              ]
            }
        """.trimIndent()

        val decoded = backupJson.decodeFromString<BackupFile>(json)

        assertEquals(1, decoded.formatVersion)
        assertFalse(decoded.includesDownloads, "defaulted")
        assertEquals(1, decoded.mangas.size)
        assertEquals("Solo Leveling", decoded.mangas.single().title)
        assertEquals("https://azora/ch/1", decoded.mangas.single().chapters.single().url)
        assertNull(decoded.mangas.single().chapters.single().downloadEntry)
        assertTrue(decoded.history.isEmpty())
    }

    @Test
    fun writer_serializes_defaults_explicitly() {
        val json = backupJson.encodeToString(BackupFile.serializer(), BackupFile())
        assertTrue("\"formatVersion\":1" in json, "encodeDefaults keeps the version marker on the wire: $json")
        assertTrue("\"includesDownloads\":false" in json)
    }
}
