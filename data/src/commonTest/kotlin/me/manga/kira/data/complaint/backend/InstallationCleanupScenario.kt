package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.RecoveryIntent
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ServerTerminalFact
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.data.complaint.backend.InstallationCoordinatorFixtures as Fixtures

internal enum class InstallationCleanupPath {
    RESET,
    UNREADABLE,
    ABANDON,
    TERMINAL,
}

/** Setup invokes real admission, intent persistence and prompt APIs; no transition model is duplicated. */
internal class InstallationCleanupScenario(
    val fixture: InstallationCoordinatorFixture,
    val execute: suspend () -> Outcome<Unit>,
)

internal suspend fun prepareCleanup(path: InstallationCleanupPath): InstallationCleanupScenario =
    when (path) {
        InstallationCleanupPath.RESET -> prepareReset()
        InstallationCleanupPath.UNREADABLE -> prepareUnreadable()
        InstallationCleanupPath.ABANDON -> prepareAbandon()
        InstallationCleanupPath.TERMINAL -> prepareTerminal()
    }.also {
        it.fixture.faults.trace
            .clear()
    }

private suspend fun prepareReset(): InstallationCleanupScenario {
    val fixture = InstallationCoordinatorFixture(Fixtures.record())
    val permit = fixture.coordinator.admit().success()
    fixture.pending.slots += listOf(Fixtures.slot(1), Fixtures.slot(2))
    val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Reset(permit)).success()
    return InstallationCleanupScenario(fixture) { fixture.coordinator.confirmRecovery(prompt) }
}

private suspend fun prepareUnreadable(): InstallationCleanupScenario {
    val fixture = InstallationCoordinatorFixture(Fixtures.record())
    fixture.credentials.readFailure =
        InstallationStorageFailure.PermanentFailure(
            InstallationPermanentFailure.INVALIDATED,
        )
    fixture.pending.slots += listOf(Fixtures.slot(1), Fixtures.slot(2))
    val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Unreadable).success()
    return InstallationCleanupScenario(fixture) { fixture.coordinator.confirmRecovery(prompt) }
}

private suspend fun prepareAbandon(): InstallationCleanupScenario {
    val fixture = InstallationCoordinatorFixture(Fixtures.record())
    val deletion = fixture.coordinator.beginDeletion(fixture.coordinator.admit().success(), Fixtures.KEY).success()
    fixture.pending.slots += listOf(Fixtures.slot(1), Fixtures.slot(2))
    val prompt = fixture.coordinator.requestRecovery(RecoveryIntent.Abandon(deletion)).success()
    return InstallationCleanupScenario(fixture) { fixture.coordinator.confirmRecovery(prompt) }
}

private suspend fun prepareTerminal(): InstallationCleanupScenario {
    val fixture = InstallationCoordinatorFixture(Fixtures.record())
    val deletion = fixture.coordinator.beginDeletion(fixture.coordinator.admit().success(), Fixtures.KEY).success()
    // A synthetic future producer prerequisite, not evidence of a server 204/410 or remote erasure.
    val fact = ServerTerminalFact(deletion)
    return InstallationCleanupScenario(fixture) { fixture.coordinator.finishServerDeletion(fact) }
}
