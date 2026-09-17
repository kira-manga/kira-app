package me.manga.kira.di

import io.ktor.client.engine.HttpClientEngine
import me.manga.kira.data.remote.complaint.ComplaintSessionEngineOwner
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.presentation.complaint.ComplaintViewModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Existing graph fixture's counted borrowed owner, separated from its storage and wire payloads. */
internal class GraphEngineOwner(
    private val name: String,
    private val delegate: HttpClientEngine,
    private val events: MutableList<String>,
    private val failAccess: () -> Boolean,
    private val failClose: () -> Boolean,
) : ComplaintSessionEngineOwner {
    var closed: Boolean = false
        private set
    override val engine: HttpClientEngine
        get() {
            if (failAccess()) error("Synthetic borrowed engine getter failure")
            return delegate
        }

    override fun close() {
        if (closed) return
        closed = true
        events += "close:$name"
        delegate.close()
        if (failClose()) error("Synthetic native close details")
    }
}

internal fun ComplaintViewModel.assertLoadedReadOnlyHistory(): ComplaintHistory.Backend {
    val history = assertIs<ComplaintHistory.Backend>(state.value.history)
    assertEquals(
        "Synthetic connected subject",
        assertIs<ComplaintOwnerRow.Report>(history.items.single()).subject,
    )
    assertFalse(state.value.isLoading)
    assertTrue(state.value.all.isEmpty())
    assertFalse(state.value.legacyActionsAllowed)
    return history
}

/** No fake stores or engines exist before admission; every resource factory is a counted trap. */
internal fun trapGraphResources(onAllocation: () -> Unit): ComplaintBackendResources =
    ComplaintBackendResources(
        credentials = {
            onAllocation()
            error("Credential allocation must not occur")
        },
        pending = {
            onAllocation()
            error("Pending allocation must not occur")
        },
        generator = {
            onAllocation()
            error("Generator allocation must not occur")
        },
        engines =
            ComplaintBackendEngineFactories(
                enrollment = {
                    onAllocation()
                    error("Enrollment engine allocation must not occur")
                },
                session = {
                    onAllocation()
                    error("Session engine allocation must not occur")
                },
                history = {
                    onAllocation()
                    error("History engine allocation must not occur")
                },
                mutation = {
                    onAllocation()
                    error("Mutation engine allocation must not occur")
                },
            ),
        reportInputs = {
            onAllocation()
            error("Report input allocation must not occur")
        },
    )
