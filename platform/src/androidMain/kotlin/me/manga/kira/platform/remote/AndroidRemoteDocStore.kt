package me.manga.kira.platform.remote

import co.touchlab.kermit.Logger
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Android actual for [RemoteDocStore] — delegates to the singleton
 * `FirebaseFirestore.getInstance()`.
 *
 * Path resolution mirrors Firestore: slash-separated, odd segments = document ids, even segments
 * = collection ids. A document path therefore has an even count of segments (collection, doc,
 * collection, doc, ...). `Firestore.document(path)` itself validates this and throws
 * `IllegalArgumentException` for a malformed path — the actual lets that propagate so misuse is
 * loud during development. Network / permission failures inside the async Tasks are caught and
 * turned into safe defaults (null / empty list / no-op write) after a Kermit warn-level log,
 * because the upstream call sites are read-mostly and must not crash on transient errors.
 *
 * Verbatim semantic port from legacy
 * `:shared/androidMain/.../core/remote/RemoteDocStore.android.kt`. Preserves:
 *  - Zero-arg `FirebaseFirestore.getInstance()` (no Context needed; the SDK reads its config
 *    from the embedded `google-services.json` at process start).
 *  - `try { … } catch (e: Exception) { log.w(e) … }` swallow-and-default semantics on every
 *    read/write so a single Firestore hiccup doesn't propagate into the UI.
 *  - `clause.value!!` non-null assertion on the comparison branches that require it
 *    (`whereGreaterThan` etc. have non-null Java signatures); a `null` value here is a caller
 *    bug and is intentionally allowed to throw, matching legacy behavior.
 *  - `whereIn(field, (value as? List<*>) ?: emptyList<Any>())` empty-list fallback for the `IN`
 *    op when the caller passes a non-List value — preserves the legacy "best-effort" semantics.
 *  - `callbackFlow` + `awaitClose { registration.remove() }` pattern for `observeDoc`, including
 *    the inline emit-null-on-error branch.
 *  - `snapshot.documents.mapNotNull { it.data }` for query result extraction — drops docs whose
 *    data is null (Firestore returns null for docs deleted between snapshot and read).
 */
class AndroidRemoteDocStore : RemoteDocStore {

    private val log = Logger.withTag(TAG)
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    private fun resolveDoc(path: String): DocumentReference {
        // Firestore.document(path) validates that the path resolves to a document (even segments)
        // and throws IllegalArgumentException otherwise — we let that propagate so misuse is loud.
        return firestore.document(path)
    }

    override suspend fun getDoc(path: String): Map<String, Any?>? {
        return try {
            val snapshot = resolveDoc(path).get().await()
            if (snapshot.exists()) snapshot.data else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "getDoc($path) failed" }
            null
        }
    }

    override suspend fun setDoc(path: String, data: Map<String, Any?>) {
        try {
            resolveDoc(path).set(data).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "setDoc($path) failed" }
        }
    }

    override suspend fun deleteDoc(path: String) {
        try {
            resolveDoc(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "deleteDoc($path) failed" }
        }
    }

    override fun observeDoc(path: String): Flow<Map<String, Any?>?> = callbackFlow {
        val ref = resolveDoc(path)
        val registration = ref.addSnapshotListener { snapshot, error ->
            if (error != null) {
                log.w(error) { "observeDoc($path) listener error" }
                trySend(null)
                return@addSnapshotListener
            }
            val data = if (snapshot != null && snapshot.exists()) snapshot.data else null
            trySend(data)
        }
        awaitClose { registration.remove() }
    }

    override suspend fun query(
        collectionPath: String,
        where: List<RemoteQuery>,
        limit: Int?,
    ): List<Map<String, Any?>> {
        return try {
            var q: Query = firestore.collection(collectionPath)
            for (clause in where) {
                q = when (clause.op) {
                    RemoteOp.EQUAL -> q.whereEqualTo(clause.field, clause.value)
                    RemoteOp.NOT_EQUAL -> q.whereNotEqualTo(clause.field, clause.value)
                    RemoteOp.GREATER_THAN -> q.whereGreaterThan(clause.field, clause.value!!)
                    RemoteOp.LESS_THAN -> q.whereLessThan(clause.field, clause.value!!)
                    RemoteOp.GREATER_OR_EQUAL -> q.whereGreaterThanOrEqualTo(clause.field, clause.value!!)
                    RemoteOp.LESS_OR_EQUAL -> q.whereLessThanOrEqualTo(clause.field, clause.value!!)
                    RemoteOp.IN -> q.whereIn(clause.field, (clause.value as? List<*>) ?: emptyList<Any>())
                    RemoteOp.ARRAY_CONTAINS -> q.whereArrayContains(clause.field, clause.value!!)
                }
            }
            if (limit != null) q = q.limit(limit.toLong())
            val snapshot = q.get().await()
            snapshot.documents.mapNotNull { it.data }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "query($collectionPath, where=$where, limit=$limit) failed" }
            emptyList()
        }
    }

    private companion object {
        const val TAG = "RemoteDocStore.android"
    }
}

/*
 * §253 audit-trail postscript — cluster274 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (LIVE)
 *
 * Unit kind: platform-facade — Android concrete impl of the commonMain
 * interface RemoteDocStore (Phase 5.z.7 relocation, Task #194). This is the
 * Firestore-backed actual of a 3-platform fan; the iOS and Desktop siblings
 * are no-op stubs swept in this same cluster274 batch.
 *
 * LIVE evidence: the interface RemoteDocStore is bound per-platform via the
 * legacy expect-class no-arg form single brace RemoteDocStore parens brace —
 * confirmed at shared/src/androidMain/.../di/PlatformModule.android.kt:117
 * (the Phase 8.8 Firebase-facades block). The :platform module ships NO Koin
 * module of its own (Glob platform slash slash di found zero files), and no
 * rework consumer references AndroidRemoteDocStore by name (grep across
 * composeApp returned no matches). This is the established strangler-fig
 * posture for the cluster144-149 :platform relocations: the relocated actual
 * compiles and is wired-ready, but the live runtime binding remains the
 * legacy :shared expect-class until a rework Koin module rebinds it. The
 * commonMain expect-decl analog (interface RemoteDocStore plus RemoteQuery
 * plus RemoteOp) was swept at cluster149 (Task #605), file
 * platform/src/commonMain/.../remote/RemoteDocStore.kt.
 *
 * Delta-axes (this Android actual vs the iOS/Desktop no-op siblings):
 *  1. Platform API: delegates to FirebaseFirestore.getInstance() — the
 *     zero-arg singleton reading its config from the embedded
 *     google-services.json at process start; no Context, no Activity.
 *  2. Threading/dispatcher: bridges Firebase Tasks to coroutines via
 *     kotlinx-coroutines-play-services await() on the suspend reads/writes,
 *     and callbackFlow plus awaitClose brace registration.remove brace for
 *     the observeDoc snapshot listener.
 *  3. Error handling: every read/write is wrapped in try-catch returning a
 *     safe default (null, emptyList, no-op write) after a Kermit warn-level
 *     log — upstream callers are read-mostly and must not crash on a network
 *     blip. The clause.value double-bang on the five comparison overloads is
 *     deliberately preserved: a null there is a caller bug and surfaces
 *     loudly, matching legacy semantics. A malformed path likewise lets
 *     IllegalArgumentException from resolveDoc propagate.
 *  4. DI binding mechanism: zero-arg constructor, registered as a Koin
 *     single (see PlatformModule.android.kt:117).
 *  5. Behavioural-contract parity across the 3-actual fan: Android FULFILLS
 *     the contract (real Firestore reads/writes/queries/observe); iOS returns
 *     no-op stubs pending Phase 12 firebase-ios-sdk cinterop; Desktop returns
 *     no-op stubs pending a Phase 13 Ktor REST surface. All three honor the
 *     interface signatures and never break LSP — the stubs simply return
 *     null/empty/no-op rather than throwing.
 *
 * Nested-comment hazard check: this file has exactly 1 legitimate KDoc
 * opener (the class header on line 12). The block appended here adds exactly
 * one opener and one closer, balanced, with zero interior comment delimiters
 * — the literal slash-star, star-slash, and slash-star-star sequences are
 * spelled out as words above and never typed.
 */
