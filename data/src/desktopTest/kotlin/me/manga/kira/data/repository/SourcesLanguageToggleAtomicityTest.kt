package me.manga.kira.data.repository

import androidx.sqlite.SQLiteException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.MangaDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Generated Room DAO + production repository, without a test-supplied mutation transaction. */
@OptIn(ExperimentalCoroutinesApi::class)
class SourcesLanguageToggleAtomicityTest {
    private var database: MangaDatabase? = null

    @BeforeTest
    fun open() {
        database = languageToggleDatabase()
    }

    @AfterTest
    fun close() {
        // runTest has cancelled/joined its command jobs and backgroundScope legacy collectors first.
        database?.close()
        database = null
    }

    @Test
    fun confirmedPostCommitCancellationLeavesTheWholeGroupAndReleasesAdmission() =
        runTest {
            val fixture = fixture()
            val gate = EnablementGate().also { fixture.dao.afterNextWrite = it }
            try {
                val command = async { fixture.repository.setLanguageEnabled("(EN)", true) }
                gate.reached.await()
                fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")

                cancelAndAssert(command)
                fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")
                fixture.repository.setLanguageEnabledWithFallback("(XX)", "(EN)", false)
                fixture.assertOnlyEnabled()
            } finally {
                gate.release()
            }
        }

    @Test
    fun cancelledWaiterAndPreDelegateCommandWriteNothingAndReleaseAdmission() =
        runTest {
            val fixture = fixture()
            val gate = EnablementGate().also { fixture.dao.beforeNextWrite = it }
            try {
                val active = async { fixture.repository.setLanguageEnabled("(EN)", true) }
                gate.reached.await()
                val waiter = async { fixture.repository.setLanguageEnabledWithFallback("(XX)", "(EN)", false) }
                runCurrent()
                assertFalse(waiter.isCompleted)
                assertEquals(1, fixture.dao.snapshots, "The waiter must not read targets before admission")
                assertEquals(1, fixture.catalog.snapshotCalls)

                cancelAndAssert(waiter)
                cancelAndAssert(active)
                assertTrue(fixture.dao.committedWrites.isEmpty())
                fixture.assertOnlyEnabled()
                fixture.repository.setLanguageEnabled("(EN)", true)
                fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")
            } finally {
                gate.release()
            }
        }

    @Test
    fun explicitLanguageThenOppositeFallbackCommitInAdmissionOrder() =
        runTest {
            val fixture = fixture()
            val gate = EnablementGate().also { fixture.dao.afterNextWrite = it }
            try {
                val explicit = async { fixture.repository.setLanguageEnabled("(EN)", true) }
                gate.reached.await()
                val fallback = async { fixture.repository.setLanguageEnabledWithFallback("(XX)", "(EN)", false) }
                runCurrent()
                assertFalse(fallback.isCompleted)
                assertEquals(1, fixture.dao.snapshots)
                fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")

                gate.release()
                explicit.await()
                fallback.await()
                fixture.assertOnlyEnabled()
                assertEquals(listOf(englishNames to true, englishNames to false), fixture.dao.committedWrites)
            } finally {
                gate.release()
            }
        }

    @Test
    fun bulkAndSingleSourceShareAdmissionWithoutASecondBulkPrefix() =
        runTest {
            val fixture = fixture()
            val gate = EnablementGate().also { fixture.dao.afterNextWrite = it }
            try {
                val bulk = async { fixture.repository.setLanguageEnabled("(EN)", true) }
                gate.reached.await()
                val single = async { fixture.repository.setSourceEnabled("EnglishTwo", false) }
                runCurrent()
                assertFalse(single.isCompleted)
                fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")

                gate.release()
                bulk.await()
                single.await()
                fixture.assertOnlyEnabled("EnglishOne")
                assertEquals(listOf(englishNames to true, setOf("EnglishTwo") to false), fixture.dao.committedWrites)
            } finally {
                gate.release()
            }
        }

    @Test
    fun catalogMembershipIsCapturedOnceBeforeThePersistedRowReadSuspends() =
        runTest {
            val fixture = fixture()
            fixture.catalog.descriptors = listOf(fakeDescriptor("EnglishOne"))
            val gate = EnablementGate().also { fixture.dao.beforeNextRead = it }
            try {
                val command = async { fixture.repository.setLanguageEnabled("(EN)", true) }
                gate.reached.await()
                assertEquals(1, fixture.catalog.snapshotCalls)
                fixture.catalog.descriptors = listOf(fakeDescriptor("EnglishTwo"))
                gate.release()
                command.await()

                fixture.assertOnlyEnabled("EnglishOne")
                assertEquals(1, fixture.catalog.snapshotCalls, "Do not re-read membership per row or after suspension")
            } finally {
                gate.release()
            }
        }

    @Test
    fun realSqlAbortRollsBackEveryBulkTargetAndBothWritePathsPropagateFailure() =
        runTest {
            val fixture = fixture()
            fixture.installAbortTrigger()

            val failure = assertFailsWith<SQLiteException> { fixture.repository.setLanguageEnabled("(EN)", true) }
            assertTrue(failure.message.orEmpty().contains("language-toggle-test"))
            fixture.assertOnlyEnabled()

            // Setup one enabled row, then the same trigger independently fails the single-name path.
            fixture.persisted.setEnabledByName("EnglishOne", true)
            assertFailsWith<SQLiteException> { fixture.repository.setSourceEnabled("EnglishTwo", true) }
            fixture.assertOnlyEnabled("EnglishOne")

            fixture.dropAbortTrigger()
            fixture.repository.setLanguageEnabled("(EN)", true)
            fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")
        }

    @Test
    fun generatedBulkQueryHandlesEmptyAndMissingNamesWithoutChangingOtherColumns() =
        runTest {
            val fixture = fixture()
            assertEquals(0, fixture.persisted.setEnabledByNames(emptyList(), true))
            fixture.assertOnlyEnabled()
            assertEquals(2, fixture.persisted.setEnabledByNames(englishNames.toList() + "Missing", true))
            fixture.assertOnlyEnabled("EnglishOne", "EnglishTwo")
        }

    private suspend fun TestScope.fixture(): SourcesLanguageToggleRoomFixture =
        SourcesLanguageToggleRoomFixture(checkNotNull(database), backgroundScope).also { it.seed() }

    private companion object {
        val englishNames = setOf("EnglishOne", "EnglishTwo")
    }
}
