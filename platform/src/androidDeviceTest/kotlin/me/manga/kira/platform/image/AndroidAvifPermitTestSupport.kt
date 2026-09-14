package me.manga.kira.platform.image

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertNotNull

/** Wait for an observed queue/ownership event, not an elapsed sleep presented as ordering proof. */
internal fun awaitAvifPermitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AVIF_PERMIT_WAIT_SECONDS)
    while (!condition()) {
        if (System.nanoTime() >= deadline) throw AssertionError("AVIF permit coordination timed out.")
        Thread.yield()
    }
}

/** Hold the real runInterruptible return dispatch until the test has cancelled the caller. */
internal class AvifPermitReturnDispatcher : CoroutineDispatcher() {
    private val tasks = LinkedBlockingQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.add(block)
    }

    fun takeTask(): Runnable = assertNotNull(tasks.poll(AVIF_PERMIT_WAIT_SECONDS, TimeUnit.SECONDS))

    fun finishCancelled(job: Job, heldTask: Runnable?) {
        job.cancel()
        heldTask?.run()
        while (!job.isCompleted) takeTask().run()
    }
}

internal const val AVIF_PERMIT_WAIT_SECONDS = 10L
