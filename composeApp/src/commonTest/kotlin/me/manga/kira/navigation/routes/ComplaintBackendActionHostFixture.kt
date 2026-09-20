package me.manga.kira.navigation.routes

import androidx.lifecycle.ViewModel
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.core.result.AppResult
import me.manga.kira.di.ComplaintBackendGraph
import me.manga.kira.di.ComplaintBackendGraphFixture
import me.manga.kira.di.ComplaintBackendResources
import me.manga.kira.di.GRAPH_BASE
import me.manga.kira.di.GRAPH_EDIT_BODY
import me.manga.kira.di.GRAPH_EDIT_ID
import me.manga.kira.di.MobileEditGraphFixture
import me.manga.kira.di.createComplaintBackendGraph
import me.manga.kira.di.graphEditDetail
import me.manga.kira.di.graphEditHeaders
import me.manga.kira.di.graphHeaders
import me.manga.kira.di.graphHistoryResponse
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.presentation.complaint.ComplaintDetailIntent
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEffect
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteEffect
import me.manga.kira.presentation.settings.feedback.delete.BackendComplaintDeleteIntent
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditEffect
import me.manga.kira.presentation.settings.feedback.edit.BackendComplaintEditIntent
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyActivity
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyEffect
import me.manga.kira.presentation.settings.feedback.reply.ComplaintReplyIntent
import org.koin.core.KoinApplication
import org.koin.dsl.koinApplication
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Existing real candidate/CAS fixtures; only a delegated credential read can pause child cleanup. */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions") // Small test steps share this one existing candidate and lifetime.
internal class ComplaintBackendActionHostFixture(private val scope: TestScope) {
    val backend = ComplaintBackendGraphFixture(scope)
    val edit = MobileEditGraphFixture(scope, backend)
    val credentials = ActionHostCredentialReads(backend.credentials)
    var row = graphEditDetail()
    val graph: ComplaintBackendGraph
    val app: KoinApplication
    val detail: ComplaintBackendDetailOpening
    val owner: ComplaintBackendActionHostOwner
    private val deliveries = mutableListOf<Job>()

    init {
        backend.historyHandler = { request ->
            if (request.url.parameters.isEmpty()) {
                respond(row.toString(), HttpStatusCode.OK, graphEditHeaders(row.getValue("version").jsonPrimitive.long))
            } else {
                val history =
                    JsonObject(
                        Json.parseToJsonElement(graphHistoryResponse()).jsonObject + ("items" to JsonArray(listOf(row))),
                    )
                respond(history.toString(), HttpStatusCode.OK, graphHeaders())
            }
        }
        graph =
            assertIs<AppResult.Success<ComplaintBackendGraph>>(
                createComplaintBackendGraph({ GRAPH_BASE }, resources()),
            ).value
        app = koinApplication { modules(graph.module()) }
        detail = ComplaintBackendDetailOpening(app.koin)
        owner = ComplaintBackendActionHostOwner(app.koin, detail.detail)
    }

    private fun resources(): ComplaintBackendResources {
        val original = edit.resources()
        return ComplaintBackendResources(
            { credentials }, original.pending, original.generator, original.engines, original.inputs,
        )
    }

    fun idle() = scope.runCurrent()

    fun select(): ComplaintDetail.Owned {
        detail.select(GRAPH_EDIT_ID)
        scope.runCurrent()
        return assertIs<ComplaintDetail.Owned>(detail.detail.state.value.detail)
    }

    fun setVersion(version: Long) {
        row =
            JsonObject(
                row + mapOf(
                    "version" to JsonPrimitive(version),
                    "actionTag" to JsonPrimitive("\"complaint-$GRAPH_EDIT_ID-v$version\""),
                ),
            )
    }

    fun refresh(): ComplaintDetail.Owned {
        detail.detail.submit(ComplaintDetailIntent.Retry)
        scope.runCurrent()
        return assertIs<ComplaintDetail.Owned>(detail.detail.state.value.detail)
    }

    fun open(
        action: ComplaintBackendAction,
        target: ComplaintDetail.Owned = select(),
    ): ComplaintBackendActionOpening {
        owner.open(action, target)
        scope.runCurrent()
        return assertIs<ComplaintBackendActionSlot.Action>(owner.state.value).opening
    }

    fun changeDraft(opening: ComplaintBackendActionOpening) {
        when (val panel = opening.panel) {
            is ComplaintBackendActionPanel.Reply -> panel.viewModel.submit(ComplaintReplyIntent.ChangeBody(GRAPH_EDIT_BODY))
            is ComplaintBackendActionPanel.Edit -> {
                panel.viewModel.submit(BackendComplaintEditIntent.ChangeSubject("Replacement subject"))
                panel.viewModel.submit(BackendComplaintEditIntent.ChangeBody(GRAPH_EDIT_BODY))
            }
            is ComplaintBackendActionPanel.Delete -> Unit
        }
        scope.runCurrent()
    }

    fun submit(opening: ComplaintBackendActionOpening) {
        when (val panel = opening.panel) {
            is ComplaintBackendActionPanel.Reply -> panel.viewModel.submit(ComplaintReplyIntent.Submit)
            is ComplaintBackendActionPanel.Edit -> panel.viewModel.submit(BackendComplaintEditIntent.Submit)
            is ComplaintBackendActionPanel.Delete -> panel.viewModel.submit(BackendComplaintDeleteIntent.Confirm)
        }
        scope.runCurrent()
    }

    fun holdAction(opening: ComplaintBackendActionOpening): ActionHostReadHold {
        changeDraft(opening)
        val hold = credentials.holdNext()
        submit(opening)
        assertTrue(hold.entered.isCompleted)
        return hold
    }

    fun collectFinish(opening: ComplaintBackendActionOpening): Job =
        scope.backgroundScope.launch { owner.actionFinished(opening, opening.requestsRecovery()) }.also { deliveries += it }

    fun collectFinish(opening: ComplaintBackendRequestOpening): Job =
        scope.backgroundScope.launch {
            opening.viewModel.effects.first { it == SettingsFeedbackEffect.Closed }
            owner.recoveryFinished(opening)
        }.also { deliveries += it }

    fun requestClose(opening: ComplaintBackendActionOpening, recovery: Boolean) {
        when (val panel = opening.panel) {
            is ComplaintBackendActionPanel.Reply ->
                panel.viewModel.submit(if (recovery) ComplaintReplyIntent.OpenRecovery else ComplaintReplyIntent.Close)
            is ComplaintBackendActionPanel.Edit ->
                panel.viewModel.submit(if (recovery) BackendComplaintEditIntent.OpenRecovery else BackendComplaintEditIntent.Close)
            is ComplaintBackendActionPanel.Delete ->
                panel.viewModel.submit(if (recovery) BackendComplaintDeleteIntent.OpenRecovery else BackendComplaintDeleteIntent.Close)
        }
        scope.runCurrent()
    }

    fun assertNoWriteOrSetup() {
        assertTrue(edit.pending.slots.isEmpty() && edit.pending.transitions.isEmpty())
        assertTrue(backend.mutationCalls == 0 && backend.deletionCalls == 0)
        assertTrue(backend.credentials.writes == 0 && backend.generations == 0)
        assertTrue(backend.deletionKeyGenerations == 0 && backend.reportIdentifierGenerations == 0)
        assertTrue(backend.reportMetadataReads == 0 && edit.editKeyGenerations == 0)
        assertTrue(backend.events.none { it == "request:enrollment" })
    }

    fun close() {
        credentials.releaseAll()
        deliveries.forEach { it.cancel() }
        owner.close()
        detail.close()
        scope.runCurrent()
        app.close()
        graph.close()
        scope.runCurrent()
    }
}

/** Delegation preserves every existing SPI behavior; no replacement protocol or pending-store fake. */
internal class ActionHostCredentialReads(
    private val delegate: InstallationCredentialStore,
) : InstallationCredentialStore by delegate {
    var reads = 0
        private set
    private var next: ActionHostReadHold? = null
    private val holds = mutableListOf<ActionHostReadHold>()

    fun holdNext(): ActionHostReadHold {
        check(next == null)
        return ActionHostReadHold().also {
            next = it
            holds += it
        }
    }

    override suspend fun read(): CredentialReadResult {
        reads++
        val hold = next
        next = null
        hold?.awaitCleanup()
        return delegate.read()
    }

    fun releaseAll() {
        next = null
        holds.forEach { it.release.complete(Unit) }
    }
}

/** The read's child keeps its parent incomplete after cancellation, not merely until UI becomes CLOSED. */
internal class ActionHostReadHold {
    val entered = CompletableDeferred<Job>()
    val closing = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    suspend fun awaitCleanup(): Nothing {
        val caller = currentCoroutineContext().job
        return coroutineScope {
            launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        closing.complete(Unit)
                        release.await()
                    }
                }
            }
            entered.complete(caller)
            awaitCancellation()
        }
    }
}

internal val ComplaintBackendActionOpening.viewModel: ViewModel
    get() =
        when (val current = panel) {
            is ComplaintBackendActionPanel.Reply -> current.viewModel
            is ComplaintBackendActionPanel.Edit -> current.viewModel
            is ComplaintBackendActionPanel.Delete -> current.viewModel
        }

internal fun ComplaintBackendActionOpening.isUiClosed(): Boolean =
    when (val current = panel) {
        is ComplaintBackendActionPanel.Reply -> current.viewModel.state.value.activity == ComplaintReplyActivity.CLOSED
        is ComplaintBackendActionPanel.Edit -> current.viewModel.state.value.closed
        is ComplaintBackendActionPanel.Delete -> current.viewModel.state.value.closed
    }

/** Consume the real, content-free VM effect, never manufacture completion from its early UI state. */
internal suspend fun ComplaintBackendActionOpening.requestsRecovery(): Boolean =
    when (val current = panel) {
        is ComplaintBackendActionPanel.Reply -> current.viewModel.effects.first() == ComplaintReplyEffect.OpenSettingsRecovery
        is ComplaintBackendActionPanel.Edit -> current.viewModel.effects.first() == BackendComplaintEditEffect.OpenRecovery
        is ComplaintBackendActionPanel.Delete -> current.viewModel.effects.first() == BackendComplaintDeleteEffect.OpenRecovery
    }

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withActionHost(test: suspend ComplaintBackendActionHostFixture.() -> Unit) {
    val fixture = ComplaintBackendActionHostFixture(this)
    try {
        runCurrent()
        fixture.test()
    } finally {
        fixture.close()
    }
}
