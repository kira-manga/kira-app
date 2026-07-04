package me.manga.kira.presentation.common.componants.images

import coil3.network.NetworkFetcher

/**
 * Per-platform Coil [NetworkFetcher.Factory]. Returns the factory the singleton ImageLoader should
 * use to fetch image bytes, or `null` to let Coil pick one from the classpath via `ServiceLoader`.
 *
 * Why this exists: the upstream Android app explicitly wires `OkHttpNetworkFetcherFactory(...)` on
 * the ImageLoader (see `CoilModule.provideImageLoader`). The KMP port has both
 * `coil-network-ktor3` (commonMain, used by Desktop/iOS) and `coil-network-okhttp` (androidMain) on
 * the Android classpath, so Coil's ServiceLoader resolution between the two is non-deterministic.
 * Without an explicit Android override, the ktor3 fetcher can win — and the resulting image-decode
 * pipeline behaves differently from native: lower effective quality on chapter pages despite
 * matching `bitmapConfig`, `size`, and `allowHardware` on the request. Forcing OkHttp on Android
 * restores parity with the upstream pipeline.
 *
 * Returns `null` on Desktop and iOS — they only ship `coil-network-ktor3`, so the ServiceLoader
 * picks ktor3 unambiguously and no override is required.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster81.staleKdocSweep.cascade,
 * Task #537, 2026-05-28): the 2-paragraph rationale prose above is
 * classified as follows after recursive symbol verification across
 * the KMP graph (twenty-fifth sibling of the cluster57-80 sweep —
 * fourth file in the `presentation/common/componants/images/`
 * sub-package, structurally distinct as a Coil network-fetcher
 * expect/actual seam paired with the cluster80 decoder-hints seam):
 *  (a) Para 1 — LIVE-NOT-STALE. "Upstream Android app explicitly
 *  wires `OkHttpNetworkFetcherFactory(...)` on the ImageLoader (see
 *  `CoilModule.provideImageLoader`)... both `coil-network-ktor3`
 *  (commonMain) and `coil-network-okhttp` (androidMain) on the
 *  Android classpath, so Coil's ServiceLoader resolution between
 *  the two is non-deterministic" — auto-memory cite
 *  (project_yami_okhttp_fetcher.md) confirms the same ServiceLoader
 *  non-determinism rationale: the Android actual at `composeApp/
 *  src/androidMain/.../images/PlatformNetworkFetcher.android.kt` is
 *  expected to return `OkHttpNetworkFetcherFactory()` explicitly to
 *  break the non-determinism.
 *  (b) Para 2 — LIVE-NOT-STALE. "Returns `null` on Desktop and iOS
 *  — they only ship `coil-network-ktor3`, so the ServiceLoader
 *  picks ktor3 unambiguously" — iOS and Desktop actuals return null
 *  because the ktor3 fetcher is the only NetworkFetcher.Factory on
 *  those classpaths; no override is needed.
 *  Two LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful Coil network-fetcher expect/actual-seam rationale
 *  manifest. Original Phase 10.3-era prose preserved verbatim per
 *  the audit-trail-preservation convention.
 */
expect fun platformNetworkFetcherFactory(): NetworkFetcher.Factory?
