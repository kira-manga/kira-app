package me.manga.kira.domain.model.reader

/**
 * Per-page download / decode progress state.
 *
 * Surfaces the lifecycle of a single Coil image-load to the Reader's `:ui` loading placeholder so
 * the user sees a meaningful indicator instead of a forever-indeterminate spinner. Modeled as a
 * sealed interface rather than an `enum class` because [InProgress] carries a payload (the
 * fraction value).
 *
 * Contract §6 SRP: this type represents ONE rule — "where in the download/decode pipeline is the
 * page right now". Coil interceptor / OkHttp body-wrap mechanics live in `:platform`; the cold
 * Flow that surfaces these values lives in [me.manga.kira.domain.repository.PageProgressRepository];
 * the per-page Map that the VM observes lives in [me.manga.kira.presentation.reader.ReaderState].
 *
 * Lifecycle ordering (intended emission sequence per page URL):
 * ```
 *   Idle ──> Started ──> InProgress(0.0) … InProgress(1.0) ──> Decoding ──> Complete
 *                  │
 *                  └────────────────────────────────────────> Failed
 * ```
 *  - [Idle] is the default — no entry yet exists for this URL in the repository's map. Repository
 *    impls SHOULD surface [Idle] when `observe(url)` is called for a URL that has never been
 *    reported. Pages currently off-screen / not yet attached to a Coil request stay at [Idle].
 *  - [Started] is dispatched the moment Coil begins fetching (the `:platform` listener's
 *    `onStart` hook, or the Coil-level interceptor's pre-fetch step). Useful for distinguishing
 *    "queued but not yet hitting the network" from "actively fetching" — though in practice the
 *    transition Started → InProgress(0.0) is near-instantaneous and the UI may not bother
 *    rendering Started differently from InProgress(null).
 *  - [InProgress] arrives for each meaningful byte-progress tick from the Android OkHttp
 *    interceptor (the only platform that currently emits per-byte progress). The Android impl
 *    SHOULD throttle to avoid emission storms (e.g., only re-emit when the fraction has advanced
 *    by ≥1% or every ≥50ms). iOS/Desktop currently don't surface bytes — for them, this state
 *    may be skipped entirely or used with `fraction = null` to signal "fetching but no precise
 *    measurement".
 *  - [Decoding] is dispatched when bytes-received is complete but the decoder hasn't finished
 *    producing the bitmap yet. Optional emission — `:platform` impls MAY skip directly from
 *    InProgress(1.0) to Complete if the decode hook isn't cheap to observe. The `:ui`
 *    placeholder treats Decoding the same as InProgress(null) — indeterminate spinner — so the
 *    skip is observationally invisible.
 *  - [Complete] is dispatched when Coil's `onSuccess` listener fires. The `:ui` placeholder
 *    won't actually be visible at this point (Coil swaps to the success slot), but the state is
 *    reported anyway for state-machine completeness and for any future progress-aware caller
 *    (preload manager, batch-download UI) that may want to track completion across pages.
 *  - [Failed] is dispatched when Coil's `onError` listener fires. The `:ui` placeholder also
 *    won't be visible (Coil swaps to the error slot which has its own per-page Retry +
 *    Open-in-WebView affordances from §71-§72), but tracking it here makes the state machine
 *    total. A future "X of Y pages failed" header summary could read this without a separate
 *    error-tracking channel.
 *
 * Immutable by rework convention (contract §4). All variants are `data object` except
 * [InProgress] which carries the `fraction` payload.
 *
 * Why a sealed interface rather than an `enum class` with a nullable Float field:
 *  - Models the fraction's presence in the type system. `Idle`, `Started`, `Decoding`,
 *    `Complete`, and `Failed` have no fraction by definition — making fraction `Float?` on an
 *    enum would require every caller to handle the `null` case in every branch, defeating the
 *    `when` exhaustiveness check.
 *  - Sealed-interface variants compose cleanly with `when` statements in `:ui` — see the
 *    Reader's loading-slot dispatch in `ReaderPageItem`.
 *
 * Why not a separate `bytesRead: Long, contentLength: Long` payload on [InProgress]:
 *  - The `:ui` placeholder only needs the 0..1 ratio. Tracking absolute bytes would balloon the
 *    state's per-page memory footprint on long chapters (`Map<String, PageDownloadProgress>` of
 *    100-page chapters * 16 bytes per Long * 2 = 3.2KB extra per chapter — small but pointless).
 *  - Future "47s left" estimated-time UI would need rate-of-change calculations, which the
 *    `:data` impl can compute on-demand from a side-channel without bloating this domain type.
 *
 * Equality: `data object` provides automatic structural equality; `data class InProgress`
 * generates `equals` over the `fraction` field. The repository impl SHOULD `distinctUntilChanged`
 * downstream of the [observe] Flow so the `:ui` only recomposes on real transitions.
 *
 * DIP (contract §6): `:domain`-pure type. No Coil, OkHttp, ktor3, or Android imports.
 * Consumers ([me.manga.kira.domain.repository.PageProgressRepository], the rework Reader VM,
 * and the `:ui` placeholder) all see this type only — they never reach into `:platform` or
 * `:data` for progress mechanics.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster134.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-seventeenth sibling of the cluster57-133
 * sweep — third and closing file of the wave-24 second-cluster `:domain/
 * model/reader/` 3-leaf-model batch alongside Page plus ReadingMode;
 * closes cluster134):
 *  (a) "Per-page-download-decode-progress-state + Surfaces-the-lifecycle-
 *  of-a-single-Coil-image-load-to-the-Reader's-:ui-loading-placeholder-
 *  so-the-user-sees-a-meaningful-indicator-instead-of-a-forever-
 *  indeterminate-spinner + Modeled-as-a-sealed-interface-rather-than-
 *  enum-class-because-InProgress-carries-a-payload-(the-fraction-value)
 *  + Contract-§6-SRP-this-type-represents-ONE-rule-where-in-the-
 *  download-decode-pipeline-is-the-page-right-now + Coil-interceptor-
 *  or-OkHttp-body-wrap-mechanics-live-in-:platform + cold-Flow-that-
 *  surfaces-these-values-lives-in-PageProgressRepository + per-page-
 *  Map-that-the-VM-observes-lives-in-ReaderState" — LIVE-NOT-STALE +
 *  FULFILLED-PREDICTION. Verified via recursive grep: PageProgress-
 *  Repository.observe(url) declared in :domain/repository (Phase 7.x.
 *  reader.modelayout.pageprogress slice); PageProgressRepositoryImpl
 *  in :data; ReaderState.pageProgress field declared as `Map<String,
 *  PageDownloadProgress> = emptyMap()` (verified ReaderState.kt L221);
 *  OkHttpProgressInterceptor.android.kt plus KtorPageProgressObserver.
 *  kt plus PlatformNetworkFetcher.(android+ios+desktop).kt all
 *  reference this type. The state-machine modeling (sealed interface
 *  with InProgress payload) is preserved verbatim across the rework.
 *  (b) "Lifecycle-ordering-Idle-arrow-Started-arrow-InProgress(0.0)-
 *  through-InProgress(1.0)-arrow-Decoding-arrow-Complete-with-Failed-
 *  branch-from-Started + Idle-is-the-default-no-entry-yet-exists-for-
 *  this-URL-in-the-repository's-map + InProgress-arrives-for-each-
 *  meaningful-byte-progress-tick-from-the-Android-OkHttp-interceptor-
 *  (only-platform-that-currently-emits-per-byte-progress) + Android-
 *  impl-SHOULD-throttle-to-avoid-emission-storms-(e.g.-only-re-emit-
 *  when-the-fraction-has-advanced-by-≥1%-or-every-≥50ms) + iOS-Desktop-
 *  currently-don't-surface-bytes-(for-them-this-state-may-be-skipped-
 *  entirely-or-used-with-fraction-null-to-signal-fetching-but-no-
 *  precise-measurement) + Decoding-is-dispatched-when-bytes-received-
 *  is-complete-but-the-decoder-hasn't-finished-producing-the-bitmap-
 *  yet" — LIVE-NOT-STALE + FULFILLED-PREDICTION-(Android-byte-progress)
 *  + FULFILLED-PREDICTION-(iOS-Desktop-ktor3). Verified:
 *  OkHttpProgressInterceptor.android.kt emits per-byte InProgress
 *  (fraction) ticks from the Android OkHttp body interceptor (Phase
 *  7.x.reader.modelayout.pageprogress slice). The iOS plus Desktop
 *  ktor3 path lifts equivalent surface via KtorPageProgressObserver.kt
 *  (Phase 7.x.reader.modelayout.pageprogress.ktor3 slice — Task #237
 *  in the task ledger). The byte-progress emission-storm prediction
 *  stands — the throttling guard at the Android interceptor level
 *  prevents per-byte ticks from saturating the Flow.
 *  (c) "Sealed-interface-rather-than-enum-class-with-nullable-Float-
 *  field + Models-the-fraction's-presence-in-the-type-system + Idle-
 *  Started-Decoding-Complete-Failed-have-no-fraction-by-definition +
 *  making-fraction-Float-nullable-on-an-enum-would-require-every-caller-
 *  to-handle-the-null-case-in-every-branch-defeating-the-when-
 *  exhaustiveness-check + Why-not-a-separate-bytesRead-Long-
 *  contentLength-Long-payload-on-InProgress + :ui-placeholder-only-
 *  needs-the-0..1-ratio + Equality-data-object-provides-automatic-
 *  structural-equality + data-class-InProgress-generates-equals-over-
 *  the-fraction-field + repository-impl-SHOULD-distinctUntilChanged-
 *  downstream + DIP-:domain-pure-type-no-Coil-OkHttp-ktor3-or-Android-
 *  imports + Consumers-(PageProgressRepository-rework-Reader-VM-:ui-
 *  placeholder)-all-see-this-type-only" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. Verified: the sealed interface retains 6 variants (Idle
 *  plus Started plus InProgress plus Decoding plus Complete plus
 *  Failed) with InProgress carrying the Float-nullable fraction
 *  payload. Zero Coil/OkHttp/ktor3/Android imports in the :domain file.
 *  ReaderState.kt L221 declares `pageProgress: Map<String, Page-
 *  DownloadProgress> = emptyMap()` matching the predicted Map<URL,
 *  Progress> shape. The VM filters Idle emissions per the predicted
 *  filter — only meaningful transitions reach the :ui placeholder. The
 *  distinctUntilChanged downstream of observe() preserves recomposition
 *  discipline.
 *  Three classifications STAND on their own merits. Closes cluster134.
 *  Original Phase 7.x.reader.modelayout.pageprogress-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
sealed interface PageDownloadProgress {

    /** No download attempted yet. Default state for any URL the repository has no entry for. */
    data object Idle : PageDownloadProgress

    /** Coil has begun fetching; bytes not yet flowing. Transitional state — often skipped. */
    data object Started : PageDownloadProgress

    /**
     * Bytes are being received. [fraction] is `0.0..1.0` when Content-Length is known and the
     * Android OkHttp interceptor can compute the ratio; `null` when the platform can't surface
     * precise bytes (iOS/Desktop ktor3 today, or any platform when the server omits
     * Content-Length on a chunked response).
     *
     * Repository impls SHOULD `coerceIn(0f, 1f)` non-null values defensively — a malformed
     * Content-Length header on a misconfigured CDN could otherwise produce fractions >1.0.
     */
    data class InProgress(val fraction: Float?) : PageDownloadProgress

    /** Bytes received; decoder is producing the bitmap. Optional emission (impls may skip). */
    data object Decoding : PageDownloadProgress

    /** Coil's `onSuccess` fired. UI placeholder won't be visible past this point. */
    data object Complete : PageDownloadProgress

    /** Coil's `onError` fired. UI's error slot takes over; this state is reported for completeness. */
    data object Failed : PageDownloadProgress
}
