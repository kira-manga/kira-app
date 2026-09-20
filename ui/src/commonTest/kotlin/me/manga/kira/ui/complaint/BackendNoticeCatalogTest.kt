package me.manga.kira.ui.complaint

import me.manga.kira.domain.model.complaint.BackendNoticeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure finite catalog discrimination; no Compose UI, backend seeding or mutation is exercised. */
class BackendNoticeCatalogTest {
    @Test
    fun catalogContainsOnlyTheTwoExactVersionOnePolicyDefinitions() {
        val expected =
            mapOf(
                "complaints.notice.content-policy" to 1,
                "complaints.notice.source-requirements" to 1,
            )
        val definitions = BackendNoticeDefinition.entries
        assertEquals(expected.size, definitions.size)
        assertEquals(expected, definitions.associate { it.key to it.definitionVersion })
        assertEquals(expected.keys, BackendNoticeKey.entries.map { it.key }.toSet())
        assertEquals(BackendNoticeKey.entries.toSet(), definitions.map { it.identity }.toSet())
        for (definition in definitions) {
            assertSame(definition.identity, BackendNoticeKey.fromKey(definition.key))
            assertTrue(isKnownBackendNoticeKey(definition.key))
            assertSame(definition, backendNoticeDefinition(definition.key))
        }
    }

    @Test
    fun unknownHistoricAndNearMatchKeysNeverResolveToPolicyCopy() {
        val unknown = listOf("", "admin", "0", "complaints.notice.future-policy", "content-policy", "source-requirements")
        for (key in unknown) {
            assertNull(BackendNoticeKey.fromKey(key))
            assertFalse(isKnownBackendNoticeKey(key))
            assertNull(backendNoticeDefinition(key))
        }
        for (key in listOf("complaints.notice.content-policy", "complaints.notice.source-requirements")) {
            for (nearMatch in listOf(key.uppercase(), " $key", "$key ", "$key\n", "x.$key", "$key.extra", key.replace('-', '_'))) {
                assertNull(BackendNoticeKey.fromKey(nearMatch))
                assertFalse(isKnownBackendNoticeKey(nearMatch))
                assertNull(backendNoticeDefinition(nearMatch))
            }
        }
    }
}
