package me.manga.kira.platform.download

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.XcodeSeverityWriter
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSLog
import platform.posix.fflush
import platform.posix.getpid
import platform.posix.write
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Run alone in a fresh process with external stdout, stderr and unified-log capture.
 * Passing XML proves stimulus completion, not privacy: every channel control and readable safe
 * payload must be observed, with no private canary content. Private-only OSLog output is incomplete.
 * The explicit Warn floor models release filtering, not the shipping bootstrap or distribution.
 */
@OptIn(ExperimentalForeignApi::class)
class BgDownloadLogAppleWriterCanaryTest {
    @Test
    fun defaultAppleWriterEmitsCanaryForExternalCapture() {
        val writer = initialAppleWriter()
        val previousFloor = Logger.config.minSeverity
        // Initialize the tagged Bg logger before changing its shared config's floor.
        val previousVerbose = BgDownloadLog.VERBOSE
        val previousPerformance = BgDownloadLog.DLPERF
        try {
            BgDownloadLog.VERBOSE = true
            BgDownloadLog.DLPERF = true
            emitPhase("WARN", Severity.Warn, 7001L, writer)
            emitPhase("INFO", Severity.Info, 7002L, writer)
        } finally {
            Logger.setMinSeverity(previousFloor)
            BgDownloadLog.VERBOSE = previousVerbose
            BgDownloadLog.DLPERF = previousPerformance
        }
        assertWriterRetained(writer)
    }

    private fun initialAppleWriter(): LogWriter {
        val writers = Logger.config.logWriterList
        assertTrue(writers.size == 1, "Expected one initial default Apple writer")
        val writer = writers.single()
        assertTrue(writer::class == XcodeSeverityWriter::class, "Unexpected initial default Apple writer")
        return writer
    }

    private fun assertWriterRetained(writer: LogWriter) {
        val writers = Logger.config.logWriterList
        assertTrue(writers.size == 1 && writers[0] === writer, "Default Apple writer identity changed")
    }

    private fun emitPhase(
        phase: String,
        floor: Severity,
        chapterId: Long,
        writer: LogWriter,
    ) {
        assertWriterRetained(writer)
        Logger.setMinSeverity(floor)
        assertTrue(Logger.config.minSeverity == floor, "Expected canary floor was not applied")
        emitChannelBoundary(phase, "BEGIN")
        emitDefaultWriterControl(phase)
        emitBackgroundCanary(chapterId)
        assertWriterRetained(writer)
        emitChannelBoundary(phase, "END")
    }

    private fun emitChannelBoundary(
        phase: String,
        boundary: String,
    ) {
        val context = "phase=$phase boundary=$boundary pid=${getpid()}"
        println("APP70_CONTROL_STDOUT $context")
        writeStderrControl("APP70_CONTROL_STDERR $context\n")
        // Code-owned ASCII context contains no format directives or negative-case input.
        NSLog("APP70_CONTROL_UNIFIED $context")
        assertTrue(fflush(null) == 0, "Canary output flush failed")
    }

    private fun writeStderrControl(message: String) {
        val bytes = message.encodeToByteArray()
        val written = bytes.usePinned { write(2, it.addressOf(0), bytes.size.convert()) }
        assertTrue(written == bytes.size.toLong(), "Stderr control write was incomplete")
    }

    private fun emitDefaultWriterControl(phase: String) {
        val context = "phase=$phase pid=${getpid()}"
        val control =
            IllegalStateException(
                "APP70_CONTROL_ROOT $context",
                IllegalArgumentException("APP70_CONTROL_CAUSE $context"),
            )
        control.addSuppressed(IllegalArgumentException("APP70_CONTROL_SUPPRESSED $context"))
        // This intentionally exercises the real default writer's separate Throwable stdout path.
        Logger.withTag("App70WriterControl").e(control) { "APP70_CONTROL_MESSAGE $context" }
    }

    private fun emitBackgroundCanary(chapterId: Long) {
        val fields =
            arrayOf<Pair<String, Any?>>(
                "chapterId" to chapterId,
                "pageIndex" to 3,
                "httpStatus" to 403,
                "count" to privateDiagnosticSamples[0],
                "state" to privateDiagnosticSamples[6],
                "reason" to privateDiagnosticSamples[5],
                "engine" to "/private/$PRIVACY_CANARY/chapter.cbz",
                "url" to privateDiagnosticSamples[0],
                "msg" to "$PRIVACY_CANARY-FIELD",
                "unknown-$PRIVACY_CANARY" to privateDiagnosticSamples[7],
            )
        BgDownloadLog.log("task.enqueued", *fields)
        BgDownloadLog.warn("task.httpError", *fields)
        BgDownloadLog.error(privateCanaryThrowable(), "prepare.resolve.failed", *fields)
        BgDownloadLog.dlperf("resolve.ms", *fields)
    }

    private fun privateCanaryThrowable(): Throwable =
        App70PrivateCanaryThrowable(
            "$PRIVACY_CANARY-ROOT",
            App70PrivateCanaryThrowable("$PRIVACY_CANARY-CAUSE"),
        ).apply {
            addSuppressed(App70PrivateCanaryThrowable("$PRIVACY_CANARY-SUPPRESSED"))
        }
}

private class App70PrivateCanaryThrowable(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
