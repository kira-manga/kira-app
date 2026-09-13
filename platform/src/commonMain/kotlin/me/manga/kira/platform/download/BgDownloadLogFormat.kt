package me.manga.kira.platform.download

/** Closed diagnostic vocabulary, not a scrubber for arbitrary application data. */
internal object BgDownloadLogFormat {
    private const val MAX_EVENT_LENGTH = 64
    private const val MAX_KEY_LENGTH = 24
    private const val MAX_TOKEN_LENGTH = 32
    private const val MAX_FIELDS = 12
    private const val MAX_OUTPUT_LENGTH = 256

    fun format(
        event: String,
        fields: Array<out Pair<String, Any?>>,
        performance: Boolean = false,
    ): String {
        val safeEvent = if (event.length <= MAX_EVENT_LENGTH && event in events) event else "event.redacted"
        val rendered = StringBuilder(if (performance) "DLPERF.$safeEvent" else safeEvent)
        var separator = " | "
        for (index in 0 until minOf(fields.size, MAX_FIELDS)) {
            val (key, value) = fields[index]
            val safeValue = fieldValue(key, value)
            if (safeValue != null) {
                val field = "$key=$safeValue"
                if (rendered.length + separator.length + field.length > MAX_OUTPUT_LENGTH) break
                rendered.append(separator).append(field)
                separator = " "
            }
        }
        return rendered.toString()
    }

    private fun fieldValue(
        key: String,
        value: Any?,
    ): String? {
        if (key.length > MAX_KEY_LENGTH) return null
        return when {
            key in numericFields -> number(value)
            key in booleanFields -> (value as? Boolean)?.toString()
            else ->
                tokenFields[key]?.let { allowed ->
                    if (value is String && value.length <= MAX_TOKEN_LENGTH && value in allowed) value else null
                }
        }
    }

    // Never widen this to Number: its conversion/toString methods can be arbitrary user code.
    // Unsigned primitives retain NSURLSession's Native task identifiers without narrowing to Int.
    private fun number(value: Any?): String? =
        when (value) {
            is Byte -> value.toString()
            is Short -> value.toString()
            is Int -> value.toString()
            is Long -> value.toString()
            is UByte -> value.toString()
            is UShort -> value.toString()
            is UInt -> value.toString()
            is ULong -> value.toString()
            is Float -> if (value.isFinite()) value.toString() else null
            is Double -> if (value.isFinite()) value.toString() else null
            else -> null
        }

    private val numericFields =
        setOf(
            "chapterId",
            "mangaId",
            "pageIndex",
            "taskId",
            "rowId",
            "lead",
            "tracked",
            "failedPage",
            "count",
            "pages",
            "sourcePages",
            "manifestPages",
            "onDisk",
            "inFlight",
            "toEnqueue",
            "cancelled",
            "bytesExpected",
            "attempt",
            "max",
            "httpStatus",
            "errorCode",
            "queued",
            "running",
            "downloaded",
            "active",
            "activeChapters",
            "freeSlots",
            "concurrency",
            "maxPerHost",
            "current",
            "total",
            "percent",
            "progress",
            "trackedPct",
            "cycle",
            "cycles",
            "polls",
            "delayMs",
            "forMs",
            "inMs",
            "ms",
            "gapMs",
            "decodeMs",
            "totalMs",
            "decodedMiB",
            "bands",
            "srcKiB",
            "outKiB",
            "q",
        )

    private val booleanFields =
        setOf(
            "enabled",
            "flag",
            "pending",
            "value",
            "thermal",
            "lowPower",
            "transferWork",
            "trackedDone",
            "complete",
            "cbzPending",
            "readable",
            "challenge",
            "hookRegistered",
        )

    private val states = setOf("QUEUED", "RUNNING", "DOWNLOADED", "COMPRESSING", "SUCCESS", "FAILED", "ABSENT")
    private val tokenFields =
        mapOf(
            "state" to states,
            "existingState" to states,
            "roomState" to states,
            "from" to states,
            "to" to states,
            "transition" to setOf("->SUCCESS", "->FAILED(cancelled)"),
            "engine" to setOf("BackgroundUrlSession", "CoroutineLegacy"),
            "mode" to setOf("continuedChapterBatch", "chapterBatch"),
            "enc" to setOf("skia", "libwebp"),
            "files" to setOf("encodeOwns", "deleted"),
            "fallback" to setOf("reResolve", "pump"),
            "reason" to
                setOf(
                    "didBecomeActive",
                    "startup",
                    "reconcileInterrupted",
                    "pageCompleteNoManifest",
                    "settleRetry",
                    "workBecamePending",
                    "finalizeInFlight",
                    "rowGone",
                    "lowPowerMode",
                    "noExecutionWindow",
                    "finalizeSuccess",
                    "flagOff",
                ),
        )

    // Current code-owned literals only. New events require an explicit privacy review here.
    // The historical startup literal deliberately includes one space; other input is not echoed.
    private val events =
        setOf(
            "bgwork.cancel",
            "bgwork.cycle",
            "bgwork.done",
            "bgwork.skipped",
            "bgwork.start",
            "bridge.deviceStress",
            "bridge.handleEvents",
            "bridge.handleEvents.flagOff",
            "bridge.hasPendingWork",
            "bridge.hasTransferWork",
            "bridge.setScheduler",
            "bridge.verboseLogging",
            "cancel.all",
            "cancel.delete",
            "cancel.deletePartialPages",
            "cancel.onCancel",
            "cancel.revertReadable",
            "cancel.revertReadable.failed",
            "cancel.running",
            "cancel.running.filesKept",
            "cbz.atomicRename.replaceExisting",
            "cbz.atomicRename.success",
            "cbz.loosePagesDeleted",
            "cbz.partWrite.start",
            "compressionGate.clearedPump",
            "compressionGate.pumpFailed",
            "downloaded.readable",
            "engine.init",
            "engine.selected",
            "enqueue.inserted",
            "enqueue.mutexHeldMs",
            "enqueue.request",
            "enqueue.skip.alreadyActive",
            "file.move.failure",
            "file.move.success",
            "files.deleteFailed",
            "files.partialDeleteFailed",
            "finalize.adoptExistingCbz",
            "finalize.cancelledCleanup",
            "finalize.deferred",
            "finalize.failed",
            "finalize.failed.keepReadable",
            "finalize.ms",
            "finalize.postSuccessError.ignored",
            "finalize.settleRetry.armed",
            "finalize.staleRowError.ignored",
            "finalize.start",
            "finalize.success",
            "lifecycle.didBecomeActive",
            "lifecycle.didEnterBackground",
            "lifecycle.launch startupReconcile",
            "mainStall",
            "manifest.created",
            "manifest.deleted",
            "manifest.missing",
            "manifest.read",
            "manifest.store.attemptIncremented",
            "manifest.store.delete",
            "manifest.store.read.hit",
            "manifest.store.read.miss",
            "manifest.store.read.unreadable",
            "manifest.store.write",
            "manifest.store.write.failed",
            "markReadable.failed",
            "notif.complete.posted",
            "notif.complete.skipped",
            "notif.failed.posted",
            "notif.finalizeDeferred.posted",
            "notif.finalizing.posted",
            "notif.progress.posted",
            "page.complete",
            "page.complete.ignored",
            "page.failed.ignored",
            "prefetch.discarded",
            "prefetch.manifest.written",
            "prefetch.paused",
            "prefetch.promotedToResolve",
            "prefetch.resolve.empty",
            "prefetch.resolve.failed",
            "prefetch.resolve.start",
            "prepare.awaitingPrefetch",
            "prepare.claim.raced",
            "prepare.resolve.alreadyInFlight",
            "prepare.resolve.cloudflare",
            "prepare.resolve.discarded",
            "prepare.resolve.empty",
            "prepare.resolve.failDiscarded",
            "prepare.resolve.failed",
            "prepare.resolve.start",
            "pump.start",
            "reconcile.enqueue",
            "reconcile.plan",
            "reconcile.requested",
            "reconcile.waitInFlight",
            "resolve.ms",
            "retry.attemptIncremented",
            "retry.enqueue",
            "retry.exhausted",
            "retry.scheduled",
            "retry.skip.inFlight",
            "retry.skip.notRunning",
            "retry.skip.onDisk",
            "scheduler.hookSet",
            "scheduler.requestProcessing",
            "scheduler.scheduleProcessing",
            "session.completionHandler.invoked",
            "session.completionHandler.received",
            "session.created",
            "session.didFinishEvents",
            "session.ensureReady",
            "session.getAllTasks",
            "signal.update",
            "startup.pumpFailed",
            "state.transition",
            "task.cancelAll",
            "task.cancelChapter",
            "task.didComplete.cancelled",
            "task.didCompleteWithError",
            "task.didFinishDownloading",
            "task.didWriteData.started",
            "task.enqueue.invalidUrl",
            "task.enqueued",
            "task.httpError",
            "transport.cb.pageComplete",
            "transport.cb.pageFailed",
            "webpEncode",
            "window.fill",
            "window.ms",
        )
}
