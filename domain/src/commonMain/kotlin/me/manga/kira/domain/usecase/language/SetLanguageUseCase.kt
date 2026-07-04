package me.manga.kira.domain.usecase.language

import me.manga.kira.domain.repository.LanguageRepository

/**
 * Persist the user's language selection.
 *
 * Phase 7.x.language rework. The rework `LanguageViewModel` injects this use case and invokes it
 * from `viewModelScope.launch` when the user taps a row in the picker. Fire-and-forget: the
 * upstream [ObserveSelectedLanguageUseCase] flow re-emits with the new code once the underlying
 * DataStore write commits — the picker's selected-row icon reflects the new state by virtue of
 * state-driven recomposition.
 *
 * Contract §6 SRP: owns ONE rule — "delegate to [LanguageRepository.setLanguage]". The pairing
 * with the platform locale switch (`core.locale.applyApplicationLocale`) lives in the `:data`
 * impl; this use case just forwards the IETF tag.
 *
 * Why a use case at all when this is a single-line pass-through: same rationale as the other
 * mutator use cases across the rework
 * ([me.manga.kira.domain.usecase.theme.SetAppThemeUseCase] /
 * [me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase] /
 * [me.manga.kira.domain.usecase.history.DeleteHistoryEntryUseCase]) — the VM depends on a
 * stable use-case interface, not on a repository method (DIP); future composition (e.g., emit an
 * analytics event on language change, or capture the previous-value for an "Undo" snackbar) lives
 * here, not in the VM.
 *
 * Constructor injection per contract §6 DIP — Koin binds it as a `factory` in
 * `languageReworkModule` (factory: stateless, cheap to construct, never shared).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster116.staleKdocSweep.cascade,
 * Task #572, 2026-05-28): the file-scope use-case manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (fifty-sixth sibling of the cluster57-115 sweep — closes the
 * wave-16 `:domain/usecase/language/` batch alongside ObserveSelected-
 * LanguageUseCase.kt plus GetSupportedLanguagesUseCase.kt):
 *  (a) "Phase 7.x.language rework — rework LanguageViewModel injects this
 *  use case plus invokes it from viewModelScope.launch when the user taps
 *  a row in the picker (fire-and-forget)" — LIVE-NOT-STALE. LanguageView-
 *  Model.kt L155 primary constructor binds `private val setLanguage:
 *  SetLanguageUseCase`; L170-172 `LanguageIntent.OnSelectLanguage rename-
 *  to viewModelScope.launch { setLanguage(intent.code) }` realization
 *  confirms the fire-and-forget posture; cluster106 sibling sweep (Task
 *  #562) verified the intent-launch posture.
 *  (b) "Fire-and-forget — upstream ObserveSelectedLanguageUseCase flow
 *  re-emits with the new code once the underlying DataStore write commits;
 *  the picker's selected-row icon reflects the new state by virtue of
 *  state-driven recomposition" — LIVE-NOT-STALE. LanguageRepositoryImpl
 *  `:data` impl verified at cluster23 sibling sweep (Task #479) — wraps
 *  legacy `SettingsRepository.setLanguage` (Preferences-DataStore write)
 *  plus the upstream `languageFlow` re-emits the new value on commit.
 *  (c) "Contract §6 SRP owns ONE rule — delegate to LanguageRepository.
 *  setLanguage; the pairing with the platform locale switch (`core.locale.
 *  applyApplicationLocale`) lives in the `:data` impl; this use case just
 *  forwards the IETF tag" — LIVE-NOT-STALE. L33 realization `repository.
 *  setLanguage(code)` single-line pass-through; the DataStore-write-plus-
 *  applyApplicationLocale pairing in LanguageRepositoryImpl `:data` impl
 *  verified at cluster23 sibling sweep — the platform-locale orchestration
 *  is correctly hosted at the impl boundary, not at the use case.
 *  (d) "Why a use case at all when this is a single-line pass-through —
 *  peer cross-ref to SetAppThemeUseCase plus SetSourceEnabledUseCase plus
 *  DeleteHistoryEntryUseCase; future composition — emit an analytics event
 *  on language change, or capture the previous-value for an Undo snackbar
 *  — lives here, not in the VM" — LIVE-NOT-STALE plus FORECAST-NOT-YET-
 *  FULFILLED. Peer mutator use cases verified at cluster115 sibling sweep
 *  (Task #571 — SetAppThemeUseCase) plus cluster26 sibling sweep (Task
 *  #482 — SetSourceEnabledUseCase) plus cluster112 sibling sweep (Task
 *  #568 — DeleteHistoryEntryUseCase). Recursive search for analytics-
 *  event-emission or previous-value-capture-for-Undo orchestration on
 *  this use case returns zero matches; the use case remains a single-line
 *  pass-through. Forecast posture preserved verbatim.
 *  (e) "Constructor injection per contract §6 DIP — Koin binds it as a
 *  factory in `languageReworkModule`" — LIVE-NOT-STALE. LanguageRework-
 *  Module.kt L127 `factory { SetLanguageUseCase(get()) }` realization
 *  confirms factory lifecycle (stateless, cheap to construct, never
 *  shared).
 *  Five classifications STAND on their own merits as a faithful Set-
 *  LanguageUseCase manifest. Original Phase 7.x.language-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
class SetLanguageUseCase(
    private val repository: LanguageRepository,
) {
    suspend operator fun invoke(code: String) = repository.setLanguage(code)
}
