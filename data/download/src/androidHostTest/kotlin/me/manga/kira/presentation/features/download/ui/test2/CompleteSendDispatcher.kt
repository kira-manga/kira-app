package me.manga.kira.presentation.features.download.ui.test2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Retains only the identified flowOn producer's real resume after its final send suspends. */
internal class CompleteSendDispatcher(
    val producer: DownloadJobWitness,
) : CoroutineDispatcher(),
    AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "app75-complete-producer") }
    private val lock = Any()
    private val activeTurn = ThreadLocal<ProducerTurn>()
    private val pending = mutableListOf<Runnable>()
    private var armed = false
    private var released = false
    val suspendedSend = CompletableDeferred<Unit>()
    val queuedResume = CompletableDeferred<Unit>()
    val retainedResumeCount = AtomicInteger()

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val job = checkNotNull(context[Job])
        if (producer.job == null) producer.capture(job)
        val task = Runnable { runTurn(job, block) }
        synchronized(lock) {
            if (armed && !released && job === producer.job) {
                pending += task
                retainedResumeCount.incrementAndGet()
                println("APP75 producer=${System.identityHashCode(job)} real-send-resume-retained")
                queuedResume.complete(Unit)
                return
            }
        }
        executor.execute(task)
    }

    fun markAfterRealNotificationReturn(job: Job) {
        val turn = checkNotNull(activeTurn.get())
        assertSame(producer.job, job)
        assertSame(job, turn.job)
        check(!turn.marked && !armed)
        turn.marked = true
    }

    private fun runTurn(
        job: Job,
        block: Runnable,
    ) {
        val turn = ProducerTurn(job)
        activeTurn.set(turn)
        try {
            block.run()
        } finally {
            activeTurn.remove()
            if (turn.marked) recordSuspendedTurn(job)
        }
    }

    private fun recordSuspendedTurn(job: Job) {
        synchronized(lock) { armed = !released }
        if (job.isActive && !job.isCompleted) {
            println("APP75 producer=${System.identityHashCode(job)} marked-turn-returned-unfinished")
            suspendedSend.complete(Unit)
        } else {
            suspendedSend.completeExceptionally(AssertionError("Marked Complete producer did not suspend"))
        }
    }

    fun release() {
        val resumes =
            synchronized(lock) {
                released = true
                pending.toList().also { pending.clear() }
            }
        resumes.forEach(executor::execute)
    }

    override fun close() {
        release()
        executor.shutdown()
        if (!executor.awaitTermination(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Owned producer executor failed to terminate"
            }
        }
    }
}

private class ProducerTurn(
    val job: Job,
) {
    var marked = false
}

/** Cancellation-start and completion are separate receipts; a cancelled Future proves neither. */
internal class DownloadJobWitness(
    private val label: String,
) {
    private val captured = AtomicReference<Job?>()
    val job: Job? get() = captured.get()
    val entered = CompletableDeferred<Job>()
    val cancelling = CompletableDeferred<Throwable>()
    val completed = CompletableDeferred<Throwable?>()

    @OptIn(InternalCoroutinesApi::class)
    fun capture(actual: Job) {
        if (!captured.compareAndSet(null, actual)) {
            assertSame(job, actual)
            return
        }
        actual.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
            if (cause != null) cancelling.complete(cause)
        }
        actual.invokeOnCompletion { cause -> completed.complete(cause) }
        entered.complete(actual)
        println("APP75 job=$label captured=${System.identityHashCode(actual)}")
    }

    suspend fun awaitCancellation() {
        val cause = withTimeout(GATE_TIMEOUT_MILLIS) { cancelling.await() }
        assertTrue(cause is CancellationException)
        assertTrue(checkNotNull(job).isCancelled)
        println("APP75 job=$label cancelling=${cause.javaClass.name}")
    }

    suspend fun join() {
        val actual = checkNotNull(job) { "$label Job never reached its real capture seam" }
        withTimeout(GATE_TIMEOUT_MILLIS) { actual.join() }
        val cause = withTimeout(GATE_TIMEOUT_MILLIS) { completed.await() }
        println("APP75 job=$label joined cause=${cause?.javaClass?.name}")
    }
}
