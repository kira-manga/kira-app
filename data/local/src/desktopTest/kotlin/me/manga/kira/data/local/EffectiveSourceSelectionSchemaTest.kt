package me.manga.kira.data.local

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.data.local.dao.ReaderProgressFixture
import me.manga.kira.data.local.entity.EffectiveSourceSelectionEntity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

/** Real generated Room schema and transaction behavior, not source-selection authentication. */
class EffectiveSourceSelectionSchemaTest {
    @Test
    fun freshAndMigratedCreationSeedOnlyOneAllocatorWithoutSelectingACatalog() = runTest {
        listOf(13, 16).forEach { version ->
            ReaderProgressFixture().use { fresh ->
                ReaderProgressFixture().use { migrated ->
                    createReaderVersion(migrated.file, version)
                    listOf(fresh, migrated).forEach { fixture ->
                        assertEmptySelection(fixture)
                        assertEquals(1L, fixture.db.effectiveSourceSelectionDao().nextGeneration())
                        assertEquals(1L, fixture.number("SELECT count(*) FROM source_selection_generation"))
                        fixture.reopen()
                        assertEmptySelection(fixture)
                        assertEquals(1L, fixture.db.effectiveSourceSelectionDao().nextGeneration())
                    }
                }
            }
        }
    }

    @Test
    fun invalidSelectionAndAllocatorWritesRollBackEveryEarlierWrite() = runTest {
        listOf(13, 16).forEach { version ->
            ReaderProgressFixture().use { fresh ->
                ReaderProgressFixture().use { migrated ->
                    createReaderVersion(migrated.file, version)
                    listOf(fresh, migrated).forEach { fixture -> assertInvalidWritesRollBack(fixture) }
                }
            }
        }
    }

    @Test
    fun reopenDoesNotRewindTheAllocatorOrRepairMissingAuthority() = runTest {
        ReaderProgressFixture().use { fixture ->
            fixture.writer {
                val dao = fixture.db.effectiveSourceSelectionDao()
                assertEquals(1, dao.advance(expected = 1, next = 2))
                dao.setSelection(selection())
            }
            fixture.reopen()
            assertEquals(2L, fixture.db.effectiveSourceSelectionDao().nextGeneration())
            assertEquals(selection(), fixture.db.effectiveSourceSelectionDao().boundedSelection(PAYLOAD_LIMIT))
            fixture.execute("DELETE FROM effective_source_selection")
            fixture.reopen()
            assertEmptySelection(fixture)
            assertEquals(2L, fixture.db.effectiveSourceSelectionDao().nextGeneration())
            fixture.execute("DELETE FROM source_selection_generation")
            fixture.reopen()
            assertNull(fixture.db.effectiveSourceSelectionDao().nextGeneration())
            assertEmptySelection(fixture)
            assertEquals(0L, fixture.number("SELECT count(*) FROM source_selection_generation"))
        }
    }

    @Test
    fun overflowAbortsWithoutPublishingAnUncoupledSelection() = runTest {
        ReaderProgressFixture().use { fixture ->
            val dao = fixture.db.effectiveSourceSelectionDao()
            fixture.writer {
                assertEquals(1, dao.advance(expected = 1, next = Long.MAX_VALUE))
                dao.setSelection(selection(generation = Long.MAX_VALUE - 1))
            }
            assertFails {
                fixture.writer {
                    dao.setSelection(selection(generation = Long.MAX_VALUE))
                    fixture.execute("UPDATE source_selection_generation SET nextGeneration = nextGeneration + 1")
                }
            }
            assertEquals(Long.MAX_VALUE, dao.nextGeneration())
            assertEquals(Long.MAX_VALUE - 1, dao.selectedGeneration())
            fixture.reopen()
            assertEquals(Long.MAX_VALUE, fixture.db.effectiveSourceSelectionDao().nextGeneration())
            assertEquals(Long.MAX_VALUE - 1, fixture.db.effectiveSourceSelectionDao().selectedGeneration())
        }
    }

    @Test
    fun compilerExport17RetainsEvery16EntityAndAddsOnlyReaderAndSelectionTables() {
        // 17.json must come from actual Room/KSP; this test does not synthesize an identity hash.
        val before = exportedEntities(16)
        val after = exportedEntities(17)
        val additions = setOf(
            "reader_work_state",
            "reader_chapter_state",
            "reader_legacy_cleanup",
            "effective_source_selection",
            "source_selection_generation",
        )
        assertEquals(before.keys + additions, after.keys)
        before.forEach { (table, entity) -> assertEquals(entity, after[table], "Changed v16 entity: $table") }
    }

    private fun exportedEntities(version: Int): Map<String, JsonObject> {
        val file = File("schemas/me.manga.kira.data.local.MangaDatabase/$version.json")
        val database = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        assertEquals(version.toString(), database.getValue("version").jsonPrimitive.content)
        return database.getValue("entities").jsonArray.associate { element ->
            val entity = element.jsonObject
            entity.getValue("tableName").jsonPrimitive.content to entity
        }
    }

    private suspend fun assertInvalidWritesRollBack(fixture: ReaderProgressFixture) {
        val dao = fixture.db.effectiveSourceSelectionDao()
        fixture.writer {
            assertEquals(1, dao.advance(expected = 1, next = 2))
            dao.setSelection(selection())
        }
        (invalidSelections() + invalidAllocators()).forEach { invalidSql ->
            assertFails(invalidSql) {
                fixture.writer {
                    assertEquals(1, dao.advance(expected = 2, next = 3))
                    dao.setSelection(selection(generation = 2, payload = "changed"))
                    fixture.execute(invalidSql)
                }
            }
            assertEquals(2L, dao.nextGeneration(), invalidSql)
            assertEquals(selection(), dao.boundedSelection(PAYLOAD_LIMIT), invalidSql)
        }
    }

    private suspend fun assertEmptySelection(fixture: ReaderProgressFixture) {
        val dao = fixture.db.effectiveSourceSelectionDao()
        assertEquals(0, dao.selectionCount())
        assertNull(dao.boundedSelection(PAYLOAD_LIMIT))
    }

    private fun selection(generation: Long = 1, payload: String = "{}") =
        EffectiveSourceSelectionEntity(generation = generation, payload = payload, payloadDigest = DIGEST)

    private fun invalidSelections(): List<String> =
        listOf(
            selectionInsert("1", "1", "'{}'", "'$DIGEST'"),
            selectionInsert("0", "0", "'{}'", "'$DIGEST'"),
            selectionInsert("0", "1.5", "'{}'", "'$DIGEST'"),
            selectionInsert("0", "1", "X'00'", "'$DIGEST'"),
            selectionInsert("0", "1", "'{}'", "'${DIGEST.uppercase()}'"),
            "UPDATE effective_source_selection SET id = 1",
            "UPDATE effective_source_selection SET generation = -1",
            "UPDATE effective_source_selection SET generation = ${Long.MAX_VALUE} + 1",
            "UPDATE effective_source_selection SET payload = X'00'",
            "UPDATE effective_source_selection SET payloadDigest = X'00'",
            "UPDATE effective_source_selection SET payloadDigest = 'bad'",
            "UPDATE effective_source_selection SET payloadDigest = '${DIGEST.dropLast(1)}g'",
            "UPDATE effective_source_selection SET payloadDigest = '${DIGEST.dropLast(1)}' || char(0)",
        )

    private fun selectionInsert(id: String, generation: String, payload: String, digest: String): String =
        "INSERT OR REPLACE INTO effective_source_selection (id, generation, payload, payloadDigest) " +
            "VALUES ($id, $generation, $payload, $digest)"

    private fun invalidAllocators(): List<String> =
        listOf(
            "INSERT INTO source_selection_generation (id, nextGeneration) VALUES (1, 1)",
            "INSERT OR REPLACE INTO source_selection_generation (id, nextGeneration) VALUES (0, 0)",
            "INSERT OR REPLACE INTO source_selection_generation (id, nextGeneration) VALUES (0, 1.5)",
            "UPDATE source_selection_generation SET id = 1",
            "UPDATE source_selection_generation SET nextGeneration = -1",
            "UPDATE source_selection_generation SET nextGeneration = 'invalid'",
        )

    private companion object {
        const val PAYLOAD_LIMIT = 4 * 1024 * 1024
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
