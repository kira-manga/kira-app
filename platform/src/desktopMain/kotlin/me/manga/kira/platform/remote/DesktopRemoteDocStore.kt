package me.manga.kira.platform.remote

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Desktop actual for [RemoteDocStore] — no-op.
 *
 * Firebase has no first-party JVM SDK. The Firestore REST surface (`firestore.googleapis.com`)
 * could be wired through Ktor in a future Phase 13 for desktop runs that genuinely need
 * server-backed docs; until then every read returns null/empty and writes are dropped (logged
 * via Kermit at debug level so the call is still visible during development).
 *
 * Verbatim port from legacy `:shared/desktopMain/.../core/remote/RemoteDocStore.desktop.kt`.
 */
class DesktopRemoteDocStore : RemoteDocStore {

    private val log = Logger.withTag(TAG)

    override suspend fun getDoc(path: String): Map<String, Any?>? {
        // TODO(Phase 13 — Desktop Firestore via REST API)
        log.d { "getDoc($path) — no-op on Desktop, returning null" }
        return null
    }

    override suspend fun setDoc(path: String, data: Map<String, Any?>) {
        // TODO(Phase 13 — Desktop Firestore via REST API)
        log.d { "setDoc($path) — no-op on Desktop, data dropped" }
    }

    override suspend fun deleteDoc(path: String) {
        // TODO(Phase 13 — Desktop Firestore via REST API)
        log.d { "deleteDoc($path) — no-op on Desktop" }
    }

    override fun observeDoc(path: String): Flow<Map<String, Any?>?> {
        // TODO(Phase 13 — Desktop Firestore via REST API)
        log.d { "observeDoc($path) — no-op on Desktop, emitting single null" }
        return flowOf(null)
    }

    override suspend fun query(
        collectionPath: String,
        where: List<RemoteQuery>,
        limit: Int?,
    ): List<Map<String, Any?>> {
        // TODO(Phase 13 — Desktop Firestore via REST API)
        log.d { "query($collectionPath, where=$where, limit=$limit) — no-op on Desktop, returning emptyList" }
        return emptyList()
    }

    private companion object {
        const val TAG = "RemoteDocStore.desktop"
    }
}

/*
 * §253 audit-trail postscript — cluster274 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE)
 *
 * Unit kind: platform-facade — Desktop (JVM) concrete impl of the commonMain
 * interface RemoteDocStore (Phase 5.z.7 relocation, Task #194). One of a
 * 3-platform fan; the Android sibling is the Firestore-backed actual and the
 * iOS sibling is a parallel no-op stub, both swept in this same cluster274
 * batch. Desktop is a NO-OP stub: Firebase has no first-party JVM SDK.
 *
 * LIVE evidence: the interface RemoteDocStore is bound per-platform via the
 * legacy expect-class no-arg form single brace RemoteDocStore parens brace —
 * confirmed for the JVM target at
 * shared/src/desktopMain/.../di/PlatformModule.desktop.kt:103. The :platform
 * module declares NO Koin module of its own (Glob platform slash slash di
 * found zero files) and no rework consumer references DesktopRemoteDocStore
 * by name (grep across composeApp returned no matches). This matches the
 * cluster144-149 strangler-fig posture: the relocated actual is wired-ready
 * but the live binding remains the legacy :shared expect-class. The
 * commonMain expect-decl analog (interface RemoteDocStore plus RemoteQuery
 * plus RemoteOp) was swept at cluster149 (Task #605), file
 * platform/src/commonMain/.../remote/RemoteDocStore.kt.
 *
 * Delta-axes (this Desktop actual vs the Android/iOS siblings):
 *  1. Platform API: NONE. There is no JVM Firebase SDK, so no vendor call is
 *     made. The Phase 13 forecast is a Firestore REST surface
 *     (firestore.googleapis.com) wired through Ktor — currently UNREALIZED;
 *     the TODO markers in each method preserve that deferral verbatim.
 *  2. Threading/dispatcher: trivial. Suspend methods return immediately;
 *     observeDoc returns flowOf(null) (a single-emission cold flow). No
 *     coroutine bridging or callback registration is needed because nothing
 *     is awaited.
 *  3. Error handling: none required — every method is a safe-default no-op
 *     that cannot fail. Each call is still logged at Kermit DEBUG level so it
 *     stays visible during development without polluting release logs.
 *  4. DI binding mechanism: zero-arg constructor, registered as a Koin single
 *     (see PlatformModule.desktop.kt:103).
 *  5. Behavioural-contract parity across the 3-actual fan: Desktop honors all
 *     five interface signatures and never throws (LSP-safe), returning
 *     null/emptyList/no-op-write/flowOf(null). It is contract-faithful but
 *     functionally inert — distinct from Android (FULFILLS with real
 *     Firestore) and identical in shape to the iOS no-op stub, differing only
 *     in the deferral phase (Desktop Phase 13 REST vs iOS Phase 12 cinterop).
 *
 * Nested-comment hazard check: this file has exactly 1 legitimate KDoc
 * opener (the class header on line 7). The block appended here adds exactly
 * one opener and one closer, balanced, with zero interior comment delimiters
 * — the literal slash-star, star-slash, and slash-star-star sequences are
 * spelled out as words above and never typed.
 */
