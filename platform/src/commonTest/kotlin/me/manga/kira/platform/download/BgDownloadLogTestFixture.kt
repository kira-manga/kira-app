package me.manga.kira.platform.download

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

internal const val PRIVACY_CANARY = "APP70_FAKE_COOKIE_BEARER_TOKEN"

internal val privateDiagnosticSamples =
    listOf(
        "https://reader.invalid/$PRIVACY_CANARY?token=$PRIVACY_CANARY#private",
        "http://reader.invalid/$PRIVACY_CANARY",
        "/relative/$PRIVACY_CANARY?key=value",
        "%2F$PRIVACY_CANARY%3Ftoken%3D$PRIVACY_CANARY",
        "#$PRIVACY_CANARY",
        "Cookie: $PRIVACY_CANARY",
        "Authorization: Bearer $PRIVACY_CANARY",
        "token=$PRIVACY_CANARY",
    )

internal object BgDownloadLogTestFixture {
    private val recordingLock = Mutex()

    fun recording(block: (BgDownloadRecordingWriter) -> Unit) = serialized { capture(block) }

    fun serialized(block: () -> Unit) =
        runTest {
            recordingLock.withLock { block() }
        }

    // Called only inside serialized(); separate so the restoration failure path can be tested.
    fun capture(block: (BgDownloadRecordingWriter) -> Unit) {
        val previous = BgDownloadLogGlobals()
        val recorder = BgDownloadRecordingWriter()
        try {
            Logger.setLogWriters(listOf(recorder))
            Logger.setMinSeverity(Severity.Verbose)
            BgDownloadLog.VERBOSE = true
            BgDownloadLog.DLPERF = false
            block(recorder)
        } finally {
            previous.restore()
        }
    }
}

internal class BgDownloadLogGlobals {
    private val writers = Logger.config.logWriterList.toList()
    private val floor = Logger.config.minSeverity

    // Initialize BgDownloadLog's tagged logger BEFORE replacing the global config's writers/floor.
    private val verbose = BgDownloadLog.VERBOSE
    private val performance = BgDownloadLog.DLPERF

    fun restore() {
        Logger.setLogWriters(writers)
        Logger.setMinSeverity(floor)
        BgDownloadLog.VERBOSE = verbose
        BgDownloadLog.DLPERF = performance
    }

    fun assertRestored() {
        assertEquals(writers, Logger.config.logWriterList)
        assertEquals(floor, Logger.config.minSeverity)
        assertEquals(verbose, BgDownloadLog.VERBOSE)
        assertEquals(performance, BgDownloadLog.DLPERF)
    }
}

internal data class BgDownloadLogRecord(
    val severity: Severity,
    val message: String,
    val tag: String,
    val throwable: Throwable?,
)

internal class BgDownloadRecordingWriter : LogWriter() {
    val records = mutableListOf<BgDownloadLogRecord>()

    override fun log(
        severity: Severity,
        message: String,
        tag: String,
        throwable: Throwable?,
    ) {
        records += BgDownloadLogRecord(severity, message, tag, throwable)
    }

    fun assertSafeRecords(expected: Int) {
        assertTrue(expected > 0)
        assertEquals(expected, records.size)
        records.forEach {
            assertEquals("KiraBgDownload", it.tag)
            assertNull(it.throwable)
            assertTrue(PRIVACY_CANARY !in it.message)
        }
    }

    fun assertMessages(vararg expected: String) {
        assertSafeRecords(expected.size)
        assertEquals(expected.toList(), records.map { it.message })
    }
}

internal fun emitEveryBgDownloadApi(vararg fields: Pair<String, Any?>) {
    BgDownloadLog.DLPERF = true
    BgDownloadLog.log("task.enqueued", *fields)
    BgDownloadLog.warn("task.httpError", *fields)
    BgDownloadLog.error(IllegalStateException(PRIVACY_CANARY), "prepare.resolve.failed", *fields)
    BgDownloadLog.dlperf("resolve.ms", *fields)
}

internal fun emitPrivateBgDownloadFields(secret: String) {
    emitEveryBgDownloadApi(
        "pageIndex" to 3,
        "chapterId" to secret,
        "httpStatus" to secret,
        "state" to secret,
        "reason" to secret,
        "engine" to secret,
        "url" to secret,
        "msg" to secret,
        "unknown-$PRIVACY_CANARY" to secret,
    )
}

internal fun hostileDiagnosticFields(): List<Pair<String, Any?>> =
    listOf(
        "chapterId" to HostileLogNumber(),
        "chapterId" to HostileLogObject(),
        "state" to HostileLogNumber(),
        "state" to HostileLogObject(),
        "state" to HostileLogState.UNTRUSTED,
        "challenge" to HostileLogObject(),
        "pages" to listOf(PRIVACY_CANARY),
        "count" to mapOf("Cookie" to PRIVACY_CANARY),
        "unknown-$PRIVACY_CANARY" to HostileLogObject(),
    )

internal class HostileLogObject {
    override fun toString(): String = fail("Unexpected object rendering")

    override fun equals(other: Any?): Boolean = fail("Unexpected object equality")

    override fun hashCode(): Int = fail("Unexpected object hash")
}

internal class HostileLogNumber : Number() {
    override fun toByte(): Byte = throw AssertionError("Unexpected Number conversion")

    override fun toShort(): Short = throw AssertionError("Unexpected Number conversion")

    override fun toInt(): Int = throw AssertionError("Unexpected Number conversion")

    override fun toLong(): Long = throw AssertionError("Unexpected Number conversion")

    override fun toFloat(): Float = throw AssertionError("Unexpected Number conversion")

    override fun toDouble(): Double = throw AssertionError("Unexpected Number conversion")

    override fun toString(): String = fail("Unexpected Number rendering")

    override fun equals(other: Any?): Boolean = fail("Unexpected Number equality")

    override fun hashCode(): Int = fail("Unexpected Number hash")
}

internal enum class HostileLogState {
    UNTRUSTED,
    ;

    override fun toString(): String = fail("Unexpected enum rendering")
}
