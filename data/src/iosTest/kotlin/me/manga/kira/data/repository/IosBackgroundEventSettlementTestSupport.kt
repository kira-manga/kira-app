@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import me.manga.kira.data.local.dao.ChapterDownloadDao
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.platform.download.BackgroundTransport
import me.manga.kira.platform.download.TransferRequest
import me.manga.kira.platform.filesystem.AppFileSystem
import okio.ForwardingFileSystem
import okio.Path
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSThread
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Holds a real filesystem call on a worker; every test releases it before joining its host. */
internal class HeldSettlementWrite {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    fun awaitRelease() {
        entered.complete(Unit)
        runBlocking { release.await() }
    }
}

internal fun IosCbzFinalizationFixture.holdManifestMove(gate: HeldSettlementWrite): AppFileSystem {
    val forwarding = object : ForwardingFileSystem(system) {
        override fun atomicMove(source: Path, target: Path) {
            if (target.name == "manifest.json") gate.awaitRelease()
            super.atomicMove(source, target)
        }
    }
    return object : AppFileSystem by appFileSystem {
        override fun fileSystem() = forwarding
    }
}

internal fun ChapterDownloadDao.holdProgressUpdate(gate: HeldSettlementWrite): ChapterDownloadDao {
    val delegate = this
    return object : ChapterDownloadDao by delegate {
        override suspend fun updateProgressForArtifact(chapterId: Long, downloadId: Long, token: String, progress: Int) {
            gate.entered.complete(Unit)
            gate.release.await()
            delegate.updateProgressForArtifact(chapterId, downloadId, token, progress)
        }
    }
}

internal fun BackgroundTransport.holdNextEnqueue(gate: HeldSettlementWrite): BackgroundTransport {
    val delegate = this
    return object : BackgroundTransport by delegate {
        override suspend fun enqueue(requests: List<TransferRequest>) {
            gate.entered.complete(Unit)
            gate.release.await()
            delegate.enqueue(requests)
        }
    }
}

/** Only the ordinary signal collector is held; the completion's fresh query reaches real Room. */
internal class HeldFirstDownloadObservation(private val delegate: ChapterDownloadDao) : ChapterDownloadDao by delegate {
    val collecting = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override fun observeAllDownloads(): Flow<List<ChapterDownloadEntity>> = flow {
        if (collecting.complete(Unit)) release.await()
        emitAll(delegate.observeAllDownloads())
    }
}

internal class EventSettlementTrace {
    private val calls = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())

    fun record(name: String) {
        val onMain = NSThread.isMainThread
        calls.update { it + (name to onMain) }
    }

    fun assertNames(vararg names: String) {
        val snapshot = calls.value
        assertEquals(names.toList(), snapshot.map { it.first })
        assertTrue(snapshot.all { it.second }, "scheduling and system completion must run on native main")
    }
}

/** runTest alone does not service native Dispatchers.Main. No simulated main dispatcher is used. */
internal fun pumpNativeMainUntil(done: CompletableDeferred<Unit>) {
    val deadline = NSDate.dateWithTimeIntervalSinceNow(5.0)
    while (!done.isCompleted && deadline.timeIntervalSinceNow > 0) {
        NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
    }
    assertTrue(done.isCompleted, "native main completion did not arrive")
}
