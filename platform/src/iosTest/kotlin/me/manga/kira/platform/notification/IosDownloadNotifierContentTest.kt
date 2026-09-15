package me.manga.kira.platform.notification

import kotlinx.coroutines.test.runTest
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Inspects real request content without submitting to Notification Center or requesting permission. */
class IosDownloadNotifierContentTest {
    @Test
    fun progressIsPassiveAndSilent() = runTest {
        val request = captureRequest { it.onProgress(KEY, TITLE, current = 3, total = 12) }

        assertPassive(request, "3/12 pages")
    }

    @Test
    fun finalizingIsPassiveAndSilent() = runTest {
        val request = captureRequest { it.onFinalizing(KEY, TITLE) }

        assertPassive(request, "Finalizing chapter…")
    }

    @Test
    fun deferredFinalizationIsPassiveAndSilent() = runTest {
        val request = captureRequest { it.onFinalizeDeferred(KEY, TITLE) }

        assertPassive(request, "Chapter ready — compression paused (Low Power Mode)")
    }

    @Test
    fun completionRemainsActiveWithSound() = runTest {
        val request = captureRequest { it.onComplete(KEY, TITLE) }

        assertAlerting(request, "Download complete")
    }

    @Test
    fun failureRemainsActiveWithSound() = runTest {
        val request = captureRequest { it.onFailed(KEY, TITLE) }

        assertAlerting(request, "Download failed")
    }

    @Test
    fun replacementKeepsTheChapterIdentifierWithoutMutatingEarlierRequests() = runTest {
        val requests = mutableListOf<UNNotificationRequest>()
        val notifier = IosDownloadNotifier { requests += it }

        notifier.onProgress(KEY, TITLE, current = 3, total = 12)
        notifier.onFinalizing(KEY, TITLE)
        notifier.onFinalizeDeferred(KEY, TITLE)
        notifier.onComplete(KEY, TITLE)
        notifier.onFailed(KEY, TITLE)

        assertEquals(5, requests.size)
        assertPassive(requests[0], "3/12 pages")
        assertPassive(requests[1], "Finalizing chapter…")
        assertPassive(requests[2], "Chapter ready — compression paused (Low Power Mode)")
        assertAlerting(requests[3], "Download complete")
        assertAlerting(requests[4], "Download failed")
    }

    private suspend fun captureRequest(send: suspend (IosDownloadNotifier) -> Unit): UNNotificationRequest {
        val requests = mutableListOf<UNNotificationRequest>()
        send(IosDownloadNotifier { requests += it })
        return requests.single()
    }

    private fun assertPassive(request: UNNotificationRequest, expectedBody: String) {
        assertContent(request, expectedBody, category = "DOWNLOAD_PROGRESS")
        assertEquals(UNNotificationInterruptionLevelPassive, request.content.interruptionLevel)
        assertNull(request.content.sound)
    }

    private fun assertAlerting(request: UNNotificationRequest, expectedBody: String) {
        assertContent(request, expectedBody, category = "DOWNLOAD_DONE")
        assertEquals(UNNotificationInterruptionLevelActive, request.content.interruptionLevel)
        assertNotNull(request.content.sound)
    }

    private fun assertContent(request: UNNotificationRequest, expectedBody: String, category: String) {
        assertEquals("dl-$KEY", request.identifier)
        assertNull(request.trigger)
        assertEquals(TITLE, request.content.title)
        assertEquals(expectedBody, request.content.body)
        assertEquals(category, request.content.categoryIdentifier)
    }

    private companion object {
        const val KEY = 42
        const val TITLE = "Example chapter"
    }
}
