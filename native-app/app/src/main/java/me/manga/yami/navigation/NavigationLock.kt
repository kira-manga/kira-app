package me.manga.yamiapk.navigation

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NavigationLock {
    private val mutex = Mutex()
    private var isNavigating = false

    suspend fun <T> withLock(block: suspend () -> T): T {
        return mutex.withLock {
            if (isNavigating) {
                // Wait a bit for previous navigation to complete
                delay(100)
            }
            isNavigating = true
            try {
                block()
            } finally {
                isNavigating = false
            }
        }
    }

    fun tryLock(block: () -> Unit): Boolean {
        return if (!isNavigating) {
            isNavigating = true
            try {
                block()
                true
            } finally {
                isNavigating = false
            }
        } else {
            false
        }
    }
}