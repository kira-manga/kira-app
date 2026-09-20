package me.manga.kira.navigation.routes

import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.backup.BackupSelection
import me.manga.kira.domain.model.identity.WorkLocator
import kotlin.test.Test
import kotlin.test.assertEquals

class BackupScopeCodecTest {
    @Test
    fun only_the_explicit_empty_route_marker_is_full_library() {
        assertEquals(BackupScope.FullLibrary, decodeBackupScope(""))
        for (raw in listOf(" ", "null", "[]", "{}", "not json", "[")) {
            assertEquals(BackupScope.Invalid, decodeBackupScope(raw), raw)
        }
    }

    @Test
    fun encoding_an_empty_selection_never_becomes_the_full_library_marker() {
        val encoded = encodeBackupScope(emptyList())
        assertEquals("[]", encoded)
        assertEquals(BackupScope.Invalid, decodeBackupScope(encoded))
    }

    @Test
    fun old_title_language_routes_and_blank_identity_are_invalid_not_broadened() {
        val old = """[{"api":"source","language":"en","title":"Same title"}]"""
        assertEquals(BackupScope.Invalid, decodeBackupScope(old))
        for (key in listOf(BackupScopeKey("", "https://current.test/work/one"), BackupScopeKey("source", " "))) {
            assertEquals(BackupScope.Invalid, decodeBackupScope(encodeBackupScope(listOf(key))))
        }
    }

    @Test
    fun raw_scoped_identity_round_trips_without_title_or_language_authority() {
        val url = "https://current.test/work/a%2Fb?query=one|two#片"
        val keys = listOf(
            BackupScopeKey("source", url, "Renamed | \"Title\""),
            BackupScopeKey("other", url, "Same display title"),
        )
        assertEquals(
            BackupScope.Mangas(keys.map { BackupSelection(WorkLocator(it.api, it.url), it.title) }),
            decodeBackupScope(encodeBackupScope(keys)),
        )
    }

    @Test
    fun absent_display_label_uses_the_raw_url_and_does_not_invent_an_owner() {
        val raw = """[{"api":"source","url":"https://current.test/work/one","language":"old"}]"""
        val work = WorkLocator("source", "https://current.test/work/one")
        assertEquals(BackupScope.Mangas(listOf(BackupSelection(work))), decodeBackupScope(raw))
    }
}
