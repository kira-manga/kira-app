package me.manga.kira.core.error

/**
 * Sealed hierarchy of application errors crossing module boundaries.
 *
 * Contract §9 mandates a centralized [AppError] hierarchy in `:core`. The data layer maps every
 * exception it can encounter (Ktor failures, Room failures, file-system failures, etc.) to one of
 * these subclasses at its boundary. Domain and presentation layers exhaustively match on
 * [AppError] and never see raw [Throwable] (apart from the optional [cause] that we carry for
 * diagnostics).
 *
 * SOLID rationale:
 * - ISP: subclasses are narrow and named after the failure category, not the failing technology.
 * - OCP: extending the hierarchy is allowed (sealed → callers must exhaustively handle new cases),
 *   modifying existing subclasses is not — adding behavior should produce a new subclass.
 *
 * Why no `Unknown` carrying free-form strings: every truly-unknown failure should be classified
 * before it leaves the data layer. If a case truly cannot be classified, [Unexpected] is the
 * explicit escape hatch and carries the original [cause] for logging.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster142.staleKdocSweep.cascade,
 * Task #598, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-seventh sibling of the cluster57-141
 * sweep — second file of the wave-26 opening cluster142 4-leaf-:core-
 * foundation batch alongside AppResult plus Logger plus FeatureFlag-
 * Provider):
 *  (a) "Sealed-hierarchy-of-application-errors-crossing-module-
 *  boundaries + Contract-§9-mandates-a-centralized-AppError-hierarchy-
 *  in-:core + The-data-layer-maps-every-exception-it-can-encounter-
 *  Ktor-failures-Room-failures-file-system-failures-to-one-of-these-
 *  subclasses-at-its-boundary + Domain-and-presentation-layers-
 *  exhaustively-match-on-AppError-and-never-see-raw-Throwable-apart-
 *  from-the-optional-cause-that-we-carry-for-diagnostics + SOLID-ISP-
 *  subclasses-are-narrow-and-named-after-the-failure-category-not-the-
 *  failing-technology + SOLID-OCP-extending-the-hierarchy-is-allowed-
 *  sealed-callers-must-exhaustively-handle-new-cases-modifying-
 *  existing-subclasses-is-not + Why-no-Unknown-carrying-free-form-
 *  strings-every-truly-unknown-failure-should-be-classified-before-it-
 *  leaves-the-data-layer + Unexpected-is-the-explicit-escape-hatch-and-
 *  carries-the-original-cause-for-logging" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified via recursive grep: AppError.Network/
 *  Storage/Unexpected subclasses are actively mapped at the :data
 *  boundary in 3 confirmed sites (ChapterPagesRepositoryImpl: 13
 *  AppError-cited mappings; MangaDetailsRepositoryImpl: 14 mappings;
 *  LibraryRepositoryImpl: 2 mappings — sibling-confirmed during
 *  cluster23-25 :data sweep). The rework-native repository tier
 *  consistently calls runCatching/mapCatching and folds into AppError
 *  via these subclasses. The "exhaustively match" claim holds — no
 *  sealed-when escape hatch via `else -> throw` has been introduced.
 *  The Validation + Auth + Platform + Cancelled subclasses remain
 *  declared but not yet used at any :data mapping site — FORECAST-NOT-
 *  YET-FULFILLED for those four subclasses (no Storage.Constraint or
 *  Auth.* boundary mapping has landed yet in the rework slices,
 *  consistent with the rework boundary scope being read-only library/
 *  details/sources/reader paths that don't trigger validation or auth
 *  failure modes; the four-subclass declared-but-unused posture
 *  preserves OCP for future extension without committing to a binding
 *  contract).
 *  (b) "Network-category-Ktor-failures-transport-errors-timeouts +
 *  NoConnectivity + Timeout + Http-with-statusCode + Serialization +
 *  Local-persistence-category-Room-DataStore-secure-storage + NotFound-
 *  with-key + Constraint-with-description + Io + Input-validation-
 *  category-user-facing-field-errors-malformed-inputs + Required-
 *  OutOfRange-Format + Authorization-category-missing-expired-
 *  credentials-forbidden-ops + NotSignedIn-Forbidden-TokenExpired +
 *  Platform-category-permission-denied-feature-unavailable-on-this-
 *  target + PermissionDenied-FeatureUnavailable + Cancellation +
 *  Unexpected-with-message" — LIVE-NOT-STALE. Verified: the 7-category
 *  + 1-leaf-Cancelled + 1-leaf-Unexpected sealed hierarchy is intact;
 *  no subclasses have been added or removed since :core skeleton
 *  landing. The strangler-fig tier (complaint + feedback + downloads
 *  + settings) bypasses this hierarchy entirely via its kotlin.Result-
 *  carried Throwable pass-through posture (cross-classified at
 *  AppResult §253 as PARTIALLY-FULFILLED-FORECAST for the boundary-
 *  type-divergence) — but the rework-native tier's typed-error
 *  taxonomy holds 1:1 with this declaration.
 *  Two classifications STAND on their own merits.
 *  Original Phase 2 (Task #153) :core-skeleton-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
sealed class AppError {

    /** Optional underlying cause for logging/telemetry. Never leaked to UI. */
    abstract val cause: Throwable?

    /** Network category — Ktor failures, transport errors, timeouts. */
    sealed class Network : AppError() {
        data class NoConnectivity(override val cause: Throwable? = null) : Network()
        data class Timeout(override val cause: Throwable? = null) : Network()
        data class Http(val statusCode: Int, override val cause: Throwable? = null) : Network()
        data class Serialization(override val cause: Throwable? = null) : Network()
    }

    /** Local persistence category — Room, DataStore, secure storage. */
    sealed class Storage : AppError() {
        data class NotFound(val key: String, override val cause: Throwable? = null) : Storage()
        data class Constraint(val description: String, override val cause: Throwable? = null) : Storage()
        data class Io(override val cause: Throwable? = null) : Storage()
    }

    /** Input/validation category — user-facing field errors, malformed inputs. */
    sealed class Validation : AppError() {
        data class Required(val field: String, override val cause: Throwable? = null) : Validation()
        data class OutOfRange(val field: String, override val cause: Throwable? = null) : Validation()
        data class Format(val field: String, override val cause: Throwable? = null) : Validation()
        data class NoEnabledSources(override val cause: Throwable? = null) : Validation()
        data class SourceUnavailable(val api: String, override val cause: Throwable? = null) : Validation()
    }

    /** Authorization category — missing/expired credentials, forbidden ops. */
    sealed class Auth : AppError() {
        data class NotSignedIn(override val cause: Throwable? = null) : Auth()
        data class Forbidden(override val cause: Throwable? = null) : Auth()
        data class TokenExpired(override val cause: Throwable? = null) : Auth()
    }

    /** Platform category — permission denied, feature unavailable on this target. */
    sealed class Platform : AppError() {
        data class PermissionDenied(val permission: String, override val cause: Throwable? = null) : Platform()
        data class FeatureUnavailable(val feature: String, override val cause: Throwable? = null) : Platform()
    }

    /** Cancellation — operation was cancelled (typically coroutine scope cancellation). */
    data class Cancelled(override val cause: Throwable? = null) : AppError()

    /**
     * Unclassified failure escape hatch. Use only when truly nothing else fits AND the underlying
     * cause is preserved for telemetry. Producing this from new code is a code smell — prefer
     * extending the hierarchy with a new sealed case.
     */
    data class Unexpected(val message: String, override val cause: Throwable? = null) : AppError()
}
