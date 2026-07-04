package me.manga.kira.platform.remote

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * iOS actual for [RemoteDocStore] — no-op.
 *
 * Firebase Firestore iOS SDK is not wired in Phase 8; real Firestore is scheduled for Phase 12
 * alongside the rest of the Firebase iOS integration (CocoaPods / cinterop story not finalized).
 * Until then, every read returns null/empty and writes are dropped (logged via Kermit at debug
 * level so the call is still visible during development).
 *
 * Verbatim port from legacy `:shared/iosMain/.../core/remote/RemoteDocStore.ios.kt`.
 */
class IosRemoteDocStore : RemoteDocStore {

    private val log = Logger.withTag(TAG)

    override suspend fun getDoc(path: String): Map<String, Any?>? {
        // TODO(Phase 12 — iOS Firestore via firebase-ios-sdk cinterop)
        log.d { "getDoc($path) — no-op on iOS, returning null" }
        return null
    }

    override suspend fun setDoc(path: String, data: Map<String, Any?>) {
        // TODO(Phase 12 — iOS Firestore via firebase-ios-sdk cinterop)
        log.d { "setDoc($path) — no-op on iOS, data dropped" }
    }

    override suspend fun deleteDoc(path: String) {
        // TODO(Phase 12 — iOS Firestore via firebase-ios-sdk cinterop)
        log.d { "deleteDoc($path) — no-op on iOS" }
    }

    override fun observeDoc(path: String): Flow<Map<String, Any?>?> {
        // TODO(Phase 12 — iOS Firestore via firebase-ios-sdk cinterop)
        log.d { "observeDoc($path) — no-op on iOS, emitting single null" }
        return flowOf(null)
    }

    override suspend fun query(
        collectionPath: String,
        where: List<RemoteQuery>,
        limit: Int?,
    ): List<Map<String, Any?>> {
        // TODO(Phase 12 — iOS Firestore via firebase-ios-sdk cinterop)
        log.d { "query($collectionPath, where=$where, limit=$limit) — no-op on iOS, returning emptyList" }
        return emptyList()
    }

    private companion object {
        const val TAG = "RemoteDocStore.ios"
    }
}

/*
 * §253 audit-trail postscript — cluster274 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE)
 *
 * Unit kind: platform-facade — iOS concrete impl of the commonMain interface
 * RemoteDocStore (Phase 5.z.7 relocation, Task #194). One of a 3-platform
 * fan; the Android sibling is the Firestore-backed actual and the Desktop
 * sibling is a parallel no-op stub, both swept in this same cluster274 batch.
 * iOS is a NO-OP stub: the firebase-ios-sdk cinterop is not wired in Phase 8.
 *
 * LIVE evidence: the interface RemoteDocStore is bound per-platform via the
 * legacy expect-class no-arg form single brace RemoteDocStore parens brace —
 * confirmed for the iOS target at
 * shared/src/iosMain/.../di/PlatformModule.ios.kt:103. The :platform module
 * ships NO Koin module of its own (Glob platform slash slash di found zero
 * files) and no rework consumer references IosRemoteDocStore by name (grep
 * across composeApp returned no matches). This is the cluster144-149
 * strangler-fig posture: the relocated actual is wired-ready but the live
 * binding remains the legacy :shared expect-class until a rework Koin module
 * rebinds it. The commonMain expect-decl analog (interface RemoteDocStore
 * plus RemoteQuery plus RemoteOp) was swept at cluster149 (Task #605), file
 * platform/src/commonMain/.../remote/RemoteDocStore.kt.
 *
 * Delta-axes (this iOS actual vs the Android/Desktop siblings):
 *  1. Platform API: NONE yet. The firebase-ios-sdk Firestore surface is
 *     scheduled for Phase 12 (CocoaPods plus cinterop story not finalized);
 *     until then no vendor call is made. The TODO markers on every method
 *     preserve that deferral verbatim.
 *  2. Threading/dispatcher: trivial. Suspend methods return immediately;
 *     observeDoc returns flowOf(null) (a single-emission cold flow). No
 *     coroutine bridge to Firebase Tasks (as Android uses) and no callback
 *     registration are needed because nothing is awaited.
 *  3. Error handling: none required — every method is a safe-default no-op
 *     that cannot fail. Each call is logged at Kermit DEBUG level so it stays
 *     visible during development on the iOS device/simulator console.
 *  4. DI binding mechanism: zero-arg constructor, registered as a Koin single
 *     (see PlatformModule.ios.kt:103).
 *  5. Behavioural-contract parity across the 3-actual fan: iOS honors all
 *     five interface signatures and never throws (LSP-safe), returning
 *     null/emptyList/no-op-write/flowOf(null). Contract-faithful but
 *     functionally inert — identical in shape to the Desktop no-op stub and
 *     distinct from Android (which FULFILLS with real Firestore). The only
 *     difference from Desktop is the deferral phase (iOS Phase 12 cinterop vs
 *     Desktop Phase 13 REST).
 *
 * Nested-comment hazard check: this file has exactly 1 legitimate KDoc
 * opener (the class header on line 7). The block appended here adds exactly
 * one opener and one closer, balanced, with zero interior comment delimiters
 * — the literal slash-star, star-slash, and slash-star-star sequences are
 * spelled out as words above and never typed.
 */
