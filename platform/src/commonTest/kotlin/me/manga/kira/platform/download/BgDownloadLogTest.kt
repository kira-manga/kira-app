package me.manga.kira.platform.download

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BgDownloadLogTest {
    @Test
    fun warningAndErrorKeepUsefulDiagnosticsWithVerboseDisabled() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.VERBOSE = false
            BgDownloadLog.log("task.enqueued", "chapterId" to 12L)
            BgDownloadLog.warn("task.httpError", "chapterId" to 12L, "pageIndex" to 3, "httpStatus" to 403)
            BgDownloadLog.error(
                IllegalStateException(PRIVACY_CANARY),
                "task.didCompleteWithError",
                "mangaId" to 4L,
                "taskId" to ULong.MAX_VALUE,
                "errorCode" to -1009L,
                "challenge" to true,
            )
            recorder.assertMessages(
                "task.httpError | chapterId=12 pageIndex=3 httpStatus=403",
                "task.didCompleteWithError | mangaId=4 taskId=18446744073709551615 errorCode=-1009 challenge=true",
            )
            assertEquals(listOf(Severity.Warn, Severity.Error), recorder.records.map { it.severity })
        }

    @Test
    fun infoUsesVerboseGateAndStableTag() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.VERBOSE = false
            BgDownloadLog.log("retry.scheduled", "attempt" to 2)
            assertEquals(0, recorder.records.size)
            BgDownloadLog.VERBOSE = true
            BgDownloadLog.log("retry.scheduled", "chapterId" to 12L, "attempt" to 2, "delayMs" to 500L)
            recorder.assertMessages("retry.scheduled | chapterId=12 attempt=2 delayMs=500")
            assertEquals(Severity.Info, recorder.records.single().severity)
        }

    @Test
    fun performanceGateIsIndependentOfVerbose() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.VERBOSE = false
            BgDownloadLog.dlperf("resolve.ms", "ms" to 27L)
            assertEquals(0, recorder.records.size)
            BgDownloadLog.DLPERF = true
            BgDownloadLog.dlperf("resolve.ms", "chapterId" to 12L, "pages" to 8, "ms" to 27L)
            BgDownloadLog.DLPERF = false
            BgDownloadLog.dlperf("resolve.ms", "ms" to 99L)
            recorder.assertMessages("DLPERF.resolve.ms | chapterId=12 pages=8 ms=27")
            assertEquals(Severity.Info, recorder.records.single().severity)
        }

    @Test
    fun raisedWarnFloorFiltersInfoAndPerformance() =
        BgDownloadLogTestFixture.recording { recorder ->
            Logger.setMinSeverity(Severity.Warn)
            emitEveryBgDownloadApi("chapterId" to 12L)
            recorder.assertMessages("task.httpError | chapterId=12", "prepare.resolve.failed | chapterId=12")
            assertEquals(listOf(Severity.Warn, Severity.Error), recorder.records.map { it.severity })
        }

    @Test
    fun allApisDropSecretsFromKnownAndUnknownFields() =
        BgDownloadLogTestFixture.recording { recorder ->
            privateDiagnosticSamples.forEach(::emitPrivateBgDownloadFields)
            recorder.assertSafeRecords(privateDiagnosticSamples.size * 4)
            assertTrue(recorder.records.all { it.message.endsWith(" | pageIndex=3") })
        }

    @Test
    fun exceptionsIncludingNestedAndSuppressedNeverReachTheSink() =
        BgDownloadLogTestFixture.recording { recorder ->
            val nested = IllegalStateException(PRIVACY_CANARY, IllegalArgumentException(PRIVACY_CANARY))
            nested.addSuppressed(IllegalStateException("Cookie: $PRIVACY_CANARY"))
            listOf(null, IllegalStateException(PRIVACY_CANARY), nested).forEach { failure ->
                BgDownloadLog.error(failure, "prepare.resolve.failed", "chapterId" to 12L)
            }
            recorder.assertMessages(
                "prepare.resolve.failed | chapterId=12",
                "prepare.resolve.failed | chapterId=12",
                "prepare.resolve.failed | chapterId=12",
            )
        }

    @Test
    fun hostileKnownKeyValuesAreNeverConvertedComparedOrHashed() =
        BgDownloadLogTestFixture.recording { recorder ->
            val hostile = hostileDiagnosticFields()
            hostile.forEach { field -> emitEveryBgDownloadApi(field, "pageIndex" to 3) }
            recorder.assertSafeRecords(hostile.size * 4)
            assertTrue(recorder.records.all { it.message.endsWith(" | pageIndex=3") })
        }

    @Test
    fun primitiveNumbersIncludingNativeUnsignedTaskIdsStayVisible() =
        BgDownloadLogTestFixture.recording { recorder ->
            val numbers =
                listOf(
                    1.toByte(),
                    2.toShort(),
                    3,
                    4L,
                    5.toUByte(),
                    6.toUShort(),
                    UInt.MAX_VALUE,
                    ULong.MAX_VALUE,
                    1.5f,
                    2.5,
                )
            numbers.forEach { BgDownloadLog.log("task.enqueued", "taskId" to it) }
            val expected = listOf("1", "2", "3", "4", "5", "6", "4294967295", "18446744073709551615", "1.5", "2.5")
            recorder.assertSafeRecords(expected.size)
            assertEquals(expected.map { "task.enqueued | taskId=$it" }, recorder.records.map { it.message })
        }

    @Test
    fun nonFiniteAndWrongScalarTypesAreOmitted() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.log(
                "resolve.ms",
                "ms" to Double.NaN,
                "totalMs" to Float.POSITIVE_INFINITY,
                "progress" to Double.NEGATIVE_INFINITY,
                "q" to Float.NaN,
                "state" to 42L,
                "challenge" to "true",
                "pages" to null,
                "count" to 7,
            )
            recorder.assertMessages("resolve.ms | count=7")
        }

    @Test
    fun stringTokensAreClosedPerField() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.log(
                "reconcile.plan",
                "state" to "RUNNING",
                "reason" to "startup",
                "engine" to "BackgroundUrlSession",
                "mode" to "RUNNING",
                "from" to "rowGone",
                "to" to "FAILED",
                "existingState" to "ABSENT",
            )
            recorder.assertMessages(
                "reconcile.plan | state=RUNNING reason=startup engine=BackgroundUrlSession " +
                    "to=FAILED existingState=ABSENT",
            )
        }

    @Test
    fun unknownInvalidAndOversizedEventsNeverEchoInput() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.DLPERF = true
            val events =
                listOf(
                    PRIVACY_CANARY,
                    "https://$PRIVACY_CANARY",
                    "task.enqueued\n$PRIVACY_CANARY",
                    PRIVACY_CANARY.repeat(1000),
                )
            events.forEach { event ->
                BgDownloadLog.log(event, "chapterId" to 12L)
                BgDownloadLog.warn(event, "chapterId" to 12L)
                BgDownloadLog.error(null, event, "chapterId" to 12L)
                BgDownloadLog.dlperf(event, "chapterId" to 12L)
            }
            recorder.assertSafeRecords(events.size * 4)
            assertEquals(
                List(events.size) {
                    List(3) { "event.redacted | chapterId=12" } + "DLPERF.event.redacted | chapterId=12"
                }.flatten(),
                recorder.records.map { it.message },
            )
        }

    @Test
    fun historicalSpacedStartupEventIsExplicitlyRetained() =
        BgDownloadLogTestFixture.recording { recorder ->
            BgDownloadLog.log("lifecycle.launch startupReconcile", "queued" to 2)
            recorder.assertMessages("lifecycle.launch startupReconcile | queued=2")
        }

    @Test
    fun fieldScanStopsAtTwelveIncludingRejectedFields() =
        BgDownloadLogTestFixture.recording { recorder ->
            val known = List(12) { "count" to 1 } + ("pageIndex" to 99)
            BgDownloadLog.warn("task.enqueued", *known.toTypedArray())
            val unknown = List(12) { "unknown-$it" to HostileLogObject() } + ("chapterId" to 12L)
            BgDownloadLog.warn("task.enqueued", *unknown.toTypedArray())
            recorder.assertMessages("task.enqueued | " + List(12) { "count=1" }.joinToString(" "), "task.enqueued")
        }

    @Test
    fun outputLimitDropsWholeFieldsInsteadOfTruncatingValues() =
        BgDownloadLogTestFixture.recording { recorder ->
            val fields = Array(12) { "activeChapters" to ULong.MAX_VALUE }
            BgDownloadLog.warn("task.enqueued", *fields)
            recorder.assertMessages(
                "task.enqueued | " + List(6) { "activeChapters=18446744073709551615" }.joinToString(" "),
            )
            val message =
                recorder.records
                    .single()
                    .message
            assertTrue(message.length <= 256)
        }

    @Test
    fun oversizedKeysAndTokensAreRejectedWithoutTruncation() =
        BgDownloadLogTestFixture.recording { recorder ->
            val oversized = PRIVACY_CANARY.repeat(1000)
            emitEveryBgDownloadApi(oversized to 3, "state" to oversized, "reason" to oversized, "pageIndex" to 3)
            recorder.assertSafeRecords(4)
            assertTrue(recorder.records.all { it.message.endsWith(" | pageIndex=3") })
        }

    @Test
    fun globalWritersFloorAndFlagsRestoreAfterAssertionFailure() =
        BgDownloadLogTestFixture.serialized {
            val original = BgDownloadLogGlobals()
            try {
                Logger.setLogWriters(listOf(BgDownloadRecordingWriter()))
                Logger.setMinSeverity(Severity.Warn)
                BgDownloadLog.VERBOSE = false
                BgDownloadLog.DLPERF = true
                val expected = BgDownloadLogGlobals()
                assertFailsWith<AssertionError> {
                    BgDownloadLogTestFixture.capture {
                        Logger.setMinSeverity(Severity.Error)
                        BgDownloadLog.VERBOSE = true
                        BgDownloadLog.DLPERF = false
                        assertTrue(false, "Synthetic recorder assertion failure")
                    }
                }
                expected.assertRestored()
            } finally {
                original.restore()
            }
        }
}
