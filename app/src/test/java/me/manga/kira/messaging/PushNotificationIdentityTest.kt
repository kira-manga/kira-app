package me.manga.kira.messaging

import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import com.google.firebase.messaging.RemoteMessage
import me.manga.kira.MainActivity
import me.manga.kira.firebase_cores.messaging.MyFirebaseMessagingService
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.push.NotificationRouter
import me.manga.kira.navigation.push.PushDestination
import me.manga.kira.navigation.push.toScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real service/token/handler coverage, without booting MyApp or the Compose navigation graph. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PushNotificationIdentityTest {
    @Test
    fun differentSlotsRetainTheirPayloadAndDoNotAliasOtherActivityTokens() =
        withService { service, manager ->
            val details = detailsPayload("older")
            val reader = readerPayload()
            service.onMessageReceived(message("A", details))
            val older = contentIntent(manager, -65)
            service.onMessageReceived(message("B", reader))
            val newer = contentIntent(manager, -66)

            assertEquals(2, manager.activeNotifications.size)
            assertNotEquals(older, newer)
            assertPushDelivery(older, -65, details)
            assertPushDelivery(newer, -66, reader)

            val application = RuntimeEnvironment.getApplication()
            val actionless =
                PendingIntent.getActivity(
                    application,
                    0,
                    Intent(application, MainActivity::class.java).apply {
                        addFlags(ACTIVITY_FLAGS)
                        putExtra("screen", "home")
                    },
                    PENDING_INTENT_FLAGS,
                )
            val zeroPayload = detailsPayload("zero")
            service.onMessageReceived(message("", zeroPayload))
            val zero = contentIntent(manager, 0)

            assertEquals(3, manager.activeNotifications.size)
            assertNotEquals(actionless, zero)
            assertEquals(mapOf("screen" to "home"), payload(sendAndDequeue(actionless)))
            assertPushDelivery(zero, 0, zeroPayload)
        }

    @Test
    fun repeatedMessageIdReplacesOnlyItsOwnCardAndTokenPayload() =
        withService { service, manager ->
            service.onMessageReceived(message("A", detailsPayload("original")))
            val original = contentIntent(manager, -65)
            val otherPayload = readerPayload()
            service.onMessageReceived(message("B", otherPayload))
            val other = contentIntent(manager, -66)
            val replacement = detailsPayload("replacement")

            // Same keys deliberately: Robolectric 4.16.1's UPDATE_CURRENT shadow merges extras rather
            // than replacing the Bundle as AOSP does. Removed-old-key behavior is not this JVM oracle.
            service.onMessageReceived(message("A", replacement))

            assertEquals(2, manager.activeNotifications.size)
            assertEquals(original, contentIntent(manager, -65))
            assertPushDelivery(original, -65, replacement)
            assertPushDelivery(other, -66, otherPayload)
        }

    @Test
    fun missingMessageIdsShareOneFallbackWithoutChangingNamedPushes() =
        withService { service, manager ->
            service.onMessageReceived(message(null, detailsPayload("anonymous-first")))
            val original = contentIntent(manager, -1000)
            val namedPayload = readerPayload()
            service.onMessageReceived(message("B", namedPayload))
            val named = contentIntent(manager, -66)
            val replacement = detailsPayload("anonymous-latest")
            service.onMessageReceived(message(null, replacement))

            assertEquals(2, manager.activeNotifications.size)
            assertEquals(original, contentIntent(manager, -1000))
            assertNotEquals(original, named)
            assertPushDelivery(original, -1000, replacement)
            assertPushDelivery(named, -66, namedPayload)
        }

    @Test
    fun collidingMessageIdsKeepTheExistingCardAndTokenReplacementRule() =
        withService { service, manager ->
            // Aa/BB both hash to 2112; the single-character id U+03E8 hashes to the fallback's 1000.
            val collisions = listOf(Triple("Aa", "BB", -2112), Triple(null, "\u03e8", -1000))
            for ((firstId, nextId, slot) in collisions) {
                service.onMessageReceived(message(firstId, detailsPayload("original")))
                val original = contentIntent(manager, slot)
                val count = manager.activeNotifications.size
                val replacement = detailsPayload("replacement")
                service.onMessageReceived(message(nextId, replacement))

                assertEquals(count, manager.activeNotifications.size)
                assertEquals(original, contentIntent(manager, slot))
                assertPushDelivery(original, slot, replacement)
            }
        }

    @Test
    fun olderTapReachesItsOwnDestinationAndHandlerConsumptionDoesNotReplay() =
        withService { service, manager ->
            withRouter { router ->
                // Attach only: onCreate/setup would boot Compose and unrelated app dependencies.
                val activity = Robolectric.buildActivity(MainActivity::class.java).get()
                val handler =
                    MainActivity::class.java.getDeclaredMethod("handlePushIntent", Intent::class.java).apply {
                        isAccessible = true
                    }
                val details = detailsPayload("older")
                service.onMessageReceived(message("A", details))
                val older = contentIntent(manager, -65)
                service.onMessageReceived(message("B", readerPayload()))
                val newer = contentIntent(manager, -66)

                val olderDelivery = sendAndDequeue(older)
                handler.invoke(activity, olderDelivery)
                val detailDestination = requireNotNull(router.pending.value).destination
                assertEquals(PushDestination.MangaDetail("Azora", details.getValue("url")), detailDestination)
                assertEquals(
                    Screen.MangaDetails(mangaUrl = details.getValue("url"), api = "Azora"),
                    detailDestination.toScreen(),
                )
                assertNull(olderDelivery.extras)
                router.consume()
                handler.invoke(activity, olderDelivery)
                assertNull(router.pending.value)

                val newerDelivery = sendAndDequeue(newer)
                handler.invoke(activity, newerDelivery)
                val readerDestination = requireNotNull(router.pending.value).destination
                assertEquals(
                    PushDestination.Reader(
                        api = "Azora",
                        language = "ar",
                        mangaUrl = "https://azoramoon.com/series/newer",
                        chapterUrl = "https://azoramoon.com/series/newer/chapter-7",
                        chapterNumber = "7",
                        title = "Newer manga",
                        coverUrl = "https://azoramoon.com/covers/newer.jpg",
                        chapterName = "Seventh chapter",
                    ),
                    readerDestination,
                )
                assertEquals(
                    Screen.ChapterImagesRework(
                        api = "Azora",
                        language = "ar",
                        title = "Newer manga",
                        mangaUrl = "https://azoramoon.com/series/newer",
                        coverUrl = "https://azoramoon.com/covers/newer.jpg",
                        chapterNumber = "7",
                        chapterName = "Seventh chapter",
                        chapterUrl = "https://azoramoon.com/series/newer/chapter-7",
                    ),
                    readerDestination.toScreen(),
                )
                assertNull(newerDelivery.extras)
            }
        }

    private fun withService(block: (MyFirebaseMessagingService, NotificationManager) -> Unit) {
        val controller = Robolectric.buildService(MyFirebaseMessagingService::class.java).create()
        try {
            val service = controller.get()
            val manager = requireNotNull(service.getSystemService(NotificationManager::class.java))
            block(service, manager)
        } finally {
            controller.destroy()
        }
    }

    private fun withRouter(block: (NotificationRouter) -> Unit) {
        check(GlobalContext.getOrNull() == null) { "Refusing to replace an existing Koin graph" }
        val router = NotificationRouter()
        val application = GlobalContext.startKoin { modules(module { single { router } }) }
        try {
            block(router)
        } finally {
            check(GlobalContext.getOrNull() === application.koin) { "The test no longer owns the Koin graph" }
            GlobalContext.stopKoin()
        }
    }

    private fun message(
        id: String?,
        data: Map<String, String>,
    ): RemoteMessage =
        RemoteMessage
            .Builder("app4-test-recipient")
            .apply {
                setData(data)
                if (id != null) setMessageId(id)
            }.build()
            .also {
                // In particular, an absent id must really be null rather than an invented empty id.
                assertEquals(id, it.messageId)
            }

    private fun contentIntent(
        manager: NotificationManager,
        slot: Int,
    ): PendingIntent =
        requireNotNull(
            manager.activeNotifications
                .single { it.id == slot }
                .notification.contentIntent,
        )

    private fun sendAndDequeue(token: PendingIntent): Intent {
        token.send()
        val delivered = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        // Copy only AFTER the actual send/dequeue. This shadow aliases the saved immutable Intent;
        // Android sends a copy. Handler consumption must not mutate the token through that alias.
        return Intent(delivered)
    }

    private fun assertPushDelivery(
        token: PendingIntent,
        slot: Int,
        expected: Map<String, String>,
    ) {
        assertEquals(slot, shadowOf(token).requestCode)
        assertEquals(PENDING_INTENT_FLAGS, shadowOf(token).flags)
        assertTrue(token.isImmutable)
        val delivered = sendAndDequeue(token)
        assertEquals(ComponentName(RuntimeEnvironment.getApplication(), MainActivity::class.java), delivered.component)
        assertEquals("me.manga.kira.action.OPEN_PUSH_NOTIFICATION", delivered.action)
        assertNull(delivered.data)
        assertEquals(ACTIVITY_FLAGS, delivered.flags and ACTIVITY_FLAGS)
        assertEquals(expected, payload(delivered))
    }

    private fun payload(intent: Intent): Map<String, String> {
        val extras = requireNotNull(intent.extras)
        return extras.keySet().associateWith { requireNotNull(extras.getString(it)) }
    }

    private fun detailsPayload(name: String): Map<String, String> =
        mapOf(
            "screen" to "manga",
            "api" to "Azora",
            "url" to "https://azoramoon.com/series/$name",
        )

    private fun readerPayload(): Map<String, String> =
        mapOf(
            "screen" to "reader",
            "api" to "Azora",
            "language" to "ar",
            "url" to "https://azoramoon.com/series/newer",
            "chapterUrl" to "https://azoramoon.com/series/newer/chapter-7",
            "chapterNumber" to "7",
            "title" to "Newer manga",
            "coverUrl" to "https://azoramoon.com/covers/newer.jpg",
            "chapterName" to "Seventh chapter",
        )

    private companion object {
        const val ACTIVITY_FLAGS = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        const val PENDING_INTENT_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
