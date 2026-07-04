package me.manga.kira.platform.background

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol

/**
 * iOS [BackgroundExecutionGuard].
 *
 * Holds a `UIApplication` background-task assertion **only while the app is actually backgrounded** —
 * begun on `didEnterBackground`, ended on `willEnterForeground`, on the OS expiration handler, or when
 * the chapter finishes (whichever comes first). While the app is in the foreground NO assertion is
 * held, so a long in-foreground download does not trip iOS's "background task held over 30s" watchdog.
 * When backgrounded, the assertion gives the in-flight chapter iOS's bounded grace period (~30s);
 * anything not finished resumes from the Room-persisted queue on next launch.
 *
 * All assertion state is confined to the main thread (notification blocks fire on the posting/main
 * thread; begin/end run on [Dispatchers.Main]) so the [block] download keeps running on its own
 * dispatcher untouched.
 */
class IosBackgroundExecutionGuard : BackgroundExecutionGuard {

    override suspend fun <T> runGuarded(label: String, block: suspend () -> T): T {
        val assertion = BackgroundAssertion(label)
        withContext(Dispatchers.Main) { assertion.startObserving() }
        return try {
            block()
        } finally {
            // Always tear down (even on cancellation): remove observers + release any assertion.
            withContext(NonCancellable + Dispatchers.Main) { assertion.stop() }
        }
    }
}

/** Main-thread-confined background-task assertion tied to app foreground/background transitions. */
private class BackgroundAssertion(private val label: String) {
    private val app = UIApplication.sharedApplication
    private val center = NSNotificationCenter.defaultCenter
    private var taskId = UIBackgroundTaskInvalid
    private var enterBgToken: NSObjectProtocol? = null
    private var enterFgToken: NSObjectProtocol? = null

    fun startObserving() {
        // Null queue → the block runs on the thread that posts the notification, which for these
        // UIApplication lifecycle notifications is the main thread (same thread we register on).
        enterBgToken = center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, null) { _ -> begin() }
        enterFgToken = center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, null) { _ -> end() }
        // Downloads are always user-initiated in the foreground, so no assertion is taken here;
        // the didEnterBackground observer begins one if/when the user leaves the app mid-download.
    }

    private fun begin() {
        if (taskId == UIBackgroundTaskInvalid) {
            taskId = app.beginBackgroundTaskWithName(label) { end() }
        }
    }

    private fun end() {
        val id = taskId
        if (id != UIBackgroundTaskInvalid) {
            taskId = UIBackgroundTaskInvalid
            app.endBackgroundTask(id)
        }
    }

    fun stop() {
        end()
        enterBgToken?.let { center.removeObserver(it) }
        enterFgToken?.let { center.removeObserver(it) }
        enterBgToken = null
        enterFgToken = null
    }
}
