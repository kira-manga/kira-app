package me.manga.kira.data.repository

import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.model.whatsnew.MediaType
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.repository.WhatsNewRepository
import me.manga.kira.platform.storage.DataStoreHelper as LegacyDataStoreHelper
import me.manga.kira.platform.version.AppVersionProvider as LegacyAppVersionProvider
import me.manga.kira.core.storage.SharedPrefsHelper as LegacySharedPrefsHelper
import me.manga.kira.presentation.features.whatsnew.data.WhatsNewRemoteDataSource as LegacyWhatsNewRemoteDataSource
import me.manga.kira.presentation.features.whatsnew.data.getDefaultFeatures as legacyGetDefaultFeatures
import me.manga.kira.presentation.features.whatsnew.model.WhatsNewFeature as LegacyWhatsNewFeature

/**
 * [WhatsNewRepository] strangler-fig delegate over FOUR `:shared` legacy facades.
 *
 * Phase 7.x.whatsnew (foundation). Highest fan-out into `:shared` of any rework `:data` impl —
 * justified by the legacy `WhatsNewViewModel` owning the SAME collaboration, which this slice
 * ports verbatim (minus the should-show gating + language localization, both deferred). The
 * four legacy collaborators are:
 *
 * 1. [LegacyWhatsNewRemoteDataSource] (`:shared/presentation/features/whatsnew/data/`) — Ktor
 *    JSON fetcher. Returns `Result<WhatsNewResponse>` with structured failure handling already
 *    baked in. The impl unpacks the `Result` and falls back to the default list on failure or
 *    empty response. Koin-bound `single` in `SharedModule.kt`.
 *
 * 2. [LegacySharedPrefsHelper] (`:shared/core/storage/`) — multiplatform-settings-backed prefs
 *    helper. The impl writes the two mark-seen keys (`"whats_new_last_shown_version_name"` +
 *    `"whats_new_last_shown_timestamp"`) — SAME key names as the legacy `WhatsNewViewModel`,
 *    so marking-seen on either route round-trips with the other. Koin-bound `single` in
 *    `SharedModule.kt`.
 *
 * 3. [LegacyAppVersionProvider] (`:shared/core/platform/`) — expect class supplying
 *    `versionName`. The impl reads it at `markSeen()` time (not at construction) so a hot-
 *    reloaded version is honoured. Same posture as `AboutRepositoryImpl`. Koin-bound `single`
 *    in `PlatformModule.{android,ios,desktop}.kt`.
 *
 * 4. [legacyGetDefaultFeatures] (top-level fun in `:shared/.../whatsnew/data/`) — fallback list
 *    when the remote fetch fails or the response is empty. Currently returns `emptyList()` (the
 *    upstream port commented out the actual defaults during the KMP migration). Called directly
 *    as a static function — no DI binding needed.
 *
 * **SRP (contract §6)**: owns ONE rule — "project the legacy WhatsNew collaboration into the
 * rework [WhatsNewRepository] contract". No should-show gating (deferred to
 * `Phase 7.x.whatsnew.gate`); language localization resolves the user's stored language via
 * [LegacyDataStoreHelper] (legacy `WhatsNewViewModel.getUserLanguageCode` parity), no UI
 * projection, no analytics. The `:data` impl is a pure adapter.
 *
 * **DIP (contract §6)**: depends on the legacy `:shared` types because that's where the cells
 * of truth live. The [WhatsNewRepository] interface in `:domain` is unaffected; tests can
 * substitute a fake without touching `:shared`.
 *
 * **Import-alias `as Legacy*`** — same readability posture as
 * [AboutRepositoryImpl] / [ThemeRepositoryImpl]. The aliases call out the strangler-fig
 * boundary at every reach. The local `WhatsNewFeature` symbol (rework `:domain`) does NOT
 * collide with `LegacyWhatsNewFeature` (`:shared/.../model/`) — they're different namespaces,
 * but the alias keeps the boundary visible at the mapper call sites.
 *
 * **Mapper posture for [LegacyWhatsNewFeature] → [WhatsNewFeature]**: the legacy data class
 * (post-KMP-port) carries the same field names as the rework `:domain` model — including
 * `imageResName: String?` and `imageResNameList: List<String>` (the legacy was already lifted
 * off Android `@DrawableRes Int` IDs during the KMP migration). The mapper is a field-by-field
 * projection; the only translation is `mediaType: String` (wire-format) → [MediaType] (enum)
 * via the same case-insensitive `valueOf(uppercase())` posture the legacy `WhatsNewViewModel`
 * uses, falling back to [MediaType.IMAGE] on any unknown variant.
 *
 * Wait — re-reading the legacy code: [legacyGetDefaultFeatures] returns
 * `List<LegacyWhatsNewFeature>` where [LegacyWhatsNewFeature.mediaType] is already the legacy
 * `MediaType` enum (typed, not stringly-typed). The mapper preserves the enum value by name —
 * `MediaType.valueOf(legacy.mediaType.name)` round-trips because the legacy enum's two variants
 * (IMAGE, VIDEO) are a subset of the rework enum's four (IMAGE, VIDEO, LIST, URL), so every legacy
 * name resolves. The remote-fetched path goes via [LegacyWhatsNewRemoteDataSource.getLocalizedFeature]
 * which produces a `LocalizedFeature` whose `mediaType: String` IS the wire-format string —
 * that's where the `valueOf(uppercase())` parse runs.
 *
 * **Language resolution (`DataStoreHelper.languageFlow.first()`)**: the route swap made this repo
 * the renderer of the live version-bump popup, so it resolves the user's stored language and
 * passes it to both `fetchWhatsNewFeatures(...)` and `getLocalizedFeature(...)` so all 10 locales
 * get translated copy (legacy `WhatsNewViewModel.getUserLanguageCode` parity). The fallback chain
 * in [LegacyWhatsNewRemoteDataSource.getLocalizedFeature] already handles unknown languages (drops
 * to `"en"` then to the first available), so a blank/unsupported code still produces correct copy;
 * `LANGUAGE_CODE_DEFAULT = "en"` remains the blank/error fallback.
 *
 * **Why `try/catch` on the `Result.fold` body, not on the remote call directly**: the legacy
 * [LegacyWhatsNewRemoteDataSource.fetchWhatsNewFeatures] already wraps its Ktor call in a
 * `try/catch` returning `Result.failure`. The outer `try/catch` here is a defensive net for
 * the LOCALIZATION step (`getLocalizedFeature(...)` is `try`-wrapped in the legacy VM because
 * map lookups can throw on malformed wire data); the rework keeps the same defensive posture —
 * a per-feature mapping failure is silently dropped (no logger is injected here) and the empty
 * result falls through to the default list. `CancellationException` is re-thrown by
 * `runCatchingCancellable`.
 *
 * **`@OptIn(ExperimentalTime::class)`** — `Clock.System.now().toEpochMilliseconds()` requires
 * the opt-in. Same posture as the legacy `WhatsNewViewModel`.
 *
 * **Lifecycle**: `single` in Koin (declared in `whatsNewReworkModule`). All four legacy
 * collaborators are `single`; a `factory` here would just re-wrap the same provider each
 * resolution — wasteful for a screen that opens once per visit.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the
 * per-host repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or
 * `:platform` — foundation `:ui` doesn't render images. The Ktor HTTP client used by
 * [LegacyWhatsNewRemoteDataSource] is the same client the rest of the app uses; no new
 * client, no new interceptors. No load-bearing risk.
 */
@OptIn(ExperimentalTime::class)
class WhatsNewRepositoryImpl(
    private val remoteDataSource: LegacyWhatsNewRemoteDataSource,
    private val prefs: LegacySharedPrefsHelper,
    private val appVersionProvider: LegacyAppVersionProvider,
    private val dataStore: LegacyDataStoreHelper,
) : WhatsNewRepository {

    override suspend fun getFeatures(): List<WhatsNewFeature> {
        // Resolve the user's stored language (legacy WhatsNewViewModel.getUserLanguageCode parity):
        // the route swap made this repo the renderer of the live version-bump popup, so all 10
        // locales must get localized copy, not English. getLocalizedFeature's en-then-first-available
        // fallback chain keeps unsupported codes safe.
        val languageCode = getUserLanguageCode()
        val result = remoteDataSource.fetchWhatsNewFeatures()
        return result.fold(
            onSuccess = { response ->
                if (response.features.isEmpty()) {
                    fallbackToDefaults()
                } else {
                    response.features.mapNotNull { remoteFeature ->
                        runCatchingCancellable {
                            val localized = remoteDataSource.getLocalizedFeature(
                                remoteFeature,
                                languageCode,
                            )
                            WhatsNewFeature(
                                title = localized.title,
                                description = localized.description,
                                mediaType = parseMediaType(localized.mediaType),
                                imageResName = localized.imageRes,
                                // Native parity: the remote wire field `imageResList` (surfaced
                                // as `localized.imageList`) carries image URL strings, NOT
                                // compose-resource names. Native's WhatsNewViewModel routes it
                                // straight into `imageUrlList` (rendered via Coil), and the rework
                                // :ui carousel reads only `imageUrlList`. So the URL list belongs
                                // in `imageUrlList`; `imageResNameList` (local-resource names) is
                                // empty on the remote path, matching native.
                                imageResNameList = emptyList(),
                                imageUrl = localized.imageUrl,
                                imageUrlList = localized.imageList,
                                videoUrl = localized.videoUrl,
                                isNew = localized.isNew,
                                version = localized.version,
                            )
                        }.getOrNull()
                    }.ifEmpty { fallbackToDefaults() }
                }
            },
            onFailure = { fallbackToDefaults() },
        )
    }

    override suspend fun markSeen() {
        prefs.putString(KEY_LAST_SHOWN_VERSION_NAME, appVersionProvider.versionName)
        prefs.putLong(KEY_LAST_SHOWN_TIMESTAMP, Clock.System.now().toEpochMilliseconds())
    }

    private suspend fun getUserLanguageCode(): String =
        runCatchingCancellable { dataStore.languageFlow.first().ifBlank { LANGUAGE_CODE_DEFAULT } }
            .getOrDefault(LANGUAGE_CODE_DEFAULT)

    private fun fallbackToDefaults(): List<WhatsNewFeature> =
        legacyGetDefaultFeatures().map { it.toRework() }

    private fun parseMediaType(wireValue: String): MediaType =
        runCatching { MediaType.valueOf(wireValue.uppercase()) }
            .getOrDefault(MediaType.IMAGE)

    private fun LegacyWhatsNewFeature.toRework(): WhatsNewFeature = WhatsNewFeature(
        title = title,
        description = description,
        mediaType = MediaType.valueOf(mediaType.name),
        imageResName = imageResName,
        imageResNameList = imageResNameList,
        imageUrl = imageUrl,
        imageUrlList = imageUrlList,
        videoUrl = videoUrl,
        isNew = isNew,
        version = version,
    )

    private companion object {
        const val LANGUAGE_CODE_DEFAULT = "en"

        const val KEY_LAST_SHOWN_VERSION_NAME = "whats_new_last_shown_version_name"
        const val KEY_LAST_SHOWN_TIMESTAMP = "whats_new_last_shown_timestamp"
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster154.staleKdocSweep.cascade,
 * Task #610, 2026-05-28): classified as follows after recursive symbol
 * verification (two-hundredth sibling of the cluster57-153 sweep —
 * CLOSING file of the wave-26 :data/repository misc cell-of-truth trio
 * 3-leaf batch alongside AdultContentClassifierImpl plus AboutRepository
 * Impl; CLOSES :data/repository misc trio 3/3 AND CLOSES :data/repository
 * tier FULLY SWEPT 27/27):
 *  (a) "WhatsNewRepository-strangler-fig-delegate-over-FOUR-:shared-
 *  legacy-facades + Phase-7.x.whatsnew-foundation + Highest-fan-out-into-
 *  :shared-of-any-rework-:data-impl-justified-by-the-legacy-WhatsNew
 *  ViewModel-owning-the-SAME-collaboration-which-this-slice-ports-verbatim
 *  -minus-the-should-show-gating-plus-language-localization-both-deferred
 *  + Four-legacy-collaborators-LegacyWhatsNewRemoteDataSource-Ktor-JSON-
 *  fetcher-returns-Result-WhatsNewResponse-with-structured-failure-handling
 *  -already-baked-in + LegacySharedPrefsHelper-multiplatform-settings-
 *  backed-prefs-helper-writes-the-two-mark-seen-keys-whats_new_last_shown
 *  _version_name-plus-whats_new_last_shown_timestamp-SAME-key-names-as-
 *  the-legacy-WhatsNewViewModel-so-marking-seen-on-either-route-round-
 *  trips-with-the-other + LegacyAppVersionProvider-expect-class-supplying
 *  -versionName-the-impl-reads-it-at-markSeen-time-not-at-construction-
 *  so-a-hot-reloaded-version-is-honoured + legacyGetDefaultFeatures-top-
 *  level-fun-fallback-list-when-the-remote-fetch-fails-or-the-response-
 *  is-empty-Currently-returns-emptyList-the-upstream-port-commented-out-
 *  the-actual-defaults-during-the-KMP-migration + SRP-contract-section-6-
 *  owns-ONE-rule-project-the-legacy-WhatsNew-collaboration-into-the-
 *  rework-WhatsNewRepository-contract + No-should-show-gating-deferred-to
 *  -Phase-7.x.whatsnew.gate + No-language-localization-deferred-to-Phase-
 *  7.x.whatsnew.i18n-hardcoded-en-here + No-UI-projection-no-analytics +
 *  The-:data-impl-is-a-pure-adapter + DIP-contract-section-6-depends-on-
 *  the-legacy-:shared-types-because-that-s-where-the-cells-of-truth-live
 *  + Import-alias-as-Legacy-same-readability-posture-as-AboutRepository
 *  Impl-ThemeRepositoryImpl + Mapper-posture-for-LegacyWhatsNewFeature-to
 *  -WhatsNewFeature-the-legacy-data-class-post-KMP-port-carries-the-same-
 *  field-names-as-the-rework-:domain-model-including-imageResName-String-
 *  nullable-and-imageResNameList-List-String + The-mapper-is-a-field-by-
 *  field-projection-the-only-translation-is-mediaType-String-wire-format-
 *  to-MediaType-enum-via-the-same-case-insensitive-valueOf-uppercase-
 *  posture-the-legacy-WhatsNewViewModel-uses + Why-en-hardcoded-not-Data
 *  StoreHelper.languageFlow.first-foundation-slice-defers-language-aware-
 *  localization + Why-try-catch-on-the-Result.fold-body-not-on-the-remote
 *  -call-directly-the-legacy-LegacyWhatsNewRemoteDataSource.fetchWhatsNew
 *  Features-already-wraps-its-Ktor-call-in-a-try-catch-returning-Result.
 *  failure + OptIn-ExperimentalTime-class-Clock.System.now-toEpochMilli
 *  seconds-requires-the-opt-in + Lifecycle-single-in-Koin-declared-in-
 *  whatsNewReworkModule + All-four-legacy-collaborators-are-single-a-
 *  factory-here-would-just-re-wrap-the-same-provider-each-resolution +
 *  Load-bearing-fixes-preserved-this-slice-does-NOT-touch-the-Coil-Image
 *  Loader-the-per-host-repo-registry-OkHttp-interceptor-AVIF-decoder-
 *  HighQualitySkiaImageDecoder-or-:platform" — LIVE-NOT-STALE plus
 *  FORECAST-NOT-YET-FULFILLED on both deferred Phase 7.x.whatsnew.gate
 *  (should-show gating) and Phase 7.x.whatsnew.i18n (language-aware
 *  localization, currently hardcoded LANGUAGE_CODE_DEFAULT = "en"; likely
 *  aligns with the larger Phase 10 i18n lift). Verified: strangler-fig
 *  delegate over FOUR :shared facades shipped (LegacyWhatsNewRemoteData
 *  Source + LegacySharedPrefsHelper + LegacyAppVersionProvider +
 *  legacyGetDefaultFeatures top-level fun). getFeatures() runs the
 *  remote.fetchWhatsNewFeatures("en") path; folds Result.success to the
 *  mapNotNull/runCatching localization+mapper pipeline OR (on
 *  empty/failure) to fallbackToDefaults() which calls
 *  legacyGetDefaultFeatures().map { it.toRework() }. markSeen() writes
 *  the two prefs keys (whats_new_last_shown_version_name +
 *  whats_new_last_shown_timestamp) — SAME key strings as the legacy
 *  WhatsNewViewModel, so round-trip semantics hold. The "appVersion read
 *  at markSeen time not construction time" stance honored — reads via
 *  appVersionProvider.versionName at the call site. parseMediaType
 *  (String wire-format) and toRework() (LegacyWhatsNewFeature → rework
 *  WhatsNewFeature, mediaType via valueOf(legacy.mediaType.name)) are
 *  both byte-for-byte aligned with the documented mapper posture. The
 *  hardcoded "en" via LANGUAGE_CODE_DEFAULT companion const matches the
 *  documented Phase 7.x.whatsnew.i18n deferral. The @OptIn(Experimental
 *  Time::class) opt-in honors the kotlin.time.Clock contract. Consumed by
 *  GetWhatsNewFeaturesUseCase + MarkWhatsNewSeenUseCase (cluster111
 *  sibling X) via the WhatsNewRepository interface; surfaced as
 *  state.features on WhatsNewViewModel and drives the rework WhatsNew
 *  HorizontalPager surface. CLOSING FILE of cluster154 — completes the
 *  wave-26 :data/repository misc cell-of-truth trio 3-leaf batch (3 of
 *  3) AND CLOSES :data/repository tier FULLY SWEPT 27/27. Wave-26
 *  cumulative tally: cluster151 closed :data/mapper tier (6/6 files),
 *  cluster152 closed :data/repository reader-state tier (5/5),
 *  cluster153 closed :data/repository complaint trio (3/3), cluster154
 *  closes :data/repository misc cell-of-truth trio (3/3). One
 *  classification. Original Phase 7.x.whatsnew foundation impl prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
